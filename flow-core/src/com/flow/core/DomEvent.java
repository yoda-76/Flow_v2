package com.flow.core;

import java.util.List;

/**
 * Grows a depth array, as this type's original comment anticipated
 * (D-58): bidRows/askRows carry the full resting book as reported by one
 * DOM update, not just top-of-book. bestBid/bestAskTicks/Size stay as
 * cheap top-of-book convenience fields, computed from the same update.
 *
 * DELIBERATELY not persisted at this granularity: RawEventCodec.encode()
 * for the "dom" record type only ever writes the top-of-book fields, not
 * bidRows/askRows -- full per-update depth never reaches the raw journal
 * at all, by construction, not by a special-cased skip in Pipeline. This
 * is D-58's answer to the storage problem D-35 already measured (full
 * per-order DOM detail: ~31.5 GB/hour) -- rather than a narrower capture
 * of the same "every update" shape, DOM updates simply never get
 * persisted event-by-event; only a periodic bounded-window snapshot of
 * the LIVE, continuously-updated LiquidityMapFeature state does (see
 * Pipeline's DOM-snapshot cadence). Consequence, accepted explicitly:
 * replay (D-11) cannot reconstruct LiquidityMapFeature's state at
 * tick/update granularity -- RawEventCodec.decode() reconstructs a
 * DomEvent with empty bidRows/askRows, since that data was never
 * recorded. Only the periodic snapshot record is available for
 * historical reconstruction of the liquidity map, same tradeoff the
 * user chose directly (2026-09-18) once D-35's storage numbers made
 * event-level DOM persistence a non-option.
 */
public record DomEvent(
    long seq,
    long eventTimeMs,
    long receiptTimeMs,
    int bestBidTicks,
    double bestBidSize,
    int bestAskTicks,
    double bestAskSize,
    List<DomRow> bidRows,
    List<DomRow> askRows
) implements Event {}
