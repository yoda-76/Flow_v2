package com.flow.core;

import com.flow.journal.LogRetention;
import com.flow.journal.RollingFileWriter;

import java.io.IOException;
import java.io.Writer;

/**
 * Opens the per-feature diagnostic logs (logs/vwap_feature_2026-09-26.log, ...) as trading-day rolling files and
 * applies the log-retention setting to them (D-106). Replaces the seven copies of
 * {@code new FileWriter(FlowHome.logFile("x_feature.log"), true)} that appended to one file forever.
 *
 * {@link #setRetentionMs} is called once by the runtime as soon as the config is loaded; until then (and whenever
 * it is 0, the default) nothing is ever deleted. Old files are pruned whenever a log rolls to a new day.
 */
public final class FeatureLogs {
  private static volatile long retentionMs = 0L;

  private FeatureLogs() {}

  public static void setRetentionMs(long ms) {
    retentionMs = Math.max(0L, ms);
  }

  public static long retentionMs() {
    return retentionMs;
  }

  /** {@code base} like "vwap_feature". Throws IOException if the log cannot be opened, as before. */
  public static Writer open(String base) throws IOException {
    return open(FlowHome.logs(), base);
  }

  /** As {@link #open(String)} under an explicit directory (tests). */
  public static Writer open(java.nio.file.Path logDir, String base) throws IOException {
    return open(logDir, base, System::currentTimeMillis);
  }

  /** As above with an injected clock, used for both the day rollover and the age of files being pruned (tests). */
  public static Writer open(java.nio.file.Path logDir, String base, java.util.function.LongSupplier clockMs) throws IOException {
    return new RollingFileWriter(logDir, base, clockMs,
        () -> LogRetention.pruneFeatureLogs(logDir, clockMs.getAsLong(), retentionMs));
  }
}
