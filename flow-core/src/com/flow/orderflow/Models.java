package com.flow.orderflow;

import java.util.ArrayList;
import java.util.List;

/**
 * The eight entry models (gapAnswers §5; M8 approved as A3). Each confirms or rejects the idea's direction and
 * returns a {@link ModelResult}. Shared rule (original Q8.1 merged per G0.1): +2 needs location + a closed trigger
 * bar (the last closed bar: evaluation runs at each bar close, closed-bar baseline G0.3) + at least one read group.
 * Model priority (used to pick the stop when several are at +2): M1, M3, M2, M4, M5, M6, M7, M8.
 */
public final class Models {
  private Models() {}

  public static final List<String> PRIORITY = List.of("M1", "M3", "M2", "M4", "M5", "M6", "M7", "M8");

  public static List<ModelResult> evaluateAll(EvalContext x) {
    List<ModelResult> out = new ArrayList<>();
    if (x.eng.lastClosed() == null || !x.eng.isReady()) {
      for (String m : PRIORITY) out.add(ModelResult.noData(m, x.eng.notReadyReason()));
      return out;
    }
    out.add(guard("M1", () -> m1(x)));
    out.add(guard("M2", () -> m2(x)));
    out.add(guard("M3", () -> m3(x)));
    out.add(guard("M4", () -> m4(x)));
    out.add(guard("M5", () -> m5(x)));
    out.add(guard("M6", () -> m6(x)));
    out.add(guard("M7", () -> m7(x)));
    out.add(guard("M8", () -> m8(x)));
    return out;
  }

  /** extended Q6.17: an evaluation error becomes NO_SIGNAL with the error recorded, never a numeric score. */
  private static ModelResult guard(String id, java.util.function.Supplier<ModelResult> f) {
    try {
      return f.get();
    } catch (RuntimeException e) {
      return ModelResult.noSignal(id, "error: " + e);
    }
  }

  private static ModelResult ok(String m, int score, Reads r, Integer stopRef, int lo, int hi, String note) {
    return new ModelResult(m, ModelResult.Status.OK, score, List.copyOf(r.names), stopRef, lo, hi, note);
  }

  /** Common score ladder once the location is present: -1 contra, 0 nothing, +1 reads, +2 reads + trigger. */
  private static int ladder(Reads r, boolean trigger, int minGroups) {
    if (r.groups() >= minGroups && trigger) return 2;
    if (r.groups() >= 1) return 1;
    if (r.contra) return -1;
    return 0;
  }

  private static boolean touched(OrderFlowEngine eng, int n, int lo, int hi) {
    for (int k = 0; k < n && eng.back(k) != null; k++) {
      OfBar b = eng.back(k);
      if (b.low <= hi && b.high >= lo) return true;
    }
    return false;
  }

  private static int extremeAgainst(OrderFlowEngine eng, int d, int n) {
    int e = d > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
    for (int k = 0; k < n && eng.back(k) != null; k++) {
      OfBar b = eng.back(k);
      e = d > 0 ? Math.min(e, b.low) : Math.max(e, b.high);
    }
    return e;
  }

  /** G43: trigger bar closing within pocShortTicks BEFORE reaching the session POC in the trade direction. */
  static boolean closesShortOfPoc(EvalContext x, OfBar bar) {
    Profile s = x.activeSession();
    Integer poc = s == null ? null : s.poc(x.c);
    if (poc == null || !x.c.pocShortFilter) return false;
    int dist = (poc - bar.close) * x.idea.direction();
    return dist > 0 && dist <= x.c.pocShortTicks;
  }

  // ---------------------------------------------------------------- M1 LVN rejection (E3)
  static ModelResult m1(EvalContext x) {
    OfConfig c = x.c;
    int d = x.idea.direction();
    Profile.Lvn best = null;
    for (Profile.Lvn l : x.lvns()) {
      if (!x.nearZone(l.lowTicks(), l.highTicks())) continue;
      if (!touched(x.eng, c.readValidBars, l.lowTicks(), l.highTicks())) continue;
      if (best == null || l.score() > best.score()) best = l;
    }
    if (best == null) return ModelResult.noSignal("M1", "no touched LVN at the idea zone");
    OfBar last = x.eng.lastClosed();
    int far = d > 0 ? best.lowTicks() : best.highTicks();
    if ((far - last.close) * d >= c.traverseTicks) {
      return ok("M1", -2, new Reads(), far, best.lowTicks(), best.highTicks(), "LVN traversed");
    }
    Reads r = Reads.compute(x, best.lowTicks(), best.highTicks());
    // trigger bar = the last bar itself traded in (or at) the LVN and closed back out on the idea side (B2)
    boolean barInLvn = last.low <= best.highTicks() + c.approachTicks && last.high >= best.lowTicks() - c.approachTicks;
    boolean backOut = barInLvn && (d > 0 ? last.close > best.highTicks() : last.close < best.lowTicks());
    boolean shortOfPoc = closesShortOfPoc(x, last);
    int score = ladder(r, backOut && !shortOfPoc, 1);
    return ok("M1", score, r, far, best.lowTicks(), best.highTicks(), shortOfPoc && backOut ? "closes short of POC" : null);
  }

  // ---------------------------------------------------------------- M2 value-area counter-trade (E2, E3)
  static ModelResult m2(EvalContext x) {
    OfConfig c = x.c;
    int d = x.idea.direction();
    Profile s = x.activeSession();
    if (s == null || s.poc(c) == null) return ModelResult.noData("M2", "no session profile");
    Profile.Shape shape = s.shape(x.now, c);
    Profile prev = x.eng.profiles().lastCompletedSession();
    Profile.Shape prevShape = prev == null ? Profile.Shape.NONE : prev.shape(x.now, c);
    boolean balanced = shape == Profile.Shape.D || shape == Profile.Shape.DOUBLE
        || prevShape == Profile.Shape.D || prevShape == Profile.Shape.DOUBLE;
    if (!balanced) return ModelResult.noSignal("M2", "day not balanced (shape " + shape + ")");
    int edge = d > 0 ? s.val(c) : s.vah(c);
    boolean beyond = false;
    for (int k = 0; k < c.readValidBars && x.eng.back(k) != null; k++) {
      OfBar b = x.eng.back(k);
      if (d > 0 ? b.low < edge : b.high > edge) beyond = true;
    }
    if (!beyond) return ModelResult.noSignal("M2", "price not beyond " + (d > 0 ? "VAL" : "VAH"));
    OfBar last = x.eng.lastClosed(), prevBar = x.eng.back(1);
    boolean acceptance = prevBar != null
        && (d > 0 ? last.close < edge && prevBar.close < edge && (last.stackedBear || prevBar.stackedBear)
                  : last.close > edge && prevBar.close > edge && (last.stackedBull || prevBar.stackedBull));
    int stopRef = extremeAgainst(x.eng, d, c.readValidBars + 1);
    if (acceptance) return ok("M2", -2, new Reads(), stopRef, edge, edge, "accepted beyond the value area");
    Reads r = Reads.compute(x, edge, edge);
    boolean backInside = d > 0 ? last.low < edge && last.close >= edge : last.high > edge && last.close <= edge; // pierced, closed back (B2)
    boolean trigger = backInside && r.abs && !closesShortOfPoc(x, last);
    return ok("M2", ladder(r, trigger, 1), r, stopRef, edge, edge, null);
  }

  // ---------------------------------------------------------------- M3 failed aggression at a zone (E3, E5)
  static ModelResult m3(EvalContext x) {
    OfConfig c = x.c;
    int d = x.idea.direction();
    int lo = x.idea.zoneLowTicks(), hi = x.idea.zoneHighTicks();
    boolean atZone = false;
    for (int k = 0; k < c.readValidBars && x.eng.back(k) != null; k++) {
      if (Reads.barNear(x.eng.back(k), lo, hi, c.locationMaxDistanceTicks)) atZone = true;
    }
    if (!atZone) return ModelResult.noSignal("M3", "price not at the idea zone");
    Reads r = Reads.compute(x, lo, hi);
    int stopRef = extremeAgainst(x.eng, d, c.readValidBars);
    if (r.aggressionAgainstSucceeded) return ok("M3", -2, r, stopRef, lo, hi, "aggression against the idea succeeded");
    OfBar last = x.eng.lastClosed(), prev = x.eng.back(1);
    boolean strongClose = d > 0 ? last.closeLocation >= 0.70 : last.closeLocation <= 0.30;
    boolean finished = d > 0 ? (last.finishedLow || (prev != null && prev.finishedLow))
                             : (last.finishedHigh || (prev != null && prev.finishedHigh));
    boolean lastAtZone = Reads.barNear(last, lo, hi, c.approachTicks); // the trigger bar itself is at the zone (B2)
    boolean trigger = lastAtZone && r.failedAggression && strongClose && (finished || r.deltaFlip) && !closesShortOfPoc(x, last);
    return ok("M3", ladder(r, trigger, 1), r, stopRef, lo, hi, null);
  }

  // ---------------------------------------------------------------- M4 exhaustion -> stacked imbalance, A+ (E4)
  static ModelResult m4(EvalContext x) {
    OfConfig c = x.c;
    int d = x.idea.direction();
    OfBar last = x.eng.lastClosed();
    Double vwap = x.eng.vwap();
    Profile s = x.activeSession();
    Integer lo = null, hi = null;
    if (vwap != null && Math.abs(last.close - vwap) <= c.locationMaxDistanceTicks) { lo = hi = (int) Math.round(vwap); }
    else if (s != null && s.poc(c) != null) {
      int edge = d > 0 ? s.val(c) : s.vah(c);
      if (Reads.barNear(last, edge, edge, c.locationMaxDistanceTicks)) lo = hi = edge;
    }
    if (lo == null) return ModelResult.noSignal("M4", "not at VWAP or a value-area edge");
    int finishedCount = 0;
    int seqExtreme = d > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
    for (int k = 0; k < 5 && x.eng.back(k) != null; k++) {
      OfBar b = x.eng.back(k);
      if (d > 0 ? b.finishedLow : b.finishedHigh) finishedCount++;
      seqExtreme = d > 0 ? Math.min(seqExtreme, b.low) : Math.max(seqExtreme, b.high);
    }
    Reads r = Reads.compute(x, lo, hi);
    if ((seqExtreme - last.close) * d >= c.traverseTicks) {
      return ok("M4", -2, r, seqExtreme, lo, hi, "new extreme beyond the exhaustion sequence");
    }
    boolean setup = finishedCount >= 2 || r.exhaustion;
    boolean trigger = setup && (d > 0 ? last.stackedBull : last.stackedBear) && Math.signum(last.delta) == d;
    int score = trigger ? 2 : setup ? 1 : r.contra ? -1 : 0;
    return ok("M4", score, r, seqExtreme, lo, hi, "finishedAuctions=" + finishedCount);
  }

  // ---------------------------------------------------------------- M5 delta flip + retest (E1, E5)
  static ModelResult m5(EvalContext x) {
    OfConfig c = x.c;
    int d = x.idea.direction();
    OrderFlowEngine eng = x.eng;
    // find a flip bar in the last 10: >= 3 bars of delta against the idea, then a bar with delta for it + an imbalance
    long flipIdx = -1;
    for (int k = 0; k < 10 && eng.back(k + 3) != null; k++) {
      OfBar f = eng.back(k);
      boolean flipBar = Math.signum(f.delta) == d && !(d > 0 ? f.bullImbalanceRows.isEmpty() : f.bearImbalanceRows.isEmpty());
      if (!flipBar) continue;
      boolean against = true;
      for (int j = 1; j <= 3; j++) if (Math.signum(eng.back(k + j).delta) != -d) against = false;
      if (against) { flipIdx = f.index; break; }
    }
    if (flipIdx < 0) return ModelResult.noSignal("M5", "no delta flip after sustained opposite delta");
    OfBar last = eng.lastClosed();
    Integer lvl = eng.bosLevel();
    boolean bosForIdea = eng.bosDirection() == d && eng.bosBarIndex() >= flipIdx && lvl != null;
    int lo = lvl != null ? lvl : last.low, hi = lvl != null ? lvl : last.high;
    Reads r = Reads.compute(x, lo, hi);
    if (!bosForIdea) return ok("M5", 1, r, d > 0 ? last.low : last.high, lo, hi, "flip, waiting for break of structure");
    long since = last.index - eng.bosBarIndex();
    if ((lvl - last.close) * d >= c.traverseTicks) return ok("M5", -2, r, lvl, lo, hi, "broken level lost");
    boolean retest = since >= 1 && since <= c.retestMaxBars
        && (d > 0 ? last.low <= lvl + c.retestTicks && last.close > lvl : last.high >= lvl - c.retestTicks && last.close < lvl);
    int stopRef = d > 0 ? last.low : last.high;
    return ok("M5", retest ? 2 : 1, r, stopRef, lo, hi, retest ? "retest held" : "waiting for retest");
  }

  // ---------------------------------------------------------------- M6 trapped traders at LVN + VWAP (E5)
  static ModelResult m6(EvalContext x) {
    OfConfig c = x.c;
    int d = x.idea.direction();
    Double vwapD = x.eng.vwap();
    if (vwapD == null) return ModelResult.noData("M6", "no VWAP");
    int vwap = (int) Math.round(vwapD);
    OfBar last = x.eng.lastClosed();
    boolean vwapAdverse = d < 0 ? vwap > last.close : vwap < last.close;
    Profile.Lvn lvn = null;
    for (Profile.Lvn l : x.lvns()) {
      boolean nearVwap = vwap >= l.lowTicks() - c.locationMaxDistanceTicks && vwap <= l.highTicks() + c.locationMaxDistanceTicks;
      if (nearVwap && Reads.barNear(last, l.lowTicks(), l.highTicks(), c.locationMaxDistanceTicks)) { lvn = l; break; }
    }
    if (lvn == null || !vwapAdverse) return ModelResult.noSignal("M6", "no LVN at VWAP on the adverse side");
    if ((last.close - vwap) * -d > 0 && Math.signum(last.delta) == -d) {
      return ok("M6", -2, new Reads(), vwap, lvn.lowTicks(), lvn.highTicks(), "closed through VWAP against the idea");
    }
    int traps = 0;
    for (int k = 0; k < 5 && x.eng.back(k + 1) != null; k++) {
      OfBar b = x.eng.back(k), p = x.eng.back(k + 1);
      boolean movedAgainst = d < 0 ? b.high > p.high : b.low < p.low;
      boolean closedFor = d < 0 ? b.close < b.open : b.close > b.open;
      if (movedAgainst && closedFor && Math.signum(b.delta) == -d) traps++;
    }
    Reads r = Reads.compute(x, lvn.lowTicks(), lvn.highTicks());
    OfBar prev = x.eng.back(1);
    boolean finished = d < 0 ? (last.finishedHigh || (prev != null && prev.finishedHigh))
                             : (last.finishedLow || (prev != null && prev.finishedLow));
    boolean trigger = traps >= 2 && finished && Math.signum(last.delta) == d;
    int stopRef = extremeAgainst(x.eng, d, 5);
    int score = trigger ? 2 : traps >= 2 ? 1 : r.contra ? -1 : 0;
    return ok("M6", score, r, stopRef, lvn.lowTicks(), lvn.highTicks(), "traps=" + traps);
  }

  // ---------------------------------------------------------------- M7 profile-shape failure (E6)
  static ModelResult m7(EvalContext x) {
    OfConfig c = x.c;
    int d = x.idea.direction();
    Profile s = x.eng.profiles().session();
    if (s == null) return ModelResult.noData("M7", "no session profile");
    Profile.Shape shape = s.shape(x.now, c);
    Profile.Shape needed = d < 0 ? Profile.Shape.P : Profile.Shape.B_LOWER;
    boolean htfAligned = x.htfTrend == (d < 0 ? 1 : -1); // a P in an uptrend is a breakout, not a trap
    if (shape != needed || htfAligned) return ModelResult.noSignal("M7", "shape " + shape);
    List<Integer> hvns = s.hvnPrices(c);
    if (hvns.isEmpty()) return ModelResult.noSignal("M7", "no HVN");
    int hvn = d < 0 ? hvns.get(hvns.size() - 1) : hvns.get(0);
    OfBar last = x.eng.lastClosed();
    if ((last.close - hvn) * -d >= 10) return ok("M7", -2, new Reads(), hvn, hvn, hvn, "accepted beyond the HVN");
    int rejections = 0;
    for (int k = 0; k < 5 && x.eng.back(k) != null; k++) {
      OfBar b = x.eng.back(k);
      if (d < 0 ? b.high >= hvn - 2 && b.close < hvn : b.low <= hvn + 2 && b.close > hvn) rejections++;
    }
    if (rejections == 0) return ModelResult.noSignal("M7", "not at the HVN");
    Reads r = Reads.compute(x, hvn, hvn);
    boolean trigger = rejections >= 2 && (r.deltaFlip || (d < 0 ? last.stackedBear : last.stackedBull));
    int score = trigger ? 2 : rejections >= 2 ? 1 : r.contra ? -1 : 0;
    return ok("M7", score, r, extremeAgainst(x.eng, d, 5), hvn, hvn, "rejections=" + rejections);
  }

  // ---------------------------------------------------------------- M8 business-area return / Gold Extra Edge (E2)
  static ModelResult m8(EvalContext x) {
    OfConfig c = x.c;
    int d = x.idea.direction();
    ProfileBook pb = x.eng.profiles();
    List<Profile> cands = new ArrayList<>();
    if (pb.completed(SessionClock.Kind.NY) != null) cands.add(pb.completed(SessionClock.Kind.NY)); // priority
    for (Profile p : pb.completed()) if (!cands.contains(p)) cands.add(p);
    OfBar last = x.eng.lastClosed();
    long windowStart = x.eng.back(Math.min(c.readValidBars, x.eng.closedBars().size()) - 1).startMs;
    for (Profile p : cands) {
      Integer poc = p.poc(c);
      if (poc == null || !x.nearZone(poc, poc)) continue;
      boolean firstApproach = !p.pocRevisited || p.pocRevisitedAtMs >= windowStart;
      if (!firstApproach || !touched(x.eng, c.readValidBars, poc - c.approachTicks, poc + c.approachTicks)) continue;
      OfBar prev = x.eng.back(1);
      boolean lost = prev != null && (poc - last.close) * d >= c.traverseTicks && (poc - prev.close) * d >= c.traverseTicks;
      int stopRef = poc - d * c.profileRowTicks;
      if (lost) return ok("M8", -2, new Reads(), stopRef, poc, poc, p.label + " POC lost");
      Reads r = Reads.compute(x, poc, poc);
      boolean lastTouched = last.low <= poc + c.approachTicks && last.high >= poc - c.approachTicks;
      boolean rejection = lastTouched && (last.close - poc) * d > 0; // the trigger bar touched the POC (B2)
      return ok("M8", ladder(r, rejection, 1), r, stopRef, poc, poc, p.label + " POC");
    }
    return ModelResult.noSignal("M8", "no prior-session POC at the idea zone on first approach");
  }
}
