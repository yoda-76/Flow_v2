package com.flow.bars;

import java.time.LocalDate;

/**
 * Turns 1-minute bars into N-minute bars aligned to the Chicago clock: bucket = (CT date, CT minute-of-day / N), the
 * same bucketing as the Python backtests (bucket = minute // N within the CT date).
 *
 * Completion rule -- a bucket is COMPLETED, and returned, as soon as either
 *   (a) the incoming 1m bar is the bucket's last minute (minuteOfDay % N == N-1): returned including that bar, so a
 *       strategy can act on the bar the moment its last minute closes (no waiting for the next bucket's first bar); or
 *   (b) a 1m bar of a LATER bucket arrives while the previous bucket is still open (its last minute never traded):
 *       the previous bucket is returned WITHOUT the new bar, which starts the next bucket.
 * Not thread-safe (pipeline thread only). Input must be in time order (BarHistoryFeature guarantees that).
 */
public final class Resampler {
  /** A completed N-minute bar; prices in ticks. startMs is the bucket's nominal start (first 1m bar seen minus its offset). */
  public record Bar(LocalDate ctDate, int bucketStartMinute, long startMs,
                    int open, int high, int low, int close, long volume, int minuteBars) {}

  private final int n;

  private boolean open = false;
  private LocalDate date;
  private int bucket;
  private long startMs;
  private int o, h, l, c;
  private long vol;
  private int bars;

  public Resampler(int minutesPerBar) {
    if (minutesPerBar < 1 || 1440 % minutesPerBar != 0) {
      throw new IllegalArgumentException("minutesPerBar must divide 1440: " + minutesPerBar);
    }
    this.n = minutesPerBar;
  }

  /** Feed one closed 1m bar (start time, OHLC ticks, volume). Returns a completed N-minute bar or null. */
  public Bar onMinuteBar(long startMs, int o1, int h1, int l1, int c1, long v1) {
    LocalDate d = CtTime.ctDate(startMs);
    int m = CtTime.ctMinuteOfDay(startMs);
    int b = m / n;

    Bar completed = null;
    if (open && (b != bucket || !d.equals(date))) {
      completed = snapshot();   // rule (b)
      open = false;
    }
    if (!open) {
      open = true;
      date = d;
      bucket = b;
      this.startMs = startMs - (long) (m - b * n) * 60_000L;
      o = o1; h = h1; l = l1; c = c1; vol = v1; bars = 1;
    } else {
      h = Math.max(h, h1);
      l = Math.min(l, l1);
      c = c1;
      vol += v1;
      bars++;
    }
    if (completed != null) {
      // Rule (b) fired. If this bar is ALSO the new bucket's last minute (the earlier minutes of that bucket never
      // traded), the new bucket stays open and completes by rule (b) later -- one return value per input, no loss.
      return completed;
    }
    if (m % n == n - 1) {       // rule (a)
      Bar last = snapshot();
      open = false;
      return last;
    }
    return null;
  }

  private Bar snapshot() {
    return new Bar(date, bucket * n, startMs, o, h, l, c, vol, bars);
  }
}
