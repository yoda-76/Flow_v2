package com.flow.journal;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The per-feature log rolling and pruning (D-106): RollingFileWriter (one file per trading day) and
 * LogRetention.pruneFeatureLogs / retentionMsForHours. Real temp directories, a controllable clock.
 * Plain main(), nonzero exit on failure, wired into build/build.sh.
 */
public final class RollingLogsTest {
  private static int failures = 0;
  private static final ZoneId CT = ZoneId.of("America/Chicago");
  private static final long HOUR = 3_600_000L;

  private static void checkEq(String label, Object got, Object want) {
    boolean ok = want == null ? got == null : want.equals(got);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static long ct(int y, int mo, int d, int h, int mi, int s) {
    return ZonedDateTime.of(y, mo, d, h, mi, s, 0, CT).toInstant().toEpochMilli();
  }

  private static void touch(Path f, long mtimeMs) throws Exception {
    Files.writeString(f, "x");
    Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(mtimeMs));
  }

  public static void main(String[] args) throws Exception {
    testTradingDay();
    testRolling();
    testFailureNeverBreaksTheCaller();
    testFeatureLogsAppliesRetentionOnRoll();
    testFeatureLogNames();
    testPruneFeatureLogs();
    testRetentionHours();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: rolling/pruned feature log checks passed.");
  }

  private static void testTradingDay() {
    // Trading day D = 17:00 CT on D-1 .. 17:00 CT on D, named by the day it ends in.
    checkEq("16:59:59 CT is still that calendar day's session", RollingFileWriter.tradingDay(ct(2026, 9, 23, 16, 59, 59)), "2026-09-23");
    checkEq("17:00:00 CT starts the NEXT day's session", RollingFileWriter.tradingDay(ct(2026, 9, 23, 17, 0, 0)), "2026-09-24");
    checkEq("midnight CT belongs to the session that ends that day", RollingFileWriter.tradingDay(ct(2026, 9, 24, 0, 0, 0)), "2026-09-24");
    checkEq("Sunday 17:00 CT opens Monday's session", RollingFileWriter.tradingDay(ct(2026, 9, 27, 17, 0, 0)), "2026-09-28");
    checkEq("year end: 17:00 CT on 31 Dec is 1 Jan's session", RollingFileWriter.tradingDay(ct(2026, 12, 31, 17, 0, 0)), "2027-01-01");
    // Both sides of a DST change: the boundary is still 17:00 local.
    checkEq("just before 17:00 CST (after the November change)", RollingFileWriter.tradingDay(ct(2026, 11, 2, 16, 59, 59)), "2026-11-02");
    checkEq("17:00 CST (after the November change)", RollingFileWriter.tradingDay(ct(2026, 11, 2, 17, 0, 0)), "2026-11-03");
    checkEq("just before 17:00 CDT (after the March change)", RollingFileWriter.tradingDay(ct(2026, 3, 9, 16, 59, 59)), "2026-03-09");
    checkEq("17:00 CDT (after the March change)", RollingFileWriter.tradingDay(ct(2026, 3, 9, 17, 0, 0)), "2026-03-10");
  }

  private static void testRolling() throws Exception {
    Path dir = Files.createTempDirectory("flow_rolling_test");
    AtomicLong now = new AtomicLong(ct(2026, 9, 23, 10, 0, 0));
    AtomicInteger rolls = new AtomicInteger();
    RollingFileWriter w = new RollingFileWriter(dir, "vwap_feature", now::get, rolls::incrementAndGet);
    PrintWriter pw = new PrintWriter(w);
    checkEq("the first file exists as soon as the writer is created", Files.exists(dir.resolve("vwap_feature_2026-09-23.log")), true);
    checkEq("creating the writer is not a roll", rolls.get(), 0);

    pw.println("line one"); pw.flush();
    now.set(ct(2026, 9, 23, 16, 59, 59));
    pw.println("line two"); pw.flush();
    checkEq("same trading day: same file, no roll", Files.readAllLines(dir.resolve("vwap_feature_2026-09-23.log")), List.of("line one", "line two"));
    checkEq("no roll yet", rolls.get(), 0);

    now.set(ct(2026, 9, 23, 17, 0, 0));
    pw.println("line three"); pw.flush();
    checkEq("17:00 CT: the write lands in the NEW day's file", Files.readAllLines(dir.resolve("vwap_feature_2026-09-24.log")), List.of("line three"));
    checkEq("the old day's file is left as it was", Files.readAllLines(dir.resolve("vwap_feature_2026-09-23.log")), List.of("line one", "line two"));
    checkEq("one roll was reported", rolls.get(), 1);

    now.set(ct(2026, 9, 23, 20, 0, 0));
    pw.println("line four"); pw.flush();
    checkEq("more writes on the new day append to it", Files.readAllLines(dir.resolve("vwap_feature_2026-09-24.log")), List.of("line three", "line four"));
    checkEq("still one roll", rolls.get(), 1);

    // A long quiet weekend: the next write after several days simply opens that day's file.
    now.set(ct(2026, 9, 28, 9, 0, 0));
    pw.println("monday"); pw.flush();
    checkEq("a write days later opens that day's file", Files.readAllLines(dir.resolve("vwap_feature_2026-09-28.log")), List.of("monday"));
    checkEq("skipped days produce no empty files", Files.exists(dir.resolve("vwap_feature_2026-09-26.log")), false);
    checkEq("two rolls in total", rolls.get(), 2);

    // Re-opening the same day appends (a study re-activated mid-day must not truncate).
    pw.close();
    RollingFileWriter again = new RollingFileWriter(dir, "vwap_feature", now::get, null);
    PrintWriter pw2 = new PrintWriter(again);
    pw2.println("after re-activation"); pw2.close();
    checkEq("re-opening the same day appends, never truncates",
        Files.readAllLines(dir.resolve("vwap_feature_2026-09-28.log")), List.of("monday", "after re-activation"));

    // Two features side by side do not share files.
    RollingFileWriter other = new RollingFileWriter(dir, "liquidity_map_feature", now::get, null);
    new PrintWriter(other, true).println("book");
    other.close();
    checkEq("each feature has its own file", Files.exists(dir.resolve("liquidity_map_feature_2026-09-28.log")), true);

    // afterRoll blowing up must not reach the writer's caller.
    AtomicLong t2 = new AtomicLong(ct(2026, 9, 23, 10, 0, 0));
    RollingFileWriter boom = new RollingFileWriter(dir, "boom_feature", t2::get, () -> { throw new IllegalStateException("x"); });
    PrintWriter pb = new PrintWriter(boom);
    t2.set(ct(2026, 9, 23, 18, 0, 0));
    pb.println("survives"); pb.flush();
    checkEq("an exception in afterRoll does not break logging", Files.readAllLines(dir.resolve("boom_feature_2026-09-24.log")), List.of("survives"));
    pb.close();
    deleteAll(dir);
  }

  private static void testFailureNeverBreaksTheCaller() throws Exception {
    Path dir = Files.createTempDirectory("flow_rolling_fail_test");
    AtomicLong now = new AtomicLong(ct(2026, 9, 23, 10, 0, 0));
    AtomicInteger rolls = new AtomicInteger();
    RollingFileWriter w = new RollingFileWriter(dir, "big_trade_feature", now::get, rolls::incrementAndGet);
    PrintWriter pw = new PrintWriter(w);
    pw.println("day one"); pw.flush();

    // Make the next day's file impossible to open: a DIRECTORY with that name.
    Files.createDirectory(dir.resolve("big_trade_feature_2026-09-24.log"));
    now.set(ct(2026, 9, 23, 17, 30, 0));
    pw.println("dropped"); pw.println("also dropped"); pw.flush();
    checkEq("no roll is reported when the new file could not be opened", rolls.get(), 0);
    checkEq("the old day's file did not receive the dropped lines",
        Files.readAllLines(dir.resolve("big_trade_feature_2026-09-23.log")), List.of("day one"));

    // Documented behaviour: a day whose file could not be opened is NOT retried until the day changes (no retry
    // storm on the event thread). Removing the blocker now must not make the writer start writing mid-day.
    Files.delete(dir.resolve("big_trade_feature_2026-09-24.log"));
    pw.println("same day, blocker removed"); pw.flush();
    checkEq("no retry within the failed day (the file still does not exist)",
        Files.exists(dir.resolve("big_trade_feature_2026-09-24.log")), false);

    // The day after, it recovers by itself.
    now.set(ct(2026, 9, 24, 17, 30, 0));
    pw.println("recovered"); pw.flush();
    checkEq("the next day opens normally", Files.readAllLines(dir.resolve("big_trade_feature_2026-09-25.log")), List.of("recovered"));
    checkEq("and that roll is reported", rolls.get(), 1);
    pw.close();
    deleteAll(dir);
  }

  private static void testFeatureLogsAppliesRetentionOnRoll() throws Exception {
    Path dir = Files.createTempDirectory("flow_featurelogs_test");
    AtomicLong now = new AtomicLong(ct(2026, 9, 28, 10, 0, 0));
    long t0 = now.get();
    try {
      // Negative retention is clamped to "keep everything".
      com.flow.core.FeatureLogs.setRetentionMs(-5);
      checkEq("a negative retention is clamped to 0 (keep)", com.flow.core.FeatureLogs.retentionMs(), 0L);

      // Retention 0: rolling to a new day deletes nothing, however old.
      touch(dir.resolve("vwap_feature_2026-09-20.log"), t0 - 200 * HOUR);
      com.flow.core.FeatureLogs.setRetentionMs(0);
      PrintWriter keep = new PrintWriter(com.flow.core.FeatureLogs.open(dir, "vwap_feature", now::get));
      now.set(ct(2026, 9, 28, 18, 0, 0));
      keep.println("x"); keep.flush(); keep.close();
      checkEq("retention 0: an old feature log survives a roll", Files.exists(dir.resolve("vwap_feature_2026-09-20.log")), true);

      // Retention 48 h: the roll prunes old files (judged against the injected clock), keeps recent ones.
      touch(dir.resolve("liquidity_map_feature_2026-09-20.log"), t0 - 200 * HOUR);
      touch(dir.resolve("liquidity_map_feature_2026-09-28.log"), t0 - 2 * HOUR);
      com.flow.core.FeatureLogs.setRetentionMs(LogRetention.retentionMsForHours(48));
      checkEq("the setting is readable back", com.flow.core.FeatureLogs.retentionMs(), 172_800_000L);
      now.set(ct(2026, 9, 29, 10, 0, 0));
      PrintWriter pw = new PrintWriter(com.flow.core.FeatureLogs.open(dir, "liquidity_map_feature", now::get));
      checkEq("opening does not prune (only a roll does)", Files.exists(dir.resolve("liquidity_map_feature_2026-09-20.log")), true);
      now.set(ct(2026, 9, 29, 17, 30, 0));
      pw.println("y"); pw.flush(); pw.close();
      checkEq("retention 48 h: the roll pruned the 200 h-old file", Files.exists(dir.resolve("liquidity_map_feature_2026-09-20.log")), false);
      checkEq("retention 48 h: it pruned other features' old files too (one directory, one policy)",
          Files.exists(dir.resolve("vwap_feature_2026-09-20.log")), false);
      checkEq("retention 48 h: the file just written stays", Files.exists(dir.resolve("liquidity_map_feature_2026-09-30.log")), true);
    } finally {
      com.flow.core.FeatureLogs.setRetentionMs(0);
      deleteAll(dir);
    }
  }

  private static void testFeatureLogNames() {
    for (String ok : new String[] {"vwap_feature.log", "vwap_feature_2026-09-26.log", "liquidity_map_feature_2026-09-26.log",
        "order_repeat_feature.log", "volume_profile_feature_2027-01-01.log"}) {
      checkEq("feature log name recognised: " + ok, LogRetention.isFeatureLogName(ok), true);
    }
    for (String no : new String[] {"decisions.jsonl", "notes.log", "vwap_feature.log.bak", "vwap_feature_2026-9-26.log",
        "vwap_feature_2026-09-26.log.gz", "raw.jsonl", "vwap.log", "VWAP_feature.log", "x_feature_.log", "_feature.log"}) {
      checkEq("NOT a feature log, must never be pruned: " + no, LogRetention.isFeatureLogName(no), false);
    }
  }

  private static void testPruneFeatureLogs() throws Exception {
    Path dir = Files.createTempDirectory("flow_prune_features_test");
    long now = ct(2026, 9, 28, 12, 0, 0);
    touch(dir.resolve("vwap_feature.log"), now - 100 * HOUR);                 // legacy single file, old
    touch(dir.resolve("vwap_feature_2026-09-24.log"), now - 60 * HOUR);       // old day
    touch(dir.resolve("vwap_feature_2026-09-26.log"), now - 47 * HOUR);       // inside 48 h
    touch(dir.resolve("vwap_feature_2026-09-28.log"), now - 1 * HOUR);        // today's
    touch(dir.resolve("liquidity_map_feature.log"), now - 48 * HOUR);         // exactly on the boundary
    touch(dir.resolve("notes.log"), now - 500 * HOUR);                        // not ours
    touch(dir.resolve("vwap_feature.log.bak"), now - 500 * HOUR);             // not ours
    Path session = dir.resolve("level_zone_observer_1790000000000_inst1");
    Files.createDirectories(session);
    touch(session.resolve("decisions.jsonl"), now - 900 * HOUR);
    touch(session.resolve("raw.jsonl"), now - 900 * HOUR);
    Path lookalike = dir.resolve("old_feature_2026-01-01.log");               // a DIRECTORY that looks like a feature log
    Files.createDirectories(lookalike);

    checkEq("retention 0 (keep everything) deletes nothing",
        LogRetention.pruneFeatureLogs(dir, now, 0L), 0);
    checkEq("negative retention deletes nothing either", LogRetention.pruneFeatureLogs(dir, now, -1L), 0);
    checkEq("everything still there after retention 0", Files.exists(dir.resolve("vwap_feature.log")), true);

    int n = LogRetention.pruneFeatureLogs(dir, now, 48 * HOUR);
    checkEq("48 h: the old single file goes", Files.exists(dir.resolve("vwap_feature.log")), false);
    checkEq("48 h: an old day's file goes", Files.exists(dir.resolve("vwap_feature_2026-09-24.log")), false);
    checkEq("48 h: exactly 48 h old goes (>=)", Files.exists(dir.resolve("liquidity_map_feature.log")), false);
    checkEq("48 h: a file 47 h old stays", Files.exists(dir.resolve("vwap_feature_2026-09-26.log")), true);
    checkEq("48 h: today's file stays", Files.exists(dir.resolve("vwap_feature_2026-09-28.log")), true);
    checkEq("48 h: three files were deleted", n, 3);
    checkEq("unrelated .log file untouched", Files.exists(dir.resolve("notes.log")), true);
    checkEq("a .bak of a feature log untouched", Files.exists(dir.resolve("vwap_feature.log.bak")), true);
    checkEq("session directories are never touched: decisions.jsonl", Files.exists(session.resolve("decisions.jsonl")), true);
    checkEq("session directories are never touched: raw.jsonl", Files.exists(session.resolve("raw.jsonl")), true);
    checkEq("a directory named like a feature log is left alone", Files.isDirectory(lookalike), true);
    checkEq("a missing log directory is not an error", LogRetention.pruneFeatureLogs(dir.resolve("nope"), now, HOUR), 0);
    deleteAll(dir);
  }

  private static void testRetentionHours() throws Exception {
    checkEq("0 hours -> 0 (keep)", LogRetention.retentionMsForHours(0), 0L);
    checkEq("negative hours -> 0 (keep)", LogRetention.retentionMsForHours(-3), 0L);
    checkEq("48 hours in ms", LogRetention.retentionMsForHours(48), 172_800_000L);
    checkEq("large hours do not overflow int arithmetic", LogRetention.retentionMsForHours(2_000_000), 7_200_000_000_000L);

    // The raw pruner honours 0 = keep as well (this is what stops the dev machine deleting raw.jsonl).
    Path root = Files.createTempDirectory("flow_raw_keep_test");
    long now = System.currentTimeMillis();
    Path old = root.resolve("s_" + (now - 100 * HOUR) + "_inst1");
    Files.createDirectories(old);
    Files.writeString(old.resolve("raw.jsonl"), "raw");
    checkEq("raw pruning with retention 0 deletes nothing", LogRetention.pruneOldRawLogs(root, now, 0L), 0);
    checkEq("...and the file is still there", Files.exists(old.resolve("raw.jsonl")), true);
    checkEq("raw pruning with 48 h deletes the old one", LogRetention.pruneOldRawLogs(root, now, 48 * HOUR), 1);
    deleteAll(root);
  }

  private static void deleteAll(Path p) throws Exception {
    if (Files.isDirectory(p)) {
      try (var s = Files.list(p)) {
        for (Path c : (Iterable<Path>) s::iterator) deleteAll(c);
      }
    }
    Files.deleteIfExists(p);
  }
}
