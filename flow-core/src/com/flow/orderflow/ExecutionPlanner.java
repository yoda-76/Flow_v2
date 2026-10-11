package com.flow.orderflow;

import com.flow.journal.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregation (gapAnswers §6), stop and target selection and the entry filters (§9) for one evaluation. Pure: takes
 * the model results and the context, returns a {@link Plan} saying Go or No-Go with every reason, so the journal
 * line shows exactly why (extended Q6.20/Q6.36: all block reasons, not just the first).
 */
public final class ExecutionPlanner {
  private ExecutionPlanner() {}

  public record Target(String label, int priceTicks) {}

  public static final class Plan {
    public boolean go;
    public final List<String> blocks = new ArrayList<>();
    public String entryModel;
    public int entryTicks, stopTicks, targetTicks;
    public String targetLabel;
    public double rr;
    public final List<Target> targetCandidates = new ArrayList<>();

    public String toJson() {
      Json j = Json.object().field("go", go).field("blocks", String.join(",", blocks));
      if (entryModel != null) j.field("entryModel", entryModel);
      if (go || entryModel != null) {
        j.field("entry", entryTicks).field("stop", stopTicks).field("target", targetTicks).field("rr", rr);
        if (targetLabel != null) j.field("targetLabel", targetLabel);
      }
      StringBuilder sb = new StringBuilder();
      for (Target t : targetCandidates) sb.append(sb.length() == 0 ? "" : ",").append(t.label()).append('@').append(t.priceTicks());
      j.field("targets", sb.toString());
      return j.build();
    }
  }

  /** Session-scoped counters the strategy owns (A4 limits). */
  public interface Limits {
    int entriesForZoneThisSession(String zoneKey);
    int entriesThisSession();
  }

  public static Plan plan(EvalContext x, List<ModelResult> results, Limits limits) {
    OfConfig c = x.c;
    Plan p = new Plan();
    int d = x.idea.direction();
    OfBar last = x.eng.lastClosed();
    if (last == null) { p.blocks.add("no_bar"); return p; }

    // ---- aggregation: Go = some model at +2 and none at -2
    List<ModelResult> full = new ArrayList<>();
    for (String id : Models.PRIORITY) for (ModelResult r : results) if (r.model().equals(id) && r.isFullSetup()) full.add(r);
    boolean veto = false;
    for (ModelResult r : results) if (r.isVeto()) { veto = true; p.blocks.add("veto_" + r.model()); }
    if (full.isEmpty()) p.blocks.add("no_full_setup");
    if (full.isEmpty() || veto) return p;

    p.entryTicks = last.close;

    // ---- stop: the highest-priority +2 model whose structural stop fits the cap (original Q9.3)
    ModelResult chosen = null;
    int stop = 0;
    for (ModelResult r : full) {
      if (r.stopRefTicks() == null) continue;
      int s = r.stopRefTicks() - d * c.stopBufferTicks;
      int risk = (p.entryTicks - s) * d;
      if (risk <= 0) continue;
      if (risk < c.minStopTicks) s = p.entryTicks - d * c.minStopTicks;
      // stop behind a wall (gapAnswers §10): a qualified wall just beyond the stop moves the stop beyond it
      if (x.liq != null && x.liq.available()) {
        LiquidityTracker.Wall w = x.liq.nearestWall(s, -d, x.now);
        if (w != null && Math.abs(w.centerTicks - s) <= c.wallStopShiftTicks) {
          int shifted = w.centerTicks - d * (1 + c.stopBufferTicks);
          if ((p.entryTicks - shifted) * d <= c.maxStopTicks) s = shifted;
        }
      }
      if ((p.entryTicks - s) * d <= c.maxStopTicks) { chosen = r; stop = s; break; }
    }
    if (chosen == null) { p.entryModel = full.get(0).model(); p.blocks.add("stop_too_wide"); return p; }
    p.entryModel = chosen.model();
    p.stopTicks = stop;
    int risk = (p.entryTicks - stop) * d;

    // ---- target: nearest candidate at least minRewardRisk away (E2/E3)
    collectTargets(x, p);
    int minDist = (int) Math.ceil(c.minRewardRisk * risk);
    Target best = null;
    for (Target t : p.targetCandidates) {
      int dist = (t.priceTicks() - p.entryTicks) * d;
      if (dist >= minDist && (best == null || dist < (best.priceTicks() - p.entryTicks) * d)) best = t;
    }
    if (best == null) p.blocks.add("no_target_at_min_rr");
    else { p.targetTicks = best.priceTicks(); p.targetLabel = best.label(); p.rr = (best.priceTicks() - p.entryTicks) * d / (double) risk; }

    // ---- POC / HVN blocker (original Q11.1, E3 "don't trade with the POC in between"): any profile's POC, or an
    // active-session / day HVN, strictly between entry and the min-R:R point (B3: completed-session POCs count too)
    int minPoint = p.entryTicks + d * minDist;
    Profile active = x.activeSession(), day = x.eng.profiles().day();
    for (Profile pr : x.eng.profiles().all()) {
      List<Integer> lv = new ArrayList<>();
      if (pr.poc(c) != null) lv.add(pr.poc(c));
      if (pr == active || pr == day) lv.addAll(pr.hvnPrices(c));
      for (int lvl : lv) {
        if ((lvl - p.entryTicks) * d > 0 && (minPoint - lvl) * d > 0) { p.blocks.add("blocker_" + pr.label + "@" + lvl); break; }
      }
    }
    if (active != null && !x.eng.profiles().all().contains(active)) {
      Integer poc = active.poc(c);
      if (poc != null && (poc - p.entryTicks) * d > 0 && (minPoint - poc) * d > 0) p.blocks.add("blocker_" + active.label + "@" + poc);
    }

    // ---- chasing: entry more than chaseMaxBarRanges average bars beyond the triggering model's location (B2)
    if (chosen.locLowTicks() != null) {
      int edge = d > 0 ? chosen.locHighTicks() : chosen.locLowTicks();
      double maxChase = c.chaseMaxBarRanges * Math.max(1, x.eng.avgRange(c.avgWindowBars));
      if ((p.entryTicks - edge) * d > maxChase) p.blocks.add("chasing");
    }
    // ---- strong momentum against the idea, not broken by the trigger bar
    if (momentumAgainst(x, d) && Math.signum(last.delta) != d) p.blocks.add("momentum_against");
    // ---- P-shape longs outside an uptrend (E6 "always a trap")
    if (c.pShapeLongFilter && d > 0 && (chosen.model().equals("M2") || chosen.model().equals("M7"))) {
      Profile s = x.eng.profiles().session();
      if (s != null && s.shape(x.now, c) == Profile.Shape.P && x.htfTrend != 1) p.blocks.add("p_shape_long");
    }
    // ---- limits (A4)
    if (limits.entriesForZoneThisSession(x.idea.zoneKey()) >= c.maxEntriesPerZonePerSession) p.blocks.add("zone_limit");
    if (limits.entriesThisSession() >= c.maxEntriesPerSession) p.blocks.add("session_limit");

    p.go = p.blocks.isEmpty();
    return p;
  }

  static boolean momentumAgainst(EvalContext x, int d) {
    OfConfig c = x.c;
    OrderFlowEngine eng = x.eng;
    if (eng.closedBars().size() < c.momentumBars + 1 + c.avgWindowBars) return false;
    double avgDelta = eng.avgAbsDelta(c.avgWindowBars);
    double avgRange = eng.avgRange(c.avgWindowBars);
    int range = 0;
    for (int k = 1; k <= c.momentumBars; k++) { // the bars before the trigger bar
      OfBar b = eng.back(k);
      boolean against = d > 0 ? b.down() : b.up();
      if (!against || Math.signum(b.delta) != -d || Math.abs(b.delta) < avgDelta) return false;
      range += b.range();
    }
    return range >= c.momentumRangeMult * avgRange * c.momentumBars;
  }

  static void collectTargets(EvalContext x, Plan p) {
    OfConfig c = x.c;
    int d = x.idea.direction();
    List<Target> t = p.targetCandidates;
    Double vwap = x.eng.vwap();
    if (vwap != null) t.add(new Target("VWAP", (int) Math.round(vwap)));
    for (Profile pr : x.eng.profiles().all()) {
      Integer poc = pr.poc(c);
      if (poc != null) t.add(new Target(pr.label + ":POC" + (pr.completed && !pr.pocRevisited ? "(naked)" : ""), poc));
      for (int h : pr.hvnPrices(c)) t.add(new Target(pr.label + ":HVN", h));
    }
    Profile s = x.activeSession();
    if (s != null && s.poc(c) != null) t.add(new Target(s.label + (d > 0 ? ":VAH" : ":VAL"), d > 0 ? s.vah(c) : s.val(c)));
    if (x.liq != null && x.liq.available()) {
      LiquidityTracker.Wall w = x.liq.nearestWall(p.entryTicks, d, x.now);
      if (w != null) t.add(new Target("wall", w.centerTicks - d)); // just before the wall
    }
    // unfinished auction at the session extreme in the trade direction (G10; S6: a revisit is never assumed)
    Profile cur = x.eng.profiles().session();
    long sessStart = cur == null ? Long.MIN_VALUE : cur.startMs;
    OfBar ext = null;
    for (OfBar b : x.eng.closedBars()) {
      if (b.startMs < sessStart) continue;
      if (ext == null || (d > 0 ? b.high > ext.high : b.low < ext.low)) ext = b;
    }
    if (ext != null && (d > 0 ? ext.unfinishedHigh : ext.unfinishedLow)) {
      t.add(new Target(d > 0 ? "unfinishedHigh" : "unfinishedLow", d > 0 ? ext.high : ext.low));
    }
    t.removeIf(tg -> (tg.priceTicks() - p.entryTicks) * d <= 0);
  }
}
