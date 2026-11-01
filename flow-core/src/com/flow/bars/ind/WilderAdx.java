package com.flow.bars.ind;

/**
 * Wilder ADX(n), incremental. The algorithm is exactly analysis/gb_nq_backtest/vwap_adx.py compute_adx (the Python
 * backtest the live strategy must reproduce), including: running over one continuous bar list with no reset and no
 * gap handling; the first n smoothed values are plain sums of bars 1..n; ADX's first value is the mean of the first n
 * DX values.
 */
public final class WilderAdx {
  private final int n;
  private int i = -1;                 // index of the last bar fed
  private double pH, pL, pC;
  private double sTr, sP, sM;
  private double dxSum;
  private int dxCount;
  private Double adx = null;

  public WilderAdx(int n) {
    if (n < 1) throw new IllegalArgumentException("n < 1");
    this.n = n;
  }

  public void update(double h, double l, double c) {
    i++;
    if (i == 0) {
      pH = h; pL = l; pC = c;
      return;
    }
    double tr = Math.max(h - l, Math.max(Math.abs(h - pC), Math.abs(l - pC)));
    double up = h - pH;
    double dn = pL - l;
    double pdm = (up > dn && up > 0) ? up : 0.0;
    double mdm = (dn > up && dn > 0) ? dn : 0.0;
    pH = h; pL = l; pC = c;
    if (i < n) {
      sTr += tr; sP += pdm; sM += mdm;
      return;
    }
    if (i == n) {
      sTr += tr; sP += pdm; sM += mdm;
    } else {
      sTr = sTr - sTr / n + tr;
      sP = sP - sP / n + pdm;
      sM = sM - sM / n + mdm;
    }
    double pdi = sTr != 0 ? 100.0 * sP / sTr : 0.0;
    double mdi = sTr != 0 ? 100.0 * sM / sTr : 0.0;
    double sm = pdi + mdi;
    double dx = sm != 0 ? 100.0 * Math.abs(pdi - mdi) / sm : 0.0;
    if (adx == null) {
      dxSum += dx;
      dxCount++;
      if (dxCount == n) adx = dxSum / n;
    } else {
      adx = (adx * (n - 1) + dx) / n;
    }
  }

  /** Null until n DX values exist (bar index 2n-1 and later). */
  public Double value() {
    return adx;
  }
}
