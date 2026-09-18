package com.flow.flow;

import com.flow.core.Feature;

/**
 * Bar-scoped volume profile (D-36): same SDK engine as VolumeProfileView,
 * same integer-tick vocabulary (D-21), reset every bar close instead of
 * accumulated for the whole session. No rotation/memory-management logic
 * needed here the way VolumeProfileView needed the E-3 fix -- a bar is
 * naturally short-lived, so the underlying SDK object never lives long
 * enough to approach the growth rate E-3 measured.
 *
 * isReady() is false until the first FULL bar has closed (D-37: the bar
 * already in progress when the feature attaches is partial -- discarded,
 * never published as lastClosed, not backfilled from historical ticks).
 */
public interface FootprintView extends Feature {
  String FEATURE_ID = "footprint";

  /** The bar currently forming, live-updating every tick. Never null. */
  BarFootprint current();

  /** The most recently CLOSED full bar. Never null; empty rows until isReady() (D-37). */
  BarFootprint lastClosed();
}
