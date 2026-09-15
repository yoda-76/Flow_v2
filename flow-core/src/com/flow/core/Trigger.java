package com.flow.core;

import com.flow.flow.ZoneView;

/**
 * When a strategy gets woken, declared per-strategy rather than a global
 * cadence (D-16). Dynamic registration (PriceCross/BookChange carrying
 * their own parameter) is what keeps event-level decisions affordable --
 * a strategy watching one level sleeps until price is near it instead of
 * being woken on every tick.
 *
 * LevelCross/ZoneTransition are D-38's NamedLevel/NamedZone triggers,
 * added now that VolumeProfileView (D-44) exists to attach them to --
 * they were deliberately not added speculatively ahead of it. Both are
 * feature-agnostic: featureId + levelName/zoneKind, not hardcoded to
 * volume profile, so a future feature (prior-session levels, VWAP, ...)
 * can participate by implementing LevelSource/ZoneSource without any
 * change here. Evaluated by TriggerEvaluator against whichever
 * registered Feature implements those interfaces.
 */
public sealed interface Trigger {
  record BarClose() implements Trigger {}
  record EveryTick() implements Trigger {}
  record Throttle(long millis) implements Trigger {}
  record PriceCross(int levelTicks) implements Trigger {}
  record BookChange(double minSize) implements Trigger {}

  /**
   * D-38's NamedLevel. Cross cause is deliberately cause-agnostic
   * (fires whether price or the level itself moved) per D-38's decision.
   */
  record LevelCross(String featureId, String levelName, CrossKind kind) implements Trigger {
    public enum CrossKind { TOUCH, CROSS_ABOVE, CROSS_BELOW }
  }

  /**
   * D-38's NamedZone. Wakes on ANY zone of the given kind transitioning,
   * not one specific zone id -- matches the original ask ("whenever
   * price touches/enters/leaves LVN HVN"), a strategy inspects
   * ZoneSource.zonesOfKind() itself to see which one.
   */
  record ZoneTransition(String featureId, ZoneView.Kind zoneKind, TransitionKind kind) implements Trigger {
    public enum TransitionKind { ENTER, LEAVE, TOUCH }
  }
}
