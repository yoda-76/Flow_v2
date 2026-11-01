package com.flow.strategies;

import com.flow.bars.replay.BarReplay;
import com.flow.core.FillEvent;
import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.StrategyLog;
import com.flow.core.Trigger;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Strategy decision logging (2026-11-02): (1) the log never changes a decision -- every intent of every strategy is
 * identical with no sink, with a recording sink and with a sink that throws; (2) a throwing sink never breaks the
 * strategy; (3) each strategy emits init / day_start / warmup_done / an evaluation record on a synthetic multi-week
 * series; (4) the record line has the documented shape. Exits non-zero on any failure.
 */
public final class StrategyLogTest {
  private static int failures = 0;
  private static int checks = 0;

  private static void check(boolean ok, String what) {
    checks++;
    if (!ok) { failures++; System.err.println("FAIL: " + what); }
  }

  /** Delegates everything to the real strategy and records every intent it returns. */
  private static final class Recorder implements FlowStrategy {
    final FlowStrategy inner;
    final List<String> intents = new ArrayList<>();

    Recorder(FlowStrategy inner) { this.inner = inner; }

    @Override public String id() { return inner.id(); }
    @Override public Set<String> requires() { return inner.requires(); }
    @Override public Set<Trigger> triggers() { return inner.triggers(); }
    @Override public void onInit(StrategyConfig cfg) { inner.onInit(cfg); }
    @Override public void setLog(StrategyLog log) { inner.setLog(log); }
    @Override public void onFill(FillEvent f) { inner.onFill(f); }
    @Override public void onIntentRejected(Intent i, String r) { inner.onIntentRejected(i, r); }
    @Override public void onFlattened(String r) { inner.onFlattened(r); }

    @Override
    public Intent onEvent(MarketState s) {
      Intent it = inner.onEvent(s);
      intents.add(it == null ? "null" : it.targetPosition() + "|" + it.stopPriceTicks() + "|" + it.targetPriceTicks()
          + "|" + it.anchorToFill() + "|" + it.reason());
      return it;
    }
  }

  /** 1440 bars a day, weekdays only, from 2024-01-02 00:00 CT; a drifting random walk with a slow cycle. */
  private static BarReplay.Bars synthetic(int weekdays, long seed, double start) {
    ZoneId ct = ZoneId.of("America/Chicago");
    long t0 = LocalDate.of(2024, 1, 2).atStartOfDay(ct).toInstant().toEpochMilli();
    int cap = weekdays * 1440;
    long[] ts = new long[cap]; double[] o = new double[cap], h = new double[cap], l = new double[cap], c = new double[cap];
    long[] v = new long[cap];
    Random rnd = new Random(seed);
    double px = start;
    int n = 0;
    LocalDate d = LocalDate.of(2024, 1, 2);
    for (int day = 0; day < weekdays; ) {
      if (d.getDayOfWeek().getValue() >= 6) { d = d.plusDays(1); continue; }
      long dayStart = d.atStartOfDay(ct).toInstant().toEpochMilli();
      double dayDrift = Math.sin(day * 0.9) * 0.08;
      for (int m = 0; m < 1440; m++) {
        double drift = dayDrift + 0.5 * Math.sin(m / 180.0 + day);
        double step = (rnd.nextDouble() - 0.5) * 2.2 + drift * 0.25;
        double open = px;
        double close = Math.round((open + step) * 4) / 4.0;
        double hi = Math.max(open, close) + Math.round(rnd.nextDouble() * 3) / 4.0;
        double lo = Math.min(open, close) - Math.round(rnd.nextDouble() * 3) / 4.0;
        ts[n] = dayStart + m * 60_000L;
        o[n] = open; h[n] = hi; l[n] = lo; c[n] = close; v[n] = 50 + rnd.nextInt(100);
        px = close;
        n++;
      }
      d = d.plusDays(1);
      day++;
    }
    return new BarReplay.Bars(java.util.Arrays.copyOf(ts, n), java.util.Arrays.copyOf(o, n), java.util.Arrays.copyOf(h, n),
        java.util.Arrays.copyOf(l, n), java.util.Arrays.copyOf(c, n), java.util.Arrays.copyOf(v, n));
  }

  private static final BarReplay.Settings ST =
      new BarReplay.Settings(0.25, 2, 1.0, 1, LocalDate.of(2024, 1, 1), null);

  private static StrategyConfig cfg() { return new StrategyConfig(Map.of("tickSize", "0.25")); }

  private static void one(String id, BarReplay.Bars bars, Set<String> required) {
    var reg = StrategyRegistrations.buildDefault();

    Recorder none = new Recorder(reg.create(id));
    BarReplay.Result r0 = BarReplay.run(bars, none, cfg(), ST);

    List<String> lines = new ArrayList<>();
    Map<String, Integer> byEvent = new TreeMap<>();
    Recorder withLog = new Recorder(reg.create(id));
    StrategyLog sink = (t, ev, f) -> {
      String line = StrategyLog.line(id, t, ev, f);
      lines.add(line);
      byEvent.merge(ev, 1, Integer::sum);
    };
    BarReplay.Result r1 = BarReplay.run(bars, withLog, cfg(), ST, sink);

    int[] thrown = {0};
    Recorder thrower = new Recorder(reg.create(id));
    BarReplay.Result r2 = null;
    try {
      r2 = BarReplay.run(bars, thrower, cfg(), ST, (t, ev, f) -> { thrown[0]++; throw new IllegalStateException("sink boom"); });
    } catch (Throwable e) {
      check(false, id + ": a throwing sink escaped into the strategy: " + e);
    }

    check(none.intents.equals(withLog.intents), id + ": intents identical with and without a log sink ("
        + none.intents.size() + " calls)");
    check(r2 != null && none.intents.equals(thrower.intents), id + ": intents identical with a throwing sink");
    check(r0.trades().size() == r1.trades().size() && r2 != null && r0.trades().size() == r2.trades().size(),
        id + ": trade count identical (" + r0.trades().size() + ")");
    check(r0.trades().equals(r1.trades()), id + ": trades identical with a log sink");
    check(thrown[0] > 0, id + ": the throwing sink was actually invoked (" + thrown[0] + ")");
    for (String ev : required) check(byEvent.getOrDefault(ev, 0) > 0, id + ": emits '" + ev + "' (events: " + byEvent + ")");
    check(!lines.isEmpty() && lines.get(0).startsWith("{\"type\":\"strategy_log\",\"strategyId\":\"" + id + "\",\"t\":0,\"event\":\"init\""),
        id + ": first line is the init record in the documented shape: " + (lines.isEmpty() ? "none" : lines.get(0)));
    boolean ctOnTimed = true;
    for (String ln : lines) {
      if (!ln.contains("\"t\":0,") && !ln.contains("\"ct\":\"")) { ctOnTimed = false; break; }
      if (!ln.startsWith("{") || !ln.endsWith("}")) { ctOnTimed = false; break; }
    }
    check(ctOnTimed, id + ": every timed record carries ct and is a closed JSON object");
    System.out.println("  " + id + ": trades=" + r0.trades().size() + " records=" + lines.size() + " " + byEvent);
  }

  public static void main(String[] args) {
    BarReplay.Bars nq = synthetic(45, 7, 17000);
    BarReplay.Bars mes = synthetic(45, 11, 4800);
    Set<String> common = Set.of("init", "history", "warmup_done", "day_start", "eval");
    one(VaultBreakStrategy.ID, nq, common);
    one(OvernightBiasOrbStrategy.ID, nq, Set.of("init", "history", "warmup_done", "day_start", "eval", "bias", "or_ready"));
    one(VwapPullbackAdxStrategy.ID, nq, Set.of("init", "history", "warmup_done", "day_start", "or_ready", "armed", "eval"));
    one(DailyAnchoredVwapStrategy.ID, mes, common);

    // Json.merge: header + fields in one object
    String l = StrategyLog.line("x", 5L, "e", com.flow.journal.Json.object().field("a", 1L).field("b", "q\"z"));
    check(l.equals("{\"type\":\"strategy_log\",\"strategyId\":\"x\",\"t\":5,\"event\":\"e\",\"a\":1,\"b\":\"q\\\"z\"}"),
        "line shape: " + l);
    String empty = StrategyLog.line("x", 5L, "e", com.flow.journal.Json.object());
    check(empty.equals("{\"type\":\"strategy_log\",\"strategyId\":\"x\",\"t\":5,\"event\":\"e\"}"), "empty fields: " + empty);

    System.out.println("StrategyLogTest: " + checks + " checks, " + failures + " failures");
    if (failures > 0) System.exit(1);
  }
}
