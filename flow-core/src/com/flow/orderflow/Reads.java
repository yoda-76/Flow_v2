package com.flow.orderflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The order-flow reads for one idea direction at one location (gapAnswers §4), evaluated over the last
 * readValidBars closed bars (G0.2). Reads are grouped so correlated evidence is not double-counted (R4):
 * ABS = {failed aggression, absorption, sweep-reversal/gap}, EXH = {exhaustion, finished auction},
 * FLIP = {delta flip, delta shift}, IMB = {stacked imbalance, candle dominance}. Supporting reads (time at level,
 * trapped small players, unfinished auction) are recorded but never count as a group.
 */
public final class Reads {
  public boolean abs, exh, flip, imb;
  public boolean failedAggression, absorption, liquidityEvent, exhaustion, finishedAuction, deltaFlip, deltaShift,
      stacked, dominance;
  public boolean contra;           // a read against the idea at the location (-1)
  public boolean aggressionAgainstSucceeded;
  public final List<String> names = new ArrayList<>();

  public int groups() { return (abs ? 1 : 0) + (exh ? 1 : 0) + (flip ? 1 : 0) + (imb ? 1 : 0); }

  /** True if a bar's range is within maxDist of [lo, hi]. */
  static boolean barNear(OfBar b, int lo, int hi, int maxDist) {
    return b.low <= hi + maxDist && b.high >= lo - maxDist;
  }

  static Reads compute(EvalContext x, int lo, int hi) {
    Reads r = new Reads();
    OrderFlowEngine eng = x.eng;
    OfConfig c = x.c;
    int d = x.idea.direction();
    int n = Math.min(c.readValidBars, eng.closedBars().size());
    if (n == 0) return r;
    OfBar last = eng.back(0);
    long firstIdx = eng.back(n - 1).index;
    double avgDelta = eng.avgAbsDelta(c.avgWindowBars);

    // ---- ABS: failed aggression against the idea near the location
    for (AggPrint p : eng.prints()) {
      if (p.barIndex < firstIdx || p.kind == AggPrint.Kind.SMALL) continue;
      boolean againstIdea = (p.buy ? 1 : -1) == -d;
      boolean nearLoc = p.price >= lo - c.aggressionProximityTicks && p.price <= hi + c.aggressionProximityTicks;
      if (!againstIdea || !nearLoc) continue;
      if (p.outcome == AggPrint.Outcome.FAILURE) r.failedAggression = true;
      if (p.outcome == AggPrint.Outcome.SUCCESS) r.aggressionAgainstSucceeded = true;
    }
    // ---- ABS: absorption (footprint only, A2): big delta against the idea, bar closes for the idea
    for (int k = 0; k < n; k++) {
      OfBar b = eng.back(k);
      if (!barNear(b, lo, hi, c.locationMaxDistanceTicks) || avgDelta <= 0) continue;
      boolean bigAgainst = -d * b.delta >= c.absorptionDeltaMult * avgDelta;
      boolean closesFor = d > 0 ? b.closeLocation >= 1 - c.absorptionCloseLoc : b.closeLocation <= c.absorptionCloseLoc;
      if (bigAgainst && closesFor) r.absorption = true;
    }
    // ---- ABS: liquidity events in the idea direction near the location (heatmap, when available)
    if (x.liq != null) {
      long since = eng.back(n - 1).startMs;
      for (LiquidityTracker.LiqEvent ev : x.liq.events()) {
        if (ev.timeMs() < since || ev.kind() == LiquidityTracker.EventKind.SPOOF) continue;
        boolean forIdea = ev.bullish() == (d > 0);
        boolean nearLoc = ev.wallTicks() >= lo - c.locationMaxDistanceTicks && ev.wallTicks() <= hi + c.locationMaxDistanceTicks;
        if (forIdea && nearLoc && ev.kind() != LiquidityTracker.EventKind.BREAKTHROUGH) r.liquidityEvent = true;
      }
    }
    r.abs = r.failedAggression || r.absorption || r.liquidityEvent;

    // ---- EXH: finished auction at the adverse extreme, or exhaustion (declining extreme aggression)
    for (int k = 0; k < n; k++) {
      OfBar b = eng.back(k);
      if (!barNear(b, lo, hi, c.locationMaxDistanceTicks)) continue;
      if (d > 0 ? b.finishedLow : b.finishedHigh) r.finishedAuction = true;
    }
    r.exhaustion = exhaustion(eng, d, c);
    r.exh = r.finishedAuction || r.exhaustion;

    // ---- FLIP: delta flip on the last bar, or delta shift
    OfBar prev = eng.back(1);
    if (prev != null && Math.signum(last.delta) == d && Math.signum(prev.delta) == -d) {
      boolean noNewExtreme = d > 0 ? last.low >= prev.low : last.high <= prev.high;
      r.deltaFlip = noNewExtreme;
    }
    r.deltaShift = deltaShift(eng, d, c, last);
    r.flip = r.deltaFlip || r.deltaShift;

    // ---- IMB: stacked imbalance / candle dominance in the idea direction on the last bar
    r.stacked = d > 0 ? last.stackedBull : last.stackedBear;
    r.dominance = d > 0 ? last.dominanceBull : last.dominanceBear;
    r.imb = r.stacked || r.dominance;

    // ---- contra (-1): stacked imbalance against the idea at the location, or aggression against it succeeding
    boolean stackedAgainst = (d > 0 ? last.stackedBear : last.stackedBull) && barNear(last, lo, hi, c.locationMaxDistanceTicks);
    r.contra = stackedAgainst || r.aggressionAgainstSucceeded;

    if (r.failedAggression) r.names.add("failedAggression");
    if (r.absorption) r.names.add("absorption");
    if (r.liquidityEvent) r.names.add("liquidityEvent");
    if (r.finishedAuction) r.names.add("finishedAuction");
    if (r.exhaustion) r.names.add("exhaustion");
    if (r.deltaFlip) r.names.add("deltaFlip");
    if (r.deltaShift) r.names.add("deltaShift");
    if (r.stacked) r.names.add("stacked");
    if (r.dominance) r.names.add("dominance");
    if (r.contra) r.names.add("CONTRA");
    // supporting
    int mid = (lo + hi) / 2;
    if (eng.secondsNear(mid, Math.max(c.timeAtLevelTicks, (hi - lo) / 2 + c.timeAtLevelTicks), 60 * 60_000L) * 1000L >= c.timeAtLevelLongMs) {
      r.names.add("sup:timeAtLevel");
    }
    if (trappedSmall(eng, d, c)) r.names.add("sup:trappedSmall");
    return r;
  }

  /** Last exhaustionBars bars keep printing the adverse extreme while the aggressive volume there declines. */
  static boolean exhaustion(OrderFlowEngine eng, int d, OfConfig c) {
    int k = c.exhaustionBars;
    if (eng.closedBars().size() < k) return false;
    int declines = 0;
    for (int i = k - 1; i >= 1; i--) {
      OfBar older = eng.back(i), newer = eng.back(i - 1);
      boolean extreme = d > 0 ? newer.low <= older.low : newer.high >= older.high;
      double vOld = d > 0 ? older.maxBidAtLow : older.maxAskAtHigh;
      double vNew = d > 0 ? newer.maxBidAtLow : newer.maxAskAtHigh;
      if (extreme && vNew < vOld) declines++;
    }
    return declines >= 2;
  }

  /** Close beyond the band holding deltaShiftShare of the window's idea-side delta (E5 "delta shift"). */
  static boolean deltaShift(OrderFlowEngine eng, int d, OfConfig c, OfBar last) {
    TreeMap<Integer, Double> dp = eng.deltaProfile(c.deltaProfileWindowMs);
    // E5: "when price moves below the seller-dominance area the market is shifting toward sellers" -> for a short the
    // band is where seller (negative) delta concentrated and the shift is a close below it; mirror for a long.
    List<Map.Entry<Integer, Double>> side = new ArrayList<>();
    double total = 0;
    for (Map.Entry<Integer, Double> e : dp.entrySet()) {
      if (Math.signum(e.getValue()) == d) { side.add(e); total += Math.abs(e.getValue()); }
    }
    if (total <= 0 || side.size() < 2) return false;
    side.sort((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));
    double acc = 0;
    int bandLo = Integer.MAX_VALUE, bandHi = Integer.MIN_VALUE;
    for (Map.Entry<Integer, Double> e : side) {
      acc += Math.abs(e.getValue());
      bandLo = Math.min(bandLo, e.getKey());
      bandHi = Math.max(bandHi, e.getKey() + c.footprintRowTicks - 1);
      if (acc >= c.deltaShiftShare * total) break;
    }
    return d > 0 ? last.close > bandHi : last.close < bandLo;
  }

  /** >= N small-player prints against the current move in the last bars, each followed by price going the other way. */
  static boolean trappedSmall(OrderFlowEngine eng, int d, OfConfig c) {
    if (eng.closedBars().size() < c.trappedSmallBars) return false;
    long from = eng.back(c.trappedSmallBars - 1).index;
    int count = 0;
    for (AggPrint p : eng.prints()) {
      if (p.kind != AggPrint.Kind.SMALL || p.barIndex < from) continue;
      boolean againstIdea = (p.buy ? 1 : -1) == -d;
      if (againstIdea && p.outcome == AggPrint.Outcome.FAILURE) count++;
    }
    return count >= c.trappedSmallMinCount;
  }
}
