package com.flow.orderflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One execution bar (default 500 trades, E3) with its footprint, built from TickEvents by {@link OrderFlowEngine}.
 * Mutable while forming; {@link #close} computes the per-bar reads (gapAnswers §3/§4) once and freezes it.
 *
 * Footprint rows are {@code floorDiv(priceTicks, rowTicks)}; a row's price is {@code row * rowTicks} (its low edge).
 * Bid volume = aggressive sells (hit the bid), ask volume = aggressive buys (lifted the offer). Delta = ask - bid.
 */
public final class OfBar {
  public final long index;
  public final long startMs;
  public long endMs;
  public int open, high, low, close;
  public long trades;
  public double volume, delta;
  final int rowTicks;
  /** row -> {bid, ask} */
  final TreeMap<Integer, double[]> rows = new TreeMap<>();

  // ---- reads, set by close() ----
  public boolean closed;
  public final List<Integer> bullImbalanceRows = new ArrayList<>(); // ask(r) vs bid(r-1)
  public final List<Integer> bearImbalanceRows = new ArrayList<>(); // bid(r) vs ask(r+1)
  public boolean stackedBull, stackedBear;
  public int stackedBullLowRow = Integer.MIN_VALUE, stackedBearHighRow = Integer.MIN_VALUE;
  /** Finished auction at the high = top row bid == 0 and ask > 0; at the low = bottom row ask == 0 and bid > 0 (§3). */
  public boolean finishedHigh, finishedLow, unfinishedHigh, unfinishedLow;
  public int volumeClusterPrice;
  public double closeLocation;      // (close-low)/(high-low), 0.5 when range is 0
  public boolean dominanceBull, dominanceBear;
  public double maxAskAtHigh, maxBidAtLow; // aggressive volume on the extreme rows (for exhaustion)

  OfBar(long index, long startMs, int price, int rowTicks) {
    this.index = index;
    this.startMs = startMs;
    this.endMs = startMs;
    this.open = this.high = this.low = this.close = price;
    this.rowTicks = rowTicks;
  }

  void add(long timeMs, int price, double size, boolean isAsk) {
    endMs = timeMs;
    trades++;
    if (price > high) high = price;
    if (price < low) low = price;
    close = price;
    volume += size;
    delta += isAsk ? size : -size;
    double[] r = rows.computeIfAbsent(Math.floorDiv(price, rowTicks), k -> new double[2]);
    if (isAsk) r[1] += size; else r[0] += size;
  }

  public int rowOf(int price) { return Math.floorDiv(price, rowTicks); }
  public int rowPrice(int row) { return row * rowTicks; }
  public double bidAt(int row) { double[] r = rows.get(row); return r == null ? 0 : r[0]; }
  public double askAt(int row) { double[] r = rows.get(row); return r == null ? 0 : r[1]; }
  public Map<Integer, double[]> rowsView() { return java.util.Collections.unmodifiableMap(rows); }
  public int range() { return high - low; }
  public boolean up() { return close > open; }
  public boolean down() { return close < open; }

  void close(OfConfig c) {
    closed = true;
    int hiRow = rowOf(high), loRow = rowOf(low);
    // Diagonal imbalance (S1: direction = the general footprint convention; verify against MotiveWave, gapAnswers §13).
    for (int r = loRow; r <= hiRow; r++) {
      double ask = askAt(r), bidBelow = bidAt(r - 1);
      if (ask >= c.imbalanceMinVolume && ask >= c.imbalanceRatio * bidBelow && r - 1 >= loRow) bullImbalanceRows.add(r);
      double bid = bidAt(r), askAbove = askAt(r + 1);
      if (bid >= c.imbalanceMinVolume && bid >= c.imbalanceRatio * askAbove && r + 1 <= hiRow) bearImbalanceRows.add(r);
    }
    int[] bull = longestRun(bullImbalanceRows);
    int[] bear = longestRun(bearImbalanceRows);
    stackedBull = bull[1] >= c.stackedMinRows;
    stackedBear = bear[1] >= c.stackedMinRows;
    if (stackedBull) stackedBullLowRow = bull[0];
    if (stackedBear) stackedBearHighRow = bear[0] + bear[1] - 1;
    double topBid = bidAt(hiRow), topAsk = askAt(hiRow), botBid = bidAt(loRow), botAsk = askAt(loRow);
    finishedHigh = topBid == 0 && topAsk > 0;
    finishedLow = botAsk == 0 && botBid > 0;
    unfinishedHigh = topBid > 0 && topAsk > 0;
    unfinishedLow = botBid > 0 && botAsk > 0;
    maxAskAtHigh = topAsk;
    maxBidAtLow = botBid;
    double best = -1;
    for (Map.Entry<Integer, double[]> e : rows.entrySet()) {
      double v = e.getValue()[0] + e.getValue()[1];
      if (v > best) { best = v; volumeClusterPrice = rowPrice(e.getKey()); }
    }
    closeLocation = range() == 0 ? 0.5 : (close - low) / (double) range();
    dominanceBull = bullImbalanceRows.size() >= c.dominanceMinImbalances && bearImbalanceRows.size() <= c.dominanceMaxOpposite;
    dominanceBear = bearImbalanceRows.size() >= c.dominanceMinImbalances && bullImbalanceRows.size() <= c.dominanceMaxOpposite;
  }

  /** {startRow, length} of the longest run of consecutive rows in an ascending list. */
  static int[] longestRun(List<Integer> sortedRows) {
    int bestStart = 0, bestLen = 0, curStart = 0, curLen = 0;
    Integer prev = null;
    for (int r : sortedRows) {
      if (prev != null && r == prev + 1) curLen++;
      else { curStart = r; curLen = 1; }
      if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
      prev = r;
    }
    return new int[]{bestStart, bestLen};
  }
}
