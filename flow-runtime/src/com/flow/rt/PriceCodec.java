package com.flow.rt;

/**
 * Converts SDK decimal prices to/from integer tick offsets (D-21).
 * Anchor is the first price seen this session -- simplest defensible v1
 * (README's "session anchor" isn't pinned to anything more specific yet,
 * e.g. session open); revisit once a real price-keyed feature needs the
 * anchor to mean something more principled than "wherever we happened to
 * start listening."
 */
final class PriceCodec {
  private final double tickSize;
  private volatile Double anchor;

  PriceCodec(double tickSize) {
    this.tickSize = tickSize;
  }

  synchronized int toTicks(double price) {
    if (anchor == null) anchor = price;
    return (int) Math.round((price - anchor) / tickSize);
  }

  double fromTicks(int ticks) {
    Double a = anchor;
    return (a == null ? 0.0 : a) + ticks * tickSize;
  }
}
