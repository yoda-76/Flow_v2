package com.flow.core;

/**
 * One trade. Prices are integer tick offsets from the session anchor
 * (D-21) -- conversion to/from decimal happens only at the flow-runtime
 * ingest boundary, never in here or below.
 *
 * exchOrderId/aggExchOrderId carry the SDK's real exchange order ids
 * (0 = none/unavailable -- the platform's own "no id" sentinel, confirmed
 * live per T-3 in ../../motivewave/docs/dynamic/findings.md), added for
 * big trades (AggregateFilter's aggByOrder=true dedupe needs the real id
 * to detect a repeat emission of the same logical trade). Previously
 * stubbed to 0 by TickAdapter since nothing needed them; volume profile
 * and footprint's SDK engine never reads them either way.
 */
public record TickEvent(
    long seq,
    long eventTimeMs,
    long receiptTimeMs,
    int priceTicks,
    int volume,
    boolean isAskTick,
    int bidPriceTicks,
    int askPriceTicks,
    long exchOrderId,
    long aggExchOrderId
) implements Event {}
