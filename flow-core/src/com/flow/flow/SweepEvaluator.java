package com.flow.flow;

/**
 * D-27's third entry evaluator: liquidity grab/sweep, read from the
 * live liquidity map. Unlike Absorption/AggressionEvaluator this one is
 * genuinely **stateful** -- detecting a sweep means detecting *change*
 * (resting size that was there, then wasn't), which a single point-in-
 * time read of LiquidityMapView can't tell on its own. A strategy owns
 * one instance of this per area-of-interest attempt and calls reset()
 * whenever that area of interest changes (a fresh attempt shouldn't
 * compare against a stale prior reading from a different price/setup).
 *
 * v1 proxy: watches the near side's resting size at the touched price
 * (bid side for a bullish/long setup -- the support liquidity price is
 * pressing into; ask side for bearish) and fires once it drops by at
 * least half since the last check. A real stop-order sweep isn't
 * visible in resting-limit-order DOM data at all (stops aren't resting
 * limit orders) -- this is the DOM-visible proxy the user's own
 * framing asked for ("liquidity grab/sweep (heatmap)"), not a claim
 * that it detects the same thing a real stop-hunt would.
 */
public final class SweepEvaluator {
  private static final double DROP_RATIO_THRESHOLD = 0.5; // arbitrary v1 threshold

  private Double previousSize;

  public boolean sweepAt(LiquidityMapView lm, int priceTicks, boolean bullishBias) {
    if (lm == null || !lm.isReady()) return false;
    Double currentSize = bullishBias ? lm.bidSizeAt(priceTicks) : lm.askSizeAt(priceTicks);
    boolean swept = false;
    if (previousSize != null && currentSize != null && previousSize > 0) {
      double dropRatio = (previousSize - currentSize) / previousSize;
      if (dropRatio >= DROP_RATIO_THRESHOLD) swept = true;
    }
    previousSize = currentSize;
    return swept;
  }

  public void reset() {
    previousSize = null;
  }
}
