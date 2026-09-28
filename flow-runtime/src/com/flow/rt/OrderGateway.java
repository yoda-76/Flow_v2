package com.flow.rt;

import com.flow.core.Intent;
import com.flow.journal.Json;
import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;

/**
 * The sole holder of an OrderContext (README hard rule) -- package-private,
 * constructed by FlowRuntimeStudy, never handed anywhere else.
 *
 * reconcileDryRun() (D-14) is called whenever the session isn't armed for
 * live trading (or Mode != SIM_LIVE) -- reads the current position and
 * journals what it would do, never places anything. submitRealEntry()/
 * submitRealBracket() (2026-09-19, confirmed working end-to-end 2026-09-21
 * -- D-67/D-81) are real order-submission code, now called automatically
 * from FlowRuntimeStudy.reconcileLive() under CLAUDE.md's second exception
 * (D-82/Q-11: session-scoped arm, Sim account only, risk-chain-bounded).
 * The one-shot manual test harness that first exercised these two methods
 * (FIRE_TEST_TRADE_KEY) has been stripped from FlowRuntimeStudy now that
 * it's confirmed working, per the plan recorded when it was added.
 *
 * closeAtMarket() and cancelIfActive() (2026-09-21) exist because a real
 * live session found submitRealEntry() alone is not a safe way to close a
 * position that still has resting bracket legs -- see their own javadocs.
 */
final class OrderGateway {
  private final OrderContext ctx;
  // 2026-09-28 (user: "only simulated trades will affect anything related to the system"): set the moment an order
  // or fill on a NON-simulated account is seen. Shared across gateways (the Study builds a new one at each
  // activation), and once set NOTHING is sent: entries, brackets, closes and cancels all refuse. There is no
  // un-lock -- remove and re-add the study, and fix the account, first.
  private final java.util.concurrent.atomic.AtomicBoolean orderLock;

  OrderGateway(OrderContext ctx) {
    this(ctx, new java.util.concurrent.atomic.AtomicBoolean(false));
  }

  OrderGateway(OrderContext ctx, java.util.concurrent.atomic.AtomicBoolean orderLock) {
    this.ctx = ctx;
    this.orderLock = orderLock;
  }

  boolean locked() { return orderLock.get(); }

  void lockOrders() { orderLock.set(true); }

  /**
   * The Simulated-only rule at the point of sending: refuses (throws) if the lock is set, or if any of these
   * not-yet-submitted orders already names an account other than the simulated one. An order that names no account
   * yet is let through (AccountPolicy: UNKNOWN); the fill callback re-checks with the account the platform reports.
   */
  private void requireSimulated(Order... orders) {
    if (orderLock.get()) throw new IllegalStateException("order lock: a non-simulated account was seen -- no orders are sent");
    for (Order o : orders) {
      String id = safe(o::getAccountId);
      if (com.flow.core.AccountPolicy.classify(id) == com.flow.core.AccountPolicy.Kind.OTHER) {
        orderLock.set(true);
        throw new IllegalStateException("REFUSING to submit: the order's account is '" + id + "', not the simulated account");
      }
    }
  }

  private static String refusedLine(String what) {
    return Json.object().field("type", "order_refused").field("what", what)
        .field("reason", "order lock: a non-simulated account was seen").build();
  }

  /**
   * D-94: a structured journal record for one fill, so the nightly report can show entry/exit price, time and
   * side without parsing an opaque "ORDER_FILLED bp.aa@4ecca8f8". Every getter is read defensively: a value
   * that throws or is unavailable becomes a JSON null rather than an exception, because this runs inside an
   * order callback and must never be able to break order handling. Prices are snapped to the tick grid and
   * printed as the shortest decimal (a float 4394.8 is otherwise journaled as 4394.7998046875).
   *
   * role is the caller's classification (entry / stop / target / untracked). cashBalance and positionAfter are
   * read at callback time, so they reflect this fill (cash includes fees; the price-based PnL does not).
   * sdkTotalRealizedPnL is the platform's own figure, journaled raw -- its exact meaning is unverified.
   */
  String describeFill(Order order, String role) {
    com.motivewave.platform.sdk.common.Instrument inst = safe(ctx::getInstrument);
    Json j = Json.object()
        .field("type", "order_fill")
        .field("t", System.currentTimeMillis())
        .field("role", role)
        .field("orderId", String.valueOf(safe(order::getOrderId)))
        .field("instrument", inst == null ? "?" : String.valueOf(safe(inst::getSymbol)))
        .field("action", Boolean.TRUE.equals(safe(order::isBuy)) ? "BUY" : "SELL")
        .field("accountId", String.valueOf(safe(order::getAccountId))); // F-2 probe (C3): what the platform calls the account -- read-only evidence for a future real-account guard
    Integer quantity = safe(order::getQuantity);
    Integer filled = safe(order::getFilled);
    Long lastFillTimeMs = safe(order::getLastFillTime);
    Integer positionAfter = safe(ctx::getPosition);
    Double cashBalance = safe(ctx::getCashBalance);
    Double sdkRealized = safe(ctx::getTotalRealizedPnL);
    Double pointValue = inst == null ? null : safe(inst::getPointValue);
    Double tickSize = inst == null ? null : safe(inst::getTickSize);
    j.fieldOrNull("quantity", quantity)
        .fieldOrNull("filled", filled)
        .fieldOrNull("avgFillPrice", price(inst, safe(order::getAvgFillPrice)))
        .fieldOrNull("lastFillPrice", price(inst, safe(order::getLastFillPrice)))
        .fieldOrNull("lastFillTimeMs", lastFillTimeMs)
        .fieldOrNull("stopPrice", price(inst, safe(order::getStopPrice)))
        .fieldOrNull("limitPrice", price(inst, safe(order::getLimitPrice)))
        .fieldOrNull("positionAfter", positionAfter)
        .fieldOrNull("cashBalance", cashBalance)
        .fieldOrNull("sdkTotalRealizedPnL", sdkRealized)
        .fieldOrNull("pointValue", pointValue)
        .fieldOrNull("tickSize", tickSize);
    return j.build();
  }

  /**
   * Code review A9: a price snapped to the instrument's tick grid, for an order about to be sent. The codec's anchor
   * carries float noise (e.g. 4322.900098); a float cast happened to round it away on gold, but a real exchange
   * rejects an off-tick price. Falls back to the raw price if the platform cannot round it.
   */
  float roundToTick(float price) {
    com.motivewave.platform.sdk.common.Instrument inst = safe(ctx::getInstrument);
    if (inst == null) return price;
    Float r = safe(() -> inst.round(price));
    return r == null ? price : r;
  }

  private static <T> T safe(java.util.function.Supplier<T> s) {
    try {
      return s.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Double price(com.motivewave.platform.sdk.common.Instrument inst, Float raw) {
    if (raw == null || raw.isNaN() || raw == 0f) return null; // 0 = "not filled / no such price" in this SDK
    Float snapped = raw;
    if (inst != null) {
      Float r = safe(() -> inst.round(raw));
      if (r != null) snapped = r;
    }
    return Double.parseDouble(Float.toString(snapped));
  }

  /**
   * D-24/D-61: refuse to arm if the account already holds a position or
   * has resting orders on activation/reload -- adopting either silently
   * is how a small loss becomes a large one, and flattening them would
   * be an order placed as a side effect of startup, which CLAUDE.md's
   * hard rule forbids. Returns null when clear to arm, otherwise the
   * reason to journal.
   */
  @SuppressWarnings("unchecked") // getActiveOrders() returns a raw List in this jar, same T-6-class mismatch as other raw-List SDK returns
  String refuseToArmReason() {
    int position = ctx.getPosition();
    int accountPosition = ctx.getAccountPosition(); // F-2: the ACCOUNT's, which the study's own getPosition() can miss
    java.util.List activeOrders = ctx.getActiveOrders();
    int orderCount = activeOrders == null ? 0 : activeOrders.size();
    if (position != 0 || accountPosition != 0 || orderCount > 0) {
      return "existing position=" + position + " accountPosition=" + accountPosition + " activeOrders=" + orderCount
          + " -- clear manually before arming";
    }
    return null;
  }

  /**
   * The STUDY's own position (SDK getPosition(): "the current open position for this strategy"). Not the account's:
   * F-2 (2026-09-28) found the SDK has a separate getAccountPosition(), and that this one did not show a manual close
   * for two minutes (L-2) and lagged a leg fill by milliseconds (L-1). Use accountPosition() alongside it.
   */
  int currentPosition() {
    return ctx.getPosition();
  }

  /** F-2: the ACCOUNT's position for the chart instrument (SDK getAccountPosition()) -- includes trades the study did not place. */
  int accountPosition() {
    return ctx.getAccountPosition();
  }

  /**
   * F-1: the account's own numbers for the risk chain's daily-loss check -- see RiskChain.AccountTruth. Read at fill
   * callbacks and once a second. Null if the instrument's tick value is unavailable. The average entry is only
   * converted to ticks when the price anchor already exists (toTicks would otherwise fix the anchor from it).
   */
  com.flow.core.RiskChain.AccountTruth accountTruth(PriceCodec codec, double startCash) {
    com.motivewave.platform.sdk.common.Instrument inst = safe(ctx::getInstrument);
    Double pointValue = inst == null ? null : safe(inst::getPointValue);
    Double tickSize = inst == null ? null : safe(inst::getTickSize);
    Double cash = safe(ctx::getCashBalance);
    Integer position = safe(ctx::getAccountPosition);
    if (pointValue == null || tickSize == null || cash == null || position == null) return null;
    Integer entryTicks = null;
    if (position != 0 && codec != null && codec.hasAnchor()) {
      Float entry = safe(ctx::getAccountAvgEntryPrice);
      if (entry != null && !entry.isNaN() && entry != 0f) entryTicks = codec.toTicks(entry);
    }
    return new com.flow.core.RiskChain.AccountTruth(cash, startCash, position, entryTicks, pointValue * tickSize);
  }

  /** The account's cash balance now, or null if the platform cannot say (used to fix the session's starting balance). */
  Double cashBalance() {
    return safe(ctx::getCashBalance);
  }

  /** D-82/Q-11: same existing-order check refuseToArmReason() uses, reused as a stacking guard before every automatic real entry. */
  @SuppressWarnings("unchecked")
  boolean hasRestingOrders() {
    java.util.List activeOrders = ctx.getActiveOrders();
    return activeOrders != null && !activeOrders.isEmpty();
  }

  /**
   * Diffs the strategy's desired position against the account's actual
   * position and returns a journal line describing what would happen,
   * including the bracket (stop/target) that would accompany a real
   * entry (Q-06's "sizing and brackets"). This method itself never calls
   * anything order-submitting -- reporting only. Called from
   * FlowRuntimeStudy.onIntentChanged() whenever the session is NOT live-armed
   * (DRY_RUN, or Mode != SIM_LIVE); when it is, LiveOrderTracker.reconcileLive()
   * is called instead, which does route to submitRealEntry()/submitRealBracket()
   * below (see the class javadoc).
   */
  String reconcileDryRun(Intent intent) {
    int currentPosition = ctx.getPosition();
    int delta = intent.targetPosition() - currentPosition;
    String action = delta == 0 ? "NONE" : (delta > 0 ? "WOULD_BUY" : "WOULD_SELL");
    return Json.object()
        .field("type", "reconcile_dry_run")
        .field("strategyId", intent.strategyId())
        .field("intentSeq", intent.seq())
        .field("currentPosition", currentPosition)
        .field("targetPosition", intent.targetPosition())
        .field("action", action)
        .field("qty", Math.abs(delta))
        .fieldOrNull("wouldSetStopPriceTicks", intent.stopPriceTicks())
        .fieldOrNull("wouldSetTargetPriceTicks", intent.targetPriceTicks())
        .field("reason", intent.reason())
        .build();
  }

  // ------------------------------------------------------------------
  // Real order submission (2026-09-19). Called automatically now, under
  // CLAUDE.md's second exception (D-82/Q-11: session-scoped Sim arming,
  // no per-order confirmation) -- submitRealEntry()/submitRealBracket()
  // are invoked from LiveOrderTracker.reconcileLive()/onOrderFilled(),
  // which FlowRuntimeStudy.onIntentChanged() calls whenever the session
  // is live-armed (Mode=SIM_LIVE, Armed checked, not denied). The kill
  // switch and the session-end flatten go through cancelAllAndClose()/
  // cancelAllOrders() below instead (LiveOrderTracker.onKillSwitch() /
  // flattenForSessionEnd()), not through this pair.
  // ------------------------------------------------------------------

  /**
   * D-67: goes through createMarketOrder()+submitOrders(), NOT
   * ctx.buy(int)/sell(int) -- the live 2026-09-19 test used the buy/sell
   * shortcuts, the entry filled correctly on the account/broker side, but
   * onOrderFilled was never called back for it. buy()/sell() return void,
   * giving the strategy no Order reference at all -- suspected (not
   * confirmable against SDK source, which isn't available) to mean the
   * platform never links a later fill callback back to an order submitted
   * that way. createMarketOrder()+submitOrders() is the same tracked-order
   * family as the stop/target orders below -- confirmed working live
   * 2026-09-21: entry filled, onOrderFilled fired, and the bracket
   * submitted from it (below) filled/cancelled correctly as an OCO pair.
   */
  String submitRealEntry(boolean isBuy, int qty, String reason) {
    return submitRealEntry(isBuy, qty, reason, null, 0L);
  }

  /**
   * F-11 (2026-09-28): the same submission, with the journal record now carrying WHEN it was sent (local clock) and
   * what the signal tick looked like (last, bid, ask, exchange time) -- without these, entry latency and slippage
   * could only be inferred afterwards. submitLocalMs is read by the caller just before this call.
   */
  String submitRealEntry(boolean isBuy, int qty, String reason, LiveOrderTracker.SignalInfo signal, long submitLocalMs) {
    int positionBefore = ctx.getPosition();
    Order entry = OrderAdapter.marketOrder(ctx, isBuy, qty);
    requireSimulated(entry);
    ctx.submitOrders(entry);
    Json j = Json.object()
        .field("type", "real_order_submitted")
        .field("orderType", "MARKET")
        .field("instrument", ctx.getInstrument().getSymbol())
        .field("side", isBuy ? "BUY" : "SELL")
        .field("qty", qty)
        .field("positionBefore", positionBefore)
        .field("cashBalance", ctx.getCashBalance())
        .field("reason", reason);
    if (submitLocalMs > 0) j.field("t", submitLocalMs);
    if (signal != null) {
      j.fieldOrNull("signalPriceTicks", signal.priceTicks())
          .fieldOrNull("signalBidTicks", signal.bidTicks())
          .fieldOrNull("signalAskTicks", signal.askTicks())
          .field("signalEventTimeMs", signal.eventTimeMs())
          .field("signalReceivedLocalMs", signal.receivedLocalMs());
    }
    return j.build();
  }

  /**
   * Stop + target bracket for an already-filled entry -- call only from
   * onOrderFilled (confirmed fill), never speculatively ahead of one, so
   * a rejected/partial entry never leaves an orphaned bracket sized for
   * a position that doesn't exist. isBuy here is the CLOSING side
   * (opposite of the entry: SELL-side bracket closes a long).
   *
   * Returns both Order references, not just a journal line -- there is no
   * linked-OCO/bracket concept anywhere in this SDK surface (confirmed by
   * reading OrderContext's full method list, 2026-09-21: submitOrders()
   * takes independent orders, nothing pairs them at the platform level).
   * A 2026-09-21 near-miss found exactly this: a leg's sibling doesn't
   * reliably self-cancel just because we hope it's OCO-linked. The caller
   * (FlowRuntimeStudy) holds these references and is responsible for
   * cancelling whichever one is still active the moment the position they
   * protect goes away, by either path (a leg fills naturally, or the
   * strategy independently decides to flatten) -- a leg must never
   * outlive its position, per the user's explicit design call.
   */
  record BracketOrders(Order stop, Order target, String journalLine) {}

  BracketOrders submitRealBracket(boolean closingIsBuy, int qty, float stopPrice, float targetPrice, String reason) {
    Order stop = OrderAdapter.stopOrder(ctx, closingIsBuy, qty, stopPrice);
    Order target = OrderAdapter.limitOrder(ctx, closingIsBuy, qty, targetPrice);
    requireSimulated(stop, target);
    ctx.submitOrders(stop, target);
    String line = Json.object()
        .field("type", "real_bracket_submitted")
        .field("instrument", ctx.getInstrument().getSymbol())
        .field("closingSide", closingIsBuy ? "BUY" : "SELL")
        .field("qty", qty)
        .field("stopPrice", stopPrice)
        .field("targetPrice", targetPrice)
        .field("reason", reason)
        .build();
    return new BracketOrders(stop, target, line);
  }

  /**
   * The platform's own "close the position" call, not a speculative fresh
   * order -- distinct from submitRealEntry(), which opens/resizes.
   * Doesn't know about or cancel any resting bracket legs on its own
   * (confirmed from OrderContext's method list -- no bracket/OCO grouping
   * exists here), so a caller wanting that too must cancel separately.
   *
   * No caller in FlowRuntimeStudy as of the 2026-09-21 SL/TP-is-bracket-
   * only rework -- reconcileLive() used to call this on every strategy-
   * detected stop/target hit, which is exactly what caused two live
   * near-misses (racing the real bracket closing the same position a
   * different way). The session-end auto-flatten that was still-unbuilt
   * when this was written now exists (D-92, LiveOrderTracker.flattenForSessionEnd()),
   * but it goes through cancelAllAndClose()/cancelAllOrders() below, not
   * this method -- same "don't race a resting bracket leg" reasoning:
   * those two also cancel whatever is still resting in the same call,
   * which a bare closeAtMarket() does not. Kept, not deleted: it's
   * genuine, proven-safe infrastructure (exercised by OrderGatewayTest)
   * for a future case that legitimately wants "close the position"
   * with nothing resting to also cancel -- unlike cancelTrackedLegs()'s
   * old per-position surgical cancellation, which was deleted outright
   * since nothing else needed that shape.
   */
  String closeAtMarket(String reason) {
    if (orderLock.get()) return refusedLine("closeAtMarket");
    int positionBefore = ctx.getPosition();
    ctx.closeAtMarket();
    return Json.object()
        .field("type", "real_close_at_market")
        .field("instrument", ctx.getInstrument().getSymbol())
        .field("positionBefore", positionBefore)
        .field("reason", reason)
        .build();
  }

  /**
   * Cancels exactly one order, and only if it's still active -- never a
   * blanket ctx.cancelOrders() sweep (the user's explicit call: a leg is
   * owned by its position, cancelled by reference, not "cancel everything
   * resting"). Returns null (nothing journaled) if the order was already
   * resolved (filled/cancelled) by the time this runs, which is the
   * normal case when the OTHER leg is the one that triggered the cleanup.
   */
  String cancelIfActive(Order order, String legName, String reason) {
    if (order == null || !order.isActive()) {
      return null;
    }
    if (orderLock.get()) return refusedLine("cancel " + legName);
    ctx.cancelOrders(order);
    return Json.object()
        .field("type", "real_leg_cancelled")
        .field("leg", legName)
        .field("reason", reason)
        .build();
  }

  /**
   * D-92: cancel everything resting WITHOUT sending a close -- for the session-end flatten when the account is
   * already flat but orders are still working (e.g. a bracket whose entry was closed another way). Avoids
   * sending closeAtMarket() to a flat account, whose behaviour there is unconfirmed.
   */
  String cancelAllOrders(String reason) {
    if (orderLock.get()) return refusedLine("cancelAllOrders");
    ctx.cancelOrders();
    return Json.object()
        .field("type", "session_orders_cancelled")
        .field("instrument", ctx.getInstrument().getSymbol())
        .field("reason", reason)
        .build();
  }

  /**
   * The one sanctioned use of a blanket sweep -- everywhere else in this
   * class cancels by reference (cancelIfActive()), deliberately, per the
   * user's "a leg is owned by its position" design call. The daily-loss
   * kill switch is different in kind: the user's explicit requirement is
   * "no matter what, close every open and resting position," not
   * "close this specific one" -- a blunt instrument on purpose, reserved
   * for exactly this one caller (Pipeline's per-event breach check via
   * FlowRuntimeStudy's kill-switch callback).
   */
  String cancelAllAndClose(String reason) {
    if (orderLock.get()) return refusedLine("cancelAllAndClose");
    int positionBefore = ctx.getPosition();
    ctx.closeAtMarket();
    ctx.cancelOrders();
    return Json.object()
        .field("type", "kill_switch_flatten")
        .field("instrument", ctx.getInstrument().getSymbol())
        .field("positionBefore", positionBefore)
        .field("reason", reason)
        .build();
  }
}
