package com.flow.core;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Where an instant falls relative to the daily maintenance halt and the
 * weekend closure -- the clock half of D-29's "flatten at session end"
 * (D-92). Pure and stateless: the same instant always gives the same phase,
 * so replay reproduces it.
 *
 * Model (CME Globex, @GC, all in America/Chicago so DST is handled by the
 * ZoneId, same zone as SessionBoundary):
 *   - trading pauses daily at CLOSE (16:00 CT) and resumes at REOPEN
 *     (17:00 CT -- SessionBoundary's own session boundary);
 *   - the week closes Friday at CLOSE and reopens Sunday at REOPEN.
 * A position must not be carried through either gap, so:
 *   OPEN            normal trading.
 *   NO_NEW_ENTRIES  the last `noEntryLeadMinutes` before CLOSE: nothing new
 *                   may open, but existing positions/brackets run as usual.
 *   FLATTEN         from `flattenLeadMinutes` before CLOSE until REOPEN
 *                   (and all weekend): no entries, and anything still open
 *                   must be closed.
 *
 * Not modelled, on purpose (flagged in D-92): exchange holidays and early
 * closes (a holiday looks like a normal day -- the feed is simply silent;
 * the user watches the calendar and there is deliberately no feed-silence
 * watchdog, D-93), and any per-strategy
 * opt-in to overnight carry (D-29: "no overnight carry unless a strategy
 * explicitly opts in later").
 *
 * flattenLeadMinutes <= 0 disables the whole thing (always OPEN), so a
 * config can switch the feature off without a code change. The clamps keep
 * a misconfigured value from wrapping past midnight or inverting the two
 * windows. A large lead is also how to TEST it live: the real flatten
 * (15:55 CT = ~02:25 IST) falls outside any monitored window, so setting
 * e.g. flattenLeadMinutes=400 moves the window to start at 09:20 CT.
 */
public final class TradingWindow {
  public enum Phase { OPEN, NO_NEW_ENTRIES, FLATTEN }

  static final ZoneId ZONE = ZoneId.of("America/Chicago");
  static final LocalTime CLOSE = LocalTime.of(16, 0);
  static final LocalTime REOPEN = SessionBoundary.BOUNDARY;
  private static final int MAX_LEAD_MINUTES = 600; // 10h -> window can start as early as 06:00 CT; lets a live test place it inside a monitored session

  private TradingWindow() {}

  public static Phase phaseAt(long epochMillis, int noEntryLeadMinutes, int flattenLeadMinutes) {
    if (flattenLeadMinutes <= 0) return Phase.OPEN;
    int flattenLead = Math.min(flattenLeadMinutes, MAX_LEAD_MINUTES);
    int noEntryLead = Math.min(Math.max(noEntryLeadMinutes, flattenLead), MAX_LEAD_MINUTES);

    ZonedDateTime z = Instant.ofEpochMilli(epochMillis).atZone(ZONE);
    DayOfWeek day = z.getDayOfWeek();
    LocalTime t = z.toLocalTime();

    if (day == DayOfWeek.SATURDAY) return Phase.FLATTEN;
    if (day == DayOfWeek.SUNDAY) return t.isBefore(REOPEN) ? Phase.FLATTEN : Phase.OPEN;

    LocalTime flattenAt = CLOSE.minusMinutes(flattenLead);
    LocalTime noEntryAt = CLOSE.minusMinutes(noEntryLead);
    if (!t.isBefore(flattenAt)) {
      // From the flatten time to the end of a Friday the week is over; on other days until REOPEN.
      if (day == DayOfWeek.FRIDAY || t.isBefore(REOPEN)) return Phase.FLATTEN;
      return Phase.OPEN; // Mon-Thu, at or after REOPEN
    }
    if (!t.isBefore(noEntryAt)) return Phase.NO_NEW_ENTRIES;
    return Phase.OPEN;
  }
}
