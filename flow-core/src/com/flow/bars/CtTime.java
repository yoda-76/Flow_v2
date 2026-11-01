package com.flow.bars;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Central-time (America/Chicago, DST-aware) helpers for the bar/indicator layer. Every session rule the planned
 * strategies use (00:00 CT session open, 08:30 CT anchor, 14:30/14:55 CT exits) is a CT clock time, and the Python
 * backtests bucket 1-minute bars by CT date and CT minute of day -- so live code must do exactly the same.
 */
public final class CtTime {
  public static final ZoneId CT = ZoneId.of("America/Chicago");

  private CtTime() {}

  private static ZonedDateTime at(long epochMs) {
    return Instant.ofEpochMilli(epochMs).atZone(CT);
  }

  /** The Chicago calendar date of this instant. */
  public static LocalDate ctDate(long epochMs) {
    return at(epochMs).toLocalDate();
  }

  /** Minutes since 00:00 Chicago wall-clock time on that date (0..1439). */
  public static int ctMinuteOfDay(long epochMs) {
    ZonedDateTime z = at(epochMs);
    return z.getHour() * 60 + z.getMinute();
  }

  /**
   * Start time of the bar of length barMs whose END time is endTimeMs. The -1 makes it correct whether the platform
   * reports the end as exclusive (start + barMs) or as the bar's last millisecond (start + barMs - 1).
   * Bars are aligned to the epoch, which for minute-multiple bar lengths is the same as aligning to the CT clock.
   */
  public static long barStartMs(long endTimeMs, long barMs) {
    return Math.floorDiv(endTimeMs - 1, barMs) * barMs;
  }
}
