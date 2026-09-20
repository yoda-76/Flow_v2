package com.flow.core;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Direct synthetic check for SessionBoundary's 17:00 America/Chicago
 * rollover math, same discipline as TriggerEvaluatorTest /
 * MarketStructureFeatureTest -- no MotiveWave involved.
 */
public final class SessionBoundaryTest {
  private static final ZoneId CT = ZoneId.of("America/Chicago");
  private static int failures = 0;

  private static void check(String label, Object got, Object want) {
    boolean ok = got == null ? want == null : got.equals(want);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static void check(String label, boolean got, boolean want) {
    check(label, (Object) got, (Object) want);
  }

  private static long ct(int y, int mo, int d, int h, int mi) {
    return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, CT).toInstant().toEpochMilli();
  }

  public static void main(String[] args) {
    testBoundaryMath();
    testTrackerFirstObservationIsNotATransition();
    testTrackerReportsExactlyOneTransitionAtTheBoundary();
    testTrackerAcrossMultipleDays();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all SessionBoundary synthetic checks passed.");
  }

  private static void testBoundaryMath() {
    long justBefore = SessionBoundary.sessionIdFor(ct(2026, 3, 15, 16, 59));
    long atBoundary = SessionBoundary.sessionIdFor(ct(2026, 3, 15, 17, 0));
    long justAfter = SessionBoundary.sessionIdFor(ct(2026, 3, 15, 17, 1));
    long lateSameSession = SessionBoundary.sessionIdFor(ct(2026, 3, 16, 16, 59)); // next calendar day, still before its own 17:00

    check("16:59 CT is strictly before the boundary -> previous session", justBefore, atBoundary - 1);
    check("17:00 CT exactly counts as the new session (isBefore is strict)", atBoundary, justAfter);
    check("16:59 CT the NEXT calendar day is still the same session as 17:00 the day before",
        lateSameSession, atBoundary);
  }

  private static void testTrackerFirstObservationIsNotATransition() {
    SessionBoundary.Tracker t = new SessionBoundary.Tracker();
    check("First observation ever never reports a transition (avoids a spurious reset on attach)",
        t.advance(ct(2026, 3, 15, 12, 0)), false);
  }

  private static void testTrackerReportsExactlyOneTransitionAtTheBoundary() {
    SessionBoundary.Tracker t = new SessionBoundary.Tracker();
    t.advance(ct(2026, 3, 15, 12, 0)); // baseline, mid-session
    check("Still same session, no transition", t.advance(ct(2026, 3, 15, 16, 59)), false);
    check("Crossing 17:00 CT reports a transition", t.advance(ct(2026, 3, 15, 17, 0)), true);
    check("Immediately re-checking the same instant's session doesn't re-report",
        t.advance(ct(2026, 3, 15, 17, 1)), false);
  }

  private static void testTrackerAcrossMultipleDays() {
    SessionBoundary.Tracker t = new SessionBoundary.Tracker();
    t.advance(ct(2026, 3, 15, 18, 0)); // baseline, inside session starting 2026-03-15 17:00
    check("Day 2 crossing", t.advance(ct(2026, 3, 16, 17, 0)), true);
    check("Day 2 mid-session, no transition", t.advance(ct(2026, 3, 16, 20, 0)), false);
    check("Day 3 crossing", t.advance(ct(2026, 3, 17, 17, 0)), true);
  }
}
