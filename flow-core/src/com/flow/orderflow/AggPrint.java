package com.flow.orderflow;

/**
 * One aggressor order (all fills sharing an {@code aggExchOrderId}, gapAnswers §3: a course "bubble" is the contracts
 * of a single transaction) or a burst of same-side big orders. Its price response (MFE and end-of-window price) is
 * tracked by {@link OrderFlowEngine} until the close of the next execution bar, capped at responseCapMs.
 */
public final class AggPrint {
  public enum Kind { SMALL, BIG, BURST }
  public enum Outcome { PENDING, SUCCESS, FAILURE, INCONCLUSIVE }

  public final Kind kind;
  public final long startMs;
  public final int price;          // first fill price
  public final boolean buy;        // aggressive buyer (lifted the offer)
  public final long barIndex;      // execution bar it printed in
  public double size;
  public long endMs;
  public int lastFillPrice;
  public int count = 1;            // orders in a burst
  public int mfeTicks;
  public Integer endPrice;
  public Outcome outcome = Outcome.PENDING;

  AggPrint(Kind kind, long startMs, int price, boolean buy, long barIndex, double size) {
    this.kind = kind;
    this.startMs = startMs;
    this.endMs = startMs;
    this.price = price;
    this.lastFillPrice = price;
    this.buy = buy;
    this.barIndex = barIndex;
    this.size = size;
  }

  void track(int tradePrice) {
    int move = buy ? tradePrice - price : price - tradePrice;
    if (move > mfeTicks) mfeTicks = move;
  }

  void finish(int lastPrice, OfConfig c) {
    endPrice = lastPrice;
    boolean backAgainst = buy ? lastPrice <= price : lastPrice >= price;
    if (mfeTicks >= c.responseSuccessTicks) outcome = Outcome.SUCCESS;
    else if (mfeTicks < c.responseFailMaxMfeTicks && backAgainst) outcome = Outcome.FAILURE;
    else outcome = Outcome.INCONCLUSIVE;
  }
}
