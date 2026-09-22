package com.flow.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct synthetic test for Sequencer's producer-side backpressure
 * (flagged untested in docs/dynamic/plumbingEdgeCases.md §5): publish()
 * blocks the calling thread on a full queue rather than dropping
 * (deliberate, per the class javadoc), and queueDepth() is what
 * RiskChain.checkLag() watches to disarm before a slow drain thread ever
 * blocks a real MotiveWave callback thread against the 100,000-capacity
 * ceiling. Confirms depth actually grows under a slow handler and that a
 * realistic lag-guard threshold would trip well under that ceiling --
 * RiskChainTest already covers checkLag()'s own threshold logic in
 * isolation, this test is specifically about Sequencer producing a real,
 * growing queueDepth() for it to act on.
 */
public final class SequencerTest {
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
    testQueueDepthGrowsUnderASlowHandler();
    testLagGuardWouldTripWellUnderTheQueueCeiling();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all Sequencer synthetic checks passed.");
  }

  private static ClockEvent clockEvent(long seq) {
    return new ClockEvent(seq, seq, seq);
  }

  /**
   * A handler that sleeps a little per event, fed by a producer publishing
   * as fast as it can -- queueDepth() must climb well above zero while the
   * drain thread is stuck working through the backlog, proving the queue
   * genuinely absorbs backpressure rather than the handler keeping pace
   * for free.
   */
  private static void testQueueDepthGrowsUnderASlowHandler() throws Exception {
    AtomicInteger handled = new AtomicInteger(0);
    Sequencer.ExceptionHandler noThrow = (t, e) -> {
      System.out.println("UNEXPECTED exception in slow-handler test: " + t);
      failures++;
    };
    Sequencer seq = new Sequencer(e -> {
      handled.incrementAndGet();
      try {
        Thread.sleep(5);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }, noThrow);
    seq.start();

    long maxObservedDepth = 0;
    for (int i = 1; i <= 200; i++) {
      seq.publish((s, et, rt) -> clockEvent(s), i);
      maxObservedDepth = Math.max(maxObservedDepth, seq.queueDepth());
    }
    check("queueDepth grew well past a handful of events while the handler lagged behind "
        + "(observed max=" + maxObservedDepth + ")", maxObservedDepth > 20, true);
    check("queueDepth stayed nowhere near the 100,000 real ceiling for this small backlog",
        maxObservedDepth < 100_000, true);

    // Let it fully drain before returning -- deterministic teardown between tests.
    long deadline = System.currentTimeMillis() + 5_000;
    while (seq.queueDepth() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    check("backlog fully drained before teardown", seq.queueDepth() == 0, true);
    check("every published event was eventually handled, none silently dropped",
        handled.get() == 200, true);
    seq.stop();
  }

  /**
   * Composes Sequencer's real queueDepth() with RiskChain.checkLag()
   * directly (not through a full Pipeline) to confirm a realistic
   * threshold trips comfortably before the queue's own 100,000 ceiling --
   * the guard this whole mechanism exists to exercise (README "The risk
   * chain": disarm rather than let a strategy trade on a stale book).
   */
  private static void testLagGuardWouldTripWellUnderTheQueueCeiling() throws Exception {
    AtomicLong sawDepth = new AtomicLong(-1);
    Sequencer.ExceptionHandler noThrow = (t, e) -> failures++;
    Sequencer seq = new Sequencer(e -> {
      try {
        Thread.sleep(5);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }, noThrow);
    seq.start();

    for (int i = 1; i <= 100; i++) {
      seq.publish((s, et, rt) -> clockEvent(s), i);
      if (seq.queueDepth() > sawDepth.get()) sawDepth.set(seq.queueDepth());
    }

    // A realistic configured threshold (README/ExternalConfig default is
    // 1_000) -- checkLag() would already have blocked well before this
    // small backlog gets anywhere close to the real 100,000 queue ceiling.
    RiskChain rc = new RiskChain(configWithLagThreshold(20));
    RiskChain.Result r = rc.evaluate(new Intent("s", 1, 1, null, null, "enter"),
        new RiskChain.Context(true, true, 1000, 1000L, (int) sawDepth.get(), 0L));
    check("a threshold of 20 blocks against the observed depth (" + sawDepth.get()
        + "), long before the 100,000 queue ceiling", r.allowed(), false);

    long deadline = System.currentTimeMillis() + 5_000;
    while (seq.queueDepth() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    seq.stop();
  }

  private static ExternalConfig configWithLagThreshold(int threshold) throws java.io.IOException {
    java.nio.file.Path f = java.nio.file.Files.createTempFile("flow_v2_test_lag", ".json");
    f.toFile().deleteOnExit();
    java.nio.file.Files.writeString(f, "{\"maxContracts\":10,\"dailyLossLimitTicks\":100000,"
        + "\"rateLimitPerMinute\":1000,\"minDwellMs\":0,\"maxReversalsPerSession\":1000,"
        + "\"lagQueueDepthThreshold\":" + threshold + ",\"lagProcessingMsThreshold\":1000000}");
    return ExternalConfig.load(f);
  }
}
