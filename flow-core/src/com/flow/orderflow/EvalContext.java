package com.flow.orderflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a model needs for one evaluation: the idea, the engine, the liquidity tracker (null/unavailable in
 * replay), the time, and the HTF trend from the idea source. Helpers return the V1 locations (gapAnswers §2 G0.5).
 */
public final class EvalContext {
  public final TradeIdea idea;
  public final OrderFlowEngine eng;
  public final LiquidityTracker liq;
  public final OfConfig c;
  public final long now;
  /** +1 HTF up, -1 down, 0 unknown. */
  public final int htfTrend;

  public EvalContext(TradeIdea idea, OrderFlowEngine eng, LiquidityTracker liq, long now, int htfTrend) {
    this.idea = idea;
    this.eng = eng;
    this.liq = liq;
    this.c = eng.config();
    this.now = now;
    this.htfTrend = htfTrend;
  }

  public Profile activeSession() { return eng.profiles().activeSession(now); }

  /** LVNs from the active session, day and week profiles. */
  public List<Profile.Lvn> lvns() {
    List<Profile.Lvn> out = new ArrayList<>();
    ProfileBook pb = eng.profiles();
    Profile s = activeSession();
    if (s != null) out.addAll(s.lvns(c));
    if (pb.day() != null && pb.day() != s) out.addAll(pb.day().lvns(c));
    if (pb.week() != null) out.addAll(pb.week().lvns(c));
    return out;
  }

  /** POCs of every live and completed profile (developing session, day, week, completed sessions, previous day). */
  public List<Integer> pocs() {
    List<Integer> out = new ArrayList<>();
    for (Profile p : eng.profiles().all()) { Integer poc = p.poc(c); if (poc != null) out.add(poc); }
    return out;
  }

  public boolean nearZone(int lo, int hi) {
    return lo <= idea.zoneHighTicks() + c.locationMaxDistanceTicks && hi >= idea.zoneLowTicks() - c.locationMaxDistanceTicks;
  }
}
