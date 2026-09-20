package com.flow.rt;

import com.flow.flow.MarketStructureFeature;
import com.flow.flow.MarketStructureView;
import com.flow.flow.ZoneRange;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Diagnostic-only file logger for live validation, same discipline as
 * every other construct's first pass (D-60, reworked D-74 against the
 * fully reviewed rules). One line per pullback validation, TJL pair
 * formation, and flip -- the three named events in the source system,
 * not a periodic sample (unlike VWAP's continuous value, these are
 * genuinely discrete occurrences). TJL_FORMED/FLIP lines now also log
 * the current tradeable-levels set (marketStructureRules.md §6, points
 * 9/10) -- a new concept this rework introduces, worth seeing directly
 * in the log for the first live validation pass.
 */
final class MarketStructureFileLogger implements MarketStructureFeature.Listener {
  private final PriceCodec codec;
  private final PrintWriter log;

  MarketStructureFileLogger(PriceCodec codec) {
    this.codec = codec;
    PrintWriter w;
    try {
      w = new PrintWriter(new FileWriter(
          "C:/yadvendra/trading/FLOW_V2/logs/market_structure_feature.log", true));
      w.println("# feature start " + System.currentTimeMillis() + " id=market_structure"
          + " -- reviewed rules, see docs/dynamic/marketStructureRules.md (all 10 points resolved)");
      w.flush();
    } catch (IOException e) {
      w = null;
    }
    this.log = w;
  }

  @Override
  public void onPullbackValid(MarketStructureView.Trend trend, MarketStructureFeature.Bar firstBar) {
    logLine("PULLBACK_VALID trend=" + trend + " firstBarLow=" + codec.fromTicks(firstBar.lowTicks())
        + " firstBarHigh=" + codec.fromTicks(firstBar.highTicks()));
  }

  @Override
  public void onTjlFormed(MarketStructureView.Trend trend, ZoneRange tjl1, ZoneRange tjl2,
                           Set<MarketStructureView.TradeableLevel> tradeableLevels) {
    logLine("TJL_FORMED trend=" + trend
        + " tjl1=[" + codec.fromTicks(tjl1.lowTicks()) + "," + codec.fromTicks(tjl1.highTicks()) + "]"
        + " tjl2=[" + codec.fromTicks(tjl2.lowTicks()) + "," + codec.fromTicks(tjl2.highTicks()) + "]"
        + " tradeable=" + tradeableLevels);
  }

  @Override
  public void onFlip(MarketStructureView.Trend newTrend, ZoneRange aPlus, ZoneRange sbrRbs, ZoneRange dtOrDb,
                      Set<MarketStructureView.TradeableLevel> tradeableLevels) {
    logLine("FLIP newTrend=" + newTrend
        + " aPlus=" + zoneStr(aPlus) + " sbrRbs=" + zoneStr(sbrRbs) + " dtOrDb=" + zoneStr(dtOrDb)
        + " tradeable=" + tradeableLevels);
  }

  private String zoneStr(ZoneRange z) {
    return z == null ? "null" : "[" + codec.fromTicks(z.lowTicks()) + "," + codec.fromTicks(z.highTicks()) + "]";
  }

  private void logLine(String s) {
    if (log == null) return;
    log.println(System.currentTimeMillis() + " " + s);
    log.flush();
  }

  void closeLog() {
    if (log != null) {
      log.flush();
      log.close();
    }
  }
}
