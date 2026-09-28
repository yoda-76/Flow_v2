package com.flow.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Turns runtime log lines and pipeline events into operator alerts, once each: the same key inside the throttle window
 * is suppressed (a stuck condition can log a line every second) and the next one that gets through says how many were
 * held back. The sink is failure-proof from here -- nothing thrown by it ever reaches the caller.
 */
public final class AlertDispatcher {
  /** Default: the same alert key is sent at most once a minute. */
  public static final long DEFAULT_THROTTLE_MS = 60_000L;

  private final AlertSink sink;
  private final LongSupplier clockMs;
  private final long throttleMs;
  private final Map<String, Long> lastSentMs = new HashMap<>();
  private final Map<String, Integer> suppressed = new HashMap<>();

  public AlertDispatcher(AlertSink sink, LongSupplier clockMs, long throttleMs) {
    this.sink = sink;
    this.clockMs = clockMs;
    this.throttleMs = throttleMs;
  }

  /** A runtime log line: sent if AlertPolicy says it is an alert. */
  public void onLogLine(String msg) {
    AlertPolicy.Match m = AlertPolicy.forLogLine(msg);
    if (m != null) record(m.severity(), m.key(), msg);
  }

  /** An event that is not a log line (e.g. the pipeline's FEED_STALE / DISARM). */
  public synchronized void record(String severity, String key, String message) {
    long now = clockMs.getAsLong();
    Long last = lastSentMs.get(key);
    if (last != null && now - last < throttleMs) {
      suppressed.merge(key, 1, Integer::sum);
      return;
    }
    Integer held = suppressed.remove(key);
    String text = held == null ? message : message + " (+" + held + " similar suppressed)";
    lastSentMs.put(key, now);
    try {
      sink.send(severity, key, text, now);
    } catch (RuntimeException e) {
      // an alert channel that is down must never disturb trading
    }
  }
}
