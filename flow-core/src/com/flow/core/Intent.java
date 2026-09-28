package com.flow.core;

/**
 * A strategy's desired end state, not a command (README "Strategies emit
 * intent. They do not place orders."). exec/ diffs this against the
 * actual position and live orders; re-submitting an identical intent is a
 * no-op there, not here.
 *
 * anchorToFill (2026-09-28, F-7): when true, stopPriceTicks/targetPriceTicks were computed relative to the SIGNAL price
 * (the price of the tick that fired the intent) and mean "this many ticks either side of where I get in". The runtime
 * then places the real bracket the same distances from the actual FILL price, so a market entry that fills a spread or
 * two away from the signal (measured: +1.6..1.9 ticks on average) keeps the strategy's intended risk/reward instead of
 * a lopsided one, and a target can never land on the wrong side of the fill. False (the default) = the prices are
 * absolute levels (e.g. beyond a structural zone) and are used exactly as given.
 */
public record Intent(
    String strategyId,
    long seq,
    int targetPosition,
    Integer stopPriceTicks,   // null = no stop
    Integer targetPriceTicks, // null = no target
    String reason,
    boolean anchorToFill
) {
  /** Absolute stop/target levels (the historical shape). */
  public Intent(String strategyId, long seq, int targetPosition, Integer stopPriceTicks, Integer targetPriceTicks,
                String reason) {
    this(strategyId, seq, targetPosition, stopPriceTicks, targetPriceTicks, reason, false);
  }

  public static Intent none(String strategyId, long seq) {
    return new Intent(strategyId, seq, 0, null, null, "none");
  }
}
