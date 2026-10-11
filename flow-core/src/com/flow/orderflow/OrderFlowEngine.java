package com.flow.orderflow;

import com.flow.core.ClockEvent;
import com.flow.core.Event;
import com.flow.core.Feature;
import com.flow.core.TickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

/**
 * Pure-core feature that builds every order-flow construct the execution model needs from raw TickEvents
 * (gapAnswers §3, §4, §7): execution bars with footprint and per-bar reads, aggressor orders (big / small) and bursts
 * with their price response, session/day/week profiles, VWAP (17:00 CT reset), micro structure, and a 1-second price
 * history for time-at-level. Everything is derived from trades only, so the same results come out of a replay of a
 * raw journal (research machine, G35) as out of the live feed. No SDK import.
 *
 * Single-writer (Pipeline drain thread). The strategy reads it through {@link com.flow.core.MarketState#orderFlow()}.
 */
public final class OrderFlowEngine implements Feature {
  public static final String FEATURE_ID = "orderflow_engine";
  private static final int KEEP_BARS = 400;
  private static final int KEEP_PRINTS = 2_000;
  private static final int ROLL_JUMP_TICKS = 300; // contract roll detection (G26)

  private final OfConfig c;
  private final ProfileBook profiles;
  private final List<OfBar> bars = new ArrayList<>();        // closed bars, oldest first
  private OfBar forming;
  private long barCount;

  private final List<AggPrint> prints = new ArrayList<>();   // closed aggressor orders + bursts, oldest first
  private final List<AggPrint> tracking = new ArrayList<>(); // response still pending
  private AggPrint openOrder;                                 // fills of the current aggressor order
  private long openOrderId = Long.MIN_VALUE;
  private AggPrint openBurst;

  // VWAP (gapAnswers §7, G23): trade-based, resets at the 17:00 CT trading-day start
  private long vwapDay = Long.MIN_VALUE;
  private double pv, vol;

  // micro structure (G12)
  private Integer swingHigh, swingLow;
  private int bosDirection;          // +1 broke above a swing high, -1 below a swing low, 0 none
  private Integer bosLevel;
  private long bosBarIndex = -1;

  // 1-second last-price ring, 60 min (G41)
  private final int[] secPrice = new int[3600];
  private final long[] secTime = new long[3600];
  private long lastSecond = Long.MIN_VALUE;

  // recent trades for consumption-vs-pulling (gapAnswers §10)
  private final Deque<long[]> recentTrades = new ArrayDeque<>(); // {timeMs, price, size}

  private Integer lastPrice;
  private long lastTimeMs;
  private String lastRollNote;

  public OrderFlowEngine(OfConfig c) {
    this.c = c;
    this.profiles = new ProfileBook(c);
  }

  @Override public String id() { return FEATURE_ID; }

  @Override
  public boolean isReady() {
    return bars.size() >= c.readyMinBars;
  }

  @Override
  public String notReadyReason() {
    return isReady() ? null : "orderflow_engine: " + bars.size() + "/" + c.readyMinBars + " execution bars closed";
  }

  /**
   * Never throws: an exception in any feature disarms the whole pipeline for the session (Pipeline.onPipelineException),
   * and this feature is research code. An error is counted and kept instead; the strategy stops new entries once
   * errorCount() > 0 (fail closed, extended Q6.46) and writes the error to its evaluation log.
   */
  @Override
  public void onEvent(Event e) {
    try {
      if (e instanceof TickEvent t) onTrade(t.eventTimeMs(), t.priceTicks(), t.volume(), t.isAskTick(), t.aggExchOrderId());
      else if (e instanceof ClockEvent ce) onClock(ce.eventTimeMs());
    } catch (RuntimeException ex) {
      errorCount++;
      lastError = ex + " at seq " + e.seq() + (ex.getStackTrace().length > 0 ? " (" + ex.getStackTrace()[0] + ")" : "");
    }
  }

  private long errorCount;
  private String lastError;
  public long errorCount() { return errorCount; }
  public String lastError() { return lastError; }

  /** Public for tests and replay tools; the live path goes through onEvent. */
  public void onTrade(long t, int price, double size, boolean isAsk, long aggOrderId) {
    if (size <= 0) return;
    if (lastPrice != null && Math.abs(price - lastPrice) > ROLL_JUMP_TICKS) {
      lastRollNote = "price jump " + lastPrice + " -> " + price + " at " + t + ": profiles and VWAP reset (G26)";
      profiles.reset();
      vwapDay = Long.MIN_VALUE;
    }
    lastPrice = price;
    lastTimeMs = t;
    recentTrades.addLast(new long[]{t, price, (long) size});
    while (!recentTrades.isEmpty() && recentTrades.peekFirst()[0] < t - 120_000) recentTrades.pollFirst();

    // VWAP
    long day = SessionClock.tradingDayStart(t);
    if (day != vwapDay) { vwapDay = day; pv = 0; vol = 0; }
    pv += price * size;
    vol += size;

    profiles.onTrade(t, price, size);

    // aggressor order aggregation (fills of one aggressor order arrive consecutively)
    if (openOrder != null && aggOrderId == openOrderId && openOrder.buy == isAsk) {
      openOrder.size += size;
      openOrder.endMs = t;
      openOrder.lastFillPrice = price;
    } else {
      closeOpenOrder();
      long barIdx = forming == null ? barCount : forming.index;
      openOrder = new AggPrint(AggPrint.Kind.SMALL, t, price, isAsk, barIdx, size);
      openOrderId = aggOrderId;
    }

    // execution bar
    if (forming == null) forming = new OfBar(barCount, t, price, c.footprintRowTicks);
    forming.add(t, price, size, isAsk);
    if (forming.trades >= c.barTrades) closeBar();

    // responses
    for (Iterator<AggPrint> it = tracking.iterator(); it.hasNext(); ) {
      AggPrint p = it.next();
      p.track(price);
      if (t - p.startMs >= c.responseCapMs) { p.finish(price, c); it.remove(); }
    }
  }

  public void onClock(long t) {
    profiles.onClock(t);
    long sec = Math.floorDiv(t, 1000L);
    if (lastPrice != null && sec != lastSecond) {
      int i = (int) Math.floorMod(sec, 3600L);
      secPrice[i] = lastPrice;
      secTime[i] = sec;
      lastSecond = sec;
    }
  }

  private void closeOpenOrder() {
    AggPrint o = openOrder;
    openOrder = null;
    if (o == null || o.size < c.smallPlayerMin) return;
    AggPrint p = o.size >= c.bigTradeMin
        ? new AggPrint(AggPrint.Kind.BIG, o.startMs, o.price, o.buy, o.barIndex, o.size)
        : o; // SMALL keeps its own object
    p.endMs = o.endMs;
    p.lastFillPrice = o.lastFillPrice;
    addPrint(p);
    if (p.kind == AggPrint.Kind.BIG) {
      boolean joins = openBurst != null && openBurst.buy == p.buy
          && p.startMs - openBurst.endMs <= c.burstGapMs
          && Math.abs(p.price - openBurst.lastFillPrice) <= c.burstPriceTicks;
      if (joins) {
        openBurst.size += p.size;
        openBurst.count++;
        openBurst.endMs = p.endMs;
        openBurst.lastFillPrice = p.lastFillPrice;
        if (openBurst.count == 2) addPrint(openBurst); // a burst exists from its second order on
      } else {
        openBurst = new AggPrint(AggPrint.Kind.BURST, p.startMs, p.price, p.buy, p.barIndex, p.size);
        openBurst.endMs = p.endMs;
        openBurst.lastFillPrice = p.lastFillPrice;
      }
    }
  }

  private void addPrint(AggPrint p) {
    prints.add(p);
    tracking.add(p);
    if (prints.size() > KEEP_PRINTS) prints.remove(0);
  }

  private void closeBar() {
    OfBar b = forming;
    forming = null;
    b.close(c);
    bars.add(b);
    barCount++;
    if (bars.size() > KEEP_BARS) bars.remove(0);
    // responses whose window is "to the close of the next bar" finish now
    for (Iterator<AggPrint> it = tracking.iterator(); it.hasNext(); ) {
      AggPrint p = it.next();
      if (b.index >= p.barIndex + 1) { p.finish(b.close, c); it.remove(); }
    }
    updateMicroStructure();
  }

  private void updateMicroStructure() {
    int k = c.swingFractalBars;
    int n = bars.size();
    if (n >= 2 * k + 1) {
      OfBar mid = bars.get(n - 1 - k);
      boolean isHigh = true, isLow = true;
      for (int i = n - 1 - 2 * k; i <= n - 1; i++) {
        if (i == n - 1 - k) continue;
        OfBar o = bars.get(i);
        if (o.high >= mid.high) isHigh = false;
        if (o.low <= mid.low) isLow = false;
      }
      if (isHigh) swingHigh = mid.high;
      if (isLow) swingLow = mid.low;
    }
    OfBar last = bars.get(n - 1);
    if (swingHigh != null && last.close > swingHigh) {
      bosDirection = 1; bosLevel = swingHigh; bosBarIndex = last.index; swingHigh = null;
    } else if (swingLow != null && last.close < swingLow) {
      bosDirection = -1; bosLevel = swingLow; bosBarIndex = last.index; swingLow = null;
    }
  }

  // ------------------------------------------------------------------ read API

  public OfConfig config() { return c; }
  public ProfileBook profiles() { return profiles; }
  public Integer lastPrice() { return lastPrice; }
  public long lastTimeMs() { return lastTimeMs; }
  public long barCount() { return barCount; }
  public OfBar forming() { return forming; }
  public List<OfBar> closedBars() { return Collections.unmodifiableList(bars); }
  public OfBar lastClosed() { return bars.isEmpty() ? null : bars.get(bars.size() - 1); }
  /** k-th most recent closed bar, 0 = last. */
  public OfBar back(int k) { int i = bars.size() - 1 - k; return i < 0 ? null : bars.get(i); }
  public List<AggPrint> prints() { return Collections.unmodifiableList(prints); }
  public String lastRollNote() { return lastRollNote; }
  public Double vwap() { return vol <= 0 ? null : pv / vol; }
  public Integer swingHigh() { return swingHigh; }
  public Integer swingLow() { return swingLow; }
  public int bosDirection() { return bosDirection; }
  public Integer bosLevel() { return bosLevel; }
  public long bosBarIndex() { return bosBarIndex; }

  public double avgAbsDelta(int n) {
    double s = 0; int k = 0;
    for (int i = bars.size() - 1; i >= 0 && k < n; i--, k++) s += Math.abs(bars.get(i).delta);
    return k == 0 ? 0 : s / k;
  }

  public double avgRange(int n) {
    double s = 0; int k = 0;
    for (int i = bars.size() - 1; i >= 0 && k < n; i--, k++) s += bars.get(i).range();
    return k == 0 ? 0 : s / k;
  }

  /** Seconds in the last {@code windowMs} during which the 1-second last price was within {@code ticks} of price. */
  public long secondsNear(int price, int ticks, long windowMs) {
    if (lastSecond == Long.MIN_VALUE) return 0;
    long from = lastSecond - windowMs / 1000L;
    long count = 0;
    for (int i = 0; i < secPrice.length; i++) {
      if (secTime[i] > from && secTime[i] <= lastSecond && Math.abs(secPrice[i] - price) <= ticks) count++;
    }
    return count;
  }

  /** Traded volume at prices in [lo, hi] since sinceMs (last 120 s kept). */
  public double tradedVolume(int lo, int hi, long sinceMs) {
    double s = 0;
    for (long[] tr : recentTrades) if (tr[0] >= sinceMs && tr[1] >= lo && tr[1] <= hi) s += tr[2];
    return s;
  }

  /** Delta by footprint row over closed bars that ended within the last windowMs (the course's Delta Profile, E5). */
  public TreeMap<Integer, Double> deltaProfile(long windowMs) {
    TreeMap<Integer, Double> out = new TreeMap<>();
    for (int i = bars.size() - 1; i >= 0; i--) {
      OfBar b = bars.get(i);
      if (b.endMs < lastTimeMs - windowMs) break;
      for (var e : b.rowsView().entrySet()) out.merge(b.rowPrice(e.getKey()), e.getValue()[1] - e.getValue()[0], Double::sum);
    }
    return out;
  }
}
