package com.flow.flow;

/**
 * One exchange order id observed trading more than once -- the raw signal
 * behind iceberg/hidden-liquidity detection (an order that keeps
 * refreshing at the same price shows up as the same exchOrderId repeating
 * across several ticks). No size threshold, no classification judgment
 * made here -- this is capture, not detection (D-56): "is this actually
 * an iceberg" is deferred analysis, same as D-39's LVN/HVN ranking was
 * deliberately deferred behind D-40's raw journal first.
 *
 * firstSeenMs/lastSeenMs bound the repeat's own observed lifetime so far;
 * repeatCount and totalSize are running totals, updated in place on each
 * further repeat (same "update, don't re-emit a new record" shape as
 * BigTradeEvent).
 */
public record OrderRepeatEvent(
    long exchOrderId,
    long firstSeenMs,
    long lastSeenMs,
    int repeatCount,
    double totalSize,
    int priceTicks,
    boolean isAskTick
) {}
