package com.flow.strategies;

import com.flow.core.StrategyRegistry;

/**
 * The one place every known strategy gets registered. FlowRuntimeStudy
 * (live) and ReplayHarness both call buildDefault() rather than each
 * registering their own copy -- README's replay requirement is "feeds
 * recorded events back through the identical feature and strategy code,"
 * and a registry that could drift between live and replay would quietly
 * violate that the moment a second strategy is added in only one place.
 */
public final class StrategyRegistrations {
  private StrategyRegistrations() {}

  public static StrategyRegistry buildDefault() {
    StrategyRegistry r = new StrategyRegistry();
    r.register("null_strategy", NullStrategy::new);
    return r;
  }
}
