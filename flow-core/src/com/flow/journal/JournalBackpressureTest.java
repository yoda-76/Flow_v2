package com.flow.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Direct synthetic test for JournalWriter's two backpressure policies
 * (D-15's class javadoc): raw tier drops + GAP_MARKER, decisions tier
 * fails loud via decisionsOverflowed(). Previously specified only in
 * prose and exercised only by real live sessions -- flagged in
 * docs/dynamic/plumbingEdgeCases.md §3 as untested. LogRetentionTest
 * covers rotation/deletion, a different concern entirely.
 *
 * The two decisions-queue tests run without ever calling journal.start()
 * -- the writer thread never runs, so nothing drains the queue and the
 * exact 10,000-capacity boundary is fully deterministic, no timing
 * involved. The single-gap raw test does the same for the same reason.
 * The two-separate-gaps test is the one exception: proving a successful
 * write flushes a *pending* gap before a *second*, independent one can
 * start requires the real background writer thread actually draining
 * between the two episodes, so it necessarily waits on real time -- see
 * that test's own comment for why the margins used are safe, not just
 * convenient.
 */
public final class JournalBackpressureTest {
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

  public static void main(String[] args) throws IOException, InterruptedException {
    testDecisionsQueueOverflowExactBoundary();
    testRawQueueSingleContiguousGap();
    testRawQueueTwoSeparateGapsStayDistinct();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all JournalWriter backpressure checks passed.");
  }

  private static Path tempSessionDir() throws IOException {
    Path dir = Files.createTempDirectory("flow_v2_test_journal");
    dir.toFile().deleteOnExit();
    return dir;
  }

  /**
   * decisionsQueue's capacity is a hardcoded 10_000 (JournalWriter.java).
   * Never calling start() means nothing ever drains it, so the exact
   * boundary (10,000 succeed, the 10,001st fails) is deterministic.
   */
  private static void testDecisionsQueueOverflowExactBoundary() throws IOException {
    JournalWriter j = new JournalWriter(tempSessionDir());
    for (int i = 1; i <= 10_000; i++) {
      j.writeDecision(i, "{\"seq\":" + i + "}");
    }
    check("decisionsOverflowed still false after exactly 10,000 offers (queue exactly full, not over)",
        j.decisionsOverflowed(), false);
    j.writeDecision(10_001, "{\"seq\":10001}");
    check("decisionsOverflowed flips true on the 10,001st offer", j.decisionsOverflowed(), true);
  }

  /** Same reasoning as above, applied to rawQueue's hardcoded 50_000 capacity. */
  private static void testRawQueueSingleContiguousGap() throws IOException {
    Path dir = tempSessionDir();
    JournalWriter j = new JournalWriter(dir);
    for (int i = 1; i <= 50_000; i++) {
      j.writeRaw(i, "{\"seq\":" + i + "}");
    }
    // Next 5 (seq 50001..50005) overflow the full queue and get dropped.
    for (int i = 50_001; i <= 50_005; i++) {
      j.writeRaw(i, "{\"seq\":" + i + "}");
    }
    j.flushAndClose(); // never start()ed -- flushAndClose() drains what's queued and flushes the pending gap itself

    List<String> lines = Files.readAllLines(dir.resolve("raw.jsonl"));
    long rawLines = lines.stream().filter(l -> !l.contains("GAP_MARKER")).count();
    List<String> gapLines = lines.stream().filter(l -> l.contains("GAP_MARKER")).toList();
    check("all 50,000 successfully-queued raw entries survive to disk", rawLines, 50_000L);
    check("exactly one GAP_MARKER written for the dropped range", gapLines.size(), 1);
    check("gap covers fromSeq=50001", gapLines.get(0).contains("\"fromSeq\":50001"), true);
    check("gap covers toSeq=50005", gapLines.get(0).contains("\"toSeq\":50005"), true);
  }

  /**
   * A gap that's already been flushed to disk (because a later raw entry
   * was successfully drained after it) must not get merged with a LATER,
   * independent gap -- JournalWriter.rawDropped()/flushPendingGapIfAny()
   * reset pendingGapStart to null on every flush, so a second drop episode
   * starts its own fresh range.
   *
   * Each overflow episode is created with the writer thread NOT running
   * (deterministic, same as the single-gap test above), and start()/
   * stop() bracket the drain in between purely so gap A actually gets
   * flushed to disk (proving it's closed, not still pending) before
   * episode 2 begins -- the writer thread is never racing the producer
   * for whether an overflow happens at all, only used for its own single
   * job (drain and flush) with generous, one-directional waits around it.
   */
  private static void testRawQueueTwoSeparateGapsStayDistinct() throws IOException, InterruptedException {
    Path dir = tempSessionDir();
    JournalWriter j = new JournalWriter(dir);

    // Episode 1, writer not yet started -- deterministic overflow (gap A).
    for (int i = 1; i <= 50_000; i++) j.writeRaw(i, "{\"seq\":" + i + "}");
    for (int i = 50_001; i <= 50_003; i++) j.writeRaw(i, "{\"seq\":" + i + "}");

    // Drain everything and flush gap A to disk (the first successful
    // drain after a recorded drop always flushes it). Draining 50,000
    // already-buffered println()s is a low-single-digit-millisecond
    // operation on this hardware -- 300ms is a comfortable multiple of
    // that, not a tight race.
    j.start();
    Thread.sleep(300);

    // A successful write strictly between the two episodes -- proves gap
    // A is already closed out, not still open when episode 2 starts.
    j.writeRaw(50_004, "{\"seq\":50004}");
    Thread.sleep(50);

    // Stop the writer and give it a moment to fully exit its loop before
    // episode 2 begins, so nothing drains concurrently while we fill the
    // queue again -- episode 2's overflow is then just as deterministic
    // as episode 1's, at a seq range that can't be confused with gap A.
    j.stop();
    Thread.sleep(50);
    for (int i = 100_000; i < 150_000; i++) j.writeRaw(i, "{\"seq\":" + i + "}");
    for (int i = 150_000; i <= 150_002; i++) j.writeRaw(i, "{\"seq\":" + i + "}");

    j.flushAndClose();

    List<String> gapLines = Files.readAllLines(dir.resolve("raw.jsonl")).stream()
        .filter(l -> l.contains("GAP_MARKER")).toList();
    check("exactly two distinct GAP_MARKER records, not one merged range", gapLines.size(), 2);
    if (gapLines.size() == 2) {
      check("gap A is the first-episode range", gapLines.get(0).contains("\"fromSeq\":50001")
          && gapLines.get(0).contains("\"toSeq\":50003"), true);
      check("gap B is the second-episode range, independent of gap A",
          gapLines.get(1).contains("\"toSeq\":150002"), true);
    }
  }
}
