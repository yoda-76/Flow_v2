package com.flow.bars.ind;

import com.flow.bars.CtTime;

import java.time.LocalDate;

/**
 * VWAP that restarts every Chicago calendar date at a fixed CT minute, fed with 1-minute bars (volume-weighted typical
 * price (h+l+c)/3, or the close). Same semantics as the VWAP blocks of the Python backtests (vwap_adx.py,
 * vault_break.py):
 *  - anchorMinute >= 0: bars of the date with minute-of-day >= anchorMinute accumulate; value is null before that.
 *  - anchorMinute < 0 (e.g. -420 = 17:00 CT the day before): the previous CT date's bars with minute-of-day >=
 *    1440+anchorMinute seed the new date's sums (only if that date is exactly the previous calendar day), and the
 *    date's own bars accumulate from 00:00.
 * Value is null while accumulated volume is 0. Prices are whatever unit the caller feeds (ticks).
 */
public final class AnchoredVwap {
  private final int anchorMinute;
  private final boolean useClose;

  private LocalDate date = null;
  private double pv = 0, v = 0;
  private double nextPv = 0, nextV = 0;   // this date's evening bars, for the NEXT date's seed (negative anchor only)
  private Double value = null;

  public AnchoredVwap(int anchorMinute, boolean useClose) {
    if (anchorMinute < -1440 || anchorMinute >= 1440) throw new IllegalArgumentException("anchorMinute " + anchorMinute);
    this.anchorMinute = anchorMinute;
    this.useClose = useClose;
  }

  public AnchoredVwap(int anchorMinute) {
    this(anchorMinute, false);
  }

  public void update(long startMs, double h, double l, double c, double volume) {
    LocalDate d = CtTime.ctDate(startMs);
    int m = CtTime.ctMinuteOfDay(startMs);
    if (!d.equals(date)) {
      boolean seed = anchorMinute < 0 && date != null && d.equals(date.plusDays(1));
      pv = seed ? nextPv : 0;
      v = seed ? nextV : 0;
      nextPv = 0;
      nextV = 0;
      date = d;
      value = null;
    }
    double px = useClose ? c : (h + l + c) / 3.0;
    if (anchorMinute < 0 && m >= 1440 + anchorMinute) {
      nextPv += px * volume;
      nextV += volume;
    }
    int dayAnchor = Math.max(anchorMinute, 0);
    if (m < dayAnchor) {
      value = null;
      return;
    }
    pv += px * volume;
    v += volume;
    value = v > 0 ? pv / v : null;
  }

  /** VWAP after the last bar fed; null before the anchor or while volume is zero. */
  public Double value() {
    return value;
  }
}
