package com.flow.flow;

/**
 * D-27's second entry evaluator: aggression, read from big trades.
 * Stateless -- a pure function of BigTradeView's current recent window
 * plus a caller-supplied "now" for recency filtering (a big trade from
 * hours ago at this price isn't a live confirmation).
 */
public final class AggressionEvaluator {
  private AggressionEvaluator() {}

  public static boolean aggressionAt(BigTradeView bigTrades, int priceTicks, int toleranceTicks,
                                      boolean bullishBias, long nowEventTimeMs, long recencyMs) {
    if (bigTrades == null || !bigTrades.isReady()) return false;
    for (BigTradeEvent t : bigTrades.recent()) {
      if (nowEventTimeMs - t.eventTimeMs() > recencyMs) continue;
      if (Math.abs(t.priceTicks() - priceTicks) > toleranceTicks) continue;
      // isAskTick == buy aggressor ("+ve"), matching D-54's own green/red convention.
      // bullishBias wants buy aggression confirming a long; bearish wants sell aggression.
      if (t.isAskTick() == bullishBias) return true;
    }
    return false;
  }
}
