package com.flow.core;

/**
 * Where a changed Intent goes next (README "Deciding and acting are
 * separate"). Implemented in flow-runtime, where OrderGateway actually
 * lives -- Pipeline itself stays SDK-free so it's testable under a plain
 * JDK per README "Testing".
 */
public interface IntentSink {
  void onIntentChanged(Intent intent, Event triggeringEvent);
}
