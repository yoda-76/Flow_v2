package com.flow.backtest;

import com.flow.core.BarEvent;
import com.flow.core.BarPhase;
import com.flow.flow.MarketStructureFeature;
import com.flow.flow.MarketStructureView;
import com.flow.flow.ZoneRange;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plain-OHLC backtest of the reviewed market structure rules (D-74) —
 * `docs/dynamic/todo.md`'s explicit carve-out from D-06's "no
 * backtesting" stance: pure market-structure logic is bar-close-only by
 * construction (`marketStructureRules.md` §9) and needs nothing
 * tick-level, unlike the order-flow-confirmed entries
 * `MarketStructureLvnReversalStrategy` layers on top (deliberately NOT
 * reproduced here — "no order flow execution").
 *
 * Zero SDK dependency, same as `MarketStructureFeature` itself — reads
 * a plain CSV (`timestampMs,open,high,low,close,volume`, decimal
 * prices), feeds each bar through the real, reworked
 * `MarketStructureFeature`, and simulates trades against its own output
 * only.
 *
 * <h2>Entry rule (proposed 2026-09-20, not yet separately reviewed the
 * way the market-structure rules themselves were)</h2>
 * The reviewed rules already state trade direction explicitly for every
 * `tradeableLevels()` phase — "Look for SHORTS from A+/SBR/DT," "Look
 * for LONGS from DT/DB," etc., always matching the CURRENT trend. So:
 * flat and a bar's [low,high] range touches any zone currently in
 * `tradeableLevels()` → enter in the direction of `trend()` (long if
 * UP, short if DOWN) at that bar's close, stop `stopBufferTicks` beyond
 * the touched zone's far edge, target at `rewardRiskMultiple` × risk.
 * One position at a time (matches `MarketStructureLvnReversalStrategy`'s
 * own phase model) — a touch while already in a position is ignored,
 * not stacked or reversed. If a later bar's range crosses BOTH the stop
 * and target, the stop is assumed to have hit first (the standard
 * conservative backtest convention, since intrabar path order is
 * genuinely unknown from OHLC alone).
 *
 * Prices are integer ticks (D-21) internally, exactly like the live
 * system — converted directly via `ticks = round(price / tickSize)`,
 * deliberately NOT anchored to a session-start price the way the live
 * `PriceCodec` is (D-21's own rationale for an anchor — keeping numbers
 * small — doesn't apply here; `MarketStructureFeature` only ever
 * compares tick DIFFERENCES, never assumes values start near zero, so
 * skipping the anchor is simpler and loses nothing).
 */
public final class MarketStructureBacktest {
  public record Bar(long timestampMs, double open, double high, double low, double close, long volume) {}

  public record Trade(
      long entryTimeMs, double entryPrice, int direction,
      double stopPrice, double targetPrice,
      long exitTimeMs, double exitPrice, String exitReason, double rMultiple) {}

  public record Report(
      List<Trade> trades, int wins, int losses, double totalR, double avgR,
      double maxDrawdownR, int pullbackValidCount, int tjlFormedCount, int flipCount) {}

  private MarketStructureBacktest() {}

  public static List<Bar> readCsv(Path path) throws IOException {
    List<Bar> bars = new ArrayList<>();
    try (BufferedReader r = Files.newBufferedReader(path)) {
      String header = r.readLine(); // "timestampMs,open,high,low,close,volume"
      String line;
      while ((line = r.readLine()) != null) {
        line = line.strip();
        if (line.isEmpty()) continue;
        String[] f = line.split(",");
        bars.add(new Bar(
            Long.parseLong(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2]),
            Double.parseDouble(f[3]), Double.parseDouble(f[4]), (long) Double.parseDouble(f[5])));
      }
    }
    return bars;
  }

  private static int toTicks(double price, double tickSize) {
    return (int) Math.round(price / tickSize);
  }

  private static double fromTicks(int ticks, double tickSize) {
    return ticks * tickSize;
  }

  public static Report run(List<Bar> bars, double tickSize) {
    return run(bars, tickSize, 2.0, 2);
  }

  public static Report run(List<Bar> bars, double tickSize, double rewardRiskMultiple, int stopBufferTicks) {
    class Counter implements MarketStructureFeature.Listener {
      int pullbackValid, tjlFormed, flip;
      @Override public void onPullbackValid(MarketStructureView.Trend t, MarketStructureFeature.Bar b) { pullbackValid++; }
      @Override public void onTjlFormed(MarketStructureView.Trend t, ZoneRange a, ZoneRange b, Set<MarketStructureView.TradeableLevel> s) { tjlFormed++; }
      @Override public void onFlip(MarketStructureView.Trend t, ZoneRange a, ZoneRange b, ZoneRange c, Set<MarketStructureView.TradeableLevel> s) { flip++; }
    }
    Counter counter = new Counter();
    MarketStructureFeature ms = new MarketStructureFeature("backtest_ms", counter);

    List<Trade> trades = new ArrayList<>();
    Integer posDirection = null;
    double entryPrice = 0, stopPrice = 0, targetPrice = 0;
    long entryTimeMs = 0;

    long seq = 0;
    for (Bar bar : bars) {
      seq++;
      boolean closedThisBar = false;

      // 1. Manage an open position against THIS bar's range, before anything else.
      if (posDirection != null) {
        boolean stopHit = posDirection > 0 ? bar.low() <= stopPrice : bar.high() >= stopPrice;
        boolean targetHit = posDirection > 0 ? bar.high() >= targetPrice : bar.low() <= targetPrice;
        if (stopHit || targetHit) {
          boolean isStop = stopHit; // conservative: stop wins if both hit the same bar
          double exitPrice = isStop ? stopPrice : targetPrice;
          double risk = Math.abs(entryPrice - stopPrice);
          double r = risk == 0 ? 0 : ((exitPrice - entryPrice) * posDirection) / risk;
          trades.add(new Trade(entryTimeMs, entryPrice, posDirection, stopPrice, targetPrice,
              bar.timestampMs(), exitPrice, isStop ? "stop" : "target", r));
          posDirection = null;
          closedThisBar = true; // deliberate: no same-bar re-entry (see step 3 below) -- an
          // exit and a fresh entry on the identical bar is an unrealistic whipsaw artifact of
          // checking exit-then-entry against the same OHLC range, not a real trading decision.
        }
      }

      // 2. Feed the bar into the real, reworked state machine.
      int openT = toTicks(bar.open(), tickSize);
      int highT = toTicks(bar.high(), tickSize);
      int lowT = toTicks(bar.low(), tickSize);
      int closeT = toTicks(bar.close(), tickSize);
      ms.onEvent(new BarEvent(seq, bar.timestampMs(), bar.timestampMs(), BarPhase.CLOSE,
          openT, highT, lowT, closeT, bar.volume()));

      // 3. Entry check -- only if flat AND didn't just close this same bar (see
      //    closedThisBar above), only against levels CURRENTLY tradeable (post
      //    this-bar's own structure update, so a bar that itself confirms a
      //    flip already sees the fresh tradeable set, not last bar's).
      if (posDirection == null && !closedThisBar && ms.isReady()) {
        ZoneRange touched = firstTouchedTradeableZone(ms, lowT, highT);
        if (touched != null) {
          boolean bullish = ms.trend() == MarketStructureView.Trend.UP;
          int dir = bullish ? 1 : -1;
          int stopT = bullish ? touched.lowTicks() - stopBufferTicks : touched.highTicks() + stopBufferTicks;
          int riskT = Math.abs(closeT - stopT);
          int targetT = bullish
              ? closeT + (int) Math.round(riskT * rewardRiskMultiple)
              : closeT - (int) Math.round(riskT * rewardRiskMultiple);
          posDirection = dir;
          entryPrice = bar.close();
          stopPrice = fromTicks(stopT, tickSize);
          targetPrice = fromTicks(targetT, tickSize);
          entryTimeMs = bar.timestampMs();
        }
      }
    }

    int wins = 0, losses = 0;
    double totalR = 0, running = 0, peak = 0, maxDrawdown = 0;
    for (Trade t : trades) {
      if (t.rMultiple() > 0) wins++; else if (t.rMultiple() < 0) losses++;
      totalR += t.rMultiple();
      running += t.rMultiple();
      peak = Math.max(peak, running);
      maxDrawdown = Math.max(maxDrawdown, peak - running);
    }
    double avgR = trades.isEmpty() ? 0 : totalR / trades.size();

    return new Report(trades, wins, losses, totalR, avgR, maxDrawdown,
        counter.pullbackValid, counter.tjlFormed, counter.flip);
  }

  /** First tradeable-level zone (in a fixed check order) whose range intersects [lowT,highT]. Null if none touched. */
  private static ZoneRange firstTouchedTradeableZone(MarketStructureFeature ms, int lowT, int highT) {
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

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("usage: MarketStructureBacktest <csv path> <tickSize> [rewardRisk] [stopBufferTicks]");
      System.exit(2);
    }
    Path csv = Path.of(args[0]);
    double tickSize = Double.parseDouble(args[1]);
    double rr = args.length > 2 ? Double.parseDouble(args[2]) : 2.0;
    int stopBuf = args.length > 3 ? Integer.parseInt(args[3]) : 2;

    List<Bar> bars = readCsv(csv);
    Report report = run(bars, tickSize, rr, stopBuf);

    System.out.println("=== " + csv.getFileName() + " (" + bars.size() + " bars, tickSize=" + tickSize + ") ===");
    System.out.println("structure: " + report.pullbackValidCount() + " valid pullbacks, "
        + report.tjlFormedCount() + " TJL pairs formed, " + report.flipCount() + " flips");
    System.out.println("trades: " + report.trades().size() + " (" + report.wins() + "W / " + report.losses()
        + "L / " + (report.trades().size() - report.wins() - report.losses()) + " flat)");
    System.out.printf("totalR=%.2f avgR=%.2f maxDrawdownR=%.2f%n",
        report.totalR(), report.avgR(), report.maxDrawdownR());
    System.out.println();
    for (Trade t : report.trades()) {
      System.out.printf("  %s %s entry=%.2f stop=%.2f target=%.2f exit=%.2f (%s) R=%.2f%n",
          java.time.Instant.ofEpochMilli(t.entryTimeMs()), t.direction() > 0 ? "LONG " : "SHORT",
          t.entryPrice(), t.stopPrice(), t.targetPrice(), t.exitPrice(), t.exitReason(), t.rMultiple());
    }
  }
}
