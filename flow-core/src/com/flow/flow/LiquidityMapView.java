package com.flow.flow;

import com.flow.core.DomRow;
import com.flow.core.Feature;

import java.util.List;

/**
 * The live, continuously-updated resting order book (D-58) -- built
 * directly from the raw MBO DOM stream, aggregate rows only (bid/ask
 * size per price), no per-order detail. Ready immediately (D-22 class 1:
 * "everything DOM-derived... a full book snapshot arrives on
 * subscription") once the first DOM update is processed.
 *
 * Each DOM update REPLACES this feature's state wholesale, not merges
 * into it -- the SDK hands back the full current book on every update,
 * not a delta (confirmed live, `../../motivewave/docs/dynamic/
 * findings.md`), so unlike VolumeProfileView this needs no rotation/
 * memory-management machinery at all: the live state is always exactly
 * as large as the current book (~600-700 rows/side on `@GC`), never
 * accumulates.
 *
 * bidRowsWithin()/askRowsWithin() are what the periodic bounded-window
 * snapshot (Pipeline's DOM-snapshot cadence, ~10s default) reads to
 * produce the only historical record this construct keeps -- per-update
 * detail is deliberately never persisted (see DomEvent's javadoc, D-58);
 * this live state plus periodic snapshots plus historical OHLC is the
 * whole plan for reconstructing an accurate heatmap later, per the
 * user's own design (2026-09-18).
 *
 * Deliberately no per-order tracking (individual resting order ids,
 * ages) -- see todo.md's deferred experimentation item: whether that
 * detail is even reliably available on this feed, and whether it would
 * actually support intent analysis (iceberg/manipulation detection)
 * before any code gets built toward it.
 */
public interface LiquidityMapView extends Feature {
  String FEATURE_ID = "liquidity_map";

  Integer bestBidTicks();
  Integer bestAskTicks();

  /** Resting size at a price on the bid side; null if no row there. */
  Double bidSizeAt(int priceTicks);

  /** Resting size at a price on the ask side; null if no row there. */
  Double askSizeAt(int priceTicks);

  /** Bid rows within windowTicks of the current mid-price, sorted by price. */
  List<DomRow> bidRowsWithin(int windowTicks);

  /** Ask rows within windowTicks of the current mid-price, sorted by price. */
  List<DomRow> askRowsWithin(int windowTicks);
}
