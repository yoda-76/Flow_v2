package com.flow.core;

import com.flow.journal.JournalWriter;
import com.flow.journal.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Direct synthetic test for two things docs/dynamic/plumbingEdgeCases.md
 * flags as built but never exercised:
 *
 *  - §6: Pipeline's exception boundary (Sequencer.drainLoop() catches a
 *    feature/strategy throw and calls Pipeline.onPipelineException) --
 *    the drain thread must survive, raw ingestion must continue, the
 *    strategy must never be invoked again this session, and the DISARM
 *    record must carry the throwable.
 *  - §2: the daily-loss kill switch goes SILENT the instant Pipeline
 *    disarms for any reason (a feature exception here), because the
 *    every-event breach check sits behind the same `healthy` early
 *    return as strategy invocation. testKillSwitch... below locks in
 *    this CURRENT behavior as a regression baseline -- it is not an
 *    endorsement that this is correct. Whether it should change is
 *    exactly what's flagged for review there.
 *
 * Uses a real Sequencer (not a direct Pipeline.handle() call in a
 * try/catch) specifically so a regression in Sequencer's own wrapping,
 * not just Pipeline.onPipelineException() in isolation, would be caught.
 */
public final class PipelineExceptionBoundaryTest {
  private static int failures = 0;

  private static void check(String label, boolean got, boolean want) {
    if (got != want) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) throws Exception {
    testExceptionBoundary_DisarmsKeepsIngestingRaw_StrategyNeverInvokedAgain();
    testKillSwitch_FiresNormally_PositiveControl();
    testKillSwitch_GoesSilentAfterPipelineDisarmsForAnUnrelatedException();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all Pipeline exception-boundary / kill-switch checks passed.");
  }

  // --- shared test doubles -------------------------------------------

  /** Throws on exactly the Nth call to onEvent(), no-ops otherwise. */
  private static final class ThrowingFeature implements Feature {
    final AtomicInteger calls = new AtomicInteger(0);
    final int throwOnCall;
    ThrowingFeature(int throwOnCall) { this.throwOnCall = throwOnCall; }
    @Override public String id() { return "throwing"; }
    @Override public void onEvent(Event e) {
      if (calls.incrementAndGet() == throwOnCall) {
        throw new RuntimeException("synthetic failure on call " + throwOnCall);
      }
    }
    @Override public boolean isReady() { return true; }
    @Override public String notReadyReason() { return null; }
  }

  /** Wakes on every tick, counts its own invocations, never trades. */
  private static final class CountingObserverStrategy implements FlowStrategy {
    final AtomicInteger invocations = new AtomicInteger(0);
    @Override public String id() { return "counting_observer"; }
    @Override public Set<String> requires() { return Set.of(); }
    @Override public Set<Trigger> triggers() { return Set.of(new Trigger.EveryTick()); }
    @Override public void onInit(StrategyConfig cfg) {}
    @Override public Intent onEvent(MarketState state) {
      invocations.incrementAndGet();
      return Intent.none(id(), invocations.get());
    }
  }

  /** Enters long on its first wake, then re-asserts the same content forever ("still holding"). */
  private static final class EnterOnceThenHoldStrategy implements FlowStrategy {
    private final AtomicInteger seq = new AtomicInteger(0);
    private volatile boolean entered = false;
    @Override public String id() { return "enter_once_then_hold"; }
    @Override public Set<String> requires() { return Set.of(); }
    @Override public Set<Trigger> triggers() { return Set.of(new Trigger.EveryTick()); }
    @Override public void onInit(StrategyConfig cfg) {}
    @Override public Intent onEvent(MarketState state) {
      entered = true;
      return new Intent(id(), seq.incrementAndGet(), 1, null, null, "enter");
    }
  }

  private static TickEvent tick(long seq, int priceTicks) {
    return new TickEvent(seq, seq * 1000L, seq * 1000L, priceTicks, 1, true, priceTicks, priceTicks, 0L, 0L);
  }

  private static ExternalConfig configWith(String json) throws Exception {
    Path f = Files.createTempFile("flow_v2_test_pipeline", ".json");
    f.toFile().deleteOnExit();
    Files.writeString(f, json);
    return ExternalConfig.load(f);
  }

  private static void awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
      Thread.sleep(5);
    }
  }

  // --- §6: exception boundary ------------------------------------------

  private static void testExceptionBoundary_DisarmsKeepsIngestingRaw_StrategyNeverInvokedAgain() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_pipeline_exc");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();

    ThrowingFeature feature = new ThrowingFeature(3); // throws on the 3rd event
    CountingObserverStrategy strategy = new CountingObserverStrategy();
    IntentSink sink = (intent, event) -> {};
    Pipeline pipeline = new Pipeline(strategy, journal, sink, Map.of("throwing", feature), null);
    Sequencer sequencer = new Sequencer(pipeline::handle, pipeline);
    sequencer.start();

    for (long s = 1; s <= 2; s++) sequencer.publish((seq, et, rt) -> tick(seq, 1000), s * 1000L);
    awaitTrue(() -> feature.calls.get() >= 2, 2000);
    check("2 pre-exception events processed the feature normally", feature.calls.get() >= 2, true);
    check("strategy invoked twice so far (once per tick before the throw)",
        strategy.invocations.get() == 2, true);
    check("pipeline still healthy before the throwing event", pipeline.healthy(), true);

    sequencer.publish((seq, et, rt) -> tick(seq, 1000), 3000L); // the throwing event (feature call #3)
    awaitTrue(() -> !pipeline.healthy(), 2000);
    check("pipeline disarms (healthy=false) after the feature throws", pipeline.healthy(), false);

    for (long s = 4; s <= 6; s++) sequencer.publish((seq, et, rt) -> tick(seq, 1000), s * 1000L);
    awaitTrue(() -> feature.calls.get() >= 6, 2000);
    // feature.calls increments partway through handle() (before
    // journal.writeRaw() runs later in that same call) -- give the
    // in-flight handle() call a moment to actually finish (writeRaw +
    // return) before stopping the sequencer and flushing the journal,
    // rather than racing the tail end of that one call.
    Thread.sleep(50);
    check("the drain thread survived the exception and kept processing later events "
        + "(feature saw all 6, not stuck at 3)", feature.calls.get() == 6, true);
    check("the strategy was NEVER invoked again after the disarm (still exactly 2, not 5)",
        strategy.invocations.get() == 2, true);

    sequencer.stop();
    journal.flushAndClose();

    // 5, not 6: Pipeline.handle() calls journal.writeRaw() AFTER the
    // feature loop, so the one event whose OWN feature call throws (#3)
    // never reaches that line and is never raw-journaled itself, even
    // though marketState.bump() already ran for it moments earlier in
    // the same call. Ingestion resuming afterward (events 4-6) is the
    // guarantee actually being tested here, not the throwing event itself.
    List<String> rawLines = Files.readAllLines(dir.resolve("raw.jsonl"));
    check("raw ingestion continued for every event AFTER the exception (5 of 6 total -- "
        + "the throwing event #3 itself is never raw-journaled, see comment above)",
        rawLines.size() == 5, true);

    List<String> decisionLines = Files.readAllLines(dir.resolve("decisions.jsonl"));
    boolean disarmFound = decisionLines.stream().anyMatch(l ->
        l.contains("\"type\":\"DISARM\"") && l.contains("synthetic failure on call 3"));
    check("a DISARM decision record was written carrying the actual throwable's message", disarmFound, true);
  }

  // --- §2: kill switch vs. Pipeline health ------------------------------

  private static ExternalConfig killSwitchConfig() throws Exception {
    return configWith("{\"maxContracts\":10,\"dailyLossLimitTicks\":50,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":1000000,\"lagProcessingMsThreshold\":1000000}");
  }

  /**
   * Positive control: without any exception in the picture, entering a
   * position and then crashing price past the daily-loss limit must trip
   * the kill switch. This is what makes the NEXT test's "kill switch did
   * NOT fire" result meaningful, rather than a silently broken harness.
   */
  private static void testKillSwitch_FiresNormally_PositiveControl() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_killswitch_control");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();

    RiskChain riskChain = new RiskChain(killSwitchConfig());
    EnterOnceThenHoldStrategy strategy = new EnterOnceThenHoldStrategy();
    IntentSink sink = (intent, event) -> {};
    AtomicReference<String> killSwitchReason = new AtomicReference<>();
    java.util.function.Consumer<String> killSwitch = killSwitchReason::set;

    Pipeline pipeline = new Pipeline(strategy, journal, sink, Map.of(), ticks -> ticks,
        riskChain, () -> true, () -> 0, killSwitch);
    Sequencer sequencer = new Sequencer(pipeline::handle, pipeline);
    sequencer.start();

    sequencer.publish((seq, et, rt) -> tick(seq, 1000), 1000L); // enters long @1000
    awaitTrue(() -> pipeline.marketState().generation() >= 1, 2000);
    sequencer.publish((seq, et, rt) -> tick(seq, 949), 2000L); // unrealized -51, breaches -50
    awaitTrue(() -> killSwitchReason.get() != null, 2000);

    check("kill switch fires once the breach is real, with no exception in the picture",
        killSwitchReason.get() != null, true);

    sequencer.stop();
    journal.flushAndClose();
  }

  /**
   * The actual §2 finding: an unrelated feature exception disarms
   * Pipeline BEFORE the breach ever arrives, and the kill switch -- which
   * its own javadoc frames as checked "on every event, independent of
   * the strategy's own triggers or intents" -- never fires, because that
   * check itself sits behind the same `healthy` early return the
   * exception just tripped. Locking in the CURRENT behavior; see this
   * file's class javadoc and plumbingEdgeCases.md §2 for the open
   * question of whether it should change.
   */
  private static void testKillSwitch_GoesSilentAfterPipelineDisarmsForAnUnrelatedException() throws Exception {
    Path dir = Files.createTempDirectory("flow_v2_test_killswitch_disarmed");
    dir.toFile().deleteOnExit();
    JournalWriter journal = new JournalWriter(dir);
    journal.start();

    RiskChain riskChain = new RiskChain(killSwitchConfig());
    EnterOnceThenHoldStrategy strategy = new EnterOnceThenHoldStrategy();
    ThrowingFeature feature = new ThrowingFeature(2); // throws on the 2nd event, right after entry
    IntentSink sink = (intent, event) -> {};
    AtomicReference<String> killSwitchReason = new AtomicReference<>();
    AtomicBoolean killSwitchCalled = new AtomicBoolean(false);
    java.util.function.Consumer<String> killSwitch = reason -> {
      killSwitchCalled.set(true);
      killSwitchReason.set(reason);
    };

    Pipeline pipeline = new Pipeline(strategy, journal, sink, Map.of("throwing", feature), ticks -> ticks,
        riskChain, () -> true, () -> 0, killSwitch);
    Sequencer sequencer = new Sequencer(pipeline::handle, pipeline);
    sequencer.start();

    sequencer.publish((seq, et, rt) -> tick(seq, 1000), 1000L); // event 1: enters long @1000, feature call #1
    awaitTrue(() -> feature.calls.get() >= 1, 2000);
    check("entry accepted before the exception (still healthy)", pipeline.healthy(), true);

    sequencer.publish((seq, et, rt) -> tick(seq, 1000), 2000L); // event 2: feature call #2 -- throws, disarms
    awaitTrue(() -> !pipeline.healthy(), 2000);
    check("pipeline disarmed by the unrelated feature exception", pipeline.healthy(), false);

    // Event 3: price crashes to breach the daily-loss limit exactly as in
    // the positive control above -- the only difference is Pipeline is
    // already disarmed.
    sequencer.publish((seq, et, rt) -> tick(seq, 949), 3000L);
    // No positive signal to await for "it did NOT fire" -- give it a
    // generous window during which the positive control above already
    // proved a real breach fires well inside.
    Thread.sleep(500);

    check("CURRENT BEHAVIOR: the kill switch never fires, even though the exact same "
        + "breach fired it in the positive control -- because dailyLossBreached() sits "
        + "behind Pipeline's healthy-check early return", killSwitchCalled.get(), false);

    sequencer.stop();
    journal.flushAndClose();

    List<String> decisionLines = Files.readAllLines(dir.resolve("decisions.jsonl"));
    boolean killSwitchJournaled = decisionLines.stream().anyMatch(l -> l.contains("\"type\":\"KILL_SWITCH\""));
    check("no KILL_SWITCH decision record either -- the breach is invisible in the journal, "
        + "not just unactioned", killSwitchJournaled, false);
  }
}
