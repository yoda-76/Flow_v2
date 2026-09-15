package com.flow.core;

import com.flow.journal.Json;
import com.flow.journal.JournalWriter;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single-writer drain-thread handler (README "The event stream"):
 * bumps MarketState, writes the raw-tier journal record for every event,
 * evaluates the active strategy's declared triggers, and on a changed
 * Intent writes the decisions-tier record and hands it to the IntentSink
 * (flow-runtime's OrderGateway). SDK-free by construction -- everything
 * here is testable under a plain JDK with synthetic event sequences
 * (README "Testing"), which is the point of keeping it out of
 * flow-runtime.
 *
 * Also the exception boundary (D-25): implements Sequencer.ExceptionHandler
 * itself, so a strategy/feature throwing here disarms (stops invoking the
 * strategy, keeps ingesting and journaling raw events) rather than killing
 * the drain thread or silently continuing to trade on state that may now
 * be wrong.
 */
public final class Pipeline implements Sequencer.ExceptionHandler {
  private static final long HEARTBEAT_EVERY_CLOCK_EVENTS = 100; // ~10s at the 100ms clock cadence

  private final MutableMarketState marketState = new MutableMarketState();
  private final TriggerEvaluator triggers;
  private final JournalWriter journal;
  private final FlowStrategy strategy;
  private final IntentSink intentSink;
  private final Map<String, Feature> features;

  private final AtomicBoolean healthy = new AtomicBoolean(true);
  private final AtomicLong clockEventCount = new AtomicLong(0);
  private volatile Intent lastIntent;

  /**
   * features is required explicitly (an empty Map.of() is fine, but
   * callers must say so) rather than defaulted, so "this Pipeline has no
   * features" is a visible choice at every call site -- ReplayHarness
   * currently has to pass Map.of() for anything SDK-engine-backed (D-43),
   * and that should never be silent. Also what TriggerEvaluator looks
   * features up in for D-38's LevelCross/ZoneTransition triggers.
   */
  public Pipeline(FlowStrategy strategy, JournalWriter journal, IntentSink intentSink, Map<String, Feature> features) {
    this.strategy = strategy;
    this.journal = journal;
    this.intentSink = intentSink;
    this.features = Map.copyOf(features);
    this.triggers = new TriggerEvaluator(this.features);
    this.lastIntent = Intent.none(strategy.id(), 0);
  }

  public MarketState marketState() {
    return marketState;
  }

  public Map<String, Feature> features() {
    return features;
  }

  public boolean healthy() {
    return healthy.get();
  }

  /** The Consumer<Event> handed to Sequencer's constructor. */
  public void handle(Event e) {
    marketState.bump(e);
    for (Feature f : features.values()) {
      f.onEvent(e); // single-writer thread only (README "The event stream") -- this IS that thread
    }
    journal.writeRaw(e.seq(), RawEventCodec.encode(e));

    if (e instanceof ClockEvent && clockEventCount.incrementAndGet() % HEARTBEAT_EVERY_CLOCK_EVENTS == 0) {
      heartbeat(e);
    }

    if (journal.decisionsOverflowed() && healthy.compareAndSet(true, false)) {
      journal.writeRaw(e.seq(), Json.object()
          .field("type", "DISARM")
          .field("reason", "decisions journal queue overflow")
          .field("seq", e.seq())
          .build());
    }

    if (!healthy.get()) return; // keep ingesting/journaling raw events; stop invoking the strategy

    // Every declared trigger is evaluated every event, never short-circuited
    // on the first one that fires -- a stateful trigger (LevelCross,
    // ZoneTransition, PriceCross, BookChange) has to see every event to
    // keep its own "last observed side" correct, and a strategy commonly
    // declares more than one (e.g. separate ENTER and LEAVE triggers on
    // the same zone kind). Breaking early would silently let whichever
    // trigger comes later in iteration order fall behind. Found by
    // TriggerEvaluatorTest, not assumed safe.
    Set<Trigger> declared = strategy.triggers();
    boolean wake = false;
    for (Trigger t : declared) {
      if (triggers.shouldWake(t, e, e.eventTimeMs())) {
        wake = true;
      }
    }
    if (!wake) return;

    Intent intent = strategy.onEvent(marketState);
    if (!sameContent(intent, lastIntent)) {
      lastIntent = intent;
      journal.writeDecision(e.seq(), intentChangeLine(intent, e.seq()));
      intentSink.onIntentChanged(intent, e);
    }
  }

  /**
   * Content equality deliberately excluding Intent.seq(). seq is a
   * per-call identifier (NullStrategy increments it on every invocation,
   * and any real strategy will too), not part of "did the desired state
   * actually change" -- comparing full record equality here would make
   * every single wake produce a change record regardless of whether
   * anything meaningful moved, defeating D-15's change-only journal
   * design entirely.
   */
  private static boolean sameContent(Intent a, Intent b) {
    return Objects.equals(a.strategyId(), b.strategyId())
        && a.targetPosition() == b.targetPosition()
        && Objects.equals(a.stopPriceTicks(), b.stopPriceTicks())
        && Objects.equals(a.targetPriceTicks(), b.targetPriceTicks())
        && Objects.equals(a.reason(), b.reason());
  }

  @Override
  public void onPipelineException(Throwable t, Event e) {
    healthy.set(false);
    String line = Json.object()
        .field("type", "DISARM")
        .field("reason", "pipeline exception: " + t)
        .field("seq", e.seq())
        .build();
    journal.writeDecision(e.seq(), line);
  }

  private void heartbeat(Event e) {
    journal.writeDecision(e.seq(), Json.object()
        .field("type", "heartbeat")
        .field("seq", e.seq())
        .field("generation", marketState.generation())
        .field("exchangeTimeMs", marketState.exchangeTimeMs())
        .field("localTimeMs", marketState.localTimeMs())
        .field("healthy", healthy.get())
        .field("lastIntentReason", lastIntent.reason())
        .build());
  }

  private static String intentChangeLine(Intent intent, long seq) {
    return Json.object()
        .field("type", "intent_changed")
        .field("seq", seq)
        .field("strategyId", intent.strategyId())
        .field("intentSeq", intent.seq())
        .field("targetPosition", intent.targetPosition())
        .fieldOrNull("stopPriceTicks", intent.stopPriceTicks())
        .fieldOrNull("targetPriceTicks", intent.targetPriceTicks())
        .field("reason", intent.reason())
        .build();
  }

}
