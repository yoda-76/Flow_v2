package com.flow.flow;

/**
 * One LVN/HVN cluster, in our own vocabulary (D-38). id is persistent
 * across recomputes for as long as the underlying zone is judged to be
 * "the same" one (price-range-overlap matching, largest-overlap-wins on
 * split/merge) -- not just a positional index, which is what makes
 * D-39's per-zone track record possible at all.
 *
 * firstSeenAtMs is the event time (not wall clock) this zone's id was
 * first assigned -- unchanged across recomputes for as long as the same
 * id persists, even as lowPriceTicks/highPriceTicks shift. A consumer
 * computes age as (current event time - firstSeenAtMs) rather than this
 * record carrying a precomputed, constantly-stale age itself.
 */
public record ZoneView(String id, Kind kind, int lowPriceTicks, int highPriceTicks, long firstSeenAtMs) {
  public enum Kind { LVN, HVN }
}
