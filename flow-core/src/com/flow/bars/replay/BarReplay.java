package com.flow.bars.replay;

import com.flow.bars.BarHistoryFeature;
import com.flow.bars.CtTime;
import com.flow.core.BarEvent;
import com.flow.core.BarPhase;
import com.flow.core.Feature;
import com.flow.core.FillEvent;
import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MutableMarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.StrategyLog;
import com.flow.strategies.StrategyRegistrations;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parity harness (forwardTest_plan_v1.md Phase 3): runs a LIVE FlowStrategy over a 1-minute CSV and simulates the
 * runtime's execution, so the resulting trade list can be compared with the Python backtests.
 *
 * Feeding: every 1m bar becomes BarEvent(CLOSE, eventTime = open + 60000) into a BarHistoryFeature and a
 * MutableMarketState (exactly the runtime's pieces), then strategy.onEvent(state) is called after EVERY bar. Bars of CT
 * dates before --from only warm up: the strategy is still called, but a non-flat intent is answered with
 * onIntentRejected (as the risk chain does when it blocks an entry) and ignored.
 *
 * Execution rules (prices in ticks, relative to the first bar's open):
 *  - flat -> non-zero intent: fill at the NEXT 1m bar's open + slip (long) / - slip (short). With anchorToFill the stop
 *    and target shift by (fill - signal close). FillEvent ENTRY goes to strategy.onFill.
 *  - in position: each 1m bar from the entry bar on: open beyond the stop -> exit at open -/+ slip (GAP_SL); open beyond
 *    the target -> exit at open (GAP_TP); else low/high through the stop -> stop -/+ slip (SL; the stop wins if both are
 *    touched in one bar); else through the target -> target (TP, no slip). FillEvent STOP / TARGET (positionAfter 0).
 *  - non-zero -> flat intent (strategy exit): exit at the NEXT 1m bar's open -/+ slip (EXIT); no FillEvent.
 *  - same position, changed stop/target: the new levels apply from the next bar (no re-anchoring).
 *  - a non-zero intent of the other sign while in a position: ignored and counted (live refuses flips).
 */
public final class BarReplay {
  public record Settings(double tickSize, double pointValue, double commission, double slipTicks,
                         LocalDate from, LocalDate to) {}

  public record Trade(LocalDate date, int side, String entryTimeCt, String exitTimeCt, double entryPx, double exitPx,
                      double stopPx, double targetPx, String exitReason, double pnlUsd) {}

  public record Result(List<Trade> trades, int ignoredFlips, int rejectedWarmup) {}

  /** Bars as parallel arrays; ts = bar OPEN time in epoch ms, strictly increasing. */
  public record Bars(long[] ts, double[] o, double[] h, double[] l, double[] c, long[] v) {
    public int size() { return ts.length; }
  }

  private BarReplay() {}

  // ------------------------------------------------------------------ simulation

  public static Result run(Bars b, FlowStrategy strat, StrategyConfig cfg, Settings st) {
    return run(b, strat, cfg, st, null);
  }

  /** As above, with an optional structured strategy log sink (set BEFORE onInit, like the runtime does). */
  public static Result run(Bars b, FlowStrategy strat, StrategyConfig cfg, Settings st, StrategyLog log) {
    final int n = b.size();
    if (log != null) strat.setLog(log);
    final double tick = st.tickSize();
    final double anchor = b.o()[0];
    final BarHistoryFeature hist = new BarHistoryFeature(Math.max(n, 10), 60_000L);
    Map<String, Feature> feats = new HashMap<>();
    feats.put(hist.id(), hist);
    final MutableMarketState state = new MutableMarketState(feats);
    strat.onInit(cfg);

    List<Trade> trades = new ArrayList<>();
    int flips = 0, rejected = 0;
    long seq = 0;

    int pos = 0;                          // +1 / -1 / 0
    boolean pendingEntry = false;
    int pSide = 0;
    Integer pStop = null, pTarget = null;
    boolean pAnchor = false;
    int pSignalClose = 0;
    boolean pendingExit = false;
    Double stop = null, target = null;    // ticks (double: shifted by integer ticks, so exact)
    int entryIdx = -1;
    double entryTicks = 0;
    double entryStop = 0, entryTarget = 0;

    for (int i = 0; i < n; i++) {
      LocalDate date = CtTime.ctDate(b.ts()[i]);
      boolean afterTo = st.to() != null && date.isAfter(st.to());
      if (afterTo && pos == 0 && !pendingEntry) break;
      boolean inRange = !date.isBefore(st.from()) && !afterTo;

      int ot = toTicks(b.o()[i], anchor, tick);
      int ht = toTicks(b.h()[i], anchor, tick);
      int lt = toTicks(b.l()[i], anchor, tick);
      int ct = toTicks(b.c()[i], anchor, tick);
      long endMs = b.ts()[i] + 60_000L;

      // 1. pending entry fills at this bar's open
      if (pendingEntry) {
        pendingEntry = false;
        double fill = ot + pSide * st.slipTicks();
        pos = pSide;
        entryIdx = i;
        entryTicks = fill;
        stop = pStop == null ? null : pStop + (pAnchor ? fill - pSignalClose : 0);
        target = pTarget == null ? null : pTarget + (pAnchor ? fill - pSignalClose : 0);
        entryStop = stop == null ? Double.NaN : stop;
        entryTarget = target == null ? Double.NaN : target;
        strat.onFill(new FillEvent(++seq, endMs, endMs, "E" + seq, FillEvent.Role.ENTRY, pSide > 0,
            (int) Math.round(fill), 1, pos));
      } else if (pendingExit && pos != 0) {
        // 2. strategy exit at this bar's open
        double px = ot - pos * st.slipTicks();
        trades.add(trade(b, anchor, tick, st, entryIdx, i, pos, entryTicks, px, entryStop, entryTarget, "EXIT"));
        pos = 0;
        pendingExit = false;
      }
      pendingExit = false;

      // 3. walk this bar for the resting stop / target
      if (pos != 0) {
        double xpx = Double.NaN;
        String why = null;
        if (pos > 0) {
          if (stop != null && ot <= stop) { xpx = ot - st.slipTicks(); why = "GAP_SL"; }
          else if (target != null && ot >= target) { xpx = ot; why = "GAP_TP"; }
          else if (stop != null && lt <= stop) { xpx = stop - st.slipTicks(); why = "SL"; }
          else if (target != null && ht >= target) { xpx = target; why = "TP"; }
        } else {
          if (stop != null && ot >= stop) { xpx = ot + st.slipTicks(); why = "GAP_SL"; }
          else if (target != null && ot <= target) { xpx = ot; why = "GAP_TP"; }
          else if (stop != null && ht >= stop) { xpx = stop + st.slipTicks(); why = "SL"; }
          else if (target != null && lt <= target) { xpx = target; why = "TP"; }
        }
        if (why != null) {
          trades.add(trade(b, anchor, tick, st, entryIdx, i, pos, entryTicks, xpx, entryStop, entryTarget, why));
          boolean wasLong = pos > 0;
          pos = 0;
          FillEvent.Role role = why.endsWith("SL") ? FillEvent.Role.STOP : FillEvent.Role.TARGET;
          strat.onFill(new FillEvent(++seq, endMs, endMs, "X" + seq, role, !wasLong, (int) Math.round(xpx), 1, 0));
        }
      }

      // 4. the strategy sees the closed bar
      BarEvent be = new BarEvent(++seq, endMs, endMs, BarPhase.CLOSE, ot, ht, lt, ct, b.v()[i]);
      hist.onEvent(be);
      state.bump(be);
      Intent it = strat.onEvent(state);
      int tp = it == null ? 0 : it.targetPosition();

      if (pos == 0 && !pendingEntry) {
        if (tp != 0) {
          if (!inRange) {
            strat.onIntentRejected(it, "outside --from/--to");
            rejected++;
          } else {
            pendingEntry = true;
            pSide = tp > 0 ? 1 : -1;
            pStop = it.stopPriceTicks();
            pTarget = it.targetPriceTicks();
            pAnchor = it.anchorToFill();
            pSignalClose = ct;
          }
        }
      } else if (pos != 0) {
        if (tp == 0) {
          pendingExit = true;
        } else if ((tp > 0) != (pos > 0)) {
          flips++;
        } else {
          if (it.stopPriceTicks() != null) stop = (double) it.stopPriceTicks();
          if (it.targetPriceTicks() != null) target = (double) it.targetPriceTicks();
        }
      }
    }
    return new Result(trades, flips, rejected);
  }

  private static int toTicks(double px, double anchor, double tick) {
    return (int) Math.round((px - anchor) / tick);
  }

  private static Trade trade(Bars b, double anchor, double tick, Settings st, int entryIdx, int exitIdx, int side,
                             double entryTicks, double exitTicks, double stopT, double targetT, String why) {
    double ep = anchor + entryTicks * tick;
    double xp = anchor + exitTicks * tick;
    double pnl = side * (xp - ep) * st.pointValue() - st.commission();
    return new Trade(CtTime.ctDate(b.ts()[entryIdx]), side, ctStamp(b.ts()[entryIdx]), ctStamp(b.ts()[exitIdx]),
        ep, xp, Double.isNaN(stopT) ? Double.NaN : anchor + stopT * tick,
        Double.isNaN(targetT) ? Double.NaN : anchor + targetT * tick, why, pnl);
  }

  private static String ctStamp(long ms) {
    int m = CtTime.ctMinuteOfDay(ms);
    return String.format(Locale.ROOT, "%s %02d:%02d", CtTime.ctDate(ms), m / 60, m % 60);
  }

  // ------------------------------------------------------------------ CSV + summary

  /** Reads timestampMs,open,high,low,close,volume; sorts by time and keeps the LAST row of any duplicate timestamp. */
  public static Bars readCsv(Path path) throws IOException {
    int cap = 1 << 16;
    long[] ts = new long[cap];
    double[] o = new double[cap], h = new double[cap], l = new double[cap], c = new double[cap];
    long[] v = new long[cap];
    int n = 0;
    try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      r.readLine(); // header
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isBlank()) continue;
        String[] f = line.split(",");
        if (f.length < 6) continue;
        long t;
        try { t = Long.parseLong(f[0].trim()); } catch (NumberFormatException e) { continue; }
        if (n == cap) {
          cap *= 2;
          ts = Arrays.copyOf(ts, cap); o = Arrays.copyOf(o, cap); h = Arrays.copyOf(h, cap);
          l = Arrays.copyOf(l, cap); c = Arrays.copyOf(c, cap); v = Arrays.copyOf(v, cap);
        }
        ts[n] = t;
        o[n] = Double.parseDouble(f[1]); h[n] = Double.parseDouble(f[2]);
        l[n] = Double.parseDouble(f[3]); c[n] = Double.parseDouble(f[4]);
        v[n] = (long) Double.parseDouble(f[5]);
        n++;
      }
    }
    boolean sorted = true;
    for (int i = 1; i < n; i++) if (ts[i] <= ts[i - 1]) { sorted = false; break; }
    if (sorted) {
      return new Bars(Arrays.copyOf(ts, n), Arrays.copyOf(o, n), Arrays.copyOf(h, n), Arrays.copyOf(l, n),
          Arrays.copyOf(c, n), Arrays.copyOf(v, n));
    }
    final long[] tsf = ts;
    Integer[] idx = new Integer[n];
    for (int i = 0; i < n; i++) idx[i] = i;
    Arrays.sort(idx, (x, y) -> Long.compare(tsf[x], tsf[y]));   // stable: equal timestamps keep file order
    long[] t2 = new long[n]; double[] o2 = new double[n], h2 = new double[n], l2 = new double[n], c2 = new double[n];
    long[] v2 = new long[n];
    int m = 0;
    for (int k = 0; k < n; k++) {
      int i = idx[k];
      if (m > 0 && t2[m - 1] == ts[i]) m--;   // duplicate: the later row wins
      t2[m] = ts[i]; o2[m] = o[i]; h2[m] = h[i]; l2[m] = l[i]; c2[m] = c[i]; v2[m] = v[i];
      m++;
    }
    return new Bars(Arrays.copyOf(t2, m), Arrays.copyOf(o2, m), Arrays.copyOf(h2, m), Arrays.copyOf(l2, m),
        Arrays.copyOf(c2, m), Arrays.copyOf(v2, m));
  }

  public static String summary(String strategyId, Result r) {
    List<Trade> t = r.trades();
    int n = t.size();
    int wins = 0;
    double gw = 0, gl = 0, net = 0, cum = 0, peak = 0, dd = 0;
    for (Trade x : t) {
      if (x.pnlUsd() > 0) { wins++; gw += x.pnlUsd(); } else if (x.pnlUsd() < 0) gl -= x.pnlUsd();
      net += x.pnlUsd();
      cum += x.pnlUsd();
      peak = Math.max(peak, cum);
      dd = Math.max(dd, peak - cum);
    }
    Map<String, Integer> reasons = new java.util.TreeMap<>();
    for (Trade x : t) reasons.merge(x.exitReason(), 1, Integer::sum);
    return String.format(Locale.ROOT,
        "strategy=%s trades=%d win%%=%.1f PF=%.2f net=$%.2f maxDD=$%.2f exits=%s ignoredFlips=%d rejectedOutsideRange=%d",
        strategyId, n, n == 0 ? 0.0 : 100.0 * wins / n, gl > 0 ? gw / gl : Double.POSITIVE_INFINITY, net, dd,
        reasons, r.ignoredFlips(), r.rejectedWarmup());
  }

  private static String px(double v) {
    if (Double.isNaN(v)) return "";
    String s = String.format(Locale.ROOT, "%.4f", v);
    s = s.replaceAll("0+$", "");
    return s.endsWith(".") ? s + "0" : s;
  }

  public static void writeTrades(Path file, List<Trade> trades) throws IOException {
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
      w.println("date,side,entry_time_ct,exit_time_ct,entry_px,exit_px,stop_px,target_px,exit_reason,pnl_usd");
      for (Trade x : trades) {
        w.println(x.date() + "," + (x.side() > 0 ? "L" : "S") + "," + x.entryTimeCt() + "," + x.exitTimeCt() + ","
            + px(x.entryPx()) + "," + px(x.exitPx()) + "," + px(x.stopPx()) + "," + px(x.targetPx()) + ","
            + x.exitReason() + "," + String.format(Locale.ROOT, "%.2f", x.pnlUsd()));
      }
    }
  }

  // ------------------------------------------------------------------ CLI

  public static void main(String[] args) throws Exception {
    Map<String, String> a = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("bad args near " + args[i]);
      a.put(args[i].substring(2), args[i + 1]);
    }
    for (String req : new String[] {"csv", "strategy", "out"}) {
      if (!a.containsKey(req)) throw new IllegalArgumentException("missing --" + req);
    }
    double tick = Double.parseDouble(a.getOrDefault("tick", "0.25"));
    Settings st = new Settings(tick, Double.parseDouble(a.getOrDefault("point-value", "2")),
        Double.parseDouble(a.getOrDefault("comm", "1.0")), Double.parseDouble(a.getOrDefault("slip-ticks", "1")),
        a.containsKey("from") ? LocalDate.parse(a.get("from")) : LocalDate.of(1900, 1, 1),
        a.containsKey("to") ? LocalDate.parse(a.get("to")) : null);
    Map<String, String> cfgMap = new HashMap<>();
    cfgMap.put("tickSize", String.valueOf(tick));
    if (a.containsKey("config")) cfgMap.put("strategyConfigPath", a.get("config"));
    FlowStrategy strat = StrategyRegistrations.buildDefault().create(a.get("strategy"));
    Bars bars = readCsv(Path.of(a.get("csv")));
    System.out.println("bars=" + bars.size());
    Result r;
    if (a.containsKey("log")) {
      // Same record shape as the runtime's decisions.jsonl strategy_log lines. Records about bars before --from (pure
      // warm-up) are dropped, except init / history / warmup_done.
      final LocalDate from = st.from();
      final String sid = a.get("strategy");
      Path logPath = Path.of(a.get("log"));
      if (logPath.getParent() != null) Files.createDirectories(logPath.getParent());
      try (PrintWriter lw = new PrintWriter(Files.newBufferedWriter(logPath, StandardCharsets.UTF_8))) {
        StrategyLog sink = (t, ev, f) -> {
          boolean always = ev.equals("init") || ev.equals("history") || ev.equals("warmup_done");
          if (!always && t > 0 && CtTime.ctDate(t).isBefore(from)) return;
          lw.println(StrategyLog.line(sid, t, ev, f));
        };
        r = run(bars, strat, new StrategyConfig(cfgMap), st, sink);
      }
    } else {
      r = run(bars, strat, new StrategyConfig(cfgMap), st);
    }
    Path out = Path.of(a.get("out"));
    Files.createDirectories(out);
    writeTrades(out.resolve("trades.csv"), r.trades());
    String s = summary(a.get("strategy"), r);
    Files.writeString(out.resolve("summary.txt"), s + System.lineSeparator(), StandardCharsets.UTF_8);
    System.out.println(s);
  }
}
