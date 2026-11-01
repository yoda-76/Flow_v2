package com.flow.bars;

import com.flow.core.BarEvent;
import com.flow.core.BarPhase;
import com.flow.core.Event;

/**
 * Ring buffer of the last `capacity` closed bars. Fed by live BarEvent(CLOSE)s through the pipeline and, at start-up,
 * by seed() from the chart's history (the same code path, so warm-start and live cannot disagree).
 *
 * A bar whose start is not strictly after the newest stored start is dropped and counted (duplicate delivery or
 * out-of-order data must never corrupt the series a strategy resamples from).
 *
 * NOT thread-safe by design: written and read only on the pipeline thread (README "The event stream"); the runtime's
 * warm-start runs before the pipeline exists.
 */
public final class BarHistoryFeature implements BarHistoryView {
  private final int capacity;
  private final long barMs;
  private final long[] starts;
  private final int[] opens;
  private final int[] highs;
  private final int[] lows;
  private final int[] closes;
  private final long[] volumes;

  private int next = 0;      // write position
  private int count = 0;
  private long appended = 0;
  private long dropped = 0;
  private long lastStart = Long.MIN_VALUE;

  public BarHistoryFeature(int capacity, long barMs) {
    if (capacity < 1) throw new IllegalArgumentException("capacity < 1");
    if (barMs < 1) throw new IllegalArgumentException("barMs < 1");
    this.capacity = capacity;
    this.barMs = barMs;
    this.starts = new long[capacity];
    this.opens = new int[capacity];
    this.highs = new int[capacity];
    this.lows = new int[capacity];
    this.closes = new int[capacity];
    this.volumes = new long[capacity];
  }

  @Override public String id() { return FEATURE_ID; }

  @Override
  public void onEvent(Event e) {
    if (e instanceof BarEvent be && be.phase() == BarPhase.CLOSE) append(be);
  }

  /** Warm-start entry: identical to a live close. */
  public void seed(BarEvent be) {
    if (be.phase() == BarPhase.CLOSE) append(be);
  }

  private void append(BarEvent be) {
    long start = CtTime.barStartMs(be.eventTimeMs(), barMs);
    if (start <= lastStart) {
      dropped++;
      return;
    }
    starts[next] = start;
    opens[next] = be.openTicks();
    highs[next] = be.highTicks();
    lows[next] = be.lowTicks();
    closes[next] = be.closeTicks();
    volumes[next] = be.volume();
    next = (next + 1) % capacity;
    if (count < capacity) count++;
    appended++;
    lastStart = start;
  }

  private int slot(int i) {
    if (i < 0 || i >= count) throw new IndexOutOfBoundsException("bar index " + i + " size " + count);
    return Math.floorMod(next - count + i, capacity);
  }

  @Override public int size() { return count; }
  @Override public long startMs(int i) { return starts[slot(i)]; }
  @Override public int open(int i) { return opens[slot(i)]; }
  @Override public int high(int i) { return highs[slot(i)]; }
  @Override public int low(int i) { return lows[slot(i)]; }
  @Override public int close(int i) { return closes[slot(i)]; }
  @Override public long volume(int i) { return volumes[slot(i)]; }
  @Override public long appended() { return appended; }
  @Override public long barMs() { return barMs; }

  /** Bars rejected as duplicate / out of order since construction (diagnostic). */
  public long dropped() { return dropped; }

  @Override public boolean isReady() { return count >= 1; }

  @Override
  public String notReadyReason() {
    return count >= 1 ? null : "no closed bar seen yet (warm-start empty and no live bar has closed)";
  }
}
