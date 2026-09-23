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
  private java.util.function.DoubleConsumer anchorListener; // guarded by this

  PriceCodec(double tickSize) {
    this.tickSize = tickSize;
  }

  /**
   * Called once, with the exact anchor, when it is first set (or right
   * away if it already was). The anchor is per-activation and its exact
   * float-noisy value is what turns recorded tick offsets back into the
   * decimals live features used -- replay needs it, so the runtime journals
   * it (`price_anchor`) instead of letting it be lost.
   */
  synchronized void onAnchor(java.util.function.DoubleConsumer listener) {
    if (anchor != null) {
      listener.accept(anchor);
    } else {
      anchorListener = listener;
    }
  }

  synchronized int toTicks(double price) {
    if (anchor == null) {
      anchor = price;
      if (anchorListener != null) {
        anchorListener.accept(price);
        anchorListener = null;
      }
    }
    return (int) Math.round((price - anchor) / tickSize);
  }

  double fromTicks(int ticks) {
    Double a = anchor;
    return (a == null ? 0.0 : a) + ticks * tickSize;
  }
}
