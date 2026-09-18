package com.flow.rt;

import com.flow.flow.BigTradeEvent;
import com.flow.flow.BigTradeFeature;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Diagnostic-only file logger for live validation, same discipline as
 * SdkVolumeProfileFeature/SdkFootprintFeature's own log files -- one line
 * per CREATED/UPDATED big trade. Kept as a separate flow-runtime class
 * (implements BigTradeFeature.Listener) rather than baked into the
 * feature itself, since BigTradeFeature lives in flow-core now (D-53) and
 * stays free of file I/O and any platform dependency.
 */
final class BigTradeFileLogger implements BigTradeFeature.Listener {
  private final PriceCodec codec;
  private final PrintWriter log;

  BigTradeFileLogger(PriceCodec codec, double minSize, long aggPeriodMs) {
    this.codec = codec;
    PrintWriter w;
    try {
      w = new PrintWriter(new FileWriter(
          "C:/yadvendra/trading/FLOW_V2/logs/big_trade_feature.log", true));
      w.println("# feature start " + System.currentTimeMillis()
          + " id=big_trades minSize=" + minSize + " aggPeriodMs=" + aggPeriodMs);
      w.flush();
    } catch (IOException e) {
      w = null;
    }
    this.log = w;
  }

  @Override
  public void onCreated(BigTradeEvent event) {
    logLine("CREATED openExchOrderId=" + event.exchOrderId() + " size=" + event.size()
        + " isAskTick=" + event.isAskTick() + " price=" + codec.fromTicks(event.priceTicks()));
  }

  @Override
  public void onUpdated(BigTradeEvent previous, BigTradeEvent updated) {
    logLine("WINDOW_GROW openExchOrderId=" + updated.exchOrderId() + " prevSize=" + previous.size()
        + " newSize=" + updated.size() + " price=" + codec.fromTicks(updated.priceTicks()));
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
