package com.flow.core;

/**
 * One trade. Prices are integer tick offsets from the session anchor
 * (D-21) -- conversion to/from decimal happens only at the flow-runtime
 * ingest boundary, never in here or below.
 */
public record TickEvent(
    long seq,
    long eventTimeMs,
    long receiptTimeMs,
    int priceTicks,
    int volume,
    boolean isAskTick,
    int bidPriceTicks,
    int askPriceTicks
) implements Event {}
