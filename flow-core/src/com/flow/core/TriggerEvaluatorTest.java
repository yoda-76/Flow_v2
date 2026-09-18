package com.flow.core;

import com.flow.flow.LevelSource;
import com.flow.flow.ZoneSource;
import com.flow.flow.ZoneView;

import java.util.List;
import java.util.Map;

/**
 * Direct synthetic-event test for D-38's LevelCross/ZoneTransition logic
 * in TriggerEvaluator -- no MotiveWave involved (README "Testing":
 * "assert the intents you expect, before the platform is involved").
 * Plain main(), no JUnit on this classpath, same pattern as
 * ReplayEquivalenceTest.
 */
public final class TriggerEvaluatorTest {
  static final class FakeFeature implements Feature, LevelSource, ZoneSource {
    Integer poc;
    List<ZoneView> zones = List.of();

    @Override public String id() { return "vp"; }
    @Override public void onEvent(Event e) {}
    @Override public boolean isReady() { return true; }
    @Override public String notReadyReason() { return null; }
    @Override public Integer levelValue(String name) { return "POC".equals(name) ? poc : null; }
    @Override public List<ZoneView> zonesOfKind(ZoneView.Kind kind) {
      return zones.stream().filter(z -> z.kind() == kind).toList();
    }
  }

  private static int seq = 0;
  private static TickEvent tick(int priceTicks) {
    seq++;
    return new TickEvent(seq, seq * 1000L, seq * 1000L, priceTicks, 1, true, priceTicks, priceTicks, 0L, 0L);
  }

  private static int failures = 0;

  private static void check(String label, boolean got, boolean want) {
    if (got != want) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) {
    testLevelCross();
    testZoneEnterLeaveDebounce();
    testZoneJumpAndTouch();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all TriggerEvaluator synthetic checks passed.");
  }

  private static void testLevelCross() {
    FakeFeature f = new FakeFeature();
    f.poc = 100;
    TriggerEvaluator ev = new TriggerEvaluator(Map.of("vp", f));
    Trigger.LevelCross crossAbove = new Trigger.LevelCross("vp", "POC", Trigger.LevelCross.CrossKind.CROSS_ABOVE);
    Trigger.LevelCross crossBelow = new Trigger.LevelCross("vp", "POC", Trigger.LevelCross.CrossKind.CROSS_BELOW);
    Trigger.LevelCross touch = new Trigger.LevelCross("vp", "POC", Trigger.LevelCross.CrossKind.TOUCH);

    check("LevelCross: first observation (below) never wakes", ev.shouldWake(crossAbove, tick(90), 0), false);
    check("LevelCross: still below, no flip", ev.shouldWake(crossAbove, tick(95), 0), false);
    check("LevelCross: crosses above -> CROSS_ABOVE wakes", ev.shouldWake(crossAbove, tick(105), 0), true);
    check("LevelCross: still above, CROSS_BELOW doesn't wake on its own first obs",
        ev.shouldWake(crossBelow, tick(105), 0), false);
    check("LevelCross: drops below -> CROSS_BELOW wakes", ev.shouldWake(crossBelow, tick(95), 0), true);
    check("LevelCross: exact match -> TOUCH wakes", ev.shouldWake(touch, tick(100), 0), true);
    check("LevelCross: off by one -> TOUCH doesn't wake", ev.shouldWake(touch, tick(101), 0), false);

    f.poc = null;
    check("LevelCross: unknown level never wakes", ev.shouldWake(crossAbove, tick(105), 0), false);
  }

  /**
   * Both ENTER and LEAVE are evaluated at every step here, matching how
   * Pipeline actually drives TriggerEvaluator (every declared trigger
   * sees every event, never short-circuited -- see Pipeline.handle()'s
   * comment). Evaluating only one of a related pair, as an earlier
   * version of this test did, silently desyncs the other's state and
   * was caught by this exact test before it reached a live run.
   */
  private static void testZoneEnterLeaveDebounce() {
    FakeFeature f = new FakeFeature();
    ZoneView z1 = new ZoneView("z1", ZoneView.Kind.LVN, 10, 12, 0); // width = 3
    f.zones = List.of(z1);
    TriggerEvaluator ev = new TriggerEvaluator(Map.of("vp", f));
    Trigger.ZoneTransition enter = new Trigger.ZoneTransition("vp", ZoneView.Kind.LVN, Trigger.ZoneTransition.TransitionKind.ENTER);
    Trigger.ZoneTransition leave = new Trigger.ZoneTransition("vp", ZoneView.Kind.LVN, Trigger.ZoneTransition.TransitionKind.LEAVE);

    TickEvent e = tick(5);
    check("Zone: outside, ENTER doesn't wake", ev.shouldWake(enter, e, 0), false);
    check("Zone: outside, LEAVE doesn't wake (never entered)", ev.shouldWake(leave, e, 0), false);

    e = tick(11);
    check("Zone: enters range -> ENTER wakes", ev.shouldWake(enter, e, 0), true);
    check("Zone: entering, LEAVE doesn't fire", ev.shouldWake(leave, e, 0), false);

    e = tick(12);
    check("Zone: still inside -> ENTER doesn't re-fire", ev.shouldWake(enter, e, 0), false);
    check("Zone: still inside, LEAVE doesn't fire", ev.shouldWake(leave, e, 0), false);

    e = tick(13); // high=12, width=3, debounce margin extends to 15
    check("Zone: just past boundary, ENTER doesn't fire", ev.shouldWake(enter, e, 0), false);
    check("Zone: just past boundary (within debounce margin) -> LEAVE doesn't fire",
        ev.shouldWake(leave, e, 0), false);

    e = tick(15);
    check("Zone: still within margin, ENTER doesn't fire", ev.shouldWake(enter, e, 0), false);
    check("Zone: still within margin -> LEAVE doesn't fire", ev.shouldWake(leave, e, 0), false);

    e = tick(16);
    check("Zone: cleared margin, ENTER doesn't fire", ev.shouldWake(enter, e, 0), false);
    check("Zone: cleared the debounce margin -> LEAVE fires", ev.shouldWake(leave, e, 0), true);

    e = tick(20);
    check("Zone: already left, ENTER doesn't fire", ev.shouldWake(enter, e, 0), false);
    check("Zone: already left -> LEAVE doesn't re-fire", ev.shouldWake(leave, e, 0), false);

    // re-entry within the debounce margin should be seen as the same open trial
    TriggerEvaluator ev2 = new TriggerEvaluator(Map.of("vp", f));
    e = tick(11);
    check("Zone: enter", ev2.shouldWake(enter, e, 0), true);
    check("Zone: enter, LEAVE doesn't fire", ev2.shouldWake(leave, e, 0), false);
    e = tick(13);
    check("Zone: brief exit inside margin, ENTER doesn't fire", ev2.shouldWake(enter, e, 0), false);
    check("Zone: brief exit inside margin -> no LEAVE", ev2.shouldWake(leave, e, 0), false);
    e = tick(11);
    check("Zone: re-enter -> ENTER doesn't re-fire (same trial)", ev2.shouldWake(enter, e, 0), false);
    check("Zone: re-enter, LEAVE doesn't fire", ev2.shouldWake(leave, e, 0), false);
  }

  private static void testZoneJumpAndTouch() {
    FakeFeature f = new FakeFeature();
    ZoneView z1 = new ZoneView("z1", ZoneView.Kind.HVN, 10, 12, 0);
    ZoneView z2 = new ZoneView("z2", ZoneView.Kind.HVN, 20, 22, 0);
    f.zones = List.of(z1);
    TriggerEvaluator ev = new TriggerEvaluator(Map.of("vp", f));
    Trigger.ZoneTransition enter = new Trigger.ZoneTransition("vp", ZoneView.Kind.HVN, Trigger.ZoneTransition.TransitionKind.ENTER);
    Trigger.ZoneTransition touch = new Trigger.ZoneTransition("vp", ZoneView.Kind.HVN, Trigger.ZoneTransition.TransitionKind.TOUCH);

    check("Zone jump: enters z1", ev.shouldWake(enter, tick(11), 0), true);
    f.zones = List.of(z2); // z1 gone, z2 appeared -- a direct jump, no "outside" in between
    check("Zone jump: direct jump into a different zone still fires ENTER",
        ev.shouldWake(enter, tick(21), 0), true);

    check("Zone touch: exact low boundary wakes", ev.shouldWake(touch, tick(20), 0), true);
    check("Zone touch: exact high boundary wakes", ev.shouldWake(touch, tick(22), 0), true);
    check("Zone touch: interior (not boundary) doesn't wake", ev.shouldWake(touch, tick(21), 0), false);
  }
}
