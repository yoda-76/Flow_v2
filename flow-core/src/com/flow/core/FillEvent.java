package com.flow.core;

/** Placeholder, same status as OrderEvent -- see that type's note. */
public record FillEvent(
    long seq,
    long eventTimeMs,
    long receiptTimeMs,
    String orderId,
    int priceTicks,
    int quantity
) implements Event {}
