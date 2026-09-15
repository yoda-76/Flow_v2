package com.flow.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-trigger wake state (D-16's dynamic registration). One instance per
 * strategy -- a strategy declaring the same Trigger.Throttle/PriceCross/
 * BookChange twice would share state, which is intentionally not
 * supported (declare a distinct instance per distinct thing you want to
 * watch).
 */
final class TriggerEvaluator {
  private final Map<Trigger, Long> lastWakeMs = new HashMap<>();
  private final Map<Trigger, Boolean> lastAbove = new HashMap<>();
  private final Map<Trigger, Double> lastBookSize = new HashMap<>();

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
    throw new IllegalStateException("unhandled Trigger type: " + t.getClass());
  }

  private static Integer priceOf(Event e) {
    if (e instanceof TickEvent te) return te.priceTicks();
    if (e instanceof BarEvent be) return be.closeTicks();
    return null;
  }
}
