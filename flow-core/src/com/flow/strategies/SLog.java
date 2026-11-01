package com.flow.strategies;

import com.flow.bars.BarHistoryView;
import com.flow.bars.CtTime;
import com.flow.core.StrategyLog;
import com.flow.journal.Json;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Small helper the four bar strategies share for {@link StrategyLog}: a no-op until a sink is set, "ct" stamping and a
 * guard that swallows anything the sink throws (logging must never change a decision or break the strategy).
 * Callers guard with {@code if (lg.on())} so no Json is even built when nothing listens.
 */
final class SLog {
  private static final ZoneId ET = ZoneId.of("America/New_York");

  private StrategyLog sink;
  private boolean historyLogged = false;

  void set(StrategyLog s) { this.sink = s; }

  boolean on() { return sink != null; }

  /** "YYYY-MM-DD HH:MM" Chicago time of an epoch-ms instant. */
  static String ct(long ms) {
    int m = CtTime.ctMinuteOfDay(ms);
    return String.format(Locale.ROOT, "%s %02d:%02d", CtTime.ctDate(ms), m / 60, m % 60);
  }

  static String etDate(long ms) { return Instant.ofEpochMilli(ms).atZone(ET).toLocalDate().toString(); }

  /** A fresh field object, already carrying "ct" when the time is known. */
  Json j(long t) {
    Json j = Json.object();
    if (t > 0) j.field("ct", ct(t));
    return j;
  }

  void emit(long t, String event, Json fields) {
    StrategyLog s = sink;
    if (s == null) return;
    try {
      s.record(t, event, fields);
    } catch (Throwable ignored) {
      // a broken sink must never reach the strategy
    }
  }

  /** One record, once: how much history the strategy was first handed (the warm-up is NOT logged bar by bar). */
  void historyOnce(BarHistoryView h) {
    if (historyLogged || sink == null) return;
    historyLogged = true;
    int n = h.size();
    long last = n == 0 ? 0 : h.startMs(n - 1) + 60_000L;
    Json f = j(last).field("bars", n).field("appended", h.appended());
    if (n > 0) {
      f.field("firstBarStartCt", ct(h.startMs(0))).field("lastBarStartCt", ct(h.startMs(n - 1)));
    }
    emit(last, "history", f);
  }
}
