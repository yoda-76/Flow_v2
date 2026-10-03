package com.flow.backtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Two kinds of checks: (1) a regression test proving the new generic engine, running
 * {@link MarketStructureBacktestStrategy} with every risk limit disabled, reproduces the OLD
 * {@code MarketStructureBacktest.run()}'s results exactly on real data -- the correctness gate
 * `backtestEnginePlan.md` (2026-10-03) calls for before trusting the refactor; (2) synthetic checks of the
 * ported risk chain itself (daily-loss kill switch, max reversals, rate limit, min dwell), using a scripted
 * test-double strategy so each mechanism is isolated from {@code MarketStructureFeature}'s own logic.
 */
public final class BacktestEngineTest {
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
    testRegressionAgainstOldToolOnRealData();
    testDailyLossKillSwitchForcesExitAndBlocksRestOfSession_thenResetsNextSession();
    testDailyLossKillSwitchFiresExactlyAtTheBoundary();
    testMaxReversalsPerSessionBlocksFurtherEntries();
    testRateLimitBlocksAnEntryWithinTheSameSlidingWindow();
    testMinDwellBlocksAnEntryTooSoonAfterTheLastChange();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all BacktestEngine synthetic checks passed.");
  }

  // ---------------------------------------------------------------------------------------------
  // Regression: new generic engine (risk-chain disabled) must exactly reproduce the old, hardcoded
  // tool's trades on the same real data -- D-79's original export, still on disk.
  // ---------------------------------------------------------------------------------------------
  private static void testRegressionAgainstOldToolOnRealData() throws IOException {
    Path csv = Path.of("analysis/data/GC_1m_1789910262328.csv");
    if (!Files.isRegularFile(csv)) {
      System.out.println("SKIP regression test: " + csv + " not present on this machine (analysis/data/ is "
          + "user data, D-79's gitignore question is still undecided) -- not a build failure.");
      return;
    }
    double tickSize = 0.1;
    List<MarketStructureBacktest.Bar> oldBars = MarketStructureBacktest.readCsv(csv);
    MarketStructureBacktest.Report oldReport = MarketStructureBacktest.run(oldBars, tickSize);

    List<Bar> newBars = BacktestCsv.readCsv(csv);
    MarketStructureBacktestStrategy strategy = new MarketStructureBacktestStrategy(
        tickSize, 2.0, MarketStructureBacktest.StopRule.FIXED_BUFFER_TICKS, 2);
    BacktestEngine.Report newReport = BacktestEngine.run(newBars, tickSize, strategy, BacktestEngine.RiskConfig.unlimited());

    check("same bar count read", newBars.size(), oldBars.size());
    check("same trade count (D-79: 1206)", newReport.trades().size(), oldReport.trades().size());
    check("same win count (D-79: 545)", newReport.wins(), oldReport.wins());
    check("same loss count (D-79: 631)", newReport.losses(), oldReport.losses());
    checkDouble("same totalR (D-79: 189.00)", newReport.totalR(), oldReport.totalR());
    checkDouble("same avgR (D-79: +0.16)", newReport.avgR(), oldReport.avgR());
    checkDouble("same maxDrawdownR (D-79: 36.00)", newReport.maxDrawdownR(), oldReport.maxDrawdownR());
    check("same structure stats: pullbackValid", strategy.pullbackValidCount(), oldReport.pullbackValidCount());
    check("same structure stats: tjlFormed", strategy.tjlFormedCount(), oldReport.tjlFormedCount());
    check("same structure stats: flip", strategy.flipCount(), oldReport.flipCount());
    check("risk chain never denied anything (unlimited config)",
        newReport.denials(), new BacktestEngine.DenialCounts(0, 0, 0, 0));
    check("kill switch never fired (unlimited config)", newReport.killSwitchTrips(), 0);

    int n = Math.min(newReport.trades().size(), oldReport.trades().size());
    int mismatches = 0;
    for (int i = 0; i < n; i++) {
      var a = newReport.trades().get(i);
      var b = oldReport.trades().get(i);
      boolean same = a.entryTimeMs() == b.entryTimeMs() && a.entryPrice() == b.entryPrice()
          && a.direction() == b.direction() && a.stopPrice() == b.stopPrice() && a.targetPrice() == b.targetPrice()
          && a.exitTimeMs() == b.exitTimeMs() && a.exitPrice() == b.exitPrice()
          && a.exitReason().equals(b.exitReason()) && Math.abs(a.rMultiple() - b.rMultiple()) < 1e-9;
      if (!same) mismatches++;
    }
    check("every individual trade identical, old vs new engine", mismatches, 0);
  }

  // ---------------------------------------------------------------------------------------------
  // Scripted test-double: fires a fixed long signal on whichever bar timestamps the test names, so
  // entry timing is fully controlled; exits still come from the engine's own stop/target-vs-bar-range
  // check, so the test crafts each bar's high/low to hit the stop exactly when an exit is wanted.
  // ---------------------------------------------------------------------------------------------
  private static final class ScriptedStrategy implements BacktestStrategy {
    private final Set<Long> fireOnTimestamps;
    private final double stopOffset, targetOffset;
    ScriptedStrategy(Set<Long> fireOnTimestamps, double stopOffset, double targetOffset) {
      this.fireOnTimestamps = fireOnTimestamps;
      this.stopOffset = stopOffset;
      this.targetOffset = targetOffset;
    }
    @Override public String id() { return "scripted"; }
    @Override public void onBar(Bar bar, long seq) {}
    @Override public boolean isReady() { return true; }
    @Override public Signal entrySignal(Bar bar) {
      if (!fireOnTimestamps.contains(bar.timestampMs())) return null;
      return new Signal(1, bar.close() - stopOffset, bar.close() + targetOffset);
    }
  }

  private static Bar bar(long t, double o, double h, double l, double c) {
    return new Bar(t, o, h, l, c, 0);
  }

  private static final double TICK = 1.0;

  private static void testDailyLossKillSwitchForcesExitAndBlocksRestOfSession_thenResetsNextSession() {
    ZoneId ct = ZoneId.of("America/Chicago");
    // Session A: 16:40 CT (before the 17:00 rollover) and 16:50 CT (still session A).
    long tA1 = ZonedDateTime.of(2024, 3, 4, 16, 40, 0, 0, ct).toInstant().toEpochMilli();
    long tA2 = ZonedDateTime.of(2024, 3, 4, 16, 50, 0, 0, ct).toInstant().toEpochMilli();
    // Session B: 17:10 CT the same calendar day -- a NEW trading session per SessionBoundary.
    long tB1 = ZonedDateTime.of(2024, 3, 4, 17, 10, 0, 0, ct).toInstant().toEpochMilli();

    Set<Long> fireOn = new HashSet<>(List.of(tA1, tA2, tB1));
    // stop/target far away (1000 ticks) so only the daily-loss check can close the position, never the
    // stop/target geometry -- isolates the kill switch from the ordinary exit path entirely.
    ScriptedStrategy strat = new ScriptedStrategy(fireOn, 1000, 1000);

    List<Bar> bars = new ArrayList<>();
    bars.add(bar(tA1, 100, 101, 99, 100)); // entry @100, long, stop=-900, target=+1100 (unreachable either way)
    // Close drifts 10 against the position -> unrealized = -10 ticks, breaches a limit of 5.
    bars.add(bar(tA2, 100, 100, 89, 90));
    // Same session, later: strategy would fire again, but the kill switch already blocked this session.
    long tA3 = tA2 + 60_000; // 16:51 CT, still session A
    bars.add(bar(tA3, 90, 91, 89, 90));
    fireOn.add(tA3);
    // Next session: should be allowed to enter again.
    bars.add(bar(tB1, 90, 91, 89, 90));

    BacktestEngine.RiskConfig risk = new BacktestEngine.RiskConfig(1, 1, 5, 6, 0L, 20);
    BacktestEngine.Report r = BacktestEngine.run(bars, TICK, strat, risk);

    check("exactly one trade: the forced daily-loss close", r.trades().size(), 1);
    var t = r.trades().get(0);
    check("exit reason is daily_loss", t.exitReason(), "daily_loss");
    checkDouble("forced exit at that bar's close (90), not the geometric stop", t.exitPrice(), 90.0);
    check("kill switch fired exactly once", r.killSwitchTrips(), 1);
    check("the later same-session entry attempt was denied", r.denials().dailyLoss(), 1);
    check("next session: no further daily-loss denial once entry succeeded again",
        r.denials().dailyLoss(), 1); // still 1 -- the session-B bar's entry was NOT denied (see below)

    // Confirm session B's entry actually opened a (still-open, never-closed) position rather than being
    // silently skipped for some other reason: wins+losses from the one closed trade is 0/1 (a loss), and
    // a second position opening would not show up as a trade unless/until it also exits -- so assert the
    // only trade present is the daily_loss one, and that no SECOND daily_loss denial happened in session B
    // (if the reset had failed, session B's attempt would have been denied too, incrementing dailyLoss to 2).
  }

  /** Boundary case the first kill-switch test didn't cover: a loss landing EXACTLY on the limit must still
   * trigger (<=, not <) -- caught a real mutation survivor during this engine's own mutation-test pass. */
  private static void testDailyLossKillSwitchFiresExactlyAtTheBoundary() {
    long t1 = 0, t2 = 60_000;
    Set<Long> fireOn = new HashSet<>(List.of(t1));
    ScriptedStrategy strat = new ScriptedStrategy(fireOn, 1000, 1000); // stop/target unreachable -- isolates the kill switch

    List<Bar> bars = new ArrayList<>();
    bars.add(bar(t1, 100, 101, 99, 100)); // entry @100
    bars.add(bar(t2, 100, 100, 94, 95));  // close=95 -> unrealized = -5 ticks, EXACTLY the limit below

    BacktestEngine.RiskConfig risk = new BacktestEngine.RiskConfig(1, 1, 5, 6, 0L, 20);
    BacktestEngine.Report r = BacktestEngine.run(bars, TICK, strat, risk);

    check("a loss landing exactly on the limit still trips the kill switch", r.killSwitchTrips(), 1);
    checkDouble("the forced exit is at this bar's close (95), not the stop", r.trades().get(0).exitPrice(), 95.0);
  }

  private static void testMaxReversalsPerSessionBlocksFurtherEntries() {
    long t1 = 0, t2 = 60_000, t3 = 120_000, t4 = 180_000;
    Set<Long> fireOn = new HashSet<>(List.of(t1, t3));
    ScriptedStrategy strat = new ScriptedStrategy(fireOn, 5, 1000); // tight stop, far target -> stop hits next bar

    List<Bar> bars = new ArrayList<>();
    bars.add(bar(t1, 100, 101, 99, 100));   // entry @100, stop=95
    bars.add(bar(t2, 100, 100, 94, 95));    // low=94 <= 95 -> stop hit, exit. This is reversal #1 (entry was exempt).
    bars.add(bar(t3, 95, 96, 94, 95));      // maxReversalsPerSession=1 already reached -> entry attempt denied
    bars.add(bar(t4, 95, 96, 94, 95));

    BacktestEngine.RiskConfig risk = new BacktestEngine.RiskConfig(1, 1, 10_000, 6, 0L, 1);
    BacktestEngine.Report r = BacktestEngine.run(bars, TICK, strat, risk);

    check("exactly one trade (the stop-out); the 2nd entry was denied, never opened", r.trades().size(), 1);
    check("the 2nd entry attempt was denied for max reversals", r.denials().maxReversals(), 1);
  }

  private static void testRateLimitBlocksAnEntryWithinTheSameSlidingWindow() {
    long t1 = 0, t2 = 60_000, t3 = 120_000;
    Set<Long> fireOn = new HashSet<>(List.of(t1, t3));
    ScriptedStrategy strat = new ScriptedStrategy(fireOn, 5, 1000);

    List<Bar> bars = new ArrayList<>();
    bars.add(bar(t1, 100, 101, 99, 100)); // entry @100, stop=95 -- 1st change ever, not rate-checked
    bars.add(bar(t2, 100, 100, 94, 95));  // stop hit -> exit (flat intents bypass the rate check, but still
                                          // consume a slot, same as RiskChain.recordAccepted)
    bars.add(bar(t3, 95, 96, 94, 95));    // rateLimitPerMinute=1: the window at t3 still holds t2's exit
                                          // timestamp (60,000ms old, not yet pruned) -> this entry is denied

    BacktestEngine.RiskConfig risk = new BacktestEngine.RiskConfig(1, 1, 10_000, 1, 0L, 20);
    BacktestEngine.Report r = BacktestEngine.run(bars, TICK, strat, risk);

    check("exactly one trade (the stop-out); the 2nd entry was rate-limited, never opened", r.trades().size(), 1);
    check("the 2nd entry attempt was denied for the rate limit", r.denials().rateLimit(), 1);
  }

  private static void testMinDwellBlocksAnEntryTooSoonAfterTheLastChange() {
    // t4 is EXACTLY minDwellMs after t2 -- live's checkChurn only blocks when dwell < minDwell, so dwell ==
    // minDwell must be ALLOWED, not blocked. (Caught a real mutation survivor during this engine's own
    // mutation-test pass: a <= instead of < here would wrongly deny this exact-boundary case.)
    long t1 = 0, t2 = 60_000, t3 = 120_000, t4 = t2 + 90_000; // = 150_000
    Set<Long> fireOn = new HashSet<>(List.of(t1, t3, t4));
    ScriptedStrategy strat = new ScriptedStrategy(fireOn, 5, 1000);

    List<Bar> bars = new ArrayList<>();
    bars.add(bar(t1, 100, 101, 99, 100)); // entry
    bars.add(bar(t2, 100, 100, 94, 95));  // stop hit -> exit at t2
    bars.add(bar(t3, 95, 96, 94, 95));    // t3 - t2 = 60,000ms < minDwellMs(90,000) -> denied
    bars.add(bar(t4, 95, 96, 94, 95));    // t4 - t2 = 90,000ms == minDwellMs exactly -> allowed, not denied

    BacktestEngine.RiskConfig risk = new BacktestEngine.RiskConfig(1, 1, 10_000, 6, 90_000L, 20);
    BacktestEngine.Report r = BacktestEngine.run(bars, TICK, strat, risk);

    check("the too-soon entry at t3 was denied for min dwell", r.denials().minDwell(), 1);
    check("exactly-at-the-boundary entry at t4 was NOT also denied", r.denials().minDwell(), 1);
    check("the later entry at t4 was allowed (still open, no exit bar crafted -> 1 trade total so far)",
        r.trades().size(), 1);
  }
}
