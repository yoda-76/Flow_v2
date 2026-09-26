package com.flow.rt;

import com.flow.core.Intent;

import java.util.List;

/**
 * First automated test of flow-runtime's order plumbing (plumbingEdgeCases.md
 * §13): drives the real OrderGateway against FakeBroker, a passive in-memory
 * pretend broker (see its javadoc for which behaviors are LIVE/DOC-confirmed
 * and which are the fake's own assumptions). No real OrderContext exists
 * anywhere in this test, so nothing can reach an account.
 *
 * Scope: OrderGateway only -- the SDK-bound half that needs no MotiveWave
 * runtime. The fill/cancel/reject callback state machine used to live inside
 * FlowRuntimeStudy, which cannot be instantiated outside MotiveWave (its
 * logging path initializes MotiveWave's own UI/config classes); it was
 * extracted into LiveOrderTracker (D-91) and is covered by
 * LiveOrderTrackerTest.
 *
 * Plain main(), nonzero exit on failure, wired into build/build.sh.
 */
public final class OrderGatewayTest {
  private static int failures = 0;

  private static void check(String label, boolean ok) {
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static void checkEq(String label, Object got, Object want) {
    boolean ok = want == null ? got == null : want.equals(got);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) {
    testRefuseToArm();
    testDryRunNeverOrders();
    testEntry();
    testBracketLegsAreIndependent();
    testCancelIfActive();
    testCloseAtMarketLeavesRestingOrders();
    testKillSwitchFlattensPositionAndOrders();
    testCancelAllOrdersNeverSendsAClose();
    testFullLifecycleStopFills();
    testFullLifecycleTargetFillsShort();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all OrderGateway synthetic checks passed.");
  }

  private static Intent intent(int targetPosition, Integer stopTicks, Integer targetTicks) {
    return new Intent("s", 1, targetPosition, stopTicks, targetTicks, "test");
  }

  // ---- D-24/D-61: refuse to arm ---------------------------------------

  private static void testRefuseToArm() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());
    check("flat account with no resting orders: clear to arm", gw.refuseToArmReason() == null);

    b.setPosition(1);
    String r = gw.refuseToArmReason();
    check("an open position refuses to arm, naming the position", r != null && r.contains("position=1"));

    b.setPosition(0);
    b.restingOrder("LIMIT", "SELL", 1, 4310f);
    r = gw.refuseToArmReason();
    check("a resting order refuses to arm, naming the count", r != null && r.contains("activeOrders=1"));
    check("hasRestingOrders() agrees", gw.hasRestingOrders());
    check("refusing to arm placed nothing", b.calls().isEmpty());
  }

  // ---- reconcileDryRun is reporting only ------------------------------

  private static void testDryRunNeverOrders() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());
    String line = gw.reconcileDryRun(intent(1, 8, 16));
    check("dry run reports WOULD_BUY", line.contains("\"action\":\"WOULD_BUY\""));
    check("dry run reports the quantity", line.contains("\"qty\":1"));
    check("dry run created no order", b.allOrders().isEmpty());
    check("dry run made no mutating call", b.calls().isEmpty());
    check("dry run left the position alone", b.position() == 0);

    b.setPosition(2);
    line = gw.reconcileDryRun(intent(0, null, null));
    check("dry run reports WOULD_SELL when target is below the position", line.contains("\"action\":\"WOULD_SELL\""));
    check("still no mutating call", b.calls().isEmpty());
  }

  // ---- entry ----------------------------------------------------------

  private static void testEntry() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());

    String line = gw.submitRealEntry(true, 1, "enter long");
    check("journal line says real_order_submitted", line.contains("\"type\":\"real_order_submitted\""));
    check("journal line carries side, qty, instrument, positionBefore",
        line.contains("\"side\":\"BUY\"") && line.contains("\"qty\":1")
            && line.contains("\"instrument\":\"GC\"") && line.contains("\"positionBefore\":0"));
    checkEq("exactly one order submitted", b.calls().size(), 1);
    List<FakeBroker.FakeOrder> active = b.activeOrders();
    checkEq("one active order", active.size(), 1);
    checkEq("it is a MARKET BUY 1", active.get(0).type + " " + active.get(0).action + " " + active.get(0).qty,
        "MARKET BUY 1");
    checkEq("position unchanged until the fill", b.position(), 0);

    b.fill(active.get(0));
    checkEq("after the fill the account is long 1", gw.currentPosition(), 1);
    check("a filled entry no longer counts as resting", !gw.hasRestingOrders());

    FakeBroker s = new FakeBroker();
    new OrderGateway(s.ctx()).submitRealEntry(false, 2, "enter short");
    FakeBroker.FakeOrder e = s.activeOrders().get(0);
    checkEq("a sell entry is a SELL 2", e.action + " " + e.qty, "SELL 2");
  }

  // ---- bracket --------------------------------------------------------

  private static void testBracketLegsAreIndependent() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());
    b.setPosition(1);

    OrderGateway.BracketOrders br = gw.submitRealBracket(false, 1, 4300f, 4310f, "bracket for long");
    check("bracket journal line", br.journalLine().contains("\"type\":\"real_bracket_submitted\"")
        && br.journalLine().contains("\"closingSide\":\"SELL\""));
    checkEq("both legs resting", b.activeOrders().size(), 2);
    check("stop leg is a SELL STOP at 4300", br.stop().getStopPrice() != null && br.stop().getStopPrice() == 4300f
        && br.stop().isSell());
    check("target leg is a SELL LIMIT at 4310", br.target().getLimitPrice() != null && br.target().getLimitPrice() == 4310f
        && br.target().isSell());

    // No OCO at the SDK level (2026-09-21 finding): filling one leg leaves the sibling resting.
    b.fill(b.allOrders().get(1)); // target fills
    check("the target leg is filled", br.target().isFilled());
    check("its sibling stop is still active -- nothing self-cancels", br.stop().isActive());
    checkEq("position flat after the target fill", b.position(), 0);
  }

  // ---- cancelIfActive -------------------------------------------------

  private static void testCancelIfActive() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());
    b.setPosition(1);
    OrderGateway.BracketOrders br = gw.submitRealBracket(false, 1, 4300f, 4310f, "r");

    String line = gw.cancelIfActive(br.stop(), "stop", "sibling leg");
    check("cancelling an active leg journals real_leg_cancelled",
        line != null && line.contains("\"type\":\"real_leg_cancelled\"") && line.contains("\"leg\":\"stop\""));
    check("the leg is cancelled and inactive", br.stop().isCancelled() && !br.stop().isActive());
    check("the OTHER leg was not touched", br.target().isActive());

    int callsBefore = b.calls().size();
    check("cancelling an already-cancelled leg returns null", gw.cancelIfActive(br.stop(), "stop", "again") == null);
    checkEq("...and makes no cancel call", b.calls().size(), callsBefore);

    b.fill(b.allOrders().get(1)); // target fills
    callsBefore = b.calls().size();
    check("cancelling an already-filled leg returns null", gw.cancelIfActive(br.target(), "target", "x") == null);
    checkEq("...and makes no cancel call", b.calls().size(), callsBefore);

    check("cancelling null returns null, no exception", gw.cancelIfActive(null, "stop", "x") == null);
  }

  // ---- closeAtMarket / kill switch ------------------------------------

  private static void testCloseAtMarketLeavesRestingOrders() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());
    b.setPosition(1);
    gw.submitRealBracket(false, 1, 4300f, 4310f, "r");

    String line = gw.closeAtMarket("universal flatten");
    check("journal line says real_close_at_market", line.contains("\"type\":\"real_close_at_market\"")
        && line.contains("\"positionBefore\":1"));
    checkEq("position flat", b.position(), 0);
    // The documented gotcha that caused the 2026-09-21 near-miss: closing does not cancel resting legs.
    checkEq("the bracket legs are STILL resting -- closeAtMarket() does not cancel them", b.activeOrders().size(), 2);
    check("no blanket cancel was issued", !b.called("cancelOrders(all)"));
  }

  private static void testKillSwitchFlattensPositionAndOrders() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());
    b.setPosition(-1);
    gw.submitRealBracket(true, 1, 4320f, 4290f, "r");

    String line = gw.cancelAllAndClose("daily loss limit");
    check("journal line says kill_switch_flatten", line.contains("\"type\":\"kill_switch_flatten\"")
        && line.contains("\"positionBefore\":-1") && line.contains("daily loss limit"));
    checkEq("position flat", b.position(), 0);
    checkEq("no orders resting", b.activeOrders().size(), 0);
    List<String> calls = b.calls();
    int closeIdx = -1;
    int cancelIdx = -1;
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).startsWith("closeAtMarket")) closeIdx = i;
      if (calls.get(i).startsWith("cancelOrders(all)")) cancelIdx = i;
    }
    check("close is issued before the blanket cancel (plumbingEdgeCases.md §7)",
        closeIdx >= 0 && cancelIdx > closeIdx);
  }

  /** D-92: the session-end flatten's flat-account branch -- cancel resting orders without a close. */
  private static void testCancelAllOrdersNeverSendsAClose() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());
    b.restingOrder("LIMIT", "SELL", 1, 4310f);
    b.restingOrder("STOP", "SELL", 1, 4300f);

    String line = gw.cancelAllOrders("session end");
    check("journal line says session_orders_cancelled", line.contains("\"type\":\"session_orders_cancelled\"")
        && line.contains("session end"));
    checkEq("nothing resting any more", b.activeOrders().size(), 0);
    check("a blanket cancel was issued", b.called("cancelOrders(all)"));
    check("NO closeAtMarket was sent to the flat account", !b.called("closeAtMarket"));
  }

  // ---- whole lifecycle, as FlowRuntimeStudy sequences it ---------------

  private static void testFullLifecycleStopFills() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());

    gw.submitRealEntry(true, 1, "enter long");
    b.fill(b.activeOrders().get(0));
    OrderGateway.BracketOrders br = gw.submitRealBracket(false, 1, 4300f, 4310f, "bracket");
    checkEq("long 1 with two resting legs", b.position() + "/" + b.activeOrders().size(), "1/2");

    b.fill(b.allOrders().get(1)); // the stop leg fills
    String cancel = gw.cancelIfActive(br.target(), "target", "sibling leg after stop filled");
    check("the surviving target leg is cancelled", cancel != null && br.target().isCancelled());
    checkEq("flat with nothing resting", b.position() + "/" + b.activeOrders().size(), "0/0");
    check("the account is clear to arm again", gw.refuseToArmReason() == null);
  }

  private static void testFullLifecycleTargetFillsShort() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());

    gw.submitRealEntry(false, 1, "enter short");
    b.fill(b.activeOrders().get(0));
    checkEq("short 1", b.position(), -1);
    OrderGateway.BracketOrders br = gw.submitRealBracket(true, 1, 4320f, 4290f, "bracket");
    check("a short's bracket closes with BUYs", br.stop().isBuy() && br.target().isBuy());

    b.fill(b.allOrders().get(2)); // the target leg fills
    gw.cancelIfActive(br.stop(), "stop", "sibling leg after target filled");
    checkEq("flat with nothing resting", b.position() + "/" + b.activeOrders().size(), "0/0");
  }
}
