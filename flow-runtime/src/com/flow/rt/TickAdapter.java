package com.flow.rt;

import com.flow.core.TickEvent;
import com.motivewave.platform.sdk.common.Tick;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * Builds an SDK Tick from a core TickEvent via a dynamic proxy, not a
 * plain `implements Tick` class. Reason: Tick declares
 * getPrice(Enums.BarData), and this jar's compiled Enums.class does not
 * expose BarData as a resolvable member type to javac -- confirmed
 * directly: even `import com.motivewave...Enums.BarData` fails to
 * resolve ("cannot find symbol: class BarData, location: interface
 * Enums"), though the class Enums$BarData genuinely exists on the
 * classpath and loads fine at runtime. Same class of javadoc-vs-jar
 * mismatch as T-6 (sdk-capability-findings.md), here on a different
 * type. A dynamic proxy sidesteps it entirely: Proxy.newProxyInstance
 * only needs Tick.class, and the handler below reads the BarData
 * argument via java.lang.Enum.name() -- an ordinary java.lang API, never
 * naming Enums.BarData in source anywhere.
 *
 * Feeds the SDK's VolumeProfile.onTick() from the drain thread using our
 * own already-converted TickEvent, never the original callback-thread
 * Tick -- that would violate the single-writer invariant (README "The
 * event stream").
 *
 * Bid/ask size are not carried by TickEvent (D-21's ingest boundary keeps
 * the core event minimal) and are stubbed to 0 -- confirmed safe for this
 * use: "Bid/Ask sizes are not currently used by the volume analysis tools
 * in MotiveWave" per the SDK docs, and VolumeProfile's own algorithm
 * (E-2, live-confirmed) never reads exchange order ids either. Order ids
 * ARE carried by TickEvent (added for big trades -- see that record's
 * javadoc) and passed through genuinely here, not stubbed.
 */
final class TickAdapter {
  private TickAdapter() {}

  static Tick wrap(TickEvent e, PriceCodec codec) {
    double price = codec.fromTicks(e.priceTicks());
    double bidPrice = codec.fromTicks(e.bidPriceTicks());
    double askPrice = codec.fromTicks(e.askPriceTicks());

    InvocationHandler handler = (proxy, method, args) -> {
      String name = method.getName();
      switch (name) {
        case "getPrice": {
          if (args == null || args.length == 0) return (float) price;
          String bd = args[0] == null ? "" : ((Enum<?>) args[0]).name();
          if (bd.equals("BID")) return (float) bidPrice;
          if (bd.equals("ASK")) return (float) askPrice;
          if (bd.equals("MIDPOINT")) return (float) ((bidPrice + askPrice) / 2.0);
          return (float) price;
        }
        case "getVolume": return e.volume();
        case "getVolumeAsFloat": return (float) e.volume();
        case "getAskPrice": return (float) askPrice;
        case "getAskSize": return 0;
        case "getAskSizeAsFloat": return 0f;
        case "getBidPrice": return (float) bidPrice;
        case "getBidSize": return 0;
        case "getBidSizeAsFloat": return 0f;
        case "getTime": return e.eventTimeMs();
        case "isAskTick": return e.isAskTick();
        case "getExchOrderId": return e.exchOrderId();
        case "getAggExchOrderId": return e.aggExchOrderId();
        case "equals": return proxy == (args != null && args.length > 0 ? args[0] : null);
        case "hashCode": return System.identityHashCode(proxy);
        case "toString": return "TickAdapter[seq=" + e.seq() + " price=" + price + "]";
        default: throw new UnsupportedOperationException("Tick." + name + " not implemented by TickAdapter");
      }
    };

    return (Tick) Proxy.newProxyInstance(
        Tick.class.getClassLoader(), new Class<?>[]{Tick.class}, handler);
  }
}
