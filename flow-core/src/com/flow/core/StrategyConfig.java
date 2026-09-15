package com.flow.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * Per-strategy parameters read from the external hand-edited store
 * (README "External inputs and config"), not from a UI. Kept as a plain
 * string map for now -- typed accessors get added once a real strategy
 * needs them, not speculatively.
 */
public final class StrategyConfig {
  private final Map<String, String> values;

  public StrategyConfig(Map<String, String> values) {
    this.values = new TreeMap<>(values);
  }

  public String get(String key) {
    return values.get(key);
  }

  public String getOrDefault(String key, String def) {
    return values.getOrDefault(key, def);
  }
}
