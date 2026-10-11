package com.flow.orderflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A volume profile over a time span (gapAnswers §7), rows of {@code profileRowTicks} (E4: 4). Incremental adds;
 * levels are recomputed lazily when asked for after new volume (cheap: a session has a few hundred rows).
 *
 * POC = highest-volume row (ties: the one nearest the profile's middle, then the lower). Value area = 70 % expanding
 * from the POC by the larger adjacent row (ties go up). On a 3-row moving average: HVN = highest row within
 * +-hvnSpanRows and >= hvnMult x mean row volume; LVN = between two neighbouring HVNs, the minimum is <=
 * lvnPeakRatio x the smaller HVN, extent = the run of rows under that line, at least lvnMinRows wide (build note B1).
 * Mean row volume is over every row between the profile's low and high (empty rows count as 0).
 */
public final class Profile {
  public enum Shape { NONE, D, P, B_LOWER, DOUBLE }

  public record Lvn(int lowTicks, int highTicks, int minRowPriceTicks, double depth, int widthRows, double score) {}

  public final String label;
  public final long startMs;
  final int rowTicks;
  private final TreeMap<Integer, Double> rows = new TreeMap<>();
  private double total;
  private long lastAddMs;
  private boolean dirty = true;

  private Integer poc, vah, val;
  private final List<Integer> hvnRows = new ArrayList<>();
  private final List<Lvn> lvns = new ArrayList<>();
  private double meanRow;
  /** POC "naked" flag: true until price trades back at the POC after this profile completed (G9). */
  public boolean completed;
  public boolean pocRevisited;
  public long pocRevisitedAtMs = -1;

  Profile(String label, long startMs, int rowTicks) {
    this.label = label;
    this.startMs = startMs;
    this.rowTicks = rowTicks;
  }

  void add(long t, int priceTicks, double size) {
    rows.merge(Math.floorDiv(priceTicks, rowTicks), size, Double::sum);
    total += size;
    lastAddMs = t;
    dirty = true;
  }

  public double totalVolume() { return total; }
  public long lastAddMs() { return lastAddMs; }
  public boolean isEmpty() { return total <= 0; }
  public boolean mature(long now, OfConfig c) {
    return completed || (now - startMs >= c.profileMatureMs && total >= c.profileMatureVolume);
  }

  private int price(int row) { return row * rowTicks; }

  public Integer poc(OfConfig c) { recompute(c); return poc == null ? null : price(poc) + rowTicks / 2; }
  public Integer vah(OfConfig c) { recompute(c); return vah == null ? null : price(vah) + rowTicks - 1; }
  public Integer val(OfConfig c) { recompute(c); return val == null ? null : price(val); }
  public List<Lvn> lvns(OfConfig c) { recompute(c); return lvns; }
  public double meanRow(OfConfig c) { recompute(c); return meanRow; }
  public List<Integer> hvnPrices(OfConfig c) {
    recompute(c);
    List<Integer> out = new ArrayList<>();
    for (int r : hvnRows) out.add(price(r) + rowTicks / 2);
    return out;
  }
  public double volumeAtPrice(int priceTicks) {
    Double v = rows.get(Math.floorDiv(priceTicks, rowTicks));
    return v == null ? 0 : v;
  }
  public Integer high() { return rows.isEmpty() ? null : price(rows.lastKey()) + rowTicks - 1; }
  public Integer low() { return rows.isEmpty() ? null : price(rows.firstKey()); }

  private void recompute(OfConfig c) {
    if (!dirty) return;
    dirty = false;
    poc = vah = val = null;
    hvnRows.clear();
    lvns.clear();
    if (rows.isEmpty()) return;
    int lo = rows.firstKey(), hi = rows.lastKey();
    int n = hi - lo + 1;
    double[] v = new double[n];
    for (Map.Entry<Integer, Double> e : rows.entrySet()) v[e.getKey() - lo] = e.getValue();
    meanRow = total / n;
    // POC
    double mid = (n - 1) / 2.0;
    int p = 0;
    for (int i = 1; i < n; i++) {
      if (v[i] > v[p] || (v[i] == v[p] && Math.abs(i - mid) < Math.abs(p - mid))) p = i;
    }
    poc = lo + p;
    // value area
    double target = c.valueAreaShare * total, acc = v[p];
    int a = p, b = p;
    while (acc < target && (a > 0 || b < n - 1)) {
      double up = b < n - 1 ? v[b + 1] : -1, down = a > 0 ? v[a - 1] : -1;
      if (up >= down) { b++; acc += v[b]; } else { a--; acc += v[a]; }
    }
    val = lo + a;
    vah = lo + b;
    // Real intraday profiles are smooth: compare on a 3-row moving average so one noisy row is neither a peak nor a
    // gap (build note B1, replay 2026-09-29: the mean-based LVN rule found no LVN at all).
    double[] sm = new double[n];
    for (int i = 0; i < n; i++) {
      double s = 0; int k = 0;
      for (int j = Math.max(0, i - 1); j <= Math.min(n - 1, i + 1); j++) { s += v[j]; k++; }
      sm[i] = s / k;
    }
    // HVN: highest smoothed row within +-hvnSpanRows, at least hvnMult x the mean row volume
    List<Integer> peaks = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (sm[i] < c.hvnMult * meanRow) continue;
      boolean isMax = true;
      for (int j = Math.max(0, i - c.hvnSpanRows); j <= Math.min(n - 1, i + c.hvnSpanRows); j++) {
        if (sm[j] > sm[i] || (sm[j] == sm[i] && j < i)) { isMax = false; break; }
      }
      if (isMax) { peaks.add(i); hvnRows.add(lo + i); }
    }
    // LVN (course: the gap between two HVNs, "deeper = stronger"): between each pair of neighbouring peaks, the
    // smoothed minimum must be <= lvnPeakRatio x the smaller peak; its extent is the run of rows under that line.
    for (int k = 0; k + 1 < peaks.size(); k++) {
      int h1 = peaks.get(k), h2 = peaks.get(k + 1);
      double smaller = Math.min(sm[h1], sm[h2]);
      int m = -1;
      for (int i = h1 + 1; i < h2; i++) if (m < 0 || sm[i] < sm[m]) m = i;
      if (m < 0 || sm[m] > c.lvnPeakRatio * smaller) continue;
      double line = c.lvnPeakRatio * smaller;
      int s = m, e = m;
      while (s - 1 > h1 && sm[s - 1] <= line) s--;
      while (e + 1 < h2 && sm[e + 1] <= line) e++;
      int width = e - s + 1;
      if (width < c.lvnMinRows) continue;
      double depth = smaller <= 0 ? 0 : Math.max(0, 1 - sm[m] / smaller);
      lvns.add(new Lvn(price(lo + s), price(lo + e) + rowTicks - 1, price(lo + m), depth, width, 0));
    }
    // rank score = 0.6 depth + 0.4 normalised width (gapAnswers §7)
    int maxW = 1;
    for (Lvn l : lvns) maxW = Math.max(maxW, l.widthRows());
    for (int k = 0; k < lvns.size(); k++) {
      Lvn l = lvns.get(k);
      lvns.set(k, new Lvn(l.lowTicks(), l.highTicks(), l.minRowPriceTicks(), l.depth(), l.widthRows(),
          0.6 * l.depth() + 0.4 * l.widthRows() / (double) maxW));
    }
  }

  /** Shape of the profile (G14 / original Q5.5); NONE until shapeMinAgeMs old. */
  public Shape shape(long now, OfConfig c) {
    recompute(c);
    if (poc == null || (!completed && now - startMs < c.shapeMinAgeMs)) return Shape.NONE;
    int lo = rows.firstKey(), hi = rows.lastKey();
    int n = hi - lo + 1;
    if (n < 6) return Shape.NONE;
    // double distribution: two HVN peaks with an LVN between them
    if (hvnRows.size() >= 2) {
      int r1 = hvnRows.get(0), r2 = hvnRows.get(hvnRows.size() - 1);
      double v1 = rows.getOrDefault(r1, 0.0), v2 = rows.getOrDefault(r2, 0.0);
      double smaller = Math.min(v1, v2), larger = Math.max(v1, v2);
      double minBetween = Double.MAX_VALUE;
      for (int r = r1 + 1; r < r2; r++) minBetween = Math.min(minBetween, rows.getOrDefault(r, 0.0));
      if (smaller >= 0.7 * larger && minBetween <= 0.5 * smaller) return Shape.DOUBLE;
    }
    double pos = (poc - lo) / (double) (n - 1);
    double midPrice = lo + (n - 1) / 2.0, above = 0;
    for (Map.Entry<Integer, Double> e : rows.entrySet()) if (e.getKey() > midPrice) above += e.getValue();
    double aboveShare = total <= 0 ? 0.5 : above / total;
    if (pos > 2.0 / 3 && aboveShare >= 0.6) return Shape.P;
    if (pos < 1.0 / 3 && aboveShare <= 0.4) return Shape.B_LOWER;
    if (pos >= 1.0 / 3 && pos <= 2.0 / 3) return Shape.D;
    return Shape.NONE;
  }
}
