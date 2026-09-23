package com.flow.core;

import com.flow.flow.BigTradeFeature;
import com.flow.flow.LiquidityMapFeature;
import com.flow.flow.VWAPFeature;
import com.flow.flow.VWAPView;
import com.flow.journal.ConstructDataStore;
import com.flow.journal.JournalWriter;
import com.flow.strategies.NullStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Direct synthetic test for D-88's per-construct data recording:
 * DataRecorder (what gets written, when) and ConstructDataStore (where,
 * and the rolling trading-day prune). Real files in real temp dirs, no
 * mocks -- same discipline as every other test here.
 */
public final class DataRecorderTest {
  private static final ZoneId CT = ZoneId.of("America/Chicago");
  private static int failures = 0;

  private static void check(String label, boolean got, boolean want) {
    if (got != want) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static void checkInt(String label, long got, long want) {
    if (got != want) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static long ct(int y, int mo, int d, int h, int mi, int s) {
    return ZonedDateTime.of(y, mo, d, h, mi, s, 0, CT).toInstant().toEpochMilli();
  }

  private static long seq = 0;

  private static TickEvent tick(long t, int priceTicks, int vol, boolean ask) {
    seq++;
    return new TickEvent(seq, t, t, priceTicks, vol, ask, priceTicks, priceTicks, 0L, 0L);
  }

  private static ClockEvent clock(long t) {
    seq++;
    return new ClockEvent(seq, t, t);
  }

  public static void main(String[] args) throws Exception {
    testFootprintCandles();
    testVwapAndBigTrades();
    testLiquiditySeparateInterval();
    testNullDecoderMarksTicks();
    testSessionRolloverSplitsFiles();
    testPruneKeepsNewestTradingDays();
    testRecorderFailureDoesNotDisarmPipeline();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all DataRecorder synthetic checks passed.");
  }

  private static Path tempRoot() throws IOException {
    Path d = Files.createTempDirectory("flow_v2_test_data");
    d.toFile().deleteOnExit();
    return d;
  }

  private static List<String> lines(Path root, String construct, long sessionId) throws IOException {
    Path f = root.resolve(construct).resolve(sessionId + ".jsonl");
    return Files.exists(f) ? Files.readAllLines(f) : List.of();
  }

  private static DataRecorder recorder(ConstructDataStore store, Map<String, Feature> features,
                                       java.util.function.IntToDoubleFunction dec, int dataSec, int liqSec) {
    return new DataRecorder(store, features, dec, dataSec, liqSec, 7);
  }

  private static void testFootprintCandles() throws Exception {
    Path root = tempRoot();
    ConstructDataStore store = new ConstructDataStore(root);
    store.start();
    DataRecorder rec = recorder(store, Map.of(), t -> t * 0.1, 1, 1);
    long base = ct(2026, 3, 15, 10, 0, 0);
    long sid = SessionBoundary.sessionIdFor(base);

    rec.onEvent(clock(base));
    rec.onEvent(tick(base + 100, 100, 2, true));
    rec.onEvent(tick(base + 200, 101, 1, false));
    rec.onEvent(tick(base + 300, 100, 3, false));
    rec.onEvent(clock(base + 1000)); // flush candle #1
    rec.onEvent(clock(base + 2000)); // empty second -- no line
    rec.onEvent(tick(base + 2100, 105, 4, true));
    rec.onEvent(clock(base + 3000)); // flush candle #2
    store.flushAndClose();

    List<String> ls = lines(root, "footprint", sid);
    checkInt("header + 2 candles (the empty second wrote nothing)", ls.size(), 3);
    check("first line is the header carrying unit=price and intervalSeconds=1",
        ls.get(0).contains("\"type\":\"header\"") && ls.get(0).contains("\"unit\":\"price\"")
            && ls.get(0).contains("\"intervalSeconds\":1"), true);
    String c1 = ls.get(1);
    check("candle 1 t is the closing boundary (interval END)", c1.contains("\"t\":" + (base + 1000)), true);
    check("candle 1 OHLC from drain order: o=10.0 h=10.1 l=10.0 c=10.0",
        c1.contains("\"o\":10.0") && c1.contains("\"h\":10.1") && c1.contains("\"l\":10.0") && c1.contains("\"c\":10.0"), true);
    check("candle 1 volume 6 over 3 ticks", c1.contains("\"v\":6") && c1.contains("\"n\":3"), true);
    check("candle 1 row 10.0 = ask 2 / bid 3", c1.contains("{\"p\":10.0,\"a\":2,\"b\":3}"), true);
    check("candle 1 row 10.1 = ask 0 / bid 1", c1.contains("{\"p\":10.1,\"a\":0,\"b\":1}"), true);
    check("candle 2 is only the later tick (state reset between candles)",
        ls.get(2).contains("\"v\":4") && ls.get(2).contains("{\"p\":10.5,\"a\":4,\"b\":0}"), true);
  }

  private static void testVwapAndBigTrades() throws Exception {
    Path root = tempRoot();
    ConstructDataStore store = new ConstructDataStore(root);
    store.start();
    VWAPFeature vwap = new VWAPFeature("vwap", t -> t * 0.1);
    BigTradeFeature bt = new BigTradeFeature("big_trades", 5, 2000, null);
    Map<String, Feature> features = Map.of("vwap", vwap, "big_trades", bt);
    DataRecorder rec = recorder(store, features, t -> t * 0.1, 1, 1);
    long base = ct(2026, 3, 15, 10, 0, 0);
    long sid = SessionBoundary.sessionIdFor(base);

    java.util.function.Consumer<Event> feed = e -> {
      vwap.onEvent(e);
      bt.onEvent(e);
      rec.onEvent(e);
    };
    feed.accept(clock(base));
    feed.accept(tick(base + 200, 100, 3, true));
    feed.accept(tick(base + 210, 100, 3, true)); // same window: total 6 >= minSize 5 -> big trade, size 6
    feed.accept(clock(base + 1000));
    feed.accept(tick(base + 1220, 100, 2, true)); // within the 2000ms agg period of the last tick -> same window grows to 8
    feed.accept(clock(base + 2000));
    feed.accept(clock(base + 3000)); // nothing changed -> no new big-trade line
    store.flushAndClose();

    List<String> v = lines(root, "vwap", sid);
    check("a VWAP line every flush once ticks exist (header + 3)", v.size() >= 3, true);
    check("first VWAP reading is the tick-weighted 10.0",
        v.get(1).contains("\"vwap\":10.0") && v.get(1).contains("\"vol\":6.0"), true);

    List<String> b = lines(root, "big_trades", sid);
    checkInt("big trades: header + 2 lines (created at 6, then grown to 8; the quiet flush wrote none)", b.size(), 3);
    check("first line carries size 6.0", b.get(1).contains("\"size\":6.0") && b.get(1).contains("\"ask\":true"), true);
    check("second line re-emits the SAME trade at its grown size 8.0 (consumer keeps the max per ts+price+side)",
        b.get(2).contains("\"size\":8.0") && b.get(2).contains("\"ts\":" + (base + 200)), true);
  }

  private static void testLiquiditySeparateInterval() throws Exception {
    Path root = tempRoot();
    ConstructDataStore store = new ConstructDataStore(root);
    store.start();
    LiquidityMapFeature lm = new LiquidityMapFeature("liquidity_map");
    DataRecorder rec = recorder(store, Map.of("liquidity_map", lm), t -> t * 0.1, 1, 2);
    long base = ct(2026, 3, 15, 10, 0, 0);
    long sid = SessionBoundary.sessionIdFor(base);

    java.util.function.Consumer<Event> feed = e -> {
      lm.onEvent(e);
      rec.onEvent(e);
    };
    feed.accept(clock(base));
    feed.accept(tick(base + 100, 100, 1, true));
    seq++;
    feed.accept(new DomEvent(seq, base + 150, base + 150, 99, 5.0, 101, 4.0,
        List.of(new DomRow(99, 5.0), new DomRow(98, 7.0)), List.of(new DomRow(101, 4.0))));
    feed.accept(clock(base + 1000)); // data interval passes; liquidity interval (2s) hasn't
    checkInt("no liquidity line yet at 1s with a 2s liquidity interval",
        lines(root, "liquidity_map", sid).size(), 0);
    feed.accept(clock(base + 2000));
    store.flushAndClose();

    List<String> ls = lines(root, "liquidity_map", sid);
    checkInt("header + 1 snapshot at 2s", ls.size(), 2);
    check("header records the liquidity interval (2) separately from the data interval",
        ls.get(0).contains("\"intervalSeconds\":2"), true);
    check("snapshot carries decoded bid/ask rows",
        ls.get(1).contains("{\"p\":9.9,\"s\":5.0}") && ls.get(1).contains("{\"p\":10.1,\"s\":4.0}"), true);
  }

  private static void testNullDecoderMarksTicks() throws Exception {
    Path root = tempRoot();
    ConstructDataStore store = new ConstructDataStore(root);
    store.start();
    DataRecorder rec = recorder(store, Map.of(), null, 1, 1);
    long base = ct(2026, 3, 15, 10, 0, 0);
    rec.onEvent(clock(base));
    rec.onEvent(tick(base + 100, 100, 2, true));
    rec.onEvent(clock(base + 1000));
    store.flushAndClose();
    List<String> ls = lines(root, "footprint", SessionBoundary.sessionIdFor(base));
    check("no decoder (replay) -> header says unit=ticks", ls.get(0).contains("\"unit\":\"ticks\""), true);
    check("no decoder -> row price is the raw tick offset", ls.get(1).contains("\"p\":100"), true);
  }

  private static void testSessionRolloverSplitsFiles() throws Exception {
    Path root = tempRoot();
    ConstructDataStore store = new ConstructDataStore(root);
    store.start();
    DataRecorder rec = recorder(store, Map.of(), t -> t * 0.1, 1, 1);
    long t0 = ct(2026, 3, 15, 16, 59, 58);
    rec.onEvent(clock(t0));
    rec.onEvent(tick(t0 + 100, 100, 1, true));
    rec.onEvent(clock(ct(2026, 3, 15, 16, 59, 59))); // flushes into the old session
    rec.onEvent(tick(ct(2026, 3, 15, 16, 59, 59) + 100, 101, 1, true));
    rec.onEvent(clock(ct(2026, 3, 15, 17, 0, 1))); // new session
    store.flushAndClose();
    long oldSid = SessionBoundary.sessionIdFor(t0);
    long newSid = SessionBoundary.sessionIdFor(ct(2026, 3, 15, 17, 0, 1));
    check("the two sides of 17:00 CT are different sessions", oldSid != newSid, true);
    checkInt("old session file holds its candle (header + 1)", lines(root, "footprint", oldSid).size(), 2);
    checkInt("new session file holds its own candle (header + 1)", lines(root, "footprint", newSid).size(), 2);
  }

  private static void testPruneKeepsNewestTradingDays() throws Exception {
    Path root = tempRoot();
    Path dir = Files.createDirectories(root.resolve("footprint"));
    Path dir2 = Files.createDirectories(root.resolve("vwap"));
    for (long id : new long[]{10, 11, 14, 15, 16, 17, 18}) { // a weekend-style gap between 11 and 14
      Files.writeString(dir.resolve(id + ".jsonl"), "x");
      Files.writeString(dir2.resolve(id + ".jsonl"), "x");
    }
    Files.writeString(dir.resolve("notes.txt"), "keep me");
    Files.writeString(dir.resolve("abc.jsonl"), "keep me too");

    int deleted = ConstructDataStore.pruneNow(root, 7, 18);
    checkInt("7 distinct days exist, keep 7 -> nothing deleted", deleted, 0);

    deleted = ConstructDataStore.pruneNow(root, 7, 19); // day 19 starts with no file yet -> 8 days -> oldest (10) goes
    checkInt("day 19 counts even with no file yet: oldest day 10 deleted from BOTH constructs (2 files)", deleted, 2);
    check("day 10 gone", !Files.exists(dir.resolve("10.jsonl")) && !Files.exists(dir2.resolve("10.jsonl")), true);
    check("day 11 still kept (the window is trading days present, not calendar days)",
        Files.exists(dir.resolve("11.jsonl")), true);
    check("non-matching file names are never touched",
        Files.exists(dir.resolve("notes.txt")) && Files.exists(dir.resolve("abc.jsonl")), true);
    checkInt("pruning a missing root is a no-op", ConstructDataStore.pruneNow(root.resolve("nope"), 7, 19), 0);
  }

  /** A data-recording bug must disable the recorder, never disarm trading. */
  private static void testRecorderFailureDoesNotDisarmPipeline() throws Exception {
    Path root = tempRoot();
    ConstructDataStore store = new ConstructDataStore(root);
    store.start();
    VWAPView boom = new VWAPView() {
      @Override public String id() { return "vwap"; }
      @Override public void onEvent(Event e) {}
      @Override public boolean isReady() { return true; }
      @Override public String notReadyReason() { return null; }
      @Override public Double vwap() { throw new RuntimeException("synthetic recorder failure"); }
      @Override public double totalVolume() { return 0; }
    };
    Path jdir = Files.createTempDirectory("flow_v2_test_data_journal");
    jdir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(jdir);
    journal.start();
    Map<String, Feature> features = Map.of("vwap", boom);
    Pipeline pipeline = new Pipeline(new NullStrategy(), journal, (i, e) -> {}, features, null);
    pipeline.attachDataRecorder(recorder(store, features, null, 1, 1));

    long base = ct(2026, 3, 15, 10, 0, 0);
    pipeline.handle(clock(base));
    pipeline.handle(tick(base + 100, 100, 1, true));
    pipeline.handle(clock(base + 1000)); // recorder's VWAP read throws here
    pipeline.handle(clock(base + 2000)); // pipeline must keep going

    check("pipeline is still healthy after the recorder threw", pipeline.healthy(), true);
    store.flushAndClose();
    journal.flushAndClose();
    List<String> dec = Files.readAllLines(jdir.resolve("decisions.jsonl"));
    check("a data_recorder_disabled decision was journaled with the reason",
        dec.stream().anyMatch(l -> l.contains("data_recorder_disabled") && l.contains("synthetic recorder failure")), true);
    check("no DISARM record", dec.stream().noneMatch(l -> l.contains("\"type\":\"DISARM\"")), true);
  }
}
