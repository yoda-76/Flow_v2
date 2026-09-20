package com.flow.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Shared 24h-session-boundary detection (D-29/D-34's own boundary,
 * finally given a concrete clock time -- decided directly with the user,
 * 2026-09-20): **17:00 America/Chicago**, matching CME Globex's own
 * electronic-session rollover for `@GC` and staying consistent with
 * D-34's existing Asia/London/NY sub-session windows already being
 * defined in that same zone. `ZoneId` handles CT's DST transitions on its
 * own -- no manual UTC-offset arithmetic here.
 *
 * Deliberately narrow scope: this answers "has a new session started,"
 * nothing else. It does **not** reset D-21's price-tick anchor
 * (`PriceCodec`, flow-runtime) or the journal's per-activation session
 * file -- re-anchoring ticks mid-run would shift the coordinate system
 * every tick-keyed feature already has live state expressed in (zone
 * ranges, VP buckets, market-structure TJL zones), a materially bigger
 * and riskier change than zeroing a counter, and the journal's file
 * boundary is a separate, coarser thing MotiveWave's own study lifecycle
 * already governs. Wired into `RiskChain` (D-19's per-session reversal
 * cap, D-30's daily-loss PnL) and `VWAPFeature` (D-57's own deferred
 * "true daily reset") only -- both are self-contained counters safe to
 * zero out, unlike position/entry-price state, which reflects reality
 * and must survive a calendar rollover untouched.
 */
public final class SessionBoundary {
  private static final ZoneId ZONE = ZoneId.of("America/Chicago");
  private static final LocalTime BOUNDARY = LocalTime.of(17, 0);

  private SessionBoundary() {}

  /**
   * A monotonically increasing id for "which trading session this instant
   * belongs to" -- the epoch-day of that session's own 17:00 CT start.
   * Two instants share an id iff they fall in the same session (an
   * instant at or after 17:00 CT belongs to the session that just
   * started; before 17:00 CT, it belongs to the session that started the
   * previous day).
   */
  public static long sessionIdFor(long epochMillis) {
    ZonedDateTime zdt = Instant.ofEpochMilli(epochMillis).atZone(ZONE);
    LocalDate date = zdt.toLocalDate();
    if (zdt.toLocalTime().isBefore(BOUNDARY)) date = date.minusDays(1);
    return date.toEpochDay();
  }

  /**
   * Stateful "did a new session just start" check, drain-thread-only
   * (same single-writer assumption every Feature/RiskChain already
   * relies on). Feed it every event's eventTimeMs, not just ticks -- a
   * quiet book must still cross the boundary on schedule via ClockEvent,
   * the same reason D-23 injects synthetic clock events at all.
   */
  public static final class Tracker {
    private long lastSessionId = Long.MIN_VALUE;

    /**
     * True exactly on the update that crosses into a new session. The
     * very first observation ever just establishes the baseline -- it is
     * never itself reported as a transition, so a feature attaching
     * mid-session doesn't see a spurious reset the moment it starts.
     */
    public boolean advance(long epochMillis) {
      long id = sessionIdFor(epochMillis);
      if (id == lastSessionId) return false;
      boolean first = lastSessionId == Long.MIN_VALUE;
      lastSessionId = id;
      return !first;
    }

    public long currentSessionId() {
      return lastSessionId;
    }
  }
}
