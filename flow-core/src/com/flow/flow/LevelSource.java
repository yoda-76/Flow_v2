package com.flow.flow;

/**
 * A feature that exposes one or more named, moving price levels (D-38's
 * NamedLevel) -- POC/VAH/VAL today (VolumeProfileView), extensible later
 * to prior-session levels, VWAP, etc. without touching Trigger.java.
 */
public interface LevelSource {
  /** Current tick-offset value of the named level, or null if not ready/unknown. */
  Integer levelValue(String levelName);
}
