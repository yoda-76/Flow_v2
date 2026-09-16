package com.flow.flow;

/**
 * A feature that exposes one or more named, moving price levels (D-38's
 * NamedLevel) -- POC/VAH/VAL today (VolumeProfileView), extensible later
 * to prior-session levels, VWAP, etc. without touching Trigger.java.
 */
public interface LevelSource {
  /** Current tick-offset value of the named level, or null if not ready/unknown. */
  Integer levelValue(String levelName);

  /**
   * Row/bucket distance of priceTicks from this source's own natural
   * zero-reference (POC for VolumeProfileView) -- 0 at the reference,
   * positive above, negative below, magnitude growing with distance.
   * Null if this source has no such reference, or isn't ready yet.
   * Default: unsupported: not every LevelSource necessarily has a
   * natural zero-anchor among its own levels.
   */
  default Integer relativeRow(int priceTicks) {
    return null;
  }
}
