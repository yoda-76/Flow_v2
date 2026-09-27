package com.flow.core;

import com.flow.journal.JournalWriter;
import com.flow.strategies.LvnFadeTestStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression tests for the 2026-09-27 code review's risk-chain / pipeline fixes (docs/dynamic/codeReview.md):
 *   B1 a flat intent is never blocked; A4 only real position changes use (or are limited by) the rate limit;
 *   A7 risk-chain P&L scales with position size; B2 a repeated blocked intent is re-evaluated (throttled, silent
 *   while still blocked) and the strategy is told on every repeat, and lvn_fade_test rolls a blocked entry back.
 * Plain main(), nonzero exit on failure, wired into build/build.sh.
 */
public final class RiskReviewFixesTest {
  private static int failures = 0;

  private static void checkEq(String label, Object got, Object want) {
    boolean ok = java.util.Objects.equals(got, want);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static ExternalConfig cfg(String json) throws Exception {
    Path f = Files.createTempFile("flow_v2_review_fixes", ".json");
    f.toFile().deleteOnExit();
    Files.writeString(f, json);
    return ExternalConfig.load(f);
  }

  private static RiskChain.Context ctx(boolean armed, int price, long t) {
    return new RiskChain.Context(armed, true, price, t, 0, 0L);
  }

  public static void main(String[] args) throws Exception {
    testFlatIsNeverBlocked();
    testRateLimitCountsOnlyRealChanges();
    testPnlScalesWithSize();
    testPipelineRetriesABlockedIntentOnceTheBlockLifts();
    testPipelineTellsTheStrategyOnEveryRepeatAndStaysQuiet();
    testLvnFadeRollsBackABlockedEntry();
    testRiskClockIsMonotonicOnTheRealRecording();
    testDomEventsDoNotMoveExchangeTime();
    testSessionTrackerIgnoresAnEarlierSession();
    testPipelineJudgesWindowsOnTheRiskClock();
    testDataStoreClosesOldDaysAndPrunesPastAFailure();
    testDomBacklogIsBounded();
    testRuntimeRefusalIsTreatedLikeABlock();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: code-review risk fixes checks passed.");
  }

  // ---- B1 ---------------------------------------------------------------------------------------------------

  private static void testFlatIsNeverBlocked() throws Exception {
    // Dwell 5 s, limit 1 change/min, 1 reversal: an exit 2 s after entry would have been blocked by churn before.
    RiskChain rc = new RiskChain(cfg("{\"maxContracts\":1,\"dailyLossLimitTicks\":100000,\"rateLimitPerMinute\":1,"
        + "\"minDwellMs\":5000,\"maxReversalsPerSession\":1,\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}"));
    long t0 = 1_000_000L;
    Intent enter = new Intent("s", 1, 1, 990, 1010, "enter");
    checkEq("B1: the entry is allowed", rc.evaluate(enter, ctx(true, 1000, t0)).allowed(), true);
    rc.recordAccepted(enter, ctx(true, 1000, t0));

    Intent exit = new Intent("s", 2, 0, null, null, "stop_hit");
    RiskChain.Result r = rc.evaluate(exit, ctx(true, 990, t0 + 2_000));
    checkEq("B1: an exit inside the 5 s dwell is allowed (it was blocked by churn before)", r.allowed(), true);
    checkEq("B1: its single verdict says why", r.verdicts().get(0).filter(), "flat");
    rc.recordAccepted(exit, ctx(true, 990, t0 + 2_000));
    checkEq("B1: after the exit the chain is flat: no phantom unrealized P&L marks against later prices",
        rc.dailyLossBreached(ctx(true, 0, t0 + 3_000)), false);

    RiskChain notArmed = new RiskChain(cfg("{}"));
    checkEq("B1: a flat intent is allowed even when not armed",
        notArmed.evaluate(new Intent("s", 1, 0, null, null, "none"), ctx(false, 1000, t0)).allowed(), true);
    checkEq("B1: but an entry when not armed is still blocked",
        notArmed.evaluate(new Intent("s", 2, 1, null, null, "enter"), ctx(false, 1000, t0)).allowed(), false);
  }

  // ---- A4 ---------------------------------------------------------------------------------------------------

  private static void testRateLimitCountsOnlyRealChanges() throws Exception {
    RiskChain rc = new RiskChain(cfg("{\"maxContracts\":1,\"dailyLossLimitTicks\":100000,\"rateLimitPerMinute\":2,"
        + "\"minDwellMs\":0,\"maxReversalsPerSession\":1000,\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}"));
    long t = 1_000_000L;
    Intent enter = new Intent("s", 1, 1, null, null, "enter");
    rc.recordAccepted(enter, ctx(true, 1000, t));                                // change 1 of 2
    for (int i = 0; i < 5; i++) {                                               // "holding" restatements
      Intent hold = new Intent("s", 10 + i, 1, null, null, "holding " + i);
      RiskChain.Result r = rc.evaluate(hold, ctx(true, 1000, t + 100 + i));
      checkEq("A4: a no-change 'holding' intent is never rate-limited (#" + i + ")", r.allowed(), true);
      rc.recordAccepted(hold, ctx(true, 1000, t + 100 + i));
    }
    Intent exit = new Intent("s", 20, 0, null, null, "exit");
    rc.recordAccepted(exit, ctx(true, 1000, t + 200));                          // change 2 of 2
    Intent again = new Intent("s", 21, -1, null, null, "enter again");
    checkEq("A4: the third real change IS limited (2 real changes used, holdings did not count)",
        rc.evaluate(again, ctx(true, 1000, t + 300)).allowed(), false);
    checkEq("A4: with the window full, a no-change intent (still flat) is still allowed -- nothing to limit",
        rc.evaluate(new Intent("s", 22, 0, null, null, "none"), ctx(true, 1000, t + 310)).allowed(), true);
    RiskChain full = new RiskChain(cfg("{\"maxContracts\":1,\"dailyLossLimitTicks\":100000,\"rateLimitPerMinute\":1,"
        + "\"minDwellMs\":0,\"maxReversalsPerSession\":1000,\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}"));
    full.recordAccepted(new Intent("s", 1, 1, null, null, "enter"), ctx(true, 1000, t));
    checkEq("A4: with the window full, a 'holding' restatement (same position) is still allowed",
        full.evaluate(new Intent("s", 2, 1, null, null, "holding"), ctx(true, 1000, t + 10)).allowed(), true);

    RiskChain rc2 = new RiskChain(cfg("{\"maxContracts\":1,\"dailyLossLimitTicks\":100000,\"rateLimitPerMinute\":2,"
        + "\"minDwellMs\":0,\"maxReversalsPerSession\":1000,\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}"));
    rc2.recordAccepted(new Intent("s", 1, 1, null, null, "enter"), ctx(true, 1000, t));
    for (int i = 0; i < 5; i++) rc2.recordAccepted(new Intent("s", 2 + i, 1, null, null, "holding " + i), ctx(true, 1000, t + i));
    checkEq("A4: after 1 change and 5 restatements a second real change is still allowed (1 of 2 used)",
        rc2.evaluate(new Intent("s", 9, -1, null, null, "flip"), ctx(true, 1000, t + 50)).allowed(), true);
  }

  // ---- A7 ---------------------------------------------------------------------------------------------------

  private static void testPnlScalesWithSize() throws Exception {
    RiskChain rc = new RiskChain(cfg("{\"maxContracts\":3,\"dailyLossLimitTicks\":50,\"rateLimitPerMinute\":1000,"
        + "\"minDwellMs\":0,\"maxReversalsPerSession\":1000,\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}"));
    long t = 1_000_000L;
    rc.recordAccepted(new Intent("s", 1, 3, null, null, "enter 3"), ctx(true, 1000, t));
    checkEq("A7: 3 lots, 17 ticks against = -51 -> breached (1 lot would be -17)",
        rc.dailyLossBreached(ctx(true, 983, t + 1)), true);
    checkEq("A7: 3 lots, 16 ticks against = -48 -> not breached", rc.dailyLossBreached(ctx(true, 984, t + 2)), false);
    RiskChain booked = new RiskChain(cfg("{\"maxContracts\":3,\"dailyLossLimitTicks\":50,\"rateLimitPerMinute\":1000,"
        + "\"minDwellMs\":0,\"maxReversalsPerSession\":1000}"));
    booked.recordAccepted(new Intent("s", 1, 3, null, null, "enter 3"), ctx(true, 1000, t));
    booked.recordAccepted(new Intent("s", 2, 0, null, null, "exit"), ctx(true, 983, t + 1));
    checkEq("A7: 3 lots closed 17 ticks down books -51 REALIZED -> breached while flat",
        booked.dailyLossBreached(ctx(true, 2000, t + 2)), true);
    RiskChain one = new RiskChain(cfg("{\"maxContracts\":3,\"dailyLossLimitTicks\":50}"));
    one.recordAccepted(new Intent("s", 1, -1, null, null, "short 1"), ctx(true, 1000, t));
    checkEq("A7: 1 lot short, 51 ticks against -> breached (unchanged at 1 contract)",
        one.dailyLossBreached(ctx(true, 1051, t + 1)), true);
  }

  // ---- B2: Pipeline ------------------------------------------------------------------------------------------

  /** Always wants long 1 with the same content; counts rejections. */
  private static final class StubbornLong implements FlowStrategy {
    final AtomicInteger rejected = new AtomicInteger();
    private final AtomicInteger seq = new AtomicInteger();
    @Override public String id() { return "stubborn"; }
    @Override public Set<String> requires() { return Set.of(); }
    @Override public Set<Trigger> triggers() { return Set.of(new Trigger.EveryTick()); }
    @Override public void onInit(StrategyConfig cfg) {}
    @Override public Intent onEvent(MarketState state) { return new Intent(id(), seq.incrementAndGet(), 1, null, null, "enter"); }
    @Override public void onIntentRejected(Intent intent, String reason) { rejected.incrementAndGet(); }
  }

  private static TickEvent tickAt(long seq, long timeMs, int price) {
    return new TickEvent(seq, timeMs, timeMs, price, 1, true, price, price, 0L, 0L);
  }

  /** Runs one tick per entry of `times`; `armedAt[i]` is the Armed flag while event i is handled. */
  private static List<String> run(FlowStrategy strategy, boolean[] armedAt, long[] times, List<Integer> sinkCount)
      throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_review_pipeline");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    RiskChain rc = new RiskChain(cfg("{\"maxContracts\":1,\"dailyLossLimitTicks\":100000,\"rateLimitPerMinute\":1000,"
        + "\"minDwellMs\":0,\"maxReversalsPerSession\":1000,\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000,"
        + "\"flattenLeadMinutes\":0}"));
    java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean(false);
    Pipeline p = new Pipeline(strategy, journal, (i, e) -> sinkCount.add(1), Map.of(), ticks -> ticks, rc, armed::get,
        () -> 0, r -> {});
    for (int i = 0; i < times.length; i++) {
      armed.set(armedAt[i]);
      p.handle(tickAt(i + 1, times[i], 1000)); // drain-thread semantics, called directly
    }
    journal.flushAndClose();
    return Files.readAllLines(dir.resolve("decisions.jsonl"));
  }

  private static long verdicts(List<String> lines, boolean allowed) {
    java.util.regex.Pattern top = java.util.regex.Pattern.compile("\"intentSeq\":[0-9]+,\"allowed\":" + allowed);
    return lines.stream().filter(l -> l.contains("\"type\":\"risk_verdict\"") && top.matcher(l).find()).count();
  }

  private static void testPipelineRetriesABlockedIntentOnceTheBlockLifts() throws Exception {
    // 1 s apart, so every repeat is due a retry. Not armed for events 1-3 (two silent blocked retries), armed from 4.
    StubbornLong s = new StubbornLong();
    List<Integer> sink = new java.util.ArrayList<>();
    List<String> lines = run(s, new boolean[] {false, false, false, true, true},
        new long[] {1_000, 2_000, 3_000, 4_000, 5_000}, sink);
    checkEq("B2: the entry reaches the sink once arming lifts the block (it used to be dropped forever)", sink.size(), 1);
    checkEq("B2: exactly one allowed verdict is journaled", verdicts(lines, true), 1L);
    checkEq("B2: the two still-blocked retries wrote no verdict (only the first block is journaled)",
        verdicts(lines, false), 1L);
    checkEq("B2: only one intent_changed line (retries are not changes)",
        lines.stream().filter(l -> l.contains("\"type\":\"intent_changed\"")).count(), 1L);
  }

  private static void testPipelineTellsTheStrategyOnEveryRepeatAndStaysQuiet() throws Exception {
    // Blocked at the first event; armed from the 2nd, but repeats inside 1 s are NOT re-evaluated (throttle): the
    // entry goes through only at the first repeat >= 1 s after the block -- the 11th event (t0 + 1000 ms).
    StubbornLong s = new StubbornLong();
    List<Integer> sink = new java.util.ArrayList<>();
    long[] times = new long[11];
    boolean[] armedAt = new boolean[11];
    for (int i = 0; i < 11; i++) {
      times[i] = 10_000 + i * 100L;
      armedAt[i] = i > 0;
    }
    List<String> lines = run(s, armedAt, times, sink);
    checkEq("B2: the strategy is told 'rejected' on the first block and on each of the 9 throttled repeats",
        s.rejected.get(), 10);
    checkEq("B2: repeats inside 1 s are not re-evaluated -- the entry went through exactly once, at the 1 s retry",
        sink.size(), 1);
    checkEq("B2: one blocked verdict in the journal", verdicts(lines, false), 1L);
  }

  // ---- B2: lvn_fade_test rollback -------------------------------------------------------------------------------

  /** A volume profile with exactly one LVN zone, [1000, 1005]. */
  private static final class OneLvn implements com.flow.flow.VolumeProfileView {
    @Override public String id() { return FEATURE_ID; }
    @Override public void onEvent(Event e) {}
    @Override public boolean isReady() { return true; }
    @Override public String notReadyReason() { return null; }
    @Override public Integer poc() { return 1010; }
    @Override public Integer vah() { return 1020; }
    @Override public Integer val() { return 990; }
    @Override public double totalVolume() { return 100; }
    @Override public double totalDelta() { return 0; }
    @Override public Double volumeAt(int bucketPriceTicks) { return null; }
    @Override public List<com.flow.flow.ZoneView> zones() {
      return List.of(new com.flow.flow.ZoneView("lvn-1", com.flow.flow.ZoneView.Kind.LVN, 1000, 1005, 0L));
    }
  }

  private static Intent step(LvnFadeTestStrategy lvn, MutableMarketState st, long seq, long t, int price) {
    st.bump(tickAt(seq, t, price));
    return lvn.onEvent(st);
  }

  private static void testLvnFadeRollsBackABlockedEntry() {
    // Blocked ENTRY -> back to searching: the next tick inside the zone gives a flat "none", not a "holding".
    LvnFadeTestStrategy lvn = new LvnFadeTestStrategy();
    MutableMarketState st = new MutableMarketState(Map.of(com.flow.flow.VolumeProfileView.FEATURE_ID, new OneLvn()));
    step(lvn, st, 1, 0L, 990);                            // first event starts the 2-minute warm-up
    step(lvn, st, 2, 200_000L, 990);                      // past warm-up, below the zone
    Intent entry = step(lvn, st, 3, 200_001L, 1000);      // enters the LVN from below -> SHORT
    checkEq("B2 setup: entering the LVN from below produces a short entry", entry.targetPosition(), -1);
    lvn.onIntentRejected(entry, "not armed");
    Intent after = step(lvn, st, 4, 200_002L, 1001);
    checkEq("B2: after a blocked entry lvn_fade_test is searching again (flat, not 'holding')", after.targetPosition(), 0);

    // Blocked 'holding' -> still in position.
    LvnFadeTestStrategy lvn2 = new LvnFadeTestStrategy();
    MutableMarketState st2 = new MutableMarketState(Map.of(com.flow.flow.VolumeProfileView.FEATURE_ID, new OneLvn()));
    step(lvn2, st2, 1, 0L, 990);
    step(lvn2, st2, 2, 200_000L, 990);
    step(lvn2, st2, 3, 200_001L, 1000);                   // entry accepted (no rejection)
    Intent hold = step(lvn2, st2, 4, 200_002L, 1001);
    checkEq("B2 setup: the next tick restates the position as 'holding'", hold.reason(), "holding");
    lvn2.onIntentRejected(hold, "rate limit");
    Intent still = step(lvn2, st2, 5, 200_003L, 1001);
    checkEq("B2: a blocked 'holding' does NOT drop the position", still.targetPosition(), -1);
  }

  // ---- A2 / A3: clocks -----------------------------------------------------------------------------------------

  /** The committed 5-minute recording: ticks ~2.27 s ahead of their receipt, DOM/clock events on the local clock. */
  private static void testRiskClockIsMonotonicOnTheRealRecording() throws Exception {
    Path raw = Path.of("flow-core/fixtures/recording_gc_20260924_5min/raw.jsonl");
    if (!Files.exists(raw)) {
      checkEq("A2: fixture present (run from the repo root)", Files.exists(raw), true);
      return;
    }
    MutableMarketState st = new MutableMarketState(Map.of());
    long prevRisk = Long.MIN_VALUE, prevExch = Long.MIN_VALUE;
    int riskBack = 0, exchBack = 0, events = 0;
    for (String line : Files.readAllLines(raw)) {
      if (line.isBlank()) continue;
      Event e = RawEventCodec.decode(line);
      if (e == null) continue;
      st.bump(e);
      events++;
      if (st.riskClockMs() < prevRisk) riskBack++;
      if (st.exchangeTimeMs() < prevExch) exchBack++;
      prevRisk = st.riskClockMs();
      prevExch = st.exchangeTimeMs();
    }
    checkEq("A2: the recording was read (" + events + " events)", events > 4000, true);
    checkEq("A2: the risk clock never moves backwards on the real recording", riskBack, 0);
    checkEq("A2: exchange time no longer jumps back (it did 74 times when DOM events moved it)", exchBack, 0);
  }

  private static void testDomEventsDoNotMoveExchangeTime() {
    MutableMarketState st = new MutableMarketState(Map.of());
    st.bump(new TickEvent(1, 12_270L, 10_000L, 1000, 1, true, 1000, 1000, 0L, 0L)); // exchange 2.27 s ahead of receipt
    st.bump(new DomEvent(2, 10_100L, 10_100L, 999, 1, 1001, 1, List.of(), List.of()));
    checkEq("A2: a DOM event (local clock) leaves exchange time at the last tick's", st.exchangeTimeMs(), 12_270L);
    checkEq("A2: the risk clock is the latest receipt time", st.riskClockMs(), 10_100L);
    st.bump(new ClockEvent(3, 10_050L, 10_050L));
    checkEq("A2: the risk clock never goes back (an older receipt is ignored)", st.riskClockMs(), 10_100L);
    checkEq("A2: clock events still set the local clock", st.localTimeMs(), 10_050L);
    st.bump(new BarEvent(4, 12_300L, 10_200L, BarPhase.CLOSE, 1, 1, 1, 1, 1));
    checkEq("A2: bars still move exchange time", st.exchangeTimeMs(), 12_300L);
  }

  private static void testSessionTrackerIgnoresAnEarlierSession() {
    long day = 86_400_000L;
    // 1970-01-02 23:00 UTC = 17:00 CST on Friday 2 Jan 1970 (session boundary).
    long boundary = day + 23L * 3_600_000L;
    SessionBoundary.Tracker t = new SessionBoundary.Tracker();
    checkEq("A3: baseline", t.advance(boundary - 5_000), false);
    checkEq("A3: crossing 17:00 CT is a rollover", t.advance(boundary + 500), true);
    checkEq("A3: an event from before 17:00 arriving after it is NOT a rollover", t.advance(boundary - 1_500), false);
    checkEq("A3: ...and crossing forward again is not a second rollover", t.advance(boundary + 1_000), false);
    checkEq("A3: the next day's 17:00 is a rollover", t.advance(boundary + day + 1), true);
  }


  /**
   * A2 at the Pipeline level: the entry window and the flatten window are judged on the risk clock (receipt time),
   * not on a tick's exchange timestamp. 1970-01-01 00:00:01 UTC = Wed 18:00 CT (OPEN); 1970-01-03 12:00 UTC =
   * Sat 06:00 CT (weekend FLATTEN).
   */
  private static void testPipelineJudgesWindowsOnTheRiskClock() throws Exception {
    long open = 1_000L, saturday = 2 * 86_400_000L + 12 * 3_600_000L;
    ExternalConfig conf = cfg("{\"maxContracts\":1,\"dailyLossLimitTicks\":100000,\"rateLimitPerMinute\":1000,"
        + "\"minDwellMs\":0,\"maxReversalsPerSession\":1000,\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");

    // Exchange stamp says OPEN, the risk clock says Saturday -> the entry must be blocked by the window.
    Path d1 = Files.createTempDirectory("flow_v2_review_clock1");
    d1.toFile().deleteOnExit();
    JournalWriter j1 = new JournalWriter(d1);
    j1.start();
    List<Integer> sink = new java.util.ArrayList<>();
    Pipeline p1 = new Pipeline(new StubbornLong(), j1, (i, e) -> sink.add(1), Map.of(), t -> t,
        new RiskChain(conf), () -> true, () -> 0, r -> {}, r -> {});
    p1.handle(new TickEvent(1, open, saturday, 1000, 1, true, 1000, 1000, 0L, 0L));
    j1.flushAndClose();
    checkEq("A2: an entry received on Saturday is blocked by the window even if its exchange stamp says OPEN",
        sink.size(), 0);
    checkEq("A2: ...and the block is the session window",
        Files.readAllLines(d1.resolve("decisions.jsonl")).stream()
            .anyMatch(l -> l.contains("\"filter\":\"session_open\",\"allowed\":false")), true);

    // Exchange stamp says Saturday, the risk clock says OPEN -> no flatten.
    Path d2 = Files.createTempDirectory("flow_v2_review_clock2");
    d2.toFile().deleteOnExit();
    JournalWriter j2 = new JournalWriter(d2);
    j2.start();
    AtomicInteger flattens = new AtomicInteger();
    Pipeline p2 = new Pipeline(new StubbornLong(), j2, (i, e) -> {}, Map.of(), t -> t,
        new RiskChain(conf), () -> true, () -> 0, r -> {}, r -> flattens.incrementAndGet());
    p2.handle(new TickEvent(1, saturday, open, 1000, 1, true, 1000, 1000, 0L, 0L));
    j2.flushAndClose();
    checkEq("A2: no session flatten when the risk clock is inside the OPEN window", flattens.get(), 0);
  }


  // ---- E2: data store ----------------------------------------------------------------------------------------

  private static void awaitTrue(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
    long end = System.currentTimeMillis() + ms;
    while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
  }

  private static void testDataStoreClosesOldDaysAndPrunesPastAFailure() throws Exception {
    Path root = Files.createTempDirectory("flow_v2_review_store");
    com.flow.journal.ConstructDataStore store = new com.flow.journal.ConstructDataStore(root);
    store.start();
    for (long sid = 100; sid <= 103; sid++) store.write("vwap", sid, "{\"t\":" + sid + "}");
    awaitTrue(() -> store.openWriterCount() == 4, 2000);
    checkEq("E2 setup: four days' files are open", store.openWriterCount(), 4);

    // A plain file can't be deleted if it is really a non-empty DIRECTORY named like a data file: pruning must
    // skip it and still delete the others.
    // Named "10.jsonl" so it sorts (and is attempted) BEFORE 100/101 -- the case where a failure used to abort the rest.
    Path stuck = root.resolve("vwap").resolve("10.jsonl");
    Files.createDirectories(stuck.resolve("x"));
    store.requestPrune(2, 103);                               // keep the newest 2 days: 102 and 103
    awaitTrue(() -> store.openWriterCount() == 1, 2000);
    checkEq("E2: at the new day, the previous days' writers were closed (only today's stays open)",
        store.openWriterCount(), 1);
    awaitTrue(() -> !Files.exists(root.resolve("vwap").resolve("100.jsonl")), 2000);
    checkEq("E2: day 100 pruned although an earlier entry (10) could not be deleted",
        Files.exists(root.resolve("vwap").resolve("100.jsonl")), false);
    checkEq("E2: day 101 pruned too", Files.exists(root.resolve("vwap").resolve("101.jsonl")), false);
    checkEq("E2: kept days survive", Files.exists(root.resolve("vwap").resolve("102.jsonl"))
        && Files.exists(root.resolve("vwap").resolve("103.jsonl")), true);

    // A late line for a closed day reopens it in append mode (no truncation).
    store.write("vwap", 102, "{\"t\":\"late\"}");
    store.flushAndClose();
    checkEq("E2: a late line for a closed day is appended, not a truncation",
        Files.readAllLines(root.resolve("vwap").resolve("102.jsonl")).size(), 2);
  }


  // ---- E1: DOM backlog bound -----------------------------------------------------------------------------------

  private static void testDomBacklogIsBounded() throws Exception {
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    AtomicInteger doms = new AtomicInteger(), ticks = new AtomicInteger();
    Sequencer seq = new Sequencer(e -> {
      try { release.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
      if (e instanceof DomEvent) doms.incrementAndGet();
      if (e instanceof TickEvent) ticks.incrementAndGet();
    }, (t, e) -> {});
    seq.start();
    int published = 0;
    for (int i = 0; i < 200; i++) {
      if (seq.publishDom((s, et, rt) -> new DomEvent(s, et, rt, 1, 1, 2, 1, List.of(), List.of()), i)) published++;
      seq.publish((s, et, rt) -> new TickEvent(s, et, rt, 1, 1, true, 1, 1, 0L, 0L), i);
    }
    // One DOM may already be in the handler (blocked on the latch), so at most MAX_PENDING_DOM + 1 got through.
    checkEq("E1: order-book snapshots are capped while the drain is stalled",
        published <= Sequencer.MAX_PENDING_DOM + 1, true);
    checkEq("E1: the rest were skipped and counted", seq.skippedDomCount(), (long) (200 - published));
    release.countDown();
    final int pub = published;
    awaitTrue(() -> ticks.get() == 200 && doms.get() == pub, 3000);
    checkEq("E1: every tick is kept (never skipped)", ticks.get(), 200);
    checkEq("E1: every published snapshot is drained", doms.get(), pub);
    checkEq("E1: once drained, a new snapshot is accepted again",
        seq.publishDom((s, et, rt) -> new DomEvent(s, et, rt, 1, 1, 2, 1, List.of(), List.of()), 999), true);
    seq.stop();
  }


  // ---- B3: a runtime refusal ------------------------------------------------------------------------------------

  private static void testRuntimeRefusalIsTreatedLikeABlock() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_review_refusal");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();
    RiskChain rc = new RiskChain(cfg("{\"maxContracts\":1,\"dailyLossLimitTicks\":50,\"rateLimitPerMinute\":1000,"
        + "\"minDwellMs\":0,\"maxReversalsPerSession\":1000,\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}"));
    java.util.concurrent.atomic.AtomicBoolean refuse = new java.util.concurrent.atomic.AtomicBoolean(true);
    AtomicInteger delivered = new AtomicInteger();
    IntentSink sink = new IntentSink() {
      @Override public void onIntentChanged(Intent intent, Event e) {}
      @Override public String deliver(Intent intent, Event e) {
        delivered.incrementAndGet();
        return refuse.get() ? "previous real order still in flight" : null;
      }
    };
    StubbornLong s = new StubbornLong();
    AtomicInteger kills = new AtomicInteger();
    Pipeline p = new Pipeline(s, journal, sink, Map.of(), t -> t, rc, () -> true, () -> 0, r -> kills.incrementAndGet());
    p.handle(tickAt(1, 1_000L, 1000));            // allowed by the chain, refused by the runtime
    checkEq("B3: the strategy was told its entry did not happen", s.rejected.get(), 1);
    p.handle(tickAt(2, 1_500L, 900));             // -100 ticks: a recorded long would breach the -50 limit
    checkEq("B3: the refused entry was NOT recorded as a position (no phantom loss, no kill switch)", kills.get(), 0);
    checkEq("B3: inside 1 s the repeat is not re-delivered", delivered.get(), 1);
    refuse.set(false);
    p.handle(tickAt(3, 2_600L, 1000));            // >= 1 s later: retried, and now acted
    checkEq("B3: the repeat is retried after 1 s and delivered again", delivered.get(), 2);
    p.handle(tickAt(4, 2_700L, 940));             // now it IS a real position: -60 breaches
    checkEq("B3: once the runtime acted, the position is recorded (the kill switch sees the loss)", kills.get(), 1);
    journal.flushAndClose();
  }

}
