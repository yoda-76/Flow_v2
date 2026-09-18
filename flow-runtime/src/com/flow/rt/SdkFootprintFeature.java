package com.flow.rt;

import com.flow.core.BarEvent;
import com.flow.core.BarPhase;
import com.flow.core.Event;
import com.flow.core.TickEvent;
import com.flow.flow.BarFootprint;
import com.flow.flow.FootprintRow;
import com.flow.flow.FootprintView;

import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.profile.VolumeProfile;
import com.motivewave.platform.sdk.profile.VolumeRow;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps the SDK's VolumeProfile bar-scoped instead of session-scoped
 * (D-36: "same engine covers footprint"). No E-3-style rotation needed --
 * a bar is naturally short-lived (reset every close), so the underlying
 * SDK object never accumulates long enough to approach the growth rate
 * E-3 measured on a session-length profile.
 *
 * D-37's partial-bar rule: the bar already in progress when this feature
 * is constructed is discarded at its own close, never published as
 * lastClosed(), not backfilled. isReady() flips true only once a bar has
 * been tracked from its own open through its own close.
 *
 * Same single-drain-thread-only discipline as SdkVolumeProfileFeature --
 * mutable state (current/lastClosed fields) is drain-thread-only;
 * `snapshot` is the one volatile exception, published after every
 * recompute so a MotiveWave-invoked callback thread (drawing) can read a
 * consistent view without racing the drain thread. See
 * VolumeProfileSnapshot's javadoc for the fuller explanation -- same
 * pattern, same reason.
 */
final class SdkFootprintFeature implements FootprintView {
  private final String id;
  private final Instrument instrument;
  private final PriceCodec codec;
  private final int rangeTicks;

  private VolumeProfile currentProfile;
  private long currentBarStartMs;
  private BarFootprint current;
  private BarFootprint lastClosed;
  private boolean ready = false;

  private volatile FootprintSnapshot snapshot;

  // Diagnostic-only logging for live validation, same pattern as
  // SdkVolumeProfileFeature -- one line per bar close, since that's the
  // cadence footprint data actually changes at.
  private final PrintWriter log;

  SdkFootprintFeature(String id, Instrument instrument, PriceCodec codec, int rangeTicks) {
    this.id = id;
    this.instrument = instrument;
    this.codec = codec;
    this.rangeTicks = rangeTicks;
    this.currentBarStartMs = System.currentTimeMillis();
    this.currentProfile = newBarProfile(currentBarStartMs);
    this.current = BarFootprint.empty(currentBarStartMs);
    this.lastClosed = BarFootprint.empty(currentBarStartMs);
    this.snapshot = new FootprintSnapshot(current, lastClosed);

    PrintWriter w;
    try {
      w = new PrintWriter(new FileWriter(
          "C:/yadvendra/trading/FLOW_V2/logs/footprint_feature.log", true));
      w.println("# feature start " + System.currentTimeMillis() + " id=" + id + " rangeTicks=" + rangeTicks);
      w.flush();
    } catch (IOException e) {
      w = null;
    }
    this.log = w;
  }

  private VolumeProfile newBarProfile(long startTimeMs) {
    // endTime is generous and unused for anything except the SDK's own
    // constructor requirement -- we discard and recreate this object at
    // every bar close regardless of what it thinks its own window is.
    return new VolumeProfile(startTimeMs, startTimeMs + 24L * 3600_000L, instrument, rangeTicks);
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
    return ready ? null : "no full bar has closed yet (D-37: the bar in progress at attach is partial, not backfilled)";
  }

  @Override
  public void onEvent(Event e) {
    if (e instanceof TickEvent te) {
      currentProfile.onTick(TickAdapter.wrap(te, codec));
      recomputeCurrent(te.eventTimeMs());
    } else if (e instanceof BarEvent be && be.phase() == BarPhase.CLOSE) {
      onBarClose(be);
    }
  }

  private void onBarClose(BarEvent be) {
    if (ready) {
      lastClosed = current;
      logBarClose();
    } else if (log != null) {
      log.println(be.eventTimeMs() + " DISCARDED partial bar at attach (D-37), rows=" + current.rows().size());
      log.flush();
    }
    // Either this was the first (partial, discarded) close, after which
    // we're ready for the next bar tracked from its own open; or it was
    // itself a full bar just published above -- either way the bar
    // starting now gets a clean profile.
    ready = true;
    currentBarStartMs = be.eventTimeMs();
    currentProfile = newBarProfile(currentBarStartMs);
    current = BarFootprint.empty(currentBarStartMs);
    snapshot = new FootprintSnapshot(current, lastClosed);
  }

  private void logBarClose() {
    if (log == null) return;
    StringBuilder sb = new StringBuilder();
    sb.append(lastClosed.endMs())
        .append(" BAR_CLOSE startMs=").append(lastClosed.startMs())
        .append(" totalVolume=").append(lastClosed.totalVolume())
        .append(" totalDelta=").append(lastClosed.totalDelta())
        .append(" rowCount=").append(lastClosed.rows().size())
        .append(" rows=[");
    List<FootprintRow> rows = lastClosed.rows();
    for (int i = 0; i < rows.size(); i++) {
      if (i > 0) sb.append(", ");
      FootprintRow r = rows.get(i);
      sb.append(String.format("%.4f", codec.fromTicks(r.priceTicks())))
          .append(":ask=").append(r.askVolume())
          .append(",bid=").append(r.bidVolume());
    }
    sb.append(']');
    log.println(sb);
    log.flush();
  }

  void closeLog() {
    if (log != null) {
      log.flush();
      log.close();
    }
  }

  @SuppressWarnings("unchecked") // VolumeProfile.getRows() returns a raw List in this jar -- see SdkVolumeProfileFeature's note on the same issue
  private void recomputeCurrent(long nowEventTimeMs) {
    List<VolumeRow> sdkRows = currentProfile.getRows();
    List<FootprintRow> out = new ArrayList<>();
    double total = 0;
    double delta = 0;
    for (VolumeRow row : sdkRows) {
      int priceTicks = Math.round((float) codec.toTicks(row.getRowPrice()) / rangeTicks) * rangeTicks;
      double ask = row.getAskVolume();
      double bid = row.getBidVolume();
      out.add(new FootprintRow(priceTicks, ask, bid, ask - bid));
      total += ask + bid;
      delta += ask - bid;
    }
    current = new BarFootprint(currentBarStartMs, nowEventTimeMs, out, total, delta);
    snapshot = new FootprintSnapshot(current, lastClosed);
  }

  @Override
  public BarFootprint current() {
    return current;
  }

  @Override
  public BarFootprint lastClosed() {
    return lastClosed;
  }

  /** Safe to call from any thread -- see the `snapshot` field's javadoc. */
  FootprintSnapshot snapshot() {
    return snapshot;
  }
}
