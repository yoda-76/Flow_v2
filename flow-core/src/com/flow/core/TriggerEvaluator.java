package com.flow.core;

import com.flow.flow.LevelSource;
import com.flow.flow.ZoneSource;
import com.flow.flow.ZoneView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-trigger wake state (D-16's dynamic registration). One instance per
 * strategy -- a strategy declaring the same Trigger.Throttle/PriceCross/
 * BookChange/LevelCross/ZoneTransition twice would share state, which is
 * intentionally not supported (declare a distinct instance per distinct
 * thing you want to watch).
 *
 * LevelCross/ZoneTransition (D-38) look up the relevant Feature by id in
 * the features map Pipeline already owns, and only act if that Feature
 * implements LevelSource/ZoneSource -- this class never references
 * VolumeProfileView or any other concrete feature by name.
 */
final class TriggerEvaluator {
  private final Map<Trigger, Long> lastWakeMs = new HashMap<>();
  private final Map<Trigger, Boolean> lastAbove = new HashMap<>();
  private final Map<Trigger, Double> lastBookSize = new HashMap<>();
  private final Map<Trigger, String> lastZoneId = new HashMap<>();
  private final Map<Trigger, int[]> lastZoneRange = new HashMap<>();

  private final Map<String, Feature> features;

  TriggerEvaluator(Map<String, Feature> features) {
    this.features = features;
  }

  boolean shouldWake(Trigger t, Event e, long nowMs) {
    if (t instanceof Trigger.BarClose) {
      return e instanceof BarEvent be && be.phase() == BarPhase.CLOSE;
    }
    if (t instanceof Trigger.EveryTick) {
      return e instanceof TickEvent;
    }
    if (t instanceof Trigger.Throttle th) {
      Long last = lastWakeMs.get(t);
      if (last != null && nowMs - last < th.millis()) return false;
      lastWakeMs.put(t, nowMs);
      return true;
    }
    if (t instanceof Trigger.PriceCross pc) {
      Integer price = priceOf(e);
      if (price == null) return false;
      boolean above = price > pc.levelTicks();
      Boolean prev = lastAbove.put(t, above);
      return prev != null && prev != above;
    }
    if (t instanceof Trigger.BookChange bch) {
      if (!(e instanceof DomEvent de)) return false;
      double size = de.bestBidSize() + de.bestAskSize();
      Double prev = lastBookSize.put(t, size);
      return prev != null && Math.abs(size - prev) >= bch.minSize();
    }
    if (t instanceof Trigger.LevelCross lc) {
      return checkLevelCross(t, lc, e);
    }
    if (t instanceof Trigger.ZoneTransition zt) {
      return checkZoneTransition(t, zt, e);
    }
    throw new IllegalStateException("unhandled Trigger type: " + t.getClass());
  }

  /**
   * Cross cause is deliberately cause-agnostic (D-38): fires on any flip
   * in relative position between price and the level, whether price
   * moved, the level moved (POC/VAH/VAL recompute independently every
   * tick), or both.
   */
  private boolean checkLevelCross(Trigger key, Trigger.LevelCross lc, Event e) {
    Integer price = priceOf(e);
    if (price == null) return false;
    Feature f = features.get(lc.featureId());
    if (!(f instanceof LevelSource ls)) return false;
    Integer level = ls.levelValue(lc.levelName());
    if (level == null) return false;

    if (lc.kind() == Trigger.LevelCross.CrossKind.TOUCH) {
      return price.intValue() == level.intValue();
    }

    boolean above = price > level;
    Boolean prevAbove = lastAbove.put(key, above);
    if (prevAbove == null || prevAbove == above) return false; // no prior reading, or no flip
    return switch (lc.kind()) {
      case CROSS_ABOVE -> above;
      case CROSS_BELOW -> !above;
      case TOUCH -> false; // unreachable, handled above
    };
  }

  /**
   * Wakes on ANY zone of the declared kind transitioning, not one
   * specific zone id. LEAVE is debounced (D-38): not confirmed until
   * price clears the zone's own width past its last known range, so a
   * fast re-entry at the boundary continues the same open trial rather
   * than firing spurious ENTER/LEAVE pairs.
   */
  private boolean checkZoneTransition(Trigger key, Trigger.ZoneTransition zt, Event e) {
    Integer price = priceOf(e);
    if (price == null) return false;
    Feature f = features.get(zt.featureId());
    if (!(f instanceof ZoneSource zs)) return false;
    List<ZoneView> candidates = zs.zonesOfKind(zt.zoneKind());

    if (zt.kind() == Trigger.ZoneTransition.TransitionKind.TOUCH) {
      for (ZoneView z : candidates) {
        if (price == z.lowPriceTicks() || price == z.highPriceTicks()) return true;
      }
      return false;
    }

    ZoneView containing = null;
    for (ZoneView z : candidates) {
      if (price >= z.lowPriceTicks() && price <= z.highPriceTicks()) {
        containing = z;
        break;
      }
    }

    String prevId = lastZoneId.get(key);

    if (containing != null) {
      boolean isNewMembership = !Objects.equals(containing.id(), prevId);
      lastZoneId.put(key, containing.id());
      lastZoneRange.put(key, new int[]{containing.lowPriceTicks(), containing.highPriceTicks()});
      return isNewMembership && zt.kind() == Trigger.ZoneTransition.TransitionKind.ENTER;
    }

    // price is outside every current zone of this kind
    if (prevId == null) return false; // already outside, nothing changed
    int[] prevRange = lastZoneRange.get(key);
    int width = Math.max(1, prevRange[1] - prevRange[0] + 1);
    boolean clearedBelow = price < prevRange[0] - width;
    boolean clearedAbove = price > prevRange[1] + width;
    if (!clearedBelow && !clearedAbove) {
      return false; // inside the debounce margin -- same open trial, not a confirmed LEAVE
    }
    lastZoneId.remove(key);
    lastZoneRange.remove(key);
    return zt.kind() == Trigger.ZoneTransition.TransitionKind.LEAVE;
  }

  private static Integer priceOf(Event e) {
    if (e instanceof TickEvent te) return te.priceTicks();
    if (e instanceof BarEvent be) return be.closeTicks();
    return null;
  }
}
