package com.flow.rt;

import com.flow.core.ClockEvent;
import com.flow.core.Event;
import com.flow.core.TickEvent;
import com.flow.flow.VolumeProfileView;
import com.flow.flow.ZoneView;

import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.profile.VolumeProfile;
import com.motivewave.platform.sdk.profile.VolumeRow;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Wraps the SDK's own sdk.profile.VolumeProfile (D-36) with the E-3
 * memory-rotation fix: the SDK object is kept alive only for a bounded
 * window, then its aggregate row volumes (not trade-level detail) are
 * merged into our own persistent bucket accumulator and it is discarded.
 * POC/VAH/VAL/HVN/LVN are computed by us from the merged running totals
 * every tick, not read off the SDK object directly -- that object only
 * ever reflects one rotation window, never the full session.
 *
 * D-43: this feature cannot be constructed outside a running MotiveWave
 * session (it needs a real Instrument), so it cannot go through
 * ReplayHarness. That is an accepted, documented gap -- see D-43 -- not
 * something this class works around.
 *
 * Everything here runs on the sequencer's single drain thread only
 * (onEvent is only ever called from there, same as every Feature) --
 * deliberately no synchronization anywhere in this class, matching the
 * architectural point of the single-writer design (README "The event
 * stream").
 */
final class SdkVolumeProfileFeature implements VolumeProfileView {
  // v1 defaults, derived directly from the E-3 live measurement
  // (../motivewave/docs/dynamic/findings.md, 2026-09-15): ~1.1-1.2 MB
  // retained per tick, 300MB guard tripped at 317 ticks. Rotating well
  // before that bounds peak SDK-object growth to roughly 150-180MB.
  // Expect this to be revised once measured under this feature
  // specifically rather than the raw diagnostic probe.
  private static final int ROTATE_EVERY_TICKS = 150;
  private static final long ROTATE_EVERY_MS = 3 * 60_000L;

  private static final double VALUE_AREA_PCT = 0.70; // D-22 standard; D-37 accepts "close enough" parity
  private static final int HVN_LVN_WINDOW = 5;
  private static final double HVN_THRESHOLD = 1.5;
  private static final double LVN_THRESHOLD = 0.5;
  private static final int MIN_BINS_FOR_HVN_LVN = 3;
  // Same algorithm and same defaults as ../FLOW/flow/features/volume_profile.py
  // (already validated accurate on real data) -- ported, not reinvented.

  private final String id;
  private final Instrument instrument;
  private final PriceCodec codec;
  private final int rangeTicks;

  private VolumeProfile current;
  private long ticksSinceRotation = 0;
  private long lastRotationEventTimeMs = -1;
  private boolean ready = false;

  /** bucketKeyTicks -> [askVolume, bidVolume], survives rotation, never holds a raw tick. */
  private final Map<Integer, double[]> persistentBuckets = new TreeMap<>();
  private Map<Integer, double[]> lastCombined = Map.of();

  private Integer poc;
  private Integer vah;
  private Integer val;
  private double totalVolume;
  private double totalDelta;
  private List<ZoneView> zones = List.of();

  /**
   * Published after every recompute so a MotiveWave-invoked callback
   * thread (e.g. FlowRuntimeStudy.onTick, for drawing) can read a
   * consistent, immutable view of this feature's state without racing
   * the drain thread that owns it. Standard "immutable object published
   * via volatile" pattern -- the volatile write/read pair is what
   * supplies the happens-before edge; nothing else in this class needs
   * one, since everything else is drain-thread-only (see class javadoc).
   * Drawing must run from a MotiveWave callback thread specifically, not
   * an independently spawned one -- confirmed the hard way in
   * ../../motivewave/docs/dynamic/findings.md (the untagged addFigure/
   * clearFigures overloads only work called synchronously from a
   * platform-invoked callback).
   */
  private volatile VolumeProfileSnapshot snapshot =
      new VolumeProfileSnapshot(null, null, null, List.of(), 0, 0);

  /** Zone identity (D-38): id -> [lowKeyTicks, highKeyTicks], tracked per kind. */
  private final Map<String, int[]> lvnRanges = new LinkedHashMap<>();
  private final Map<String, int[]> hvnRanges = new LinkedHashMap<>();
  private int nextZoneSeq = 0;

  // Diagnostic-only logging so live output can be compared against the
  // chart's built-in study (same purpose as E-2), without reading this
  // feature's state from any thread other than the drain thread that
  // owns it -- this writes from inside onEvent(), which IS that thread.
  private final PrintWriter log;
  private int ticksSinceLog = 0;
  private static final int LOG_EVERY_TICKS = 50;

  SdkVolumeProfileFeature(String id, Instrument instrument, PriceCodec codec, int rangeTicks) {
    this.id = id;
    this.instrument = instrument;
    this.codec = codec;
    this.rangeTicks = rangeTicks;
    this.current = newSdkProfile(System.currentTimeMillis());
    PrintWriter w;
    try {
      w = new PrintWriter(new FileWriter(
          "C:/yadvendra/trading/FLOW_V2/logs/volume_profile_feature.log", true));
      w.println("# feature start " + System.currentTimeMillis() + " id=" + id + " rangeTicks=" + rangeTicks);
      w.flush();
    } catch (IOException e) {
      w = null;
    }
    this.log = w;
  }

  private VolumeProfile newSdkProfile(long startTimeMs) {
    return new VolumeProfile(startTimeMs, startTimeMs + 12L * 3600_000L, instrument, rangeTicks);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public boolean isReady() {
    return ready;
  }

  @Override
  public String notReadyReason() {
    return ready ? null : "no ticks processed yet (D-37: forward-only from attach, no historical warm-start)";
  }

  @Override
  public void onEvent(Event e) {
    if (e instanceof TickEvent te) {
      onTick(te);
    } else if (e instanceof ClockEvent) {
      maybeRotate(e.eventTimeMs()); // time-based fallback so a quiet tape still bounds SDK-object age
    }
  }

  private void onTick(TickEvent te) {
    current.onTick(TickAdapter.wrap(te, codec));
    ticksSinceRotation++;
    ready = true;
    maybeRotate(te.eventTimeMs());
    recompute();
    maybeLog(te.eventTimeMs());
  }

  private void maybeLog(long nowEventTimeMs) {
    if (log == null) return;
    if (++ticksSinceLog < LOG_EVERY_TICKS) return;
    ticksSinceLog = 0;
    log.println(nowEventTimeMs
        + " POC=" + priceStr(poc) + " VAH=" + priceStr(vah) + " VAL=" + priceStr(val)
        + " (ticks: poc=" + poc + " vah=" + vah + " val=" + val + ")"
        + " totalVolume=" + totalVolume + " totalDelta=" + totalDelta
        + " bucketCount=" + lastCombined.size()
        + " zones=" + zonesWithPrices());
    log.flush();
  }

  private String priceStr(Integer ticks) {
    return ticks == null ? "null" : String.format("%.4f", codec.fromTicks(ticks));
  }

  private String zonesWithPrices() {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < zones.size(); i++) {
      ZoneView z = zones.get(i);
      if (i > 0) sb.append(", ");
      sb.append(z.kind()).append(' ').append(z.id()).append(' ')
          .append(priceStr(z.lowPriceTicks())).append('-').append(priceStr(z.highPriceTicks()));
    }
    return sb.append(']').toString();
  }

  private void maybeRotate(long nowEventTimeMs) {
    if (lastRotationEventTimeMs < 0) {
      lastRotationEventTimeMs = nowEventTimeMs;
      return;
    }
    boolean tickTrigger = ticksSinceRotation >= ROTATE_EVERY_TICKS;
    boolean timeTrigger = (nowEventTimeMs - lastRotationEventTimeMs) >= ROTATE_EVERY_MS;
    if (tickTrigger || timeTrigger) rotate(nowEventTimeMs);
  }

  @SuppressWarnings("unchecked") // see mergeRowsInto's note on VolumeProfile.getRows()'s raw List
  private void rotate(long nowEventTimeMs) {
    mergeRowsInto(persistentBuckets, current.getRows());
    current = newSdkProfile(nowEventTimeMs);
    ticksSinceRotation = 0;
    lastRotationEventTimeMs = nowEventTimeMs;
  }

  // VolumeProfile.getRows() returns a raw List in this jar (not List<VolumeRow>
  // -- another spot where the compiled jar's generics don't match the
  // javadoc, same class of mismatch as T-6/TickAdapter's note above).
  // Safe here: every element is confirmed a VolumeRow by inspection of
  // the SDK source's own usage pattern, not guessed.
  @SuppressWarnings("unchecked")
  private void mergeRowsInto(Map<Integer, double[]> target, List<VolumeRow> rows) {
    if (rows == null) return;
    for (VolumeRow row : rows) {
      int key = bucketKey(row.getRowPrice());
      double[] acc = target.computeIfAbsent(key, k -> new double[2]);
      acc[0] += row.getAskVolume();
      acc[1] += row.getBidVolume();
    }
  }

  private int bucketKey(double price) {
    int t = codec.toTicks(price);
    return Math.round((float) t / rangeTicks) * rangeTicks;
  }

  @SuppressWarnings("unchecked") // see mergeRowsInto's note on VolumeProfile.getRows()'s raw List
  private void recompute() {
    Map<Integer, double[]> combined = new TreeMap<>();
    for (Map.Entry<Integer, double[]> en : persistentBuckets.entrySet()) {
      combined.put(en.getKey(), en.getValue().clone());
    }
    mergeRowsInto(combined, current.getRows());
    lastCombined = combined;

    if (combined.isEmpty()) {
      poc = null;
      vah = null;
      val = null;
      totalVolume = 0;
      totalDelta = 0;
      zones = List.of();
      snapshot = new VolumeProfileSnapshot(null, null, null, List.of(), 0, 0);
      return;
    }

    List<Integer> prices = new ArrayList<>(combined.keySet());
    double[] vols = new double[prices.size()];
    double total = 0;
    double delta = 0;
    int pocIdx = 0;
    double pocVol = -1;
    for (int i = 0; i < prices.size(); i++) {
      double[] ab = combined.get(prices.get(i));
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

    this.poc = prices.get(pocIdx);
    this.vah = prices.get(hi);
    this.val = prices.get(lo);
    this.totalVolume = total;
    this.totalDelta = delta;
    this.zones = buildZones(lvnKeys, hvnKeys);
    this.snapshot = new VolumeProfileSnapshot(poc, vah, val, zones, totalVolume, totalDelta);
  }

  private List<ZoneView> buildZones(List<Integer> lvnKeys, List<Integer> hvnKeys) {
    List<ZoneView> result = new ArrayList<>();
    result.addAll(matchClusters(cluster(lvnKeys), lvnRanges, ZoneView.Kind.LVN, "lvn"));
    result.addAll(matchClusters(cluster(hvnKeys), hvnRanges, ZoneView.Kind.HVN, "hvn"));
    return result;
  }

  /** Contiguous runs of bucket keys (consecutive by exactly rangeTicks). */
  private List<int[]> cluster(List<Integer> sortedKeys) {
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

  /**
   * D-38's zone-identity matching: greedy largest-overlap-wins across
   * (old id, new cluster) pairs, each used at most once. An unmatched new
   * cluster gets a fresh id (appeared); an unmatched old id is dropped
   * (dissolved or merged away). No event fires here yet -- that's D-38's
   * trigger-layer wiring, a deliberately separate later pass; this method
   * only has to keep ids stable, which is the data contract zones()
   * promises.
   */
  private List<ZoneView> matchClusters(List<int[]> newClusters, Map<String, int[]> prevRanges,
                                        ZoneView.Kind kind, String prefix) {
    record Pair(String oldId, int newIdx, int overlap) {}
    List<Pair> pairs = new ArrayList<>();
    for (Map.Entry<String, int[]> old : prevRanges.entrySet()) {
      for (int ni = 0; ni < newClusters.size(); ni++) {
        int[] nc = newClusters.get(ni);
        int overlap = Math.min(old.getValue()[1], nc[1]) - Math.max(old.getValue()[0], nc[0]);
        if (overlap >= 0) pairs.add(new Pair(old.getKey(), ni, overlap));
      }
    }
    pairs.sort((a, b) -> Integer.compare(b.overlap(), a.overlap()));

    String[] assignedId = new String[newClusters.size()];
    Set<String> usedOldIds = new HashSet<>();
    for (Pair p : pairs) {
      if (assignedId[p.newIdx()] != null) continue;
      if (usedOldIds.contains(p.oldId())) continue;
      assignedId[p.newIdx()] = p.oldId();
      usedOldIds.add(p.oldId());
    }

    Map<String, int[]> nextRanges = new LinkedHashMap<>();
    List<ZoneView> out = new ArrayList<>();
    for (int ni = 0; ni < newClusters.size(); ni++) {
      String zid = assignedId[ni];
      if (zid == null) zid = prefix + "-" + (nextZoneSeq++);
      int[] nc = newClusters.get(ni);
      nextRanges.put(zid, nc);
      out.add(new ZoneView(zid, kind, nc[0], nc[1]));
    }
    prevRanges.clear();
    prevRanges.putAll(nextRanges);
    return out;
  }

  @Override
  public Integer poc() {
    return poc;
  }

  @Override
  public Integer vah() {
    return vah;
  }

  @Override
  public Integer val() {
    return val;
  }

  @Override
  public double totalVolume() {
    return totalVolume;
  }

  @Override
  public double totalDelta() {
    return totalDelta;
  }

  @Override
  public Double volumeAt(int bucketPriceTicks) {
    double[] ab = lastCombined.get(bucketPriceTicks);
    return ab == null ? null : ab[0] + ab[1];
  }

  @Override
  public List<ZoneView> zones() {
    return zones;
  }

  /** Safe to call from any thread -- see the `snapshot` field's javadoc. */
  VolumeProfileSnapshot snapshot() {
    return snapshot;
  }

  void closeLog() {
    if (log != null) {
      log.flush();
      log.close();
    }
  }
}
