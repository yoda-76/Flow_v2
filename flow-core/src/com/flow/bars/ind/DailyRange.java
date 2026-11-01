package com.flow.bars.ind;

import com.flow.bars.CtTime;

import java.time.LocalDate;
import java.util.ArrayDeque;

/**
 * Mean daily range ("volatility") from 1-minute bars, as in the Python backtests (vault_break.py build_vol with
 * vol_ma=sma, midnight session; overnight_orb.py): per CT date, the session is the 1m bars whose start minute is in
 * [sessionStartMin, sessionEndMin) -- (0, 960) is 00:00-16:00 CT. A finished session's TR is
 * max(H, prevClose) - min(L, prevClose) with prevClose = the previous finished session's last close (the first
 * session, or trMode HL: H - L). smaVol() is the mean TR of the last `len` finished sessions, so "today" is never
 * included (null until `len` exist).
 *
 * A session is finished by the first bar (ANY minute) of a LATER CT date, not only an in-session one: the Python
 * code counts sessions with date strictly before the bar's date, and evening bars (e.g. Sunday 17:00 CT) of a
 * date with no session must already see Friday's session finished.
 */
public final class DailyRange {
  public enum TrMode { TR, HL }

  private final int startMin, endMin, len;
  private final TrMode mode;
  private final ArrayDeque<Double> trs = new ArrayDeque<>();

  private LocalDate curDate = null;
  private double sessH, sessL, sessC;
  private boolean haveSession = false;
  private Double prevClose = null;
  private double lastH = Double.NaN, lastL = Double.NaN, lastC = Double.NaN;
  private boolean haveLast = false;
  private Double smaVol = null;

  public DailyRange(int sessionStartMin, int sessionEndMin, int len, TrMode trMode) {
    if (len < 1) throw new IllegalArgumentException("len < 1");
    this.startMin = sessionStartMin;
    this.endMin = sessionEndMin;
    this.len = len;
    this.mode = trMode;
  }

  public void update(long startMs, double h, double l, double c) {
    LocalDate d = CtTime.ctDate(startMs);
    int m = CtTime.ctMinuteOfDay(startMs);
    if (curDate != null && d.isAfter(curDate)) {
      finish();
    }
    curDate = d;
    if (m < startMin || m >= endMin) return;
    if (!haveSession) {
      haveSession = true;
      sessH = h; sessL = l; sessC = c;
    } else {
      sessH = Math.max(sessH, h);
      sessL = Math.min(sessL, l);
      sessC = c;
    }
  }

  private void finish() {
    if (!haveSession) return;
    double tr = (mode == TrMode.TR && prevClose != null)
        ? Math.max(sessH, prevClose) - Math.min(sessL, prevClose)
        : sessH - sessL;
    prevClose = sessC;
    lastH = sessH; lastL = sessL; lastC = sessC;
    haveLast = true;
    trs.addLast(tr);
    if (trs.size() > len) trs.removeFirst();
    if (trs.size() == len) {
      double s = 0;
      for (double t : trs) s += t;
      smaVol = s / len;
    }
    haveSession = false;
  }

  /** Mean TR of the last len finished sessions (today excluded); null until len exist. */
  public Double smaVol() {
    return smaVol;
  }

  /** Last FINISHED session's high / low / last close; NaN before the first session finishes. */
  public double lastSessionHigh() { return lastH; }
  public double lastSessionLow() { return lastL; }
  public double lastSessionClose() { return lastC; }
  public boolean hasFinishedSession() { return haveLast; }
}
