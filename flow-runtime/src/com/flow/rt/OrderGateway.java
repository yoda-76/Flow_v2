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
 */
final class OrderGateway {
  private final OrderContext ctx;

  OrderGateway(OrderContext ctx) {
    this.ctx = ctx;
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
    java.util.List activeOrders = ctx.getActiveOrders();
    int orderCount = activeOrders == null ? 0 : activeOrders.size();
    if (position != 0 || orderCount > 0) {
      return "existing position=" + position + " activeOrders=" + orderCount + " -- clear manually before arming";
    }
    return null;
  }

  /** D-82/Q-11: the account's actual position, for reconcileLive()'s diff -- never the strategy's own belief. */
  int currentPosition() {
    return ctx.getPosition();
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
   * anything order-submitting -- reporting only. submitRealEntry()/
   * submitRealBracket() below do the real thing, but nothing routes an
   * automatic intent to them (see the class javadoc).
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
  // Real order submission (2026-09-19). Written, but deliberately NOT
  // called from anywhere in the automatic intent/pipeline path -- no
  // caller exists yet. CLAUDE.md's hard rule requires the exact
  // account/instrument/side/quantity/order-type stated and one last
  // explicit confirmation *immediately before* submitting, which an
  // automatic per-tick strategy loop cannot satisfy on its own once
  // wired in. The first real call site gets added deliberately, in the
  // same conversation turn as that final confirmation -- not before.
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
    int positionBefore = ctx.getPosition();
    Order entry = OrderAdapter.marketOrder(ctx, isBuy, qty);
    ctx.submitOrders(entry);
    return Json.object()
        .field("type", "real_order_submitted")
        .field("orderType", "MARKET")
        .field("instrument", ctx.getInstrument().getSymbol())
        .field("side", isBuy ? "BUY" : "SELL")
        .field("qty", qty)
        .field("positionBefore", positionBefore)
        .field("cashBalance", ctx.getCashBalance())
        .field("reason", reason)
        .build();
  }

  /**
   * Stop + target bracket for an already-filled entry -- call only from
   * onOrderFilled (confirmed fill), never speculatively ahead of one, so
   * a rejected/partial entry never leaves an orphaned bracket sized for
   * a position that doesn't exist. isBuy here is the CLOSING side
   * (opposite of the entry: SELL-side bracket closes a long).
   */
  String submitRealBracket(boolean closingIsBuy, int qty, float stopPrice, float targetPrice, String reason) {
    Order stop = OrderAdapter.stopOrder(ctx, closingIsBuy, qty, stopPrice);
    Order target = OrderAdapter.limitOrder(ctx, closingIsBuy, qty, targetPrice);
    ctx.submitOrders(stop, target);
    return Json.object()
        .field("type", "real_bracket_submitted")
        .field("instrument", ctx.getInstrument().getSymbol())
        .field("closingSide", closingIsBuy ? "BUY" : "SELL")
        .field("qty", qty)
        .field("stopPrice", stopPrice)
        .field("targetPrice", targetPrice)
        .field("reason", reason)
        .build();
  }
}
