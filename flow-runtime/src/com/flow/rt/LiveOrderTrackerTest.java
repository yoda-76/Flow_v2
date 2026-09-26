package com.flow.rt;

import com.flow.core.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives LiveOrderTracker -- the automatic-real-order state machine
 * extracted from FlowRuntimeStudy (D-91) -- against FakeBroker. Covers what
 * only live Sim sessions exercised before: entry -> bracket-on-fill, the
 * sibling-leg cleanup, D-87's double-fill correction, and every disarm path.
 *
 * FakeBroker is passive, so each test scripts the broker event (fill/
 * reject) and THEN calls the matching tracker callback, in the order the
 * test wants -- the callback ordering is the thing under test. No real
 * OrderContext exists here; nothing can reach an account.
 *
 * Fidelity note (see FakeBroker's javadoc): "callback delivered after the
 * broker event" is how D-81's live run behaved; concurrent delivery for two
 * legs (plumbingEdgeCases.md §10) is NOT modelled and stays an open live
 * question.
 */
public final class LiveOrderTrackerTest {
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

  /** One tracker wired to a fresh FakeBroker, with the Study's four dependencies stubbed. */
  private static final class Rig {
    final FakeBroker broker = new FakeBroker();
    final OrderGateway gw = new OrderGateway(broker.ctx());
    final PriceCodec codec = new PriceCodec(0.1);
    final List<String> log = new ArrayList<>();
    boolean armDenied = false;
    boolean gatewayAvailable = true;
    final LiveOrderTracker tracker;
    int seq = 0;

    Rig() {
      codec.toTicks(4300.0); // first price seen becomes the anchor: tick 0 == 4300.0
      tracker = new LiveOrderTracker(() -> gatewayAvailable ? gw : null, () -> codec, log::add, () -> armDenied = true);
    }

    /** Opening intent with stop/target in ticks from the anchor. */
    String reconcile(int targetPosition, Integer stopTicks, Integer targetTicks) {
      return tracker.reconcileLive(new Intent("s", ++seq, targetPosition, stopTicks, targetTicks, "test"), gw);
    }

    boolean logged(String prefix) {
      for (String l : log) if (l.startsWith(prefix)) return true;
      return false;
    }

    FakeBroker.FakeOrder entry() { return broker.allOrders().get(0); }

    /** Long 1 entry submitted, filled, callback delivered: bracket resting, tracker settled. */
    void openLong() {
      reconcile(1, -10, 20);
      broker.fill(entry());
      tracker.onOrderFilled(entry().order());
    }
  }

  public static void main(String[] args) {
    testEntryThenBracketOnFill();
    testShortBracketClosesWithBuys();
    testSecondIntentSkippedWhileEntryInFlight();
    testGuardsPlaceNothing();
    testStopFillsCancelsTargetCleanly();
    testTargetFillsCancelsStopCleanly();
    testDoubleFillIsDetectedAndFlattened();
    testEntryRejectedDisarms();
    testEntryCancelledDisarms();
    testSelfCancelIsNotAnAnomaly();
    testUnrelatedFillAndCancelAreIgnored();
    testEntryWithoutBracketPrices();
    testGatewayGoneAtFill();
    testKillSwitchResetForgetsEverything();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all LiveOrderTracker synthetic checks passed.");
  }

  // ---- entry -> bracket ------------------------------------------------

  private static void testEntryThenBracketOnFill() {
    Rig r = new Rig();
    String line = r.reconcile(1, -10, 20);
    check("opening intent submits a real entry", line.contains("\"type\":\"real_order_submitted\"")
        && line.contains("\"side\":\"BUY\""));
    check("tracker now has an order in flight", r.tracker.orderInFlight());
    checkEq("only the entry exists yet -- no bracket ahead of the fill", r.broker.allOrders().size(), 1);

    r.broker.fill(r.entry());
    r.tracker.onOrderFilled(r.entry().order());
    check("in-flight cleared once the entry fills", !r.tracker.orderInFlight());
    checkEq("entry + stop + target exist", r.broker.allOrders().size(), 3);
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    FakeBroker.FakeOrder target = r.broker.allOrders().get(2);
    check("stop is a SELL STOP at 4299.0 (-10 ticks)", "STOP".equals(stop.type) && "SELL".equals(stop.action)
        && stop.price == 4299.0f);
    check("target is a SELL LIMIT at 4302.0 (+20 ticks)", "LIMIT".equals(target.type) && "SELL".equals(target.action)
        && target.price == 4302.0f);
    check("both legs are resting", stop.isActive() && target.isActive());
    check("tracker holds the leg references", r.tracker.restingStop() == stop.order()
        && r.tracker.restingTarget() == target.order());
    check("bracket submission was logged", r.logged("LIVE_BRACKET_SUBMITTED"));
    check("nothing disarmed", !r.armDenied);
  }

  private static void testShortBracketClosesWithBuys() {
    Rig r = new Rig();
    r.reconcile(-1, 10, -20);
    check("a short entry is a SELL", "SELL".equals(r.entry().action));
    r.broker.fill(r.entry());
    r.tracker.onOrderFilled(r.entry().order());
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    FakeBroker.FakeOrder target = r.broker.allOrders().get(2);
    check("a short's stop is a BUY STOP at 4301.0", "BUY".equals(stop.action) && stop.price == 4301.0f);
    check("a short's target is a BUY LIMIT at 4298.0", "BUY".equals(target.action) && target.price == 4298.0f);
  }

  // ---- guards ----------------------------------------------------------

  private static void testSecondIntentSkippedWhileEntryInFlight() {
    Rig r = new Rig();
    r.reconcile(1, -10, 20);
    String second = r.reconcile(-1, 10, -20);
    check("a second intent while the entry is unresolved is skipped, not stacked",
        second.contains("reconcile_live_skipped") && second.contains("still in flight"));
    checkEq("still only one order", r.broker.allOrders().size(), 1);
  }

  private static void testGuardsPlaceNothing() {
    Rig noop = new Rig();
    check("target == position is a noop", noop.reconcile(0, null, null).contains("reconcile_live_noop"));
    check("noop placed nothing", noop.broker.calls().isEmpty());

    Rig closing = new Rig();
    closing.broker.setPosition(1);
    String c = closing.reconcile(0, null, null);
    check("a closing intent is logged, not executed (bracket-only)",
        c.contains("reconcile_live_skipped") && c.contains("bracket-only"));
    check("closing placed nothing", closing.broker.calls().isEmpty());

    Rig flip = new Rig();
    flip.broker.setPosition(1);
    String f = flip.reconcile(-1, 10, -20);
    check("a direct flip is skipped", f.contains("reconcile_live_skipped") && f.contains("direct flip"));
    check("flip placed nothing", flip.broker.calls().isEmpty());

    Rig stacked = new Rig();
    stacked.broker.restingOrder("LIMIT", "SELL", 1, 4310f);
    String s = stacked.reconcile(1, -10, 20);
    check("resting orders on the account block a new entry", s.contains("resting orders found"));
    check("blocked entry placed nothing", stacked.broker.calls().isEmpty());
    check("and left nothing in flight", !stacked.tracker.orderInFlight());
  }

  // ---- bracket resolves ------------------------------------------------

  private static void testStopFillsCancelsTargetCleanly() {
    Rig r = new Rig();
    r.openLong();
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    FakeBroker.FakeOrder target = r.broker.allOrders().get(2);

    r.broker.fill(stop);
    r.tracker.onOrderFilled(stop.order());
    check("the sibling target was cancelled", target.cancelled);
    check("sibling cancellation logged", r.logged("LIVE_SIBLING_LEG_CANCELLED"));
    checkEq("flat with nothing resting", r.broker.position() + "/" + r.broker.activeOrders().size(), "0/0");
    check("a clean stop-out does not disarm", !r.armDenied);
    check("no mismatch reported", !r.logged("POSITION_MISMATCH"));
    check("tracker forgot the legs", r.tracker.restingStop() == null && r.tracker.restingTarget() == null);

    r.tracker.onOrderCancelled(target.order()); // the callback for OUR cancel arrives after
    check("the callback for our own cancel is expected -- no disarm", !r.armDenied);

    // The case the exemption exists for: the strategy re-enters (account is flat, nothing resting)
    // BEFORE the callback for our sibling cancel is delivered. That late callback must not be
    // mistaken for the new entry being cancelled.
    Rig late = new Rig();
    late.openLong();
    FakeBroker.FakeOrder lateStop = late.broker.allOrders().get(1);
    FakeBroker.FakeOrder lateTarget = late.broker.allOrders().get(2);
    late.broker.fill(lateStop);
    late.tracker.onOrderFilled(lateStop.order());
    late.reconcile(1, -10, 20); // new entry now in flight
    check("a new entry is in flight", late.tracker.orderInFlight());
    late.tracker.onOrderCancelled(lateTarget.order()); // stale callback for the old sibling
    check("the stale cancel callback does not disarm", !late.armDenied);
    check("and does not clear the new in-flight entry", late.tracker.orderInFlight());
    late.tracker.onOrderCancelled(late.broker.allOrders().get(3).order()); // now the new entry itself is cancelled
    check("a real cancel of the new entry still disarms", late.armDenied);
  }

  private static void testTargetFillsCancelsStopCleanly() {
    Rig r = new Rig();
    r.openLong();
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    FakeBroker.FakeOrder target = r.broker.allOrders().get(2);

    r.broker.fill(target);
    r.tracker.onOrderFilled(target.order());
    check("the sibling stop was cancelled", stop.cancelled);
    checkEq("flat with nothing resting", r.broker.position() + "/" + r.broker.activeOrders().size(), "0/0");
    check("a clean target hit does not disarm", !r.armDenied);
  }

  // ---- D-87 ------------------------------------------------------------

  private static void testDoubleFillIsDetectedAndFlattened() {
    Rig r = new Rig();
    r.openLong();
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    FakeBroker.FakeOrder target = r.broker.allOrders().get(2);

    // Both legs fill at the broker before the sibling cancel can land: long 1 - 1 - 1 = short 1.
    r.broker.fill(stop);
    r.broker.fill(target);
    checkEq("the account is now accidentally short 1", r.broker.position(), -1);

    r.tracker.onOrderFilled(stop.order());
    check("the mismatch is detected", r.logged("POSITION_MISMATCH_DETECTED"));
    check("the correction is logged", r.logged("POSITION_MISMATCH_CORRECTED"));
    check("arming is denied after a mismatch", r.armDenied);
    checkEq("the account is flattened", r.broker.position(), 0);
    check("the flatten was a close then a blanket cancel",
        r.broker.called("closeAtMarket") && r.broker.called("cancelOrders(all)"));
  }

  // ---- disarm paths ----------------------------------------------------

  private static void testEntryRejectedDisarms() {
    Rig r = new Rig();
    r.reconcile(1, -10, 20);
    r.broker.reject(r.entry());
    r.tracker.onOrderRejected(r.entry().order());
    check("a rejected entry clears in-flight", !r.tracker.orderInFlight());
    check("and disarms", r.armDenied);
    check("and says why", r.logged("LIVE_ENTRY_REJECTED_DISARMING"));
    checkEq("no bracket was ever placed for a position that doesn't exist", r.broker.allOrders().size(), 1);
  }

  private static void testEntryCancelledDisarms() {
    Rig r = new Rig();
    r.reconcile(1, -10, 20);
    r.tracker.onOrderCancelled(r.entry().order()); // not one of OUR cancels
    check("an unexpected cancel of the in-flight entry clears in-flight", !r.tracker.orderInFlight());
    check("and disarms", r.armDenied);
    check("and says why", r.logged("LIVE_ENTRY_CANCELLED_DISARMING"));
  }

  private static void testSelfCancelIsNotAnAnomaly() {
    Rig idle = new Rig();
    idle.openLong();
    idle.tracker.onOrderCancelled(idle.broker.allOrders().get(1).order()); // a leg cancelled by someone else, nothing in flight
    check("a cancel with no entry in flight does not disarm", !idle.armDenied);
  }

  private static void testUnrelatedFillAndCancelAreIgnored() {
    Rig r = new Rig();
    r.openLong();
    FakeBroker.FakeOrder foreign = r.broker.restingOrder("LIMIT", "SELL", 1, 4400f);
    int callsBefore = r.broker.calls().size();
    r.broker.fill(foreign);
    r.tracker.onOrderFilled(foreign.order());
    checkEq("an untracked fill causes no calls", r.broker.calls().size(), callsBefore);
    check("and leaves our legs tracked", r.tracker.restingStop() != null && r.tracker.restingTarget() != null);
    check("and does not disarm", !r.armDenied);
  }

  // ---- edges -----------------------------------------------------------

  private static void testEntryWithoutBracketPrices() {
    Rig r = new Rig();
    r.reconcile(1, null, null);
    r.broker.fill(r.entry());
    r.tracker.onOrderFilled(r.entry().order());
    check("no stop/target on the intent -> no bracket, logged", r.logged("LIVE_FILL_NO_BRACKET"));
    checkEq("only the entry exists", r.broker.allOrders().size(), 1);
    check("in-flight cleared", !r.tracker.orderInFlight());
  }

  private static void testGatewayGoneAtFill() {
    Rig r = new Rig();
    r.reconcile(1, -10, 20);
    r.gatewayAvailable = false; // study deactivated between submit and fill
    r.broker.fill(r.entry());
    r.tracker.onOrderFilled(r.entry().order());
    check("no gateway -> bracket skipped and logged, no exception", r.logged("LIVE_BRACKET_SKIPPED"));
    checkEq("no bracket orders", r.broker.allOrders().size(), 1);
    check("in-flight cleared", !r.tracker.orderInFlight());
  }

  private static void testKillSwitchResetForgetsEverything() {
    Rig inFlight = new Rig();
    inFlight.reconcile(1, -10, 20);
    inFlight.tracker.resetForKillSwitch();
    check("reset clears an in-flight entry", !inFlight.tracker.orderInFlight());

    Rig bracketed = new Rig();
    bracketed.openLong();
    bracketed.tracker.resetForKillSwitch();
    check("reset forgets the resting legs",
        bracketed.tracker.restingStop() == null && bracketed.tracker.restingTarget() == null);
    FakeBroker.FakeOrder stop = bracketed.broker.allOrders().get(1);
    int callsBefore = bracketed.broker.calls().size();
    bracketed.tracker.onOrderFilled(stop.order());
    checkEq("a late fill after the reset is treated as untracked -- no sibling handling",
        bracketed.broker.calls().size(), callsBefore);
  }
}
