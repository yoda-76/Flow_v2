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

/**
 * F-19 (2026-09-28, the 21:53 IST Rithmic disconnect): the feed watchdog in Pipeline + the risk chain's "feed" filter.
 * No tick for feedStaleSeconds inside the trading window -> FEED_STALE, entries blocked; the first tick after it ->
 * a data_gap record (with the price jump) and a call to the runtime's listener; feedSettleSeconds of continuous
 * data later -> feed_live, entries allowed again. Silent during the halt/weekend, off when configured 0, and a relapse
 * while settling goes straight back to STALE. Events are fed into Pipeline.handle() on this thread (deterministic).
 */
public final class FeedWatchdogTest {
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

  private static ExternalConfig config(String feedKeys) throws Exception {
    Path f = Files.createTempFile("flow_v2_test_feed", ".json");
    f.toFile().deleteOnExit();
    Files.writeString(f, "{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":100000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000" + feedKeys + "}");
    return ExternalConfig.load(f);
  }

  private static final class StubStrategy implements FlowStrategy {
    @Override public String id() { return "stub"; }
    @Override public Set<String> requires() { return Set.of(); }
    @Override public Set<Trigger> triggers() { return Set.of(new Trigger.EveryTick()); }
    @Override public void onInit(StrategyConfig cfg) {}
    @Override public Intent onEvent(MarketState state) { return Intent.none(id(), 1); }
  }

  private static final class Rig {
    final Path dir;
    final JournalWriter journal;
    final RiskChain rc;
    final Pipeline p;
    final List<Pipeline.FeedGap> gaps = new ArrayList<>();
    long seq = 0;

    Rig(String feedKeys) throws Exception {
      dir = Files.createTempDirectory("flow_v2_test_feed");
      dir.toFile().deleteOnExit();
      journal = new JournalWriter(dir);
      journal.start();
      rc = new RiskChain(config(feedKeys));
      p = new Pipeline(new StubStrategy(), journal, (i, e) -> {}, Map.of(), null, rc, () -> true, () -> 0, r -> {}, r -> {});
      p.attachFeedListener(gaps::add);
    }

    void clock(long t) { p.handle(new ClockEvent(++seq, t, t)); }

    void tick(long t, int px) { p.handle(new TickEvent(++seq, t, t, px, 1, true, px, px, 0L, 0L)); }

    /** One clock event per second from a (exclusive) to b (inclusive). */
    void clocks(long a, long b) { for (long t = a + 1000; t <= b; t += 1000) clock(t); }

    RiskChain.Result tryEnter(long now) {
      return rc.evaluate(new Intent("s", 1, 1, null, null, "enter"), new RiskChain.Context(true, true, 1000, now, 0, 0L));
    }

    String filterThatBlocked(RiskChain.Result r) {
      for (RiskChain.Verdict v : r.verdicts()) if (!v.allowed()) return v.filter() + ": " + v.reason();
      return null;
    }

    List<String> lines() throws Exception {
      journal.flushAndClose();
      return Files.readAllLines(dir.resolve("decisions.jsonl"));
    }

    static int count(List<String> lines, String needle) {
      int n = 0;
      for (String l : lines) if (l.contains(needle)) n++;
      return n;
    }
  }

  public static void main(String[] args) throws Exception {
    testStaleGapSettleLive();
    testWatchdogOffAndOutsideTheWindow();
    testRelapseWhileSettling();
    testHeartbeatCarriesTheFeedState();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all feed-watchdog synthetic checks passed.");
  }

  private static void testStaleGapSettleLive() throws Exception {
    Rig r = new Rig(",\"feedStaleSeconds\":5,\"feedSettleSeconds\":3");
    long t0 = ct(23, 10, 0, 0);
    r.clock(t0);
    r.tick(t0, 1000);
    r.clocks(t0, t0 + 5_000);
    check("5 s of silence is not yet stale (limit is 'more than 5 s')", r.tryEnter(t0 + 5_000).allowed());
    r.clocks(t0 + 5_000, t0 + 6_000);
    RiskChain.Result stale = r.tryEnter(t0 + 6_000);
    check("6 s of silence: an entry is blocked by the feed filter", !stale.allowed()
        && r.filterThatBlocked(stale).startsWith("feed: no market data for 6s"));
    check("...but going flat is never blocked", r.rc.evaluate(new Intent("s", 2, 0, null, null, "exit"),
        new RiskChain.Context(true, true, 1000, t0 + 6_000, 0, 0L)).allowed());
    r.clocks(t0 + 6_000, t0 + 20_000);
    checkEq("no listener call while it is still down", r.gaps.size(), 0);

    r.clock(t0 + 20_000);
    r.tick(t0 + 20_000, 1010); // the feed is back, 10 ticks higher
    checkEq("the first tick after the gap calls the listener once", r.gaps.size(), 1);
    Pipeline.FeedGap g = r.gaps.get(0);
    check("...with the gap's start (the last tick), end, and both prices",
        g.startLocalMs() == t0 && g.endLocalMs() == t0 + 20_000 && g.priceBeforeTicks() == 1000 && g.priceAfterTicks() == 1010);
    RiskChain.Result settling = r.tryEnter(t0 + 20_100);
    check("right after the resume entries are still blocked (settling)", !settling.allowed()
        && r.filterThatBlocked(settling).contains("settling"));
    r.tick(t0 + 21_000, 1011);
    r.clocks(t0 + 20_000, t0 + 22_000);
    check("2 s into the 3 s settle: still blocked", !r.tryEnter(t0 + 22_000).allowed());
    r.clock(t0 + 23_000);
    check("3 s of continuous data: entries allowed again", r.tryEnter(t0 + 23_000).allowed());

    List<String> lines = r.lines();
    checkEq("FEED_STALE journaled once (not every clock event)", Rig.count(lines, "\"type\":\"FEED_STALE\""), 1);
    checkEq("data_gap journaled once", Rig.count(lines, "\"type\":\"data_gap\""), 1);
    check("...with the duration and the jump", lines.stream().anyMatch(l -> l.contains("\"type\":\"data_gap\"")
        && l.contains("\"durationMs\":20000") && l.contains("\"jumpTicks\":10")));
    checkEq("feed_live journaled once", Rig.count(lines, "\"type\":\"feed_live\""), 1);
  }

  private static void testWatchdogOffAndOutsideTheWindow() throws Exception {
    Rig off = new Rig(",\"feedStaleSeconds\":0");
    long t0 = ct(23, 10, 0, 0);
    off.clock(t0);
    off.tick(t0, 1000);
    off.clocks(t0, t0 + 600_000);
    check("feedStaleSeconds=0 switches the watchdog off: 10 minutes of silence, entries still allowed", off.tryEnter(t0 + 600_000).allowed());
    checkEq("...and nothing journaled", Rig.count(off.lines(), "FEED_STALE"), 0);

    Rig sat = new Rig(",\"feedStaleSeconds\":5");
    long s0 = ct(26, 12, 0, 0); // Saturday: the market is shut, silence is normal
    sat.clock(s0);
    sat.tick(s0, 1000);
    sat.clocks(s0, s0 + 60_000);
    checkEq("Saturday silence raises no alert", Rig.count(sat.lines(), "FEED_STALE"), 0);

    Rig fresh = new Rig(",\"feedStaleSeconds\":5");
    fresh.clocks(t0, t0 + 60_000);
    checkEq("no tick has EVER arrived (a study just added on a quiet chart): no alert", Rig.count(fresh.lines(), "FEED_STALE"), 0);
  }

  private static void testRelapseWhileSettling() throws Exception {
    Rig r = new Rig(",\"feedStaleSeconds\":5,\"feedSettleSeconds\":30");
    long t0 = ct(23, 10, 0, 0);
    r.clock(t0);
    r.tick(t0, 1000);
    r.clocks(t0, t0 + 10_000);            // stale
    r.clock(t0 + 11_000);
    r.tick(t0 + 11_000, 1005);            // resumed -> settling (30 s)
    checkEq("one gap so far", r.gaps.size(), 1);
    r.clocks(t0 + 11_000, t0 + 18_000);   // 7 s of silence again while settling
    RiskChain.Result again = r.tryEnter(t0 + 18_000);
    check("silent again during the settle: blocked as STALE once more", !again.allowed()
        && r.filterThatBlocked(again).startsWith("feed: no market data"));
    r.clock(t0 + 19_000);
    r.tick(t0 + 19_000, 1006);
    checkEq("the next tick is a second resume", r.gaps.size(), 2);
    List<String> lines = r.lines();
    checkEq("two FEED_STALE alerts", Rig.count(lines, "\"type\":\"FEED_STALE\""), 2);
    checkEq("two data_gap records", Rig.count(lines, "\"type\":\"data_gap\""), 2);
  }

  private static void testHeartbeatCarriesTheFeedState() throws Exception {
    Rig r = new Rig(",\"feedStaleSeconds\":5");
    long t0 = ct(23, 10, 0, 0);
    r.clock(t0);
    r.tick(t0, 1000);
    for (int i = 1; i <= 100; i++) {
      r.clock(t0 + i * 100L);
      if (i % 10 == 0) r.tick(t0 + i * 100L, 1000); // a tick a second: the feed is healthy
    }
    check("a heartbeat says the feed is LIVE", r.lines().stream().anyMatch(l -> l.contains("\"type\":\"heartbeat\"") && l.contains("\"feedState\":\"LIVE\"")));
  }
}
