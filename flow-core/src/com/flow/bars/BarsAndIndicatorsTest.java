package com.flow.bars;

import com.flow.bars.ind.AnchoredVwap;
import com.flow.bars.ind.DailyRange;
import com.flow.bars.ind.Ema;
import com.flow.bars.ind.WilderAdx;
import com.flow.bars.ind.WilderAtr;
import com.flow.core.BarEvent;
import com.flow.core.BarPhase;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Bar history, resampler, CT helpers and indicators. Hand-made cases plus golden comparisons against the Python
 * reference (analysis/gen_indicator_fixture.py -> flow-core/fixtures/indicators_nq_2026): ADX(14), anchored VWAPs and
 * the 15-session daily range must match on every one of ~39.5k bars. Exits non-zero on any failure.
 * Usage: BarsAndIndicatorsTest [fixtureDir]   (default flow-core/fixtures/indicators_nq_2026, run from the repo root)
 */
public final class BarsAndIndicatorsTest {
  private static int failures = 0;
  private static int checks = 0;

  private static void check(boolean ok, String what) {
    checks++;
    if (!ok) {
      failures++;
      System.err.println("FAIL: " + what);
    }
  }

  private static long utc(int y, int mo, int d, int h, int mi) {
    return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
  }

  private static BarEvent bar(long endMs, int o, int h, int l, int c, long v) {
    return new BarEvent(1, endMs, endMs, BarPhase.CLOSE, o, h, l, c, v);
  }

  public static void main(String[] args) throws Exception {
    ctTime();
    barHistory();
    resampler();
    smallIndicators();
    golden(args.length > 0 ? Path.of(args[0]) : Path.of("flow-core", "fixtures", "indicators_nq_2026"));
    if (failures > 0) {
      System.err.println("BarsAndIndicatorsTest FAILED: " + failures + " of " + checks + " checks");
      System.exit(1);
    }
    System.out.println("BarsAndIndicatorsTest passed: " + checks + " checks");
  }

  private static void ctTime() {
    // Summer (CDT, UTC-5): 2026-07-01 13:30 UTC = 08:30 CT. Winter (CST, UTC-6): 2026-01-15 14:30 UTC = 08:30 CT.
    check(CtTime.ctMinuteOfDay(utc(2026, 7, 1, 13, 30)) == 510, "CDT 08:30");
    check(CtTime.ctMinuteOfDay(utc(2026, 1, 15, 14, 30)) == 510, "CST 08:30");
    // 2026 DST starts Sunday 03-08 at 02:00 CT (08:00 UTC) and ends Sunday 11-01 at 02:00 CT (07:00 UTC).
    check(CtTime.ctMinuteOfDay(utc(2026, 3, 8, 7, 59)) == 1 * 60 + 59, "03-08 07:59 UTC is 01:59 CST");
    check(CtTime.ctMinuteOfDay(utc(2026, 3, 8, 8, 0)) == 3 * 60, "03-08 08:00 UTC is 03:00 CDT");
    check(CtTime.ctMinuteOfDay(utc(2026, 11, 1, 6, 59)) == 1 * 60 + 59, "11-01 06:59 UTC is 01:59 CDT");
    check(CtTime.ctMinuteOfDay(utc(2026, 11, 1, 7, 59)) == 1 * 60 + 59, "11-01 07:59 UTC is 01:59 CST (second pass)");
    check(CtTime.ctDate(utc(2026, 7, 1, 4, 59)).equals(LocalDate.of(2026, 6, 30)), "04:59 UTC is still the previous CT date");
    check(CtTime.ctDate(utc(2026, 7, 1, 5, 0)).equals(LocalDate.of(2026, 7, 1)), "05:00 UTC is midnight CT (CDT)");
    check(CtTime.ctDate(utc(2026, 1, 15, 5, 59)).equals(LocalDate.of(2026, 1, 14)), "05:59 UTC is previous CT date (CST)");
    // barStartMs: exclusive end and last-millisecond end give the same start.
    check(CtTime.barStartMs(120_000, 60_000) == 60_000, "barStart exclusive end");
    check(CtTime.barStartMs(119_999, 60_000) == 60_000, "barStart last-ms end");
    check(CtTime.barStartMs(60_001, 60_000) == 60_000, "barStart one past start");
    check(CtTime.barStartMs(60_000, 60_000) == 0, "barStart end==boundary");
  }

  private static void barHistory() {
    BarHistoryFeature h = new BarHistoryFeature(3, 60_000);
    check(!h.isReady() && h.notReadyReason() != null, "empty history not ready");
    h.onEvent(new BarEvent(1, 60_000, 60_000, BarPhase.UPDATE, 1, 1, 1, 1, 1));
    check(h.size() == 0, "non-CLOSE phases ignored");
    h.onEvent(bar(60_000, 10, 12, 9, 11, 5));    // start 0
    h.onEvent(bar(120_000, 11, 13, 10, 12, 6));  // start 60000
    check(h.isReady() && h.size() == 2 && h.appended() == 2, "two bars appended");
    h.onEvent(bar(120_000, 99, 99, 99, 99, 99)); // duplicate
    h.onEvent(bar(60_000, 98, 98, 98, 98, 98));  // out of order
    check(h.size() == 2 && h.appended() == 2 && h.dropped() == 2, "duplicate + out-of-order dropped and counted");
    check(h.open(0) == 10 && h.close(1) == 12 && h.startMs(1) == 60_000 && h.volume(0) == 5, "accessors, oldest first");
    h.seed(bar(180_000, 12, 14, 11, 13, 7));
    h.onEvent(bar(240_000, 13, 15, 12, 14, 8));  // wraps: oldest (start 0) falls out
    check(h.size() == 3 && h.appended() == 4, "ring full, appended keeps counting");
    check(h.startMs(0) == 60_000 && h.startMs(2) == 180_000 && h.close(2) == 14 && h.high(0) == 13 && h.low(1) == 11,
        "ring wrap keeps the newest 3 in order");
    boolean threw = false;
    try { h.open(3); } catch (IndexOutOfBoundsException e) { threw = true; }
    check(threw, "index past size throws");
    check(h.barMs() == 60_000 && h.id().equals(BarHistoryView.FEATURE_ID), "barMs / id");
  }

  private static void resampler() {
    // 2026-07-01 CDT: CT 08:30 = 13:30 UTC. 5-minute buckets.
    long t0 = utc(2026, 7, 1, 13, 30);   // CT minute 510 = bucket 102, first minute
    Resampler r = new Resampler(5);
    Resampler.Bar b = null;
    for (int k = 0; k < 4; k++) {
      b = r.onMinuteBar(t0 + k * 60_000L, 100 + k, 110 + k, 90 - k, 105 + k, 10);
      check(b == null, "bucket not complete before its last minute (k=" + k + ")");
    }
    b = r.onMinuteBar(t0 + 4 * 60_000L, 104, 111, 95, 106, 10);   // minute 514: last of the bucket
    check(b != null && b.open() == 100 && b.close() == 106 && b.high() == 113 && b.low() == 87
        && b.volume() == 50 && b.minuteBars() == 5 && b.bucketStartMinute() == 510
        && b.ctDate().equals(LocalDate.of(2026, 7, 1)) && b.startMs() == t0, "complete on last minute: " + b);

    // Missing last minute: bucket 515..519 gets minutes 515,516 only, then a minute of the next bucket arrives.
    long t1 = t0 + 5 * 60_000L;
    check(r.onMinuteBar(t1, 1, 5, 0, 2, 1) == null, "first minute");
    check(r.onMinuteBar(t1 + 60_000L, 2, 9, 1, 3, 2) == null, "second minute");
    b = r.onMinuteBar(t1 + 5 * 60_000L, 50, 60, 40, 55, 7);       // 520 -> next bucket
    check(b != null && b.bucketStartMinute() == 515 && b.minuteBars() == 2 && b.high() == 9 && b.close() == 3
        && b.volume() == 3, "previous bucket returned without the new bar: " + b);
    // The new bar started bucket 520..524; finish it.
    Resampler.Bar done = null;
    for (int k = 1; k < 5; k++) done = r.onMinuteBar(t1 + (5 + k) * 60_000L, 55, 56, 54, 55, 1);
    check(done != null && done.bucketStartMinute() == 520 && done.minuteBars() == 5 && done.open() == 50 && done.high() == 60,
        "next bucket includes the bar that triggered rule (b): " + done);

    // Date change (same bucket number on a new CT date must not merge): 14:55 CT bucket then next day's 14:55.
    Resampler r2 = new Resampler(30);
    long d1 = utc(2026, 7, 1, 19, 59);   // CT 14:59 on 07-01 -> last minute of bucket 870 (minute 899)
    b = r2.onMinuteBar(d1, 1, 2, 1, 2, 1);
    check(b != null && b.minuteBars() == 1 && b.bucketStartMinute() == 870, "30m bucket completes on minute 899");
    // 1-minute resampler is the identity.
    Resampler r1 = new Resampler(1);
    b = r1.onMinuteBar(t0, 1, 2, 0, 1, 3);
    check(b != null && b.minuteBars() == 1 && b.volume() == 3, "N=1 returns every bar");
    // Both rules on one input: a bucket whose first seen minute is its last one while an older bucket is still open.
    Resampler r3 = new Resampler(5);
    r3.onMinuteBar(t0, 1, 2, 0, 1, 1);                            // bucket 510 open
    b = r3.onMinuteBar(t0 + 9 * 60_000L, 7, 8, 6, 7, 1);          // minute 519: last of bucket 515, earlier ones missing
    check(b != null && b.bucketStartMinute() == 510, "older bucket returned first");
    b = r3.onMinuteBar(t0 + 10 * 60_000L, 8, 9, 7, 8, 1);         // 520: pushes bucket 515 out
    check(b != null && b.bucketStartMinute() == 515 && b.close() == 7, "late-completing bucket is not lost");
  }

  private static void smallIndicators() {
    Ema e1 = new Ema(3, Ema.SeedMode.FIRST_VALUE);
    e1.update(10);
    check(e1.value() == 10.0, "ema first value");
    e1.update(20);
    check(Math.abs(e1.value() - 15.0) < 1e-12, "ema alpha 0.5");
    Ema e2 = new Ema(3, Ema.SeedMode.SMA_N);
    e2.update(1); e2.update(2);
    check(e2.value() == null, "ema SMA seed not ready");
    e2.update(3);
    check(Math.abs(e2.value() - 2.0) < 1e-12, "ema SMA seed value");
    e2.update(4);
    check(Math.abs(e2.value() - 3.0) < 1e-12, "ema after seed");

    WilderAtr a = new WilderAtr(2);
    a.update(10, 8, 9);          // TR 2
    check(a.value() == null, "atr not ready");
    a.update(12, 9, 11);         // TR max(3, 3, 0) = 3 -> ATR 2.5
    check(Math.abs(a.value() - 2.5) < 1e-12, "atr first value is mean");
    a.update(11, 10, 10.5);      // TR max(1, 0, 1) = 1 -> (2.5 + 1) / 2
    check(Math.abs(a.value() - 1.75) < 1e-12, "atr wilder smoothing");

    // DailyRange by hand: three sessions on consecutive CDT days, len 2, TR mode.
    // CT 10:00 = 15:00 UTC. Session bars at 10:00, an evening bar at 17:00 CT (22:00 UTC) which is NOT in the session.
    DailyRange dr = new DailyRange(0, 960, 2, DailyRange.TrMode.TR);
    dr.update(utc(2026, 7, 1, 15, 0), 110, 100, 105);     // D1: H110 L100 C105  TR=10 (first: H-L)
    dr.update(utc(2026, 7, 1, 22, 0), 500, 1, 3);         // evening: ignored
    dr.update(utc(2026, 7, 2, 15, 0), 120, 108, 118);     // D2: H120 L108 C118 -> TR = max(120,105)-min(108,105)=15
    check(dr.smaVol() == null && dr.hasFinishedSession() && dr.lastSessionClose() == 105, "after D1 finished");
    dr.update(utc(2026, 7, 3, 15, 0), 130, 119, 125);     // D3 starts: D2 finished -> mean(10,15)
    check(dr.smaVol() != null && Math.abs(dr.smaVol() - 12.5) < 1e-12, "sma of 2 sessions, today excluded");
    check(dr.lastSessionHigh() == 120 && dr.lastSessionLow() == 108 && dr.lastSessionClose() == 118, "last session H/L/C");
    DailyRange hl = new DailyRange(0, 960, 1, DailyRange.TrMode.HL);
    hl.update(utc(2026, 7, 1, 15, 0), 110, 100, 105);
    hl.update(utc(2026, 7, 2, 15, 0), 120, 108, 118);
    check(hl.smaVol() == 10.0, "HL mode ignores prev close");

    // AnchoredVwap by hand: anchor 08:30 CT; bars before it give null.
    AnchoredVwap av = new AnchoredVwap(510, true);
    av.update(utc(2026, 7, 1, 13, 29), 5, 5, 5, 100);
    check(av.value() == null, "before anchor");
    av.update(utc(2026, 7, 1, 13, 30), 10, 10, 10, 1);
    av.update(utc(2026, 7, 1, 13, 31), 20, 20, 20, 3);
    check(Math.abs(av.value() - 17.5) < 1e-12, "close-basis vwap");
    av.update(utc(2026, 7, 2, 13, 29), 5, 5, 5, 100);
    check(av.value() == null, "new date resets");
    // negative anchor -420 = 17:00 CT the day before (22:00 UTC CDT).
    AnchoredVwap neg = new AnchoredVwap(-420, true);
    neg.update(utc(2026, 7, 1, 21, 59), 99, 99, 99, 50);  // 16:59 CT: before the evening anchor, not seeded
    neg.update(utc(2026, 7, 1, 22, 0), 10, 10, 10, 1);    // 17:00 CT: seeds next date
    neg.update(utc(2026, 7, 2, 5, 0), 20, 20, 20, 1);     // 00:00 CT next date
    check(Math.abs(neg.value() - 15.0) < 1e-12, "negative anchor seeded from previous evening");
    neg.update(utc(2026, 7, 4, 5, 0), 40, 40, 40, 1);     // two days later: not the next calendar date, no seed
    check(Math.abs(neg.value() - 40.0) < 1e-12, "no seed across a gap");
    AnchoredVwap zero = new AnchoredVwap(0);
    zero.update(utc(2026, 7, 1, 5, 0), 1, 1, 1, 0);
    check(zero.value() == null, "zero volume -> null");
  }

  private static void golden(Path dir) throws Exception {
    final double tick = 0.25;
    BarHistoryFeature hist = new BarHistoryFeature(40_000, 60_000);
    WilderAdx adx = new WilderAdx(14);
    AnchoredVwap v0830 = new AnchoredVwap(510);
    AnchoredVwap v0000 = new AnchoredVwap(0);
    AnchoredVwap vPrev = new AnchoredVwap(-420);
    DailyRange dr = new DailyRange(0, 960, 15, DailyRange.TrMode.TR);

    int rows = 0;
    int nAdx = 0, nV1 = 0, nV2 = 0, nV3 = 0, nVol = 0;
    double anchor = Double.NaN;
    try (BufferedReader bars = Files.newBufferedReader(dir.resolve("bars.csv"));
         BufferedReader exp = Files.newBufferedReader(dir.resolve("expected.csv"))) {
      bars.readLine();
      exp.readLine();
      String bl;
      while ((bl = bars.readLine()) != null) {
        String el = exp.readLine();
        if (el == null) { check(false, "expected.csv shorter than bars.csv"); break; }
        String[] b = bl.split(",");
        String[] x = el.split(",", -1);
        long ts = Long.parseLong(b[0]);
        check(ts == Long.parseLong(x[0]), "row " + rows + " timestamps line up");
        double o = Double.parseDouble(b[1]), h = Double.parseDouble(b[2]),
            l = Double.parseDouble(b[3]), c = Double.parseDouble(b[4]);
        long vol = (long) Double.parseDouble(b[5]);
        if (Double.isNaN(anchor)) anchor = o;
        int ot = ticks(o, anchor, tick), ht = ticks(h, anchor, tick), lt = ticks(l, anchor, tick), ct = ticks(c, anchor, tick);
        hist.onEvent(bar(ts + 60_000L, ot, ht, lt, ct, vol));
        adx.update(ht, lt, ct);
        v0830.update(ts, ht, lt, ct, vol);
        v0000.update(ts, ht, lt, ct, vol);
        vPrev.update(ts, ht, lt, ct, vol);
        dr.update(ts, ht, lt, ct);

        nAdx += cmp("adx", rows, adx.value(), x[1], 1.0, 0.0, 1e-6);
        nV1 += cmp("vwap0830", rows, v0830.value(), x[2], tick, anchor, 1e-6);
        nV2 += cmp("vwap0000", rows, v0000.value(), x[3], tick, anchor, 1e-6);
        nV3 += cmp("vwapPrev1700", rows, vPrev.value(), x[4], tick, anchor, 1e-6);
        nVol += cmp("dailyVol", rows, dr.smaVol(), x[5], tick, 0.0, 1e-6);
        rows++;
        if (failures > 20) break;
      }
      check(exp.readLine() == null || failures > 0, "expected.csv longer than bars.csv");
    }
    check(rows > 30_000, "fixture has the expected size, rows=" + rows);
    check(nAdx > 30_000 && nV1 > 10_000 && nV2 > 30_000 && nV3 > 30_000 && nVol > 10_000,
        "golden columns actually exercised (adx=" + nAdx + " v0830=" + nV1 + " v0000=" + nV2 + " vPrev=" + nV3 + " vol=" + nVol + ")");
    check(hist.appended() == rows && hist.dropped() == 0, "history took every fixture bar");
    check(hist.size() == rows, "history size (capacity 40000 >= rows)");
    System.out.println("golden: " + rows + " bars; compared adx=" + nAdx + " vwap0830=" + nV1 + " vwap0000=" + nV2
        + " vwapPrev1700=" + nV3 + " dailyVol=" + nVol);
  }

  private static int ticks(double price, double anchor, double tick) {
    return (int) Math.round((price - anchor) / tick);
  }

  /**
   * Compares one indicator cell. actual is in ticks relative to the anchor; scale/offset convert it back to the
   * Python unit (points = ticks*scale + offset). Returns 1 if a value was compared, 0 if both were empty.
   */
  private static int cmp(String name, int row, Double actual, String expectedCell, double scale, double offset, double tol) {
    boolean expEmpty = expectedCell.isEmpty();
    if (expEmpty != (actual == null)) {
      check(false, name + " row " + row + ": availability differs (actual=" + actual + ", expected='" + expectedCell + "')");
      return 0;
    }
    if (expEmpty) {
      checks++;
      return 0;
    }
    double a = actual * scale + offset;
    double e = Double.parseDouble(expectedCell);
    check(Math.abs(a - e) <= tol, name + " row " + row + ": " + a + " vs expected " + e);
    return 1;
  }
}
