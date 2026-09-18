package com.flow.flow;

/**
 * One price row within a single bar's footprint. Deliberately no
 * imbalance flag -- the SDK's isBidImbalance()/isAskImbalance() need a
 * threshold choice (per/delta/useDelta), and picking one now would be
 * exactly the kind of per-construct judgment call the user asked to
 * defer until after all the core constructs exist (2026-09-18). Raw
 * askVolume/bidVolume/delta are enough for a consumer to define
 * imbalance however it ends up getting decided later.
 */
public record FootprintRow(int priceTicks, double askVolume, double bidVolume, double delta) {}
