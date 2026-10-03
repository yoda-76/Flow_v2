package com.flow.backtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV read + bar-size aggregation for the generic engine's {@link Bar} type -- lifted verbatim (same logic, same
 * edge-case handling) from {@code MarketStructureBacktest}'s own static methods, which keep their original copy
 * untouched for that class's own, still-passing tests. Reads the exact shape {@code HistoricalOhlcExporter}
 * (`../motivewave/experiments`) writes: {@code timestampMs,open,high,low,close,volume}.
 */
public final class BacktestCsv {
  private BacktestCsv() {}

  public static List<Bar> readCsv(Path path) throws IOException {
    List<Bar> bars = new ArrayList<>();
    try (BufferedReader r = Files.newBufferedReader(path)) {
      r.readLine(); // header
      String line;
      while ((line = r.readLine()) != null) {
        line = line.strip();
        if (line.isEmpty()) continue;
        String[] f = line.split(",");
        bars.add(new Bar(
            Long.parseLong(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2]),
            Double.parseDouble(f[3]), Double.parseDouble(f[4]), (long) Double.parseDouble(f[5])));
      }
    }
    return bars;
  }

  /**
   * Buckets each bar's OWN start time to the nearest {@code intervalMs} boundary ({@code Math.floorDiv}), not by
   * grouping every N rows -- a gap in the source data (weekend, daily halt, a feed drop) would otherwise silently
   * misalign row-count-based grouping. Same method as {@code MarketStructureBacktest.aggregate}.
   */
  public static List<Bar> aggregate(List<Bar> bars, long intervalMs) {
    List<Bar> out = new ArrayList<>();
    long bucketStart = -1;
    double open = 0, high = 0, low = 0, close = 0;
    long volume = 0;
    for (Bar b : bars) {
      long bucket = Math.floorDiv(b.timestampMs(), intervalMs) * intervalMs;
      if (bucket != bucketStart) {
        if (bucketStart != -1) out.add(new Bar(bucketStart, open, high, low, close, volume));
        bucketStart = bucket;
        open = b.open();
        high = b.high();
        low = b.low();
        volume = 0;
      }
      high = Math.max(high, b.high());
      low = Math.min(low, b.low());
      close = b.close();
      volume += b.volume();
    }
    if (bucketStart != -1) out.add(new Bar(bucketStart, open, high, low, close, volume));
    return out;
  }
}
