package com.flow.rt;

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
      Consumer<String> log, Runnable denyArm) {
    this.gateway = gateway;
    this.codec = codec;
    this.log = log;
    this.denyArm = denyArm;
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
  String reconcileLive(Intent intent, OrderGateway gw) {
    if (liveOrderInFlight) {
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
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "direct flip/resize (from " + currentPosition + " to " + target
              + ") not auto-handled -- see reconcileLive()'s javadoc")
          .build();
    }
    if (opening && gw.hasRestingOrders()) {
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
    return gw.submitRealEntry(delta > 0, Math.abs(delta), intent.reason());
  }

  /** Clears in-flight/pending-bracket state -- shared by onOrderRejected/onOrderCancelled below. */
  private void clearPendingLiveOrder() {
    liveOrderInFlight = false;
    pendingBracketTargetPosition = 0;
    pendingBracketStopTicks = null;
    pendingBracketTargetTicks = null;
    pendingBracketReason = null;
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

    if (liveOrderInFlight) {
      int targetPosition = pendingBracketTargetPosition;
      Integer stopTicks = pendingBracketStopTicks;
      Integer targetTicks = pendingBracketTargetTicks;
      String reason = pendingBracketReason;
      clearPendingLiveOrder();
      if (targetPosition == 0 || stopTicks == null || targetTicks == null) {
        log.accept("LIVE_FILL_NO_BRACKET targetPosition=" + targetPosition
            + " stopTicks=" + stopTicks + " targetTicks=" + targetTicks);
        return;
      }
      PriceCodec c = codec.get();
      OrderGateway gw = gateway.get();
      if (c == null || gw == null) {
        log.accept("LIVE_BRACKET_SKIPPED reason=codec_or_gateway_unavailable");
        return;
      }
      boolean closingIsBuy = targetPosition < 0; // a short position closes with a BUY bracket
      float stopPrice = (float) c.fromTicks(stopTicks);
      float targetPrice = (float) c.fromTicks(targetTicks);
      OrderGateway.BracketOrders bracket =
          gw.submitRealBracket(closingIsBuy, Math.abs(targetPosition), stopPrice, targetPrice, reason);
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
      }
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
    String id = order.getOrderId();
    if (id != null && selfCancelledOrderIds.remove(id)) {
      return;
    }
    if (liveOrderInFlight) {
      clearPendingLiveOrder();
      denyArm.run();
      log.accept("LIVE_ENTRY_CANCELLED_DISARMING -- entry cancelled before filling, disarming");
    }
  }

  /** D-82/Q-11: same reasoning as onOrderCancelled() above. */
  void onOrderRejected(Order order) {
    log.accept("ORDER_REJECTED " + order);
    if (liveOrderInFlight) {
      clearPendingLiveOrder();
      denyArm.run();
      log.accept("LIVE_ENTRY_REJECTED_DISARMING -- entry rejected, disarming");
    }
  }
}
