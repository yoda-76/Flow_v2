package com.flow.core;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks a strategy's declared Feature.requires() against a registry of
 * live features (D-22: "the runtime refuses to arm until every feature
 * the active strategy uses reports ready"). A strategy requiring zero
 * features (e.g. NullStrategy) is vacuously ready -- that's what lets the
 * walking skeleton prove this mechanism without a real feature existing
 * yet.
 */
public final class ReadinessChecker {
  private final Map<String, Feature> featuresById;

  public ReadinessChecker(Map<String, Feature> featuresById) {
    this.featuresById = featuresById;
  }

  /** Empty map = ready. Non-empty = feature id -> blocking reason. */
  public Map<String, String> blockingReasons(Set<String> requiredFeatureIds) {
    Map<String, String> blocking = new TreeMap<>();
    for (String id : requiredFeatureIds) {
      Feature f = featuresById.get(id);
      if (f == null) {
        blocking.put(id, "feature not registered");
        continue;
      }
      if (!f.isReady()) {
        String reason = f.notReadyReason();
        blocking.put(id, reason == null ? "not ready (no reason given)" : reason);
      }
    }
    return blocking;
  }

  public boolean isReady(Set<String> requiredFeatureIds) {
    return blockingReasons(requiredFeatureIds).isEmpty();
  }
}
