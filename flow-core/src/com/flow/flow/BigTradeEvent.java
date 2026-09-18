package com.flow.flow;

/**
 * One large trade -- volume aggregated over a short time+price window
 * (D-55, corrected from D-53's original per-order-id design; see
 * BigTradeFeature's javadoc for the full "why"). eventTimeMs is when the
 * window that produced this trade FIRST opened; while the window is still
 * accumulating, later ticks update size in place without changing
 * eventTimeMs, so this stays "when the trade started," not "when we last
 * heard about it."
 *
 * exchOrderId is best-effort provenance only -- the real exchange order
 * id of the tick that opened this trade's window, not an aggregation key
 * (one window can span several different real order ids, since
 * aggregation is by time+price now, not by shared id). 0 means the
 * opening tick had no id available (the SDK's own "no id" sentinel,
 * confirmed live).
 */
public record BigTradeEvent(
    long eventTimeMs,
    int priceTicks,
    double size,
    boolean isAskTick,
    long exchOrderId
) {}
