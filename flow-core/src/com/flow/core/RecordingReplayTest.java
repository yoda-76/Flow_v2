package com.flow.core;

import com.flow.flow.BigTradeFeature;
import com.flow.flow.LiquidityMapFeature;
import com.flow.flow.VWAPFeature;
import com.flow.journal.ConstructDataStore;
import com.flow.journal.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First real replay test (D-88): replays a real 5-minute live recording
 * (flow-core/fixtures/recording_gc_20260924_5min/) through the same
 * Pipeline and compares what replay produces against what the live run
 * recorded in data/. Covers only what CAN be replayed -- see the fixture's
 * README for what can't (liquidity depth, warm-started market structure,
 * the SDK-backed volume profile itself).
 *
 * The recording predates the price_anchor journal record, so the anchor is
 * derived from the first footprint candle (documented, and the one place a
 * comparison is not independent of the data it checks); every later candle
 * is then an independent check of drain-order determinism.
 */
public final class RecordingReplayTest {
  private static int failures = 0;
  private static final Pattern ROW = Pattern.compile("\\{\"p\":(-?[0-9.]+),\"a\":(\\d+),\"b\":(\\d+)\\}");
  private static final Pattern T = Pattern.compile("\"t\":(\\d+)");

  private static void check(String label, boolean got, boolean want) {
    if (got != want) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args.length > 0 ? args[0] : "flow-core/fixtures/recording_gc_20260924_5min");
    testPriceDecoderFor();
    testReplayMatchesRecordedData(dir);
    testVolumeProfileRebuildsFromFootprintCandles(dir);
    testValueAreaAndZonesMatchLive(dir);

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: recording replay checks passed.");
  }

  private static void testPriceDecoderFor() throws IOException {
    Path d = Files.createTempDirectory("flow_v2_test_anchor");
    d.toFile().deleteOnExit();
    check("no decisions.jsonl -> no decoder", ReplayHarness.priceDecoderFor(d) == null, true);
    Files.writeString(d.resolve("decisions.jsonl"),
        "{\"type\":\"session_header\",\"strategyId\":\"x\",\"tickSize\":0.1}\n");
    check("no price_anchor record (older recording) -> no decoder", ReplayHarness.priceDecoderFor(d) == null, true);
    Files.writeString(d.resolve("decisions.jsonl"),
        "{\"type\":\"session_header\",\"strategyId\":\"x\",\"tickSize\":0.1}\n"
            + "{\"type\":\"price_anchor\",\"price\":4322.900098,\"tickSize\":0.1}\n");
    IntToDoubleFunction dec = ReplayHarness.priceDecoderFor(d);
    check("price_anchor -> decoder reproduces the live anchor + ticks*tickSize exactly",
        dec != null && dec.applyAsDouble(0) == 4322.900098 && dec.applyAsDouble(10) == 4322.900098 + 10 * 0.1, true);
  }

  private static List<String> read(Path f) throws IOException {
    return Files.exists(f) ? Files.readAllLines(f) : List.of();
  }

  /** Replays raw.jsonl with a DataRecorder attached; returns the temp data root it wrote. */
  private static Path replayInto(Path dir, IntToDoubleFunction decoder) throws Exception {
    Path root = Files.createTempDirectory("flow_v2_test_replay_data");
    root.toFile().deleteOnExit();
    ConstructDataStore store = new ConstructDataStore(root);
    store.start();
    Map<String, Feature> features = Map.of(
        "vwap", new VWAPFeature("vwap", decoder == null ? t -> t : decoder),
        "big_trades", new BigTradeFeature("big_trades", 10, 20, null),
        "liquidity_map", new LiquidityMapFeature("liquidity_map"));
    DataRecorder rec = new DataRecorder(store, features, decoder, 1, 1, 7);
    ReplayHarness.replay(dir, "level_zone_observer", features, p -> p.attachDataRecorder(rec));
    store.flushAndClose();
    return root;
  }

  private static double sessionTickSize(Path dir) throws IOException {
    for (String l : Files.readAllLines(dir.resolve("decisions.jsonl"))) {
      JsonObject o = JsonObject.parse(l);
      if ("session_header".equals(o.getString("type"))) return o.getDouble("tickSize");
    }
    throw new IllegalStateException("no session_header");
  }

  private static void testReplayMatchesRecordedData(Path dir) throws Exception {
    Path data = dir.resolve("data");
    long sid = 20718;
    List<String> recFp = read(data.resolve("footprint/" + sid + ".jsonl"));
    List<String> recVwap = read(data.resolve("vwap/" + sid + ".jsonl"));
    List<String> recLiq = read(data.resolve("liquidity_map/" + sid + ".jsonl"));
    check("fixture has recorded footprint, vwap and liquidity data", !recFp.isEmpty() && !recVwap.isEmpty() && !recLiq.isEmpty(), true);

    IntToDoubleFunction decoder = ReplayHarness.priceDecoderFor(dir);
    double tick = sessionTickSize(dir);
    if (decoder == null) {
      // Recording predates price_anchor: replay once in tick units and derive the
      // anchor from the first footprint candle's lowest row.
      Path probe = replayInto(dir, null);
      String replayed = read(probe.resolve("footprint/" + sid + ".jsonl")).get(1);
      String recorded = recFp.get(1);
      int kFirst = (int) Double.parseDouble(firstRowPrice(replayed));
      double pFirst = Double.parseDouble(firstRowPrice(recorded));
      final double anchor = pFirst - kFirst * tick;
      decoder = ticks -> anchor + ticks * tick;
      System.out.println("NOTE: recording predates price_anchor -- anchor derived from the first candle: " + anchor);
    }

    Path root = replayInto(dir, decoder);
    List<String> repFp = read(root.resolve("footprint/" + sid + ".jsonl"));
    check("replay footprint has the same number of lines as the live recording (" + recFp.size() + ")",
        repFp.size() == recFp.size(), true);
    int mismatches = 0;
    String firstBad = null;
    for (int i = 0; i < Math.min(repFp.size(), recFp.size()); i++) {
      if (!repFp.get(i).equals(recFp.get(i))) {
        mismatches++;
        if (firstBad == null) firstBad = "line " + i + "\n  live:   " + recFp.get(i) + "\n  replay: " + repFp.get(i);
      }
    }
    if (firstBad != null) System.out.println(firstBad);
    check("every replayed footprint candle is byte-identical to the live one (drain-order determinism)",
        mismatches == 0, true);

    List<String> repVwap = read(root.resolve("vwap/" + sid + ".jsonl"));
    check("replay VWAP has the same number of lines", repVwap.size() == recVwap.size(), true);
    boolean vwapOk = repVwap.size() == recVwap.size();
    double worst = 0;
    for (int i = 1; vwapOk && i < recVwap.size(); i++) {
      JsonObject a = JsonObject.parse(recVwap.get(i));
      JsonObject b = JsonObject.parse(repVwap.get(i));
      worst = Math.max(worst, Math.abs(a.getDouble("vwap") - b.getDouble("vwap")));
      if (a.getLong("t") != b.getLong("t") || a.getDouble("vol") != b.getDouble("vol")) vwapOk = false;
    }
    check("VWAP: same timestamps and volumes on every line", vwapOk, true);
    // The live decoder's anchor carries SDK float noise (~1e-4) that was never journaled for this
    // recording; the derived anchor is snapped, so VWAP can differ by that much and no more.
    check("VWAP values within 1e-3 of live (worst diff " + worst + ")", worst < 1e-3, true);

    check("no big trades recorded live (quiet window) and none replayed -- capture NOT exercised here",
        read(data.resolve("big_trades/" + sid + ".jsonl")).isEmpty()
            && read(root.resolve("big_trades/" + sid + ".jsonl")).isEmpty(), true);

    List<String> repLiq = read(root.resolve("liquidity_map/" + sid + ".jsonl"));
    boolean depthLost = repLiq.size() > 1 && repLiq.get(1).contains("\"bids\":[]") && repLiq.get(1).contains("\"asks\":[]");
    check("KNOWN GAP confirmed: replayed liquidity snapshots have empty depth (raw journal is top-of-book only), "
        + "so data/liquidity_map/ is the only record of the book", depthLost, true);
  }

  // ---- VAH / VAL / zones (D-104) ------------------------------------------------------------------------------

  private static final Pattern LIVE = Pattern.compile("POC=([0-9.]+) VAH=[0-9.]+ VAL=[0-9.]+ "
      + "\\(ticks: poc=(-?\\d+) vah=(-?\\d+) val=(-?\\d+)\\) totalVolume=([0-9.]+) totalDelta=(-?[0-9.]+) "
      + "bucketCount=(\\d+) zones=\\[(.*)\\]");
  private static final Pattern ZONE = Pattern.compile("(LVN|HVN) \\S+ ([0-9.]+)-([0-9.]+)");

  /** One logged live sample: the volume profile's own POC/VAH/VAL/zones as the running feature computed them. */
  private record LiveVp(double pocPrice, int poc, int vah, int val, long vol, long delta, int buckets,
                        java.util.Set<String> zones) {}

  private static void checkEq(String label, Object got, Object want) {
    boolean ok = java.util.Objects.equals(got, want);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static LiveVp parseLive(String line, double tick) {
    Matcher m = LIVE.matcher(line);
    if (!m.find()) return null;
    double pocPrice = Double.parseDouble(m.group(1));
    int poc = Integer.parseInt(m.group(2));
    java.util.Set<String> zones = new java.util.TreeSet<>();
    Matcher z = ZONE.matcher(m.group(8));
    while (z.find()) {
      // the live log prints zone bounds as prices (float noise ~1e-4); convert to ticks via the POC's own price/tick pair
      int lo = poc + (int) Math.round((Double.parseDouble(z.group(2)) - pocPrice) / tick);
      int hi = poc + (int) Math.round((Double.parseDouble(z.group(3)) - pocPrice) / tick);
      zones.add(z.group(1) + ":" + lo + "-" + hi);
    }
    return new LiveVp(pocPrice, poc, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
        (long) Double.parseDouble(m.group(5)), (long) Double.parseDouble(m.group(6)),
        Integer.parseInt(m.group(7)), zones);
  }

  private static java.util.Set<String> zonesOf(com.flow.flow.VolumeProfileMath.Result r) {
    java.util.Set<String> out = new java.util.TreeSet<>();
    for (int[] c : com.flow.flow.VolumeProfileMath.cluster(r.lvnKeys(), 1)) out.add("LVN:" + c[0] + "-" + c[1]);
    for (int[] c : com.flow.flow.VolumeProfileMath.cluster(r.hvnKeys(), 1)) out.add("HVN:" + c[0] + "-" + c[1]);
    return out;
  }

  private static void compareToLive(String tag, TreeMap<Integer, double[]> hist, LiveVp live) {
    com.flow.flow.VolumeProfileMath.Result r = com.flow.flow.VolumeProfileMath.compute(hist);
    checkEq(tag + ": POC (ticks)", r.poc(), live.poc());
    checkEq(tag + ": VAH (ticks)", r.vah(), live.vah());
    checkEq(tag + ": VAL (ticks)", r.val(), live.val());
    checkEq(tag + ": total delta", (long) r.totalDelta(), live.delta());
    checkEq(tag + ": bucket count", hist.size(), live.buckets());
    checkEq(tag + ": HVN/LVN zones (kind + range, ids ignored)", zonesOf(r), live.zones());
  }

  /**
   * The volume profile's VAH, VAL and HVN/LVN zones, recomputed from the recording by the SAME code the live
   * feature now calls (VolumeProfileMath, extracted verbatim), compared with what the live feature logged. Two
   * independent rebuilds: (1) from the raw ticks in raw.jsonl up to the exact contract count of the live sample --
   * exact for every sample; (2) from the 1-second footprint candles alone (D-88's storage plan) -- exact only for a
   * sample whose volume lands on a candle boundary, so only those are compared.
   */
  private static void testValueAreaAndZonesMatchLive(Path dir) throws Exception {
    double tick = sessionTickSize(dir);
    List<LiveVp> samples = new ArrayList<>();
    for (String s : read(dir.resolve("vp_live_samples.log"))) {
      LiveVp lv = parseLive(s, tick);
      check("parse live VP sample (POC/VAH/VAL/zones): " + (s.length() > 60 ? s.substring(0, 60) : s), lv != null, true);
      if (lv != null) samples.add(lv);
    }
    check("fixture carries live VP samples with VAH/VAL/zones", samples.size() >= 2, true);
    for (LiveVp lv : samples) check("sample has zones to compare (" + lv.vol() + ")", !lv.zones().isEmpty(), true);

    // (1) Raw ticks, replayed in journal order until the sample's cumulative volume is reached exactly.
    List<JsonObject> ticks = new ArrayList<>();
    for (String l : read(dir.resolve("raw.jsonl"))) {
      if (l.contains("\"type\":\"tick\"")) ticks.add(JsonObject.parse(l));
    }
    for (LiveVp lv : samples) {
      TreeMap<Integer, double[]> hist = new TreeMap<>();
      double cum = 0;
      for (JsonObject t : ticks) {
        double[] row = hist.computeIfAbsent(t.getInt("priceTicks"), k -> new double[2]);
        double v = t.getDouble("volume");
        row[t.getBoolean("isAskTick") ? 0 : 1] += v;
        cum += v;
        if (cum >= lv.vol()) break;
      }
      String tag = "raw ticks -> volume " + lv.vol();
      checkEq(tag + ": rebuilt to exactly the sample's traded volume", (long) cum, lv.vol());
      compareToLive(tag, hist, lv);
    }

    // (2) Footprint candles only (rows carry ask/bid volume per price; prices are decimals -> ticks via the POC pair).
    List<String> fp = read(dir.resolve("data/footprint/20718.jsonl"));
    int comparedFromCandles = 0;
    for (LiveVp lv : samples) {
      TreeMap<Integer, double[]> hist = new TreeMap<>();
      long vol = 0;
      boolean landed = false;
      for (String line : fp) {
        if (line.contains("\"type\":\"header\"")) continue;
        Matcher m = ROW.matcher(line);
        while (m.find()) {
          int ticksKey = lv.poc() + (int) Math.round((Double.parseDouble(m.group(1)) - lv.pocPrice()) / tick);
          double[] h = hist.computeIfAbsent(ticksKey, k -> new double[2]);
          h[0] += Long.parseLong(m.group(2));
          h[1] += Long.parseLong(m.group(3));
          vol += Long.parseLong(m.group(2)) + Long.parseLong(m.group(3));
        }
        if (vol == lv.vol()) { landed = true; break; }
        if (vol > lv.vol()) break;
      }
      if (!landed) {
        System.out.println("NOTE: volume " + lv.vol() + " falls inside a footprint candle -- candles alone cannot "
            + "reproduce it exactly (within-candle tick order is lost, D-89); covered by the raw-tick rebuild above");
        continue;
      }
      comparedFromCandles++;
      compareToLive("footprint candles -> volume " + lv.vol(), hist, lv);
    }
    check("at least one sample compared from the footprint candles alone", comparedFromCandles >= 1, true);
  }

  private static String firstRowPrice(String candleLine) {
    Matcher m = ROW.matcher(candleLine);
    if (!m.find()) throw new IllegalStateException("no row in " + candleLine);
    return m.group(1);
  }

  /** Cumulative volume profile as of the end of each footprint candle. */
  private record Prefix(long volume, long delta, int buckets, List<Double> pocPrices) {}

  private static void testVolumeProfileRebuildsFromFootprintCandles(Path dir) throws Exception {
    List<String> fp = read(dir.resolve("data/footprint/20718.jsonl"));
    TreeMap<Double, long[]> hist = new TreeMap<>();
    List<Prefix> prefixes = new ArrayList<>();
    prefixes.add(new Prefix(0, 0, 0, List.of()));
    long vol = 0, delta = 0;
    for (String line : fp) {
      if (line.contains("\"type\":\"header\"")) continue;
      Matcher m = ROW.matcher(line);
      while (m.find()) {
        double p = Double.parseDouble(m.group(1));
        long a = Long.parseLong(m.group(2)), b = Long.parseLong(m.group(3));
        long[] h = hist.computeIfAbsent(p, k -> new long[2]);
        h[0] += a;
        h[1] += b;
        vol += a + b;
        delta += a - b;
      }
      long best = -1;
      List<Double> pocs = new ArrayList<>();
      for (Map.Entry<Double, long[]> en : hist.entrySet()) {
        long v = en.getValue()[0] + en.getValue()[1];
        if (v > best) { best = v; pocs.clear(); pocs.add(en.getKey()); }
        else if (v == best) pocs.add(en.getKey());
      }
      prefixes.add(new Prefix(vol, delta, hist.size(), List.copyOf(pocs)));
    }

    Pattern samplePat = Pattern.compile("POC=([0-9.]+) .*totalVolume=([0-9.]+) totalDelta=(-?[0-9.]+) bucketCount=(\\d+)");
    List<String> samples = read(dir.resolve("vp_live_samples.log"));
    check("fixture carries live VP samples", samples.size() >= 2, true);
    int exact = 0;
    for (String s : samples) {
      Matcher m = samplePat.matcher(s);
      if (!m.find()) { check("parse live VP sample: " + s, false, true); continue; }
      double livePoc = Double.parseDouble(m.group(1));
      long liveVol = (long) Double.parseDouble(m.group(2));
      long liveDelta = (long) Double.parseDouble(m.group(3));
      int liveBuckets = Integer.parseInt(m.group(4));

      int hi = -1;
      for (int i = 0; i < prefixes.size(); i++) if (prefixes.get(i).volume() >= liveVol) { hi = i; break; }
      boolean bracketed = hi >= 0;
      Prefix upper = bracketed ? prefixes.get(hi) : null;
      Prefix lower = bracketed ? prefixes.get(Math.max(0, upper.volume() == liveVol ? hi : hi - 1)) : null;
      String tag = "sample totalVolume=" + liveVol;
      check(tag + ": live totals fall inside one footprint candle's span", bracketed, true);
      if (!bracketed) continue;
      boolean isExact = lower.volume() == liveVol && lower.delta() == liveDelta && lower.buckets() == liveBuckets;
      if (isExact) exact++;
      check(tag + ": delta " + liveDelta + " within the candle's cumulative delta range",
          liveDelta >= Math.min(lower.delta(), upper.delta()) && liveDelta <= Math.max(lower.delta(), upper.delta()), true);
      check(tag + ": bucketCount " + liveBuckets + " within the candle's range",
          liveBuckets >= lower.buckets() && liveBuckets <= upper.buckets(), true);
      boolean pocMatches = false;
      for (Prefix pf : List.of(lower, upper)) {
        for (double p : pf.pocPrices()) if (Math.abs(p - livePoc) < 1e-2) pocMatches = true;
      }
      check(tag + ": live POC " + livePoc + " equals the rebuilt POC (to the tick)", pocMatches, true);
    }
    check("at least one live VP sample is reproduced EXACTLY (volume, delta, bucket count) from candles alone",
        exact >= 1, true);
  }
}
