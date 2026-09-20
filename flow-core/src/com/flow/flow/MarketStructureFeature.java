package com.flow.flow;

import com.flow.core.BarEvent;
import com.flow.core.BarPhase;
import com.flow.core.Event;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * D-60, reworked D-74: implements the trend/pullback/TJL/flip system per
 * `docs/dynamic/marketStructureRules.md` -- now the fully reviewed
 * source of truth, all 10 points answered directly by the user
 * (`decisions.md` D-71/D-72/D-73). `marketStructureRulesTemp.md`'s
 * guesses are superseded; where this rework corrects an actual behavior
 * change (not just a doc update) is called out inline below.
 *
 * Zero SDK dependency (pure bar OHLC logic) -- lives in flow-core, same
 * realization as BigTradeFeature/VWAPFeature/LiquidityMapFeature.
 * Bar-close-triggered only, matching the source system's own
 * non-repainting, close-only framing.
 */
public final class MarketStructureFeature implements MarketStructureView {
  public interface Listener {
    void onPullbackValid(Trend trend, Bar firstBar);
    void onTjlFormed(Trend trend, ZoneRange tjl1, ZoneRange tjl2, Set<TradeableLevel> tradeableLevels);
    /** dtOrDb is whichever of DT/DB THIS flip freshly computed -- the other one (if any) is unchanged and readable via lastDt()/lastDb(). */
    void onFlip(Trend newTrend, ZoneRange aPlus, ZoneRange sbrRbs, ZoneRange dtOrDb, Set<TradeableLevel> tradeableLevels);
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
  private volatile ZoneRange lastDt;
  private volatile ZoneRange lastDb;
  private volatile Set<TradeableLevel> tradeableLevels = Set.of();

  // Bootstrap (§1a, points 1/3/7/8): CHOCH = the first-processed bar's
  // OPEN (corrected -- was CLOSE), warm-start bars count as "first" (point 8).
  private Integer chochTicks;
  private boolean hasRealPair = false;

  // Pullback run state (§2, points 1/2/3: sliding consecutive-pair
  // validation -- corrected from "anchored to the run's first candle" --
  // then the ENTIRE run remains the pullback, growing until it ends).
  private final List<Bar> pullbackBars = new ArrayList<>();

  // DT/DB search-window anchor (§6, point 6: starts at the SBR/RBS
  // candle's own open -- corrected from "the A+ candle" -- then chains
  // forward from each subsequently-computed DT/DB's own candle). Replaces
  // the old tjl1AnchorBar entirely -- A+'s own candle is no longer the
  // window anchor for anything, so tracking it separately isn't needed.
  private Bar windowAnchorBar;
  private final List<Bar> barsSinceAnchor = new ArrayList<>();

  // Point 9: true from the chain's first flip onward, reset to false the
  // instant a fresh real TJL1/TJL2 pair forms (point 10). Distinguishes
  // "this flip is the chain's first link" (A+/SBR/RBS become tradeable)
  // from "this flip is the chain's second-or-later link" (A+/SBR/RBS are
  // no longer reassigned OR tradeable at all, per point 9's own worked
  // example never mentioning them past the first link).
  private boolean chainStarted = false;

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
    return ready ? null : "no bar processed yet (CHOCH not established, forward-only from attach/warm-start per D-37/D-64)";
  }

  @Override
  public void onEvent(Event e) {
    if (!(e instanceof BarEvent be) || be.phase() != BarPhase.CLOSE) return;
    Bar bar = new Bar(be.openTicks(), be.highTicks(), be.lowTicks(), be.closeTicks());

    if (chochTicks == null) {
      // Bootstrap (points 1/3/7/8): the first bar PROCESSED -- historical
      // warm-start or live, whichever comes first -- has its OPEN taken
      // as CHOCH, standing in for lastTjl2. Never tradeable (§1a):
      // tradeableLevels() stays empty the whole time CHOCH is active.
      chochTicks = bar.openTicks();
      lastTjl2 = new ZoneRange(chochTicks, chochTicks);
      ready = true;
      return;
    }

    if (hasRealPair) barsSinceAnchor.add(bar); // accumulate for a possible future flip's DT/DB scan (§6)

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
      // reset pullback detection for the new direction. Unchanged by
      // this rework -- none of the reviewed points touch this branch.
      trend = newTrend;
      pullbackState = PullbackState.NONE;
      pullbackBars.clear();
      return;
    }

    boolean firstLinkOfChain = !chainStarted;

    // §6 point 6: scan windowAnchorBar's own candle through this flip-
    // confirming bar (already included in barsSinceAnchor -- see onEvent)
    // for the extreme that becomes DT (Up->Down) or DB (Down->Up).
    ZoneRange freshDtOrDb = null;
    Bar freshAnchorBar = windowAnchorBar;
    if (windowAnchorBar != null && !barsSinceAnchor.isEmpty()) {
      if (trend == Trend.UP) {
        Bar extreme = windowAnchorBar;
        for (Bar b : barsSinceAnchor) if (b.highTicks() > extreme.highTicks()) extreme = b;
        freshDtOrDb = dtZone(extreme);
        freshAnchorBar = extreme;
      } else {
        Bar extreme = windowAnchorBar;
        for (Bar b : barsSinceAnchor) if (b.lowTicks() < extreme.lowTicks()) extreme = b;
        freshDtOrDb = dbZone(extreme);
        freshAnchorBar = extreme;
      }
    }

    if (firstLinkOfChain) {
      // Point 9: A+/SBR-RBS become tradeable for exactly this one flip.
      lastAPlus = lastTjl1;
      lastSbrRbs = lastTjl2;
    } else {
      // Point 9: chain already underway -- A+/SBR-RBS are never
      // reassigned again and are no longer tradeable from here on.
      lastAPlus = null;
      lastSbrRbs = null;
    }

    // Whichever kind this flip direction produces gets refreshed; the
    // other kind (if any) carries over untouched -- point 9's "refresh
    // one side at a time" pattern.
    if (trend == Trend.UP) {
      if (freshDtOrDb != null) lastDt = freshDtOrDb;
    } else {
      if (freshDtOrDb != null) lastDb = freshDtOrDb;
    }

    // Point 5/6: the fresh DT/DB becomes flip-watch's reference AND the
    // next window's anchor, chaining forward -- replaces lastTjl2
    // outright (not "SBR/RBS keeps playing lastTjl2's role", the old
    // guess).
    if (freshDtOrDb != null) {
      lastTjl2 = freshDtOrDb;
      windowAnchorBar = freshAnchorBar;
      barsSinceAnchor.clear();
      barsSinceAnchor.add(freshAnchorBar);
    }

    lastTjl1 = null; // no "current" TJL1 until the new trend's own pullback cycle forms one
    chainStarted = true;
    trend = newTrend;
    pullbackState = PullbackState.NONE;
    pullbackBars.clear();
    recomputeTradeableLevels();

    if (listener != null) listener.onFlip(newTrend, lastAPlus, lastSbrRbs, freshDtOrDb, tradeableLevels);
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
      // Point 1: sliding consecutive-pair check -- current candle vs. the
      // IMMEDIATELY PRECEDING one in the run, not anchored to the run's
      // first candle (the old, now-corrected guess).
      Bar previous = pullbackBars.get(pullbackBars.size() - 1);
      pullbackBars.add(bar);
      boolean valid = trend == Trend.UP
          ? bar.closeTicks() < previous.lowTicks()
          : bar.closeTicks() > previous.highTicks();
      if (valid) {
        pullbackState = PullbackState.VALID;
        // Point 2: reported "first" bar is always the run's TRUE first
        // candle, regardless of which pair actually validated it.
        if (listener != null) listener.onPullbackValid(trend, pullbackBars.get(0));
      }
      return;
    }

    // VALID: keep growing while counter-trend candles continue (point 2,
    // confirmed -- unchanged from the original implementation).
    if (counterTrend) {
      pullbackBars.add(bar);
      return;
    }

    // D-65 bugfix (unaffected by this rework, still correct): a
    // non-confirming trend-colored candle is ignored, not added, doesn't
    // reset anything -- state stays VALID, waiting for a later candle to
    // eventually confirm.
    Bar highBar = argmaxHigh(pullbackBars);
    Bar lowBar = argminLow(pullbackBars);
    int pullbackExtreme = trend == Trend.UP ? highBar.highTicks() : lowBar.lowTicks();
    boolean continuation = trend == Trend.UP
        ? bar.closeTicks() > pullbackExtreme
        : bar.closeTicks() < pullbackExtreme;

    if (continuation) {
      formTjlPair(bar, highBar, lowBar);
      pullbackState = PullbackState.NONE;
      pullbackBars.clear();
    }
  }

  private void formTjlPair(Bar continuationBar, Bar highBar, Bar lowBar) {
    ZoneRange newTjl1;
    ZoneRange newTjl2;
    Bar tjl2Bar; // the candle that produced TJL2 -- becomes SBR/RBS and the window anchor if a flip happens next (point 6)
    if (trend == Trend.UP) {
      newTjl1 = zoneFromHigh(highBar); // near-side / resumption pivot
      newTjl2 = zoneFromLow(lowBar);   // far-side / invalidation
      tjl2Bar = lowBar;
    } else {
      newTjl2 = zoneFromHigh(highBar); // far-side / invalidation, now the top
      newTjl1 = zoneFromLow(lowBar);   // near-side
      tjl2Bar = highBar;
    }
    lastTjl1 = newTjl1;
    lastTjl2 = newTjl2;

    // Point 10: a fresh real TJL1/TJL2 pair immediately retires any
    // DT/DB chain tradeable state, whether this is the very first pair
    // ever or one ending a chain of pair-less flips.
    lastAPlus = null;
    lastSbrRbs = null;
    lastDt = null;
    lastDb = null;
    chainStarted = false;

    windowAnchorBar = tjl2Bar;
    barsSinceAnchor.clear();
    barsSinceAnchor.add(tjl2Bar);
    barsSinceAnchor.add(continuationBar);

    hasRealPair = true;
    recomputeTradeableLevels();
    if (listener != null) listener.onTjlFormed(trend, newTjl1, newTjl2, tradeableLevels);
  }

  private void recomputeTradeableLevels() {
    if (!hasRealPair) {
      tradeableLevels = Set.of(); // still just the CHOCH bootstrap -- never tradeable (§1a)
      return;
    }
    if (!chainStarted) {
      tradeableLevels = Set.of(TradeableLevel.TJL2); // normal operation -- TJL1 is deliberately excluded, see its own javadoc
      return;
    }
    EnumSet<TradeableLevel> s = EnumSet.noneOf(TradeableLevel.class);
    if (lastAPlus != null) s.add(TradeableLevel.A_PLUS);
    if (lastSbrRbs != null) s.add(TradeableLevel.SBR_RBS);
    if (lastDt != null) s.add(TradeableLevel.DT);
    if (lastDb != null) s.add(TradeableLevel.DB);
    tradeableLevels = Set.copyOf(s);
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

  /** Point 4: 1% of the candle's own BODY height (|close-open|) -- corrected from the high-low range. */
  private static int bodyOffsetTicks(Bar b) {
    return Math.round(Math.abs(b.closeTicks() - b.openTicks()) * 0.01f);
  }

  /** TJL1 (uptrend) / TJL2 (downtrend): from the high, extending down into the body 1% of body height. */
  private static ZoneRange zoneFromHigh(Bar highBar) {
    int bodyTop = Math.max(highBar.openTicks(), highBar.closeTicks());
    return ZoneRange.of(highBar.highTicks(), bodyTop - bodyOffsetTicks(highBar));
  }

  /** TJL2 (uptrend) / TJL1 (downtrend): from the low, extending up into the body 1% of body height. */
  private static ZoneRange zoneFromLow(Bar lowBar) {
    int bodyBottom = Math.min(lowBar.openTicks(), lowBar.closeTicks());
    return ZoneRange.of(lowBar.lowTicks(), bodyBottom + bodyOffsetTicks(lowBar));
  }

  /** DT (Up->Down flip): from the extreme high, extending up/outward beyond the body top. */
  private static ZoneRange dtZone(Bar highBar) {
    int bodyTop = Math.max(highBar.openTicks(), highBar.closeTicks());
    return ZoneRange.of(highBar.highTicks(), bodyTop + bodyOffsetTicks(highBar));
  }

  /** DB (Down->Up flip): from the extreme low, extending down/outward beyond the body bottom. */
  private static ZoneRange dbZone(Bar lowBar) {
    int bodyBottom = Math.min(lowBar.openTicks(), lowBar.closeTicks());
    return ZoneRange.of(lowBar.lowTicks(), bodyBottom - bodyOffsetTicks(lowBar));
  }

  @Override public Trend trend() { return trend; }
  @Override public PullbackState pullbackState() { return pullbackState; }
  @Override public ZoneRange lastTjl1() { return lastTjl1; }
  @Override public ZoneRange lastTjl2() { return lastTjl2; }
  @Override public ZoneRange lastAPlus() { return lastAPlus; }
  @Override public ZoneRange lastSbrRbs() { return lastSbrRbs; }
  @Override public ZoneRange lastDt() { return lastDt; }
  @Override public ZoneRange lastDb() { return lastDb; }
  @Override public Set<TradeableLevel> tradeableLevels() { return tradeableLevels; }
}
