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

  /**
   * D-62/bugfix: called when RiskChain (D-61) blocks the given intent --
   * the strategy never reaches OrderGateway, so nothing was actually
   * reconciled. Found while building the first real strategy: a
   * strategy that optimistically mutates its own internal state the
   * moment it DECIDES to change position (e.g. "I'm now in a position")
   * has no way to learn that decision never actually took effect,
   * without this hook -- it would silently desync from reality the
   * first time `armed=false` (the literal default) or any other filter
   * blocks a real entry/exit attempt. Default no-op for strategies that
   * don't hold any state a rejection could invalidate (e.g. pure
   * observers that never emit a real intent).
   */
  default void onIntentRejected(Intent intent, String reason) {}
}
