package com.flow.core;

import com.flow.journal.Json;

/**
 * Structured strategy decision log sink (2026-11-02). A strategy calls {@code record(...)} each time it evaluates a
 * signal / changes state; whoever owns the strategy decides where the records go (the runtime: the session's
 * decisions.jsonl as {"type":"strategy_log",...}; BarReplay: a JSON-lines file via --log). Purely observational: a
 * sink must never influence a decision, and a strategy never lets a throwing sink escape (see SLog).
 */
@FunctionalInterface
public interface StrategyLog {
  /**
   * @param eventTimeMs the market time the record is about (epoch ms; usually the closing time of the bar evaluated),
   *                    or 0 when there is none (e.g. {@code init})
   * @param event       short snake_case event name (init, day_start, eval, entry_intent, ...)
   * @param fields      the event's own fields (a not-yet-built Json object; the sink merges it into its record)
   */
  void record(long eventTimeMs, String event, Json fields);

  /** The single JSON line every sink writes: {"type":"strategy_log","strategyId":..,"t":..,"event":..,...fields}. */
  static String line(String strategyId, long eventTimeMs, String event, Json fields) {
    return Json.object()
        .field("type", "strategy_log")
        .field("strategyId", strategyId)
        .field("t", eventTimeMs)
        .field("event", event)
        .merge(fields)
        .build();
  }
}
