package com.flow.flow;

import com.flow.core.DomEvent;
import com.flow.core.DomRow;
import com.flow.core.Event;
import com.flow.core.TickEvent;

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
  private volatile Integer lastTradedPriceTicks = null;
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
    if (e instanceof DomEvent de) {
      bidRows = de.bidRows();
      askRows = de.askRows();
      ready = true;
    } else if (e instanceof TickEvent te) {
      lastTradedPriceTicks = te.priceTicks();
    }
  }

  /**
   * D-63 bugfix, found live 2026-09-19, root cause confirmed (not just
   * patched blind): a genuine resting sell order was sitting ~$22 below
   * the actual market on `@GC` for many minutes straight, confirmed via
   * the raw `DOMRow.isAsk()` flag agreeing with which list it came from
   * (so not a misclassification) and via the diagnostic per-second log
   * showing `bestBid` tracking price normally while `bestAsk` stayed
   * frozen at that one far price the whole time. A real, if unusual,
   * resting order -- not a data-quality artifact -- but "the lowest ask
   * price anywhere in the full ~900-row book" is not what "best ask"
   * should mean regardless of why that one row is there.
   *
   * First attempt (filtering each side against the OTHER side's raw,
   * unfiltered extreme) turned out fragile the moment BOTH sides could
   * have a stray far-off row at once -- it kept surfacing whichever
   * extreme happened to survive its own bound check, on either side,
   * across different sessions. Replaced with a much more robust
   * reference: the last genuinely TRADED price (from TickEvent, tracked
   * here too now) -- trades are authoritative in a way resting orders
   * aren't, so "best bid" is the highest bid at or below the last trade,
   * "best ask" the lowest ask at or above it. Falls back to the
   * unfiltered extreme only if no trade has been seen yet or filtering
   * removes every row.
   */
  @Override
  public Integer bestBidTicks() {
    return maxPriceAtOrBelow(bidRows, lastTradedPriceTicks);
  }

  @Override
  public Integer bestAskTicks() {
    return minPriceAtOrAbove(askRows, lastTradedPriceTicks);
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

  /** Max price at or below ref; ref == null means no filtering. Falls back to the plain max if filtering removes everything. */
  private static Integer maxPriceAtOrBelow(List<DomRow> rows, Integer ref) {
    if (ref == null) return maxPrice(rows);
    Integer max = null;
    for (DomRow r : rows) {
      if (r.priceTicks() > ref) continue;
      if (max == null || r.priceTicks() > max) max = r.priceTicks();
    }
    return max != null ? max : maxPrice(rows);
  }

  /** Min price at or above ref; ref == null means no filtering. Falls back to the plain min if filtering removes everything. */
  private static Integer minPriceAtOrAbove(List<DomRow> rows, Integer ref) {
    if (ref == null) return minPrice(rows);
    Integer min = null;
    for (DomRow r : rows) {
      if (r.priceTicks() < ref) continue;
      if (min == null || r.priceTicks() < min) min = r.priceTicks();
    }
    return min != null ? min : minPrice(rows);
  }

  private static Double sizeAt(List<DomRow> rows, int priceTicks) {
    for (DomRow r : rows) {
      if (r.priceTicks() == priceTicks) return r.size();
    }
    return null;
  }

  @Override
  public Double imbalanceAtLevels(int n) {
    List<DomRow> bids = bidRows;
    List<DomRow> asks = askRows;
    if (bids.isEmpty() && asks.isEmpty()) return null;
    double bidSum = sumNearest(bids, n, true);  // closest-to-market bid rows = highest prices first
    double askSum = sumNearest(asks, n, false); // closest-to-market ask rows = lowest prices first
    double total = bidSum + askSum;
    return total == 0.0 ? 0.0 : (bidSum - askSum) / total;
  }

  /** Sums the n rows closest to the market (highest price first if descending, else lowest first). Not the hot path -- see class javadoc's O(n) reasoning. */
  private static double sumNearest(List<DomRow> rows, int n, boolean descending) {
    if (rows.isEmpty() || n <= 0) return 0.0;
    List<DomRow> sorted = new ArrayList<>(rows);
    sorted.sort(descending
        ? (a, b) -> Integer.compare(b.priceTicks(), a.priceTicks())
        : (a, b) -> Integer.compare(a.priceTicks(), b.priceTicks()));
    double sum = 0.0;
    int limit = Math.min(n, sorted.size());
    for (int i = 0; i < limit; i++) sum += sorted.get(i).size();
    return sum;
  }
}
