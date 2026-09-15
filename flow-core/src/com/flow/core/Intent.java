package com.flow.core;

/**
 * A strategy's desired end state, not a command (README "Strategies emit
 * intent. They do not place orders."). exec/ diffs this against the
 * actual position and live orders; re-submitting an identical intent is a
 * no-op there, not here.
 */
public record Intent(
    String strategyId,
    long seq,
    int targetPosition,
    Integer stopPriceTicks,   // null = no stop
    Integer targetPriceTicks, // null = no target
    String reason
) {
  public static Intent none(String strategyId, long seq) {
    return new Intent(strategyId, seq, 0, null, null, "none");
  }
}
