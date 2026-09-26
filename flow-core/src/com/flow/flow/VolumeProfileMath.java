package com.flow.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The volume profile's pure statistics -- POC, value area (VAH/VAL), and the HVN/LVN bucket lists -- extracted
 * VERBATIM from flow-runtime's SdkVolumeProfileFeature.recompute() (D-104) so they can be tested and replayed
 * without a MotiveWave Instrument. The SDK-bound feature still owns the bucket accumulation, rotation and zone
 * identity tracking; it hands this class the combined price -> [askVolume, bidVolume] map and gets numbers back.
 *
 * Same algorithm and defaults as ../FLOW/flow/features/volume_profile.py (already validated accurate on real data):
 * value area = 70% expanded outward from the POC (ties go up), HVN/LVN by a rolling local average over 5 buckets
 * each side (>= 1.5x = HVN, <= 0.5x = LVN).
 *
 * Keys are price ticks (the codec's tick space); the caller's bucket width is rangeTicks.
 */
public final class VolumeProfileMath {
  public static final double VALUE_AREA_PCT = 0.70; // D-22 standard; D-37 accepts "close enough" parity
  public static final int HVN_LVN_WINDOW = 5;
  public static final double HVN_THRESHOLD = 1.5;
  public static final double LVN_THRESHOLD = 0.5;
  public static final int MIN_BINS_FOR_HVN_LVN = 3;

  private VolumeProfileMath() {}

  /** poc/vah/val are bucket keys; the key lists are ascending. */
  public record Result(int poc, int vah, int val, double totalVolume, double totalDelta,
                       List<Integer> lvnKeys, List<Integer> hvnKeys) {}

  /** Null for an empty profile. Input values are [askVolume, bidVolume]; the map is not modified. */
  public static Result compute(Map<Integer, double[]> combined) {
    if (combined.isEmpty()) return null;
    Map<Integer, double[]> sorted = combined instanceof SortedMap ? combined : new TreeMap<>(combined);

    List<Integer> prices = new ArrayList<>(sorted.keySet());
    double[] vols = new double[prices.size()];
    double total = 0;
    double delta = 0;
    int pocIdx = 0;
    double pocVol = -1;
    for (int i = 0; i < prices.size(); i++) {
      double[] ab = sorted.get(prices.get(i));
      double v = ab[0] + ab[1];
      vols[i] = v;
      total += v;
      delta += (ab[0] - ab[1]);
      if (v > pocVol) {
        pocVol = v;
        pocIdx = i;
      }
    }

    // Value-area expansion from POC -- identical algorithm to
    // ../FLOW/flow/features/volume_profile.py's compute_value_area().
    int lo = pocIdx, hi = pocIdx;
    double acc = vols[pocIdx];
    double target = total * VALUE_AREA_PCT;
    while (acc < target && (lo > 0 || hi < prices.size() - 1)) {
      double below = lo > 0 ? vols[lo - 1] : -1;
      double above = hi < prices.size() - 1 ? vols[hi + 1] : -1;
      if (above >= below) {
        hi++;
        acc += vols[hi];
      } else {
        lo--;
        acc += vols[lo];
      }
    }

    // HVN/LVN via rolling local average -- identical algorithm to
    // compute_rolling_local_average()/compute_hvn_lvn() in the same file.
    List<Integer> lvnKeys = new ArrayList<>();
    List<Integer> hvnKeys = new ArrayList<>();
    if (prices.size() >= MIN_BINS_FOR_HVN_LVN) {
      for (int i = 0; i < prices.size(); i++) {
        double sum = 0;
        int count = 0;
        for (int j = Math.max(0, i - HVN_LVN_WINDOW); j < i; j++) {
          sum += vols[j];
          count++;
        }
        for (int j = i + 1; j < Math.min(prices.size(), i + 1 + HVN_LVN_WINDOW); j++) {
          sum += vols[j];
          count++;
        }
        double avg = count == 0 ? 0 : sum / count;
        if (avg == 0) {
          if (vols[i] > 0) hvnKeys.add(prices.get(i));
          continue;
        }
        if (vols[i] >= HVN_THRESHOLD * avg) hvnKeys.add(prices.get(i));
        else if (vols[i] <= LVN_THRESHOLD * avg) lvnKeys.add(prices.get(i));
      }
    }

    return new Result(prices.get(pocIdx), prices.get(hi), prices.get(lo), total, delta, lvnKeys, hvnKeys);
  }

  /** Contiguous runs of bucket keys (consecutive by exactly rangeTicks), as [firstKey, lastKey] pairs. */
  public static List<int[]> cluster(List<Integer> sortedKeys, int rangeTicks) {
    List<int[]> clusters = new ArrayList<>();
    int i = 0;
    while (i < sortedKeys.size()) {
      int start = sortedKeys.get(i);
      int end = start;
      int j = i + 1;
      while (j < sortedKeys.size() && sortedKeys.get(j) == end + rangeTicks) {
        end = sortedKeys.get(j);
        j++;
      }
      clusters.add(new int[]{start, end});
      i = j;
    }
    return clusters;
  }
}
