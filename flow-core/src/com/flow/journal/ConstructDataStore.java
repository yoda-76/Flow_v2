package com.flow.journal;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Retained per-construct data (D-88): one JSONL file per construct per
 * trading day at {@code <root>/<construct>/<sessionId>.jsonl}, kept for a
 * rolling window of trading days, oldest day deleted when a new one starts.
 * sessionId is SessionBoundary.sessionIdFor()'s epoch-day of the session's
 * 17:00 CT start, so "N trading days" skips weekends by construction (only
 * sessions that actually have data count).
 *
 * Same threading rule as JournalWriter: the caller (Pipeline's drain
 * thread) never does I/O -- write() is a non-blocking offer onto a bounded
 * queue and this class's own thread is the only one that touches disk. If
 * the queue fills, lines are dropped and counted; the next line written
 * carries a DROPPED marker with the count, so a hole is admitted, not
 * silent (same stance as the raw journal's GAP_MARKER).
 */
public final class ConstructDataStore {
  private record Entry(String construct, long sessionId, String line) {}

  private static final int QUEUE_CAPACITY = 20_000;

  private final Path root;
  private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong dropped = new AtomicLong(0);
  private final AtomicLong droppedReported = new AtomicLong(0);
  private volatile Thread writerThread;

  // Prune request: -1 = none pending. Executed on the writer thread.
  private final Object pruneLock = new Object();
  private int pendingPruneKeep = -1;
  private long pendingPruneCurrentSession = 0;

  private final Map<String, PrintWriter> open = new HashMap<>(); // writer thread only

  public ConstructDataStore(Path root) {
    this.root = root;
  }

  public Path root() {
    return root;
  }

  public void start() {
    if (!running.compareAndSet(false, true)) return;
    writerThread = new Thread(this::writeLoop, "flow-construct-data-writer");
    writerThread.setDaemon(true);
    writerThread.start();
  }

  /** Non-blocking; drops (and counts) if the queue is full. */
  public void write(String construct, long sessionId, String jsonLine) {
    if (!queue.offer(new Entry(construct, sessionId, jsonLine))) {
      dropped.incrementAndGet();
    }
  }

  public long droppedCount() {
    return dropped.get();
  }

  /**
   * Ask the writer thread to prune to the newest keepSessions trading days
   * (counting currentSessionId as one even if it has no file yet). Safe to
   * call from any thread, returns immediately.
   */
  public void requestPrune(int keepSessions, long currentSessionId) {
    synchronized (pruneLock) {
      pendingPruneKeep = keepSessions;
      pendingPruneCurrentSession = currentSessionId;
    }
  }

  /**
   * Deletes every {@code <sessionId>.jsonl} whose sessionId isn't among the
   * newest keepSessions distinct ids found under root (plus
   * currentSessionId, which counts even with no file yet -- otherwise the
   * first prune after a rollover would keep one day too many, since the
   * new day's first line hasn't been written yet). Returns files deleted.
   * Non-matching names are never touched.
   */
  public static int pruneNow(Path root, int keepSessions, long currentSessionId) throws IOException {
    if (!Files.isDirectory(root)) return 0;
    TreeSet<Long> ids = new TreeSet<>();
    ids.add(currentSessionId);
    List<Path> files = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(root)) {
      for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
        try (Stream<Path> fs = Files.list(dir)) {
          for (Path f : (Iterable<Path>) fs::iterator) {
            Long id = parseSessionId(f.getFileName().toString());
            if (id != null) {
              ids.add(id);
              files.add(f);
            }
          }
        }
      }
    }
    TreeSet<Long> keep = new TreeSet<>();
    for (Long id : ids.descendingSet()) {
      if (keep.size() >= keepSessions) break;
      keep.add(id);
    }
    int deleted = 0;
    for (Path f : files) {
      Long id = parseSessionId(f.getFileName().toString());
      if (id != null && !keep.contains(id)) {
        Files.deleteIfExists(f);
        deleted++;
      }
    }
    return deleted;
  }

  static Long parseSessionId(String fileName) {
    if (!fileName.endsWith(".jsonl")) return null;
    String stem = fileName.substring(0, fileName.length() - ".jsonl".length());
    if (stem.isEmpty()) return null;
    for (int i = 0; i < stem.length(); i++) {
      if (!Character.isDigit(stem.charAt(i))) return null;
    }
    try {
      return Long.parseLong(stem);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public void stop() {
    running.set(false);
    Thread t = writerThread;
    if (t != null) t.interrupt();
  }

  /** Stops the writer, drains whatever is queued, closes every open file. */
  public void flushAndClose() {
    stop();
    Thread t = writerThread;
    if (t != null) {
      try {
        t.join(2000);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    Entry e;
    while ((e = queue.poll()) != null) writeEntry(e);
    for (PrintWriter w : open.values()) {
      w.flush();
      w.close();
    }
    open.clear();
  }

  private void writeLoop() {
    long lastFlush = System.currentTimeMillis();
    while (running.get()) {
      runPendingPrune();
      Entry e = queue.poll();
      if (e != null) {
        writeEntry(e);
        continue;
      }
      long now = System.currentTimeMillis();
      if (now - lastFlush >= 1000) {
        for (PrintWriter w : open.values()) w.flush();
        lastFlush = now;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void runPendingPrune() {
    int keep;
    long current;
    synchronized (pruneLock) {
      keep = pendingPruneKeep;
      current = pendingPruneCurrentSession;
      pendingPruneKeep = -1;
    }
    if (keep < 0) return;
    try {
      pruneNow(root, keep, current);
    } catch (IOException ex) {
      System.err.println("FLOW DATA: prune failed: " + ex);
    }
  }

  private void writeEntry(Entry e) {
    try {
      PrintWriter w = writerFor(e.construct(), e.sessionId());
      long d = dropped.get();
      long reported = droppedReported.get();
      if (d > reported) {
        w.println("{\"type\":\"DROPPED\",\"count\":" + (d - reported) + "}");
        droppedReported.set(d);
      }
      w.println(e.line());
    } catch (IOException ex) {
      System.err.println("FLOW DATA: write failed for " + e.construct() + ": " + ex);
    }
  }

  private PrintWriter writerFor(String construct, long sessionId) throws IOException {
    String key = construct + "/" + sessionId;
    PrintWriter w = open.get(key);
    if (w != null) return w;
    Path dir = root.resolve(construct);
    Files.createDirectories(dir);
    w = new PrintWriter(Files.newBufferedWriter(dir.resolve(sessionId + ".jsonl"),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND));
    open.put(key, w);
    return w;
  }
}
