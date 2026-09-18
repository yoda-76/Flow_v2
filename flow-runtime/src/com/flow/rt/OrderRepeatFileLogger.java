package com.flow.rt;

import com.flow.flow.OrderRepeatEvent;
import com.flow.flow.OrderRepeatFeature;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Diagnostic-only file logger for live validation, same discipline as
 * BigTradeFileLogger -- one line per confirmed repeat (count reaching 2)
 * and one per further repeat after that. No drawing wired for this
 * construct yet (D-56) -- log-only until there's a concrete analysis
 * need for it, same build-then-layer discipline as every other construct
 * here.
 */
final class OrderRepeatFileLogger implements OrderRepeatFeature.Listener {
  private final PriceCodec codec;
  private final PrintWriter log;

  OrderRepeatFileLogger(PriceCodec codec) {
    this.codec = codec;
    PrintWriter w;
    try {
      w = new PrintWriter(new FileWriter(
          "C:/yadvendra/trading/FLOW_V2/logs/order_repeat_feature.log", true));
      w.println("# feature start " + System.currentTimeMillis() + " id=order_repeats");
      w.flush();
    } catch (IOException e) {
      w = null;
    }
    this.log = w;
  }

  @Override
  public void onCreated(OrderRepeatEvent event) {
    logLine("FIRST_REPEAT exchOrderId=" + event.exchOrderId() + " count=" + event.repeatCount()
        + " totalSize=" + event.totalSize() + " price=" + codec.fromTicks(event.priceTicks())
        + " isAskTick=" + event.isAskTick());
  }

  @Override
  public void onUpdated(OrderRepeatEvent previous, OrderRepeatEvent updated) {
    logLine("REPEAT exchOrderId=" + updated.exchOrderId() + " count=" + updated.repeatCount()
        + " prevTotal=" + previous.totalSize() + " newTotal=" + updated.totalSize()
        + " price=" + codec.fromTicks(updated.priceTicks()));
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
