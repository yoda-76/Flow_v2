package com.flow.flow;

import com.flow.core.DomEvent;
import com.flow.core.DomRow;
import com.flow.core.Event;

import java.util.ArrayList;
import java.util.List;

/**
 * D-58: each DOM update replaces bidRows/askRows wholesale (the SDK
 * hands back the full current book every time, not a delta, confirmed
 * live) -- so onEvent() just stores the two lists directly, O(1) per
 * update, no sorted structure maintained on the hot path. Reads
 * (bestBidTicks/bidSizeAt/bidRowsWithin) do an O(n) scan over the stored
 * list instead -- deliberate, not an oversight: at DOM-update rates
 * (measured 32-172/sec, D-35) an O(n log n) sorted-map rebuild on every
 * single update is real, unmeasured cost for no benefit, since reads
 * only happen at the periodic snapshot cadence (~10s, Pipeline) or at a
 * strategy's own (inherently much slower, D-16-throttled) trigger
 * cadence -- far rarer than writes. n is small regardless (~600-700
 * rows/side on `@GC`), so an O(n) scan on the rare read path is cheap in
 * absolute terms even though it's not the asymptotically "best"
 * structure -- optimize only if live measurement ever says otherwise.
 *
 * bidRows/askRows are volatile (D-59, added once drawing needed
 * cross-thread reads -- same lesson BigTradeFeature already taught,
 * D-54). Unlike BigTradeFeature's snapshot this cost nothing extra to
 * add: onEvent() already just reassigns these fields to DomEvent's own
 * already-immutable Lists (no copy happens either way), so marking them
 * volatile is a plain-write-to-volatile-write change, not a new
 * allocation on the hot DOM-update path -- there was no cheaper
 * "throttle the publish rate" version to build, unlike what this
 * class's own javadoc originally speculated a drawing pass would need.
 */
public final class LiquidityMapFeature implements LiquidityMapView {
  private final String id;

  private volatile List<DomRow> bidRows = List.of();
  private volatile List<DomRow> askRows = List.of();
  private volatile boolean ready = false;

  public LiquidityMapFeature(String id) {
    this.id = id;
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
    return ready ? null : "no DOM update received yet (D-22 class 1 -- ready as soon as the first one arrives)";
  }

  @Override
  public void onEvent(Event e) {
    if (!(e instanceof DomEvent de)) return;
    bidRows = de.bidRows();
    askRows = de.askRows();
    ready = true;
  }

  @Override
  public Integer bestBidTicks() {
    return maxPrice(bidRows); // highest bid price is best
  }

  @Override
  public Integer bestAskTicks() {
    return minPrice(askRows); // lowest ask price is best
  }

  @Override
  public Double bidSizeAt(int priceTicks) {
    return sizeAt(bidRows, priceTicks);
  }

  @Override
  public Double askSizeAt(int priceTicks) {
    return sizeAt(askRows, priceTicks);
  }

  @Override
  public List<DomRow> bidRowsWithin(int windowTicks) {
    return rowsWithin(bidRows, windowTicks);
  }

  @Override
  public List<DomRow> askRowsWithin(int windowTicks) {
    return rowsWithin(askRows, windowTicks);
  }

  private List<DomRow> rowsWithin(List<DomRow> rows, int windowTicks) {
    Integer center = midPriceTicks();
    if (center == null || rows.isEmpty()) return List.of();
    List<DomRow> out = new ArrayList<>();
    for (DomRow r : rows) {
      if (Math.abs(r.priceTicks() - center) <= windowTicks) out.add(r);
    }
    out.sort((a, b) -> Integer.compare(a.priceTicks(), b.priceTicks()));
    return out;
  }

  private Integer midPriceTicks() {
    Integer bb = bestBidTicks();
    Integer ba = bestAskTicks();
    if (bb == null && ba == null) return null;
    if (bb == null) return ba;
    if (ba == null) return bb;
    return Math.round((bb + ba) / 2.0f);
  }

  private static Integer maxPrice(List<DomRow> rows) {
    Integer max = null;
    for (DomRow r : rows) {
      if (max == null || r.priceTicks() > max) max = r.priceTicks();
    }
    return max;
  }

  private static Integer minPrice(List<DomRow> rows) {
    Integer min = null;
    for (DomRow r : rows) {
      if (min == null || r.priceTicks() < min) min = r.priceTicks();
    }
    return min;
  }

  private static Double sizeAt(List<DomRow> rows, int priceTicks) {
    for (DomRow r : rows) {
      if (r.priceTicks() == priceTicks) return r.size();
    }
    return null;
  }
}
