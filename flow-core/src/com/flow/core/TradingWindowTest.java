package com.flow.core;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure clock test for TradingWindow (D-92): the daily halt window, the
 * Friday-through-Sunday closure, the boundaries to the second, DST, and the
 * disable/clamp behaviour. Every instant is built in America/Chicago so the
 * expectations read as the wall-clock times the rule is written in.
 *
 * 2026-09-23 is a Wednesday, 09-25 a Friday, 09-26 a Saturday, 09-27 a
 * Sunday, 09-28 a Monday; 2026-11-01 is the US DST end (CDT -> CST).
 */
public final class TradingWindowTest {
  private static final ZoneId CT = ZoneId.of("America/Chicago");
  private static final int NO_ENTRY = 15;
  private static final int FLATTEN = 5;
  private static int failures = 0;

  private static long ct(int y, int mo, int d, int h, int mi, int s) {
    return ZonedDateTime.of(y, mo, d, h, mi, s, 0, CT).toInstant().toEpochMilli();
  }

  private static void expect(String label, long t, TradingWindow.Phase want) {
    expect(label, t, NO_ENTRY, FLATTEN, want);
  }

  private static void expect(String label, long t, int noEntry, int flatten, TradingWindow.Phase want) {
    TradingWindow.Phase got = TradingWindow.phaseAt(t, noEntry, flatten);
    if (got != want) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) {
    var O = TradingWindow.Phase.OPEN;
    var N = TradingWindow.Phase.NO_NEW_ENTRIES;
    var F = TradingWindow.Phase.FLATTEN;

    // A normal Mon-Thu day, Wednesday 2026-09-23.
    expect("Wed 00:00 open", ct(2026, 9, 23, 0, 0, 0), O);
    expect("Wed 10:00 open", ct(2026, 9, 23, 10, 0, 0), O);
    expect("Wed 15:44:59 open (one second before the entry block)", ct(2026, 9, 23, 15, 44, 59), O);
    expect("Wed 15:45:00 no new entries", ct(2026, 9, 23, 15, 45, 0), N);
    expect("Wed 15:54:59 still no new entries", ct(2026, 9, 23, 15, 54, 59), N);
    expect("Wed 15:55:00 flatten", ct(2026, 9, 23, 15, 55, 0), F);
    expect("Wed 16:00:00 flatten (the halt itself)", ct(2026, 9, 23, 16, 0, 0), F);
    expect("Wed 16:59:59 flatten", ct(2026, 9, 23, 16, 59, 59), F);
    expect("Wed 17:00:00 open again (reopen = session boundary)", ct(2026, 9, 23, 17, 0, 0), O);
    expect("Wed 23:59:59 open", ct(2026, 9, 23, 23, 59, 59), O);

    // The week closes Friday and stays closed until Sunday 17:00 CT.
    expect("Fri 10:00 open", ct(2026, 9, 25, 10, 0, 0), O);
    expect("Fri 15:45 no new entries", ct(2026, 9, 25, 15, 45, 0), N);
    expect("Fri 15:55 flatten", ct(2026, 9, 25, 15, 55, 0), F);
    expect("Fri 17:00 STILL flatten -- the weekend, not the daily reopen", ct(2026, 9, 25, 17, 0, 0), F);
    expect("Fri 23:59:59 flatten", ct(2026, 9, 25, 23, 59, 59), F);
    expect("Sat 00:00 flatten", ct(2026, 9, 26, 0, 0, 0), F);
    expect("Sat 12:00 flatten", ct(2026, 9, 26, 12, 0, 0), F);
    expect("Sat 23:59:59 flatten", ct(2026, 9, 26, 23, 59, 59), F);
    expect("Sun 00:00 flatten", ct(2026, 9, 27, 0, 0, 0), F);
    expect("Sun 16:59:59 flatten", ct(2026, 9, 27, 16, 59, 59), F);
    expect("Sun 17:00:00 open (weekly reopen)", ct(2026, 9, 27, 17, 0, 0), O);
    expect("Sun 23:00 open", ct(2026, 9, 27, 23, 0, 0), O);
    expect("Mon 00:00 open", ct(2026, 9, 28, 0, 0, 0), O);
    expect("Mon 03:00 open (Asia session, not a closed day)", ct(2026, 9, 28, 3, 0, 0), O);

    // DST: the wall-clock rule must hold on both sides of the change (CST from 2026-11-01).
    expect("Fri 2026-10-30 (CDT) 15:55 flatten", ct(2026, 10, 30, 15, 55, 0), F);
    expect("Wed 2026-11-04 (CST) 15:54:59 no new entries", ct(2026, 11, 4, 15, 54, 59), N);
    expect("Wed 2026-11-04 (CST) 15:55 flatten", ct(2026, 11, 4, 15, 55, 0), F);
    expect("Wed 2026-11-04 (CST) 17:00 open", ct(2026, 11, 4, 17, 0, 0), O);
    expect("Sun 2026-11-01 17:00 (CST) open", ct(2026, 11, 1, 17, 0, 0), O);
    expect("Sun 2026-11-01 16:59 (CST) flatten", ct(2026, 11, 1, 16, 59, 0), F);

    // Config: leads are respected, 0 disables everything, nonsense is clamped.
    expect("lead 10/2: Wed 15:49:59 open", ct(2026, 9, 23, 15, 49, 59), 10, 2, O);
    expect("lead 10/2: Wed 15:50 no new entries", ct(2026, 9, 23, 15, 50, 0), 10, 2, N);
    expect("lead 10/2: Wed 15:58 flatten", ct(2026, 9, 23, 15, 58, 0), 10, 2, F);
    expect("flatten lead 0 disables it: Wed 15:56 open", ct(2026, 9, 23, 15, 56, 0), 15, 0, O);
    expect("flatten lead 0 disables it: Saturday is open too", ct(2026, 9, 26, 12, 0, 0), 15, 0, O);
    expect("negative flatten lead disables it", ct(2026, 9, 23, 15, 56, 0), 15, -3, O);
    expect("entry lead below flatten lead is raised to it: no gap or inversion, Wed 15:54 open",
        ct(2026, 9, 23, 15, 54, 0), 1, 5, O);
    expect("...and the flatten still starts at 15:55", ct(2026, 9, 23, 15, 55, 0), 1, 5, F);
    expect("absurd lead is clamped to 600 min: Wed 05:59 open", ct(2026, 9, 23, 5, 59, 0), 9999, 9999, O);
    expect("...and 06:00 flattens", ct(2026, 9, 23, 6, 0, 0), 9999, 9999, F);
    expect("a live-test lead of 400/405: 09:14:59 open", ct(2026, 9, 23, 9, 14, 59), 405, 400, O);
    expect("...09:15 the entry block starts", ct(2026, 9, 23, 9, 15, 0), 405, 400, N);
    expect("...09:19:59 still entry block only", ct(2026, 9, 23, 9, 19, 59), 405, 400, N);
    expect("...09:20 the flatten window starts", ct(2026, 9, 23, 9, 20, 0), 405, 400, F);
    expect("...and trading resumes at the normal 17:00 reopen", ct(2026, 9, 23, 17, 0, 0), 405, 400, O);

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all TradingWindow synthetic checks passed.");
  }
}
