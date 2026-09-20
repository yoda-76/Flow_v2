package com.flow.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Direct synthetic test for LogRetention (D-75) against a real temp
 * directory -- no MotiveWave involved, same discipline as every other
 * direct synthetic test this session.
 */
public final class LogRetentionTest {
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

  public static void main(String[] args) throws IOException {
    testParseSessionStartMs();
    testPruneOldRawLogs();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all LogRetention synthetic checks passed.");
  }

  private static void testParseSessionStartMs() {
    check("Simple strategyId", LogRetention.parseSessionStartMs("market_structure_lvn_reversal_1789900274994_inst1804843545"), 1789900274994L);
    check("strategyId with underscores doesn't confuse the ms field",
        LogRetention.parseSessionStartMs("null_strategy_1789498983282_inst2112383200"), 1789498983282L);
    check("Negative instanceId (System.identityHashCode can be negative)",
        LogRetention.parseSessionStartMs("s_1700000000000_inst-42"), 1700000000000L);
    check("No '_inst' suffix -> -1", LogRetention.parseSessionStartMs("not_a_session_dir"), -1L);
    check("'_inst' present but no ms field before it -> -1", LogRetention.parseSessionStartMs("_inst123"), -1L);
    check("Non-numeric ms field -> -1", LogRetention.parseSessionStartMs("strat_abc_inst123"), -1L);
  }

  private static void testPruneOldRawLogs() throws IOException {
    Path root = Files.createTempDirectory("flow_v2_log_retention_test");
    long now = System.currentTimeMillis();
    long hour = 60L * 60 * 1000;

    // A: old (55h) -- raw.jsonl must be deleted, decisions.jsonl must NOT be touched.
    Path dirA = root.resolve("strat_" + (now - 55 * hour) + "_inst1");
    Files.createDirectories(dirA);
    Files.writeString(dirA.resolve("raw.jsonl"), "raw content");
    Files.writeString(dirA.resolve("decisions.jsonl"), "decisions content");

    // B: recent (10h) -- within the retention window, nothing touched.
    Path dirB = root.resolve("strat_" + (now - 10 * hour) + "_inst2");
    Files.createDirectories(dirB);
    Files.writeString(dirB.resolve("raw.jsonl"), "raw content");
    Files.writeString(dirB.resolve("decisions.jsonl"), "decisions content");

    // C: doesn't match the session-directory naming pattern at all -- must be skipped entirely.
    Path dirC = root.resolve("not_a_session_dir");
    Files.createDirectories(dirC);
    Files.writeString(dirC.resolve("raw.jsonl"), "raw content");

    // D: old, but no raw.jsonl at all (already pruned, or never had DOM/etc.) -- no crash, not counted.
    Path dirD = root.resolve("strat_" + (now - 60 * hour) + "_inst4");
    Files.createDirectories(dirD);
    Files.writeString(dirD.resolve("decisions.jsonl"), "decisions content");

    // A stray non-directory file directly under root -- must be skipped (not a directory).
    Files.writeString(root.resolve("stray_file.txt"), "irrelevant");

    int pruned = LogRetention.pruneOldRawLogs(root, now, 48 * hour);

    check("Exactly one raw.jsonl actually deleted (dir A only)", pruned, 1);
    check("A's raw.jsonl is gone", Files.exists(dirA.resolve("raw.jsonl")), false);
    check("A's decisions.jsonl is UNTOUCHED -- long retention, never pruned here",
        Files.exists(dirA.resolve("decisions.jsonl")), true);
    check("B's raw.jsonl survives -- within the retention window", Files.exists(dirB.resolve("raw.jsonl")), true);
    check("B's decisions.jsonl survives too", Files.exists(dirB.resolve("decisions.jsonl")), true);
    check("C's raw.jsonl survives -- directory name doesn't match the pattern, skipped entirely",
        Files.exists(dirC.resolve("raw.jsonl")), true);
    check("D survives with no crash despite being old and having no raw.jsonl",
        Files.exists(dirD.resolve("decisions.jsonl")), true);

    deleteRecursive(root);
  }

  private static void deleteRecursive(Path p) throws IOException {
    if (Files.isDirectory(p)) {
      try (var stream = Files.list(p)) {
        for (Path child : (Iterable<Path>) stream::iterator) deleteRecursive(child);
      }
    }
    Files.deleteIfExists(p);
  }
}
