package com.flow.core;

import com.flow.journal.JournalWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D-92: session-end flatten, as far as flow-core can prove it. Three layers:
 *   - RiskChain: entries blocked in the no-entry/flatten windows (flat
 *     intents never), flattenDue(), and onFlattened() -- without which a
 *     weekend gap would read as a daily-loss breach on an empty account.
 *   - Pipeline: sessionFlatten fires on entering the window and every
 *     FLATTEN_RETRY_MS after, once-per-window bookkeeping (journal line,
 *     strategy.onFlattened), re-arms the next day, survives an unhealthy
 *     pipeline, and is inert without a risk chain / callback.
 *   - Pipeline + RiskChain together: a real entry intent is blocked inside
 *     the window and allowed outside it.
 * Events are fed straight into Pipeline.handle() on this thread with
 * hand-picked event times (Chicago wall-clock), so it is deterministic.
 * 2026-09-23 is a Wednesday, 09-24 a Thursday, 09-25 a Friday.
 */
public final class SessionEndTest {
  private static final ZoneId CT = ZoneId.of("America/Chicago");
  private static int failures = 0;

  private static void check(String label, boolean ok) {
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static void checkEq(String label, Object got, Object want) {
    boolean ok = want == null ? got == null : want.equals(got);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static long ct(int d, int h, int mi, int s) {
    return ZonedDateTime.of(2026, 9, d, h, mi, s, 0, CT).toInstant().toEpochMilli();
  }

  private static ExternalConfig config(String extra) throws Exception {
    Path f = Files.createTempFile("flow_v2_test_sessionend", ".json");
    f.toFile().deleteOnExit();
    Files.writeString(f, "{\"maxContracts\":10,\"dailyLossLimitTicks\":50,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000" + extra + "}");
    return ExternalConfig.load(f);
  }

  private static RiskChain.Context ctx(long nowMs, Integer priceTicks) {
    return new RiskChain.Context(true, true, priceTicks, nowMs, 0, 0L);
  }

  private static Intent enter(int seq) {
    return new Intent("s", seq, 1, null, null, "enter");
  }

  private static Intent flat(int seq) {
    return new Intent("s", seq, 0, null, null, "exit");
  }

  public static void main(String[] args) throws Exception {
    testRiskChainSessionVerdict();
    testRiskChainFlattenDue();
    testOnFlattenedStopsAPhantomPositionTrippingTheKillSwitch();
    testPipelineFlattenCadenceAndOncePerWindowBookkeeping();
    testPipelineFlattenSurvivesAnUnhealthyPipeline();
    testPipelineTellsTheRiskChainSoAWeekendGapDoesNotTripTheKillSwitch();
    testPipelineWithoutCallbackOrRiskChainIsInert();
    testEntryIntentBlockedInsideTheWindowAndAllowedOutside();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all session-end flatten synthetic checks passed.");
  }

  // ---- RiskChain -------------------------------------------------------

  private static void testRiskChainSessionVerdict() throws Exception {
    RiskChain rc = new RiskChain(config(""));
    check("Wed 10:00: an entry is allowed", rc.evaluate(enter(1), ctx(ct(23, 10, 0, 0), 1000)).allowed());

    RiskChain.Result n = rc.evaluate(enter(2), ctx(ct(23, 15, 46, 0), 1000));
    check("Wed 15:46 (no-entry window): an entry is blocked", !n.allowed());
    RiskChain.Verdict last = n.verdicts().get(n.verdicts().size() - 1);
    check("...by the session_open filter, saying why",
        "session_open".equals(last.filter()) && last.reason().contains("NO_NEW_ENTRIES"));
    check("Wed 15:46: a flat intent is still allowed", rc.evaluate(flat(3), ctx(ct(23, 15, 46, 0), 1000)).allowed());

    check("Wed 15:56 (flatten window): an entry is blocked", !rc.evaluate(enter(4), ctx(ct(23, 15, 56, 0), 1000)).allowed());
    check("Wed 15:56: a flat intent is still allowed", rc.evaluate(flat(5), ctx(ct(23, 15, 56, 0), 1000)).allowed());
    check("Sat 12:00: an entry is blocked", !rc.evaluate(enter(6), ctx(ct(26, 12, 0, 0), 1000)).allowed());
    check("Wed 17:00: entries are allowed again", rc.evaluate(enter(7), ctx(ct(23, 17, 0, 0), 1000)).allowed());

    RiskChain off = new RiskChain(config(",\"flattenLeadMinutes\":0"));
    check("flattenLeadMinutes=0 switches the block off: Wed 15:56 entry allowed",
        off.evaluate(enter(8), ctx(ct(23, 15, 56, 0), 1000)).allowed());
  }

  private static void testRiskChainFlattenDue() throws Exception {
    RiskChain rc = new RiskChain(config(""));
    check("15:54:59 not due", !rc.flattenDue(ct(23, 15, 54, 59)));
    check("15:55:00 due", rc.flattenDue(ct(23, 15, 55, 0)));
    check("Wed 17:00 not due", !rc.flattenDue(ct(23, 17, 0, 0)));
    check("Saturday due", rc.flattenDue(ct(26, 12, 0, 0)));
    check("flattenLeadMinutes=0: never due", !new RiskChain(config(",\"flattenLeadMinutes\":0")).flattenDue(ct(26, 12, 0, 0)));
  }

  /**
   * The reason onFlattened() exists: long @1000, account flattened at 990 (realizing -10), then the market
   * gaps down over the weekend to 900. With the limit at 50 ticks, a RiskChain that still believed it was
   * long would read -100 and trip the kill switch on an empty account.
   */
  private static void testOnFlattenedStopsAPhantomPositionTrippingTheKillSwitch() throws Exception {
    long t = ct(23, 10, 0, 0);
    RiskChain control = new RiskChain(config(""));
    control.recordAccepted(enter(1), ctx(t, 1000));
    check("control: without onFlattened, the gap DOES look like a breach",
        control.dailyLossBreached(ctx(ct(28, 10, 0, 0), 900)));

    RiskChain rc = new RiskChain(config(""));
    rc.recordAccepted(enter(1), ctx(t, 1000));
    rc.onFlattened(ctx(ct(23, 15, 55, 0), 990));
    check("after onFlattened the -10 realized is not a breach", !rc.dailyLossBreached(ctx(ct(23, 15, 56, 0), 990)));
    // Realized loss survives inside the same session: re-enter at 15:56 (same session), -41 unrealized + -10 realized.
    rc.recordAccepted(enter(2), ctx(ct(23, 15, 56, 0), 1000));
    check("the -10 realized is still booked: a new position at -41 unrealized totals -51 and breaches",
        rc.dailyLossBreached(ctx(ct(23, 15, 57, 0), 959)));
    check("...while -39 unrealized (-49 total) does not", !rc.dailyLossBreached(ctx(ct(23, 15, 57, 0), 961)));

    // Last, because reading a Monday time rolls RiskChain's session over: flatten, then gap down over the weekend.
    RiskChain gap = new RiskChain(config(""));
    gap.recordAccepted(enter(1), ctx(t, 1000));
    gap.onFlattened(ctx(ct(25, 15, 55, 0), 990));
    check("a weekend gap to 900 is not a breach on a flat account", !gap.dailyLossBreached(ctx(ct(28, 10, 0, 0), 900)));
  }

  // ---- Pipeline --------------------------------------------------------

  /** Wakes on every tick and clock event, holds whatever intent it is told to, counts onFlattened. */
  private static final class StubStrategy implements FlowStrategy {
    final AtomicInteger flattened = new AtomicInteger(0);
    final AtomicInteger rejected = new AtomicInteger(0);
    final AtomicInteger seq = new AtomicInteger(0);
    volatile boolean wantEnter = false;
    @Override public String id() { return "stub"; }
    @Override public Set<String> requires() { return Set.of(); }
    @Override public Set<Trigger> triggers() { return Set.of(new Trigger.EveryTick()); }
    @Override public void onInit(StrategyConfig cfg) {}
    @Override public Intent onEvent(MarketState state) {
      return wantEnter ? new Intent(id(), seq.incrementAndGet(), 1, null, null, "enter") : Intent.none(id(), seq.incrementAndGet());
    }
    @Override public void onFlattened(String reason) { flattened.incrementAndGet(); }
    @Override public void onIntentRejected(Intent intent, String reason) { rejected.incrementAndGet(); }
  }

  private static ClockEvent clock(long seq, long timeMs) {
    return new ClockEvent(seq, timeMs, timeMs);
  }

  private static TickEvent tick(long seq, long timeMs, int priceTicks) {
    return new TickEvent(seq, timeMs, timeMs, priceTicks, 1, true, priceTicks, priceTicks, 0L, 0L);
  }

  private static int count(List<String> lines, String needle) {
    int n = 0;
    for (String l : lines) if (l.contains(needle)) n++;
    return n;
  }

  private static void testPipelineFlattenCadenceAndOncePerWindowBookkeeping() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_sessionend_cadence");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    StubStrategy strategy = new StubStrategy();
    AtomicInteger flattenCalls = new AtomicInteger(0);
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null, new RiskChain(config("")),
        () -> true, () -> 0, r -> {}, r -> flattenCalls.incrementAndGet());

    long seq = 0;
    p.handle(clock(++seq, ct(23, 15, 0, 0)));
    p.handle(clock(++seq, ct(23, 15, 54, 59)));
    checkEq("before 15:55: no flatten call", flattenCalls.get(), 0);
    checkEq("before 15:55: strategy not told", strategy.flattened.get(), 0);

    p.handle(clock(++seq, ct(23, 15, 55, 0)));
    checkEq("15:55:00 entering the window calls the flatten once", flattenCalls.get(), 1);
    checkEq("...and tells the strategy once", strategy.flattened.get(), 1);
    p.handle(clock(++seq, ct(23, 15, 55, 2)));
    p.handle(clock(++seq, ct(23, 15, 55, 4)));
    checkEq("+4s: still inside the 5s retry gap, no second call", flattenCalls.get(), 1);
    p.handle(clock(++seq, ct(23, 15, 55, 5)));
    checkEq("+5s: retry", flattenCalls.get(), 2);
    p.handle(clock(++seq, ct(23, 16, 30, 0)));
    checkEq("16:30: retry again (a quiet halt still flattens on schedule, driven by ClockEvents)", flattenCalls.get(), 3);
    checkEq("the strategy was told only ONCE for the whole window", strategy.flattened.get(), 1);

    p.handle(clock(++seq, ct(23, 17, 0, 0)));
    p.handle(clock(++seq, ct(23, 20, 0, 0)));
    checkEq("after the reopen no more flatten calls", flattenCalls.get(), 3);

    p.handle(clock(++seq, ct(24, 15, 55, 0)));
    checkEq("next day's window calls the flatten again", flattenCalls.get(), 4);
    checkEq("...and tells the strategy again", strategy.flattened.get(), 2);

    journal.flushAndClose();
    List<String> lines = Files.readAllLines(dir.resolve("decisions.jsonl"));
    checkEq("SESSION_FLATTEN_DUE journaled once per window (two windows)", count(lines, "\"type\":\"SESSION_FLATTEN_DUE\""), 2);
  }

  private static void testPipelineFlattenSurvivesAnUnhealthyPipeline() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_sessionend_unhealthy");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    StubStrategy strategy = new StubStrategy();
    AtomicInteger flattenCalls = new AtomicInteger(0);
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null, new RiskChain(config("")),
        () -> true, () -> 0, r -> {}, r -> flattenCalls.incrementAndGet());

    p.handle(clock(1, ct(25, 10, 0, 0)));
    p.onPipelineException(new RuntimeException("synthetic feature failure"), clock(1, ct(25, 10, 0, 0)));
    check("pipeline is now unhealthy", !p.healthy());

    p.handle(clock(2, ct(25, 15, 55, 0)));
    checkEq("an unhealthy pipeline STILL flattens (a position must not be left open because of a feature bug)",
        flattenCalls.get(), 1);
    checkEq("...but the strategy is not invoked while unhealthy", strategy.flattened.get(), 0);
    journal.flushAndClose();
  }

  /**
   * End to end through Pipeline: long @1000 during the day, flatten window at 990, market reopens Monday at 900
   * (-100 vs a 50-tick limit). Pipeline must have told RiskChain the account is flat, or the daily-loss check
   * would read the phantom position and fire the kill switch on an empty account.
   */
  private static void testPipelineTellsTheRiskChainSoAWeekendGapDoesNotTripTheKillSwitch() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_sessionend_gap");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    StubStrategy strategy = new StubStrategy();
    strategy.wantEnter = true;
    AtomicInteger killSwitchFires = new AtomicInteger(0);
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null, new RiskChain(config("")),
        () -> true, () -> 0, r -> killSwitchFires.incrementAndGet(), r -> {});

    p.handle(tick(1, ct(25, 10, 0, 0), 1000));  // Friday: enters long @1000, accepted
    p.handle(tick(2, ct(25, 15, 56, 0), 990));  // flatten window: RiskChain is told, -10 realized
    p.handle(tick(3, ct(28, 10, 0, 0), 900));   // Monday gap down to 900
    checkEq("no kill switch: the account was flat over the weekend", killSwitchFires.get(), 0);
    journal.flushAndClose();
  }

  private static void testPipelineWithoutCallbackOrRiskChainIsInert() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_sessionend_inert");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();

    StubStrategy s1 = new StubStrategy();
    Pipeline noCallback = new Pipeline(s1, journal, (i, e) -> {}, Map.of(), null, new RiskChain(config("")),
        () -> true, () -> 0, r -> {}); // the 9-arg constructor: no sessionFlatten
    noCallback.handle(clock(1, ct(23, 15, 55, 0)));
    noCallback.handle(clock(2, ct(23, 15, 56, 0)));
    checkEq("no callback: the strategy is still told (once)", s1.flattened.get(), 1);

    StubStrategy s2 = new StubStrategy();
    Pipeline noRisk = new Pipeline(s2, journal, (i, e) -> {}, Map.of(), null); // replay-style: no risk chain
    noRisk.handle(clock(1, ct(23, 15, 55, 0)));
    checkEq("no risk chain (replay/observer setups): nothing happens, no exception", s2.flattened.get(), 0);
    journal.flushAndClose();
  }

  private static void testEntryIntentBlockedInsideTheWindowAndAllowedOutside() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_sessionend_entry");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    StubStrategy strategy = new StubStrategy();
    strategy.wantEnter = true;
    AtomicInteger accepted = new AtomicInteger(0);
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> { if (i.targetPosition() != 0) accepted.incrementAndGet(); }, Map.of(), null,
        new RiskChain(config("")), () -> true, () -> 0, r -> {}, r -> {});

    p.handle(tick(1, ct(23, 15, 50, 0), 1000)); // no-entry window
    checkEq("an entry inside the no-entry window is not forwarded", accepted.get(), 0);
    checkEq("...and the strategy is told it was rejected", strategy.rejected.get(), 1);

    strategy.wantEnter = false;
    p.handle(tick(2, ct(23, 17, 1, 0), 1000)); // reopened: intent flips to none, registering a change
    strategy.wantEnter = true;
    p.handle(tick(3, ct(23, 17, 2, 0), 1000));
    checkEq("the same entry after the reopen is forwarded", accepted.get(), 1);
    journal.flushAndClose();

    List<String> lines = Files.readAllLines(dir.resolve("decisions.jsonl"));
    check("the block is journaled as a session_open verdict",
        lines.stream().anyMatch(l -> l.contains("risk_verdict") && l.contains("session_open") && l.contains("\"allowed\":false")));
  }
}
