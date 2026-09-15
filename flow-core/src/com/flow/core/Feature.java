package com.flow.core;

/**
 * A feature owns mutable, incrementally updated internal state and is
 * correct after every event (README "Features"), never deferring work to
 * a bar close. No feature is registered yet in the walking skeleton --
 * this interface exists so ReadinessChecker and FlowStrategy.requires()
 * have something real to compile against before the first real feature
 * (volume profile) is built.
 */
public interface Feature {
  String id();
  void onEvent(Event e);

  /** True once this feature's readings mean something (README "Readiness, not warmup"). */
  boolean isReady();

  /** Human-readable reason arming is blocked; null once ready. Journaled, never guessed. */
  String notReadyReason();
}
