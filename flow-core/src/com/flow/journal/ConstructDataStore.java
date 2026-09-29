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
 * trading day at {@code <root>/<construct>/<symbol>/<sessionId>.jsonl} (the symbol folder since findings F-1,
 * 2026-09-27 -- two instruments on one day used to share a file; the one-argument constructor keeps the old
 * {@code <root>/<construct>/<sessionId>.jsonl} layout, used by replay tests and older recordings), kept for a
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
  private volatile int openCount = 0; // open.size(), published for tests (E2)
  private final String symbolDir; // F-1: the instrument's folder under each construct; null = old flat layout

  /** Old flat layout {@code <root>/<construct>/<sessionId>.jsonl} (replay, tests). */
  public ConstructDataStore(Path root) {
    this(root, null);
  }

  /**
   * F-1: {@code <root>/<construct>/<symbolDir>/<sessionId>.jsonl}. symbolDir comes from
   * InstrumentPolicy.symbolDir(instrument symbol), e.g. "GC" for "@GC".
   */
  public ConstructDataStore(Path root, String symbolDir) {
    this.root = root;
    this.symbolDir = symbolDir;
  }

  /** The instrument folder this store writes into, or null for the old flat layout. */
  public String symbolDir() {
    return symbolDir;
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
        collectDayFiles(dir, 2, ids, files); // F-1: <construct>/<sessionId>.jsonl and <construct>/<symbol>/<sessionId>.jsonl
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
        // Code review E2: one file that will not delete (locked, or not a plain file) must not abort the rest.
        try {
          if (Files.deleteIfExists(f)) deleted++;
        } catch (IOException ex) {
          System.err.println("FLOW DATA: could not prune " + f + ": " + ex);
        }
      }
    }
    return deleted;
  }

  /** Day files under dir, looking into sub-folders (the F-1 symbol folders) up to `depth` levels. */
  private static void collectDayFiles(Path dir, int depth, TreeSet<Long> ids, List<Path> files) throws IOException {
    try (Stream<Path> fs = Files.list(dir)) {
      for (Path f : (Iterable<Path>) fs::iterator) {
        Long id = parseSessionId(f.getFileName().toString());
        if (id != null) {
          ids.add(id);
          files.add(f);
        } else if (depth > 1 && Files.isDirectory(f)) {
          collectDayFiles(f, depth - 1, ids, files);
        }
      }
    }
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
    closeWritersBefore(current);
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

  /**
   * Code review E2: a writer used to stay open for every (construct, trading day) until shutdown -- handles piling
   * up over a 24/7 run, and the prune below deleting files that were still open (refused on Windows, space not
   * freed on Linux). At each new trading day (the prune request) the previous days' writers are flushed and closed;
   * a late line for an old day simply reopens its file in append mode.
   */
  private void closeWritersBefore(long currentSessionId) {
    var it = open.entrySet().iterator();
    while (it.hasNext()) {
      var en = it.next();
      String key = en.getKey();
      long sid;
      try {
        sid = Long.parseLong(key.substring(key.lastIndexOf('/') + 1));
      } catch (NumberFormatException nfe) {
        continue;
      }
      if (sid < currentSessionId) {
        en.getValue().flush();
        en.getValue().close();
        it.remove();
      }
    }
    openCount = open.size();
  }

  /** For tests: how many construct files the writer thread has open (published after every open/close). */
  public int openWriterCount() {
    return openCount;
  }

  private PrintWriter writerFor(String construct, long sessionId) throws IOException {
    String key = construct + "/" + sessionId;
    PrintWriter w = open.get(key);
    if (w != null) return w;
    Path dir = symbolDir == null ? root.resolve(construct) : root.resolve(construct).resolve(symbolDir);
    Files.createDirectories(dir);
    w = new PrintWriter(Files.newBufferedWriter(dir.resolve(sessionId + ".jsonl"),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND));
    open.put(key, w);
    openCount = open.size();
    return w;
  }
}
