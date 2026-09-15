package com.flow.core;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * "One line in the registry" (README "Adding a strategy"). A supplier per
 * id, not a shared instance -- each session gets a fresh strategy object.
 */
public final class StrategyRegistry {
  private final Map<String, Supplier<FlowStrategy>> byId = new TreeMap<>();

  public void register(String id, Supplier<FlowStrategy> factory) {
    if (byId.containsKey(id)) {
      throw new IllegalStateException("strategy id already registered: " + id);
    }
    byId.put(id, factory);
  }

  public FlowStrategy create(String id) {
    Supplier<FlowStrategy> factory = byId.get(id);
    if (factory == null) {
      throw new IllegalArgumentException("no strategy registered with id: " + id
          + " (known: " + byId.keySet() + ")");
    }
    return factory.get();
  }

  public Set<String> knownIds() {
    return byId.keySet();
  }
}
