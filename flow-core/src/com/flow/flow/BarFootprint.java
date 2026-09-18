package com.flow.flow;

import java.util.List;

/**
 * One bar's footprint as a single cohesive value, not several parallel
 * accessors -- startMs/endMs (event time, not wall clock) are the bar's
 * own boundaries, needed to draw footprint figures positioned at the
 * actual candle rather than some other time range. rows is never null
 * (empty before the first tick of a bar, or before D-37's partial-bar
 * discard resolves).
 */
public record BarFootprint(long startMs, long endMs, List<FootprintRow> rows, double totalVolume, double totalDelta) {
  public static BarFootprint empty(long startMs) {
    return new BarFootprint(startMs, startMs, List.of(), 0, 0);
  }
}
