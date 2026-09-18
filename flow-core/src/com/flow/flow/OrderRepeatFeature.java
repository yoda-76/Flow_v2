package com.flow.flow;

import com.flow.core.Event;
import com.flow.core.TickEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D-53's original per-order-id aggregation, kept alive as its own
 * construct after D-55 moved BigTradeView to time+price-window
 * aggregation instead -- see OrderRepeatView's javadoc for why both are
 * wanted. No size threshold: any order id seen a second time is already
 * the signal (iceberg/hidden-liquidity analysis cares about *repetition*,
 * not print size), so this publishes on the first confirmed repeat
 * (count reaching 2) and updates on every one after that.
 *
 * `running` (every distinct order id seen this session, including
 * one-off ids never repeated) is the one unbounded-in-principle map here
 * -- most real order ids never repeat at all, so without a cap this would
 * grow with total tick count over a long session, not with anything
 * useful. Capped at RUNNING_CAP with LRU eviction (LinkedHashMap in
 * access-order mode) as an accepted simplification, not a precisely
 * measured one (unlike D-49's zone-age cost accounting) -- forgetting a
 * very old, never-repeated order id costs nothing (it was never going to
 * repeat), and the cap only risks missing a genuine repeat that happens
 * unusually far apart in tick count, not time.
 *
 * recentByKey (confirmed repeats only) is separately capped at
 * RECENT_CAP, same as BigTradeFeature.
 */
public final class OrderRepeatFeature implements OrderRepeatView {
  private static final int RUNNING_CAP = 5000;
  private static final int RECENT_CAP = 500;

  public interface Listener {
    void onCreated(OrderRepeatEvent event);
    void onUpdated(OrderRepeatEvent previous, OrderRepeatEvent updated);
  }

  private record Running(long firstSeenMs, long lastSeenMs, int count, double totalSize,
                          int lastPriceTicks, boolean lastIsAskTick) {}

  private final String id;
  private final Listener listener; // nullable

  private final LinkedHashMap<Long, Running> running =
      new LinkedHashMap<>(16, 0.75f, true); // access-order -> LRU eviction
  private final LinkedHashMap<Long, OrderRepeatEvent> recentByKey = new LinkedHashMap<>();

  public OrderRepeatFeature(String id, Listener listener) {
    this.id = id;
    this.listener = listener;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public boolean isReady() {
    return true; // D-22 class 1 -- no threshold, nothing to warm up
  }

  @Override
  public String notReadyReason() {
    return null;
  }

  @Override
  public void onEvent(Event e) {
    if (!(e instanceof TickEvent te)) return;
    long exchId = te.exchOrderId();
    if (exchId == 0) return; // no id to correlate by (E-5's "no id" sentinel)

    Running prev = running.get(exchId);
    if (prev == null) {
      running.put(exchId, new Running(te.eventTimeMs(), te.eventTimeMs(), 1, te.volume(),
          te.priceTicks(), te.isAskTick()));
      evictRunningIfOverCap();
      return;
    }

    int count = prev.count() + 1;
    double total = prev.totalSize() + te.volume();
    running.put(exchId, new Running(prev.firstSeenMs(), te.eventTimeMs(), count, total,
        te.priceTicks(), te.isAskTick()));

    OrderRepeatEvent updated = new OrderRepeatEvent(exchId, prev.firstSeenMs(), te.eventTimeMs(),
        count, total, te.priceTicks(), te.isAskTick());
    if (count == 2) {
      recentByKey.put(exchId, updated);
      evictRecentIfOverCap();
      if (listener != null) listener.onCreated(updated);
    } else {
      OrderRepeatEvent existing = recentByKey.get(exchId);
      recentByKey.put(exchId, updated);
      if (listener != null && existing != null) listener.onUpdated(existing, updated);
    }
  }

  private void evictRunningIfOverCap() {
    while (running.size() > RUNNING_CAP) {
      var it = running.entrySet().iterator();
      it.next();
      it.remove();
    }
  }

  private void evictRecentIfOverCap() {
    while (recentByKey.size() > RECENT_CAP) {
      var it = recentByKey.entrySet().iterator();
      it.next();
      it.remove();
    }
  }

  /**
   * Drain-thread-only for now -- unlike BigTradeFeature.recent(), nothing
   * outside the drain thread calls this yet (no drawing wired for this
   * construct, D-56's scope is capture + log validation only). Needs the
   * same volatile-snapshot treatment BigTradeFeature got (D-54) before
   * anything cross-thread (e.g. drawing) reads it.
   */
  @Override
  public List<OrderRepeatEvent> recent() {
    return List.copyOf(recentByKey.values());
  }
}
