package com.flow.flow;

import com.flow.core.Event;
import com.flow.core.TickEvent;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Big trades, built directly against our own TickEvent stream -- NOT a
 * wrapper around the SDK's AggregateFilter, despite D-36/E-5 having
 * originally pointed there (see D-53's full trail on why: a live
 * ClassCastException when fed through TickAdapter's proxy, and
 * ../../motivewave/docs/dynamic/findings.md 2026-09-18).
 *
 * AGGREGATION MODE (D-55, corrected from D-53's original design): time
 * + price window, not exchange order id. AggregateFilter's own javadoc
 * (../../motivewave/docs/static/javadoc/.../AggregateFilter.html) says
 * order-id aggregation only kicks in "if this id is available... it will
 * be used to aggregate (ignoring the agg period)" -- i.e. the two modes
 * (order id vs. aggPeriod) are mutually exclusive by data availability in
 * the real engine, not a user choice. D-53's first attempt used order id
 * (available on our Rithmic feed) and was live-confirmed CORRECT for
 * that mode (real CREATED->REPEAT growth on a shared order id) -- but a
 * live side-by-side against MotiveWave's own built-in "Big Trades(20)"
 * study (user, 2026-09-18) showed materially different numbers: the
 * built-in shows varied multi-contract aggregates (2, 3, 5...) where
 * order-id aggregation mostly produced 1s, because on this feed most
 * prints genuinely have distinct exchange order ids -- each fill against
 * a different resting order. The built-in's "(20)" in its title is
 * almost certainly aggPeriod=20ms, meaning it aggregates by TIME+PRICE
 * (a sweep through several resting orders within a short window),
 * independent of order id -- the more useful, human-intuitive notion of
 * "one big trade" and the one this class now implements.
 *
 * Single in-progress aggregation window, not a per-price map: trades on
 * one instrument arrive strictly sequentially in time, so at most one
 * window can be "open" at once -- matches AggregateFilter's own internal
 * shape too (a single aggTick/tickStart field pair per the SDK javadoc's
 * field list, not a map). A tick merges into the current window iff same
 * priceTicks, same isAskTick (side), and within aggPeriodMs of the last
 * tick that joined it; anything else closes the window (silently -- its
 * last published state already stands as final) and opens a new one.
 * T-3's documented repeat-emission behavior (same order id, growing
 * size) is naturally subsumed by this: same-order-id repeats are
 * necessarily same price/side/short-gap, so they merge into the same
 * window anyway without any order-id-specific handling needed.
 *
 * exchOrderId on the resulting BigTradeEvent is now best-effort
 * provenance only (the id of the tick that opened the window) -- no
 * longer the aggregation key, since one window can span several
 * different real order ids.
 *
 * Zero SDK dependency -- lives in flow-core, not flow-runtime (D-09's
 * split isn't violated, just not needed here) -- and is a plain
 * deterministic function of the recorded TickEvent stream, so D-11's
 * replay-equivalence holds by construction.
 *
 * listener (nullable) is how flow-runtime observes CREATED/UPDATED
 * events for logging -- kept out of this class so it stays a plain,
 * synchronous-testable object per D-20, with no file I/O of its own.
 *
 * recentSnapshot is a volatile immutable copy, republished only when
 * recentByKey actually changes (not on every onEvent call -- most ticks
 * don't cross minSize) -- same reasoning as VolumeProfileSnapshot/
 * FootprintSnapshot in flow-runtime: recentByKey itself is
 * drain-thread-only, but recent() is called from FlowRuntimeStudy's
 * drawing path, a MotiveWave-invoked callback thread (D-54).
 */
public final class BigTradeFeature implements BigTradeView {
  private static final int RECENT_CAP = 500;

  public interface Listener {
    void onCreated(BigTradeEvent event);
    void onUpdated(BigTradeEvent previous, BigTradeEvent updated);
  }

  private final String id;
  private final double minSize;
  private final long aggPeriodMs;
  private final Listener listener; // nullable

  // The single in-progress aggregation window -- see class javadoc for
  // why one window (not a per-price map) is enough.
  private boolean windowOpen = false;
  private int windowPriceTicks;
  private boolean windowIsAsk;
  private long windowFirstSeenMs;
  private long windowLastSeenMs;
  private long windowOpenExchOrderId;
  private double windowTotal;
  private long windowKey = -1; // -1 until windowTotal first crosses minSize

  private final LinkedHashMap<Long, BigTradeEvent> recentByKey = new LinkedHashMap<>();
  private long nextKey = 1;
  private volatile long distinctCount = 0;
  private volatile List<BigTradeEvent> recentSnapshot = List.of();

  public BigTradeFeature(String id, double minSize, long aggPeriodMs, Listener listener) {
    this.id = id;
    this.minSize = minSize;
    this.aggPeriodMs = aggPeriodMs;
    this.listener = listener;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public boolean isReady() {
    return true; // fixed threshold, D-22 class 1 -- nothing to warm up
  }

  @Override
  public String notReadyReason() {
    return null;
  }

  @Override
  public void onEvent(Event e) {
    if (!(e instanceof TickEvent te)) return;

    boolean merges = windowOpen
        && te.priceTicks() == windowPriceTicks
        && te.isAskTick() == windowIsAsk
        && (te.eventTimeMs() - windowLastSeenMs) <= aggPeriodMs;

    if (!merges) {
      windowOpen = true;
      windowPriceTicks = te.priceTicks();
      windowIsAsk = te.isAskTick();
      windowFirstSeenMs = te.eventTimeMs();
      windowOpenExchOrderId = te.exchOrderId();
      windowTotal = 0;
      windowKey = -1;
    }
    windowTotal += te.volume();
    windowLastSeenMs = te.eventTimeMs();

    if (windowTotal < minSize) return;

    if (windowKey < 0) {
      windowKey = nextKey++;
      BigTradeEvent created = new BigTradeEvent(
          windowFirstSeenMs, windowPriceTicks, windowTotal, windowIsAsk, windowOpenExchOrderId);
      recentByKey.put(windowKey, created);
      distinctCount++;
      evictIfOverCap();
      recentSnapshot = List.copyOf(recentByKey.values());
      if (listener != null) listener.onCreated(created);
    } else {
      BigTradeEvent existing = recentByKey.get(windowKey);
      BigTradeEvent updated = new BigTradeEvent(
          existing.eventTimeMs(), windowPriceTicks, windowTotal, windowIsAsk, windowOpenExchOrderId);
      recentByKey.put(windowKey, updated);
      recentSnapshot = List.copyOf(recentByKey.values());
      if (listener != null) listener.onUpdated(existing, updated);
    }
  }

  private void evictIfOverCap() {
    while (recentByKey.size() > RECENT_CAP) {
      var it = recentByKey.entrySet().iterator();
      it.next();
      it.remove();
    }
  }

  /** Safe to call from any thread -- see recentSnapshot's javadoc above. */
  @Override
  public List<BigTradeEvent> recent() {
    return recentSnapshot;
  }

  @Override
  public long sessionCount() {
    return distinctCount;
  }
}
