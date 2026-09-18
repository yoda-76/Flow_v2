package com.flow.flow;

import com.flow.core.Feature;

/**
 * Session VWAP (D-57), ported (not copied -- doesn't compile as-is
 * against our jar, D-36's finding) from MotiveWave's own published
 * `VWAP.java` (`MotiveWave/motivewave-studies`, `ma/VWAP.java`):
 * genuinely tick-weighted, `totalPrice += price*volume; totalVolume +=
 * volume; vwap = totalPrice/totalVolume`. The rest of that file
 * (path/indicator descriptors, `DataSeriesImpl`-backed per-bar storage,
 * RTH/click-anchor UI, standard-deviation bands) is settings-panel/UI
 * plumbing we don't have and don't need -- only the core running-average
 * algorithm is ported.
 *
 * Zero SDK dependency -- lives in flow-core, not flow-runtime, same as
 * BigTradeFeature (D-53).
 */
public interface VWAPView extends Feature {
  String FEATURE_ID = "vwap";

  /** Null until the first tick is processed (forward-only, D-37's precedent extended here). */
  Double vwap();

  double totalVolume();
}
