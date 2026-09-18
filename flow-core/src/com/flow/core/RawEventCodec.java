package com.flow.core;

import com.flow.journal.Json;
import com.flow.journal.JsonObject;

import java.util.List;

/**
 * Event <-> raw-tier JSON line, in one place on purpose. Pipeline.handle()
 * calls encode() when writing the raw journal live; ReplayHarness calls
 * decode() reading it back. Keeping both directions in the same class is
 * what stops them drifting apart -- a field added to encode() without a
 * matching case in decode() fails loudly (IllegalArgumentException) the
 * first time replay hits that record, rather than silently reading it
 * back wrong.
 */
public final class RawEventCodec {
  private RawEventCodec() {}

  public static String encode(Event e) {
    Json j = Json.object()
        .field("seq", e.seq())
        .field("eventTimeMs", e.eventTimeMs())
        .field("receiptTimeMs", e.receiptTimeMs());
    if (e instanceof TickEvent te) {
      j.field("type", "tick")
          .field("priceTicks", te.priceTicks())
          .field("volume", te.volume())
          .field("isAskTick", te.isAskTick())
          .field("bidPriceTicks", te.bidPriceTicks())
          .field("askPriceTicks", te.askPriceTicks())
          .field("exchOrderId", te.exchOrderId())
          .field("aggExchOrderId", te.aggExchOrderId());
    } else if (e instanceof BarEvent be) {
      j.field("type", "bar")
          .field("phase", be.phase().name())
          .field("openTicks", be.openTicks())
          .field("highTicks", be.highTicks())
          .field("lowTicks", be.lowTicks())
          .field("closeTicks", be.closeTicks())
          .field("volume", be.volume());
    } else if (e instanceof DomEvent de) {
      // Deliberately top-of-book only -- de.bidRows()/askRows() (full
      // depth) are never encoded here. See DomEvent's javadoc (D-58):
      // this is how full-update DOM detail stays out of the raw journal
      // entirely, by construction, not a size limit anyone has to remember.
      j.field("type", "dom")
          .field("bestBidTicks", de.bestBidTicks())
          .field("bestBidSize", de.bestBidSize())
          .field("bestAskTicks", de.bestAskTicks())
          .field("bestAskSize", de.bestAskSize());
    } else if (e instanceof ClockEvent) {
      j.field("type", "clock");
    } else if (e instanceof OrderEvent oe) {
      j.field("type", "order").field("orderId", oe.orderId()).field("status", oe.status());
    } else if (e instanceof FillEvent fe) {
      j.field("type", "fill").field("orderId", fe.orderId())
          .field("priceTicks", fe.priceTicks()).field("quantity", fe.quantity());
    } else {
      throw new IllegalArgumentException("unhandled Event subtype: " + e.getClass());
    }
    return j.build();
  }

  /**
   * Returns null for a non-event record (e.g. GAP_MARKER) rather than
   * throwing -- the raw tier can legitimately contain those, and the
   * caller (ReplayHarness) decides what a gap means for equivalence
   * rather than this method silently guessing.
   */
  public static Event decode(String line) {
    JsonObject o = JsonObject.parse(line);
    String type = o.getString("type");
    if ("GAP_MARKER".equals(type)) return null;

    long seq = o.getLong("seq");
    long eventTimeMs = o.getLong("eventTimeMs");
    long receiptTimeMs = o.getLong("receiptTimeMs");

    return switch (type) {
      case "tick" -> new TickEvent(seq, eventTimeMs, receiptTimeMs,
          o.getInt("priceTicks"), o.getInt("volume"), o.getBoolean("isAskTick"),
          o.getInt("bidPriceTicks"), o.getInt("askPriceTicks"),
          o.getLong("exchOrderId"), o.getLong("aggExchOrderId"));
      case "bar" -> new BarEvent(seq, eventTimeMs, receiptTimeMs,
          BarPhase.valueOf(o.getString("phase")),
          o.getInt("openTicks"), o.getInt("highTicks"), o.getInt("lowTicks"), o.getInt("closeTicks"),
          o.getLong("volume"));
      // bidRows/askRows reconstruct empty -- that detail was never
      // written to the raw journal (see encode() and DomEvent's
      // javadoc, D-58), so replay cannot exercise LiquidityMapFeature
      // at update granularity. Accepted, documented gap.
      case "dom" -> new DomEvent(seq, eventTimeMs, receiptTimeMs,
          o.getInt("bestBidTicks"), o.getDouble("bestBidSize"),
          o.getInt("bestAskTicks"), o.getDouble("bestAskSize"),
          List.of(), List.of());
      case "clock" -> new ClockEvent(seq, eventTimeMs, receiptTimeMs);
      case "order" -> new OrderEvent(seq, eventTimeMs, receiptTimeMs, o.getString("orderId"), o.getString("status"));
      case "fill" -> new FillEvent(seq, eventTimeMs, receiptTimeMs, o.getString("orderId"),
          o.getInt("priceTicks"), o.getInt("quantity"));
      default -> throw new IllegalArgumentException("unknown raw record type '" + type + "' in: " + line);
    };
  }
}
