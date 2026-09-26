package com.flow.journal;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.LongSupplier;

/**
 * An append-only text log that starts a NEW file each trading day (D-106), so old days can be deleted whole
 * instead of a single file growing forever. Wrap it in a PrintWriter and the existing loggers work unchanged.
 *
 * File name: {@code <base>_<yyyy-MM-dd>.log} under {@code dir}, the date being the TRADING day -- the 17:00
 * America/Chicago to 17:00 session the rest of the system uses (SessionBoundary), named by the day it ends in.
 * The day is judged from the injected clock on every write, so a long-running study rolls over on schedule with
 * no timer of its own. {@code afterRoll} (may be null) runs right after a roll -- the runtime uses it to prune
 * old feature logs -- and must not throw into the caller (exceptions are swallowed).
 *
 * The first file is opened in the constructor, so a bad directory fails there (callers already handle that as
 * "no log"); a later failure to open a new day's file is remembered and not retried until the day changes again,
 * and writes meanwhile are dropped: a diagnostic log must never stall or break the event thread. All methods are
 * synchronized.
 */
public final class RollingFileWriter extends Writer {
  private static final ZoneId ZONE = ZoneId.of("America/Chicago");

  private final Path dir;
  private final String base;
  private final LongSupplier clockMs;
  private final Runnable afterRoll;

  private String currentDay;   // the trading-day label of the open file
  private String failedDay;    // a day whose file could not be opened -- not retried until the day changes
  private FileWriter out;

  public RollingFileWriter(Path dir, String base, LongSupplier clockMs, Runnable afterRoll) throws IOException {
    this.dir = dir;
    this.base = base;
    this.clockMs = clockMs;
    this.afterRoll = afterRoll;
    Files.createDirectories(dir);
    open(tradingDay(clockMs.getAsLong()));
  }

  /** The trading day (yyyy-MM-dd) an instant belongs to: 17:00 CT rolls into the next day's name. */
  public static String tradingDay(long epochMs) {
    return LocalDate.from(Instant.ofEpochMilli(epochMs).atZone(ZONE).plusHours(7)).toString();
  }

  /** The file an instant's writes go to. */
  public Path fileFor(long epochMs) {
    return dir.resolve(base + "_" + tradingDay(epochMs) + ".log");
  }

  private void open(String day) throws IOException {
    FileWriter next = new FileWriter(dir.resolve(base + "_" + day + ".log").toFile(), true);
    if (out != null) {
      try { out.close(); } catch (IOException ignored) { /* the old day's file: nothing more to do with it */ }
    }
    out = next;
    currentDay = day;
    failedDay = null;
  }

  /** Switches files if the trading day changed since the last write. Returns whether there is an open file. */
  private boolean ensureCurrentDay() {
    String day = tradingDay(clockMs.getAsLong());
    if (day.equals(currentDay)) return out != null;
    if (day.equals(failedDay)) return false;
    try {
      open(day);
    } catch (IOException e) {
      failedDay = day;
      return false;
    }
    if (afterRoll != null) {
      try { afterRoll.run(); } catch (RuntimeException ignored) { /* diagnostics only */ }
    }
    return true;
  }

  @Override
  public synchronized void write(char[] cbuf, int off, int len) throws IOException {
    if (!ensureCurrentDay()) return; // dropped: see class javadoc
    out.write(cbuf, off, len);
  }

  @Override
  public synchronized void flush() throws IOException {
    if (out != null) out.flush();
  }

  @Override
  public synchronized void close() throws IOException {
    if (out != null) {
      out.close();
      out = null;
    }
  }
}
