package com.flow.bars.ind;

/** Exponential moving average, alpha = 2/(n+1), incremental. */
public final class Ema {
  /** FIRST_VALUE: seed with the first sample. SMA_N: value is null until n samples, seeded with their mean. */
  public enum SeedMode { FIRST_VALUE, SMA_N }

  private final int n;
  private final SeedMode mode;
  private final double alpha;
  private int count = 0;
  private double sum = 0;
  private Double value = null;

  public Ema(int n, SeedMode mode) {
    if (n < 1) throw new IllegalArgumentException("n < 1");
    this.n = n;
    this.mode = mode;
    this.alpha = 2.0 / (n + 1);
  }

  public void update(double x) {
    count++;
    if (value != null) {
      value = alpha * x + (1 - alpha) * value;
      return;
    }
    if (mode == SeedMode.FIRST_VALUE) {
      value = x;
    } else {
      sum += x;
      if (count == n) value = sum / n;
    }
  }

  public Double value() {
    return value;
  }
}
