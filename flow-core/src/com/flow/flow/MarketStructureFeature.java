package com.flow.flow;

import com.flow.core.BarEvent;
import com.flow.core.BarPhase;
import com.flow.core.Event;

import java.util.ArrayList;
import java.util.List;

/**
 * D-60: implements the trend/pullback/TJL/flip system per
 * `docs/dynamic/marketStructureRules.md` (source of truth, pending
 * review) with the 7 ambiguities it left open resolved per
 * `marketStructureRulesTemp.md` -- **unreviewed, best-guess
 * resolutions**, not confirmed. Flagged in todo.md to revisit.
 *
 * Zero SDK dependency (pure bar OHLC logic) -- lives in flow-core, same
 * realization as BigTradeFeature/VWAPFeature/LiquidityMapFeature.
 * Bar-close-triggered only, matching the source system's own
 * non-repainting, close-only framing.
 */
public final class MarketStructureFeature implements MarketStructureView {
  public interface Listener {
    void onPullbackValid(Trend trend, Bar firstBar);
    void onTjlFormed(Trend trend, ZoneRange tjl1, ZoneRange tjl2);
    void onFlip(Trend newTrend, ZoneRange aPlus, ZoneRange sbrRbs, ZoneRange dtDb);
  }

  /** One bar's OHLC, integer ticks (D-21). */
  public record Bar(int openTicks, int highTicks, int lowTicks, int closeTicks) {
    boolean isGreen() { return closeTicks >= openTicks; }
    boolean isRed() { return closeTicks < openTicks; }
  }

  private final String id;
  private final Listener listener; // nullable

  private volatile boolean ready = false;
  private volatile Trend trend = Trend.UP;
  private volatile PullbackState pullbackState = PullbackState.NONE;
  private volatile ZoneRange lastTjl1;
  private volatile ZoneRange lastTjl2;
  private volatile ZoneRange lastAPlus;
  private volatile ZoneRange lastSbrRbs;
  private volatile ZoneRange lastDtDb;

  // Bootstrap (marketStructureRulesTemp.md #1/#7)
  private Integer chochTicks;
  private boolean hasRealPair = false;

  // Pullback run state (#2 anchored, #3 grows until continuation)
  private final List<Bar> pullbackBars = new ArrayList<>();

  // Tracks the candle that produced the current TJL1, plus every bar
  // since, so a future flip can scan "highest/lowest point after A+"
  // (#6) without needing to replay history.
  private Bar tjl1AnchorBar;
  private final List<Bar> barsSincePair = new ArrayList<>();

  // Flip watch (§5): consecutive closes beyond lastTjl2's far edge.
  private int flipCloseCount = 0;

  public MarketStructureFeature(String id, Listener listener) {
    this.id = id;
    this.listener = listener;
  }

  @Override public String id() { return id; }

  @Override public boolean isReady() { return ready; }

  @Override
  public String notReadyReason() {
    return ready ? null : "no bar closed yet (CHOCH not established, forward-only from attach per D-37's pattern)";
  }

  @Override
  public void onEvent(Event e) {
    if (!(e instanceof BarEvent be) || be.phase() != BarPhase.CLOSE) return;
    Bar bar = new Bar(be.openTicks(), be.highTicks(), be.lowTicks(), be.closeTicks());

    if (chochTicks == null) {
      // Bootstrap (#1): first bar's CLOSE becomes CHOCH, stands in for lastTjl2.
      chochTicks = bar.closeTicks();
      lastTjl2 = new ZoneRange(chochTicks, chochTicks);
      ready = true;
      return;
    }

    if (lastTjl1 != null) barsSincePair.add(bar); // accumulate for a possible future flip scan (#6)

    if (checkFlip(bar)) return; // flip consumes this bar; pullback detection resets for next bar
    updatePullback(bar);
  }

  private boolean checkFlip(Bar bar) {
    if (lastTjl2 == null) return false;
    int farEdge = trend == Trend.UP ? lastTjl2.lowTicks() : lastTjl2.highTicks();
    boolean beyond = trend == Trend.UP ? bar.closeTicks() < farEdge : bar.closeTicks() > farEdge;
    flipCloseCount = beyond ? flipCloseCount + 1 : 0;
    if (flipCloseCount < 2) return false;
    flipCloseCount = 0;
    performFlip(bar);
    return true;
  }

  private void performFlip(Bar flipBar) {
    Trend newTrend = trend == Trend.UP ? Trend.DOWN : Trend.UP;

    if (!hasRealPair) {
      // CHOCH-driven initial transition (§1a): no TJL1 has ever existed,
      // so there is no A+ -- just flip trend, CHOCH/lastTjl2 stays as-is,
      // reset pullback detection for the new direction.
      trend = newTrend;
      pullbackState = PullbackState.NONE;
      pullbackBars.clear();
      return;
    }

    ZoneRange newAPlus = lastTjl1;
    ZoneRange newSbrRbs = lastTjl2; // already the correct far-side value; #5 makes this explicit, not a re-derivation
    ZoneRange newDtDb = null;

    if (tjl1AnchorBar != null && !barsSincePair.isEmpty()) {
      // #6: scan from the A+ candle through the flip-confirming bar
      // (already included in barsSincePair -- see onEvent).
      if (trend == Trend.UP) { // Up->Down: DT from the highest high in the window
        Bar extreme = tjl1AnchorBar;
        for (Bar b : barsSincePair) if (b.highTicks() > extreme.highTicks()) extreme = b;
        newDtDb = dtZone(extreme);
      } else { // Down->Up: DB from the lowest low in the window
        Bar extreme = tjl1AnchorBar;
        for (Bar b : barsSincePair) if (b.lowTicks() < extreme.lowTicks()) extreme = b;
        newDtDb = dbZone(extreme);
      }
    }

    lastAPlus = newAPlus;
    lastSbrRbs = newSbrRbs;
    if (newDtDb != null) lastDtDb = newDtDb;
    lastTjl1 = null; // no "current" TJL1 until the new trend's own pullback cycle forms one
    tjl1AnchorBar = null;
    barsSincePair.clear();
    trend = newTrend;
    pullbackState = PullbackState.NONE;
    pullbackBars.clear();

    if (listener != null) listener.onFlip(newTrend, newAPlus, newSbrRbs, newDtDb);
  }

  private void updatePullback(Bar bar) {
    boolean counterTrend = trend == Trend.UP ? bar.isRed() : bar.isGreen();

    if (pullbackState == PullbackState.NONE) {
      if (counterTrend) {
        pullbackState = PullbackState.FORMING;
        pullbackBars.clear();
        pullbackBars.add(bar);
      }
      return;
    }

    if (pullbackState == PullbackState.FORMING) {
      if (!counterTrend) {
        pullbackState = PullbackState.NONE;
        pullbackBars.clear();
        return;
      }
      pullbackBars.add(bar);
      Bar first = pullbackBars.get(0);
      boolean valid = trend == Trend.UP
          ? bar.closeTicks() < first.lowTicks()
          : bar.closeTicks() > first.highTicks();
      if (valid) {
        pullbackState = PullbackState.VALID;
        if (listener != null) listener.onPullbackValid(trend, first);
      }
      return;
    }

    // VALID: keep growing while counter-trend candles continue (#3);
    // the first trend-colored candle either confirms continuation or
    // ends the attempt inconclusively (not explicitly specified by the
    // source either way -- reverting to NONE is the simplest default).
    if (counterTrend) {
      pullbackBars.add(bar);
      return;
    }

    Bar highBar = argmaxHigh(pullbackBars);
    Bar lowBar = argminLow(pullbackBars);
    int pullbackExtreme = trend == Trend.UP ? highBar.highTicks() : lowBar.lowTicks();
    boolean continuation = trend == Trend.UP
        ? bar.closeTicks() > pullbackExtreme
        : bar.closeTicks() < pullbackExtreme;

    if (continuation) {
      formTjlPair(bar, highBar, lowBar);
    }
    pullbackState = PullbackState.NONE;
    pullbackBars.clear();
  }

  private void formTjlPair(Bar continuationBar, Bar highBar, Bar lowBar) {
    ZoneRange newTjl1;
    ZoneRange newTjl2;
    Bar anchor;
    if (trend == Trend.UP) {
      newTjl1 = zoneFromHigh(highBar); // near-side / resumption pivot
      newTjl2 = zoneFromLow(lowBar);   // far-side / invalidation
      anchor = highBar;
    } else {
      newTjl2 = zoneFromHigh(highBar); // far-side / invalidation, now the top
      newTjl1 = zoneFromLow(lowBar);   // near-side
      anchor = lowBar;
    }
    lastTjl1 = newTjl1;
    lastTjl2 = newTjl2;
    tjl1AnchorBar = anchor;
    barsSincePair.clear();
    barsSincePair.add(anchor);
    barsSincePair.add(continuationBar);
    hasRealPair = true;
    if (listener != null) listener.onTjlFormed(trend, newTjl1, newTjl2);
  }

  private static Bar argmaxHigh(List<Bar> bars) {
    Bar best = bars.get(0);
    for (Bar b : bars) if (b.highTicks() > best.highTicks()) best = b;
    return best;
  }

  private static Bar argminLow(List<Bar> bars) {
    Bar best = bars.get(0);
    for (Bar b : bars) if (b.lowTicks() < best.lowTicks()) best = b;
    return best;
  }

  private static int rangeOffsetTicks(Bar b) {
    return Math.round((b.highTicks() - b.lowTicks()) * 0.01f);
  }

  /** TJL1 (uptrend) / TJL2 (downtrend): from the high, extending down into the body 1% of range (#4). */
  private static ZoneRange zoneFromHigh(Bar highBar) {
    int bodyTop = Math.max(highBar.openTicks(), highBar.closeTicks());
    return ZoneRange.of(highBar.highTicks(), bodyTop - rangeOffsetTicks(highBar));
  }

  /** TJL2 (uptrend) / TJL1 (downtrend): from the low, extending up into the body 1% of range (#4). */
  private static ZoneRange zoneFromLow(Bar lowBar) {
    int bodyBottom = Math.min(lowBar.openTicks(), lowBar.closeTicks());
    return ZoneRange.of(lowBar.lowTicks(), bodyBottom + rangeOffsetTicks(lowBar));
  }

  /** DT (Up->Down flip): from the extreme high, extending up/outward beyond the body top (#4/#6). */
  private static ZoneRange dtZone(Bar highBar) {
    int bodyTop = Math.max(highBar.openTicks(), highBar.closeTicks());
    return ZoneRange.of(highBar.highTicks(), bodyTop + rangeOffsetTicks(highBar));
  }

  /** DB (Down->Up flip): from the extreme low, extending down/outward beyond the body bottom (#4/#6). */
  private static ZoneRange dbZone(Bar lowBar) {
    int bodyBottom = Math.min(lowBar.openTicks(), lowBar.closeTicks());
    return ZoneRange.of(lowBar.lowTicks(), bodyBottom - rangeOffsetTicks(lowBar));
  }

  @Override public Trend trend() { return trend; }
  @Override public PullbackState pullbackState() { return pullbackState; }
  @Override public ZoneRange lastTjl1() { return lastTjl1; }
  @Override public ZoneRange lastTjl2() { return lastTjl2; }
  @Override public ZoneRange lastAPlus() { return lastAPlus; }
  @Override public ZoneRange lastSbrRbs() { return lastSbrRbs; }
  @Override public ZoneRange lastDtDb() { return lastDtDb; }
}
