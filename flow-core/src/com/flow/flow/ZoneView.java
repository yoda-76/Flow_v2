package com.flow.flow;

/**
 * One LVN/HVN cluster, in our own vocabulary (D-38). id is persistent
 * across recomputes for as long as the underlying zone is judged to be
 * "the same" one (price-range-overlap matching, largest-overlap-wins on
 * split/merge) -- not just a positional index, which is what makes
 * D-39's per-zone track record possible at all.
 */
public record ZoneView(String id, Kind kind, int lowPriceTicks, int highPriceTicks) {
  public enum Kind { LVN, HVN }
}
