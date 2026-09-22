package com.flow.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Direct synthetic test for RiskChain (D-61) itself -- previously covered
 * only incidentally, via SessionResetWiringTest's narrow rollover-wiring
 * check (daily-loss and reversal-cap resets specifically). This is the
 * first test to exercise every filter (armed, readiness, size cap, rate
 * limit, churn's two sub-checks, lag's two sub-checks), the short-circuit
 * ordering evaluate() itself documents, and dailyLossBreached() (D-85's
 * kill-switch entry point) directly rather than only through evaluate().
 * Flagged for review, not fixed, in docs/dynamic/plumbingEdgeCases.md §4:
 * the short-circuit-only-journals-the-first-block behavior and the two
 * rollover cadences sharing one Tracker are real design points this test
 * locks in as CURRENT behavior, not an endorsement that either is final.
 */
public final class RiskChainTest {
  private static int failures = 0;

  private static void check(String label, boolean got, boolean want) {
    if (got != want) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static void check(String label, Object got, Object want) {
    boolean ok = got == null ? want == null : got.equals(want);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static ExternalConfig configWith(String json) throws IOException {
    Path f = Files.createTempFile("flow_v2_test_risk", ".json");
    f.toFile().deleteOnExit();
    Files.writeString(f, json);
    return ExternalConfig.load(f);
  }

  private static RiskChain.Context ctx(boolean armed, boolean ready, Integer priceTicks, long nowMs,
                                        int queueDepth, long processingMs) {
    return new RiskChain.Context(armed, ready, priceTicks, nowMs, queueDepth, processingMs);
  }

  public static void main(String[] args) throws IOException {
    testArmedFilterBlocksAndShortCircuits();
    testReadinessFilterBlocksAndShortCircuits();
    testSizeCapBlocks();
    testRateLimitBlocksThenRecoversAfterWindow();
    testChurnNoOpIntentAlwaysAllowed();
    testChurnMinDwellBlocksRapidReversal();
    testChurnMaxReversalsCap();
    testLagBlocksOnQueueDepth();
    testLagBlocksOnProcessingTime();
    testAllFiltersPassProducesFullAllowedVerdictList();
    testShortCircuitOnlyJournalsFirstBlockingFilter();
    testDailyLossBreachedMirrorsCheckDailyLoss();
    testDailyLossBreachedRollsOverIndependently();
    testRolloverInterleaving_BreachedCheckThenEvaluateSameEvent();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all RiskChain synthetic checks passed.");
  }

  private static ExternalConfig generousConfig() throws IOException {
    // Every threshold wide open except the one under test -- each test
    // below sets exactly the one knob it's exercising.
    return configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
  }

  private static void testArmedFilterBlocksAndShortCircuits() throws IOException {
    RiskChain rc = new RiskChain(generousConfig());
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    RiskChain.Result r = rc.evaluate(enter, ctx(false, true, 1000, 1000L, 0, 0L));
    check("armed=false blocks", r.allowed(), false);
    check("armed=false short-circuits after just the armed verdict", r.verdicts().size(), 1);
    check("blocking verdict is 'armed'", r.verdicts().get(0).filter(), "armed");
  }

  private static void testReadinessFilterBlocksAndShortCircuits() throws IOException {
    RiskChain rc = new RiskChain(generousConfig());
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    RiskChain.Result r = rc.evaluate(enter, ctx(true, false, 1000, 1000L, 0, 0L));
    check("not ready blocks", r.allowed(), false);
    check("not ready short-circuits after armed+session_open+readiness (3 verdicts)",
        r.verdicts().size(), 3);
    check("blocking verdict is 'readiness'", r.verdicts().get(2).filter(), "readiness");
  }

  private static void testSizeCapBlocks() throws IOException {
    ExternalConfig cfg = configWith("{\"maxContracts\":1,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    Intent oversized = new Intent("s", 1, 2, null, null, "enter");
    RiskChain.Result r = rc.evaluate(oversized, ctx(true, true, 1000, 1000L, 0, 0L));
    check("oversized intent (2 > maxContracts 1) blocks", r.allowed(), false);
    check("blocked at size_cap (5th verdict: armed,session_open,readiness,daily_loss,size_cap)",
        r.verdicts().size(), 5);
    check("blocking verdict is 'size_cap'", r.verdicts().get(4).filter(), "size_cap");
  }

  private static void testRateLimitBlocksThenRecoversAfterWindow() throws IOException {
    ExternalConfig cfg = configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":2,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    long t0 = 1_000_000L;
    Intent i1 = new Intent("s", 1, 1, null, null, "e1");
    Intent i2 = new Intent("s", 2, -1, null, null, "e2");
    Intent i3 = new Intent("s", 3, 1, null, null, "e3");

    RiskChain.Context c1 = ctx(true, true, 1000, t0, 0, 0L);
    check("change #1 allowed (0/2 used)", rc.evaluate(i1, c1).allowed(), true);
    rc.recordAccepted(i1, c1);

    RiskChain.Context c2 = ctx(true, true, 1000, t0 + 1_000L, 0, 0L);
    check("change #2 allowed (1/2 used)", rc.evaluate(i2, c2).allowed(), true);
    rc.recordAccepted(i2, c2);

    RiskChain.Context c3 = ctx(true, true, 1000, t0 + 2_000L, 0, 0L);
    check("change #3 BLOCKED -- 2/2 already used inside the 60s window",
        rc.evaluate(i3, c3).allowed(), false);

    // Past the 60s window from change #1 (t0) -- it ages out, freeing a slot.
    // Change #2 (t0+1_000) is still inside the window relative to t0+61_500.
    RiskChain.Context c4 = ctx(true, true, 1000, t0 + 61_500L, 0, 0L);
    check("change #3 retried past the window ALLOWED -- change #1 aged out (1/2 used)",
        rc.evaluate(i3, c4).allowed(), true);
  }

  private static void testChurnNoOpIntentAlwaysAllowed() throws IOException {
    // checkChurn's own first line: an intent that doesn't actually change
    // target position is allowed regardless of dwell time -- distinct
    // from the min-dwell/max-reversals checks below. maxReversalsPerSession
    // is 1, not 0: checkChurn's reversal-cap check (reversalsThisSession
    // >= maxReversals) doesn't special-case "this is the very first
    // entry" the way recordAccepted()'s counting does -- a cap of 0 would
    // block the entry itself, not just a later reversal, which isn't what
    // this test is isolating.
    ExternalConfig cfg = configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":999999,\"maxReversalsPerSession\":1,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    RiskChain.Context c0 = ctx(true, true, 1000, 1_000L, 0, 0L);
    check("entry allowed (lastPosition starts at 0, so 0->1 IS a change -- reversalsThisSession untouched by the entry itself)",
        rc.evaluate(enter, c0).allowed(), true);
    rc.recordAccepted(enter, c0);

    Intent stillLong = new Intent("s", 2, 1, null, null, "still holding");
    RiskChain.Context c1 = ctx(true, true, 1000, 1_001L, 0, 0L); // 1ms later -- would fail min-dwell if churn checked it
    check("re-asserting the SAME target position allowed instantly, ignoring the huge min-dwell",
        rc.evaluate(stillLong, c1).allowed(), true);
  }

  private static void testChurnMinDwellBlocksRapidReversal() throws IOException {
    ExternalConfig cfg = configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":5000,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    RiskChain.Context c0 = ctx(true, true, 1000, 10_000L, 0, 0L);
    check("entry allowed", rc.evaluate(enter, c0).allowed(), true);
    rc.recordAccepted(enter, c0);

    Intent reverse = new Intent("s", 2, -1, null, null, "reverse");
    RiskChain.Context c1 = ctx(true, true, 1000, 10_000L + 4_999L, 0, 0L); // 1ms short of min dwell
    check("reversal 1ms before min dwell elapses BLOCKED",
        rc.evaluate(reverse, c1).allowed(), false);

    RiskChain.Context c2 = ctx(true, true, 1000, 10_000L + 5_000L, 0, 0L); // exactly min dwell
    check("reversal at exactly min dwell ALLOWED", rc.evaluate(reverse, c2).allowed(), true);
  }

  private static void testChurnMaxReversalsCap() throws IOException {
    ExternalConfig cfg = configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    Intent flip1 = new Intent("s", 2, -1, null, null, "flip1");
    Intent flip2 = new Intent("s", 3, 1, null, null, "flip2");

    RiskChain.Context c0 = ctx(true, true, 1000, 1_000L, 0, 0L);
    rc.recordAccepted(enter, c0); // the very first entry never counts as a reversal (lastChangeAtMs starts at -1)

    RiskChain.Context c1 = ctx(true, true, 1000, 2_000L, 0, 0L);
    check("reversal #1 allowed (0/1 used)", rc.evaluate(flip1, c1).allowed(), true);
    rc.recordAccepted(flip1, c1);

    RiskChain.Context c2 = ctx(true, true, 1000, 3_000L, 0, 0L);
    check("reversal #2 BLOCKED -- cap (1) already reached", rc.evaluate(flip2, c2).allowed(), false);
  }

  private static void testLagBlocksOnQueueDepth() throws IOException {
    ExternalConfig cfg = configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":50,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    check("queueDepth just under threshold allowed",
        rc.evaluate(enter, ctx(true, true, 1000, 1_000L, 49, 0L)).allowed(), true);
    RiskChain rc2 = new RiskChain(cfg);
    check("queueDepth at threshold BLOCKED",
        rc2.evaluate(enter, ctx(true, true, 1000, 1_000L, 50, 0L)).allowed(), false);
  }

  private static void testLagBlocksOnProcessingTime() throws IOException {
    ExternalConfig cfg = configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":200}");
    RiskChain rc = new RiskChain(cfg);
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    check("processingMs just under threshold allowed",
        rc.evaluate(enter, ctx(true, true, 1000, 1_000L, 0, 199L)).allowed(), true);
    RiskChain rc2 = new RiskChain(cfg);
    check("processingMs at threshold BLOCKED",
        rc2.evaluate(enter, ctx(true, true, 1000, 1_000L, 0, 200L)).allowed(), false);
  }

  private static void testAllFiltersPassProducesFullAllowedVerdictList() throws IOException {
    RiskChain rc = new RiskChain(generousConfig());
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    RiskChain.Result r = rc.evaluate(enter, ctx(true, true, 1000, 1_000L, 0, 0L));
    check("clean allow", r.allowed(), true);
    check("all 8 filters journaled when nothing blocks "
        + "(armed,session_open,readiness,daily_loss,size_cap,rate_limit,churn,lag)",
        r.verdicts().size(), 8);
    for (var v : r.verdicts()) {
      check("verdict '" + v.filter() + "' is allowed", v.allowed(), true);
    }
  }

  /**
   * plumbingEdgeCases.md §4: evaluate()'s short-circuit means only the
   * FIRST blocking filter is ever journaled, even when a later filter in
   * the fixed order would also have blocked. Locking in the CURRENT
   * behavior as a regression baseline -- whether this is the right
   * design is exactly what's flagged for review there, not decided here.
   */
  private static void testShortCircuitOnlyJournalsFirstBlockingFilter() throws IOException {
    // Both size_cap (maxContracts=1, intent asks for 3) and rate_limit
    // (rateLimitPerMinute=0, nothing ever allowed) would fail this
    // intent -- size_cap comes first in RiskChain.evaluate()'s fixed
    // order, so rate_limit must never even be evaluated/journaled.
    ExternalConfig cfg = configWith("{\"maxContracts\":1,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":0,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    Intent oversized = new Intent("s", 1, 3, null, null, "enter");
    RiskChain.Result r = rc.evaluate(oversized, ctx(true, true, 1000, 1_000L, 0, 0L));
    check("blocked", r.allowed(), false);
    check("only 5 verdicts journaled (armed,session_open,readiness,daily_loss,size_cap) -- "
        + "rate_limit never reached despite also being violatable",
        r.verdicts().size(), 5);
    check("the one blocking verdict journaled is size_cap, not rate_limit",
        r.verdicts().get(4).filter(), "size_cap");
  }

  private static void testDailyLossBreachedMirrorsCheckDailyLoss() throws IOException {
    ExternalConfig cfg = configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":50,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    RiskChain.Context c0 = ctx(true, true, 1000, 1_000L, 0, 0L);
    rc.evaluate(enter, c0);
    rc.recordAccepted(enter, c0); // long from 1000, dailyLossLimitTicks=50

    check("dailyLossBreached false while within limits (unrealized 0)",
        rc.dailyLossBreached(ctx(true, true, 1000, 2_000L, 0, 0L)), false);
    check("dailyLossBreached false at exactly the limit boundary minus 1 (unrealized -49)",
        rc.dailyLossBreached(ctx(true, true, 951, 3_000L, 0, 0L)), false);
    check("dailyLossBreached true once unrealized loss reaches -50 (the limit)",
        rc.dailyLossBreached(ctx(true, true, 950, 4_000L, 0, 0L)), true);
    // Cross-check against checkDailyLoss()'s own verdict via evaluate() at
    // the same breaching price, on a position-neutral probe intent (same
    // target as lastPosition, so churn/rate never interfere).
    Intent stillLong = new Intent("s", 2, 1, null, null, "probe");
    RiskChain.Result r = rc.evaluate(stillLong, ctx(true, true, 950, 5_000L, 0, 0L));
    check("evaluate() blocks at daily_loss for the same breaching price dailyLossBreached() reported",
        r.allowed(), false);
  }

  private static void testDailyLossBreachedRollsOverIndependently() throws IOException {
    // dailyLossBreached() must perform its OWN session-rollover check
    // (maybeRolloverSession) even if evaluate() is never called at all on
    // the crossing event -- the whole point of D-85's kill switch being
    // independent of intent changes.
    ExternalConfig cfg = configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":50,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    long ct = java.time.ZonedDateTime.of(2026, 3, 15, 10, 0, 0, 0,
        java.time.ZoneId.of("America/Chicago")).toInstant().toEpochMilli();
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    RiskChain.Context c0 = ctx(true, true, 1000, ct, 0, 0L);
    rc.evaluate(enter, c0);
    rc.recordAccepted(enter, c0);

    // Realize a -40 loss, flat again, all same session, purely via
    // recordAccepted (never touching dailyLossBreached in between).
    Intent exitFlat = new Intent("s", 2, 0, null, null, "exit");
    RiskChain.Context c1 = ctx(true, true, 960, ct + 60_000L, 0, 0L);
    rc.evaluate(exitFlat, c1);
    rc.recordAccepted(exitFlat, c1); // realizedPnlTicks = -40, flat

    check("dailyLossBreached false -- flat, -40 realized doesn't breach -50 alone",
        rc.dailyLossBreached(ctx(true, true, 960, ct + 120_000L, 0, 0L)), false);

    // Cross 17:00 CT via dailyLossBreached() ALONE -- evaluate() is never
    // called on or after this instant in this test.
    long ctNextDay10am = java.time.ZonedDateTime.of(2026, 3, 16, 10, 0, 0, 0,
        java.time.ZoneId.of("America/Chicago")).toInstant().toEpochMilli();
    check("dailyLossBreached false after its OWN rollover reset the -40 realized loss to 0, "
        + "with no evaluate() call ever crossing the boundary",
        rc.dailyLossBreached(ctx(true, true, 960, ctNextDay10am, 0, 0L)), false);
  }

  /**
   * plumbingEdgeCases.md §4: traces the exact interleaving Pipeline.handle()
   * produces on the event that crosses a session boundary -- dailyLossBreached()
   * (every event) always runs before evaluate() (intent-change only) on
   * that same event, so evaluate()'s own rollover check is always a no-op
   * by the time it runs. Confirmed here rather than left as a hand-trace.
   */
  private static void testRolloverInterleaving_BreachedCheckThenEvaluateSameEvent() throws IOException {
    ExternalConfig cfg = configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":50,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    RiskChain rc = new RiskChain(cfg);
    long ct = java.time.ZonedDateTime.of(2026, 3, 15, 10, 0, 0, 0,
        java.time.ZoneId.of("America/Chicago")).toInstant().toEpochMilli();
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    RiskChain.Context c0 = ctx(true, true, 1000, ct, 0, 0L);
    rc.evaluate(enter, c0);
    rc.recordAccepted(enter, c0);

    Intent flip = new Intent("s", 2, -1, null, null, "flip");
    RiskChain.Context c1 = ctx(true, true, 1000, ct + 60_000L, 0, 0L);
    check("flip #1 allowed (0/1 reversals used)", rc.evaluate(flip, c1).allowed(), true);
    rc.recordAccepted(flip, c1); // reversalsThisSession = 1, now at cap

    long ctNextDay = java.time.ZonedDateTime.of(2026, 3, 16, 9, 0, 0, 0,
        java.time.ZoneId.of("America/Chicago")).toInstant().toEpochMilli();
    RiskChain.Context cBoundary = ctx(true, true, 1000, ctNextDay, 0, 0L);

    // Exact Pipeline.handle() order for this one event: the kill-switch
    // check runs first, on every event...
    boolean breached = rc.dailyLossBreached(cBoundary);
    check("not breached at the boundary event (flat + no loss)", breached, false);
    // ...then, only because a trigger also happens to fire on this same
    // event, evaluate() runs -- its own maybeRolloverSession() call must
    // find the rollover already applied (a no-op), not double-reset.
    Intent flipAgain = new Intent("s", 3, 1, null, null, "flip again");
    check("reversal allowed on the boundary event itself -- cap already reset "
        + "by dailyLossBreached()'s rollover, not still sitting at the old cap",
        rc.evaluate(flipAgain, cBoundary).allowed(), true);
  }
}
