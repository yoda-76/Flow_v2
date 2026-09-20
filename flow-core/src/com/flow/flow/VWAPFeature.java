package com.flow.flow;

import com.flow.core.Event;
import com.flow.core.SessionBoundary;
import com.flow.core.TickEvent;

import java.util.function.IntToDoubleFunction;

/**
 * D-57: ported from MotiveWave's published VWAP.java -- see VWAPView's
 * javadoc for the "why" and what was deliberately left out (std-dev
 * bands).
 *
 * Resets on every real 17:00 CT session rollover (D-68b's
 * SessionBoundary), not construction time -- closes the "true daily
 * reset" gap this class's own D-57 note originally deferred. Checked on
 * every event, not just ticks, since a quiet book must still cross the
 * boundary on schedule via the synthetic ClockEvent (D-23) -- otherwise
 * VWAP would silently keep accumulating through an idle rollover until
 * the next real trade. Between rollovers this is still forward-only from
 * whenever the feature actually attached (D-37's pattern), same as
 * before -- the first session a feature attaches mid-way through is
 * naturally partial, exactly like every other construct here.
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
  private final SessionBoundary.Tracker sessionTracker = new SessionBoundary.Tracker();

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
    if (sessionTracker.advance(e.eventTimeMs())) {
      totalPriceVolume = 0;
      totalVolumeSum = 0;
      ready = false; // forward-only from the new session's own first tick, same bootstrap as attach (D-37)
      vwapSnapshot = null;
      totalVolumeSnapshot = 0;
    }
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
