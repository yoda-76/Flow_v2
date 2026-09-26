package com.flow.core;

import com.flow.journal.JournalWriter;
import com.flow.journal.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * D-97: the journal must say whether the system is armed and in what mode -- it used to store neither
 * (session_header.mode was a fixed "DRY_RUN"), so a reader could not tell an armed session from a dry run.
 * Pipeline now writes an `arming_state` record on the first observation and whenever the effective armed
 * flag or the runtime's arming detail changes, and puts `armed` + `runtime` on every heartbeat.
 *
 * Events are fed straight into Pipeline.handle() on this thread with hand-picked times, so it is
 * deterministic. ClockEvents drive it (one per ~100 ms in production).
 */
public final class ArmingStateTest {
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

  /** A permissive risk config: the arming journal only needs a risk chain to exist, not its limits. */
  private static ExternalConfig riskConfig() throws Exception {
    Path f = Files.createTempFile("flow_v2_test_arming", ".json");
    f.toFile().deleteOnExit();
    Files.writeString(f, "{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,\"rateLimitPerMinute\":1000,"
        + "\"minDwellMs\":0,\"maxReversalsPerSession\":1000,\"lagQueueDepthThreshold\":1000000,"
        + "\"lagProcessingMsThreshold\":1000000,\"flattenLeadMinutes\":0}");
    return ExternalConfig.load(f);
  }

  private static final class Rig {
    final Path dir;
    final JournalWriter journal;
    final AtomicBoolean armed = new AtomicBoolean(false);
    final AtomicReference<String> runtime = new AtomicReference<>("{\"mode\":\"DRY_RUN\",\"armedSetting\":false,\"armDenied\":false}");
    final Pipeline pipeline;
    long seq = 0;
    long clockMs = 1_790_000_000_000L;

    Rig(boolean withRiskChain, boolean attachRuntime) throws Exception {
      dir = Files.createTempDirectory("flow_v2_test_arming");
      dir.toFile().deleteOnExit();
      journal = new JournalWriter(dir);
      journal.start();
      FlowStrategy strategy = new com.flow.strategies.NullStrategy();
      if (withRiskChain) {
        pipeline = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null, new RiskChain(riskConfig()),
            armed::get, () -> 0, r -> {}, r -> {});
      } else {
        pipeline = new Pipeline(strategy, journal, (i, e) -> {}, Map.of(), null);
      }
      if (attachRuntime) pipeline.attachRuntimeStatus(runtime::get);
    }

    /** n clock events, 100 ms apart. */
    void tick(int n) {
      for (int i = 0; i < n; i++) {
        pipeline.handle(new ClockEvent(++seq, clockMs, clockMs));
        clockMs += 100;
      }
    }

    List<String> lines(String type) throws Exception {
      journal.flushAndClose();
      List<String> out = new ArrayList<>();
      for (String l : Files.readAllLines(dir.resolve("decisions.jsonl"))) {
        if (l.contains("\"type\":\"" + type + "\"")) out.add(l);
      }
      return out;
    }
  }

  public static void main(String[] args) throws Exception {
    testFirstObservationIsJournaled();
    testNothingIsWrittenWhileNothingChanges();
    testChangesAreJournaledOnce();
    testHeartbeatsCarryArmedAndRuntime();
    testASupplierThatThrowsCannotHurtThePipeline();
    testWithoutARuntimeSupplierTheFlagIsStillJournaled();
    testTheArmedFlagAloneIsEnoughToJournalAChange();
    testNoRiskChainKeepsTheOldShape();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all arming-state synthetic checks passed.");
  }

  private static void testFirstObservationIsJournaled() throws Exception {
    Rig r = new Rig(true, true);
    r.tick(1);
    List<String> a = r.lines("arming_state");
    checkEq("the very first clock event journals one arming_state", a.size(), 1);
    JsonObject o = JsonObject.parse(a.get(0));
    check("it says not armed", !o.getBoolean("armed"));
    check("and carries the event time", o.getLong("eventTimeMs") == 1_790_000_000_000L);
    check("and the runtime detail (mode, setting, denial)", a.get(0).contains("\"runtime\":{\"mode\":\"DRY_RUN\",\"armedSetting\":false,\"armDenied\":false}"));
  }

  private static void testNothingIsWrittenWhileNothingChanges() throws Exception {
    Rig r = new Rig(true, true);
    r.tick(500);   // 50 s of clock, nothing flipped
    checkEq("no further arming_state records", r.lines("arming_state").size(), 1);
  }

  private static void testChangesAreJournaledOnce() throws Exception {
    Rig r = new Rig(true, true);
    r.tick(25);
    r.armed.set(true);
    r.runtime.set("{\"mode\":\"SIM_LIVE\",\"armedSetting\":true,\"armDenied\":false}");
    r.tick(ArmingStateTestSupport.checkWindow());   // within one checking window the change is seen
    r.tick(50);                                       // ...and journaled exactly once
    // a denial (e.g. a position mismatch) flips the EFFECTIVE flag off while the user's setting stays on
    r.armed.set(false);
    r.runtime.set("{\"mode\":\"SIM_LIVE\",\"armedSetting\":true,\"armDenied\":true}");
    r.tick(ArmingStateTestSupport.checkWindow());
    // only the runtime detail changes (armed flag identical): still a change worth a record
    r.runtime.set("{\"mode\":\"SIM_LIVE\",\"armedSetting\":true,\"armDenied\":true,\"refusedAtActivation\":true}");
    r.tick(ArmingStateTestSupport.checkWindow());

    List<String> a = r.lines("arming_state");
    checkEq("initial + armed + denied + detail-only change = 4 records", a.size(), 4);
    check("second: armed true in SIM_LIVE", JsonObject.parse(a.get(1)).getBoolean("armed") && a.get(1).contains("\"mode\":\"SIM_LIVE\""));
    check("third: effective armed false, armDenied true", !JsonObject.parse(a.get(2)).getBoolean("armed") && a.get(2).contains("\"armDenied\":true"));
    check("fourth: same flag, new detail", a.get(3).contains("\"refusedAtActivation\":true"));
    check("event times are increasing", JsonObject.parse(a.get(1)).getLong("eventTimeMs") < JsonObject.parse(a.get(2)).getLong("eventTimeMs")
        && JsonObject.parse(a.get(2)).getLong("eventTimeMs") < JsonObject.parse(a.get(3)).getLong("eventTimeMs"));
  }

  private static void testHeartbeatsCarryArmedAndRuntime() throws Exception {
    Rig r = new Rig(true, true);
    r.tick(100);                     // first heartbeat (armed false)
    r.armed.set(true);
    r.runtime.set("{\"mode\":\"SIM_LIVE\",\"armedSetting\":true,\"armDenied\":false}");
    r.tick(100);                     // second heartbeat (armed true)
    List<String> h = r.lines("heartbeat");
    checkEq("two heartbeats in 200 clock events", h.size(), 2);
    check("the first heartbeat says armed=false with the dry-run detail",
        h.get(0).contains("\"armed\":false") && h.get(0).contains("\"mode\":\"DRY_RUN\""));
    check("the second says armed=true with the live detail",
        h.get(1).contains("\"armed\":true") && h.get(1).contains("\"mode\":\"SIM_LIVE\""));
    check("the wall clock is still on the heartbeat", h.get(0).contains("\"localTimeMs\":"));
  }

  private static void testASupplierThatThrowsCannotHurtThePipeline() throws Exception {
    Rig r = new Rig(true, false);
    r.pipeline.attachRuntimeStatus(() -> { throw new IllegalStateException("settings unavailable"); });
    r.tick(100);
    check("still healthy", r.pipeline.healthy());
    List<String> a = r.lines("arming_state");
    checkEq("the flag is journaled anyway", a.size(), 1);
    check("with the runtime detail as null", a.get(0).contains("\"runtime\":null"));
  }

  private static void testWithoutARuntimeSupplierTheFlagIsStillJournaled() throws Exception {
    Rig r = new Rig(true, false);
    r.armed.set(true);
    r.tick(100);
    List<String> a = r.lines("arming_state");
    check("armed true is recorded", a.size() == 1 && JsonObject.parse(a.get(0)).getBoolean("armed"));
    check("runtime is null", a.get(0).contains("\"runtime\":null"));
  }

  /** The effective flag flips while the runtime detail stays byte-identical (here: no detail at all). */
  private static void testTheArmedFlagAloneIsEnoughToJournalAChange() throws Exception {
    Rig r = new Rig(true, false);
    r.tick(25);
    r.armed.set(true);
    r.tick(ArmingStateTestSupport.checkWindow());
    List<String> a = r.lines("arming_state");
    checkEq("two records: not armed, then armed", a.size(), 2);
    check("in that order", !JsonObject.parse(a.get(0)).getBoolean("armed") && JsonObject.parse(a.get(1)).getBoolean("armed"));
  }

  /** Replay / observer setups have no risk chain: their journals must look exactly as before. */
  private static void testNoRiskChainKeepsTheOldShape() throws Exception {
    Rig r = new Rig(false, false);
    r.tick(200);
    checkEq("no arming_state records", r.lines("arming_state").size(), 0);
    List<String> h = r.lines("heartbeat");
    checkEq("heartbeats are still written", h.size(), 2);
    check("with no armed/runtime fields", !h.get(0).contains("\"armed\"") && !h.get(0).contains("\"runtime\""));
  }

  /** The Pipeline constant is package-private; expose it (+1 for the exact-boundary event) without widening it. */
  private static final class ArmingStateTestSupport {
    static int checkWindow() {
      return (int) Pipeline.ARMING_CHECK_EVERY_CLOCK_EVENTS + 1;
    }
  }
}
