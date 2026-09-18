package com.flow.rt;

import com.flow.core.Intent;
import com.flow.journal.Json;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;

/**
 * The sole holder of an OrderContext (README hard rule) -- package-private,
 * constructed by FlowRuntimeStudy, never handed anywhere else. Dry-run
 * only for now (D-14): reconcile() only ever reads the current position
 * and journals what it would do. There is no order-submission code path
 * in this class at all yet -- not gated by an if(armed), simply absent --
 * because Q-02 (OrderContext write-call thread affinity/flush point) is
 * still open and submission code doesn't get written until that's
 * resolved and the user explicitly asks for it in the moment, per
 * CLAUDE.md's hard rule.
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

  /**
   * Diffs the strategy's desired position against the account's actual
   * position and returns a journal line describing what would happen,
   * including the bracket (stop/target) that would accompany a real
   * entry (Q-06's "sizing and brackets," dry-run reporting only -- no
   * bracket order is ever constructed or submitted, same as the
   * position side). Never calls buy/sell/createXOrder/submitOrders/
   * cancelOrders/closeAtMarket -- those methods are not called anywhere
   * in this class.
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
}
