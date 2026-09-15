package com.flow.core;

/**
 * Top-of-book only for the walking skeleton. The full MBO liquidity map
 * (README: "raw DOM never crosses MarketState") is later feature work --
 * this type will likely grow a depth array then, not be replaced.
 */
public record DomEvent(
    long seq,
    long eventTimeMs,
    long receiptTimeMs,
    int bestBidTicks,
    double bestBidSize,
    int bestAskTicks,
    double bestAskSize
) implements Event {}
