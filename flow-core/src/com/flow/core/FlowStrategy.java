package com.flow.core;

import java.util.Set;

/**
 * The only part that varies (README "The strategy contract"). Compiled
 * in flow-core, which has no mwave_sdk.jar on its classpath -- a strategy
 * that reaches into com.motivewave.* fails the build, enforced by the
 * compiler rather than by review.
 */
public interface FlowStrategy {
  String id();

  /** Feature ids gating arming (ReadinessChecker). Empty = no readiness gate. */
  Set<String> requires();

  /** When this strategy gets woken (D-16). */
  Set<Trigger> triggers();

  void onInit(StrategyConfig cfg);

  /** state -> desired end state. Never a command, never an order call (README). */
  Intent onEvent(MarketState state);

  default void onFill(FillEvent fill) {}
}
