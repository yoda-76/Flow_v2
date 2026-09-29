package com.flow.core;

import com.flow.journal.JournalWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F-1 / F-3 / F-4 (2026-09-28 P0 fixes), the flow-core halves:
 *   - RiskChain.AccountTruth: the daily-loss check reads the ACCOUNT's cash and position when a supplier gives them,
 *     instead of the signal-price estimate that fired ~10 ticks late in the live test (L-14). No supplier / a null or
 *     throwing supplier keeps the estimate exactly as before (DRY_RUN and replay).
 *   - Pipeline.suspendTrading()/resumeTrading(): a deactivated study does not wake the strategy (N-2: an allowed intent
 *     5 s after DEACTIVATE), and a re-activation resets the strategy's and risk chain's belief.
 *   - Pipeline.attachAccountWatch(): called about once a second from the clock, failure-proof.
 * Events are fed straight into Pipeline.handle() on this thread, so it is deterministic.
 */
public final class AccountTruthTest {
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

  private static ExternalConfig config(int limit) throws Exception {
    Path f = Files.createTempFile("flow_v2_test_truth", ".json");
    f.toFile().deleteOnExit();
    Files.writeString(f, "{\"maxContracts\":10,\"dailyLossLimitTicks\":" + limit + ","
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
    return ExternalConfig.load(f);
  }

  private static RiskChain.Context ctx(long nowMs, Integer priceTicks) {
    return new RiskChain.Context(true, true, priceTicks, nowMs, 0, 0L);
  }

  private static Intent enter(int seq) {
    return new Intent("s", seq, 1, null, null, "enter");
  }

  /** $10 a tick (gold), account started the session at 100,000. */
  private static RiskChain.AccountTruth truth(double cash, int position, Integer entryTicks) {
    return new RiskChain.AccountTruth(cash, 100_000.0, position, entryTicks, 10.0);
  }

  public static void main(String[] args) throws Exception {
    testTruthRealizedLossBreaches();
    testTruthUnrealizedCountsFromTheRealEntry();
    testTruthIgnoresTheEstimate();
    testTruthFallsBackToTheEstimate();
    testTruthRebasesAtTheDailyRollover();
    testPipelineSuspendResume();
    testPipelineResumeWithoutSuspendIsANoOp();
    testAccountWatchCadenceAndFailureIsolation();
    testFillEventReachesTheStrategyNotAWake();
    testFillEventSkippedWhenSuspendedOrUnhealthy();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all account-truth / suspend-resume synthetic checks passed.");
  }

  // ---- RiskChain.AccountTruth -------------------------------------------

  /** L-14: the account is down 60 ticks in cash; the chain has no idea (no intent was ever accepted). Limit 50. */
  private static void testTruthRealizedLossBreaches() throws Exception {
    AtomicReference<RiskChain.AccountTruth> t = new AtomicReference<>(truth(100_000.0, 0, null));
    RiskChain rc = new RiskChain(config(50), t::get);
    long now = ct(23, 10, 0, 0);
    check("flat cash: no breach", !rc.dailyLossBreached(ctx(now, 1000)));
    t.set(truth(99_450.0, 0, null)); // -$550 = -55 ticks
    check("cash down 55 ticks vs a 50-tick limit: breach, with no accepted intent at all",
        rc.dailyLossBreached(ctx(now + 1_000, 1000)));
    RiskChain.Result r = rc.evaluate(enter(1), ctx(now + 2_000, 1000));
    check("an entry is blocked by daily_loss", !r.allowed()
        && "daily_loss".equals(r.verdicts().get(r.verdicts().size() - 1).filter()));
    check("...and the reason says it is the account", r.verdicts().get(r.verdicts().size() - 1).reason().contains("(account)"));
    t.set(truth(99_510.0, 0, null)); // -49
    check("-49 ticks does not breach", !rc.dailyLossBreached(ctx(now + 3_000, 1000)));
  }

  /** Realized -25 and a long 1 from 1000 marked at 980 (-20): -45 total is fine, at 974 (-26) it is -51 and breaches. */
  private static void testTruthUnrealizedCountsFromTheRealEntry() throws Exception {
    RiskChain rc = new RiskChain(config(50), () -> truth(99_750.0, 1, 1000));
    long now = ct(23, 10, 0, 0);
    check("realized -25, unrealized -20 (long 1 @1000, price 980): -45, no breach", !rc.dailyLossBreached(ctx(now, 980)));
    check("price 974: unrealized -26, total -51: breach", rc.dailyLossBreached(ctx(now + 1_000, 974)));
    RiskChain shortPos = new RiskChain(config(50), () -> truth(99_750.0, -2, 1000));
    check("short 2 @1000 marked at 1013: unrealized -26 (scales with size)... total -51: breach",
        shortPos.dailyLossBreached(ctx(now, 1013)));
    RiskChain noEntry = new RiskChain(config(50), () -> truth(99_750.0, 1, null));
    check("an open position with no known entry price marks nothing (realized -25 only): no breach",
        !noEntry.dailyLossBreached(ctx(now, 500)));
  }

  /**
   * L-5/L-14 the other way round: the strategy's virtual position says -100 ticks (it entered at 1000, the price is
   * now 900) but the account is flat with the balance unchanged (the real bracket never filled / the study is not
   * the one trading). The account is the truth.
   */
  private static void testTruthIgnoresTheEstimate() throws Exception {
    RiskChain rc = new RiskChain(config(50), () -> truth(100_000.0, 0, null));
    long now = ct(23, 10, 0, 0);
    rc.recordAccepted(enter(1), ctx(now, 1000));
    RiskChain control = new RiskChain(config(50));
    control.recordAccepted(enter(1), ctx(now, 1000));
    check("control: the same intents with NO account truth DO read -100 and breach", control.dailyLossBreached(ctx(now + 1_000, 900)));
    check("with the account flat and cash unchanged there is no breach", !rc.dailyLossBreached(ctx(now + 1_000, 900)));
  }

  private static void testTruthFallsBackToTheEstimate() throws Exception {
    long now = ct(23, 10, 0, 0);
    for (String how : new String[] {"null supplier", "supplier returns null", "supplier throws", "zero tick value"}) {
      RiskChain rc = switch (how) {
        case "null supplier" -> new RiskChain(config(50));
        case "supplier returns null" -> new RiskChain(config(50), () -> null);
        case "supplier throws" -> new RiskChain(config(50), () -> { throw new IllegalStateException("gateway gone"); });
        default -> new RiskChain(config(50), () -> new RiskChain.AccountTruth(1.0, 1.0, 0, null, 0.0));
      };
      rc.recordAccepted(enter(1), ctx(now, 1000));
      check(how + ": the signal-price estimate is used (-100 breaches)", rc.dailyLossBreached(ctx(now + 1_000, 900)));
    }
  }

  /** Down 30 ticks on Wednesday; after the 17:00 CT rollover that loss is yesterday's -- only new losses count. */
  private static void testTruthRebasesAtTheDailyRollover() throws Exception {
    AtomicReference<RiskChain.AccountTruth> t = new AtomicReference<>(truth(99_700.0, 0, null));
    RiskChain rc = new RiskChain(config(50), t::get);
    check("Wed 10:00: down 30 ticks, no breach", !rc.dailyLossBreached(ctx(ct(23, 10, 0, 0), 1000)));
    check("Wed 16:30 (still the same session): no breach", !rc.dailyLossBreached(ctx(ct(23, 16, 30, 0), 1000)));
    check("Wed 17:05 (new session): rebased at 99,700 -- no breach", !rc.dailyLossBreached(ctx(ct(23, 17, 5, 0), 1000)));
    t.set(truth(99_400.0, 0, null)); // a further -30 today: -60 from the start of the week, but -30 from the rebase
    check("a further -30 ticks in the new session is -30 for the day: no breach", !rc.dailyLossBreached(ctx(ct(23, 18, 0, 0), 1000)));
    t.set(truth(99_190.0, 0, null)); // -51 from the rebase
    check("-51 ticks in the new session breaches", rc.dailyLossBreached(ctx(ct(23, 19, 0, 0), 1000)));
  }

  // ---- Pipeline ----------------------------------------------------------

  private static final class StubStrategy implements FlowStrategy {
    final AtomicInteger woken = new AtomicInteger(0);
    final AtomicInteger flattened = new AtomicInteger(0);
    final AtomicInteger seq = new AtomicInteger(0);
    final List<FillEvent> fills = new ArrayList<>();
    volatile boolean wantEnter = true;
    volatile RuntimeException throwOnFill = null;
    @Override public String id() { return "stub"; }
    @Override public Set<String> requires() { return Set.of(); }
    @Override public Set<Trigger> triggers() { return Set.of(new Trigger.EveryTick()); }
    @Override public void onInit(StrategyConfig cfg) {}
    @Override public Intent onEvent(MarketState state) {
      woken.incrementAndGet();
      return wantEnter ? new Intent(id(), seq.incrementAndGet(), 1, null, null, "enter") : Intent.none(id(), seq.incrementAndGet());
    }
    @Override public void onFlattened(String reason) { flattened.incrementAndGet(); }
    @Override public void onFill(FillEvent fill) {
      if (throwOnFill != null) throw throwOnFill;
      fills.add(fill);
    }
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

  private static void testPipelineSuspendResume() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_truth_suspend");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    StubStrategy strategy = new StubStrategy();
    AtomicInteger delivered = new AtomicInteger(0);
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> delivered.incrementAndGet(), Map.of(), null,
        new RiskChain(config(1000)), () -> true, () -> 0, r -> {}, r -> {});

    long base = ct(23, 10, 0, 0);
    p.handle(tick(1, base, 1000));
    checkEq("active: the strategy is woken", strategy.woken.get(), 1);
    checkEq("active: its entry is delivered", delivered.get(), 1);

    p.suspendTrading();
    check("suspended flag is visible", p.suspended());
    p.handle(tick(2, base + 6_000, 1001));
    p.handle(tick(3, base + 12_000, 1002));
    checkEq("suspended (study deactivated): the strategy is NOT woken", strategy.woken.get(), 1);
    checkEq("...and nothing is delivered", delivered.get(), 1);
    checkEq("...but market state still advances (features keep ingesting)", p.marketState().lastPriceTicks(), 1002);

    strategy.wantEnter = false;
    p.resumeTrading();
    check("resumed", !p.suspended());
    checkEq("resume alone does not run anything -- it waits for the next event", strategy.flattened.get(), 0);
    p.handle(tick(4, base + 18_000, 1003));
    checkEq("the strategy was told the account is flat, once", strategy.flattened.get(), 1);
    checkEq("...and is woken again from then on", strategy.woken.get(), 2);
    p.handle(tick(5, base + 24_000, 1004));
    checkEq("no repeat of the reset", strategy.flattened.get(), 1);

    // The risk chain forgot the position too: an entry now is a fresh change (no dwell/rate leftovers), not a restatement.
    strategy.wantEnter = true;
    p.handle(tick(6, base + 30_000, 1005));
    checkEq("a new entry after the resume is delivered as a change", delivered.get(), 2);

    journal.flushAndClose();
    List<String> lines = Files.readAllLines(dir.resolve("decisions.jsonl"));
    checkEq("one pipeline_resync record", count(lines, "\"type\":\"pipeline_resync\""), 1);
  }

  private static void testPipelineResumeWithoutSuspendIsANoOp() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_truth_noop");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    StubStrategy strategy = new StubStrategy();
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null, new RiskChain(config(1000)),
        () -> true, () -> 0, r -> {}, r -> {});
    p.resumeTrading(); // the FIRST activation of a fresh study
    p.handle(tick(1, ct(23, 10, 0, 0), 1000));
    checkEq("first activation: no spurious reset of a fresh strategy", strategy.flattened.get(), 0);
    checkEq("...and it is woken as normal", strategy.woken.get(), 1);
    journal.flushAndClose();
  }

  private static void testAccountWatchCadenceAndFailureIsolation() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_truth_watch");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    StubStrategy strategy = new StubStrategy();
    strategy.wantEnter = false;
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null, new RiskChain(config(1000)),
        () -> true, () -> 0, r -> {}, r -> {});
    AtomicInteger calls = new AtomicInteger(0);
    p.attachAccountWatch(() -> {
      if (calls.incrementAndGet() == 2) throw new IllegalStateException("gateway went away");
    });
    long t0 = ct(23, 10, 0, 0);
    for (int i = 1; i <= 35; i++) p.handle(clock(i, t0 + i * 100L));
    checkEq("called once per 10 clock events (10, 20, 30) = 3 times", calls.get(), 3);
    check("a throwing watch does not make the pipeline unhealthy", p.healthy());
    journal.flushAndClose();
    List<String> lines = Files.readAllLines(dir.resolve("decisions.jsonl"));
    checkEq("the failure was journaled once", count(lines, "\"type\":\"account_watch_failed\""), 1);
  }

  // ---- C1 (2026-09-29, D-120): FillEvent reaches the strategy, but is never itself a wake ----------------------

  private static FillEvent fill(long seq, long t, FillEvent.Role role, boolean isBuy, int priceTicks, int qty, int posAfter) {
    return new FillEvent(seq, t, t, "SIM-" + seq, role, isBuy, priceTicks, qty, posAfter);
  }

  private static void testFillEventReachesTheStrategyNotAWake() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_truth_fill");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    StubStrategy strategy = new StubStrategy();
    strategy.wantEnter = false;
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null, new RiskChain(config(1000)),
        () -> true, () -> 0, r -> {}, r -> {});
    long t0 = ct(23, 10, 0, 0);
    p.handle(tick(1, t0, 1000)); // one ordinary event first, so it isn't mistaken for "the first event ever"
    checkEq("baseline: one wake so far", strategy.woken.get(), 1);

    FillEvent f = fill(2, t0 + 1000, FillEvent.Role.ENTRY, true, 1005, 1, 1);
    p.handle(f);
    checkEq("the strategy heard the fill, exactly as published", strategy.fills.size(), 1);
    check("...the same event, unchanged", strategy.fills.get(0).equals(f));
    checkEq("a FillEvent is NOT a wake -- onEvent() was not called for it", strategy.woken.get(), 1);

    journal.flushAndClose();
    List<String> lines = Files.readAllLines(dir.resolve("decisions.jsonl"));
    checkEq("a fill notification writes no risk_verdict/intent_changed of its own", count(lines, "\"type\":\"risk_verdict\""), 0);
  }

  private static void testFillEventSkippedWhenSuspendedOrUnhealthy() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_truth_fill_gated");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    StubStrategy strategy = new StubStrategy();
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null, new RiskChain(config(1000)),
        () -> true, () -> 0, r -> {}, r -> {});
    long t0 = ct(23, 10, 0, 0);

    p.suspendTrading();
    p.handle(fill(1, t0, FillEvent.Role.ENTRY, true, 1000, 1, 1));
    checkEq("suspended: the fill never reaches the strategy", strategy.fills.size(), 0);

    p.resumeTrading();
    p.handle(tick(2, t0 + 1000, 1000)); // the resync happens on the NEXT event
    p.handle(fill(3, t0 + 2000, FillEvent.Role.ENTRY, true, 1000, 1, 1));
    checkEq("resumed: fills reach the strategy again", strategy.fills.size(), 1);

    p.onPipelineException(new IllegalStateException("boom"), tick(4, t0 + 3000, 1000));
    p.handle(fill(5, t0 + 4000, FillEvent.Role.ENTRY, true, 1000, 1, 1));
    checkEq("unhealthy: the fill never reaches the strategy either", strategy.fills.size(), 1);

    // An exception FROM onFill() must not escape handle() -- Sequencer's drain loop is what actually catches it in
    // production (see its javadoc); calling handle() directly here, an uncaught throw would fail this test loudly.
    Pipeline p2 = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null, new RiskChain(config(1000)),
        () -> true, () -> 0, r -> {}, r -> {});
    strategy.throwOnFill = new IllegalStateException("strategy's onFill blew up");
    boolean threw = false;
    try {
      p2.handle(fill(6, t0 + 5000, FillEvent.Role.ENTRY, true, 1000, 1, 1));
    } catch (RuntimeException e) {
      threw = true;
    }
    check("Pipeline.handle() itself does not swallow the exception (that is the Sequencer's job, by design)", threw);
    journal.flushAndClose();
  }
}
