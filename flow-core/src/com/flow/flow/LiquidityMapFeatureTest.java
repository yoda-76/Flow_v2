package com.flow.flow;

import com.flow.core.DomEvent;
import com.flow.core.DomRow;

import java.util.List;

/**
 * Direct synthetic test for LiquidityMapFeature's book-imbalance-at-N-
 * levels view (README's "imbalance at N levels"), same discipline as
 * every other direct synthetic test this session -- no MotiveWave
 * involved.
 */
public final class LiquidityMapFeatureTest {
  private static int failures = 0;

  private static void check(String label, Double got, Double want) {
    boolean ok = got == null ? want == null : Math.abs(got - want) < 1e-9;
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) {
    testNoDataYet();
    testNearestNLevelsBothSides();
    testThinnerSideUsesWhatItHas();
    testOneSideEmpty();
    testZeroLevelsRequested();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all LiquidityMapFeature synthetic checks passed.");
  }

  private static void feed(LiquidityMapFeature f, List<DomRow> bids, List<DomRow> asks) {
    f.onEvent(new DomEvent(1, 1000L, 1000L, 0, 0, 0, 0, bids, asks));
  }

  private static void testNoDataYet() {
    LiquidityMapFeature f = new LiquidityMapFeature("lm");
    check("No DOM update yet -> null, not 0.0", f.imbalanceAtLevels(3), null);
  }

  private static void testNearestNLevelsBothSides() {
    LiquidityMapFeature f = new LiquidityMapFeature("lm");
    feed(f,
        List.of(new DomRow(100, 5), new DomRow(99, 10), new DomRow(98, 20), new DomRow(97, 50)),
        List.of(new DomRow(101, 3), new DomRow(102, 7), new DomRow(103, 15), new DomRow(104, 40)));
    // Nearest 2 bid rows to market (highest price first): 100(5) + 99(10) = 15.
    // Nearest 2 ask rows to market (lowest price first): 101(3) + 102(7) = 10.
    // (15-10)/(15+10) = 0.2
    check("n=2, both sides deep enough -- uses only the nearest rows, not the far ones",
        f.imbalanceAtLevels(2), 0.2);
  }

  private static void testThinnerSideUsesWhatItHas() {
    LiquidityMapFeature f = new LiquidityMapFeature("lm");
    feed(f,
        List.of(new DomRow(100, 5), new DomRow(99, 10), new DomRow(98, 20), new DomRow(97, 50)),
        List.of(new DomRow(101, 3), new DomRow(102, 7), new DomRow(103, 15), new DomRow(104, 40)));
    // n=10 but only 4 rows per side exist -- every row counts, not padded with zeros.
    // bidSum = 5+10+20+50 = 85, askSum = 3+7+15+40 = 65, total=150, (85-65)/150 = 20/150.
    check("n larger than available rows -- uses all rows on each side, not padded",
        f.imbalanceAtLevels(10), 20.0 / 150.0);
  }

  private static void testOneSideEmpty() {
    LiquidityMapFeature f = new LiquidityMapFeature("lm");
    feed(f, List.of(new DomRow(100, 5), new DomRow(99, 10)), List.of());
    check("Ask side empty -> maximally bid-heavy, +1.0", f.imbalanceAtLevels(5), 1.0);
  }

  private static void testZeroLevelsRequested() {
    LiquidityMapFeature f = new LiquidityMapFeature("lm");
    feed(f, List.of(new DomRow(100, 5)), List.of(new DomRow(101, 3)));
    // n<=0 -- degenerate request, not "no book data" -- documented as 0.0, not null.
    check("n=0 -- both sums 0.0 by construction, reported as 0.0 (data exists, request is degenerate)",
        f.imbalanceAtLevels(0), 0.0);
  }
}
