package com.flow.bars.ind;

/** Wilder ATR(n): first bar's TR = high-low, first ATR = mean of the first n TRs, then (prev*(n-1)+TR)/n. */
public final class WilderAtr {
  private final int n;
  private int count = 0;
  private double pC;
  private double sum = 0;
  private Double atr = null;

  public WilderAtr(int n) {
    if (n < 1) throw new IllegalArgumentException("n < 1");
    this.n = n;
  }

  public void update(double h, double l, double c) {
    double tr = count == 0 ? h - l : Math.max(h - l, Math.max(Math.abs(h - pC), Math.abs(l - pC)));
    pC = c;
    count++;
    if (atr != null) {
      atr = (atr * (n - 1) + tr) / n;
    } else {
      sum += tr;
      if (count == n) atr = sum / n;
    }
  }

  public Double value() {
    return atr;
  }
}
