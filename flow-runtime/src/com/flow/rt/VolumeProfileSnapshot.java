package com.flow.rt;

import com.flow.flow.ZoneView;

import java.util.List;

/**
 * Immutable copy of SdkVolumeProfileFeature's state, published via a
 * volatile field so a MotiveWave-invoked callback thread (drawing) can
 * read it safely without racing the drain thread that owns the feature.
 * See that field's javadoc for why this exists at all.
 */
record VolumeProfileSnapshot(
    Integer poc, Integer vah, Integer val, List<ZoneView> zones, double totalVolume, double totalDelta) {}
