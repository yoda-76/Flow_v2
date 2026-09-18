package com.flow.rt;

import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;

import java.lang.reflect.Method;

/**
 * Builds stop/limit Orders via reflection, same class of Javadoc-vs-jar
 * mismatch as MarkerAdapter (see its javadoc) -- confirmed directly here:
 * `Enums.OrderAction`/`Enums.TIF` fail to compile as source-level types
 * ("cannot find symbol: class OrderAction, location: interface Enums")
 * the same way Enums.MarkerType does, even though OrderContext's own
 * compiled method signatures reference them.
 *
 * createMarketOrder/createStopOrder/createLimitOrder all go through
 * reflection (their signatures need the enum constants);
 * OrderContext.submitOrders(Order...) takes no Enums-typed params and is
 * called directly, ordinary method call, from OrderGateway.
 */
final class OrderAdapter {
  private static final Class<?> ORDER_ACTION_CLS;
  private static final Class<?> TIF_CLS;
  private static final Object BUY;
  private static final Object SELL;
  private static final Object DAY;
  private static final Method CREATE_MARKET_ORDER;
  private static final Method CREATE_STOP_ORDER;
  private static final Method CREATE_LIMIT_ORDER;

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object enumConst(Class<?> cls, String name) {
    return Enum.valueOf((Class) cls, name);
  }

  static {
    try {
      ORDER_ACTION_CLS = Class.forName("com.motivewave.platform.sdk.common.Enums$OrderAction");
      TIF_CLS = Class.forName("com.motivewave.platform.sdk.common.Enums$TIF");
      BUY = enumConst(ORDER_ACTION_CLS, "BUY");
      SELL = enumConst(ORDER_ACTION_CLS, "SELL");
      DAY = enumConst(TIF_CLS, "DAY");
      CREATE_MARKET_ORDER = OrderContext.class.getMethod(
          "createMarketOrder", ORDER_ACTION_CLS, int.class);
      CREATE_STOP_ORDER = OrderContext.class.getMethod(
          "createStopOrder", ORDER_ACTION_CLS, TIF_CLS, int.class, float.class);
      CREATE_LIMIT_ORDER = OrderContext.class.getMethod(
          "createLimitOrder", ORDER_ACTION_CLS, TIF_CLS, int.class, float.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private OrderAdapter() {}

  /**
   * D-67: entry now goes through this + submitOrders(Order...), NOT
   * OrderContext.buy(int)/sell(int) -- see D-67's javadoc for why (the
   * live test's fill never triggered onOrderFilled, suspected to be
   * because buy()/sell() return void, giving the strategy no Order
   * reference and, per hypothesis, no tracked link the platform can
   * attribute a later fill callback to). createMarketOrder() returns a
   * real Order the same way createStopOrder/createLimitOrder already do
   * -- same family, same submission path, for every order type now.
   */
  static Order marketOrder(OrderContext ctx, boolean isBuy, int qty) {
    try {
      return (Order) CREATE_MARKET_ORDER.invoke(ctx, isBuy ? BUY : SELL, qty);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("failed to create market Order", e);
    }
  }

  /** DAY TIF, closing-side action (isBuy = the CLOSING order's side, e.g. SELL to exit a long). */
  static Order stopOrder(OrderContext ctx, boolean isBuy, int qty, float stopPrice) {
    try {
      return (Order) CREATE_STOP_ORDER.invoke(ctx, isBuy ? BUY : SELL, DAY, qty, stopPrice);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("failed to create stop Order", e);
    }
  }

  static Order limitOrder(OrderContext ctx, boolean isBuy, int qty, float limitPrice) {
    try {
      return (Order) CREATE_LIMIT_ORDER.invoke(ctx, isBuy ? BUY : SELL, DAY, qty, limitPrice);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("failed to create limit Order", e);
    }
  }
}
