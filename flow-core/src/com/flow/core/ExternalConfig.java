package com.flow.core;

import com.flow.journal.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Session/risk config, hand-edited by the user (D-08/D-18) -- same write
 * discipline as `.env`: this class only ever reads the file, the runtime
 * never writes it. A flat JSON object, reusing the existing hand-rolled
 * `JsonObject` reader (no nested structure needed).
 *
 * Missing keys fall back to conservative defaults (documented per
 * accessor below) rather than failing to load -- a config file that's
 * incomplete should still let the session start, just with the
 * safest-available assumption, and every value actually in effect
 * (default or file-supplied) is meant to be journaled at session start
 * by the caller so a decision made on an incomplete or stale config is
 * traceable, not silently assumed current (README "External inputs and
 * config").
 *
 * Units are integer ticks / plain counts throughout, not dollars -- this
 * sidesteps needing a per-instrument dollar-per-tick multiplier as a
 * separate config value for now (D-30's daily-loss guard is
 * directionally meaningful in ticks; revisit if a real $ threshold ever
 * matters more than this v1 simplification).
 */
public final class ExternalConfig {
  private final Map<String, String> raw;
  private final long fileLastModifiedMs;

  private ExternalConfig(Map<String, String> raw, long fileLastModifiedMs) {
    this.raw = raw;
    this.fileLastModifiedMs = fileLastModifiedMs;
  }

  public static ExternalConfig load(Path file) throws IOException {
    String content = Files.readString(file);
    JsonObject obj = JsonObject.parse(content);
    Map<String, String> values = new TreeMap<>();
    for (String key : KNOWN_KEYS) {
      // Every known key is a plain integer/long in the file -- getLong()
      // directly, not getString() (which assumes a quoted value and
      // would throw on a bare number).
      if (obj.has(key) && !obj.isNull(key)) {
        values.put(key, String.valueOf(obj.getLong(key)));
      }
    }
    return new ExternalConfig(values, Files.getLastModifiedTime(file).toMillis());
  }

  /** Empty config -- every accessor returns its default. Used when no file exists yet. */
  public static ExternalConfig empty() {
    return new ExternalConfig(Map.of(), 0L);
  }

  private static final String[] KNOWN_KEYS = {
      "fixedContracts", "maxContracts", "dailyLossLimitTicks", "rateLimitPerMinute",
      "minDwellMs", "maxReversalsPerSession", "lagQueueDepthThreshold", "lagProcessingMsThreshold",
      "dataIntervalSeconds", "liquidityIntervalSeconds", "dataKeepTradingDays",
      "flattenLeadMinutes", "noEntryLeadMinutes"
  };

  private int getInt(String key, int def) {
    String v = raw.get(key);
    return v == null ? def : Integer.parseInt(v);
  }

  private long getLong(String key, long def) {
    String v = raw.get(key);
    return v == null ? def : Long.parseLong(v);
  }

  /** D-30: fixed contract count, not risk-per-trade. Default 1 -- the safest non-zero size. */
  public int fixedContracts() { return getInt("fixedContracts", 1); }

  /** Risk-chain size cap -- default equals fixedContracts() so a misconfigured strategy can't silently exceed its own intended size. */
  public int maxContracts() { return getInt("maxContracts", fixedContracts()); }

  /** D-30: realized+open PnL guard, in ticks (see class javadoc on the ticks-not-dollars simplification). Default: a conservative small loss. */
  public int dailyLossLimitTicks() { return getInt("dailyLossLimitTicks", 200); }

  /** Max intent CHANGES (not events) per rolling 60s window. Default: conservative. */
  public int rateLimitPerMinute() { return getInt("rateLimitPerMinute", 6); }

  /** D-19 churn guard: minimum time in a position before another change is allowed. */
  public long minDwellMs() { return getLong("minDwellMs", 5_000L); }

  /** D-19 churn guard: max position-direction changes allowed per session. */
  public int maxReversalsPerSession() { return getInt("maxReversalsPerSession", 20); }

  /** D-19 lag guard: Sequencer.queueDepth() threshold that trips a disarm. */
  public long lagQueueDepthThreshold() { return getLong("lagQueueDepthThreshold", 1_000L); }

  /** D-19 lag guard: per-event processing time (ms) threshold that trips a disarm. */
  public long lagProcessingMsThreshold() { return getLong("lagProcessingMsThreshold", 2_000L); }

  /** D-88: footprint candle / VWAP / big-trades write interval. Start at 1s, raise if storage or load demands it. */
  public int dataIntervalSeconds() { return getInt("dataIntervalSeconds", 1); }

  /** D-88: liquidity map snapshot interval, tunable independently (the heaviest construct). */
  public int liquidityIntervalSeconds() { return getInt("liquidityIntervalSeconds", 1); }

  /** D-88: rolling window of trading days kept under data/; the oldest is deleted when a new day starts. */
  public int dataKeepTradingDays() { return getInt("dataKeepTradingDays", 7); }

  /**
   * D-92: minutes before the 16:00 CT maintenance halt at which any open position is flattened (default 5
   * -> 15:55 CT; on Fridays the flatten holds through the weekend). 0 disables session-end flattening and
   * the entry block entirely.
   */
  public int flattenLeadMinutes() { return getInt("flattenLeadMinutes", 5); }

  /** D-92: minutes before the halt at which NEW entries stop (default 15 -> 15:45 CT); never less than flattenLeadMinutes. */
  public int noEntryLeadMinutes() { return getInt("noEntryLeadMinutes", 15); }

  /** For staleness journaling (README: "traceable after the fact, not silently assumed current"). 0 = no file loaded, using pure defaults. */
  public long fileLastModifiedMs() { return fileLastModifiedMs; }
}
