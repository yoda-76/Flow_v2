package com.flow.rt;

import com.flow.core.EventFactory;
import com.flow.core.FillEvent;
import com.flow.core.Intent;
import com.flow.journal.Json;
import com.motivewave.platform.sdk.order_mgmt.Order;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The automatic-real-order state machine (D-82/Q-11), extracted verbatim from
 * FlowRuntimeStudy 2026-09-26 (D-91) so it can be driven by a FakeBroker in
 * OrderGatewayTest/LiveOrderTrackerTest -- FlowRuntimeStudy itself cannot be
 * instantiated outside MotiveWave. Behavior is unchanged: the method bodies
 * are the ones that were live-verified on Sim (D-67/D-81/D-85-D-87), with
 * the Study's own fields replaced by the four injected dependencies below.
 *
 * Holds no OrderContext of its own -- every order goes through OrderGateway,
 * the sole holder (README hard rule), which the Study supplies (null before
 * onActivate / after onDeactivate, exactly as before).
 *
 * The Study keeps armDenied itself (its armed supplier and isLiveModeArmed()
 * read it); this class only ever SETS it, via denyArm.
 */
final class LiveOrderTracker {
  private final Supplier<OrderGateway> gateway;
  private final Supplier<PriceCodec> codec;
  private final Consumer<String> log;
  private final Consumer<String> record; // D-94: structured decision records (order_fill)
  private final Runnable denyArm;

  // liveOrderInFlight blocks a second automatic submission while the
  // entry's fill hasn't resolved yet -- deliberately conservative: a
  // flip/resize while an order is still working is skipped and journaled,
  // not attempted (see reconcileLive()'s javadoc).
  private volatile boolean liveOrderInFlight = false;
  private volatile int pendingBracketTargetPosition = 0;
  private volatile Integer pendingBracketStopTicks = null;
  private volatile Integer pendingBracketTargetTicks = null;
  private volatile String pendingBracketReason = null;
  // F-7 / F-11 (2026-09-28): what the runtime knew when the entry was submitted -- the signal tick (price, bid, ask,
  // times) and whether the strategy wants its stop/target distances re-anchored to the FILL. Used at the entry fill to
  // place the real bracket and to journal one `entry_execution` cost record per trade.
  private volatile boolean pendingAnchorToFill = false;
  private volatile SignalInfo pendingSignal = null;
  private volatile long pendingSubmitLocalMs = 0;
  // F-23 (2026-09-29, D-122): off (0) by default -- see ExternalConfig.entrySlippageCapTicks's own javadoc.
  private volatile int entrySlippageCapTicks = 0;
  void setEntrySlippageCapTicks(int ticks) { this.entrySlippageCapTicks = ticks; }
  /** Only set while a LIMIT entry (the slippage cap) is outstanding, so a stale scheduled timeout can recognize it has been superseded. */
  private volatile Order pendingEntryOrder;

  /** The tick that fired an entry intent, as the runtime saw it. Any field may be null (a non-tick trigger). */
  record SignalInfo(Integer priceTicks, Integer bidTicks, Integer askTicks, long eventTimeMs, long receivedLocalMs) {}
  // 2026-09-21: a bracket leg belongs to the position that created it and
  // must never outlive it -- tracked by reference here rather than assumed
  // to self-cancel via broker-side OCO, which OrderContext's own method
  // list confirms doesn't exist at this SDK level. Cleared (and the
  // survivor cancelled) when one leg fills naturally (onOrderFilled) --
  // the ONLY path that closes a position now, since the same-day rework
  // removed reconcileLive's software-side close entirely (SL/TP is
  // bracket-only; see reconcileLive()'s closing-branch javadoc).
  private volatile Order restingStopOrder = null;
  private volatile Order restingTargetOrder = null;
  // Populated immediately before WE call cancelOrders() ourselves, so the
  // resulting onOrderCancelled callback for that same order id is
  // recognized as expected cleanup, not a broker-side anomaly worth
  // disarming over (exactly the false-positive the near-miss's disarm
  // reasoning hit -- it blamed the wrong order for a cancellation that
  // was actually this sweep).
  private final java.util.Set<String> selfCancelledOrderIds =
      java.util.Collections.synchronizedSet(new java.util.HashSet<>());

  LiveOrderTracker(Supplier<OrderGateway> gateway, Supplier<PriceCodec> codec,
      Consumer<String> log, Consumer<String> record, Runnable denyArm) {
    this.gateway = gateway;
    this.codec = codec;
    this.log = log;
    this.record = record;
    this.denyArm = denyArm;
  }

  // ---- F-1/F-2 (2026-09-28): flatten decisions read BOTH positions, and every kill-switch / double-fill flatten is
  // ---- verified afterwards. Live finding L-1: a stop leg filled in the same millisecond the kill switch read the
  // ---- study's position (still 1) and sent a close -> the account went SHORT 1, unprotected, and nothing noticed.

  /**
   * How to run something later, off the calling thread: (task, delayMs). Null in tests that do not exercise the
   * post-flatten verification; the Study supplies a daemon scheduler. Never called with the tracker's own monitor
   * held, and the task itself is fully guarded.
   */
  private volatile java.util.function.BiConsumer<Runnable, Long> scheduler;
  void setScheduler(java.util.function.BiConsumer<Runnable, Long> scheduler) { this.scheduler = scheduler; }

  /** Runs after every fill callback (the Study refreshes the risk chain's account snapshot here). Failure-proof by contract of the caller. */
  private volatile Runnable afterFillHook;
  void setAfterFillHook(Runnable hook) { this.afterFillHook = hook; }

  /** Verification schedule after a flatten: 1.5 s, then 3 s, then 5 s later -- three chances to notice and undo a wrong-way fill. */
  static final long[] VERIFY_DELAYS_MS = {1_500L, 3_000L, 5_000L};

  private enum Act { NONE, CLOSE, HOLD }

  // ---- 2026-09-28 (user: "only simulated trades will affect anything related to the system") -------------------
  // Every order and fill carries getAccountId(); every Sim fill so far reported "simulated". An order or fill naming
  // any OTHER account is never processed: the runtime disarms, locks all order sending (OrderGateway's shared lock),
  // journals it and raises an ALERT. It does NOT try to flatten that account -- orders on a non-simulated account are
  // exactly what is forbidden.
  private volatile Runnable lockAction;
  void setLockAction(Runnable lockAction) { this.lockAction = lockAction; }
  private volatile boolean unknownAccountLogged = false;

  // ---- C1 (2026-09-29, D-120): tell the strategy about a REAL fill, through the normal event stream -------------

  /** How to publish a FillEvent: eventTimeMs, then the factory Sequencer.publish() needs (see FillEvent's javadoc). */
  interface FillPublisher {
    void publish(long eventTimeMs, EventFactory<FillEvent> factory);
  }

  private volatile FillPublisher publishFill; // nullable: dry-run/replay/tests that don't need it just don't set one
  void setPublishFill(FillPublisher publishFill) { this.publishFill = publishFill; }

  /**
   * Publishes ENTRY/STOP/TARGET fills of the tracker's own bracket only -- an "untracked" fill (a manual trade, the
   * platform's own close) is not the strategy's own action; F-3's account watch surfaces those instead. Never
   * throws: called from recordFill(), already inside a try/catch that only logs.
   */
  private void publishFillEvent(Order order, String role, OrderGateway gw) {
    FillPublisher pub = publishFill;
    if (pub == null || "untracked".equals(role)) return;
    PriceCodec c = codec.get();
    if (c == null) return;
    Float px = avgFillPriceOf(order);
    if (px == null || px.isNaN() || px == 0f) return;
    Integer filled = filledOf(order);
    if (filled == null) return;
    Boolean isBuy;
    try {
      isBuy = order.isBuy();
    } catch (RuntimeException e) {
      return;
    }
    FillEvent.Role r = switch (role) {
      case "entry" -> FillEvent.Role.ENTRY;
      case "stop" -> FillEvent.Role.STOP;
      case "target" -> FillEvent.Role.TARGET;
      default -> FillEvent.Role.OTHER;
    };
    int fillTicks = c.toTicks(px);
    int posAfter = gw.currentPosition();
    String orderId = String.valueOf(order.getOrderId());
    Long fillTimeMs = safeLastFillTime(order);
    long eventTimeMs = fillTimeMs != null && fillTimeMs > 0 ? fillTimeMs : System.currentTimeMillis();
    pub.publish(eventTimeMs, (seq, et, rt) -> new FillEvent(seq, et, rt, orderId, r, isBuy, fillTicks, filled, posAfter));
  }

  private static Long safeLastFillTime(Order order) {
    try {
      return order.getLastFillTime();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private boolean nonSimulatedEvent(Order order, String what) {
    String id;
    try { id = order.getAccountId(); } catch (RuntimeException e) { id = null; }
    com.flow.core.AccountPolicy.Kind kind = com.flow.core.AccountPolicy.classify(id);
    if (kind == com.flow.core.AccountPolicy.Kind.SIMULATED) return false;
    if (kind == com.flow.core.AccountPolicy.Kind.UNKNOWN) {
      if (!unknownAccountLogged) {
        unknownAccountLogged = true;
        log.accept("ACCOUNT_ID_UNKNOWN the platform reported no account id on a " + what + " -- allowed (a Sim fill always carries one; watch for this)");
      }
      return false;
    }
    denyArm.run();
    Runnable la = lockAction;
    if (la != null) la.run();
    log.accept("NON_SIM_ACCOUNT_EVENT " + what + " on account '" + id + "', order " + order
        + " -- disarmed and ALL order sending locked; this event was NOT processed. Only the simulated account may be traded.");
    try {
      record.accept(Json.object().field("type", "non_sim_account_event").field("t", System.currentTimeMillis())
          .field("what", what).field("accountId", String.valueOf(id)).field("orderId", String.valueOf(order.getOrderId())).build());
    } catch (RuntimeException e) {
      log.accept("NON_SIM_RECORD_FAILED " + e);
    }
    return true;
  }

  /**
   * Whether a flatten should send a close, from the STUDY's position (getPosition) AND the ACCOUNT's
   * (getAccountPosition) -- they can disagree (L-2: the study's view did not show a manual close for two minutes;
   * L-1: it still showed 1 while a leg fill was being delivered). closeAtMarket() closes the study's position, so
   * it is only sent when both readings agree the account holds the same non-flat position; a tracked leg that has
   * already filled means the position is closed whatever the readings say; any other disagreement is HOLD -- send
   * nothing, leave the bracket working, and let the verification (or the next retry) look again.
   */
  private Act decide(OrderGateway gw, boolean legFilled, String what) {
    if (legFilled) {
      log.accept(what + " a tracked bracket leg has already filled -- the position is closed, no close sent");
      return Act.NONE;
    }
    int strat = gw.currentPosition();
    int acct = gw.accountPosition();
    if (strat == 0 && acct == 0) return Act.NONE;
    if (strat != 0 && acct != 0 && Integer.signum(strat) == Integer.signum(acct)) return Act.CLOSE;
    log.accept("POSITION_SOURCES_DISAGREE " + what + " study=" + strat + " account=" + acct
        + " -- no close sent (a stale read here is how L-1's naked short happened); verifying");
    return Act.HOLD;
  }

  private boolean trackedLegFilled() {
    return legFilled(restingStopOrder) || legFilled(restingTargetOrder);
  }

  private static boolean legFilled(Order o) {
    if (o == null) return false;
    try { return o.isFilled(); } catch (RuntimeException e) { return false; }
  }

  private void scheduleFlatVerification(String what, int attempt) {
    java.util.function.BiConsumer<Runnable, Long> s = scheduler;
    if (s == null || attempt >= VERIFY_DELAYS_MS.length) return;
    s.accept(() -> verifyFlat(what, attempt), VERIFY_DELAYS_MS[attempt]);
  }

  /**
   * F-1: after a kill-switch / double-fill flatten, look again. Flat both ways: done (and sweep any stray leg).
   * Both readings agree the account is still (or now, wrong-way) non-flat: send the close again -- this is exactly
   * L-1's short 1. Readings disagree: no order, alert, look again. Still not flat after the last look: ALERT.
   * Fully guarded: a failure here is logged, never thrown.
   */
  void verifyFlat(String what, int attempt) {
    try {
      OrderGateway gw = gateway.get();
      if (gw == null) {
        log.accept("FLATTEN_VERIFY_SKIPPED " + what + " -- no gateway (study deactivated)");
        return;
      }
      int strat = gw.currentPosition();
      int acct = gw.accountPosition();
      boolean last = attempt >= VERIFY_DELAYS_MS.length - 1;
      if (strat == 0 && acct == 0) {
        if (gw.hasRestingOrders()) {
          log.accept("FLATTEN_VERIFIED " + what + " flat; sweeping stray orders " + gw.cancelAllOrders(what + " verification"));
        } else {
          log.accept("FLATTEN_VERIFIED " + what + " account flat, nothing resting");
        }
        return;
      }
      if (strat != 0 && acct != 0 && Integer.signum(strat) == Integer.signum(acct)) {
        log.accept("FLATTEN_CORRECTING " + what + " study=" + strat + " account=" + acct + " -- still not flat: "
            + gw.cancelAllAndClose(what + " verification: still holding " + acct));
      } else {
        log.accept("FLATTEN_VERIFY_DISAGREE " + what + " study=" + strat + " account=" + acct + " -- no order sent");
      }
      if (last) {
        log.accept("FLATTEN_NOT_CONFIRMED " + what + " study=" + strat + " account=" + acct
            + " after " + VERIFY_DELAYS_MS.length + " looks -- CHECK THE ACCOUNT BY HAND");
      } else {
        scheduleFlatVerification(what, attempt + 1);
      }
    } catch (RuntimeException e) {
      log.accept("FLATTEN_VERIFY_FAILED " + what + " " + e);
    }
  }

  // ---- F-22 (2026-09-29, D-121, the user's own idea) ---------------------------------------------------------
  //
  // The tick-based check above only sees the price the instant data resumed and the price now -- a spike that
  // reached or crossed a level and came BACK inside that window is invisible to it. This is a second, coarser,
  // DELAYED look at the historical BAR RANGE across the whole gap, only taken when the quick check said KEEP (a
  // flatten has already happened otherwise -- nothing more to add). MotiveWave backfills historical bars
  // automatically after a reconnect (seen live), but how long that takes is UNVERIFIED, so this is a single look,
  // not a retry loop: if the bars aren't there yet, it says so and KEEP stands.

  /** null if bars covering [startMs, endMs] are not available yet; else {lowTicks, highTicks} across that range. */
  interface GapRangeLookup {
    int[] lookup(long startMs, long endMs);
  }

  private volatile GapRangeLookup gapRangeLookup;
  void setGapRangeLookup(GapRangeLookup lookup) { this.gapRangeLookup = lookup; }

  /** How long after the quick check's KEEP to look at the bar range once, giving MotiveWave's backfill time to land. */
  static final long GAP_RANGE_CHECK_DELAY_MS = 5_000L;

  private void scheduleGapRangeCheck(long gapStartMs, long gapEndMs) {
    GapRangeLookup lookup = gapRangeLookup;
    java.util.function.BiConsumer<Runnable, Long> s = scheduler;
    if (lookup == null || s == null) return;
    s.accept(() -> checkGapRange(gapStartMs, gapEndMs), GAP_RANGE_CHECK_DELAY_MS);
  }

  void checkGapRange(long gapStartMs, long gapEndMs) {
    try {
      GapRangeLookup lookup = gapRangeLookup;
      if (lookup == null) return;
      Order stop = restingStopOrder;
      Order target = restingTargetOrder;
      OrderGateway gw = gateway.get();
      if (stop == null || target == null || gw == null) return; // already resolved by then (a leg filled, or the quick check already flattened)
      int strat = gw.currentPosition();
      int acct = gw.accountPosition();
      if (strat == 0 && acct == 0) return; // already flat
      Float stopPx = stop.getStopPrice();
      Float targetPx = target.getLimitPrice();
      if (stopPx == null || targetPx == null) return;
      int[] range = lookup.lookup(gapStartMs, gapEndMs);
      if (range == null) {
        log.accept("GAP_RANGE_UNAVAILABLE bars covering the outage are not backfilled yet -- the earlier KEEP stands");
        return;
      }
      PriceCodec c = codec.get();
      if (c == null) return;
      boolean isLong = stop.isSell();
      String why = crossedLevel(isLong, c.fromTicks(range[0]), stopPx, targetPx);
      if (why == null) why = crossedLevel(isLong, c.fromTicks(range[1]), stopPx, targetPx);
      if (why == null) {
        log.accept("GAP_RANGE_KEEP the historical range [" + range[0] + "," + range[1]
            + "] ticks never reached the stop " + stopPx + " or the target " + targetPx);
        return;
      }
      Act act = decide(gw, false, "GAP_RANGE");
      if (act == Act.CLOSE) {
        resetForKillSwitch();
        log.accept("GAP_RANGE_FLATTEN " + why + " (found in the bar range after the fact) -- "
            + gw.cancelAllAndClose("market-data outage (bar range): " + why));
        scheduleFlatVerification("GAP_RANGE", 0);
      } else {
        log.accept("GAP_RANGE_FLATTEN_HELD " + why + " -- no close sent (readings disagree or flat); verifying");
        scheduleFlatVerification("GAP_RANGE", 0);
      }
    } catch (RuntimeException e) {
      log.accept("GAP_RANGE_CHECK_FAILED " + e);
    }
  }

  /**
   * F-4: the study was re-activated (MotiveWave reuses the instance). Re-activation only arms when the account is
   * flat with nothing resting (D-24), so any leg / in-flight entry / self-cancel bookkeeping left over from before
   * the gap is stale by construction.
   */
  void resetForReactivation() {
    resetForKillSwitch();
    selfCancelledOrderIds.clear();
  }

  // ---- read-only state, for tests and the Study ------------------------

  boolean orderInFlight() { return liveOrderInFlight; }
  Order restingStop() { return restingStopOrder; }
  Order restingTarget() { return restingTargetOrder; }

  /**
   * The daily-loss kill switch (D-85) has just flattened everything: forget
   * the bracket legs and any in-flight entry, so no callback that follows is
   * mistaken for live state. Was inline in the Study's kill-switch lambda.
   */
  void resetForKillSwitch() {
    restingStopOrder = null;
    restingTargetOrder = null;
    clearPendingLiveOrder();
  }

  /**
   * The daily-loss kill switch's account side (moved here from the Study so it can be tested; code review A1).
   * Forgets the legs and any in-flight entry, then acts on the account ONLY when this session is explicitly in
   * live Sim mode (Mode SIM_LIVE + Armed -- the same gate as the session flatten). In DRY_RUN the risk chain can
   * still reach the daily-loss limit on dry-run "positions" when Armed is ticked; that must never send an order
   * (configuration.md: "DRY_RUN never touches an order"), so it is journaled and nothing is sent. Like the flatten
   * it never sends closeAtMarket to a flat account (its behaviour there is unconfirmed): flat + orders working ->
   * cancel only; flat + nothing -> nothing. The caller disarms first. The log line keeps the KILL_SWITCH_TRIGGERED
   * prefix the nightly report looks for.
   */
  void onKillSwitch(String reason, boolean liveOrdersRequested) {
    boolean legFilled = trackedLegFilled(); // F-1: read BEFORE the tracker forgets its legs
    resetForKillSwitch();
    if (!liveOrdersRequested) {
      log.accept("KILL_SWITCH_TRIGGERED reason=" + reason + " -- not SIM_LIVE+Armed: disarmed, no order sent");
      return;
    }
    OrderGateway gw = gateway.get();
    if (gw == null) {
      log.accept("KILL_SWITCH_TRIGGERED reason=" + reason + " (no gateway -- nothing to flatten)");
      return;
    }
    Act act = decide(gw, legFilled, "KILL_SWITCH");
    boolean resting = gw.hasRestingOrders();
    if (act == Act.HOLD) {
      log.accept("KILL_SWITCH_TRIGGERED reason=" + reason + " -- study/account positions disagree: no order sent, "
          + "bracket left working, verifying");
    } else if (act == Act.NONE && !resting) {
      log.accept("KILL_SWITCH_TRIGGERED reason=" + reason + " -- account already flat, nothing resting: no order sent");
    } else {
      log.accept("KILL_SWITCH_TRIGGERED " + (act == Act.NONE ? gw.cancelAllOrders(reason) : gw.cancelAllAndClose(reason)));
    }
    scheduleFlatVerification("KILL_SWITCH", 0); // F-1: the wrong-way fill L-1 produced is caught here
  }

  /**
   * D-92: session-end flatten. Idempotent -- when the account is already flat with nothing resting it does
   * nothing (never sends closeAtMarket to a flat account), so Pipeline can call it every few seconds for the
   * whole window and a rejected close simply gets retried. Otherwise it is the kill switch's blunt sweep
   * (close, then cancel everything) and the tracker forgets its legs and any in-flight entry. Unlike the kill
   * switch it does NOT disarm: the study stays armed and trades again after the reopen (entries are blocked
   * by the risk chain until then). Returns true iff it sent a flatten.
   */
  boolean flattenForSessionEnd(String reason) {
    OrderGateway gw = gateway.get();
    if (gw == null) return false;
    boolean legFilled = trackedLegFilled();
    Act act = decide(gw, legFilled, "SESSION_FLATTEN");
    if (act == Act.HOLD) return false; // the two readings disagree -- Pipeline retries every few seconds
    if (act == Act.NONE && !gw.hasRestingOrders()) return false;
    resetForKillSwitch();
    // Flat but orders still working: cancel only -- never send a close to a flat account.
    log.accept("SESSION_FLATTEN " + (act == Act.NONE ? gw.cancelAllOrders(reason) : gw.cancelAllAndClose(reason)));
    return true;
  }

  /**
   * Real-order counterpart to OrderGateway.reconcileDryRun() -- diffs the
   * intent against the account's actual position (gw.currentPosition()),
   * not the strategy's own optimistic belief about it. Only handles
   * OPENING (flat -> non-flat): submits the minimal entry order, then
   * stashes the intent's stop/target so onOrderFilled() can attach the
   * bracket once the entry is confirmed filled -- same submit-entry-then-
   * bracket-on-fill sequence D-67/D-81 proved out, just driven by the
   * strategy's own intents instead of a hardcoded one-shot test.
   *
   * CLOSING (non-flat -> flat) is deliberately NOT acted on here anymore
   * (2026-09-21 rework, after two live near-misses in one session): the
   * real bracket is now the ONLY thing that ever closes a position on a
   * stop/target level, full stop. Before this rework, this method also
   * submitted a fresh close order whenever the strategy's own software-
   * side check independently decided the position should be flat --
   * which raced the real bracket doing the same job through a different
   * order entirely, and both incidents were exactly that race. The
   * strategy's "stop_hit"/"target_hit" intent is still computed and still
   * journaled (Pipeline's intent_changed record, unconditionally) for
   * audit, but it no longer causes any real action. See the closing
   * branch below for the exact reasoning.
   *
   * Also deliberately conservative on OPENING, same as before: a direct
   * flip or a resize while a bracket is still resting is skipped and
   * journaled rather than risking a new order stacked on state that
   * hasn't settled -- neither strategy shipped so far ever asks for that
   * (both go through flat first via their own SEARCHING/IN_POSITION state
   * machines), so this is a real, flagged scope limit, not a silent
   * shortcut.
   *
   * Known gap, flagged rather than silently ignored: a real fill is never
   * fed back to the strategy (FlowStrategy.onFill() has no caller anywhere
   * yet -- see its own placeholder note). If the broker rejects or cancels
   * the entry, this class disarms (see onOrderRejected/onOrderCancelled)
   * rather than let the strategy's own optimistic position belief diverge
   * from the real, still-flat account.
   */
  /**
   * Code review B3: why the last reconcileLive() call did NOT act on an intent that wanted to OPEN or CHANGE a
   * position (an order still in flight, resting orders, a direct flip), or null if it acted / there was nothing to do
   * / it was a close (closes are bracket-only by design, not a refusal). Read right after reconcileLive(), on the
   * same thread.
   */
  String lastEntryRefusal() {
    return lastEntryRefusal;
  }

  private String lastEntryRefusal = null;

  String reconcileLive(Intent intent, OrderGateway gw) {
    return reconcileLive(intent, gw, null);
  }

  String reconcileLive(Intent intent, OrderGateway gw, SignalInfo signal) {
    lastEntryRefusal = null;
    if (liveOrderInFlight) {
      if (intent.targetPosition() != gw.currentPosition()) lastEntryRefusal = "previous real order still in flight";
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "previous real order still in flight (unresolved fill/reject/cancel)")
          .build();
    }
    int currentPosition = gw.currentPosition();
    int target = intent.targetPosition();
    if (target == currentPosition) {
      return Json.object().field("type", "reconcile_live_noop")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("currentPosition", currentPosition).build();
    }
    boolean opening = currentPosition == 0 && target != 0;
    boolean closing = currentPosition != 0 && target == 0;
    if (!opening && !closing) {
      lastEntryRefusal = "direct flip/resize not auto-handled";
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "direct flip/resize (from " + currentPosition + " to " + target
              + ") not auto-handled -- see reconcileLive()'s javadoc")
          .build();
    }
    if (opening && gw.accountPosition() != 0) {
      // F-2: the study's own position says flat but the ACCOUNT holds something (a manual trade, the platform's own
      // close, a fill the study never saw). Stacking an entry on it is how a small loss becomes a large one (D-24).
      lastEntryRefusal = "account holds " + gw.accountPosition() + " the study did not open";
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "account position is " + gw.accountPosition() + " while the study's own position is flat -- refusing to stack a new entry on it")
          .build();
    }
    if (opening && gw.hasRestingOrders()) {
      lastEntryRefusal = "resting orders on the account";
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "resting orders found on the account -- refusing to stack a new entry on them")
          .build();
    }

    if (closing) {
      // 2026-09-21 rework, after two live near-misses in one session: SL/TP
      // is bracket-only now, full stop -- the strategy's own software-side
      // stop/target re-check and the real resting bracket were two
      // independent things both trying to close the same position, and
      // both incidents were exactly that race. The strategy's
      // "stop_hit"/"target_hit" intent is kept for audit (already
      // journaled by Pipeline's intent_changed record regardless of what
      // this method does with it) but reconcileLive takes no real action
      // on it anymore -- no cancel, no close, nothing. The bracket is the
      // only thing that ever closes a position on a stop/target level.
      // Universal events (the daily-loss kill switch, D-85; a future
      // session-end auto-flatten) are a deliberately different case --
      // those bypass the strategy's intent stream entirely and sweep
      // everything unconditionally, which is unaffected by this change.
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "sl/tp is bracket-only -- strategy's own close intent logged, not re-executed")
          .build();
    }

    int delta = target - currentPosition; // opening only, from here on
    liveOrderInFlight = true;
    pendingBracketTargetPosition = target;
    pendingBracketStopTicks = intent.stopPriceTicks();
    pendingBracketTargetTicks = intent.targetPriceTicks();
    pendingBracketReason = intent.reason();
    pendingAnchorToFill = intent.anchorToFill();
    pendingSignal = signal;
    pendingSubmitLocalMs = System.currentTimeMillis();
    boolean isBuy = delta > 0;
    int qty = Math.abs(delta);
    try {
      // F-23: a capped LIMIT entry only when the cap is on AND the signal actually carries a touch to cap from;
      // otherwise (the default) exactly today's market order, unchanged.
      int cap = entrySlippageCapTicks;
      if (cap > 0 && signal != null && signal.bidTicks() != null && signal.askTicks() != null) {
        PriceCodec c = codec.get();
        if (c != null) {
          int limitTicks = isBuy ? signal.askTicks() + cap : signal.bidTicks() - cap;
          float limitPrice = gw.roundToTick((float) c.fromTicks(limitTicks));
          OrderGateway.EntryOrder eo = gw.submitRealLimitEntry(isBuy, qty, limitPrice, intent.reason(), signal, pendingSubmitLocalMs);
          pendingEntryOrder = eo.order();
          scheduleEntryTimeout(eo.order());
          return eo.journalLine();
        }
      }
      return gw.submitRealEntry(isBuy, qty, intent.reason(), signal, pendingSubmitLocalMs);
    } catch (IllegalStateException refused) {
      // The gateway refused (an order on a non-simulated account, or the order lock): nothing was sent.
      clearPendingLiveOrder();
      denyArm.run();
      Runnable la = lockAction;
      if (la != null) la.run();
      lastEntryRefusal = "order refused: " + refused.getMessage();
      log.accept("NON_SIM_ACCOUNT_EVENT entry refused before sending -- " + refused.getMessage() + " -- disarmed, order sending locked");
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "entry refused: " + refused.getMessage()).build();
    }
  }

  // ---- F-23 (2026-09-29, D-122): give up on a capped limit entry that never filled -------------------------------

  /** How long a capped limit entry is left resting before this gives up on it and tries again on the next signal. */
  static final long ENTRY_LIMIT_TIMEOUT_MS = 5_000L;

  private void scheduleEntryTimeout(Order entry) {
    java.util.function.BiConsumer<Runnable, Long> s = scheduler;
    if (s == null) return;
    s.accept(() -> onEntryTimeout(entry), ENTRY_LIMIT_TIMEOUT_MS);
  }

  /**
   * The entry hasn't filled after ENTRY_LIMIT_TIMEOUT_MS: cancel it (marked self-cancelled first, exactly like a
   * bracket-leg sibling cancel, so the resulting onOrderCancelled callback is recognized as expected, not a
   * disarm-worthy anomaly) and journal `entry_missed`. If it resolved (filled, or already cancelled/rejected) in the
   * meantime, cancelIfActive() returns null and nothing further happens here -- its own callback already handled it.
   * A superseded reference (a fresh entry since, or this one already cleared) is a silent no-op.
   */
  void onEntryTimeout(Order entry) {
    try {
      if (entry == null || entry != pendingEntryOrder || !entry.isActive()) return;
      OrderGateway gw = gateway.get();
      if (gw == null) return;
      String id = entry.getOrderId();
      if (id != null) selfCancelledOrderIds.add(id);
      String line = gw.cancelIfActive(entry, "entry", "slippage cap timeout: unfilled after " + ENTRY_LIMIT_TIMEOUT_MS + "ms");
      if (line == null) {
        if (id != null) selfCancelledOrderIds.remove(id); // resolved between the isActive() check and the cancel -- no callback coming for this
        return;
      }
      clearPendingLiveOrder();
      log.accept("ENTRY_MISSED " + line);
      record.accept(Json.object().field("type", "entry_missed").field("t", System.currentTimeMillis())
          .field("orderId", String.valueOf(id)).field("reason", "slippage cap timeout").build());
    } catch (RuntimeException e) {
      log.accept("ENTRY_TIMEOUT_CHECK_FAILED " + e);
    }
  }

  /** Clears in-flight/pending-bracket state -- shared by onOrderRejected/onOrderCancelled below. */
  private void clearPendingLiveOrder() {
    liveOrderInFlight = false;
    pendingBracketTargetPosition = 0;
    pendingBracketStopTicks = null;
    pendingBracketTargetTicks = null;
    pendingBracketReason = null;
    pendingAnchorToFill = false;
    pendingSignal = null;
    pendingSubmitLocalMs = 0;
    pendingEntryOrder = null;
  }

  /**
   * D-82/Q-11, reworked 2026-09-21 after a live near-miss. Three distinct
   * cases, in order:
   *
   * 1. This fill is our tracked entry (liveOrderInFlight) -- attach the
   *    bracket if the intent that opened it wanted one (targetPosition
   *    != 0), stash both leg Order refs in restingStopOrder/
   *    restingTargetOrder for later cancellation. A closing fill
   *    (targetPosition == 0, set by reconcileLive's closing branch) needs
   *    no bracket -- logged, not an error.
   * 2. This fill is one of the CURRENT position's own tracked bracket
   *    legs resolving naturally -- cancel the sibling explicitly rather
   *    than assume broker-side OCO cleaned it up. This is the fix: the
   *    near-miss happened because nothing occupied this branch at all
   *    (liveOrderInFlight was already false by the time a leg fills), so
   *    a leg could sit resting indefinitely after its position was
   *    already closed some other way.
   * 3. Neither -- log only.
   *
   * Runs on whatever thread MotiveWave calls this hook on (proven fine
   * for case 1's call sequence by D-81's live retest); deliberately does
   * NOT touch pipeline/feature/strategy state, only OrderGateway (which
   * itself only wraps ctx) -- see reconcileLive()'s onFill() gap note for
   * why strategy state is out of scope here.
   */
  void onOrderFilled(Order order) {
    log.accept("ORDER_FILLED " + order);
    if (nonSimulatedEvent(order, "fill")) return;
    recordFill(order);
    Runnable hook = afterFillHook;
    if (hook != null) {
      try {
        hook.run();
      } catch (RuntimeException e) {
        log.accept("AFTER_FILL_HOOK_FAILED " + e);
      }
    }

    if (liveOrderInFlight) {
      // D-99 (plumbingEdgeCases.md section 9): the entry is resolved only when it is COMPLETELY filled. A
      // callback for a part-filled entry is ignored -- still in flight, no bracket yet -- so this works whether
      // the platform calls onOrderFilled per slice or only on completion (unconfirmed, D-99). Waiting rather
      // than bracketing the slice avoids resizing a bracket as slices arrive.
      if (!entryFullyFilled(order)) {
        log.accept("LIVE_ENTRY_PARTIAL_FILL filled=" + filledOf(order) + " of " + quantityOf(order)
            + " -- waiting for the full fill before bracketing");
        return;
      }
      int targetPosition = pendingBracketTargetPosition;
      Integer stopTicks = pendingBracketStopTicks;
      Integer targetTicks = pendingBracketTargetTicks;
      String reason = pendingBracketReason;
      boolean anchorToFill = pendingAnchorToFill;
      SignalInfo signal = pendingSignal;
      long submitLocalMs = pendingSubmitLocalMs;
      clearPendingLiveOrder();
      PriceCodec c = codec.get();
      OrderGateway gw = gateway.get();
      Integer fillTicks = null;
      if (c != null && c.hasAnchor()) {
        Float fp = avgFillPriceOf(order);
        if (fp != null && fp != 0f && !fp.isNaN()) fillTicks = c.toTicks(fp);
      }
      journalEntryExecution(order, targetPosition, signal, fillTicks, submitLocalMs);
      if (targetPosition == 0 || stopTicks == null || targetTicks == null) {
        log.accept("LIVE_FILL_NO_BRACKET targetPosition=" + targetPosition
            + " stopTicks=" + stopTicks + " targetTicks=" + targetTicks);
        return;
      }
      if (c == null || gw == null) {
        log.accept("LIVE_BRACKET_SKIPPED reason=codec_or_gateway_unavailable");
        return;
      }
      boolean closingIsBuy = targetPosition < 0; // a short position closes with a BUY bracket
      // F-7: keep the strategy's intended distances from where we actually got in, not from the signal tick.
      if (anchorToFill && signal != null && signal.priceTicks() != null && fillTicks != null) {
        int intendedStop = stopTicks, intendedTarget = targetTicks;
        stopTicks = fillTicks + (stopTicks - signal.priceTicks());
        targetTicks = fillTicks + (targetTicks - signal.priceTicks());
        log.accept("LIVE_BRACKET_ANCHORED_TO_FILL signal=" + signal.priceTicks() + " fill=" + fillTicks
            + " stop " + intendedStop + "->" + stopTicks + " target " + intendedTarget + "->" + targetTicks + " (ticks)");
      }
      // F-21: a leg on the wrong side of the fill (a long's target at/below its fill, a stop at/above it) would be
      // marketable the moment it is submitted. Never send one as-is: clamp it one tick to the correct side.
      if (fillTicks != null) {
        boolean isLong = targetPosition > 0;
        int fixedStop = isLong ? Math.min(stopTicks, fillTicks - 1) : Math.max(stopTicks, fillTicks + 1);
        int fixedTarget = isLong ? Math.max(targetTicks, fillTicks + 1) : Math.min(targetTicks, fillTicks - 1);
        if (fixedStop != stopTicks || fixedTarget != targetTicks) {
          log.accept("LIVE_BRACKET_ADJUSTED fill=" + fillTicks + " stop " + stopTicks + "->" + fixedStop
              + " target " + targetTicks + "->" + fixedTarget + " -- a leg was on the wrong side of the fill");
          stopTicks = fixedStop;
          targetTicks = fixedTarget;
        }
      }
      float stopPrice = gw.roundToTick((float) c.fromTicks(stopTicks));     // A9: on the tick grid
      float targetPrice = gw.roundToTick((float) c.fromTicks(targetTicks));
      // D-99: size from what the entry actually filled, not what the intent asked for. Falls back to the
      // requested size only if the platform reports no fill quantity for a fill it just called us about.
      Integer filledQty = filledOf(order);
      int bracketQty = filledQty != null && filledQty > 0 ? filledQty : Math.abs(targetPosition);
      if (bracketQty != Math.abs(targetPosition)) {
        log.accept("LIVE_BRACKET_SIZE_FROM_FILL requested=" + Math.abs(targetPosition) + " filled=" + bracketQty);
      }
      OrderGateway.BracketOrders bracket;
      try {
        bracket = gw.submitRealBracket(closingIsBuy, bracketQty, stopPrice, targetPrice, reason);
      } catch (IllegalStateException refused) {
        denyArm.run();
        Runnable la = lockAction;
        if (la != null) la.run();
        log.accept("NON_SIM_ACCOUNT_EVENT bracket refused before sending -- " + refused.getMessage() + " -- disarmed, order sending locked");
        return;
      }
      restingStopOrder = bracket.stop();
      restingTargetOrder = bracket.target();
      log.accept("LIVE_BRACKET_SUBMITTED " + bracket.journalLine());
      return;
    }

    String filledId = order.getOrderId();
    Order stop = restingStopOrder;
    Order target = restingTargetOrder;
    boolean wasStop = stop != null && filledId != null && filledId.equals(stop.getOrderId());
    boolean wasTarget = !wasStop && target != null && filledId != null && filledId.equals(target.getOrderId());
    if (!wasStop && !wasTarget) {
      return; // not one of ours to track -- log line above is enough
    }
    Order sibling = wasStop ? target : stop;
    restingStopOrder = null;
    restingTargetOrder = null;
    OrderGateway gw = gateway.get();
    if (gw != null && sibling != null) {
      selfCancelledOrderIds.add(sibling.getOrderId());
      String line = gw.cancelIfActive(sibling, wasStop ? "target" : "stop",
          "sibling leg after " + (wasStop ? "stop" : "target") + " filled");
      if (line != null) {
        log.accept("LIVE_SIBLING_LEG_CANCELLED " + line);
      } else {
        selfCancelledOrderIds.remove(sibling.getOrderId()); // already resolved -- no callback coming
      }
    }

    // 2026-09-23 (plumbingEdgeCases.md §8): confirm we actually ended up
    // flat after handling the sibling either way. Every bracket today
    // closes the WHOLE position, so anything other than 0 here means the
    // sibling also genuinely filled (a real double-fill, not just a clean
    // cancel) -- cancelIfActive() can't tell "already cancelled" from
    // "already filled" apart on its own (see §8's own writeup), so this
    // checks the one thing that actually reveals which one happened.
    if (gw != null) {
      int posAfter = gw.currentPosition();
      if (posAfter != 0) {
        denyArm.run();
        log.accept("POSITION_MISMATCH_DETECTED expectedFlat=true actualPosition=" + posAfter
            + " -- both bracket legs likely filled, flattening");
        log.accept("POSITION_MISMATCH_CORRECTED " + gw.cancelAllAndClose(
            "double-fill correction: expected flat, was " + posAfter));
        scheduleFlatVerification("DOUBLE_FILL_CORRECTION", 0); // F-1: the correction is itself a position-relative close
      }
    }
  }

  private static Float avgFillPriceOf(Order order) {
    try { return order.getAvgFillPrice(); } catch (RuntimeException e) { return null; }
  }

  /**
   * F-11: one `entry_execution` record per entry fill -- what the signal saw (last, bid, ask), where we got filled,
   * and how long it took (local clock: submit -> fill callback, and signal received -> submit). Slippage is signed
   * "positive = worse for us". Purely diagnostic and failure-proof: nothing here may throw into the order callback.
   */
  private void journalEntryExecution(Order order, int targetPosition, SignalInfo sig, Integer fillTicks, long submitLocalMs) {
    try {
      long now = System.currentTimeMillis();
      int dir = targetPosition > 0 ? 1 : -1;
      Json j = Json.object()
          .field("type", "entry_execution")
          .field("t", now)
          .field("orderId", String.valueOf(order.getOrderId()))
          .field("side", targetPosition > 0 ? "BUY" : "SELL")
          .fieldOrNull("fillPriceTicks", fillTicks)
          .fieldOrNull("signalPriceTicks", sig == null ? null : sig.priceTicks())
          .fieldOrNull("signalBidTicks", sig == null ? null : sig.bidTicks())
          .fieldOrNull("signalAskTicks", sig == null ? null : sig.askTicks());
      if (fillTicks != null && sig != null && sig.priceTicks() != null) {
        j.field("slippageVsLastTicks", (fillTicks - sig.priceTicks()) * dir);
      }
      if (fillTicks != null && sig != null) {
        Integer touch = dir > 0 ? sig.askTicks() : sig.bidTicks();
        if (touch != null) j.field("slippageVsTouchTicks", (fillTicks - touch) * dir);
        if (sig.askTicks() != null && sig.bidTicks() != null) j.field("spreadTicks", sig.askTicks() - sig.bidTicks());
      }
      if (submitLocalMs > 0) j.field("submitToFillMs", now - submitLocalMs);
      if (sig != null && submitLocalMs > 0) j.field("signalToSubmitMs", submitLocalMs - sig.receivedLocalMs());
      record.accept(j.build());
    } catch (RuntimeException e) {
      log.accept("ENTRY_EXECUTION_RECORD_FAILED " + e);
    }
  }

  private static Integer filledOf(Order order) {
    try { return order.getFilled(); } catch (RuntimeException e) { return null; }
  }

  private static Integer quantityOf(Order order) {
    try { return order.getQuantity(); } catch (RuntimeException e) { return null; }
  }

  /**
   * D-99: complete if the SDK says isFilled(), or the filled quantity has reached the order quantity -- either
   * signal suffices, because whether isFilled() is already true at callback time is itself unconfirmed and
   * waiting forever on a filled entry would leave it with no bracket. A getter that throws counts as "not
   * complete" for that signal.
   */
  private static boolean entryFullyFilled(Order order) {
    try {
      if (order.isFilled()) return true;
    } catch (RuntimeException e) {
      // fall through to the quantity comparison
    }
    Integer filled = filledOf(order);
    Integer qty = quantityOf(order);
    return filled != null && qty != null && qty > 0 && filled >= qty;
  }

  /**
   * D-99: an entry that ends (cancelled/rejected) while still in flight but HAS filled something leaves a
   * position that no bracket covers. Flatten what is held (close, then cancel everything, the kill switch's
   * sweep) rather than only disarming. Held = the order's own filled quantity, or the account position if the
   * platform did not report a fill quantity. Returns the flatten's journal line, or null if nothing is held.
   */
  private String flattenIfEntryLeftAPosition(Order order) {
    OrderGateway gw = gateway.get();
    if (gw == null) return null;
    Integer filled = filledOf(order);
    int position = gw.currentPosition();
    if ((filled == null || filled <= 0) && position == 0) return null;
    String line = gw.cancelAllAndClose("partial entry ended with " + filled + " filled, position " + position
        + " -- flattening the unbracketed position");
    scheduleFlatVerification("PARTIAL_ENTRY_FLATTEN", 0); // F-1
    return line;
  }

  /**
   * D-94: journal a structured order_fill for this callback. Classifies by identity, before onOrderFilled's own
   * logic mutates any state: a tracked bracket leg is "stop"/"target"; otherwise, with an entry in flight, the
   * entry; anything else (e.g. the platform's own close from a flatten) is "untracked". Purely additive and
   * failure-proof -- nothing here may throw into the order callback.
   */
  private void recordFill(Order order) {
    try {
      OrderGateway gw = gateway.get();
      if (gw == null) return;
      String id = order.getOrderId();
      Order stop = restingStopOrder;
      Order target = restingTargetOrder;
      String role;
      if (id != null && stop != null && id.equals(stop.getOrderId())) role = "stop";
      else if (id != null && target != null && id.equals(target.getOrderId())) role = "target";
      else if (liveOrderInFlight) role = "entry";
      else role = "untracked";
      record.accept(gw.describeFill(order, role));
      publishFillEvent(order, role, gw);
    } catch (RuntimeException e) {
      log.accept("ORDER_FILL_RECORD_FAILED " + e);
    }
  }

  /**
   * D-82/Q-11, reworked 2026-09-21: a cancellation whose order id is one
   * WE just cancelled ourselves (onOrderFilled's sibling-leg cleanup, the
   * only remaining self-initiated cancel path now that reconcileLive's
   * closing branch takes no action) is expected -- not disarm-worthy. The
   * near-miss's
   * original version disarmed on ANY cancellation while an entry was in
   * flight, which misattributed a bracket-leg cleanup cancellation to the
   * entry itself. Only an unmarked cancellation while our own entry is
   * genuinely in flight still means the entry never became a real
   * position -- that's still disarm-worthy, same reasoning as before.
   */
  void onOrderCancelled(Order order) {
    log.accept("ORDER_CANCELLED " + order);
    if (nonSimulatedEvent(order, "cancel")) return;
    String id = order.getOrderId();
    if (id != null && selfCancelledOrderIds.remove(id)) {
      return;
    }
    if (liveOrderInFlight) {
      clearPendingLiveOrder();
      denyArm.run();
      log.accept("LIVE_ENTRY_CANCELLED_DISARMING -- entry cancelled before filling, disarming");
      String flat = flattenIfEntryLeftAPosition(order);
      if (flat != null) log.accept("LIVE_ENTRY_PARTIAL_FLATTENED " + flat);
      return;
    }
    noteLegEnded(order, "cancelled");
  }

  /** D-82/Q-11: same reasoning as onOrderCancelled() above. */
  void onOrderRejected(Order order) {
    log.accept("ORDER_REJECTED " + order);
    if (nonSimulatedEvent(order, "rejection")) return;
    if (liveOrderInFlight) {
      clearPendingLiveOrder();
      denyArm.run();
      log.accept("LIVE_ENTRY_REJECTED_DISARMING -- entry rejected, disarming");
      String flat = flattenIfEntryLeftAPosition(order);
      if (flat != null) log.accept("LIVE_ENTRY_PARTIAL_FLATTENED " + flat);
      return;
    }
    noteLegEnded(order, "rejected");
  }

  // ---- F-19 (2026-09-28): market data is back after an outage -------------------------------------------------
  //
  // The user's rule: with a position open, if the price has CROSSED OR REACHED the stop or the target while we could not
  // see it -> flatten; if it is still strictly between them -> leave the orders working. "Crossed" is judged on both the
  // first price after the gap (which is where it jumped to, even if it has since come back) and the price now. The
  // check runs one settle interval after the first tick, so a leg the platform itself fills on that first tick is
  // already resolved (the tracker has forgotten it) and is not raced by our own close (the L-1 lesson).
  // (A finer rule -- fetch the bars of the gap and see whether the range touched a level and came back -- is a todo.)

  void onFeedResumed(long gapStartMs, long gapEndMs, Integer firstPriceAfterTicks, java.util.function.Supplier<Integer> currentPriceTicks) {
    java.util.function.BiConsumer<Runnable, Long> s = scheduler;
    if (s == null) return;
    s.accept(() -> feedResumeCheck(gapStartMs, gapEndMs, firstPriceAfterTicks, currentPriceTicks), VERIFY_DELAYS_MS[0]);
  }

  /** null if px is between the stop and target (an exact touch counts as a hit at either boundary); else the reason. */
  private static String crossedLevel(boolean isLong, double px, float stopPx, float targetPx) {
    double eps = 1e-6;
    boolean hitStop = isLong ? px <= stopPx + eps : px >= stopPx - eps;
    boolean hitTarget = isLong ? px >= targetPx - eps : px <= targetPx + eps;
    if (hitStop) return "stop " + stopPx + " reached/crossed at " + px;
    if (hitTarget) return "target " + targetPx + " reached/crossed at " + px;
    return null;
  }

  void feedResumeCheck(long gapStartMs, long gapEndMs, Integer firstPriceAfterTicks, java.util.function.Supplier<Integer> currentPriceTicks) {
    try {
      Order stop = restingStopOrder;
      Order target = restingTargetOrder;
      OrderGateway gw = gateway.get();
      PriceCodec c = codec.get();
      if (gw == null || c == null) return;
      int strat = gw.currentPosition();
      int acct = gw.accountPosition();
      if (stop == null || target == null) {
        if (strat != 0 || acct != 0) {
          log.accept("FEED_RESUME_UNPROTECTED_POSITION study=" + strat + " account=" + acct
              + " -- the market-data outage ended with a position and no tracked bracket: CHECK THE ACCOUNT");
        } else {
          log.accept("FEED_RESUME_NOTHING_OPEN the outage ended with no position and no bracket");
        }
        return;
      }
      if (strat == 0 && acct == 0) {
        log.accept("FEED_RESUME_NOTHING_OPEN the position closed during the outage (both readings flat)");
        return;
      }
      Float stopPx = stop.getStopPrice();
      Float targetPx = target.getLimitPrice();
      Integer nowTicks = currentPriceTicks == null ? null : currentPriceTicks.get();
      if (stopPx == null || targetPx == null || (firstPriceAfterTicks == null && nowTicks == null)) {
        log.accept("FEED_RESUME_UNCHECKABLE stop=" + stopPx + " target=" + targetPx + " -- levels or prices unavailable, orders left as they are");
        return;
      }
      boolean isLong = stop.isSell(); // a long is protected by a SELL stop
      String why = null;
      for (Integer p : new Integer[] {firstPriceAfterTicks, nowTicks}) {
        if (p == null) continue;
        why = crossedLevel(isLong, c.fromTicks(p), stopPx, targetPx);
        if (why != null) break;
      }
      if (why == null) {
        log.accept("FEED_RESUME_KEEP price is still between the stop " + stopPx + " and the target " + targetPx
            + " (first " + firstPriceAfterTicks + " now " + nowTicks + " ticks) -- orders left working");
        scheduleGapRangeCheck(gapStartMs, gapEndMs); // F-22: one further look at the whole gap's range, once bars have backfilled
        return;
      }
      Act act = decide(gw, false, "FEED_RESUME");
      if (act == Act.CLOSE) {
        resetForKillSwitch();
        log.accept("FEED_RESUME_FLATTEN " + why + " -- " + gw.cancelAllAndClose("market-data outage: " + why));
        scheduleFlatVerification("FEED_RESUME", 0);
      } else {
        log.accept("FEED_RESUME_FLATTEN_HELD " + why + " -- no close sent (readings disagree or flat); verifying");
        scheduleFlatVerification("FEED_RESUME", 0);
      }
    } catch (RuntimeException e) {
      log.accept("FEED_RESUME_CHECK_FAILED " + e);
    }
  }

  // ---- F-5 / B6 (2026-09-28, the user chose "flatten + disarm") -------------------------------------------------
  //
  // A bracket leg that is cancelled or rejected on its own (not by us) while a position is open leaves that position
  // with only one protective leg -- or none. But the platform ALSO cancels the sibling leg itself the instant the other
  // one fills (L-7), and that cancel callback arrives BEFORE our fill callback. So a cancel is never judged when it
  // arrives: it is re-examined 1.5 s later, when the fill callback has either cleared our leg references (benign) or
  // not (a real lost leg).

  private void noteLegEnded(Order order, String how) {
    String id = order.getOrderId();
    if (id == null) return;
    Order stop = restingStopOrder;
    Order target = restingTargetOrder;
    String leg = stop != null && id.equals(stop.getOrderId()) ? "stop"
        : target != null && id.equals(target.getOrderId()) ? "target" : null;
    if (leg == null) return; // not one of the current bracket's legs
    log.accept("LEG_ENDED_SEEN leg=" + leg + " " + how + " -- confirming in " + VERIFY_DELAYS_MS[0] + " ms whether the position lost its protection");
    java.util.function.BiConsumer<Runnable, Long> s = scheduler;
    if (s != null) s.accept(() -> confirmLegLost(leg, id, how), VERIFY_DELAYS_MS[0]);
  }

  /** The delayed half of noteLegEnded(). Fully guarded; safe to call from the safety thread. */
  void confirmLegLost(String leg, String id, String how) {
    try {
      Order stop = restingStopOrder;
      Order target = restingTargetOrder;
      boolean stillOurs = (stop != null && id.equals(stop.getOrderId())) || (target != null && id.equals(target.getOrderId()));
      if (!stillOurs) {
        log.accept("LEG_ENDED_BENIGN leg=" + leg + " " + how + " -- the bracket was already resolved (a leg filled or we flattened)");
        return;
      }
      if (legFilled(stop) || legFilled(target)) {
        log.accept("LEG_ENDED_BENIGN leg=" + leg + " " + how + " -- the other leg filled, the position is closed");
        return;
      }
      OrderGateway gw = gateway.get();
      if (gw == null) return;
      int strat = gw.currentPosition();
      int acct = gw.accountPosition();
      if (strat == 0 && acct == 0) {
        log.accept("LEG_ENDED_BENIGN leg=" + leg + " " + how + " -- account and study are flat, nothing left to protect");
        return;
      }
      denyArm.run();
      log.accept("LIVE_LEG_LOST leg=" + leg + " " + how + " on its own with the position still open (study=" + strat
          + " account=" + acct + ") -- disarming and flattening");
      resetForKillSwitch();
      Act act = decide(gw, false, "LEG_LOST");
      if (act == Act.CLOSE) {
        log.accept("LEG_LOST_FLATTEN " + gw.cancelAllAndClose("bracket leg lost (" + leg + " " + how + ")"));
      } else {
        log.accept("LEG_LOST_FLATTEN no close sent (study and account readings disagree) -- the remaining leg is left working; verifying");
      }
      scheduleFlatVerification("LEG_LOST", 0);
    } catch (RuntimeException e) {
      log.accept("LEG_LOST_CHECK_FAILED " + e);
    }
  }
}
