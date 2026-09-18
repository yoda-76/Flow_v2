package com.flow.core;

import com.flow.flow.LevelSource;
import com.flow.flow.ZoneView;
import com.flow.journal.Json;
import com.flow.journal.JournalWriter;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntToDoubleFunction;

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
  // D-58: liquidity-map periodic snapshot cadence -- v1 defaults, same
  // "reasonable now, revisit from real storage measurement" stance as
  // SdkVolumeProfileFeature's ROTATE_EVERY_TICKS. Currently the same
  // period as the heartbeat above by coincidence, not by design -- kept
  // as its own named constant since the two serve unrelated purposes and
  // may want to diverge later (the user's own framing: "granularity will
  // be decided later on the basis of how much storage is being consumed").
  private static final long DOM_SNAPSHOT_EVERY_CLOCK_EVENTS = 100; // ~10s
  private static final int DOM_SNAPSHOT_WINDOW_TICKS = 100; // bounded window around mid-price

  private final MutableMarketState marketState = new MutableMarketState();
  private final TriggerEvaluator triggers;
  private final ReadinessChecker readinessChecker;
  private final JournalWriter journal;
  private final FlowStrategy strategy;
  private final IntentSink intentSink;
  private final Map<String, Feature> features;
  private final IntToDoubleFunction priceDecoder; // nullable -- see constructor javadoc
  private final RiskChain riskChain; // nullable -- see constructor javadoc
  private final java.util.function.BooleanSupplier armedSupplier; // nullable, only consulted if riskChain != null
  private final java.util.function.IntSupplier queueDepthSupplier; // nullable, only consulted if riskChain != null

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
   *
   * priceDecoder converts an integer tick offset to a decimal price, for
   * human-readable trace output only -- the journal is one of D-21's two
   * sanctioned decimal-conversion boundaries (ingest is the other), so
   * doing it here doesn't violate "prices are integers throughout the
   * core." Nullable: a live FlowRuntimeStudy always has one (its
   * PriceCodec); ReplayHarness passes null since it has no live price
   * context, and trace lines fall back to tick-offset-only in that case.
   *
   * riskChain/armedSupplier/queueDepthSupplier (D-61) are nullable
   * together, as one unit -- null means "no risk chain configured,"
   * preserving the exact prior behavior (every changed intent goes
   * straight to intentSink) for ReplayHarness, TriggerEvaluatorTest, and
   * the pure-observer LevelZoneObserverStrategy, none of which need
   * risk-gating. A live FlowRuntimeStudy always supplies all three.
   */
  public Pipeline(FlowStrategy strategy, JournalWriter journal, IntentSink intentSink,
                   Map<String, Feature> features, IntToDoubleFunction priceDecoder) {
    this(strategy, journal, intentSink, features, priceDecoder, null, null, null);
  }

  public Pipeline(FlowStrategy strategy, JournalWriter journal, IntentSink intentSink,
                   Map<String, Feature> features, IntToDoubleFunction priceDecoder,
                   RiskChain riskChain, java.util.function.BooleanSupplier armedSupplier,
                   java.util.function.IntSupplier queueDepthSupplier) {
    this.strategy = strategy;
    this.journal = journal;
    this.intentSink = intentSink;
    this.features = Map.copyOf(features);
    this.triggers = new TriggerEvaluator(this.features);
    this.readinessChecker = new ReadinessChecker(this.features);
    this.priceDecoder = priceDecoder;
    this.riskChain = riskChain;
    this.armedSupplier = armedSupplier;
    this.queueDepthSupplier = queueDepthSupplier;
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
    long startNanos = System.nanoTime(); // D-61 lag guard only -- see checkLag()'s call site below for why this is an accepted exception to "no wall clock below ingest"
    marketState.bump(e);
    for (Feature f : features.values()) {
      f.onEvent(e); // single-writer thread only (README "The event stream") -- this IS that thread
    }
    journal.writeRaw(e.seq(), RawEventCodec.encode(e));

    if (e instanceof ClockEvent) {
      long count = clockEventCount.incrementAndGet();
      if (count % HEARTBEAT_EVERY_CLOCK_EVENTS == 0) heartbeat(e);
      if (count % DOM_SNAPSHOT_EVERY_CLOCK_EVENTS == 0) maybeDomSnapshot(e);
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
        // The "price trace" -- journal every LevelCross/ZoneTransition
        // firing regardless of what the strategy decides to do with it
        // (README: "the runtime owns trigger evaluation and journals
        // why the strategy was woken"). Deliberately not done for
        // BarClose/EveryTick/Throttle/PriceCross/BookChange -- those
        // fire far more often and aren't what "trace through the
        // levels" means.
        if (t instanceof Trigger.LevelCross lc) {
          journal.writeDecision(e.seq(), traceLine(t, e, lc.featureId()));
        } else if (t instanceof Trigger.ZoneTransition zt) {
          journal.writeDecision(e.seq(), traceLine(t, e, zt.featureId()));
        }
      }
    }
    if (!wake) return;

    Intent intent = strategy.onEvent(marketState);
    if (!sameContent(intent, lastIntent)) {
      lastIntent = intent; // updated regardless of what the risk chain decides, so an identical still-blocked intent doesn't re-evaluate every event
      journal.writeDecision(e.seq(), intentChangeLine(intent, e.seq()));

      if (riskChain == null) {
        intentSink.onIntentChanged(intent, e); // no risk chain configured -- exact prior behavior
        return;
      }

      long processingMs = (System.nanoTime() - startNanos) / 1_000_000L;
      RiskChain.Context ctx = new RiskChain.Context(
          armedSupplier.getAsBoolean(),
          readinessChecker.isReady(strategy.requires()),
          marketState.lastPriceTicks(),
          marketState.exchangeTimeMs(),
          queueDepthSupplier.getAsInt(),
          processingMs);
      RiskChain.Result result = riskChain.evaluate(intent, ctx);
      journal.writeDecision(e.seq(), RiskChain.resultLine(e.seq(), intent, result));
      if (result.allowed()) {
        riskChain.recordAccepted(intent, ctx);
        intentSink.onIntentChanged(intent, e);
      }
      // Blocked: nothing forwarded to intentSink -- the suppressed trade
      // is visible in the risk_verdict record above, not silently dropped.
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

  /**
   * The raw event half of D-40's zone lifecycle journal -- CREATED/
   * MERGED/SPLIT/DISSOLVED and the layer1Score/outcome pairing described
   * there are D-39 (ranking) concepts that don't exist yet; this is the
   * foundational trace (what fired, where, when) that D-39's richer
   * version will build on, not a shortcut past it.
   *
   * Per the user's own request (2026-09-17): a level_trace records not
   * just the current price but the level's own value at that moment
   * (they can differ -- D-38's cause-agnostic cross means the level
   * itself can have moved onto a stationary price); a zone_trace records
   * the specific zone's low/high, not just which kind (LVN/HVN) fired --
   * fetched from TriggerEvaluator.lastFiredZoneRange(), captured at fire
   * time since a LEAVE's zone is no longer "current" by the time this
   * runs (see that method's javadoc).
   *
   * relativeRow/priceDecimal/levelPriceDecimal/zoneLow-HighDecimal are
   * all best-effort: the *Row fields are null if the feature doesn't
   * implement LevelSource.relativeRow() or isn't ready; the *Decimal
   * fields are null if this Pipeline has no priceDecoder (ReplayHarness).
   * Neither absence is an error.
   */
  private String traceLine(Trigger t, Event e, String featureId) {
    Integer price = Event.priceOf(e);
    LevelSource ls = features.get(featureId) instanceof LevelSource s ? s : null;
    Integer relativeRow = (price != null && ls != null) ? ls.relativeRow(price) : null;
    Double priceDecimal = (price != null && priceDecoder != null) ? priceDecoder.applyAsDouble(price) : null;

    if (t instanceof Trigger.LevelCross lc) {
      Integer levelValue = ls != null ? ls.levelValue(lc.levelName()) : null;
      Double levelDecimal = (levelValue != null && priceDecoder != null) ? priceDecoder.applyAsDouble(levelValue) : null;
      return Json.object()
          .field("type", "level_trace")
          .field("seq", e.seq())
          .field("featureId", lc.featureId())
          .field("levelName", lc.levelName())
          .field("kind", lc.kind().name())
          .fieldOrNull("priceTicks", price)
          .fieldOrNull("priceDecimal", priceDecimal)
          .fieldOrNull("levelPriceTicks", levelValue)
          .fieldOrNull("levelPriceDecimal", levelDecimal)
          .fieldOrNull("relativeRow", relativeRow)
          .build();
    }
    if (t instanceof Trigger.ZoneTransition zt) {
      ZoneView zone = triggers.lastFiredZone(t);
      Integer zoneLow = zone != null ? zone.lowPriceTicks() : null;
      Integer zoneHigh = zone != null ? zone.highPriceTicks() : null;
      Double zoneLowDecimal = (zoneLow != null && priceDecoder != null) ? priceDecoder.applyAsDouble(zoneLow) : null;
      Double zoneHighDecimal = (zoneHigh != null && priceDecoder != null) ? priceDecoder.applyAsDouble(zoneHigh) : null;
      // Age from the zone's own id-creation time (D-38's persistent id),
      // not from this trace record -- a zone re-entered later still
      // reports how long it has existed in total, not how long since the
      // last time it was touched.
      Long zoneAgeMs = zone != null ? e.eventTimeMs() - zone.firstSeenAtMs() : null;
      return Json.object()
          .field("type", "zone_trace")
          .field("seq", e.seq())
          .field("featureId", zt.featureId())
          .field("zoneKind", zt.zoneKind().name())
          .field("kind", zt.kind().name())
          .fieldOrNull("priceTicks", price)
          .fieldOrNull("priceDecimal", priceDecimal)
          .fieldOrNull("zoneLowTicks", zoneLow)
          .fieldOrNull("zoneHighTicks", zoneHigh)
          .fieldOrNull("zoneLowDecimal", zoneLowDecimal)
          .fieldOrNull("zoneHighDecimal", zoneHighDecimal)
          .fieldOrNull("zoneAgeMs", zoneAgeMs)
          .fieldOrNull("relativeRow", relativeRow)
          .build();
    }
    throw new IllegalArgumentException("traceLine called for a non-traceable trigger: " + t);
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

  /**
   * D-58: the only historical record this construct keeps -- per-DOM-
   * update detail is deliberately never persisted (DomEvent's javadoc),
   * so this periodic, bounded-window snapshot of LiquidityMapFeature's
   * live state is what makes any later heatmap reconstruction possible
   * at all, combined with historical OHLC (the user's own plan,
   * 2026-09-18). Silently a no-op if no liquidity-map feature is
   * registered or it isn't ready yet (e.g. DOMListener not wired, or no
   * DOM update has arrived) -- same "features is what it is, act on
   * what's actually there" stance the trace journaling already takes for
   * missing LevelSource/ZoneSource features.
   */
  private void maybeDomSnapshot(Event e) {
    if (!(features.get(com.flow.flow.LiquidityMapView.FEATURE_ID) instanceof com.flow.flow.LiquidityMapView lm)
        || !lm.isReady()) {
      return;
    }
    journal.writeDecision(e.seq(), Json.object()
        .field("type", "liquidity_snapshot")
        .field("seq", e.seq())
        .field("exchangeTimeMs", marketState.exchangeTimeMs())
        .fieldOrNull("bestBidTicks", lm.bestBidTicks())
        .fieldOrNull("bestAskTicks", lm.bestAskTicks())
        .field("windowTicks", DOM_SNAPSHOT_WINDOW_TICKS)
        .fieldRaw("bidRows", domRowsJson(lm.bidRowsWithin(DOM_SNAPSHOT_WINDOW_TICKS)))
        .fieldRaw("askRows", domRowsJson(lm.askRowsWithin(DOM_SNAPSHOT_WINDOW_TICKS)))
        .build());
  }

  private static String domRowsJson(java.util.List<com.flow.core.DomRow> rows) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < rows.size(); i++) {
      if (i > 0) sb.append(',');
      com.flow.core.DomRow r = rows.get(i);
      sb.append("{\"p\":").append(r.priceTicks()).append(",\"s\":").append(r.size()).append('}');
    }
    return sb.append(']').toString();
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
