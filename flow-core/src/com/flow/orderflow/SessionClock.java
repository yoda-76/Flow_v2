package com.flow.orderflow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;

/**
 * Session labels (gapAnswers §7, R7/S8). Defined in each financial centre's own local time so daylight saving is
 * handled by java.time, matching the course's IST times (which were given in a summer month):
 * ASIA from 09:00 Asia/Tokyo (= 05:30 IST), LONDON from 08:00 Europe/London, NY 09:00-13:00 America/New_York.
 * The CME trading day starts 17:00 America/Chicago; OPEN = from that start until Asia, LATE = after NY until the halt.
 */
public final class SessionClock {
  public enum Kind { OPEN, ASIA, LONDON, NY, LATE }

  static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
  static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
  static final ZoneId LONDON = ZoneId.of("Europe/London");
  static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

  private SessionClock() {}

  /** Epoch ms of the 17:00 Chicago start of the trading day containing t. */
  public static long tradingDayStart(long t) {
    ZonedDateTime z = Instant.ofEpochMilli(t).atZone(CHICAGO);
    ZonedDateTime start = z.toLocalDate().atTime(17, 0).atZone(CHICAGO);
    if (z.isBefore(start)) start = z.toLocalDate().minusDays(1).atTime(17, 0).atZone(CHICAGO);
    return start.toInstant().toEpochMilli();
  }

  /** Epoch ms of the Sunday 17:00 Chicago start of the trading week containing t. */
  public static long tradingWeekStart(long t) {
    long day = tradingDayStart(t);
    ZonedDateTime d = Instant.ofEpochMilli(day).atZone(CHICAGO);
    ZonedDateTime sunday = d.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .atTime(17, 0).atZone(CHICAGO);
    return sunday.toInstant().toEpochMilli();
  }

  public static Kind kindAt(long t) {
    long dayStart = tradingDayStart(t);
    long asia = boundaryAfter(dayStart, TOKYO, LocalTime.of(9, 0));
    long london = boundaryAfter(dayStart, LONDON, LocalTime.of(8, 0));
    long ny = boundaryAfter(dayStart, NEW_YORK, LocalTime.of(9, 0));
    long nyEnd = boundaryAfter(dayStart, NEW_YORK, LocalTime.of(13, 0));
    if (t >= nyEnd) return Kind.LATE;
    if (t >= ny) return Kind.NY;
    if (t >= london) return Kind.LONDON;
    if (t >= asia) return Kind.ASIA;
    return Kind.OPEN;
  }

  /** Start of the session containing t (for OPEN: the trading-day start). */
  public static long sessionStart(long t) {
    long dayStart = tradingDayStart(t);
    return switch (kindAt(t)) {
      case OPEN -> dayStart;
      case ASIA -> boundaryAfter(dayStart, TOKYO, LocalTime.of(9, 0));
      case LONDON -> boundaryAfter(dayStart, LONDON, LocalTime.of(8, 0));
      case NY -> boundaryAfter(dayStart, NEW_YORK, LocalTime.of(9, 0));
      case LATE -> boundaryAfter(dayStart, NEW_YORK, LocalTime.of(13, 0));
    };
  }

  /** First occurrence of local time {@code lt} in zone {@code zone} at or after {@code from}. */
  static long boundaryAfter(long from, ZoneId zone, LocalTime lt) {
    ZonedDateTime f = Instant.ofEpochMilli(from).atZone(zone);
    LocalDate d = f.toLocalDate();
    ZonedDateTime c = d.atTime(lt).atZone(zone);
    if (c.toInstant().toEpochMilli() < from) c = d.plusDays(1).atTime(lt).atZone(zone);
    return c.toInstant().toEpochMilli();
  }
}
