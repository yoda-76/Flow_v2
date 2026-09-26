package com.flow.rt;

import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit-test double for OrderContext (plumbingEdgeCases.md §13, resolved
 * 2026-09-22: build it, strictly a test fixture). An in-memory pretend
 * broker -- it never holds or reaches a real OrderContext, so it can't place
 * an order on any account, Sim or real; CLAUDE.md's per-order rule governs
 * real OrderContexts and doesn't apply to it.
 *
 * OrderContext, Order and Instrument are all interfaces in the SDK jar, so
 * each is a java.lang.reflect.Proxy over a small state object -- no need to
 * implement the ~60-method OrderContext surface. Any method this fake does
 * not model throws UnsupportedOperationException naming it, so code under
 * test that reaches for something unmodelled fails loudly instead of
 * silently getting a default.
 *
 * PASSIVE by design: nothing here ever calls an order callback
 * (onOrderFilled etc.) on its own. The test drives fills/cancels/rejects
 * explicitly (fill(), reject()) and then invokes the callback itself, so
 * the ORDER of events is the test's to script -- that is what makes
 * plumbingEdgeCases.md §8-§10's races expressible at all.
 *
 * Fidelity caveat (the §13 worry: tests passing against a fiction). What the
 * fake models, and how sure we are the real platform does the same:
 *   [LIVE, D-67/D-81] createXOrder + submitOrders yields a tracked order;
 *     a market entry fills; stop/target legs are independent orders.
 *   [LIVE, 2026-09-21] no OCO -- filling one leg does not cancel the other.
 *   [DOC, D-85] closeAtMarket() flattens but leaves resting orders alone;
 *     cancelOrders() with no args cancels every active order.
 *   [ASSUMED, unconfirmed] cancelling an already-resolved order is a no-op;
 *     submitOrders() makes an order active immediately (the real platform
 *     may lag until a callback); closeAtMarket() fills instantly.
 * Only the LIVE/DOC rows are evidence about the platform; the ASSUMED rows
 * are choices this fake made and must not be read as findings.
 */
final class FakeBroker {
  /** Backing state for one fake Order. Mutable, public fields: it's a test double. */
  static final class FakeOrder {
    final String id;
    final String type;    // MARKET / STOP / LIMIT
    final String action;  // BUY / SELL
    final int qty;
    final Float price;    // null for MARKET
    int filled;
    float fillPrice;     // 0 until filled
    long fillTimeMs;     // 0 until filled
    boolean submitted;
    boolean cancelled;
    boolean rejected;
    private Order proxy;

    FakeOrder(String id, String type, String action, int qty, Float price) {
      this.id = id;
      this.type = type;
      this.action = action;
      this.qty = qty;
      this.price = price;
    }

    boolean isFilled() { return filled >= qty; }
    boolean isActive() { return submitted && !cancelled && !rejected && !isFilled(); }
    Order order() { return proxy; }

    @Override public String toString() {
      return type + " " + action + " " + qty + (price == null ? "" : " @" + price) + " #" + id;
    }
  }

  private final String symbol;
  private int position;
  private double cash = 50_000;
  private float marketPrice = 4300f; // fill price used by fill(o) for a MARKET order
  private int nextId = 1;
  private final List<FakeOrder> all = new ArrayList<>();
  private final List<String> calls = new ArrayList<>();
  private final OrderContext ctx;
  private final Instrument instrument;

  FakeBroker() { this("GC"); }

  FakeBroker(String symbol) {
    this.symbol = symbol;
    this.instrument = (Instrument) Proxy.newProxyInstance(
        Instrument.class.getClassLoader(), new Class<?>[] {Instrument.class}, new InstrumentHandler());
    this.ctx = (OrderContext) Proxy.newProxyInstance(
        OrderContext.class.getClassLoader(), new Class<?>[] {OrderContext.class}, new ContextHandler());
  }

  /** The OrderContext to hand to the code under test (OrderGateway). */
  OrderContext ctx() { return ctx; }

  // ---- state the test can read / arrange ------------------------------

  int position() { return position; }

  /** Arrange a pre-existing position (e.g. "MotiveWave restarted with a position open"). */
  void setPosition(int p) { position = p; }

  /** Ordered log of every mutating call the code under test made, e.g. "submitOrders MARKET BUY 1 #1". */
  List<String> calls() { return Collections.unmodifiableList(calls); }

  /** True if any recorded call starts with the given prefix. */
  boolean called(String prefix) {
    for (String c : calls) if (c.startsWith(prefix)) return true;
    return false;
  }

  List<FakeOrder> activeOrders() {
    List<FakeOrder> out = new ArrayList<>();
    for (FakeOrder o : all) if (o.isActive()) out.add(o);
    return out;
  }

  /** Orders ever created, in creation order (whether or not submitted). */
  List<FakeOrder> allOrders() { return Collections.unmodifiableList(all); }

  /** Arrange a resting order on the account that the code under test did not place. */
  FakeOrder restingOrder(String type, String action, int qty, float price) {
    FakeOrder o = newOrder(type, action, qty, price);
    o.submitted = true;
    return o;
  }

  /**
   * The order fills completely: position moves by the signed quantity, the
   * order stops being active. Does NOT invoke any callback -- the test does.
   */
  void fill(FakeOrder o) {
    fill(o, o.price != null ? o.price : marketPrice, 1_000L * (nextId + o.filled));
  }

  /** Fill at an explicit price and time (a stop/limit fills at its own price by default). */
  void fill(FakeOrder o, float price, long timeMs) {
    if (o.isFilled()) throw new IllegalStateException("already filled: " + o);
    o.filled = o.qty;
    o.fillPrice = price;
    o.fillTimeMs = timeMs;
    position += "BUY".equals(o.action) ? o.qty : -o.qty;
    // Cash/PnL are not modelled: cash only changes via setCash().
  }

  /** Arrange the account cash balance the fake reports. */
  void setCash(double c) { cash = c; }

  /** Arrange the price a MARKET order fills at. */
  void setMarketPrice(float p) { marketPrice = p; }

  /** The broker rejects the order: it never becomes active. Does NOT invoke any callback. */
  void reject(FakeOrder o) {
    o.rejected = true;
  }

  // ---- internals -------------------------------------------------------

  private FakeOrder newOrder(String type, String action, int qty, Float price) {
    FakeOrder o = new FakeOrder(String.valueOf(nextId++), type, action, qty, price);
    o.proxy = (Order) Proxy.newProxyInstance(
        Order.class.getClassLoader(), new Class<?>[] {Order.class}, new OrderHandler(o));
    all.add(o);
    return o;
  }

  private static UnsupportedOperationException unmodelled(String what, Method m) {
    return new UnsupportedOperationException("FakeBroker does not model " + what + "." + m.getName()
        + " -- add it deliberately, with a note on whether the real platform is confirmed to behave that way");
  }

  private FakeOrder owned(Object o) {
    for (FakeOrder f : all) if (f.proxy == o) return f;
    throw new IllegalArgumentException("not an order created by this FakeBroker: " + o);
  }

  private void cancel(FakeOrder o) {
    if (!o.isActive()) return; // ASSUMED no-op, see class javadoc
    o.cancelled = true;
    calls.add("cancelOrders " + o);
  }

  private final class ContextHandler implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method m, Object[] args) {
      String n = m.getName();
      Class<?>[] pt = m.getParameterTypes();
      switch (n) {
        case "getPosition":
          if (pt.length == 0) return position;
          throw unmodelled("OrderContext", m);
        case "getInstrument": return instrument;
        case "getCashBalance": return cash;
        case "getTotalRealizedPnL": return 0d; // ASSUMED zero; the fake models no PnL
        case "getActiveOrders": return new ArrayList<Object>(activeOrders().stream().map(FakeOrder::order).toList());
        case "createMarketOrder":
          // Only the (OrderAction, int) overload OrderAdapter uses.
          if (pt.length == 2 && pt[1] == int.class) {
            return newOrder("MARKET", actionName(args[0]), (Integer) args[1], null).order();
          }
          throw unmodelled("OrderContext", m);
        case "createStopOrder":
        case "createLimitOrder":
          // Only the (OrderAction, TIF, int, float) overload OrderAdapter uses.
          if (pt.length == 4 && pt[2] == int.class && pt[3] == float.class) {
            String type = "createStopOrder".equals(n) ? "STOP" : "LIMIT";
            return newOrder(type, actionName(args[0]), (Integer) args[2], (Float) args[3]).order();
          }
          throw unmodelled("OrderContext", m);
        case "submitOrders":
          if (pt.length == 1 && pt[0] == Order[].class) {
            for (Order ord : (Order[]) args[0]) {
              FakeOrder f = owned(ord);
              f.submitted = true;
              calls.add("submitOrders " + f);
            }
            return null;
          }
          throw unmodelled("OrderContext", m);
        case "cancelOrders":
          if (pt.length == 0) { // cancel everything active
            calls.add("cancelOrders(all)");
            for (FakeOrder f : activeOrders()) f.cancelled = true;
            return null;
          }
          if (pt.length == 1 && pt[0] == Order[].class) {
            for (Order ord : (Order[]) args[0]) cancel(owned(ord));
            return null;
          }
          throw unmodelled("OrderContext", m);
        case "closeAtMarket":
          calls.add("closeAtMarket positionBefore=" + position);
          position = 0; // ASSUMED instant; resting orders deliberately left alone [DOC]
          return null;
        default:
          throw unmodelled("OrderContext", m);
      }
    }
  }

  private static String actionName(Object orderAction) {
    return ((Enum<?>) orderAction).name();
  }

  private final class OrderHandler implements InvocationHandler {
    private final FakeOrder o;
    OrderHandler(FakeOrder o) { this.o = o; }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) {
      switch (m.getName()) {
        case "getOrderId": return o.id;
        case "getQuantity": return o.qty;
        case "getFilled": return o.filled;
        case "getAvgFillPrice":
        case "getLastFillPrice": return o.fillPrice;
        case "getLastFillTime": return o.fillTimeMs;
        case "isFilled": return o.isFilled();
        case "isActive": return o.isActive();
        case "isCancelled": return o.cancelled;
        case "isBuy": return "BUY".equals(o.action);
        case "isSell": return "SELL".equals(o.action);
        case "getStopPrice": return "STOP".equals(o.type) ? o.price : null;
        case "getLimitPrice": return "LIMIT".equals(o.type) ? o.price : null;
        case "exists": return o.submitted;
        case "toString": return o.toString();
        case "hashCode": return System.identityHashCode(proxy);
        case "equals": return proxy == args[0];
        default: throw unmodelled("Order", m);
      }
    }
  }

  private final class InstrumentHandler implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method m, Object[] args) {
      switch (m.getName()) {
        case "getSymbol": return symbol;
        case "getPointValue": return 100.0;   // @GC
        case "getTickSize": return 0.1;
        case "round":
          if (args[0] instanceof Float f) return Math.round(f * 10f) / 10f; // snap to 0.1
          throw unmodelled("Instrument", m);
        case "toString": return symbol;
        case "hashCode": return System.identityHashCode(proxy);
        case "equals": return proxy == args[0];
        default: throw unmodelled("Instrument", m);
      }
    }
  }
}
