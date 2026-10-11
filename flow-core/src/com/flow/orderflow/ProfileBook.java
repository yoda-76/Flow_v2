package com.flow.orderflow;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The profiles the models read (gapAnswers §7, G9, G44): developing session / day / week, plus the last completed
 * profile of each session kind and the previous day. Rolls on session / trading-day / week boundaries from
 * {@link SessionClock}. A completed profile's POC stays "naked" until price trades within one row of it.
 */
public final class ProfileBook {
  private final OfConfig c;
  private Profile session, day, week;
  private SessionClock.Kind sessionKind;
  private final Map<SessionClock.Kind, Profile> completedByKind = new EnumMap<>(SessionClock.Kind.class);
  private Profile lastCompletedSession, prevDay;

  ProfileBook(OfConfig c) {
    this.c = c;
  }

  void onTrade(long t, int price, double size) {
    roll(t);
    session.add(t, price, size);
    day.add(t, price, size);
    week.add(t, price, size);
    for (Profile p : completed()) {
      Integer poc = p.poc(c);
      if (!p.pocRevisited && poc != null && Math.abs(price - poc) <= c.profileRowTicks) { p.pocRevisited = true; p.pocRevisitedAtMs = t; }
    }
  }

  void onClock(long t) {
    if (session != null) roll(t);
  }

  private void roll(long t) {
    long dayStart = SessionClock.tradingDayStart(t);
    long weekStart = SessionClock.tradingWeekStart(t);
    long sessStart = SessionClock.sessionStart(t);
    SessionClock.Kind kind = SessionClock.kindAt(t);
    if (week == null || week.startMs != weekStart) week = new Profile("week", weekStart, c.profileRowTicks);
    if (day == null || day.startMs != dayStart) {
      if (day != null && !day.isEmpty()) { day.completed = true; prevDay = day; }
      day = new Profile("day", dayStart, c.profileRowTicks);
    }
    if (session == null || session.startMs != sessStart) {
      if (session != null && !session.isEmpty()) {
        session.completed = true;
        completedByKind.put(sessionKind, session);
        lastCompletedSession = session;
      }
      session = new Profile("session:" + kind, sessStart, c.profileRowTicks);
      sessionKind = kind;
    }
  }

  /** Drop everything (contract roll, G26). */
  void reset() {
    session = day = week = null;
    completedByKind.clear();
    lastCompletedSession = prevDay = null;
  }

  public SessionClock.Kind sessionKind() { return sessionKind; }
  public Profile session() { return session; }
  public Profile day() { return day; }
  public Profile week() { return week; }
  public Profile prevDay() { return prevDay; }
  public Profile completed(SessionClock.Kind k) { return completedByKind.get(k); }
  public Profile lastCompletedSession() { return lastCompletedSession; }

  /** The session profile models trade from: the developing one once mature, else the last completed one (G44). */
  public Profile activeSession(long now) {
    if (session != null && session.mature(now, c)) return session;
    return lastCompletedSession != null ? lastCompletedSession : session;
  }

  public List<Profile> completed() {
    List<Profile> out = new ArrayList<>(completedByKind.values());
    if (prevDay != null) out.add(prevDay);
    return out;
  }

  /** Every profile whose levels count as locations/targets: developing session, day, week and all completed. */
  public List<Profile> all() {
    List<Profile> out = new ArrayList<>();
    if (session != null) out.add(session);
    if (day != null) out.add(day);
    if (week != null) out.add(week);
    out.addAll(completed());
    return out;
  }
}
