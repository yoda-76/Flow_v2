package com.flow.core;

import com.flow.journal.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural test (D-11, todo.md section 3): record -> replay -> assert
 * the intent sequence is identical. Needs no SDK -- runs under a plain
 * JDK against a session directory a live FlowRuntimeStudy run already
 * produced (README "Testing": "flow-core compiles and runs under a plain
 * JDK with no platform present").
 *
 * Compares content only (strategyId/targetPosition/stopPriceTicks/
 * targetPriceTicks/reason), not Intent.seq() -- same reason Pipeline's
 * own change-detection ignores it (see D-41's bugfix note): seq is a
 * per-call identifier, not part of "is this the same decision."
 */
public final class ReplayEquivalenceTest {
  private record IntentSummary(
      String strategyId, int targetPosition, Integer stopPriceTicks, Integer targetPriceTicks, String reason) {}

  public static void main(String[] args) throws IOException {
    if (args.length < 1) {
      System.err.println("usage: ReplayEquivalenceTest <sessionDir>");
      System.exit(2);
      return;
    }
    Path sessionDir = Path.of(args[0]);
    Path decisionsFile = sessionDir.resolve("decisions.jsonl");
    if (!Files.exists(decisionsFile)) {
      System.err.println("FAIL: no decisions.jsonl at " + decisionsFile);
      System.exit(1);
      return;
    }

    String strategyId = null;
    List<IntentSummary> originalIntents = new ArrayList<>();
    for (String line : Files.readAllLines(decisionsFile)) {
      if (line.isBlank()) continue;
      JsonObject o = JsonObject.parse(line);
      String type = o.getString("type");
      if ("session_header".equals(type)) {
        strategyId = o.getString("strategyId");
      } else if ("intent_changed".equals(type)) {
        originalIntents.add(new IntentSummary(
            o.getString("strategyId"),
            o.getInt("targetPosition"),
            o.getIntOrNull("stopPriceTicks"),
            o.getIntOrNull("targetPriceTicks"),
            o.getString("reason")));
      }
    }
    if (strategyId == null) {
      System.err.println("FAIL: no session_header record found in " + decisionsFile + " -- cannot determine strategyId");
      System.exit(1);
      return;
    }

    System.out.println("Original session: strategyId=" + strategyId + ", "
        + originalIntents.size() + " intent_changed record(s).");
    System.out.println("NOTE (D-43): replaying with zero features -- any SDK-engine-backed "
        + "feature (e.g. SdkVolumeProfileFeature) cannot be reconstructed under a plain JDK. "
        + "If the original strategy depended on one, this replay is not equivalent.");

    ReplayHarness.Result result = ReplayHarness.replay(sessionDir, strategyId, java.util.Map.of());
    System.out.println("Replay: " + result.eventsReplayed() + " event(s) replayed, "
        + result.gapsSkipped() + " gap(s) skipped, " + result.intents().size() + " intent(s) produced.");
    if (result.gapsSkipped() > 0) {
      System.out.println("NOTE: " + result.gapsSkipped() + " gap(s) in the raw journal -- a mismatch "
          + "below is expected and attributable to the gap, not a replay bug.");
    }

    List<IntentSummary> replayedIntents = new ArrayList<>();
    for (Intent i : result.intents()) {
      replayedIntents.add(new IntentSummary(
          i.strategyId(), i.targetPosition(), i.stopPriceTicks(), i.targetPriceTicks(), i.reason()));
    }

    if (!originalIntents.equals(replayedIntents)) {
      System.out.println("MISMATCH:");
      System.out.println("  original: " + originalIntents);
      System.out.println("  replayed: " + replayedIntents);
      System.err.println("FAIL: replayed intent sequence does not match the original session's "
          + "recorded intent_changed records.");
      System.exit(1);
      return;
    }
    System.out.println("PASS: replayed intent sequence is identical to the original session's "
        + "recorded intent_changed records.");
  }
}
