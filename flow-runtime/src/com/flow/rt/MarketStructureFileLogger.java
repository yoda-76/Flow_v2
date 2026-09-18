package com.flow.rt;

import com.flow.flow.MarketStructureFeature;
import com.flow.flow.MarketStructureView;
import com.flow.flow.ZoneRange;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Diagnostic-only file logger for live validation, same discipline as
 * every other construct's first pass (D-60). One line per pullback
 * validation, TJL pair formation, and flip -- the three named events in
 * the source system, not a periodic sample (unlike VWAP's continuous
 * value, these are genuinely discrete occurrences).
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
          + " -- UNREVIEWED rule resolutions, see docs/dynamic/marketStructureRulesTemp.md");
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
  public void onTjlFormed(MarketStructureView.Trend trend, ZoneRange tjl1, ZoneRange tjl2) {
    logLine("TJL_FORMED trend=" + trend
        + " tjl1=[" + codec.fromTicks(tjl1.lowTicks()) + "," + codec.fromTicks(tjl1.highTicks()) + "]"
        + " tjl2=[" + codec.fromTicks(tjl2.lowTicks()) + "," + codec.fromTicks(tjl2.highTicks()) + "]");
  }

  @Override
  public void onFlip(MarketStructureView.Trend newTrend, ZoneRange aPlus, ZoneRange sbrRbs, ZoneRange dtDb) {
    logLine("FLIP newTrend=" + newTrend
        + " aPlus=" + zoneStr(aPlus) + " sbrRbs=" + zoneStr(sbrRbs) + " dtDb=" + zoneStr(dtDb));
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
