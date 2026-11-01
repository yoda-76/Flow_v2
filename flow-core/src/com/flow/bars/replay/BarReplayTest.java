package com.flow.bars.replay;

import com.flow.bars.BarHistoryView;
import com.flow.core.FillEvent;
import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.Trigger;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proves each execution rule of BarReplay on a tiny synthetic series with a scripted strategy (anchor = first open =
 * 100.00, tick 0.25, point value 2, commission 1.00, slippage 1 tick). Exits non-zero on any failure.
 */
public final class BarReplayTest {
  private static int failures = 0;
  private static int checks = 0;

  private static void check(boolean ok, String what) {
    checks++;
    if (!ok) { failures++; System.err.println("FAIL: " + what); }
  }

  private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

  /** Intent at bar index -> intent; between scripted intents a position that is still open is restated. */
  private static final class Scripted implements FlowStrategy {
    final Map<Integer, Intent> script;
    final List<FillEvent> fills = new ArrayList<>();
    final List<String> rejected = new ArrayList<>();
    int call = 0;
    Intent last = null;
    boolean inPos = false;

    Scripted(Map<Integer, Intent> script) { this.script = script; }

    @Override public String id() { return "scripted"; }
    @Override public Set<String> requires() { return Set.of(); }
    @Override public Set<Trigger> triggers() { return Set.of(new Trigger.BarClose()); }
    @Override public void onInit(StrategyConfig cfg) {}

    @Override
    public Intent onEvent(MarketState state) {
      BarHistoryView h = state.bars();
      if (h.size() != call + 1) throw new IllegalStateException("history size " + h.size() + " call " + call);
      Intent it = script.get(call);
      if (it == null) {
        // a restatement without levels keeps the resting ones (live: modifyLegs needs both levels, else a no-op)
        it = (inPos && last != null) ? new Intent("scripted", call, last.targetPosition(), null, null, "holding")
            : Intent.none("scripted", call);
      } else {
        last = it.targetPosition() != 0 ? it : last;
        if (it.targetPosition() == 0) inPos = false;
      }
      call++;
      return it;
    }

    @Override
    public void onFill(FillEvent f) {
      fills.add(f);
      if (f.role() == FillEvent.Role.ENTRY) inPos = true; else inPos = false;
    }

    @Override public void onIntentRejected(Intent i, String reason) { rejected.add(reason); inPos = false; }
  }

  private static Intent in(int dir, int stop, int target, boolean anchor) {
    return new Intent("scripted", 1, dir, stop, target, "t", anchor);
  }

  private static Intent flat() { return new Intent("scripted", 1, 0, null, null, "exit"); }

  private static BarReplay.Bars bars(double[][] ohlc) {
    long t0 = ZonedDateTime.of(2024, 1, 2, 15, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
    int n = ohlc.length;
    long[] ts = new long[n]; double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n];
    long[] v = new long[n];
    for (int i = 0; i < n; i++) {
      ts[i] = t0 + i * 60_000L;
      o[i] = ohlc[i][0]; h[i] = ohlc[i][1]; l[i] = ohlc[i][2]; c[i] = ohlc[i][3]; v[i] = 10;
    }
    return new BarReplay.Bars(ts, o, h, l, c, v);
  }

  private static final BarReplay.Settings ST =
      new BarReplay.Settings(0.25, 2, 1.0, 1, LocalDate.of(2024, 1, 1), null);

  private static BarReplay.Result run(double[][] ohlc, Scripted s) {
    return BarReplay.run(bars(ohlc), s, new StrategyConfig(Map.of()), ST);
  }

  private static Map<Integer, Intent> script(Object... kv) {
    Map<Integer, Intent> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((Integer) kv[i], (Intent) kv[i + 1]);
    return m;
  }

  // bar0 closes at 100 (signal close 0 ticks); bar1 opens 101 -> long fill 101.25 (+5 ticks vs the signal)
  private static final double[] B0 = {100, 100.5, 99.5, 100};
  private static final double[] B1 = {101, 101.5, 100.5, 101};

  public static void main(String[] args) {
    // 1. long: entry at next open + slip, anchorToFill shifts stop/target by (fill - signal close), TP exits at the level
    {
      Scripted s = new Scripted(script(0, in(1, -8, 8, true)));
      BarReplay.Result r = run(new double[][] {B0, B1, {101, 103.5, 100.8, 103}}, s);
      check(r.trades().size() == 1, "tp: one trade");
      BarReplay.Trade t = r.trades().get(0);
      check(near(t.entryPx(), 101.25), "tp: entry = next open + slip, got " + t.entryPx());
      check(near(t.stopPx(), 99.25) && near(t.targetPx(), 103.25), "tp: anchored levels " + t.stopPx() + "/" + t.targetPx());
      check("TP".equals(t.exitReason()) && near(t.exitPx(), 103.25), "tp: exit at the target, no slip");
      check(near(t.pnlUsd(), (103.25 - 101.25) * 2 - 1), "tp: pnl " + t.pnlUsd());
      check(s.fills.size() == 2 && s.fills.get(0).role() == FillEvent.Role.ENTRY
          && s.fills.get(0).fillPriceTicks() == 5 && s.fills.get(0).positionAfter() == 1, "tp: ENTRY fill delivered");
      check(s.fills.get(1).role() == FillEvent.Role.TARGET && s.fills.get(1).positionAfter() == 0
          && s.fills.get(1).fillPriceTicks() == 13, "tp: TARGET fill delivered");
      check("2024-01-02 09:01".equals(t.entryTimeCt()) && "2024-01-02 09:02".equals(t.exitTimeCt()),
          "tp: CT stamps " + t.entryTimeCt() + " / " + t.exitTimeCt());
    }
    // 1b. without anchorToFill the levels are used exactly as given
    {
      Scripted s = new Scripted(script(0, in(1, -8, 8, false)));
      BarReplay.Result r = run(new double[][] {B0, B1, {101, 103.5, 100.8, 103}}, s);
      BarReplay.Trade t = r.trades().get(0);
      check(near(t.stopPx(), 98.0) && near(t.targetPx(), 102.0), "no-anchor: absolute levels");
      check(r.trades().size() == 1 && t.exitReason().equals("TP") && near(t.exitPx(), 102.0), "no-anchor: TP at 102");
    }
    // 2. stop wins when both levels are touched in one bar; SL fills one slip beyond the stop
    {
      Scripted s = new Scripted(script(0, in(1, -8, 8, true)));
      BarReplay.Result r = run(new double[][] {B0, B1, {101, 104, 99, 101}}, s);
      BarReplay.Trade t = r.trades().get(0);
      check("SL".equals(t.exitReason()) && near(t.exitPx(), 99.0), "sl: stop wins, exit 99.25 - 1 tick, got " + t.exitPx());
      check(near(t.pnlUsd(), (99.0 - 101.25) * 2 - 1), "sl: pnl");
      check(s.fills.get(1).role() == FillEvent.Role.STOP, "sl: STOP fill");
    }
    // 3. gaps at the open: GAP_SL exits at open - slip, GAP_TP at the open
    {
      Scripted s = new Scripted(script(0, in(1, -8, 8, true)));
      BarReplay.Trade t = run(new double[][] {B0, B1, {99.0, 99.5, 98.0, 99}}, s).trades().get(0);
      check("GAP_SL".equals(t.exitReason()) && near(t.exitPx(), 98.75), "gap_sl: " + t.exitReason() + " " + t.exitPx());
      Scripted s2 = new Scripted(script(0, in(1, -8, 8, true)));
      BarReplay.Trade t2 = run(new double[][] {B0, B1, {104.0, 105, 103.5, 104}}, s2).trades().get(0);
      check("GAP_TP".equals(t2.exitReason()) && near(t2.exitPx(), 104.0), "gap_tp: " + t2.exitReason() + " " + t2.exitPx());
      check(s2.fills.get(1).role() == FillEvent.Role.TARGET, "gap_tp: TARGET fill");
    }
    // 4. the entry bar itself is walked (stop hit inside the entry bar)
    {
      Scripted s = new Scripted(script(0, in(1, -8, 8, true)));
      BarReplay.Trade t = run(new double[][] {B0, {101, 101.5, 99.0, 100}, {100, 100, 100, 100}}, s).trades().get(0);
      check("SL".equals(t.exitReason()) && "2024-01-02 09:01".equals(t.exitTimeCt()), "entry bar walked: " + t.exitReason());
    }
    // 5. strategy exit: non-zero -> flat fills at the NEXT bar's open - slip, reason EXIT, NO fill event
    {
      Scripted s = new Scripted(script(0, in(1, -8, 8, true), 2, flat()));
      BarReplay.Result r = run(new double[][] {B0, B1, {101, 101.5, 100.8, 101}, {101.5, 102, 101, 101.5}, {101.5, 102, 101, 101.5}}, s);
      check(r.trades().size() == 1, "exit: one trade");
      BarReplay.Trade t = r.trades().get(0);
      check("EXIT".equals(t.exitReason()) && near(t.exitPx(), 101.25), "exit: next open 101.5 - slip, got " + t.exitPx());
      check("2024-01-02 09:03".equals(t.exitTimeCt()), "exit: stamped with the fill bar " + t.exitTimeCt());
      check(s.fills.size() == 1, "exit: no FillEvent for a strategy exit, got " + s.fills.size());
      check(near(t.pnlUsd(), -1.0), "exit: pnl is commission only");
    }
    // 6. changed stop/target on the same position applies from the NEXT bar
    {
      // after bar1 closes the strategy raises the stop to +8 ticks (102.0); bar2 low 100.8 hits it -> SL at 101.75
      Scripted s = new Scripted(script(0, in(1, -8, 8, true), 1, in(1, 8, 40, false)));
      BarReplay.Trade t = run(new double[][] {B0, B1, {102.5, 102.5, 100.8, 101}}, s).trades().get(0);
      check("SL".equals(t.exitReason()) && near(t.exitPx(), 101.75), "retrail: " + t.exitReason() + " " + t.exitPx());
      // the new level does NOT apply to the bar it was decided on (bar1 low 100.5 < 102 but it is not a stop yet)
      check("2024-01-02 09:02".equals(t.exitTimeCt()), "retrail: applies from the next bar, not bar1");
    }
    // 7. short side mirrors: fill = open - slip, stop above, TP below
    {
      Scripted s = new Scripted(script(0, in(-1, 8, -8, true)));
      BarReplay.Result r = run(new double[][] {B0, {99, 99.5, 98.5, 99}, {99, 99.2, 96.5, 97}}, s);
      BarReplay.Trade t = r.trades().get(0);
      check(t.side() == -1 && near(t.entryPx(), 98.75), "short: entry 99 - slip, got " + t.entryPx());
      check(near(t.stopPx(), 100.75) && near(t.targetPx(), 96.75), "short: anchored levels " + t.stopPx() + "/" + t.targetPx());
      check("TP".equals(t.exitReason()) && near(t.exitPx(), 96.75), "short: TP");
      check(near(t.pnlUsd(), -(96.75 - 98.75) * 2 - 1), "short: pnl " + t.pnlUsd());
      Scripted s2 = new Scripted(script(0, in(-1, 8, -8, true)));
      BarReplay.Trade t2 = run(new double[][] {B0, {99, 99.5, 98.5, 99}, {99, 101, 96.0, 99}}, s2).trades().get(0);
      check("SL".equals(t2.exitReason()) && near(t2.exitPx(), 101.0), "short: stop wins, exit stop + slip, got " + t2.exitPx());
      Scripted s3 = new Scripted(script(0, in(-1, 8, -8, true)));
      BarReplay.Trade t3 = run(new double[][] {B0, {99, 99.5, 98.5, 99}, {101.0, 101.5, 100.5, 101}}, s3).trades().get(0);
      check("GAP_SL".equals(t3.exitReason()) && near(t3.exitPx(), 101.25), "short: GAP_SL at open + slip");
    }
    // 8. a non-zero intent of the other sign while in a position is ignored and counted
    {
      Scripted s = new Scripted(script(0, in(1, -8, 8, true), 1, in(-1, 8, -8, true)));
      BarReplay.Result r = run(new double[][] {B0, B1, {101, 101.5, 100.8, 101}, {101, 101.2, 100.8, 101}}, s);
      check(r.ignoredFlips() >= 1, "flip: counted, got " + r.ignoredFlips());
      check(r.trades().isEmpty(), "flip: the long is still open (never closed by the flip)");
    }
    // 9. bars before --from only warm up: the strategy is called, the entry is rejected and not simulated
    {
      Scripted s = new Scripted(script(0, in(1, -8, 8, true)));
      BarReplay.Settings later = new BarReplay.Settings(0.25, 2, 1.0, 1, LocalDate.of(2024, 1, 3), null);
      BarReplay.Result r = BarReplay.run(bars(new double[][] {B0, B1, {101, 103.5, 100.8, 103}}), s,
          new StrategyConfig(Map.of()), later);
      check(r.trades().isEmpty() && s.call == 3, "warm-up: strategy still called on every bar");
      check(s.rejected.size() == 1 && r.rejectedWarmup() == 1, "warm-up: entry answered with onIntentRejected");
    }
    // 10. nothing happens without an intent; pnl/commission arithmetic on a flat series
    {
      Scripted s = new Scripted(script());
      check(run(new double[][] {B0, B1}, s).trades().isEmpty(), "no intent -> no trade");
    }

    System.out.println("BarReplayTest: " + checks + " checks, " + failures + " failures");
    if (failures > 0) System.exit(1);
  }
}
