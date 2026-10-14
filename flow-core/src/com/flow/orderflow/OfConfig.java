package com.flow.orderflow;

import com.flow.journal.Json;
import com.flow.journal.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every tunable number of the order-flow execution model, with the starting values decided in
 * docs/dynamic/orderFlowRules_gapAnswers_v1.md (course-derived where the course gives a number, an assumption
 * otherwise). Research machine: every value is overridable from config/orderflow.json (a flat JSON object whose keys
 * are these field names) and the whole set is journaled at session start (Q6.44), so results from different
 * settings are never silently mixed.
 *
 * Prices/distances are in instrument ticks (GC: 0.1). Times in ms unless the name says otherwise.
 */
public final class OfConfig {
  // ---- bars and footprint (gapAnswers §3) ----
  public int barTrades = 500;                 // E3: 500-tick candles (used when barSeconds is 0)
  public int barSeconds = 0;                  // > 0: time bars of this many seconds, clock-aligned (60 = the chart's
                                              // 1-minute bars, user 2026-10-12); 0: trade-count bars (barTrades)
  public int footprintRowTicks = 6;           // E5: keep above 5 for Gold (6/7/10)
  public double imbalanceRatio = 3.0;         // E5: 300 %
  public double imbalanceMinVolume = 3;       // dominant side minimum
  public int stackedMinRows = 3;              // E5
  public int dominanceMinImbalances = 3;      // candle dominance: >= this on one side ...
  public int dominanceMaxOpposite = 1;        // ... and <= this on the other

  // ---- aggression / big trades ----
  public double bigTradeMin = 30;             // E3: 30 contracts per aggressor order
  public double smallPlayerMin = 10;          // E3: lowering to 10-20 shows smaller players
  public long burstGapMs = 2_000;
  public int burstPriceTicks = 3;
  public int aggressionProximityTicks = 5;
  public long responseCapMs = 120_000;        // window = to close of next bar, capped
  public int responseSuccessTicks = 10;
  public int responseFailMaxMfeTicks = 5;

  // ---- reads (gapAnswers §4) ----
  public int readValidBars = 3;               // evidence lifetime in closed bars (G0.2)
  public int avgWindowBars = 20;
  public int readyMinBars = 5;                // engine readiness (arming gate): averages use what exists until 20
  public double absorptionDeltaMult = 1.5;
  public double absorptionCloseLoc = 0.30;    // positive delta closing in the bottom 30 % (mirror for negative)
  public int exhaustionBars = 3;
  public long deltaProfileWindowMs = 30 * 60_000L; // E5: 30-minute delta profile
  public double deltaShiftShare = 0.60;
  public int timeAtLevelTicks = 5;
  public long timeAtLevelLongMs = 20 * 60_000L;
  public int trappedSmallMinCount = 3;
  public int trappedSmallBars = 5;
  public int trappedSmallMoveTicks = 5;

  // ---- profiles, locations (gapAnswers §7) ----
  public int profileRowTicks = 4;             // E4: tick interval 4
  public double valueAreaShare = 0.70;        // E2/E6
  public double hvnMult = 1.0;                // build note B1 (replay-tuned; was 1.3)
  public double lvnPeakRatio = 0.7;          // LVN: smoothed minimum <= this x the smaller neighbouring HVN (build note B1)
  public int hvnSpanRows = 2;                 // HVN: highest smoothed row within +-this many rows
  public int lvnMinRows = 2;
  public long profileMatureMs = 30 * 60_000L; // G44
  public double profileMatureVolume = 2_000;
  public long shapeMinAgeMs = 60 * 60_000L;
  public int approachTicks = 5;
  public int traverseTicks = 3;
  public int locationMaxDistanceTicks = 10;   // original Q5.7
  public int pocShortTicks = 3;               // G43: trigger closing within this before the POC

  // ---- micro structure (G12) ----
  public int swingFractalBars = 2;
  public int retestTicks = 5;
  public int retestMaxBars = 10;

  // ---- stops, targets, filters, limits (gapAnswers §9) ----
  public int stopBufferTicks = 5;             // "slightly beyond"
  public int maxStopTicks = 80;               // build note B2 (500-tick GC bars: median range 47 ticks)
  public int minStopTicks = 4;
  public double minRewardRisk = 2.0;          // E2/E3: minimum 1:2
  public double chaseMaxBarRanges = 1.0;     // build note B2: entry at most 1 average bar range beyond the location
  public int momentumBars = 3;
  public double momentumRangeMult = 2.0;
  public int maxEntriesPerZonePerSession = 3; // A4 (E3)
  public int maxEntriesPerSession = 6;
  public boolean pShapeLongFilter = true;
  public boolean pocShortFilter = true;

  // ---- liquidity / heatmap (gapAnswers §10) ----
  public int liquidityWindowTicks = 250;
  public double wallMinLevelSize = 5;         // bot filter
  public double wallAbsoluteSize = 100;       // E7: 100-150 lots is already large
  public double wallMedianMult = 4.0;
  public long wallPersistMs = 30_000;
  public int wallStopShiftTicks = 10;
  public double sweepConsumedShare = 0.70;
  public int sweepThroughTicks = 3;
  public int breakthroughTicks = 10;
  public double gapHoldShare = 0.50;
  public int gapAwayTicks = 5;
  public double spoofLossShare = 0.80;
  public double spoofTradedMaxShare = 0.30;

  // ---- aggregation ----
  public int goScore = 2;                     // Go = some model at +2 ...
  public int vetoScore = -2;                  // ... and none at -2

  public static OfConfig defaults() {
    return new OfConfig();
  }

  /** Defaults overlaid with config/orderflow.json next to risk.json, if it exists. Unknown keys are ignored. */
  public static OfConfig load(Path file) {
    OfConfig c = new OfConfig();
    if (file == null || !Files.isRegularFile(file)) return c;
    try {
      c.overlay(JsonObject.parse(Files.readString(file).replace('\n', ' ').replace('\r', ' ')));
      c.source = file.toString();
    } catch (Exception e) {
      c.loadError = String.valueOf(e); // defaults stay in force; the error is journaled by the strategy
    }
    return c;
  }

  /** Where the values came from (null = defaults only) and any load error -- journaled, never silent. */
  public String source;
  public String loadError;

  void overlay(JsonObject obj) throws IllegalAccessException {
    for (Field f : OfConfig.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("source") || f.getName().equals("loadError")) continue;
      String k = f.getName();
      if (!obj.has(k) || obj.isNull(k)) continue;
      Class<?> t = f.getType();
      if (t == int.class) f.setInt(this, (int) Math.round(obj.getDouble(k)));
      else if (t == long.class) f.setLong(this, Math.round(obj.getDouble(k)));
      else if (t == double.class) f.setDouble(this, obj.getDouble(k));
      else if (t == boolean.class) f.setBoolean(this, obj.getBoolean(k));
    }
  }

  public String toJson() {
    Json j = Json.object();
    try {
      for (Field f : OfConfig.class.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        Object v = f.get(this);
        if (v instanceof Integer i) j.field(f.getName(), i);
        else if (v instanceof Long l) j.field(f.getName(), l);
        else if (v instanceof Double d) j.field(f.getName(), d);
        else if (v instanceof Boolean b) j.field(f.getName(), b);
        else if (v instanceof String s) j.field(f.getName(), s);
      }
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
    return j.build();
  }
}
