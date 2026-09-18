package com.flow.rt;

import com.flow.flow.BarFootprint;

/**
 * Immutable copy of SdkFootprintFeature's state, published via a
 * volatile field for the same reason VolumeProfileSnapshot exists --
 * see that class's javadoc.
 */
record FootprintSnapshot(BarFootprint current, BarFootprint lastClosed) {}
