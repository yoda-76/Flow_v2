package com.flow.core;

/**
 * Placeholder for order-lifecycle callbacks (submitted/cancelled/rejected/
 * modified). Not yet produced by anything -- the walking skeleton never
 * submits an order (armed defaults false, and OrderGateway has no
 * submission code path at all yet, per Q-02 being open). Defined now so
 * the Event hierarchy matches README's stated three producers; wired up
 * when exec/OrderGateway actually places orders.
 */
public record OrderEvent(
    long seq,
    long eventTimeMs,
    long receiptTimeMs,
    String orderId,
    String status
) implements Event {}
