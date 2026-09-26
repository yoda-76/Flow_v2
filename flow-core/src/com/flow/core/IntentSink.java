package com.flow.core;

/**
 * Where a changed Intent goes next (README "Deciding and acting are
 * separate"). Implemented in flow-runtime, where OrderGateway actually
 * lives -- Pipeline itself stays SDK-free so it's testable under a plain
 * JDK per README "Testing".
 */
public interface IntentSink {
  void onIntentChanged(Intent intent, Event triggeringEvent);

  /**
   * Code review B3: deliver an allowed intent and say whether the runtime REFUSED to act on it. Returns null when it
   * acted (or there was nothing to do); otherwise a reason, and Pipeline then treats the intent like a risk-chain
   * block -- the risk chain does not record it, the strategy is told (onIntentRejected), and a repeat is retried --
   * so neither believes in a position the account never took. Default: always acted (dry-run, replay, observers).
   */
  default String deliver(Intent intent, Event triggeringEvent) {
    onIntentChanged(intent, triggeringEvent);
    return null;
  }
}
