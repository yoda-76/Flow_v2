package com.flow.journal;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two-tier journal, one dedicated writer thread, backpressure policy
 * decided rather than discovered (D-15):
 *
 *  - decisions tier: JSONL, human-readable, long retention, change-only.
 *    If this queue fills, that is a bug -- fails loudly (logged +
 *    decisionsOverflowed() flips true) rather than silently dropping a
 *    decision. The runtime is expected to check this flag and disarm.
 *  - raw tier: the full sequenced event stream, short rolling retention.
 *    If this queue fills, drop and write a single GAP_MARKER record
 *    spanning the lost seq range once writing resumes -- a replay that
 *    silently skipped events is worse than one that admits it did.
 *
 * The pipeline (drain) thread never does I/O -- both enqueue calls here
 * are non-blocking offers, called from whatever thread produced the
 * record, and this class's own thread is the only one that touches disk.
 */
public final class JournalWriter {
  private final BlockingQueue<Entry> decisionsQueue = new ArrayBlockingQueue<>(10_000);
  private final BlockingQueue<Entry> rawQueue = new ArrayBlockingQueue<>(50_000);
  private final AtomicBoolean decisionsOverflowed = new AtomicBoolean(false);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile Thread writerThread;

  private final PrintWriter decisionsOut;
  private final PrintWriter rawOut;

  private record Entry(long seq, String line) {}

  public JournalWriter(Path sessionDir) throws IOException {
    Files.createDirectories(sessionDir);
    decisionsOut = new PrintWriter(Files.newBufferedWriter(sessionDir.resolve("decisions.jsonl")));
    rawOut = new PrintWriter(Files.newBufferedWriter(sessionDir.resolve("raw.jsonl")));
  }

  public void writeDecision(long seq, String jsonLine) {
    if (!decisionsQueue.offer(new Entry(seq, jsonLine))) {
      decisionsOverflowed.set(true);
      System.err.println("FLOW JOURNAL: decisions queue full at seq=" + seq
          + " -- this is a bug, not a transient condition. Disarm expected.");
    }
  }

  public boolean decisionsOverflowed() {
    return decisionsOverflowed.get();
  }

  public void writeRaw(long seq, String jsonLine) {
    if (!rawQueue.offer(new Entry(seq, jsonLine))) {
      rawDropped(seq);
    }
  }

  // --- raw-tier gap tracking, applied on the writer thread only ---
  private Long pendingGapStart = null;
  private long pendingGapEnd = -1;
  private final Object gapLock = new Object();

  private void rawDropped(long seq) {
    synchronized (gapLock) {
      if (pendingGapStart == null) pendingGapStart = seq;
      pendingGapEnd = seq;
    }
  }

  private void flushPendingGapIfAny() {
    Long start;
    long end;
    synchronized (gapLock) {
      if (pendingGapStart == null) return;
      start = pendingGapStart;
      end = pendingGapEnd;
      pendingGapStart = null;
    }
    String line = Json.object()
        .field("type", "GAP_MARKER")
        .field("fromSeq", start)
        .field("toSeq", end)
        .build();
    rawOut.println(line);
  }

  public void start() {
    if (!running.compareAndSet(false, true)) return;
    writerThread = new Thread(this::writeLoop, "flow-journal-writer");
    writerThread.setDaemon(true);
    writerThread.start();
  }

  public void stop() {
    running.set(false);
    Thread t = writerThread;
    if (t != null) t.interrupt();
  }

  public void flushAndClose() {
    stop();
    // Drain whatever is left, best-effort, before closing.
    Entry e;
    while ((e = decisionsQueue.poll()) != null) decisionsOut.println(e.line());
    flushPendingGapIfAny();
    while ((e = rawQueue.poll()) != null) rawOut.println(e.line());
    decisionsOut.flush();
    decisionsOut.close();
    rawOut.flush();
    rawOut.close();
  }

  private int rawSinceFlush = 0;
  private static final int RAW_FLUSH_EVERY = 50; // durability vs. I/O cost at MBO rates

  private void writeLoop() {
    while (running.get()) {
      boolean wroteSomething = false;

      Entry d = decisionsQueue.poll();
      if (d != null) {
        decisionsOut.println(d.line());
        decisionsOut.flush(); // decisions tier is low-volume; flush every line for durability
        wroteSomething = true;
      }

      Entry r = rawQueue.poll();
      if (r != null) {
        flushPendingGapIfAny();
        rawOut.println(r.line());
        if (++rawSinceFlush >= RAW_FLUSH_EVERY) {
          rawOut.flush();
          rawSinceFlush = 0;
        }
        wroteSomething = true;
      }

      if (!wroteSomething) {
        rawOut.flush(); // idle moment -- cheap insurance against losing the tail on a crash
        rawSinceFlush = 0;
        try {
          Thread.sleep(5);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }
}
