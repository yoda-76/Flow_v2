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
   * Diffs the strategy's desired position against the account's actual
   * position and returns a journal line describing what would happen.
   * Never calls buy/sell/createXOrder/submitOrders/cancelOrders/
   * closeAtMarket -- those methods are not called anywhere in this class.
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
        .field("reason", intent.reason())
        .build();
  }
}
