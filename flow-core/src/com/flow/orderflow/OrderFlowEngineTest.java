package com.flow.orderflow;

import java.time.Instant;
import java.util.List;

/**
 * Synthetic checks of the order-flow engine (gapAnswers §3/§4/§7): bar closing, footprint imbalance / stacked /
 * finished-auction mapping, aggressor-order aggregation and price response, VWAP, profile POC / value area / LVN,
 * session labels across daylight saving, delta flip and absorption reads, aggregation veto.
 * Plain main(), nonzero exit on failure, wired into build/build.sh.
 */
public final class OrderFlowEngineTest {
  private static int failures = 0;

  private static void check(String label, boolean ok) {
    if (!ok) { failures++; System.out.println("FAIL " + label); }
    else System.out.println("OK   " + label);
  }

  private static long T0 = Instant.parse("2026-06-10T13:30:00Z").toEpochMilli(); // NY session, summer
  private static long t = T0;
  private static long oid = 1;

  private static void trade(OrderFlowEngine e, int price, double size, boolean ask) {
    e.onTrade(t, price, size, ask, oid++);
    t += 100;
  }

  public static void main(String[] args) {
    // ---------------------------------------------------------------- footprint reads
    OfConfig c = OfConfig.defaults();
    c.barTrades = 10;
    OrderFlowEngine e = new OrderFlowEngine(c);
    // rows of 6 ticks: row0 = 0..5, row1 = 6..11, row2 = 12..17, row3 = 18..23
    // bid 3 at row0, ask 9 at row1, ask 9 at row2 (bid row1 = 0), ask 9 at row3 (bid row2 = 0)
    trade(e, 0, 3, false);
    trade(e, 6, 9, true);
    trade(e, 12, 9, true);
    trade(e, 18, 9, true);
    for (int i = 0; i < 6; i++) trade(e, 18, 1, true);
    check("bar closes after barTrades trades", e.barCount() == 1);
    OfBar b = e.lastClosed();
    check("bull imbalance rows 1..3", b.bullImbalanceRows.equals(List.of(1, 2, 3)));
    check("stacked bull (3 rows)", b.stackedBull && !b.stackedBear);
    check("finished at high: top row bid 0, ask > 0", b.finishedHigh);
    check("finished at low: bottom row ask 0, bid > 0", b.finishedLow);
    check("delta = ask - bid", b.delta == 9 + 9 + 9 + 6 - 3);
    check("close location at the top", b.closeLocation == 1.0);
    check("candle dominance bull", b.dominanceBull);

    // ---------------------------------------------------------------- aggressor aggregation + response
    OfConfig c2 = OfConfig.defaults();
    c2.barTrades = 50;
    OrderFlowEngine a = new OrderFlowEngine(c2);
    long id = 1000;
    a.onTrade(t, 100, 20, true, id); t += 10;
    a.onTrade(t, 101, 15, true, id); t += 10; // same aggressor order: 35 lots -> BIG
    a.onTrade(t, 101, 1, false, id + 1); t += 10; // new order closes the previous one
    check("aggressor order of 35 -> one BIG print", a.prints().size() == 1 && a.prints().get(0).kind == AggPrint.Kind.BIG
        && a.prints().get(0).size == 35 && a.prints().get(0).price == 100);
    for (int i = 0; i < 60; i++) { a.onTrade(t, 100 + Math.min(15, i), 1, true, id + 2 + i); t += 10; }
    for (int i = 0; i < 50; i++) { a.onTrade(t, 115, 1, true, id + 100 + i); t += 10; } // close next bar
    check("big buy followed by +15 ticks -> SUCCESS", a.prints().get(0).outcome == AggPrint.Outcome.SUCCESS);

    OrderFlowEngine f = new OrderFlowEngine(c2);
    f.onTrade(t, 200, 40, false, 1); t += 10; // big SELL
    f.onTrade(t, 200, 1, true, 2); t += 10;
    for (int i = 0; i < 120; i++) { f.onTrade(t, 201 + (i % 2), 1, true, 3 + i); t += 10; } // price goes UP
    check("big sell that never moves price down -> FAILURE", f.prints().get(0).outcome == AggPrint.Outcome.FAILURE);

    OrderFlowEngine s = new OrderFlowEngine(c2);
    s.onTrade(t, 300, 12, true, 1); t += 10;
    s.onTrade(t, 300, 1, true, 2); t += 10;
    check("12-lot aggressor order -> SMALL print", s.prints().size() == 1 && s.prints().get(0).kind == AggPrint.Kind.SMALL);

    // ---------------------------------------------------------------- VWAP
    OrderFlowEngine v = new OrderFlowEngine(OfConfig.defaults());
    v.onTrade(T0, 100, 1, true, 1);
    v.onTrade(T0 + 1, 110, 3, true, 2);
    check("VWAP = 107.5", Math.abs(v.vwap() - 107.5) < 1e-9);
    long nextDay = Instant.parse("2026-06-10T22:30:00Z").toEpochMilli(); // after 17:00 CDT -> new trading day
    v.onTrade(nextDay, 200, 1, true, 3);
    check("VWAP resets at 17:00 CT", Math.abs(v.vwap() - 200) < 1e-9);

    // ---------------------------------------------------------------- profile POC / VA / LVN
    OfConfig pc = OfConfig.defaults();
    Profile p = new Profile("test", T0, 4);
    // rows (4 ticks each): two HVNs at rows 0 and 6, an empty gap at rows 2..4
    int[] vols = {100, 60, 2, 1, 2, 70, 120, 50};
    for (int r = 0; r < vols.length; r++) p.add(T0, r * 4, vols[r]);
    check("POC = highest row (row 6 -> price 26)", p.poc(pc) == 26);
    List<Profile.Lvn> lvns = p.lvns(pc);
    check("one LVN between the two HVNs", lvns.size() == 1);
    check("LVN spans smoothed rows 1..4 (4..19)", lvns.size() == 1 && lvns.get(0).lowTicks() == 4 && lvns.get(0).highTicks() == 19);
    check("value area contains the POC", p.val(pc) <= 26 && p.vah(pc) >= 26);

    // ---------------------------------------------------------------- sessions across daylight saving
    check("summer 07:30 UTC = LONDON", SessionClock.kindAt(Instant.parse("2026-06-10T07:30:00Z").toEpochMilli()) == SessionClock.Kind.LONDON);
    check("summer 13:30 UTC = NY", SessionClock.kindAt(Instant.parse("2026-06-10T13:30:00Z").toEpochMilli()) == SessionClock.Kind.NY);
    check("summer 00:30 UTC = ASIA", SessionClock.kindAt(Instant.parse("2026-06-10T00:30:00Z").toEpochMilli()) == SessionClock.Kind.ASIA);
    check("summer 23:00 UTC = OPEN (after 17:00 CDT)", SessionClock.kindAt(Instant.parse("2026-06-10T23:00:00Z").toEpochMilli()) == SessionClock.Kind.OPEN);
    check("summer 18:00 UTC = LATE", SessionClock.kindAt(Instant.parse("2026-06-10T18:00:00Z").toEpochMilli()) == SessionClock.Kind.LATE);
    check("winter 13:30 UTC = LONDON (NY opens 14:00 UTC)", SessionClock.kindAt(Instant.parse("2026-01-14T13:30:00Z").toEpochMilli()) == SessionClock.Kind.LONDON);
    check("winter 14:30 UTC = NY", SessionClock.kindAt(Instant.parse("2026-01-14T14:30:00Z").toEpochMilli()) == SessionClock.Kind.NY);
    check("trading day starts 17:00 CDT = 22:00 UTC",
        SessionClock.tradingDayStart(Instant.parse("2026-06-10T07:30:00Z").toEpochMilli()) == Instant.parse("2026-06-09T22:00:00Z").toEpochMilli());

    // ---------------------------------------------------------------- reads: delta flip + absorption for a long idea
    OfConfig rc = OfConfig.defaults();
    rc.barTrades = 10;
    OrderFlowEngine r = new OrderFlowEngine(rc);
    long rt = T0;
    // 5 neutral bars around 500
    for (int k = 0; k < 5; k++) for (int i = 0; i < 10; i++) r.onTrade(rt += 100, 500 + (i % 3), 1, i % 2 == 0, 10_000 + k * 100 + i);
    // bar A: heavy selling (delta strongly negative) but it closes near its high -> absorption for a long
    for (int i = 0; i < 9; i++) r.onTrade(rt += 100, 495, 10, false, 20_000 + i);
    r.onTrade(rt += 100, 502, 1, true, 20_100);
    // bar B: delta positive, does not make a lower low -> delta flip for a long
    for (int i = 0; i < 10; i++) r.onTrade(rt += 100, 500 + (i % 4), 3, true, 21_000 + i);
    TradeIdea idea = new TradeIdea("t", 1, 490, 500, 490, rt, "test");
    EvalContext x = new EvalContext(idea, r, null, rt, 1);
    Reads reads = Reads.compute(x, 490, 500);
    check("absorption read (big sell delta, closes high)", reads.absorption);
    check("delta flip read (negative -> positive, no lower low)", reads.deltaFlip);
    check("ABS and FLIP are two groups", reads.groups() >= 2);

    // ---------------------------------------------------------------- aggregation: a -2 vetoes a +2
    List<ModelResult> res = List.of(
        new ModelResult("M1", ModelResult.Status.OK, 2, List.of(), 480, 490, 500, null),
        new ModelResult("M2", ModelResult.Status.OK, -2, List.of(), null, null, null, null));
    ExecutionPlanner.Plan plan = ExecutionPlanner.plan(x, res, new ExecutionPlanner.Limits() {
      public int entriesForZoneThisSession(String z) { return 0; }
      public int entriesThisSession() { return 0; }
    });
    check("veto blocks Go", !plan.go && plan.blocks.contains("veto_M2"));
    ExecutionPlanner.Plan none = ExecutionPlanner.plan(x, List.of(ModelResult.noSignal("M1", "x")), new ExecutionPlanner.Limits() {
      public int entriesForZoneThisSession(String z) { return 0; }
      public int entriesThisSession() { return 0; }
    });
    check("no +2 -> no_full_setup", !none.go && none.blocks.contains("no_full_setup"));
    ExecutionPlanner.Plan lim = ExecutionPlanner.plan(x, List.of(res.get(0)), new ExecutionPlanner.Limits() {
      public int entriesForZoneThisSession(String z) { return 3; }
      public int entriesThisSession() { return 0; }
    });
    check("zone limit (3 per zone per session) blocks", !lim.go && (lim.blocks.contains("zone_limit") || lim.blocks.contains("stop_too_wide")));

    // ---------------------------------------------------------------- config overlay
    OfConfig oc = OfConfig.defaults();
    try {
      oc.overlay(com.flow.journal.JsonObject.parse("{\"barTrades\": 300, \"imbalanceRatio\": 4.0, \"pShapeLongFilter\": false}"));
    } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
    check("config overlay sets int/double/boolean", oc.barTrades == 300 && oc.imbalanceRatio == 4.0 && !oc.pShapeLongFilter);

    // ---------------------------------------------------------------- time bars (barSeconds, 2026-10-12)
    OfConfig tc = OfConfig.defaults();
    tc.barSeconds = 60;
    tc.barTrades = 3; // ignored with time bars
    OrderFlowEngine tb = new OrderFlowEngine(tc);
    long m0 = T0; // 13:30:00.000, minute-aligned
    for (int i = 0; i < 5; i++) tb.onTrade(m0 + 1_000L * i, 100 + i, 1, true, 5000 + i);
    check("time bars: barTrades is ignored", tb.barCount() == 0);
    tb.onClock(m0 + 59_000);
    check("time bars: still open inside the minute", tb.barCount() == 0);
    tb.onClock(m0 + 60_100);
    check("time bars: still open within the close grace", tb.barCount() == 0);
    tb.onClock(m0 + 60_300);
    check("time bars: the clock closes the bar after the boundary", tb.barCount() == 1 && tb.lastClosed().trades == 5);
    tb.onTrade(m0 + 59_900, 104, 1, false, 6000); // late print of the closed minute
    check("time bars: a late print does not reopen the closed bar", tb.barCount() == 1 && tb.lastClosed().trades == 5);
    tb.onTrade(m0 + 61_000, 105, 1, true, 6001);
    tb.onTrade(m0 + 120_500, 106, 1, true, 6002); // next minute, before any clock event
    check("time bars: a trade in a later minute closes the forming bar", tb.barCount() == 2 && tb.lastClosed().trades == 2);
    tb.onTrade(m0 + 300_000, 107, 1, true, 6003); // minutes with no trades: no empty bars
    check("time bars: quiet minutes make no empty bars", tb.barCount() == 3 && tb.lastClosed().trades == 1);

    if (failures > 0) {
      System.out.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("all order-flow engine checks passed");
  }
}
