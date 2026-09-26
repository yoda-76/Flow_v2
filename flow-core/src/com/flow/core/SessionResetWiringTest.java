package com.flow.core;

import com.flow.flow.VWAPFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Direct synthetic check that RiskChain's and VWAPFeature's 17:00 CT
 * session-boundary wiring (D-68b, SessionBoundary) actually resets what
 * it's supposed to and, just as importantly, does NOT reset what it's
 * not supposed to (an open position's realized/entry state). Same
 * discipline as SessionBoundaryTest/MarketStructureFeatureTest.
 */
public final class SessionResetWiringTest {
  private static final ZoneId CT = ZoneId.of("America/Chicago");
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

  private static void check(String label, boolean got, boolean want) {
    check(label, (Object) got, (Object) want);
  }

  private static long ct(int y, int mo, int d, int h, int mi) {
    return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, CT).toInstant().toEpochMilli();
  }

  public static void main(String[] args) throws IOException {
    testReversalCapResetsAcrossSessionBoundary();
    testDailyLossResetsAcrossSessionBoundary_ButNotAnOpenPosition();
    testVwapResetsAcrossSessionBoundary();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all session-reset wiring checks passed.");
  }

  private static ExternalConfig configWith(String json) throws IOException {
    Path f = Files.createTempFile("flow_v2_test_risk", ".json");
    f.toFile().deleteOnExit();
    Files.writeString(f, json);
    return ExternalConfig.load(f);
  }

  private static RiskChain.Context ctx(boolean armed, Integer priceTicks, long nowMs) {
    return new RiskChain.Context(armed, true, priceTicks, nowMs, 0, 0L);
  }

  private static void testReversalCapResetsAcrossSessionBoundary() throws IOException {
    ExternalConfig cfg = configWith(
        "{\"maxReversalsPerSession\":2,\"minDwellMs\":0,\"rateLimitPerMinute\":100,\"dailyLossLimitTicks\":100000}");
    RiskChain rc = new RiskChain(cfg);

    long t0 = ct(2026, 3, 16, 10, 0);
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    Intent flip1 = new Intent("s", 2, -1, null, null, "flip1");
    Intent flip2 = new Intent("s", 3, 1, null, null, "flip2");
    Intent flip3 = new Intent("s", 4, -1, null, null, "flip3");

    RiskChain.Context c0 = ctx(true, 1000, t0);
    check("Entry allowed", rc.evaluate(enter, c0).allowed(), true);
    rc.recordAccepted(enter, c0);

    RiskChain.Context c1 = ctx(true, 1000, t0 + 60_000);
    check("Reversal #1 allowed (reversalsThisSession 0->1)", rc.evaluate(flip1, c1).allowed(), true);
    rc.recordAccepted(flip1, c1);

    RiskChain.Context c2 = ctx(true, 1000, t0 + 120_000);
    check("Reversal #2 allowed (reversalsThisSession 1->2, now at cap)", rc.evaluate(flip2, c2).allowed(), true);
    rc.recordAccepted(flip2, c2);

    RiskChain.Context c3 = ctx(true, 1000, t0 + 180_000); // still same session (well before 17:00 CT)
    check("Reversal #3 BLOCKED -- cap (2) already reached, same session",
        rc.evaluate(flip3, c3).allowed(), false);

    // Cross 17:00 CT into the next session, retry the exact same reversal.
    long tNext = ct(2026, 3, 16, 17, 30); // same day, after the rollover
    RiskChain.Context c4 = ctx(true, 1000, tNext);
    check("Same reversal ALLOWED after the session boundary -- cap reset to 0",
        rc.evaluate(flip3, c4).allowed(), true);
  }

  /**
   * Real finding while building this test, not itself the point of the
   * test but worth recording: checkDailyLoss() runs unconditionally on
   * every evaluate() call, INCLUDING an exit -- there is no "this intent
   * is reducing risk" exemption. An exit whose own mark-to-market loss
   * (at the moment it's evaluated) alone breaches the limit is BLOCKED
   * exactly like an entry would be, which means a position can get stuck
   * open past the point where flattening it would itself be the correct
   * move. One consequence proven here, incidentally: because of this, a
   * FLAT position's realizedPnlTicks can never legitimately end up past
   * the limit through the chain's own gate (the exit that would cross the
   * line is blocked before it can realize), so this test builds up a
   * sub-limit realized loss and combines it with a same-session, currently
   * OPEN position's unrealized loss to breach the limit while staying
   * inside code the chain will actually allow to execute. Flagged to the
   * user rather than changed -- this is a real risk-chain gap (an
   * always-allow-de-risking-intents exemption seems like the obvious fix)
   * but it's safety-relevant logic, not something to change unilaterally.
   */
  private static void testDailyLossResetsAcrossSessionBoundary_ButNotAnOpenPosition() throws IOException {
    ExternalConfig cfg = configWith(
        "{\"dailyLossLimitTicks\":50,\"minDwellMs\":0,\"rateLimitPerMinute\":100,\"maxReversalsPerSession\":1000}");
    RiskChain rc = new RiskChain(cfg);

    long t0 = ct(2026, 3, 16, 10, 0);
    Intent enterLong1 = new Intent("s", 1, 1, null, null, "enter1");
    Intent exitFlat = new Intent("s", 2, 0, null, null, "exit1");
    Intent enterLong2 = new Intent("s", 3, 1, null, null, "enter2");

    // Round trip #1: realize a -40 tick loss (within the -50 limit), ending flat.
    RiskChain.Context c0 = ctx(true, 1000, t0);
    check("Entry #1 allowed", rc.evaluate(enterLong1, c0).allowed(), true);
    rc.recordAccepted(enterLong1, c0);

    RiskChain.Context c1 = ctx(true, 960, t0 + 60_000); // unrealized at exit = -40, total = 0-40 = -40 > -50
    check("Exit #1 allowed -- its own loss (-40) doesn't breach the -50 limit on its own",
        rc.evaluate(exitFlat, c1).allowed(), true);
    rc.recordAccepted(exitFlat, c1); // realizedPnlTicks now -40, flat

    // Re-enter (flat -> long again) at the same price -- allowed, since flat means
    // checkDailyLoss only sees the -40 realized so far.
    RiskChain.Context c2 = ctx(true, 960, t0 + 120_000);
    check("Entry #2 allowed -- flat, only the sub-limit realized loss applies",
        rc.evaluate(enterLong2, c2).allowed(), true);
    rc.recordAccepted(enterLong2, c2); // entryPriceTicks=960, lastPosition=1

    // Probe (no position change -- just checking the verdict): price drops to 940,
    // unrealized -20 on top of the -40 already realized = -60, breaches -50.
    RiskChain.Context c3 = ctx(true, 940, t0 + 180_000); // same session
    check("BLOCKED, same session -- realized(-40) + this open position's unrealized(-20) = -60 breaches -50",
        rc.evaluate(enterLong2, c3).allowed(), false);

    // Cross 17:00 CT. Same open position, same price -- only realizedPnlTicks resets.
    long tNext = ct(2026, 3, 16, 18, 0);
    RiskChain.Context c4 = ctx(true, 940, tNext);
    RiskChain.Result r4 = rc.evaluate(enterLong2, c4);
    check("ALLOWED after the session boundary -- realized loss reset to 0, only unrealized(-20) remains",
        r4.allowed(), true);
  }

  private static void testVwapResetsAcrossSessionBoundary() {
    VWAPFeature vwap = new VWAPFeature("vwap", ticks -> ticks); // trivial 1:1 decoder for round numbers

    long t0 = ct(2026, 3, 16, 10, 0);
    vwap.onEvent(new TickEvent(1, t0, t0, 100, 10, true, 100, 100, 0L, 0L));
    vwap.onEvent(new TickEvent(2, t0 + 1000, t0 + 1000, 200, 10, true, 200, 200, 0L, 0L));
    check("VWAP ready after ticks", vwap.isReady(), true);
    check("VWAP averages the two ticks (100 and 200, equal volume) -> 150", vwap.vwap(), 150.0);
    check("Total volume so far", vwap.totalVolume(), 20.0);

    // Cross 17:00 CT with a ClockEvent (no price) -- must reset even without a new tick.
    long tBoundary = ct(2026, 3, 16, 17, 0);
    vwap.onEvent(new ClockEvent(3, tBoundary, tBoundary));
    check("VWAP not ready immediately after the rollover (no tick yet this session)", vwap.isReady(), false);
    check("VWAP value cleared by the rollover", vwap.vwap(), null);
    check("Total volume cleared by the rollover", vwap.totalVolume(), 0.0);

    // A fresh tick in the new session must not be polluted by the old totals.
    long t1 = ct(2026, 3, 16, 18, 0);
    vwap.onEvent(new TickEvent(4, t1, t1, 500, 5, true, 500, 500, 0L, 0L));
    check("New session's VWAP reflects only its own tick (500), not the old accumulation",
        vwap.vwap(), 500.0);
    check("New session's total volume reflects only its own tick (5)", vwap.totalVolume(), 5.0);
  }
}
