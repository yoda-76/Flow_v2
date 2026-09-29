package com.flow.rt;

import com.flow.core.FillEvent;
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
    final List<String> records = new ArrayList<>(); // structured decision records (order_fill)
    boolean armDenied = false;
    boolean gatewayAvailable = true;
    final LiveOrderTracker tracker;
    int seq = 0;

    final List<String> executions = new ArrayList<>();  // F-11: entry_execution cost records
    final List<Runnable> scheduled = new ArrayList<>(); // F-1: post-flatten verifications, run by hand
    final List<Long> scheduledDelays = new ArrayList<>();
    int afterFillCalls = 0;
    final List<FillEvent> publishedFills = new ArrayList<>(); // C1 (D-120)
    int nextFillSeq = 1;

    Rig() {
      codec.toTicks(4300.0); // first price seen becomes the anchor: tick 0 == 4300.0
      // entry_execution (F-11) records are kept apart so the order_fill counts the older tests check stay meaningful.
      tracker = new LiveOrderTracker(() -> gatewayAvailable ? gw : null, () -> codec, log::add,
          line -> { if (line.contains("\"type\":\"entry_execution\"")) executions.add(line); else records.add(line); },
          () -> armDenied = true);
      tracker.setScheduler((task, delay) -> { scheduled.add(task); scheduledDelays.add(delay); });
      tracker.setAfterFillHook(() -> afterFillCalls++);
      tracker.setLockAction(gw::lockOrders);
      tracker.setPublishFill((eventTimeMs, factory) -> publishedFills.add(factory.create(nextFillSeq++, eventTimeMs, eventTimeMs)));
    }

    /** Runs the oldest queued verification (it may queue the next one). */
    void runNextVerification() { scheduled.remove(0).run(); }

    boolean loggedContaining(String s) {
      for (String l : log) if (l.contains(s)) return true;
      return false;
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
    testKillSwitchAccountSide();
    testBracketPricesAreSnappedToTheTickGrid();
    testEntryRefusalsAreReported();
    testSessionFlattenClosesAPositionAndItsBracket();
    testSessionFlattenIsIdempotentWhenFlat();
    testSessionFlattenCancelsStrayOrdersWithoutAClose();
    testSessionFlattenRetriesUntilFlat();
    testSessionFlattenWithNoGateway();
    testFillRecordsCarryRoleAndPrices();
    testFillRecordForAFlattenCloseIsUntracked();
    testFillRecordFailureNeverBreaksOrderHandling();
    testPartialFills();
    testKillSwitchWithAFilledLegSendsNoCloseL1();
    testKillSwitchWrongWayFillIsCaughtAndCorrected();
    testKillSwitchDisagreeingPositionsSendNothing();
    testSessionFlattenDoesNotCloseOnAStaleStudyPosition();
    testEntryRefusedWhenTheAccountHoldsSomethingTheStudyDoesNot();
    testRefuseToArmSeesTheAccountPosition();
    testResetForReactivationForgetsEverything();
    testAfterFillHookAndAccountTruth();
    testEntryStopTargetFillsArePublished();
    testUntrackedFillIsNotPublished();
    testFillPublishingIsOptional();
    testLostLegFlattensAndDisarms();
    testPlatformSiblingCancelIsBenign();
    testLostLegWithDisagreeingPositionsSendsNoClose();
    testBracketAnchoredToTheFill();
    testAbsoluteBracketUnchangedAndWrongSideLegsAreClamped();
    testEntryExecutionCostRecord();
    testEntrySlippageCapOffByDefault();
    testEntrySlippageCapSubmitsACappedLimit();
    testEntrySlippageCapFillsNormallyLikeAnyOtherEntry();
    testEntrySlippageCapGivesUpOnTimeout();
    testEntrySlippageCapTimeoutIsANoOpIfAlreadyResolved();
    testEntrySlippageCapNeedsATouchToCapFrom();
    testFeedResumeFlattensOnlyWhenALevelWasReached();
    testFeedResumeOtherCases();
    testGapRangeCatchesASpikeAndReturnMissedByTheQuickCheck();
    testGapRangeCatchesACrossOnlyVisibleAtTheHighEndOfTheRange();
    testGapRangeIgnoresAFlattenedAccountEvenWithLegsStillReferenced();
    testGapRangeOtherCases();
    testNonSimulatedAccountIsNeverActedOn();
    testEntryOnANonSimulatedAccountIsRefusedBeforeSending();
    testUnknownAccountIdIsAllowedButLogged();

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

  // ---- D-94: structured fill records -----------------------------------

  private static void testFillRecordsCarryRoleAndPrices() {
    Rig r = new Rig();
    r.broker.setCash(98_640.0);
    r.reconcile(1, -10, 20);
    r.broker.fill(r.entry(), 4300.1f, 1_790_000_000_000L);
    r.tracker.onOrderFilled(r.entry().order());
    checkEq("one fill record after the entry", r.records.size(), 1);
    String e = r.records.get(0);
    check("entry record: type, role, side, instrument", e.contains("\"type\":\"order_fill\"")
        && e.contains("\"role\":\"entry\"") && e.contains("\"action\":\"BUY\"") && e.contains("\"instrument\":\"GC\""));
    check("entry record: clean fill price, not float noise", e.contains("\"avgFillPrice\":4300.1,"));
    check("entry record: fill time, position after, cash, point value", e.contains("\"lastFillTimeMs\":1790000000000")
        && e.contains("\"positionAfter\":1") && e.contains("\"cashBalance\":98640.0") && e.contains("\"pointValue\":100.0"));
    check("entry record: a market order has no stop/limit price", e.contains("\"stopPrice\":null") && e.contains("\"limitPrice\":null"));
    check("entry record: wall clock present", e.contains("\"t\":"));

    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    r.broker.fill(stop, 4299.0f, 1_790_000_060_000L);
    r.tracker.onOrderFilled(stop.order());
    checkEq("a second fill record after the stop", r.records.size(), 2);
    String s = r.records.get(1);
    check("stop record: role stop, SELL, at the stop price", s.contains("\"role\":\"stop\"") && s.contains("\"action\":\"SELL\"")
        && s.contains("\"avgFillPrice\":4299.0,") && s.contains("\"stopPrice\":4299.0"));
    check("stop record: position after is flat", s.contains("\"positionAfter\":0"));

    Rig t = new Rig();
    t.openLong();
    FakeBroker.FakeOrder target = t.broker.allOrders().get(2);
    t.broker.fill(target, 4302.0f, 1_790_000_090_000L);
    t.tracker.onOrderFilled(target.order());
    String tr = t.records.get(t.records.size() - 1);
    check("target record: role target at the limit price", tr.contains("\"role\":\"target\"")
        && tr.contains("\"limitPrice\":4302.0") && tr.contains("\"avgFillPrice\":4302.0,"));
  }

  private static void testFillRecordForAFlattenCloseIsUntracked() {
    Rig r = new Rig();
    r.openLong();
    int before = r.records.size();
    r.tracker.flattenForSessionEnd("session-end flatten window"); // resets tracking, then the platform's close fills
    FakeBroker.FakeOrder platformClose = r.broker.restingOrder("MARKET", "SELL", 1, 4301f);
    r.broker.fill(platformClose, 4301.0f, 1_790_000_120_000L);
    r.tracker.onOrderFilled(platformClose.order());
    checkEq("one more record", r.records.size(), before + 1);
    check("a fill nothing tracks is recorded as untracked", r.records.get(before).contains("\"role\":\"untracked\""));
  }

  /** The record is diagnostics only: a failing recorder must not stop the bracket being placed. */
  private static void testFillRecordFailureNeverBreaksOrderHandling() {
    FakeBroker broker = new FakeBroker();
    OrderGateway gw = new OrderGateway(broker.ctx());
    PriceCodec codec = new PriceCodec(0.1);
    codec.toTicks(4300.0);
    List<String> log = new ArrayList<>();
    LiveOrderTracker tracker = new LiveOrderTracker(() -> gw, () -> codec, log::add,
        line -> { throw new RuntimeException("journal exploded"); }, () -> {});
    tracker.reconcileLive(new Intent("s", 1, 1, -10, 20, "test"), gw);
    broker.fill(broker.allOrders().get(0));
    tracker.onOrderFilled(broker.allOrders().get(0).order());
    checkEq("the bracket was still submitted", broker.allOrders().size(), 3);
    check("and the failure was logged, not thrown", log.stream().anyMatch(l -> l.startsWith("ORDER_FILL_RECORD_FAILED")));
  }

  // ---- D-92: session-end flatten ---------------------------------------

  private static void testSessionFlattenClosesAPositionAndItsBracket() {
    Rig r = new Rig();
    r.openLong();
    check("precondition: long 1 with two resting legs", r.broker.position() == 1 && r.broker.activeOrders().size() == 2);

    boolean sent = r.tracker.flattenForSessionEnd("session-end flatten window");
    check("a flatten was sent", sent);
    checkEq("flat", r.broker.position(), 0);
    checkEq("nothing resting", r.broker.activeOrders().size(), 0);
    check("close came before the blanket cancel", r.broker.calls().indexOf("closeAtMarket positionBefore=1")
        < r.broker.calls().indexOf("cancelOrders(all)"));
    check("the flatten is logged", r.logged("SESSION_FLATTEN"));
    check("the tracker forgot its legs", r.tracker.restingStop() == null && r.tracker.restingTarget() == null);
    check("it does NOT disarm -- the study trades again after the reopen", !r.armDenied);

    // The cancels of our own bracket legs now arrive as callbacks: they must not be read as an anomaly.
    r.tracker.onOrderCancelled(r.broker.allOrders().get(1).order());
    r.tracker.onOrderCancelled(r.broker.allOrders().get(2).order());
    check("the late cancel callbacks for the swept legs do not disarm", !r.armDenied);

    // Trading resumes normally after the reopen.
    r.reconcile(1, -10, 20);
    check("after the flatten a new entry can be submitted", r.tracker.orderInFlight());
  }

  private static void testSessionFlattenIsIdempotentWhenFlat() {
    Rig r = new Rig();
    boolean sent = r.tracker.flattenForSessionEnd("session-end flatten window");
    check("flat account, nothing resting: nothing is sent", !sent);
    check("...and no call was made at all", r.broker.calls().isEmpty());
    check("...and nothing logged", r.log.isEmpty());

    r.openLong();
    r.tracker.flattenForSessionEnd("first");
    int callsAfterFirst = r.broker.calls().size();
    check("second call after a successful flatten is a no-op", !r.tracker.flattenForSessionEnd("second"));
    checkEq("...no further calls", r.broker.calls().size(), callsAfterFirst);
  }

  private static void testSessionFlattenCancelsStrayOrdersWithoutAClose() {
    Rig r = new Rig();
    r.broker.restingOrder("LIMIT", "SELL", 1, 4310f); // flat, but an order is still working
    boolean sent = r.tracker.flattenForSessionEnd("session-end flatten window");
    check("a stray resting order is swept", sent && r.broker.activeOrders().isEmpty());
    check("but no close is sent to a flat account", !r.broker.called("closeAtMarket"));
    check("logged as a session flatten", r.logged("SESSION_FLATTEN"));
  }

  private static void testSessionFlattenRetriesUntilFlat() {
    Rig r = new Rig();
    r.openLong();
    r.tracker.flattenForSessionEnd("try 1");
    // Simulate the close having been rejected: the account is still long.
    r.broker.setPosition(1);
    boolean again = r.tracker.flattenForSessionEnd("try 2");
    check("the account is still long -> the retry sends another flatten", again);
    checkEq("flat after the retry", r.broker.position(), 0);
  }

  private static void testSessionFlattenWithNoGateway() {
    Rig r = new Rig();
    r.openLong();
    r.gatewayAvailable = false;
    check("no gateway (study deactivated) -> nothing sent, no exception", !r.tracker.flattenForSessionEnd("x"));
    checkEq("the position is untouched", r.broker.position(), 1);
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

  // ---- F-1/F-2 (2026-09-28): live finding L-1 ------------------------------------------------------------------
  //
  // Live: a long 1 with a resting stop. The stop filled (SIM-166) in the same millisecond the daily-loss kill switch read
  // the study's position (still 1) and sent closeAtMarket (SIM-168) -> two sells against one long -> SHORT 1, unprotected,
  // and the tracker had already forgotten its legs so nothing noticed.

  /** The leg's own state says it filled: no close is sent even though BOTH position readings still say long. */
  private static void testKillSwitchWithAFilledLegSendsNoCloseL1() {
    Rig r = new Rig();
    r.openLong();
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    r.broker.fill(stop, 4299.0f, 1_790_000_000_000L); // the real account is flat now ...
    r.broker.reportStrategyPosition(1);               // ... but the study's view has not caught up
    r.broker.reportAccountPosition(1);                // ... and neither has the account read (worst case)
    r.tracker.onKillSwitch("daily loss limit breached", true);
    check("L-1: a filled tracked leg -> NO closeAtMarket is sent", !r.broker.called("closeAtMarket"));
    check("...the surviving target leg is swept", r.broker.activeOrders().isEmpty());
    check("...and it says why", r.loggedContaining("already filled"));
    checkEq("a verification is queued", r.scheduled.size(), 1);
    checkEq("...for 1.5 s later", r.scheduledDelays.get(0), 1_500L);
    r.broker.reportStrategyPosition(null);
    r.broker.reportAccountPosition(null);
    r.runNextVerification();
    check("the verification finds the account flat", r.loggedContaining("FLATTEN_VERIFIED KILL_SWITCH"));
    check("...and sends nothing", !r.broker.called("closeAtMarket"));
  }

  /** The race the leg check cannot see (fill after the read): the account ends up SHORT 1 -- the verification undoes it. */
  private static void testKillSwitchWrongWayFillIsCaughtAndCorrected() {
    Rig r = new Rig();
    r.openLong();
    r.tracker.onKillSwitch("daily loss limit breached", true);
    check("normal case: long 1 -> close sent", r.broker.called("closeAtMarket positionBefore=1"));
    checkEq("flat right after the kill switch", r.broker.position(), 0);
    r.broker.setPosition(-1); // the stop leg's fill arrived after the read: two sells against one long
    check("precondition: the account is short 1", r.broker.position() == -1);
    r.runNextVerification(); // 1.5 s
    check("verification 1: short 1 seen by BOTH readings -> corrected", r.loggedContaining("FLATTEN_CORRECTING KILL_SWITCH study=-1 account=-1"));
    check("...with a close of the short (positionBefore=-1)", r.broker.called("closeAtMarket positionBefore=-1"));
    checkEq("...flat again", r.broker.position(), 0);
    checkEq("a further verification is queued to confirm", r.scheduled.size(), 1);
    r.runNextVerification(); // 3 s
    check("verification 2: confirmed flat", r.loggedContaining("FLATTEN_VERIFIED KILL_SWITCH"));
    checkEq("nothing further queued", r.scheduled.size(), 0);
    check("no NOT_CONFIRMED alert", !r.loggedContaining("FLATTEN_NOT_CONFIRMED"));

    // A close that never takes: three looks, then the ALERT.
    Rig stuck = new Rig();
    stuck.openLong();
    stuck.tracker.onKillSwitch("x", true);
    stuck.broker.setPosition(-1);
    stuck.runNextVerification();
    stuck.broker.setPosition(-1); // the corrective close did not take either
    stuck.runNextVerification();
    stuck.broker.setPosition(-1);
    stuck.runNextVerification();
    check("still not flat after the third look: CHECK THE ACCOUNT BY HAND alert", stuck.loggedContaining("FLATTEN_NOT_CONFIRMED"));
    checkEq("no more looks queued", stuck.scheduled.size(), 0);
  }

  /** L-2: the study says long, the account says flat (a manual close). Sending closeAtMarket would open a SHORT. */
  private static void testKillSwitchDisagreeingPositionsSendNothing() {
    Rig r = new Rig();
    r.openLong();
    int callsBefore = r.broker.calls().size();
    r.broker.reportAccountPosition(0);
    r.tracker.onKillSwitch("daily loss limit breached", true);
    check("study=1 account=0: no order at all", !r.broker.called("closeAtMarket") && r.broker.calls().size() == callsBefore);
    check("...the bracket is left working", r.broker.activeOrders().size() == 2);
    check("...and the disagreement is logged", r.loggedContaining("POSITION_SOURCES_DISAGREE KILL_SWITCH study=1 account=0"));
    check("the kill-switch line is still logged (the report keys on it)", r.logged("KILL_SWITCH_TRIGGERED"));
    r.runNextVerification();
    check("verification: disagree, no order", r.loggedContaining("FLATTEN_VERIFY_DISAGREE") && !r.broker.called("closeAtMarket"));
    r.runNextVerification();
    r.runNextVerification();
    check("three looks, still disagreeing: ALERT", r.loggedContaining("FLATTEN_NOT_CONFIRMED"));
    check("...and still no order was ever sent", !r.broker.called("closeAtMarket"));

    // The opposite disagreement: the account holds it, the study's view says flat.
    Rig o = new Rig();
    o.broker.setPosition(1);
    o.broker.reportStrategyPosition(0);
    o.tracker.onKillSwitch("x", true);
    check("study=0 account=1: not closed (closeAtMarket closes the STUDY's position)", !o.broker.called("closeAtMarket"));
    check("...and logged", o.loggedContaining("POSITION_SOURCES_DISAGREE KILL_SWITCH study=0 account=1"));
  }

  private static void testSessionFlattenDoesNotCloseOnAStaleStudyPosition() {
    Rig r = new Rig();
    r.openLong();
    r.broker.setPosition(0);            // the account is flat (a manual close) ...
    r.broker.reportStrategyPosition(1); // ... the study still says long
    boolean sent = r.tracker.flattenForSessionEnd("session-end flatten window");
    check("a disagreement at the session flatten: nothing sent", !sent && !r.broker.called("closeAtMarket"));
    check("...the bracket was not swept either (the next retry looks again)", r.broker.activeOrders().size() == 2);
    r.broker.reportStrategyPosition(null);
    check("once the readings agree (both flat, legs resting) it sweeps the strays", r.tracker.flattenForSessionEnd("retry")
        && r.broker.activeOrders().isEmpty() && !r.broker.called("closeAtMarket"));
  }

  private static void testEntryRefusedWhenTheAccountHoldsSomethingTheStudyDoesNot() {
    Rig r = new Rig();
    r.broker.reportAccountPosition(1); // the study's own position reads 0
    String line = r.reconcile(1, -10, 20);
    check("an opening intent over a foreign account position is skipped", line.contains("reconcile_live_skipped")
        && line.contains("account position is 1"));
    check("...nothing submitted, nothing in flight", r.broker.allOrders().isEmpty() && !r.tracker.orderInFlight());
    check("...and the refusal is reported to the pipeline (B3)", r.tracker.lastEntryRefusal() != null
        && r.tracker.lastEntryRefusal().contains("account holds 1"));
    r.broker.reportAccountPosition(null);
    r.reconcile(1, -10, 20);
    check("with the account flat the same intent goes through", r.tracker.orderInFlight());
  }

  private static void testRefuseToArmSeesTheAccountPosition() {
    FakeBroker b = new FakeBroker();
    OrderGateway gw = new OrderGateway(b.ctx());
    check("flat: clear to arm", gw.refuseToArmReason() == null);
    b.reportAccountPosition(2); // the study reads 0, the account holds 2
    String why = gw.refuseToArmReason();
    check("an account position the study does not see refuses to arm", why != null && why.contains("accountPosition=2"));
  }

  private static void testResetForReactivationForgetsEverything() {
    Rig r = new Rig();
    r.openLong();
    r.tracker.resetForReactivation();
    check("legs forgotten", r.tracker.restingStop() == null && r.tracker.restingTarget() == null);
    Rig f = new Rig();
    f.reconcile(1, -10, 20);
    f.tracker.resetForReactivation();
    check("an in-flight entry is forgotten (it would block every later entry)", !f.tracker.orderInFlight());
    f.broker.reject(f.entry()); // the stale entry is gone from the account (re-activation only arms over a clean account, D-24)
    f.reconcile(1, -10, 20);
    check("...so a new entry can be submitted after re-activation", f.tracker.orderInFlight());
  }

  private static void testAfterFillHookAndAccountTruth() {
    Rig r = new Rig();
    r.broker.setCash(98_000.0);
    r.openLong(); // fills at 4300.0 == the codec anchor
    checkEq("the after-fill hook ran for the entry fill", r.afterFillCalls, 1);
    com.flow.core.RiskChain.AccountTruth t = r.gw.accountTruth(r.codec, 100_000.0);
    check("truth: cash, session start, position, dollars per tick", t != null && t.cash() == 98_000.0
        && t.startCash() == 100_000.0 && t.position() == 1 && Math.abs(t.dollarsPerTick() - 10.0) < 1e-9);
    checkEq("truth: the average entry (4300.0) in ticks from the anchor", t.avgEntryTicks(), 0);
    PriceCodec fresh = new PriceCodec(0.1); // no price seen yet -> no anchor
    check("no anchor: the entry is left null rather than fixing the anchor from it",
        r.gw.accountTruth(fresh, 100_000.0).avgEntryTicks() == null && !fresh.hasAnchor());
    Rig flat = new Rig();
    checkEq("flat account: no entry", flat.gw.accountTruth(flat.codec, 1.0).avgEntryTicks(), null);
    r.tracker.setAfterFillHook(() -> { throw new IllegalStateException("hook exploded"); });
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    r.broker.fill(stop, 4299.0f, 1_790_000_060_000L);
    r.tracker.onOrderFilled(stop.order());
    check("a throwing after-fill hook is logged and never breaks fill handling", r.loggedContaining("AFTER_FILL_HOOK_FAILED")
        && r.tracker.restingStop() == null);
  }

  // ---- C1 (2026-09-29, D-120): real fills published as FillEvents --------------------------------------------

  private static void testEntryStopTargetFillsArePublished() {
    Rig r = new Rig();
    r.openLong(); // entry fills at the anchor (tick 0), stop -10, target +20
    checkEq("one FillEvent for the entry", r.publishedFills.size(), 1);
    FillEvent entryFe = r.publishedFills.get(0);
    check("entry: role, side, price, qty, position after", entryFe.role() == FillEvent.Role.ENTRY && entryFe.isBuy()
        && entryFe.fillPriceTicks() == 0 && entryFe.quantity() == 1 && entryFe.positionAfter() == 1);

    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    r.broker.fill(stop, 4299.0f, 1_790_000_060_000L); // -10 ticks from the 4300.0 anchor
    r.tracker.onOrderFilled(stop.order());
    checkEq("a second FillEvent for the stop", r.publishedFills.size(), 2);
    FillEvent stopFe = r.publishedFills.get(1);
    check("stop: role STOP, closing a long is a SELL, price -10, position after 0",
        stopFe.role() == FillEvent.Role.STOP && !stopFe.isBuy() && stopFe.fillPriceTicks() == -10 && stopFe.positionAfter() == 0);
    check("the event time is the order's own fill time, not 'now'", stopFe.eventTimeMs() == 1_790_000_060_000L);

    Rig t = new Rig();
    t.openLong();
    FakeBroker.FakeOrder target = t.broker.allOrders().get(2);
    t.broker.fill(target, 4302.0f, 1_790_000_090_000L);
    t.tracker.onOrderFilled(target.order());
    FillEvent targetFe = t.publishedFills.get(t.publishedFills.size() - 1);
    check("target: role TARGET", targetFe.role() == FillEvent.Role.TARGET && targetFe.fillPriceTicks() == 20);
  }

  private static void testUntrackedFillIsNotPublished() {
    Rig r = new Rig();
    r.openLong();
    r.tracker.flattenForSessionEnd("session-end flatten window"); // forgets the tracker's own legs
    FakeBroker.FakeOrder platformClose = r.broker.restingOrder("MARKET", "SELL", 1, 4301f);
    r.broker.fill(platformClose, 4301.0f, 1_790_000_120_000L);
    int before = r.publishedFills.size();
    r.tracker.onOrderFilled(platformClose.order());
    checkEq("an untracked fill (not one of ours) is never published to the strategy", r.publishedFills.size(), before);
  }

  /** No setPublishFill() call at all (dry-run / replay / a test that doesn't care) must never throw. */
  private static void testFillPublishingIsOptional() {
    FakeBroker broker = new FakeBroker();
    OrderGateway gw = new OrderGateway(broker.ctx());
    PriceCodec codec = new PriceCodec(0.1);
    codec.toTicks(4300.0);
    LiveOrderTracker tracker = new LiveOrderTracker(() -> gw, () -> codec, l -> {}, r -> {}, () -> {});
    tracker.reconcileLive(new Intent("s", 1, 1, -10, 20, "test"), gw);
    broker.fill(broker.allOrders().get(0));
    tracker.onOrderFilled(broker.allOrders().get(0).order()); // no exception with no publisher wired
    check("nothing threw with no publisher configured", broker.allOrders().size() == 3);
  }

  // ---- F-5 / B6 (user: "flatten + disarm") ----------------------------------------------------------------------

  /** A stop leg cancelled (or rejected) by the platform with the position still open: after the settle, flatten + disarm. */
  private static void testLostLegFlattensAndDisarms() {
    for (String how : new String[] {"cancelled", "rejected"}) {
      Rig r = new Rig();
      r.openLong();
      FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
      if (how.equals("cancelled")) {
        stop.cancelled = true;
        r.tracker.onOrderCancelled(stop.order());
      } else {
        r.broker.reject(stop);
        r.tracker.onOrderRejected(stop.order());
      }
      check("[" + how + "] nothing is decided when the callback arrives -- only a confirmation is queued",
          r.scheduled.size() == 1 && !r.armDenied && !r.broker.called("closeAtMarket"));
      check("[" + how + "] the sighting is logged", r.loggedContaining("LEG_ENDED_SEEN leg=stop " + how));
      r.runNextVerification();
      check("[" + how + "] confirmed lost: disarmed", r.armDenied);
      check("[" + how + "] the ALERT line names the leg", r.loggedContaining("LIVE_LEG_LOST leg=stop " + how));
      check("[" + how + "] the position is closed", r.broker.called("closeAtMarket positionBefore=1") && r.broker.position() == 0);
      check("[" + how + "] the surviving target leg is swept", r.broker.activeOrders().isEmpty());
      check("[" + how + "] the tracker forgot the bracket", r.tracker.restingStop() == null && r.tracker.restingTarget() == null);
      check("[" + how + "] a verification of the flatten is queued", r.scheduled.size() == 1);
      r.runNextVerification();
      check("[" + how + "] ...and finds the account flat", r.loggedContaining("FLATTEN_VERIFIED LEG_LOST"));
    }
  }

  /**
   * L-7: when a leg fills the PLATFORM cancels the sibling itself, and that cancel callback arrives BEFORE our fill
   * callback. It must not be read as a lost leg -- and above all must not send a close to a flat account.
   */
  private static void testPlatformSiblingCancelIsBenign() {
    Rig r = new Rig();
    r.openLong();
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    FakeBroker.FakeOrder target = r.broker.allOrders().get(2);
    r.broker.fill(target, 4302.0f, 1_790_000_100_000L); // the target filled at the platform (account flat) ...
    stop.cancelled = true;                               // ... and the platform cancelled the stop itself
    r.tracker.onOrderCancelled(stop.order());            // that callback comes first
    r.tracker.onOrderFilled(target.order());             // our fill callback second
    r.broker.reportStrategyPosition(1);                  // worst case: both position reads still lag (L-1/L-2) when the check fires
    r.broker.reportAccountPosition(1);
    r.runNextVerification();
    check("the platform's own sibling cancel is benign", r.loggedContaining("LEG_ENDED_BENIGN") && !r.armDenied);
    check("nothing was sent", !r.broker.called("closeAtMarket"));

    // Same, but our fill callback has not run yet when the check fires: the filled leg's own state is enough.
    Rig q = new Rig();
    q.openLong();
    FakeBroker.FakeOrder qs = q.broker.allOrders().get(1);
    FakeBroker.FakeOrder qt = q.broker.allOrders().get(2);
    q.broker.fill(qt, 4302.0f, 1_790_000_100_000L);
    qs.cancelled = true;
    q.tracker.onOrderCancelled(qs.order());
    q.broker.reportStrategyPosition(1); // ... and both position reads still lag
    q.broker.reportAccountPosition(1);
    q.runNextVerification(); // the fill callback never ran
    check("even before the fill callback: the other leg is filled -> benign, no close", q.loggedContaining("LEG_ENDED_BENIGN")
        && !q.armDenied && !q.broker.called("closeAtMarket"));

    // Flat everywhere (e.g. a manual flatten): nothing left to protect.
    Rig f = new Rig();
    f.openLong();
    FakeBroker.FakeOrder fs = f.broker.allOrders().get(1);
    fs.cancelled = true;
    f.broker.setPosition(0);
    f.tracker.onOrderCancelled(fs.order());
    f.runNextVerification();
    check("account and study flat: benign", f.loggedContaining("LEG_ENDED_BENIGN") && !f.armDenied);
  }

  private static void testLostLegWithDisagreeingPositionsSendsNoClose() {
    Rig r = new Rig();
    r.openLong();
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    stop.cancelled = true;
    r.broker.reportAccountPosition(0); // the account says flat, the study says long
    r.tracker.onOrderCancelled(stop.order());
    r.runNextVerification();
    check("disarmed either way", r.armDenied);
    check("readings disagree: no close (it would open a short if the account is really flat)", !r.broker.called("closeAtMarket"));
    check("the remaining target leg is left working", r.broker.activeOrders().size() == 1);
    check("logged", r.loggedContaining("LEG_LOST_FLATTEN no close sent") && r.loggedContaining("POSITION_SOURCES_DISAGREE LEG_LOST"));
  }

  // ---- F-7 / F-11 / F-21: bracket anchored to the fill, cost record, wrong-side legs -----------------------------

  private static LiveOrderTracker.SignalInfo signal(int last, int bid, int ask) {
    return new LiveOrderTracker.SignalInfo(last, bid, ask, 1_790_000_000_000L, System.currentTimeMillis());
  }

  private static boolean near(float a, float b) { return Math.abs(a - b) < 1e-3f; }

  /** The strategy asked for 5 ticks either side of the signal (10); the market order filled at 12 -> 7 / 17. */
  private static void testBracketAnchoredToTheFill() {
    Rig r = new Rig();
    r.broker.setMarketPrice(4301.2f); // tick 12
    r.tracker.reconcileLive(new Intent("s", ++r.seq, 1, 5, 15, "test", true), r.gw, signal(10, 9, 11));
    r.broker.fill(r.entry());
    r.tracker.onOrderFilled(r.entry().order());
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    FakeBroker.FakeOrder target = r.broker.allOrders().get(2);
    check("anchored: stop is 5 ticks below the FILL (4300.7), not below the signal (4300.5)", near(stop.price, 4300.7f));
    check("anchored: target is 5 ticks above the FILL (4301.7)", near(target.price, 4301.7f));
    check("the re-anchoring is logged", r.loggedContaining("LIVE_BRACKET_ANCHORED_TO_FILL signal=10 fill=12 stop 5->7 target 15->17"));

    Rig s = new Rig();
    s.broker.setMarketPrice(4300.8f); // tick 8: a SHORT filled 2 ticks below the signal (10)
    s.tracker.reconcileLive(new Intent("s", ++s.seq, -1, 15, 5, "test", true), s.gw, signal(10, 9, 11));
    s.broker.fill(s.entry());
    s.tracker.onOrderFilled(s.entry().order());
    check("short: stop 5 above the fill (13 -> 4301.3), target 5 below (3 -> 4300.3)",
        near(s.broker.allOrders().get(1).price, 4301.3f) && near(s.broker.allOrders().get(2).price, 4300.3f));

    Rig n = new Rig();
    n.broker.setMarketPrice(4301.2f);
    n.tracker.reconcileLive(new Intent("s", ++n.seq, 1, 5, 15, "test", true), n.gw, null);
    n.broker.fill(n.entry());
    n.tracker.onOrderFilled(n.entry().order());
    check("no signal info -> falls back to the absolute levels (5 / 15 -> 4300.5 / 4301.5)",
        near(n.broker.allOrders().get(1).price, 4300.5f) && near(n.broker.allOrders().get(2).price, 4301.5f));
  }

  private static void testAbsoluteBracketUnchangedAndWrongSideLegsAreClamped() {
    Rig a = new Rig();
    a.broker.setMarketPrice(4301.2f);
    a.tracker.reconcileLive(new Intent("s", ++a.seq, 1, 5, 15, "test", false), a.gw, signal(10, 9, 11));
    a.broker.fill(a.entry());
    a.tracker.onOrderFilled(a.entry().order());
    check("anchorToFill=false: the strategy's absolute levels are used exactly as given",
        near(a.broker.allOrders().get(1).price, 4300.5f) && near(a.broker.allOrders().get(2).price, 4301.5f)
            && !a.loggedContaining("LIVE_BRACKET_ANCHORED_TO_FILL"));

    // F-21 (the 21:53 case): a long filled at tick 12 whose target sits at 11 would be marketable at once.
    Rig w = new Rig();
    w.broker.setMarketPrice(4301.2f);
    w.tracker.reconcileLive(new Intent("s", ++w.seq, 1, 6, 11, "test", false), w.gw, signal(10, 9, 11));
    w.broker.fill(w.entry());
    w.tracker.onOrderFilled(w.entry().order());
    check("a long's target at/below its fill is clamped one tick above it (13 -> 4301.3)", near(w.broker.allOrders().get(2).price, 4301.3f));
    check("...the adjustment is logged", w.loggedContaining("LIVE_BRACKET_ADJUSTED fill=12"));
    check("...and the bracket was still placed (never left unprotected)", w.broker.allOrders().size() == 3);

    Rig ws = new Rig();
    ws.broker.setMarketPrice(4301.2f);
    ws.tracker.reconcileLive(new Intent("s", ++ws.seq, -1, 12, 8, "test", false), ws.gw, signal(10, 9, 11));
    ws.broker.fill(ws.entry());
    ws.tracker.onOrderFilled(ws.entry().order());
    check("a short's stop at/below its fill is clamped one tick above it (13 -> 4301.3)", near(ws.broker.allOrders().get(1).price, 4301.3f));
    check("a normal target on the right side is untouched (8 -> 4300.8)", near(ws.broker.allOrders().get(2).price, 4300.8f));
  }

  private static void testEntryExecutionCostRecord() {
    Rig r = new Rig();
    r.broker.setMarketPrice(4301.2f); // fill 12, signal 10 (bid 9 / ask 11): a BUY
    String line = r.tracker.reconcileLive(new Intent("s", ++r.seq, 1, 5, 15, "test", true), r.gw, signal(10, 9, 11));
    check("real_order_submitted now carries its own time and the signal tick", line.contains("\"t\":")
        && line.contains("\"signalPriceTicks\":10") && line.contains("\"signalBidTicks\":9") && line.contains("\"signalAskTicks\":11"));
    r.broker.fill(r.entry());
    r.tracker.onOrderFilled(r.entry().order());
    checkEq("one entry_execution record", r.executions.size(), 1);
    String e = r.executions.get(0);
    check("fill 12 vs signal 10 = +2 ticks worse", e.contains("\"fillPriceTicks\":12") && e.contains("\"slippageVsLastTicks\":2"));
    check("vs the ask (11) = +1 tick worse, spread 2", e.contains("\"slippageVsTouchTicks\":1") && e.contains("\"spreadTicks\":2"));
    check("submit -> fill and signal -> submit times are journaled", e.contains("\"submitToFillMs\":") && e.contains("\"signalToSubmitMs\":"));
    check("side and order id present", e.contains("\"side\":\"BUY\"") && e.contains("\"orderId\":"));

    Rig s = new Rig();
    s.broker.setMarketPrice(4300.7f); // tick 7: a SELL filled 2 below the signal bid (9)... better than the touch? no: 9-7 = 2 worse
    s.tracker.reconcileLive(new Intent("s", ++s.seq, -1, 15, 5, "test", true), s.gw, signal(10, 9, 11));
    s.broker.fill(s.entry());
    s.tracker.onOrderFilled(s.entry().order());
    String se = s.executions.get(0);
    check("a SELL is measured the other way: signal 10 -> fill 7 = +3 worse; vs bid 9 = +2 worse",
        se.contains("\"slippageVsLastTicks\":3") && se.contains("\"slippageVsTouchTicks\":2") && se.contains("\"side\":\"SELL\""));
    Rig better = new Rig();
    better.broker.setMarketPrice(4300.8f); // a BUY filled at 8 vs ask 11: 3 ticks BETTER than the touch
    better.tracker.reconcileLive(new Intent("s", ++better.seq, 1, 5, 15, "test", true), better.gw, signal(10, 9, 11));
    better.broker.fill(better.entry());
    better.tracker.onOrderFilled(better.entry().order());
    check("price improvement is negative slippage", better.executions.get(0).contains("\"slippageVsTouchTicks\":-3"));
  }

  // ---- F-23 (2026-09-29, D-122): entry slippage cap, off by default -------------------------------------------

  private static void testEntrySlippageCapOffByDefault() {
    Rig r = new Rig(); // entrySlippageCapTicks defaults to 0
    String line = r.reconcile(1, -10, 20); // reconcile() carries no signal at all
    check("cap off (0): still a plain MARKET entry, exactly as before", line.contains("\"orderType\":\"MARKET\""));
    checkEq("a market order, not a limit", r.entry().type, "MARKET");

    Rig withSignal = new Rig();
    withSignal.tracker.setEntrySlippageCapTicks(0);
    withSignal.tracker.reconcileLive(new Intent("s", ++withSignal.seq, 1, -10, 20, "test"), withSignal.gw, signal(10, 9, 11));
    check("cap off even WITH a signal carrying a touch: still MARKET", withSignal.entry().type.equals("MARKET"));
  }

  private static void testEntrySlippageCapSubmitsACappedLimit() {
    Rig r = new Rig();
    r.tracker.setEntrySlippageCapTicks(3);
    String line = r.tracker.reconcileLive(new Intent("s", ++r.seq, 1, -10, 20, "test"), r.gw, signal(10, 9, 11)); // BUY, ask 11
    check("cap on, a BUY: a LIMIT order, not MARKET", line.contains("\"orderType\":\"LIMIT\""));
    check("...and it still carries the signal/time fields", line.contains("\"signalAskTicks\":11") && line.contains("\"t\":"));
    checkEq("a limit order, at ask(11) + cap(3) = 14 ticks -> 4301.4", r.entry().type, "LIMIT");
    check("...price 4301.4", near(r.entry().price, 4301.4f));
    check("still marks an entry in flight, same as a market entry would", r.tracker.orderInFlight());

    Rig s = new Rig();
    s.tracker.setEntrySlippageCapTicks(3);
    s.tracker.reconcileLive(new Intent("s", ++s.seq, -1, 10, -20, "test"), s.gw, signal(10, 9, 11)); // SELL, bid 9
    check("a SELL: at bid(9) - cap(3) = 6 ticks -> 4300.6", near(s.entry().price, 4300.6f));
  }

  private static void testEntrySlippageCapFillsNormallyLikeAnyOtherEntry() {
    Rig r = new Rig();
    r.tracker.setEntrySlippageCapTicks(3);
    r.tracker.reconcileLive(new Intent("s", ++r.seq, 1, -10, 20, "test", true), r.gw, signal(10, 9, 11));
    r.broker.fill(r.entry()); // a LIMIT fills at its own price by default (FakeBroker)
    r.tracker.onOrderFilled(r.entry().order());
    check("the entry filled: in-flight cleared, a bracket was placed from the fill (F-7)", !r.tracker.orderInFlight()
        && r.broker.allOrders().size() == 3 && r.loggedContaining("LIVE_BRACKET_SUBMITTED"));
    checkEq("an entry_execution record was still written", r.executions.size(), 1);
  }

  private static void testEntrySlippageCapGivesUpOnTimeout() {
    Rig r = new Rig();
    r.tracker.setEntrySlippageCapTicks(3);
    r.tracker.reconcileLive(new Intent("s", ++r.seq, 1, -10, 20, "test"), r.gw, signal(10, 9, 11));
    checkEq("a timeout is scheduled", r.scheduledDelays.get(r.scheduledDelays.size() - 1), LiveOrderTracker.ENTRY_LIMIT_TIMEOUT_MS);
    check("nothing filled yet: still in flight, nothing to cancel logged yet", r.tracker.orderInFlight() && !r.loggedContaining("ENTRY_MISSED"));

    r.runNextVerification(); // the timeout fires
    check("the unfilled limit is cancelled and given up on", r.broker.called("cancelOrders") && r.loggedContaining("ENTRY_MISSED"));
    checkEq("one entry_missed record", r.records.stream().filter(l -> l.contains("\"type\":\"entry_missed\"")).count(), 1L);
    check("in-flight cleared -- a fresh entry can be tried", !r.tracker.orderInFlight());

    r.reconcile(1, -10, 20);
    check("...and it goes through as an ordinary market order (cap needs a signal each time)", r.tracker.orderInFlight());

    // The late cancel callback for the timed-out entry must not be read as an anomaly (it is self-cancelled).
    boolean armDeniedBefore = r.armDenied;
    r.tracker.onOrderCancelled(r.broker.allOrders().get(0).order());
    check("the late callback for the TIMED-OUT entry does not disarm", r.armDenied == armDeniedBefore);
  }

  private static void testEntrySlippageCapTimeoutIsANoOpIfAlreadyResolved() {
    Rig filled = new Rig();
    filled.tracker.setEntrySlippageCapTicks(3);
    filled.tracker.reconcileLive(new Intent("s", ++filled.seq, 1, -10, 20, "test", true), filled.gw, signal(10, 9, 11));
    filled.broker.fill(filled.entry());
    filled.tracker.onOrderFilled(filled.entry().order());
    int callsBefore = filled.broker.calls().size();
    filled.runNextVerification(); // the timeout fires AFTER the entry already filled
    checkEq("already filled: the timeout does nothing more", filled.broker.calls().size(), callsBefore);
    check("...no bogus entry_missed", !filled.loggedContaining("ENTRY_MISSED"));

    Rig superseded = new Rig();
    superseded.tracker.setEntrySlippageCapTicks(3);
    superseded.tracker.reconcileLive(new Intent("s", ++superseded.seq, 1, -10, 20, "test"), superseded.gw, signal(10, 9, 11));
    check("setup: a timeout is queued", superseded.scheduled.size() == 1);
    superseded.broker.reject(superseded.entry()); // the first entry never worked out, some other way
    superseded.tracker.onOrderRejected(superseded.entry().order());
    check("in-flight cleared by the rejection", !superseded.tracker.orderInFlight());
    superseded.reconcile(1, -10, 20); // a fresh entry starts (a plain market order this time, no signal)
    int callsBefore2 = superseded.broker.calls().size();
    superseded.runNextVerification(); // the FIRST entry's stale timeout fires now
    checkEq("a stale timeout for a superseded entry changes nothing", superseded.broker.calls().size(), callsBefore2);
  }

  private static void testEntrySlippageCapNeedsATouchToCapFrom() {
    Rig noSignal = new Rig();
    noSignal.tracker.setEntrySlippageCapTicks(3);
    noSignal.reconcile(1, -10, 20); // no SignalInfo at all
    check("cap on but no signal: falls back to MARKET", noSignal.entry().type.equals("MARKET"));

    Rig noTouch = new Rig();
    noTouch.tracker.setEntrySlippageCapTicks(3);
    noTouch.tracker.reconcileLive(new Intent("s", ++noTouch.seq, 1, -10, 20, "test"), noTouch.gw,
        new LiveOrderTracker.SignalInfo(10, null, null, 1_790_000_000_000L, System.currentTimeMillis()));
    check("a signal with no bid/ask: also falls back to MARKET", noTouch.entry().type.equals("MARKET"));
  }

  // ---- F-19: the feed came back -------------------------------------------------------------------------------

  /** openLong: long 1 with stop 4299.0 (-10 ticks) and target 4302.0 (+20 ticks). */
  private static Rig feedRig(boolean shortSide) {
    Rig r = new Rig();
    if (shortSide) {
      r.reconcile(-1, 10, -20); // short: stop (BUY) 4301.0, target (BUY limit) 4298.0
      r.broker.fill(r.entry());
      r.tracker.onOrderFilled(r.entry().order());
    } else {
      r.openLong();
    }
    return r;
  }

  private static void resumeWith(Rig r, Integer first, Integer now) {
    r.tracker.onFeedResumed(0L, 1_000L, first, () -> now);
    r.runNextVerification();
  }

  private static void testFeedResumeFlattensOnlyWhenALevelWasReached() {
    Rig keep = feedRig(false);
    resumeWith(keep, 5, 6);
    check("long, price still between stop and target: orders left working", !keep.broker.called("closeAtMarket")
        && keep.broker.activeOrders().size() == 2 && keep.loggedContaining("FEED_RESUME_KEEP"));

    Rig tgt = feedRig(false);
    resumeWith(tgt, 25, 25);
    check("long, price jumped through the target (4302.5): flatten", tgt.broker.called("closeAtMarket positionBefore=1")
        && tgt.loggedContaining("FEED_RESUME_FLATTEN target"));
    check("...legs swept, account flat, and a verification queued", tgt.broker.activeOrders().isEmpty()
        && tgt.broker.position() == 0 && tgt.scheduled.size() == 1);

    Rig reach = feedRig(false);
    resumeWith(reach, 20, 20); // exactly the target 4302.0: REACHED counts
    check("price exactly AT the target counts as reached: flatten", reach.broker.called("closeAtMarket"));

    Rig stop = feedRig(false);
    resumeWith(stop, -12, -12);
    check("long, price gapped below the stop: flatten", stop.broker.called("closeAtMarket") && stop.loggedContaining("FEED_RESUME_FLATTEN stop"));

    Rig back = feedRig(false);
    resumeWith(back, 25, 5); // jumped through the target, then came back between the levels
    check("first price after the gap was beyond a level though it has since come back: flatten", back.broker.called("closeAtMarket"));

    Rig late = feedRig(false);
    resumeWith(late, 5, -10); // first tick between, but now at the stop
    check("first tick between, price now AT the stop: flatten", late.broker.called("closeAtMarket"));

    Rig sk = feedRig(true);
    resumeWith(sk, 0, 5);
    check("short, price between (4301.0 stop / 4298.0 target): keep", !sk.broker.called("closeAtMarket"));
    Rig ss = feedRig(true);
    resumeWith(ss, 11, 11); // 4301.1 >= the short's stop 4301.0
    check("short, price gapped above the stop: flatten", ss.broker.called("closeAtMarket positionBefore=-1"));
    Rig st = feedRig(true);
    resumeWith(st, -20, -20); // 4298.0 == the short's target
    check("short, price at the target: flatten", st.broker.called("closeAtMarket"));
  }

  private static void testFeedResumeOtherCases() {
    Rig done = feedRig(false);
    FakeBroker.FakeOrder target = done.broker.allOrders().get(2);
    done.broker.fill(target, 4302.0f, 1_790_000_100_000L); // the platform filled the target on the first tick
    done.tracker.onOrderFilled(target.order());
    resumeWith(done, 25, 25);
    check("the platform already resolved the position: nothing is sent", !done.broker.called("closeAtMarket")
        && done.loggedContaining("FEED_RESUME_NOTHING_OPEN"));

    Rig naked = feedRig(false);
    naked.tracker.resetForKillSwitch(); // the tracker has no legs but the account is still long
    resumeWith(naked, 25, 25);
    check("a position with no tracked bracket is ALERTED, not blindly closed", naked.loggedContaining("FEED_RESUME_UNPROTECTED_POSITION")
        && !naked.broker.called("closeAtMarket"));

    Rig split = feedRig(false);
    split.broker.reportAccountPosition(0); // the account says flat, the study says long
    resumeWith(split, 25, 25);
    check("crossed but the position readings disagree: no close (would open a short)", !split.broker.called("closeAtMarket")
        && split.loggedContaining("FEED_RESUME_FLATTEN_HELD"));

    Rig none = new Rig();
    resumeWith(none, 25, 25);
    check("nothing open at all: quiet", !none.broker.called("closeAtMarket") && none.loggedContaining("FEED_RESUME_NOTHING_OPEN"));

    Rig noPrices = feedRig(false);
    resumeWith(noPrices, null, null);
    check("no prices to judge by: orders left as they are, said so", !noPrices.broker.called("closeAtMarket")
        && noPrices.loggedContaining("FEED_RESUME_UNCHECKABLE"));
  }

  // ---- F-22 (2026-09-29, D-121): the historical bar range, once the quick check said KEEP -------------------------

  private static void testGapRangeCatchesASpikeAndReturnMissedByTheQuickCheck() {
    // Long 1 (openLong: stop -10 ticks = 4299.0, target +20 = 4302.0). first=5, now=6 -> the quick check says KEEP.
    // The bar range covering the gap shows the low dipped to -15 ticks (4298.5) -- past the stop -- and came back.
    Rig r = feedRig(false);
    r.tracker.setGapRangeLookup((start, end) -> new int[] {-15, 5});
    resumeWith(r, 5, 6);
    check("the quick check still says KEEP first", r.loggedContaining("FEED_RESUME_KEEP") && !r.broker.called("closeAtMarket"));
    checkEq("a gap-range check is queued", r.scheduled.size(), 1);
    checkEq("...5 s later", r.scheduledDelays.get(r.scheduledDelays.size() - 1), LiveOrderTracker.GAP_RANGE_CHECK_DELAY_MS);
    r.runNextVerification();
    check("the bar range shows the stop was reached during the outage: flattened after the fact",
        r.broker.called("closeAtMarket positionBefore=1") && r.loggedContaining("GAP_RANGE_FLATTEN stop"));
    check("...legs swept, account flat", r.broker.activeOrders().isEmpty() && r.broker.position() == 0);
    checkEq("a verification of this flatten is queued too", r.scheduled.size(), 1);
    r.runNextVerification();
    check("...and confirms flat", r.loggedContaining("FLATTEN_VERIFIED GAP_RANGE"));
  }

  private static void testGapRangeCatchesACrossOnlyVisibleAtTheHighEndOfTheRange() {
    // Long 1 (stop -10 / target +20). Range {0, 25}: the LOW (0) crosses nothing, only the HIGH (25) reaches the
    // target -- if only range[0] were ever checked, this would be missed.
    Rig r = feedRig(false);
    r.tracker.setGapRangeLookup((start, end) -> new int[] {0, 25});
    resumeWith(r, 5, 6);
    r.runNextVerification();
    check("the high end of the range alone reaches the target: flattened", r.broker.called("closeAtMarket")
        && r.loggedContaining("GAP_RANGE_FLATTEN target"));
  }

  private static void testGapRangeIgnoresAFlattenedAccountEvenWithLegsStillReferenced() {
    // The platform closed the account by itself (L-4's close dialog, extended to mid-outage) while the tracker's
    // own leg references are still non-null (no fill/cancel callback ever told it otherwise) -- both readings are
    // flat, so nothing must be sent even though stop/target are technically still "resting" by our own bookkeeping.
    Rig r = feedRig(false);
    r.tracker.setGapRangeLookup((start, end) -> new int[] {-15, 5});
    resumeWith(r, 5, 6);
    r.broker.setPosition(0);
    r.broker.reportAccountPosition(0);
    int callsBefore = r.broker.calls().size();
    r.runNextVerification();
    checkEq("both readings flat: no order sent even with legs still referenced", r.broker.calls().size(), callsBefore);
  }

  private static void testGapRangeOtherCases() {
    Rig noLookup = feedRig(false); // default: no lookup wired
    resumeWith(noLookup, 5, 6);
    checkEq("no lookup configured: nothing is scheduled for it", noLookup.scheduled.size(), 0);

    Rig unavailable = feedRig(false);
    unavailable.tracker.setGapRangeLookup((start, end) -> null); // bars not backfilled yet
    resumeWith(unavailable, 5, 6);
    unavailable.runNextVerification();
    check("bars not available: says so, sends nothing, no further schedule", unavailable.loggedContaining("GAP_RANGE_UNAVAILABLE")
        && !unavailable.broker.called("closeAtMarket") && unavailable.scheduled.isEmpty());

    Rig stillBetween = feedRig(false);
    stillBetween.tracker.setGapRangeLookup((start, end) -> new int[] {0, 15}); // the whole range stayed inside [-10, 20]
    resumeWith(stillBetween, 5, 6);
    stillBetween.runNextVerification();
    check("the range never reached a level: confirmed KEEP, nothing sent", stillBetween.loggedContaining("GAP_RANGE_KEEP")
        && !stillBetween.broker.called("closeAtMarket"));

    Rig resolved = feedRig(false);
    resolved.tracker.setGapRangeLookup((start, end) -> new int[] {-15, 5});
    resumeWith(resolved, 5, 6);
    // the position closed naturally (the platform's stop filled) BEFORE the 5 s mark -- the tracker forgot its legs.
    FakeBroker.FakeOrder stop = resolved.broker.allOrders().get(1);
    resolved.broker.fill(stop, 4299.0f, 1_790_000_200_000L);
    resolved.tracker.onOrderFilled(stop.order());
    int callsBefore = resolved.broker.calls().size();
    resolved.runNextVerification();
    checkEq("already resolved by then: the gap-range check is silent (no broker call)", resolved.broker.calls().size(), callsBefore);
    check("...cleanly, not via a caught exception", !resolved.loggedContaining("GAP_RANGE_CHECK_FAILED"));

    Rig disagree = feedRig(false);
    disagree.tracker.setGapRangeLookup((start, end) -> new int[] {-15, 5});
    resumeWith(disagree, 5, 6);
    disagree.broker.reportAccountPosition(0); // the readings now disagree by the time the range check runs
    disagree.runNextVerification();
    check("readings disagree at range-check time: no close, held", !disagree.broker.called("closeAtMarket")
        && disagree.loggedContaining("GAP_RANGE_FLATTEN_HELD"));
  }

  // ---- 2026-09-28: only the simulated account may affect anything -----------------------------------------------

  private static void testNonSimulatedAccountIsNeverActedOn() {
    Rig r = new Rig();
    r.openLong(); // on "simulated": normal
    check("precondition: a normal Sim trade left the system armed and unlocked", !r.armDenied && !r.gw.locked());
    FakeBroker.FakeOrder stop = r.broker.allOrders().get(1);
    int recordsBefore = r.records.size();
    r.broker.fill(stop, 4299.0f, 1_790_000_000_000L);
    r.broker.setAccountId("LFE025-R46U7LT7-TEST001"); // this fill is reported on some other account
    r.tracker.onOrderFilled(stop.order());
    check("a fill on another account disarms", r.armDenied);
    check("...locks all order sending", r.gw.locked());
    check("...is ALERTed with the account name", r.loggedContaining("NON_SIM_ACCOUNT_EVENT fill on account 'LFE025-R46U7LT7-TEST001'"));
    check("...is journaled as its own record, and NOT as an order_fill", r.records.size() == recordsBefore + 1
        && r.records.get(r.records.size() - 1).contains("\"type\":\"non_sim_account_event\""));
    check("...and was NOT processed: the tracker still holds its legs (no sibling handling)", r.tracker.restingStop() != null);
    int callsBefore = r.broker.calls().size();
    r.tracker.onKillSwitch("test", true);
    check("after the lock the kill switch sends NOTHING (no close, no cancel)", r.broker.calls().size() == callsBefore
        && !r.broker.called("closeAtMarket"));
    check("...and says the order was refused", r.loggedContaining("order_refused"));
    r.tracker.flattenForSessionEnd("x");
    r.tracker.verifyFlat("KILL_SWITCH", 0);
    check("neither the session flatten nor a post-flatten verification sends anything", r.broker.calls().size() == callsBefore);

    Rig k = new Rig();
    k.openLong();      // long 1 with both legs resting, nothing filled: the kill switch would normally close + sweep
    k.gw.lockOrders(); // ... but the lock is set
    int kCalls = k.broker.calls().size();
    k.tracker.onKillSwitch("test", true);
    check("locked + an open position: the kill switch's close-and-sweep is refused too", k.broker.calls().size() == kCalls
        && !k.broker.called("closeAtMarket") && k.loggedContaining("order_refused") && k.broker.position() == 1);

    Rig c = new Rig();
    c.openLong();
    FakeBroker.FakeOrder cs = c.broker.allOrders().get(1);
    cs.cancelled = true;
    c.broker.setAccountId("other");
    c.tracker.onOrderCancelled(cs.order());
    check("a cancel on another account is refused the same way (no leg-lost flatten, no ordinary handling)",
        c.armDenied && c.gw.locked() && c.scheduled.isEmpty());
    Rig j = new Rig();
    j.reconcile(1, -10, 20);
    j.broker.setAccountId("other");
    j.tracker.onOrderRejected(j.entry().order());
    check("a rejection on another account: disarmed + locked, and not treated as our entry's rejection",
        j.armDenied && j.gw.locked() && j.tracker.orderInFlight() && !j.loggedContaining("LIVE_ENTRY_REJECTED_DISARMING"));
  }

  private static void testEntryOnANonSimulatedAccountIsRefusedBeforeSending() {
    Rig r = new Rig();
    r.broker.setAccountId("LFE025-R46U7LT7-TEST001"); // the order the platform builds names a real account
    String line = r.reconcile(1, -10, 20);
    check("the entry is refused, said so", line.contains("reconcile_live_skipped") && line.contains("entry refused"));
    check("NOTHING was submitted to the platform", !r.broker.called("submitOrders") && r.broker.activeOrders().isEmpty());
    check("disarmed, locked, no entry left in flight", r.armDenied && r.gw.locked() && !r.tracker.orderInFlight());
    check("the pipeline is told (B3), and it is ALERTed", r.tracker.lastEntryRefusal() != null && r.loggedContaining("NON_SIM_ACCOUNT_EVENT entry refused"));
    r.broker.setAccountId("simulated");
    String again = r.reconcile(1, -10, 20);
    check("the lock does NOT clear itself when the account looks right again", again.contains("entry refused") && !r.broker.called("submitOrders"));
  }

  private static void testUnknownAccountIdIsAllowedButLogged() {
    Rig r = new Rig();
    r.broker.setAccountId(null);
    r.openLong();
    check("no account id reported: the trade goes through (fail-open, by design)", r.broker.allOrders().size() == 3 && !r.armDenied && !r.gw.locked());
    check("...but it is logged, once", r.loggedContaining("ACCOUNT_ID_UNKNOWN"));
    Rig u = new Rig();
    u.broker.setAccountId(" Simulated ");
    u.openLong();
    check("case and spaces do not matter: ' Simulated ' is the simulated account", !u.armDenied && !u.gw.locked() && u.broker.allOrders().size() == 3);
  }

  // ---- partial fills (plumbingEdgeCases.md section 9, D-99) ---------------
  //
  // The entry is resolved only when COMPLETELY filled. Which callback the real platform sends for a partial fill
  // (per slice, or only on completion) is unconfirmed, so both are scripted. Live today only maxContracts=1 makes
  // any of this unreachable. History: these checks first pinned the OLD behaviour (over-sized bracket, short 2 on a
  // cancelled remainder, a naked partial position) before the fix flipped them.

  private static void testPartialFills() {
    // A. Baseline: callback only on completion, whole 3-lot fill -> bracket for 3.
    Rig whole = new Rig();
    whole.reconcile(3, -10, 20);
    whole.broker.fill(whole.entry());
    whole.tracker.onOrderFilled(whole.entry().order());
    checkEq("[A] whole 3-lot fill: bracket stop is 3 lots", whole.broker.allOrders().get(1).qty, 3);
    checkEq("[A] whole 3-lot fill: bracket target is 3 lots", whole.broker.allOrders().get(2).qty, 3);
    checkEq("[A] position 3", whole.broker.position(), 3);
    check("[A] no partial-fill wait was logged", !whole.logged("LIVE_ENTRY_PARTIAL_FILL"));

    // B. Callback PER SLICE: the first slice (1 of 3) places NO bracket and leaves the entry in flight.
    Rig slice = new Rig();
    slice.reconcile(3, -10, 20);
    slice.broker.fillPartial(slice.entry(), 1, 4300f, 1_000L);
    slice.tracker.onOrderFilled(slice.entry().order());
    checkEq("[B] one lot held after the first slice", slice.broker.position(), 1);
    checkEq("[B] no bracket after a partial slice -- only the entry exists", slice.broker.allOrders().size(), 1);
    check("[B] entry is still in flight", slice.tracker.orderInFlight());
    check("[B] the wait was logged with the fill state", slice.logged("LIVE_ENTRY_PARTIAL_FILL filled=1 of 3"));
    check("[B] nothing disarmed", !slice.armDenied);
    check("[B] the partial slice was still journaled as an entry fill", slice.records.size() == 1
        && slice.records.get(0).contains("\"role\":\"entry\"") && slice.records.get(0).contains("\"filled\":1"));
    check("[B] a second intent is still skipped while waiting", slice.reconcile(-1, 10, -20).contains("still in flight"));

    // B2. A middle slice still waits; the completing slice brackets for exactly what was filled.
    slice.broker.fillPartial(slice.entry(), 1, 4300f, 2_000L);
    slice.tracker.onOrderFilled(slice.entry().order());
    checkEq("[B2] two of three: still no bracket", slice.broker.allOrders().size(), 1);
    slice.broker.fillPartial(slice.entry(), 1, 4300f, 3_000L);
    slice.tracker.onOrderFilled(slice.entry().order());
    checkEq("[B2] all three filled: entry + stop + target", slice.broker.allOrders().size(), 3);
    checkEq("[B2] stop is 3 lots", slice.broker.allOrders().get(1).qty, 3);
    checkEq("[B2] target is 3 lots", slice.broker.allOrders().get(2).qty, 3);
    check("[B2] entry no longer in flight", !slice.tracker.orderInFlight());
    check("[B2] legs tracked", slice.tracker.restingStop() != null && slice.tracker.restingTarget() != null);

    // C. Callback PER SLICE, then the remainder is cancelled: flatten the 1 held lot, disarm, no bracket ever.
    Rig cut = new Rig();
    cut.reconcile(3, -10, 20);
    cut.broker.fillPartial(cut.entry(), 1, 4300f, 1_000L);
    cut.tracker.onOrderFilled(cut.entry().order());
    cut.entry().cancelled = true; // the broker cancels the unfilled remainder
    cut.tracker.onOrderCancelled(cut.entry().order());
    check("[C] disarmed", cut.armDenied);
    check("[C] the held lot was flattened", cut.broker.called("closeAtMarket positionBefore=1"));
    checkEq("[C] account flat", cut.broker.position(), 0);
    checkEq("[C] no bracket was ever placed", cut.broker.allOrders().size(), 1);
    check("[C] nothing left resting", cut.broker.activeOrders().isEmpty());
    check("[C] not in flight", !cut.tracker.orderInFlight());
    check("[C] the flatten was logged", cut.logged("LIVE_ENTRY_PARTIAL_FLATTENED"));

    // D. Callback ONLY ON COMPLETION: 1 of 3 filled, then cancelled -- no fill callback ever came. Same outcome.
    Rig naked = new Rig();
    naked.reconcile(3, -10, 20);
    naked.broker.fillPartial(naked.entry(), 1, 4300f, 1_000L);
    naked.entry().cancelled = true;
    naked.tracker.onOrderCancelled(naked.entry().order());
    check("[D] disarmed", naked.armDenied);
    check("[D] the held lot was flattened", naked.broker.called("closeAtMarket positionBefore=1"));
    checkEq("[D] account flat", naked.broker.position(), 0);
    checkEq("[D] no bracket placed", naked.broker.allOrders().size(), 1);

    // E. Entry REJECTED after a partial fill: same treatment.
    Rig rej = new Rig();
    rej.reconcile(3, -10, 20);
    rej.broker.fillPartial(rej.entry(), 2, 4300f, 1_000L);
    rej.broker.reject(rej.entry());
    rej.tracker.onOrderRejected(rej.entry().order());
    check("[E] disarmed", rej.armDenied);
    check("[E] the 2 held lots were flattened", rej.broker.called("closeAtMarket positionBefore=2"));
    checkEq("[E] account flat", rej.broker.position(), 0);

    // F. A cancelled/rejected entry that filled NOTHING must not send a close to a flat account.
    Rig none = new Rig();
    none.reconcile(3, -10, 20);
    none.entry().cancelled = true;
    none.tracker.onOrderCancelled(none.entry().order());
    check("[F] disarmed", none.armDenied);
    check("[F] no close sent to a flat account", !none.broker.called("closeAtMarket"));
    check("[F] no flatten logged", !none.logged("LIVE_ENTRY_PARTIAL_FLATTENED"));

    // G. isFilled() lags (still false although getFilled() reached the quantity): the quantity signal must still
    // complete the entry, or a fully filled entry would sit with no bracket forever.
    Rig lag = new Rig();
    lag.reconcile(2, -10, 20);
    lag.entry().isFilledOverride = false;
    lag.broker.fill(lag.entry());
    check("[G] precondition: the SDK's isFilled() says false", !lag.entry().order().isFilled());
    lag.tracker.onOrderFilled(lag.entry().order());
    checkEq("[G] bracketed anyway -- entry + stop + target", lag.broker.allOrders().size(), 3);
    checkEq("[G] bracket sized 2", lag.broker.allOrders().get(1).qty, 2);

    // I. The SDK says isFilled() although only 1 of 3 is reported filled: trust it (a callback with isFilled true
    // is the platform's word that the entry is done) and bracket what is HELD, not what was requested.
    Rig ahead = new Rig();
    ahead.reconcile(3, -10, 20);
    ahead.broker.fillPartial(ahead.entry(), 1, 4300f, 1_000L);
    ahead.entry().isFilledOverride = true;
    ahead.tracker.onOrderFilled(ahead.entry().order());
    checkEq("[I] bracketed on the platform's isFilled()", ahead.broker.allOrders().size(), 3);
    checkEq("[I] stop sized from the 1 lot held, not the 3 requested", ahead.broker.allOrders().get(1).qty, 1);
    checkEq("[I] target sized from the 1 lot held", ahead.broker.allOrders().get(2).qty, 1);
    check("[I] the resize was logged", ahead.logged("LIVE_BRACKET_SIZE_FROM_FILL requested=3 filled=1"));

    // J. The account position lags the order (platform updates position after the callback): a cancelled entry
    // that reports a fill is still flattened, judged by the order's own filled quantity.
    Rig posLag = new Rig();
    posLag.reconcile(3, -10, 20);
    posLag.broker.fillPartial(posLag.entry(), 1, 4300f, 1_000L);
    posLag.broker.setPosition(0);
    posLag.entry().cancelled = true;
    posLag.tracker.onOrderCancelled(posLag.entry().order());
    check("[J] flatten attempted although the position still reads flat", posLag.broker.called("closeAtMarket"));

    // H. The 1-lot case that is live today is unchanged: fills whole, brackets 1.
    Rig one = new Rig();
    one.openLong();
    checkEq("[H] 1-lot stop", one.broker.allOrders().get(1).qty, 1);
    checkEq("[H] 1-lot target", one.broker.allOrders().get(2).qty, 1);
  }

  // ---- code review A1: the kill switch's account side ------------------------------------------------------------

  private static void testKillSwitchAccountSide() {
    // DRY_RUN (or SIM_LIVE with Armed off): never an order, even with a position and a bracket on the account.
    Rig dry = new Rig();
    dry.openLong();
    int callsBefore = dry.broker.calls().size();
    dry.tracker.onKillSwitch("daily loss limit breached", false);
    checkEq("A1: not live -> no order call of any kind", dry.broker.calls().size(), callsBefore);
    checkEq("A1: not live -> the position is left alone", dry.broker.position(), 1);
    check("A1: not live -> logged with the KILL_SWITCH_TRIGGERED prefix and 'no order sent'",
        dry.log.stream().anyMatch(l -> l.startsWith("KILL_SWITCH_TRIGGERED") && l.contains("no order sent")));
    check("A1: not live -> the tracker still forgets its legs", dry.tracker.restingStop() == null);

    // Live, position open -> close then cancel everything (the existing behaviour).
    Rig open = new Rig();
    open.openLong();
    open.tracker.onKillSwitch("daily loss limit breached", true);
    checkEq("A1: live with a position -> flat", open.broker.position(), 0);
    check("A1: live with a position -> closeAtMarket was sent", open.broker.called("closeAtMarket"));
    check("A1: live with a position -> nothing left resting", open.broker.activeOrders().isEmpty());

    // Live, flat but a stray order resting -> cancel only, never a close to a flat account.
    Rig stray = new Rig();
    stray.broker.restingOrder("LIMIT", "SELL", 1, 4310f);
    stray.tracker.onKillSwitch("daily loss limit breached", true);
    check("A1: live, flat + resting -> cancelled", stray.broker.activeOrders().isEmpty());
    check("A1: live, flat + resting -> no closeAtMarket to a flat account", !stray.broker.called("closeAtMarket"));

    // Live, flat and nothing resting -> nothing at all.
    Rig flat = new Rig();
    flat.tracker.onKillSwitch("daily loss limit breached", true);
    check("A1: live, flat, nothing resting -> no order call", flat.broker.calls().isEmpty());

    // Live but no gateway (study deactivated) -> no exception, nothing sent.
    Rig none = new Rig();
    none.openLong();
    none.gatewayAvailable = false;
    none.tracker.onKillSwitch("daily loss limit breached", true);
    checkEq("A1: no gateway -> the position is untouched, no exception", none.broker.position(), 1);
  }


  // ---- code review A9: bracket prices on the tick grid -----------------------------------------------------------

  private static void testBracketPricesAreSnappedToTheTickGrid() {
    FakeBroker broker = new FakeBroker();
    OrderGateway gw = new OrderGateway(broker.ctx());
    PriceCodec noisy = new PriceCodec(0.1);
    noisy.toTicks(4300.0004); // the anchor carries float noise, as the live SDK's does
    List<String> log = new ArrayList<>();
    LiveOrderTracker t = new LiveOrderTracker(() -> gw, () -> noisy, log::add, s -> {}, () -> {});
    t.reconcileLive(new Intent("s", 1, 1, -10, 20, "test"), gw);
    FakeBroker.FakeOrder entry = broker.allOrders().get(0);
    broker.fill(entry);
    t.onOrderFilled(entry.order());
    FakeBroker.FakeOrder stop = broker.allOrders().get(1);
    FakeBroker.FakeOrder target = broker.allOrders().get(2);
    check("A9 precondition: the un-snapped stop price would be off the grid", (float) noisy.fromTicks(-10) != 4299.0f);
    checkEq("A9: the stop is sent exactly on the tick grid", stop.price, 4299.0f);
    checkEq("A9: the target is sent exactly on the tick grid", target.price, 4302.0f);
  }


  // ---- code review B3: refusals reported back ------------------------------------------------------------------

  private static void testEntryRefusalsAreReported() {
    Rig ok = new Rig();
    ok.reconcile(1, -10, 20);
    checkEq("B3: an entry that was submitted is not a refusal", ok.tracker.lastEntryRefusal(), null);
    ok.reconcile(-1, 10, -20);
    check("B3: a second entry while one is in flight is a refusal",
        ok.tracker.lastEntryRefusal() != null && ok.tracker.lastEntryRefusal().contains("in flight"));

    Rig standDown = new Rig();
    standDown.reconcile(1, -10, 20);              // entry in flight, account still flat
    standDown.reconcile(0, null, null);           // the strategy stands down before the fill
    checkEq("B3: a flat intent while an entry is in flight is not a refusal (nothing to open)",
        standDown.tracker.lastEntryRefusal(), null);

    Rig resting = new Rig();
    resting.broker.restingOrder("LIMIT", "SELL", 1, 4310f);
    resting.reconcile(1, -10, 20);
    check("B3: resting orders blocking an entry is a refusal",
        resting.tracker.lastEntryRefusal() != null && resting.tracker.lastEntryRefusal().contains("resting"));

    Rig flip = new Rig();
    flip.broker.setPosition(1);
    flip.reconcile(-1, 10, -20);
    check("B3: a direct flip is a refusal", flip.tracker.lastEntryRefusal() != null);

    Rig close = new Rig();
    close.broker.setPosition(1);
    close.reconcile(0, null, null);
    checkEq("B3: a close left to the bracket is NOT a refusal (bracket-only by design)", close.tracker.lastEntryRefusal(), null);

    Rig noop = new Rig();
    noop.reconcile(0, null, null);
    checkEq("B3: nothing to do is not a refusal", noop.tracker.lastEntryRefusal(), null);

    Rig holding = new Rig();
    holding.openLong();
    holding.reconcile(1, -10, 20);
    checkEq("B3: 'holding' the position the account already has is not a refusal", holding.tracker.lastEntryRefusal(), null);

    Rig reset = new Rig();
    reset.reconcile(1, -10, 20);
    reset.reconcile(-1, 10, -20);                 // refusal
    reset.broker.fill(reset.entry());
    reset.tracker.onOrderFilled(reset.entry().order());
    reset.reconcile(1, -10, 20);                  // holding now
    checkEq("B3: the refusal is cleared on the next call", reset.tracker.lastEntryRefusal(), null);
  }

}
