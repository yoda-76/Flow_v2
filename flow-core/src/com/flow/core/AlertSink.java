package com.flow.core;

/**
 * Where an operator-facing alert goes. The system needs a way to TELL the operator when it disarms, trips the kill
 * switch, loses the feed, sees a non-simulated account, ... because a 24/7 run has nobody watching the screen.
 *
 * Today the only implementation is {@link LogFileAlertSink} (a placeholder: appends to logs/alerts.log). The user will
 * add a Telegram bot later: a TelegramAlertSink implements this same interface and is swapped in where the runtime
 * builds its dispatcher -- nothing else changes. Implementations must never throw into the caller and should not block
 * for long (the dispatcher swallows exceptions, but a slow network call on the drain thread would stall trading).
 */
public interface AlertSink {
  /**
   * severity: "ALERT" (needs a human), "WARN", or "INFO" (a notable state change). key: a short stable id, e.g.
   * "FEED_STALE". timeMs: epoch milliseconds when the alert was raised.
   */
  void send(String severity, String key, String message, long timeMs);
}
