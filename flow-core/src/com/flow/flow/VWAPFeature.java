package com.flow.flow;

import com.flow.core.Event;
import com.flow.core.TickEvent;

import java.util.function.IntToDoubleFunction;

/**
 * D-57: ported from MotiveWave's published VWAP.java -- see VWAPView's
 * javadoc for the "why" and what was deliberately left out (std-dev
 * bands).
 *
 * Session-anchored to construction time (feature attach), not a
 * calendar-day boundary -- matches VolumeProfileView/FootprintView's own
 * "forward-only from attach" behavior (D-37) rather than inventing
 * session-boundary-detection machinery nothing else in this codebase has
 * built yet either. A true daily reset matching D-29/D-34's 24h session
 * boundary is deferred until that mechanism gets built generally (it
 * will eventually be needed by D-19's reversal counters and D-21's price
 * anchor too, not just this).
 *
 * Prices are decimal here, not integer ticks (D-21) -- unlike every
 * other feature, VWAP's formula (price * volume, summed, divided by
 * volume) needs no price-keyed indexing, level equality, or bucketing, so
 * D-21's rationale for integer ticks doesn't apply, and converting a
 * per-event decimal price back and forth through PriceCodec would only
 * add rounding noise for no benefit. Takes a caller-supplied
 * tick-to-decimal decoder (same nullable-function pattern Pipeline
 * already uses for its own priceDecoder), since TickEvent itself only
 * carries the integer tick offset.
 *
 * Zero SDK dependency, no rotation/memory concern (two running doubles) --
 * unlike VolumeProfile this needs none of SdkVolumeProfileFeature's E-3
 * rotation machinery.
 *
 * vwap()/totalVolume() are published via volatile fields from the start,
 * not retrofitted later -- BigTradeFeature (D-53/D-54) needed exactly
 * this fix after the fact once drawing needed cross-thread reads; doing
 * it here from day one is cheap enough to just do.
 */
public final class VWAPFeature implements VWAPView {
  private final String id;
  private final IntToDoubleFunction priceDecoder;

  private double totalPriceVolume = 0;
  private double totalVolumeSum = 0;
  private boolean ready = false;

  private volatile Double vwapSnapshot = null;
  private volatile double totalVolumeSnapshot = 0;

  public VWAPFeature(String id, IntToDoubleFunction priceDecoder) {
    this.id = id;
    this.priceDecoder = priceDecoder;
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
    return ready ? null : "no ticks processed yet (forward-only from attach, same as volume profile per D-37)";
  }

  @Override
  public void onEvent(Event e) {
    if (!(e instanceof TickEvent te)) return;
    double price = priceDecoder.applyAsDouble(te.priceTicks());
    totalPriceVolume += price * te.volume();
    totalVolumeSum += te.volume();
    ready = true;
    vwapSnapshot = totalVolumeSum == 0 ? null : totalPriceVolume / totalVolumeSum;
    totalVolumeSnapshot = totalVolumeSum;
  }

  /** Safe to call from any thread -- see the class javadoc. */
  @Override
  public Double vwap() {
    return vwapSnapshot;
  }

  /** Safe to call from any thread -- see the class javadoc. */
  @Override
  public double totalVolume() {
    return totalVolumeSnapshot;
  }
}
