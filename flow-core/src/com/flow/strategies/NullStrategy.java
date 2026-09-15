package com.flow.strategies;

import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.Trigger;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Always returns Intent.none() (README "Build order": "a strategy that
 * always returns Intent.none()"). Requires zero features, so
 * ReadinessChecker is vacuously satisfied -- proves the arming/readiness
 * path works before any real feature exists. Wakes on BAR_CLOSE only, to
 * keep the first session's journal small and readable (README "Phasing").
 */
public final class NullStrategy implements FlowStrategy {
  private final AtomicLong intentSeq = new AtomicLong(0);

  @Override
  public String id() {
    return "null_strategy";
  }

  @Override
  public Set<String> requires() {
    return Set.of();
  }

  @Override
  public Set<Trigger> triggers() {
    return Set.of(new Trigger.BarClose());
  }

  @Override
  public void onInit(StrategyConfig cfg) {
    // nothing to configure
  }

  @Override
  public Intent onEvent(MarketState state) {
    return Intent.none(id(), intentSeq.incrementAndGet());
  }
}
