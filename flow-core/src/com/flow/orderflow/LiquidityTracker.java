package com.flow.orderflow;

import com.flow.core.DomRow;
import com.flow.flow.LiquidityMapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

/**
 * Heatmap reads (gapAnswers §10) from the live liquidity map, sampled once per second: walls (3-tick resting size
 * >= wallAbsoluteSize or >= wallMedianMult x the window median, levels below wallMinLevelSize ignored as bot
 * clutter), persistence, and the sweep / breakthrough / gap / spoof events at a qualified wall (S4: the course's two
 * "sweep" definitions are two named events). Consumption vs pulling is approximated by matching size lost at the wall
 * with traded volume there (engine's recent trades). Not available in replay (no depth history in the raw journal):
 * every query then simply finds nothing.
 */
public final class LiquidityTracker {
  public enum Side { BID, ASK }
  public enum EventKind { SWEEP_REVERSAL, BREAKTHROUGH, GAP, SPOOF }

  public static final class Wall {
    public final Side side;
    public int centerTicks;
    public double size, maxSize;
    public final long firstSeenMs;
    long lastSeenMs;
    Wall(Side side, int center, double size, long t) {
      this.side = side; this.centerTicks = center; this.size = size; this.maxSize = size; this.firstSeenMs = t; this.lastSeenMs = t;
    }
    public boolean qualified(long now, OfConfig c) { return now - firstSeenMs >= c.wallPersistMs; }
  }

  /** bullish = the event argues for price going up (a bid-wall sweep reversal or gap; an ask-wall breakthrough). */
  public record LiqEvent(EventKind kind, Side side, int wallTicks, long timeMs, boolean bullish, double sizeAtApproach,
                         double tradedShare) {}

  private static final class Episode {
    final Wall wall; final long startMs; final double sizeAtApproach; final double sizeAt10sAgo;
    boolean penetrated; long penetratedBar = -1; long sizeCheckMs;
    Episode(Wall w, long t) { wall = w; startMs = t; sizeAtApproach = w.size; sizeAt10sAgo = w.size; sizeCheckMs = t; }
  }

  private final OfConfig c;
  private final List<Wall> walls = new ArrayList<>();
  private final List<Episode> episodes = new ArrayList<>();
  private final List<LiqEvent> events = new ArrayList<>();
  private long lastSampleSec = Long.MIN_VALUE;
  private boolean available;

  public LiquidityTracker(OfConfig c) {
    this.c = c;
  }

  public boolean available() { return available; }
  public List<Wall> walls() { return Collections.unmodifiableList(walls); }
  public List<LiqEvent> events() { return Collections.unmodifiableList(events); }

  /** Call on every strategy wake; samples at most once per exchange second. */
  public void update(LiquidityMapView lm, OrderFlowEngine eng, long now) {
    long sec = Math.floorDiv(now, 1000L);
    if (sec == lastSampleSec) return;
    lastSampleSec = sec;
    Integer price = eng.lastPrice();
    if (lm == null || !lm.isReady() || price == null) { available = false; return; }
    available = true;
    List<int[]> found = new ArrayList<>(); // {side, center, size}
    detect(lm.bidRowsWithin(c.liquidityWindowTicks), Side.BID, found);
    detect(lm.askRowsWithin(c.liquidityWindowTicks), Side.ASK, found);
    // match to tracked walls (same side, center within 2 ticks)
    for (int[] f : found) {
      Side side = f[0] == 0 ? Side.BID : Side.ASK;
      Wall match = null;
      for (Wall w : walls) if (w.side == side && Math.abs(w.centerTicks - f[1]) <= 2) { match = w; break; }
      if (match == null) walls.add(new Wall(side, f[1], f[2], now));
      else { match.centerTicks = f[1]; match.size = f[2]; match.maxSize = Math.max(match.maxSize, f[2]); match.lastSeenMs = now; }
    }
    // a wall not seen this second: keep its last size at 0 for episode accounting, drop after 60 s
    for (Iterator<Wall> it = walls.iterator(); it.hasNext(); ) {
      Wall w = it.next();
      if (w.lastSeenMs != now) {
        w.size = sizeNear(w.side == Side.BID ? lm.bidRowsWithin(c.liquidityWindowTicks) : lm.askRowsWithin(c.liquidityWindowTicks), w.centerTicks);
        if (now - w.lastSeenMs > 60_000) it.remove();
      }
    }
    trackEpisodes(eng, price, now);
    while (events.size() > 500) events.remove(0);
  }

  private void detect(List<DomRow> rows, Side side, List<int[]> out) {
    TreeMap<Integer, Double> m = new TreeMap<>();
    for (DomRow r : rows) if (r.size() >= c.wallMinLevelSize) m.put(r.priceTicks(), r.size());
    if (m.isEmpty()) return;
    List<int[]> sums = new ArrayList<>();
    List<Double> nonZero = new ArrayList<>();
    for (int p : m.keySet()) {
      double s = m.getOrDefault(p - 1, 0.0) + m.get(p) + m.getOrDefault(p + 1, 0.0);
      sums.add(new int[]{p, (int) Math.round(s)});
      nonZero.add(s);
    }
    Collections.sort(nonZero);
    double median = nonZero.get(nonZero.size() / 2);
    for (int i = 0; i < sums.size(); i++) {
      int[] s = sums.get(i);
      boolean big = s[1] >= c.wallAbsoluteSize || s[1] >= c.wallMedianMult * median;
      boolean localMax = (i == 0 || sums.get(i - 1)[1] <= s[1]) && (i == sums.size() - 1 || sums.get(i + 1)[1] < s[1]);
      if (big && localMax) out.add(new int[]{side == Side.BID ? 0 : 1, s[0], s[1]});
    }
  }

  private static double sizeNear(List<DomRow> rows, int center) {
    double s = 0;
    for (DomRow r : rows) if (Math.abs(r.priceTicks() - center) <= 1) s += r.size();
    return s;
  }

  private void trackEpisodes(OrderFlowEngine eng, int price, long now) {
    for (Wall w : walls) {
      if (!w.qualified(now, c)) continue;
      boolean near = Math.abs(price - w.centerTicks) <= c.sweepThroughTicks;
      boolean has = false;
      for (Episode e : episodes) if (e.wall == w) { has = true; break; }
      if (near && !has) episodes.add(new Episode(w, now));
    }
    for (Iterator<Episode> it = episodes.iterator(); it.hasNext(); ) {
      Episode e = it.next();
      Wall w = e.wall;
      boolean bid = w.side == Side.BID;
      int through = bid ? w.centerTicks - price : price - w.centerTicks;   // >0 = beyond the wall
      int away = -through;                                                  // >0 = back on the original side
      double lost = Math.max(0, e.sizeAtApproach - w.size);
      double traded = eng.tradedVolume(w.centerTicks - 1, w.centerTicks + 1, e.startMs);
      double tradedShare = lost <= 0 ? 0 : Math.min(1, traded / lost);
      double lostShare = e.sizeAtApproach <= 0 ? 0 : lost / e.sizeAtApproach;
      // spoof: >= 80 % gone within 10 s while price close and little of it traded
      if (now - e.startMs <= 10_000 && lostShare >= c.spoofLossShare && Math.abs(through) <= 5
          && tradedShare < c.spoofTradedMaxShare) {
        events.add(new LiqEvent(EventKind.SPOOF, w.side, w.centerTicks, now, !bid, e.sizeAtApproach, tradedShare));
        it.remove();
        continue;
      }
      if (!e.penetrated && through >= c.sweepThroughTicks && lostShare >= c.sweepConsumedShare) {
        e.penetrated = true;
        e.penetratedBar = eng.barCount();
      }
      if (e.penetrated) {
        if (through >= c.breakthroughTicks) {
          events.add(new LiqEvent(EventKind.BREAKTHROUGH, w.side, w.centerTicks, now, !bid, e.sizeAtApproach, tradedShare));
          it.remove();
        } else if (away > 0 && eng.lastClosed() != null
            && (bid ? eng.lastClosed().close > w.centerTicks : eng.lastClosed().close < w.centerTicks)
            && eng.barCount() - e.penetratedBar <= 2) {
          events.add(new LiqEvent(EventKind.SWEEP_REVERSAL, w.side, w.centerTicks, now, bid, e.sizeAtApproach, tradedShare));
          it.remove();
        } else if (eng.barCount() - e.penetratedBar > 2) {
          it.remove();
        }
      } else if (away >= c.gapAwayTicks) {
        if (w.size >= c.gapHoldShare * e.sizeAtApproach && traded > 0) {
          events.add(new LiqEvent(EventKind.GAP, w.side, w.centerTicks, now, bid, e.sizeAtApproach, tradedShare));
        }
        it.remove();
      } else if (now - e.startMs > 300_000) {
        it.remove();
      }
    }
  }

  /** Nearest qualified wall strictly beyond {@code fromTicks} in direction dir (+1 above, -1 below), or null. */
  public Wall nearestWall(int fromTicks, int dir, long now) {
    Wall best = null;
    for (Wall w : walls) {
      if (!w.qualified(now, c)) continue;
      int d = (w.centerTicks - fromTicks) * dir;
      if (d > 0 && (best == null || d < (best.centerTicks - fromTicks) * dir)) best = w;
    }
    return best;
  }
}
