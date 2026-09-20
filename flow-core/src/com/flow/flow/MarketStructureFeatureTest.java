package com.flow.flow;

import com.flow.core.BarEvent;
import com.flow.core.BarPhase;

import java.util.Set;

/**
 * Direct synthetic-bar-sequence test for MarketStructureFeature (D-60,
 * reworked D-74 against the fully reviewed rules -- all 10 points in
 * `docs/dynamic/marketStructureRules.md` are now resolved directly by
 * the user, `decisions.md` D-71/D-72/D-73). Same discipline as
 * TriggerEvaluatorTest -- no MotiveWave involved.
 *
 * This supersedes the pre-rework version of this file entirely (which
 * tested `marketStructureRulesTemp.md`'s now-superseded guesses) --
 * every scenario below is re-derived by hand against the REVIEWED rules
 * and cross-checked by actually running it against the real
 * implementation, not just reasoned about.
 */
public final class MarketStructureFeatureTest {
  private static int failures = 0;

  private static void check(String label, Object got, Object want) {
    boolean ok = got == null ? want == null : got.equals(want);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static void check(String label, boolean got, boolean want) {
    check(label, (Object) got, (Object) want);
  }

  /** Records every callback verbatim so tests can assert on them after the fact. */
  static final class RecordingListener implements MarketStructureFeature.Listener {
    int pullbackValidCount = 0;
    MarketStructureFeature.Bar lastPullbackFirstBar;

    int tjlFormedCount = 0;
    ZoneRange lastTjl1Formed;
    ZoneRange lastTjl2Formed;
    Set<MarketStructureView.TradeableLevel> lastTjlFormedTradeable;

    int flipCount = 0;
    MarketStructureView.Trend lastFlipTrend;
    ZoneRange lastFlipAPlus;
    ZoneRange lastFlipSbrRbs;
    ZoneRange lastFlipDtOrDb;
    Set<MarketStructureView.TradeableLevel> lastFlipTradeable;

    @Override
    public void onPullbackValid(MarketStructureView.Trend trend, MarketStructureFeature.Bar firstBar) {
      pullbackValidCount++;
      lastPullbackFirstBar = firstBar;
    }

    @Override
    public void onTjlFormed(MarketStructureView.Trend trend, ZoneRange tjl1, ZoneRange tjl2,
                             Set<MarketStructureView.TradeableLevel> tradeableLevels) {
      tjlFormedCount++;
      lastTjl1Formed = tjl1;
      lastTjl2Formed = tjl2;
      lastTjlFormedTradeable = tradeableLevels;
    }

    @Override
    public void onFlip(MarketStructureView.Trend newTrend, ZoneRange aPlus, ZoneRange sbrRbs, ZoneRange dtOrDb,
                        Set<MarketStructureView.TradeableLevel> tradeableLevels) {
      flipCount++;
      lastFlipTrend = newTrend;
      lastFlipAPlus = aPlus;
      lastFlipSbrRbs = sbrRbs;
      lastFlipDtOrDb = dtOrDb;
      lastFlipTradeable = tradeableLevels;
    }
  }

  private static long seq = 0;

  private static void closeBar(MarketStructureFeature f, int open, int high, int low, int close) {
    seq++;
    f.onEvent(new BarEvent(seq, seq * 60_000L, seq * 60_000L, BarPhase.CLOSE, open, high, low, close, 0L));
  }

  public static void main(String[] args) {
    testChochBootstrapUsesOpen();
    testSlidingPairValidationAndBodyHeightZoneMath();
    testNonConfirmingCandleIsIgnored_D65();
    testChochDrivenFirstFlip_NoRealPair();
    testChainedFlipsAndTradeableLevelsLifecycle();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all MarketStructureFeature synthetic checks passed.");
  }

  /** Points 3/7/8: CHOCH = the first-processed bar's OPEN (corrected from CLOSE), never tradeable. */
  private static void testChochBootstrapUsesOpen() {
    MarketStructureFeature f = new MarketStructureFeature("ms", null);
    check("Bootstrap: not ready before any bar", f.isReady(), false);

    closeBar(f, 1000, 1010, 990, 1005); // open=1000, close=1005 -- deliberately different, to distinguish
    check("Bootstrap: ready after first bar processed", f.isReady(), true);
    check("Bootstrap: trend defaults UP", f.trend(), MarketStructureView.Trend.UP);
    check("Bootstrap: CHOCH is the OPEN (1000), not the close (1005) -- point 3 correction",
        f.lastTjl2(), new ZoneRange(1000, 1000));
    check("Bootstrap: no TJL1 yet", f.lastTjl1(), null);
    check("Bootstrap: nothing tradeable while only CHOCH is active (§1a)", f.tradeableLevels(), Set.of());
  }

  /**
   * Point 1: sliding consecutive-pair validation (current vs. immediately
   * preceding candle), NOT anchored to the run's first candle -- proven
   * by a pair (bar3 vs bar2) that fails to validate, followed by a pair
   * (bar4 vs bar3) that succeeds where an anchored-to-bar2 check would
   * NOT have. Point 4: 1% of body height, not high-low range -- proven
   * with numbers chosen so the two bases give visibly different zones.
   */
  private static void testSlidingPairValidationAndBodyHeightZoneMath() {
    RecordingListener rec = new RecordingListener();
    MarketStructureFeature f = new MarketStructureFeature("ms", rec);
    // CHOCH anchor (now the OPEN, 50) kept far below the action so flip-watch
    // never trips prematurely during pullback formation below.
    closeBar(f, 50, 58, 40, 55);

    closeBar(f, 1005, 1050, 800, 1000); // bar2: red (close<open), run's first candle, low=800, small body (5)
    check("Pullback: forming after first counter-trend candle", f.pullbackState(),
        MarketStructureView.PullbackState.FORMING);

    closeBar(f, 850, 860, 820, 830); // bar3: red, close=830 -- NOT < bar2.low(800) -> doesn't validate against bar2
    check("Pullback: still forming -- bar3 doesn't validate against bar2 (anchored OR sliding, same here)",
        f.pullbackState(), MarketStructureView.PullbackState.FORMING);

    // bar4: red, close=810 -- validates against bar3.low(820) [sliding: 810<820]
    // but would NOT validate against bar2.low(800) [anchored: 810<800 is false].
    // Big body (200) vs. a much bigger full range (H-L=615), so body-based and
    // range-based 1% offsets diverge sharply (2 vs 6) -- see the TJL2 assertion below.
    closeBar(f, 1010, 1015, 400, 810);
    check("Pullback: VALID -- bar4 validates against the PRECEDING candle (bar3), proving sliding, not anchored",
        f.pullbackState(), MarketStructureView.PullbackState.VALID);
    check("Pullback: onPullbackValid fired exactly once", rec.pullbackValidCount, 1);
    check("Pullback: reported firstBar is still the run's TRUE first candle (bar2, low=800), not bar3",
        rec.lastPullbackFirstBar.lowTicks(), 800);

    // Continuation: highBar across [bar2(H=1050),bar3(H=860),bar4(H=1015)] = bar2.
    // lowBar across [bar2(L=800),bar3(L=820),bar4(L=400)] = bar4.
    closeBar(f, 810, 1100, 805, 1080); // bar5: green, close=1080 > pullbackExtreme (bar2.high=1050) -- confirms
    check("TJL: onTjlFormed fired exactly once", rec.tjlFormedCount, 1);

    // TJL1 from bar2 (O=1005,C=1000 -> body=5 -> bodyOffset=round(0.05)=0):
    // zone = of(high=1050, bodyTop(1005)-0) = [1005,1050].
    check("TJL1 uses bar2's BODY height (5, ~0 offset), not its huge range (250, offset 3)",
        f.lastTjl1(), new ZoneRange(1005, 1050));
    // TJL2 from bar4 (O=1010,C=810 -> body=200 -> bodyOffset=round(2.0)=2):
    // zone = of(low=400, bodyBottom(810)+2=812) = [400,812].
    // The now-corrected old (range-based) formula would have used range=1015-400=615,
    // offset=round(6.15)=6, giving [400,816] instead -- a visibly different zone.
    check("TJL2 uses bar4's BODY height (200, offset 2) -> [400,812], not range-based [400,816]",
        f.lastTjl2(), new ZoneRange(400, 812));
    check("Pullback resets to NONE after TJL forms", f.pullbackState(), MarketStructureView.PullbackState.NONE);
    check("Tradeable set is {TJL2} in normal operation", f.tradeableLevels(), Set.of(MarketStructureView.TradeableLevel.TJL2));
  }

  /** D-65 bugfix, unaffected by this rework -- still correct against the new validation/zone rules. */
  private static void testNonConfirmingCandleIsIgnored_D65() {
    RecordingListener rec = new RecordingListener();
    MarketStructureFeature f = new MarketStructureFeature("ms", rec);
    closeBar(f, 50, 58, 40, 55); // bootstrap, CHOCH=50, kept out of the way

    closeBar(f, 1005, 1006, 800, 850);  // bar2: red, run's first candle (high=1006, low=800)
    closeBar(f, 850, 860, 700, 750);    // bar3: red, close=750 < bar2.low(800) -> validates
    check("Sanity: VALID before the interloper candle", f.pullbackState(), MarketStructureView.PullbackState.VALID);

    // Interloper: green, does NOT close above the pullback extreme (bar2.high=1006).
    closeBar(f, 750, 9999, 740, 900); // high=9999 deliberately absurd -- must be ignored, not folded in
    check("D-65: stays VALID after a non-confirming candle", f.pullbackState(), MarketStructureView.PullbackState.VALID);
    check("D-65: no spurious TJL formed off the interloper", rec.tjlFormedCount, 0);

    closeBar(f, 900, 1100, 890, 1080); // real continuation, close=1080 > 1006 (the real extreme, not 9999)
    check("D-65: continuation measured against the real extreme, not the interloper", rec.tjlFormedCount, 1);
    // TJL1 from bar2 (O=1005,C=850 -> body=155 -> bodyOffset=round(1.55)=2): of(1006,1005-2=1003)=[1003,1006].
    check("D-65: TJL1 built from the real high candle, unaffected by the interloper",
        f.lastTjl1(), new ZoneRange(1003, 1006));
  }

  /**
   * The CHOCH-driven initial transition (no real TJL pair has ever
   * formed): trend flips, but per the implementation (performFlip's
   * `!hasRealPair` branch returns before calling listener.onFlip at all)
   * -- no onFlip callback fires for this first flip. Unaffected by this
   * rework (none of the 10 reviewed points touch this specific branch),
   * still flagged as a real, worth-revisiting gap (see D-68's original
   * note) -- this test locks in current behavior, not endorses it.
   */
  private static void testChochDrivenFirstFlip_NoRealPair() {
    RecordingListener rec = new RecordingListener();
    MarketStructureFeature f = new MarketStructureFeature("ms", rec);

    closeBar(f, 1000, 1010, 990, 1005); // CHOCH = open = 1000 (point 3), no real pair yet
    check("Sanity: no real pair before this flip", f.lastTjl1(), null);

    closeBar(f, 1005, 1005, 950, 960); // close=960 < 1000 (count=1)
    closeBar(f, 960, 960, 900, 910);   // close=910 < 1000 (count=2 -> flips)

    check("CHOCH-driven flip: trend becomes DOWN", f.trend(), MarketStructureView.Trend.DOWN);
    check("CHOCH-driven flip: onFlip does NOT fire (no A+ exists yet, unchanged behavior)", rec.flipCount, 0);
    check("CHOCH-driven flip: CHOCH zone (1000,1000) is untouched", f.lastTjl2(), new ZoneRange(1000, 1000));
    check("CHOCH-driven flip: still nothing tradeable", f.tradeableLevels(), Set.of());
  }

  /**
   * The big one: forms a real TJL pair, then chains through 3 flips
   * (CHOCH1/2/3), exercising points 5, 6, 9 and 10 together against a
   * single coherent bar sequence -- exactly the scenario
   * marketStructureRules.md's own worked example describes
   * (`CHOCH1 -> A+ + SBR + DT -> CHOCH2 -> DT + DB -> CHOCH3 -> DT + DB`),
   * then ends the chain with a fresh real TJL pair (point 10).
   */
  private static void testChainedFlipsAndTradeableLevelsLifecycle() {
    RecordingListener rec = new RecordingListener();
    MarketStructureFeature f = new MarketStructureFeature("ms", rec);

    // -- Setup: bootstrap, then form a real UP-trend TJL pair. --
    closeBar(f, 50, 58, 40, 55); // bootstrap, CHOCH=50, kept out of the way
    closeBar(f, 1000, 1010, 900, 980);  // bar2: red, run's first candle (high=1010, low=900)
    closeBar(f, 980, 985, 850, 860);    // bar3: red, close=860 < bar2.low(900) -> VALID
    closeBar(f, 860, 1050, 855, 1040);  // bar4: green, close=1040 > pullbackExtreme(bar2.high=1010) -> confirms

    // TJL1 from bar2 (O=1000,C=980 -> body=20 -> offset=round(0.2)=0): of(1010,1000-0)=[1000,1010].
    check("Setup: TJL1 formed", f.lastTjl1(), new ZoneRange(1000, 1010));
    // TJL2 from bar3 (O=980,C=860 -> body=120 -> offset=round(1.2)=1): of(850,860+1=861)=[850,861].
    check("Setup: TJL2 formed", f.lastTjl2(), new ZoneRange(850, 861));
    check("Setup: tradeable = {TJL2}", f.tradeableLevels(), Set.of(MarketStructureView.TradeableLevel.TJL2));

    // -- CHOCH1: Up->Down flip. Window anchors at TJL2's own candle (bar3, point 6) -- NOT bar2/A+. --
    closeBar(f, 1040, 1045, 795, 800); // bar5: close=800 < TJL2.low(850) (count=1)
    closeBar(f, 800, 810, 700, 750);   // bar6: close=750 < 850 (count=2 -> FLIP)

    check("CHOCH1: fires exactly once so far", rec.flipCount, 1);
    check("CHOCH1: trend becomes DOWN", f.trend(), MarketStructureView.Trend.DOWN);
    check("CHOCH1: A+ is the retired TJL1", f.lastAPlus(), new ZoneRange(1000, 1010));
    check("CHOCH1: SBR is the retired TJL2", f.lastSbrRbs(), new ZoneRange(850, 861));
    // DT window = bar3's open (point 6) through bar6 inclusive = [bar3,bar4,bar5,bar6].
    // Highest high in that set is bar4 (1050). DT from bar4 (O=860,C=1040 -> body=180 ->
    // offset=round(1.8)=2): of(1050, bodyTop(1040)+2=1042) = [1042,1050].
    check("CHOCH1: DT built from the window's true extreme (bar4, H=1050)",
        f.lastDt(), new ZoneRange(1042, 1050));
    check("CHOCH1: DB not set yet", f.lastDb(), null);
    check("CHOCH1: lastTjl2() now holds DT (point 5 -- replaces the old TJL2/SBR value)",
        f.lastTjl2(), f.lastDt());
    check("CHOCH1: tradeable = {A+, SBR/RBS, DT} -- point 9's first link",
        f.tradeableLevels(), Set.of(MarketStructureView.TradeableLevel.A_PLUS,
            MarketStructureView.TradeableLevel.SBR_RBS, MarketStructureView.TradeableLevel.DT));
    check("CHOCH1: flip callback carries the same tradeable set", rec.lastFlipTradeable, f.tradeableLevels());

    // -- CHOCH2: Down->Up flip, triggered by DT (1042,1050) being crossed. --
    closeBar(f, 1050, 1075, 1030, 1060); // bar7: close=1060 > DT.high(1050) (count=1)
    closeBar(f, 1060, 1090, 1040, 1070); // bar8: close=1070 > 1050 (count=2 -> FLIP)

    check("CHOCH2: fires (2 total)", rec.flipCount, 2);
    check("CHOCH2: trend becomes UP", f.trend(), MarketStructureView.Trend.UP);
    check("CHOCH2: A+/SBR are no longer tradeable -- point 9, dropped for good", f.lastAPlus(), null);
    check("CHOCH2: SBR cleared too", f.lastSbrRbs(), null);
    check("CHOCH2: DT is UNCHANGED from CHOCH1 -- point 9's 'other side carries over untouched'",
        f.lastDt(), new ZoneRange(1042, 1050));
    // DB window chains from bar4's own open (point 6's chaining rule -- the bar that
    // produced DT at CHOCH1), NOT from the original SBR/RBS candle (bar3). This matters:
    // bar3's low (850) is actually LOWER than bar4's low (855) -- if the window had
    // wrongly stayed anchored to bar3 forever, DB would be built from bar3 instead,
    // giving a DIFFERENT (wrong) zone. Window = [bar4,bar7,bar8]. Lowest low = bar4 (855).
    // DB from bar4 (O=860,C=1040 -> body=180 -> offset=2): of(855, bodyBottom(860)-2=858) = [855,858].
    check("CHOCH2: DB chains from bar4 (the PRIOR flip's own candle), not the original SBR/RBS candle (bar3)",
        f.lastDb(), new ZoneRange(855, 858));
    check("CHOCH2: lastTjl2() now holds DB", f.lastTjl2(), f.lastDb());
    check("CHOCH2: tradeable = exactly {DT, DB} -- point 9's second link onward",
        f.tradeableLevels(), Set.of(MarketStructureView.TradeableLevel.DT, MarketStructureView.TradeableLevel.DB));

    // -- CHOCH3: Up->Down flip again, triggered by DB (855,858) being crossed. --
    closeBar(f, 855, 1100, 800, 850); // bar9: close=850 < DB.low(855) (count=1)
    closeBar(f, 1090, 1100, 800, 850); // bar10: close=850 < 855 (count=2 -> FLIP). High kept modest deliberately.

    check("CHOCH3: fires (3 total)", rec.flipCount, 3);
    check("CHOCH3: trend becomes DOWN", f.trend(), MarketStructureView.Trend.DOWN);
    check("CHOCH3: DB is UNCHANGED from CHOCH2 -- carries over again", f.lastDb(), new ZoneRange(855, 858));
    // DT window chains from bar4's open still (bar4 was still the anchor going into this
    // flip -- CHOCH2 didn't move the anchor since it found bar4 was still the extreme).
    // Window = [bar4,bar9,bar10]. Highest high: bar9 (O=855,H=1100,L=800,C=850).
    // bodyTop(bar9)=max(855,850)=855, body=5, offset=round(0.05)=0: of(1100,855-0)=[855,1100].
    check("CHOCH3: DT refreshed off the new window extreme (bar9)", f.lastDt(), new ZoneRange(855, 1100));
    check("CHOCH3: tradeable is still exactly {DT, DB}", f.tradeableLevels(),
        Set.of(MarketStructureView.TradeableLevel.DT, MarketStructureView.TradeableLevel.DB));

    // -- Point 10: a fresh REAL TJL pair ends the chain, immediately. --
    // Now in a DOWN trend -- pullback candles are green.
    closeBar(f, 845, 900, 840, 870);  // bar11: green, run's first candle (high=900, low=840)
    closeBar(f, 870, 920, 865, 910);  // bar12: green, close=910 > bar11.high(900) -> VALID
    closeBar(f, 910, 915, 800, 810);  // bar13: red, close=810 < pullbackExtreme(bar11.low=840) -> confirms

    check("Point 10: a fresh real TJL1/TJL2 pair formed", rec.tjlFormedCount, 2);
    // DOWN trend: TJL2 = zoneFromHigh(highBar=bar12), TJL1 = zoneFromLow(lowBar=bar11).
    // bar12 (O=870,C=910 -> body=40 -> offset=round(0.4)=0): of(920,910-0)=[910,920].
    check("Point 10: fresh TJL2", f.lastTjl2(), new ZoneRange(910, 920));
    // bar11 (O=845,C=870 -> body=25 -> offset=round(0.25)=0): of(840,845+0)=[840,845].
    check("Point 10: fresh TJL1", f.lastTjl1(), new ZoneRange(840, 845));
    check("Point 10: A+ immediately cleared", f.lastAPlus(), null);
    check("Point 10: SBR/RBS immediately cleared", f.lastSbrRbs(), null);
    check("Point 10: DT immediately cleared -- fully replaced, not left stale", f.lastDt(), null);
    check("Point 10: DB immediately cleared -- fully replaced, not left stale", f.lastDb(), null);
    check("Point 10: tradeable set back to exactly {TJL2} -- same as normal, non-chained operation",
        f.tradeableLevels(), Set.of(MarketStructureView.TradeableLevel.TJL2));
  }
}
