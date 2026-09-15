package com.flow.core;

/**
 * When a strategy gets woken, declared per-strategy rather than a global
 * cadence (D-16). Dynamic registration (PriceCross/BookChange carrying
 * their own parameter) is what keeps event-level decisions affordable --
 * a strategy watching one level sleeps until price is near it instead of
 * being woken on every tick.
 *
 * D-38's NamedLevel/NamedZone triggers (LEVEL_CROSS/ZONE_TRANSITION) are
 * a deliberately separate addition, made when VolumeProfileView itself is
 * built -- not speculatively added here ahead of the feature that needs
 * them.
 */
public sealed interface Trigger {
  record BarClose() implements Trigger {}
  record EveryTick() implements Trigger {}
  record Throttle(long millis) implements Trigger {}
  record PriceCross(int levelTicks) implements Trigger {}
  record BookChange(double minSize) implements Trigger {}
}
