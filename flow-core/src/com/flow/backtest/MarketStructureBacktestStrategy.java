package com.flow.backtest;

import com.flow.core.BarEvent;
import com.flow.core.BarPhase;
import com.flow.flow.MarketStructureFeature;
import com.flow.flow.MarketStructureView;
import com.flow.flow.ZoneRange;

import java.util.Set;

/**
 * The first concrete {@link BacktestStrategy} -- the exact same entry rule {@code MarketStructureBacktest.run()}
 * has always hardcoded (D-79, 2026-09-20: flat + a bar's range touches any currently-tradeable zone -> enter in
 * the direction of {@code trend()}), now behind the pluggable interface instead of inline in the engine. This is
 * a refactor, not new logic -- {@link BacktestEngineTest}'s regression check runs this strategy through
 * {@link BacktestEngine} with every risk-chain limit disabled and asserts the exact D-79 run-1 numbers
 * (1,206 trades, totalR=189.00, avgR=+0.16, maxDrawdownR=36.00) still come out, before anything built on top of
 * this refactor is trusted.
 *
 * Owns its own {@code MarketStructureFeature} instance and the same three stop-sizing conventions
 * ({@link MarketStructureBacktest.StopRule}) D-80 added -- reused directly rather than re-declared, since they're
 * a plain enum with no engine dependency.
 */
public final class MarketStructureBacktestStrategy implements BacktestStrategy {
  private final double tickSize;
  private final double rewardRiskMultiple;
  private final MarketStructureBacktest.StopRule stopRule;
  private final double stopParam;

  private final Counter counter = new Counter();
  private final MarketStructureFeature ms;

  private static final class Counter implements MarketStructureFeature.Listener {
    int pullbackValid, tjlFormed, flip;
    @Override public void onPullbackValid(MarketStructureView.Trend t, MarketStructureFeature.Bar b) { pullbackValid++; }
    @Override public void onTjlFormed(MarketStructureView.Trend t, ZoneRange a, ZoneRange b, Set<MarketStructureView.TradeableLevel> s) { tjlFormed++; }
    @Override public void onFlip(MarketStructureView.Trend t, ZoneRange a, ZoneRange b, ZoneRange c, Set<MarketStructureView.TradeableLevel> s) { flip++; }
  }

  public MarketStructureBacktestStrategy(double tickSize, double rewardRiskMultiple,
                                          MarketStructureBacktest.StopRule stopRule, double stopParam) {
    this.tickSize = tickSize;
    this.rewardRiskMultiple = rewardRiskMultiple;
    this.stopRule = stopRule;
    this.stopParam = stopParam;
    this.ms = new MarketStructureFeature("backtest_ms", counter);
  }

  @Override public String id() { return "market_structure_backtest"; }

  @Override
  public void onBar(Bar bar, long seq) {
    ms.onEvent(new BarEvent(seq, bar.timestampMs(), bar.timestampMs(), BarPhase.CLOSE,
        toTicks(bar.open()), toTicks(bar.high()), toTicks(bar.low()), toTicks(bar.close()), bar.volume()));
  }

  @Override public boolean isReady() { return ms.isReady(); }

  @Override
  public Signal entrySignal(Bar bar) {
    int lowT = toTicks(bar.low()), highT = toTicks(bar.high()), closeT = toTicks(bar.close());
    ZoneRange touched = firstTouchedTradeableZone(lowT, highT);
    if (touched == null) return null;

    boolean bullish = ms.trend() == MarketStructureView.Trend.UP;
    int dir = bullish ? 1 : -1;
    int stopT;
    if (stopRule == MarketStructureBacktest.StopRule.FIXED_BUFFER_TICKS) {
      int stopBufferTicks = (int) Math.round(stopParam);
      stopT = bullish ? touched.lowTicks() - stopBufferTicks : touched.highTicks() + stopBufferTicks;
    } else if (stopRule == MarketStructureBacktest.StopRule.ZONE_SIZE_MULTIPLE) {
      int zoneWidthTicks = touched.highTicks() - touched.lowTicks();
      int riskFromEntry = (int) Math.round(stopParam * zoneWidthTicks);
      stopT = bullish ? closeT - riskFromEntry : closeT + riskFromEntry;
    } else { // FIXED_PRICE_DISTANCE
      int riskFromEntry = (int) Math.round(stopParam / tickSize);
      stopT = bullish ? closeT - riskFromEntry : closeT + riskFromEntry;
    }
    int riskT = Math.abs(closeT - stopT);
    int targetT = bullish
        ? closeT + (int) Math.round(riskT * rewardRiskMultiple)
        : closeT - (int) Math.round(riskT * rewardRiskMultiple);
    return new Signal(dir, fromTicks(stopT), fromTicks(targetT));
  }

  /** Exposed for parity with the old `Report`'s structure counters -- read after a run completes. */
  public int pullbackValidCount() { return counter.pullbackValid; }
  public int tjlFormedCount() { return counter.tjlFormed; }
  public int flipCount() { return counter.flip; }

  private ZoneRange firstTouchedTradeableZone(int lowT, int highT) {
    Set<MarketStructureView.TradeableLevel> tradeable = ms.tradeableLevels();
    if (tradeable.contains(MarketStructureView.TradeableLevel.TJL2) && intersects(ms.lastTjl2(), lowT, highT)) return ms.lastTjl2();
    if (tradeable.contains(MarketStructureView.TradeableLevel.A_PLUS) && intersects(ms.lastAPlus(), lowT, highT)) return ms.lastAPlus();
    if (tradeable.contains(MarketStructureView.TradeableLevel.SBR_RBS) && intersects(ms.lastSbrRbs(), lowT, highT)) return ms.lastSbrRbs();
    if (tradeable.contains(MarketStructureView.TradeableLevel.DT) && intersects(ms.lastDt(), lowT, highT)) return ms.lastDt();
    if (tradeable.contains(MarketStructureView.TradeableLevel.DB) && intersects(ms.lastDb(), lowT, highT)) return ms.lastDb();
    return null;
  }

  private static boolean intersects(ZoneRange zone, int lowT, int highT) {
    return zone != null && zone.lowTicks() <= highT && zone.highTicks() >= lowT;
  }

  private int toTicks(double price) { return (int) Math.round(price / tickSize); }
  private double fromTicks(int ticks) { return ticks * tickSize; }
}
