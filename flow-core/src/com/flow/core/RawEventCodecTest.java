package com.flow.core;

import java.util.List;

/**
 * Encode/decode round trips for every Event subtype (D-15: the raw tier is the ONE thing replay reconstructs the
 * world from, so a field silently dropped between encode() and decode() is a correctness bug, not a style one).
 * FillEvent (C1, 2026-09-29, D-120) is the newest: raw.jsonl now records real fills going forward.
 */
public final class RawEventCodecTest {
  private static int failures = 0;

  private static void check(String label, boolean ok) {
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) {
    testFillRoundTrip();
    testTickAndClockStillRoundTrip();
    testUnknownRawTypeThrowsNotSilentlyNull();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all RawEventCodec checks passed.");
  }

  private static void testFillRoundTrip() {
    for (FillEvent.Role role : FillEvent.Role.values()) {
      for (boolean isBuy : new boolean[] {true, false}) {
        FillEvent f = new FillEvent(42, 1_790_000_000_000L, 1_790_000_000_050L, "SIM-123", role, isBuy, -17, 3, -3);
        String line = RawEventCodec.encode(f);
        check(role + "/" + isBuy + ": the line names its type and role", line.contains("\"type\":\"fill\"") && line.contains("\"role\":\"" + role + "\""));
        Event back = RawEventCodec.decode(line);
        check(role + "/" + isBuy + ": decodes back to an equal FillEvent", back.equals(f));
      }
    }
  }

  /** Not new behaviour -- guards against a refactor of encode()/decode() breaking an existing type while touching FillEvent's cases. */
  private static void testTickAndClockStillRoundTrip() {
    TickEvent t = new TickEvent(1, 100L, 105L, 50, 2, true, 49, 51, 7L, 8L);
    check("tick round trip", RawEventCodec.decode(RawEventCodec.encode(t)).equals(t));
    ClockEvent c = new ClockEvent(2, 200L, 200L);
    check("clock round trip", RawEventCodec.decode(RawEventCodec.encode(c)).equals(c));
    DomEvent d = new DomEvent(3, 300L, 300L, 10, 5.0, 11, 3.0, List.of(), List.of());
    Event dback = RawEventCodec.decode(RawEventCodec.encode(d));
    check("dom round trip (top-of-book only, by design -- D-58)", dback instanceof DomEvent de
        && de.bestBidTicks() == 10 && de.bestAskTicks() == 11 && de.bidRows().isEmpty() && de.askRows().isEmpty());
  }

  private static void testUnknownRawTypeThrowsNotSilentlyNull() {
    boolean threw = false;
    try {
      RawEventCodec.decode("{\"seq\":1,\"eventTimeMs\":1,\"receiptTimeMs\":1,\"type\":\"nonsense\"}");
    } catch (IllegalArgumentException e) {
      threw = true;
    }
    check("an unrecognized type throws rather than reading back wrong", threw);
    check("GAP_MARKER decodes to null (not an error)",
        RawEventCodec.decode("{\"seq\":1,\"eventTimeMs\":1,\"receiptTimeMs\":1,\"type\":\"GAP_MARKER\"}") == null);
  }
}
