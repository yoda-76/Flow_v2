package com.flow.backtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Direct synthetic test for the backtest engine's own mechanics (entry/
 * stop/target simulation, R computation, CSV round-trip) -- NOT a
 * re-test of MarketStructureFeature's own rules, which
 * MarketStructureFeatureTest already covers exhaustively. Uses tickSize
 * = 1.0 throughout so ticks and decimal prices are numerically
 * identical, keeping the arithmetic easy to hand-verify.
 */
public final class MarketStructureBacktestTest {
  private static int failures = 0;

  private static void check(String label, Object got, Object want) {
    boolean ok = got == null ? want == null : got.equals(want);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static void checkDouble(String label, double got, double want) {
    boolean ok = Math.abs(got - want) < 1e-9;
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) throws IOException {
    testTargetHitProducesExpectedRMultiple();
    testStopHitProducesMinusOneR_andNoSameBarReentry();
    testCsvRoundTrip();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all MarketStructureBacktest synthetic checks passed.");
  }

  // Shared setup: bootstrap + a pullback + continuation, forming TJL1=(1000,1010),
  // TJL2=(850,861), tradeable={TJL2}, trend=UP -- same numbers already hand-verified
  // against the real MarketStructureFeature in MarketStructureFeatureTest.
  private static List<MarketStructureBacktest.Bar> setupBars() {
    List<MarketStructureBacktest.Bar> bars = new ArrayList<>();
    long t = 0;
    bars.add(bar(t += 60_000, 50, 58, 40, 55));      // bootstrap, CHOCH=50 (open)
    bars.add(bar(t += 60_000, 1005, 1010, 900, 980)); // pullback first candle
    bars.add(bar(t += 60_000, 980, 985, 850, 860));   // confirms VALID (860 < 900)
    // continuation (1040 > 1010) -> TJL pair forms. Low deliberately kept at 900,
    // clear of the about-to-form TJL2 zone (850,861) -- a continuation bar whose own
    // range dips back into the zone it just helped create would trigger entry
    // immediately on this same bar, which is a real, valid scenario on its own but
    // not the "touch happens later, on a separate bar" case this test isolates.
    bars.add(bar(t += 60_000, 860, 1050, 900, 1040));
    return bars;
  }

  private static MarketStructureBacktest.Bar bar(long t, double o, double h, double l, double c) {
    return new MarketStructureBacktest.Bar(t, o, h, l, c, 0);
  }

  private static void testTargetHitProducesExpectedRMultiple() {
    List<MarketStructureBacktest.Bar> bars = setupBars();
    long t = bars.get(bars.size() - 1).timestampMs();
    // Touches TJL2=(850,861) -> LONG entry at close=870. stop = 850-2 = 848,
    // risk = |870-848| = 22, target = 870 + round(22*2.0) = 914.
    bars.add(bar(t += 60_000, 900, 905, 845, 870));
    // Target bar: high=920 >= 914 -> target hit, exit at 914 exactly.
    bars.add(bar(t += 60_000, 870, 920, 865, 900));

    MarketStructureBacktest.Report r = MarketStructureBacktest.run(bars, 1.0);
    check("Exactly one trade", r.trades().size(), 1);
    MarketStructureBacktest.Trade tr = r.trades().get(0);
    check("Direction is LONG", tr.direction(), 1);
    checkDouble("Entry price = bar close (870)", tr.entryPrice(), 870.0);
    checkDouble("Stop = zone low(850) - buffer(2)", tr.stopPrice(), 848.0);
    checkDouble("Target = entry + round(risk*2.0) = 870+44", tr.targetPrice(), 914.0);
    checkDouble("Exit price = the target price exactly, not the bar's close", tr.exitPrice(), 914.0);
    check("Exit reason is target", tr.exitReason(), "target");
    checkDouble("R multiple is exactly +2.0 by construction (reward:risk = 2:1)", tr.rMultiple(), 2.0);
    checkDouble("Report totalR matches the single trade", r.totalR(), 2.0);
    check("1 win, 0 losses", r.wins() + "/" + r.losses(), "1/0");
  }

  private static void testStopHitProducesMinusOneR_andNoSameBarReentry() {
    List<MarketStructureBacktest.Bar> bars = setupBars();
    long t = bars.get(bars.size() - 1).timestampMs();
    // Same entry as above: LONG @870, stop=848, target=914.
    bars.add(bar(t += 60_000, 900, 905, 845, 870));
    // Stop bar: low=840 <= 848 -> stop hit. Its OWN range [840,880] still overlaps
    // TJL2=(850,861) -- without the no-same-bar-reentry guard this would spuriously
    // re-enter on the exact same bar it just got stopped out on.
    bars.add(bar(t += 60_000, 870, 880, 840, 855));
    // A later bar, clearly clear of the zone, to prove NO new position opened on
    // the stop bar itself (if one had, this bar's range could still be managing it).
    bars.add(bar(t += 60_000, 855, 1200, 850, 1150));

    MarketStructureBacktest.Report r = MarketStructureBacktest.run(bars, 1.0);
    check("Exactly one trade -- no spurious same-bar re-entry after the stop", r.trades().size(), 1);
    MarketStructureBacktest.Trade tr = r.trades().get(0);
    checkDouble("Exit price = the stop price exactly", tr.exitPrice(), 848.0);
    check("Exit reason is stop", tr.exitReason(), "stop");
    checkDouble("R multiple is exactly -1.0 (a full stop-out, by definition)", tr.rMultiple(), -1.0);
    check("0 wins, 1 loss", r.wins() + "/" + r.losses(), "0/1");
  }

  private static void testCsvRoundTrip() throws IOException {
    Path csv = Files.createTempFile("flow_v2_backtest_test", ".csv");
    try {
      Files.writeString(csv, "timestampMs,open,high,low,close,volume\n"
          + "1000,100.5,101.0,99.5,100.0,42\n"
          + "2000,100.0,105.25,98.0,104.5,17\n");
      List<MarketStructureBacktest.Bar> bars = MarketStructureBacktest.readCsv(csv);
      check("2 bars read", bars.size(), 2);
      MarketStructureBacktest.Bar b0 = bars.get(0);
      check("bar0 timestamp", b0.timestampMs(), 1000L);
      checkDouble("bar0 open", b0.open(), 100.5);
      checkDouble("bar0 high", b0.high(), 101.0);
      checkDouble("bar0 low", b0.low(), 99.5);
      checkDouble("bar0 close", b0.close(), 100.0);
      check("bar0 volume", b0.volume(), 42L);
      MarketStructureBacktest.Bar b1 = bars.get(1);
      checkDouble("bar1 close", b1.close(), 104.5);
    } finally {
      Files.deleteIfExists(csv);
    }
  }
}
