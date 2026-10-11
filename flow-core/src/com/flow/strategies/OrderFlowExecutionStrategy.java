package com.flow.strategies;

import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.Trigger;
import com.flow.flow.MarketStructureView;
import com.flow.flow.ZoneRange;
import com.flow.journal.Json;
import com.flow.orderflow.EvalContext;
import com.flow.orderflow.ExecutionPlanner;
import com.flow.orderflow.LiquidityTracker;
import com.flow.orderflow.ModelResult;
import com.flow.orderflow.Models;
import com.flow.orderflow.OfConfig;
import com.flow.orderflow.OrderFlowEngine;
import com.flow.orderflow.SessionClock;
import com.flow.orderflow.TradeIdea;

import java.io.Writer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The order-flow execution model (docs/dynamic/orderFlowRules_gapAnswers_v1.md), one FlowStrategy as decided in G28:
 *
 * <ol>
 *   <li><b>Idea source</b> -- the market-structure feature: trend direction + its current area of interest
 *       (lastTjl2 / DT / DB when tradeable), the same objective zone the previous strategy used. The idea opens when
 *       that zone appears and closes when it changes, the trend flips, or the session is flattened (the strategy owns
 *       idea validity, extended Q-L3).</li>
 *   <li><b>Evaluation</b> -- at every closed execution bar while flat and an idea is active, M1-M8 score the idea
 *       ({@link Models}) and {@link ExecutionPlanner} aggregates (Go = a +2 and no -2), picks stop and target and
 *       applies the entry filters and the A4 limits.</li>
 *   <li><b>Order</b> -- Go emits an entry intent: market at the trigger-bar close (the runtime's existing entry path),
 *       stop and target as absolute levels. While in the position the intent is held; a stop/target touch emits a
 *       flat intent (same pattern as MarketStructureLvnReversalStrategy, whose rejection/flatten handling is reused).</li>
 * </ol>
 *
 * Every idea event, every evaluation (all model scores and reads, Go/No-Go with every block reason, the levels) and
 * every entry/exit is written as one JSON line to the rolling feature log {@code logs/orderflow_eval*.log}, kept
 * like every other system log. Strategy config key {@code evalLog=off} disables the file (tests).
 *
 * Deliberately not here yet: intrabar shadow signals (G0.3) and hypothetical-outcome tracking for rejected ideas
 * (done offline from the raw journal, gapAnswers §11).
 */
public final class OrderFlowExecutionStrategy implements FlowStrategy {
  public static final String ID = "orderflow_execution";

  private enum Phase { SEARCHING, IN_POSITION }

  private final AtomicLong intentSeq = new AtomicLong(0);
  private OfConfig c = OfConfig.defaults();
  private LiquidityTracker liq = new LiquidityTracker(c);
  private Writer log;
  private int fixedContracts = 1;

  private Phase phase = Phase.SEARCHING;
  private TradeIdea idea;
  private long ideaCounter;
  private long lastEvaluatedBar = -1;

  private int positionDirection;
  private Integer positionStopTicks, positionTargetTicks;
  private String positionModel, positionIdeaId;

  // A4 limits, per CME trading day
  private long limitsDay = Long.MIN_VALUE;
  private final Map<String, Integer> zoneEntries = new HashMap<>();
  private int sessionEntries;

  // rollback for a rejected intent (FlowStrategy.onIntentRejected)
  private long pendingMutationSeq = -1;
  private Phase savedPhase;
  private int savedDirection, savedSessionEntries;
  private Integer savedStop, savedTarget;
  private String savedZoneKey;

  private final ExecutionPlanner.Limits limits = new ExecutionPlanner.Limits() {
    @Override public int entriesForZoneThisSession(String zoneKey) { return zoneEntries.getOrDefault(zoneKey, 0); }
    @Override public int entriesThisSession() { return sessionEntries; }
  };

  @Override public String id() { return ID; }

  @Override
  public Set<String> requires() {
    return Set.of(MarketStructureView.FEATURE_ID, OrderFlowEngine.FEATURE_ID);
  }

  @Override
  public Set<Trigger> triggers() {
    return Set.of(new Trigger.EveryTick());
  }

  @Override
  public void onInit(StrategyConfig cfg) {
    fixedContracts = Integer.parseInt(cfg.getOrDefault("fixedContracts", "1"));
    String path = cfg.get("orderflowConfigPath");
    c = path == null ? OfConfig.defaults() : OfConfig.load(Path.of(path));
    liq = new LiquidityTracker(c);
    String evalLogPath = cfg.get("evalLogPath"); // replay/research tools write to their own file
    if (evalLogPath != null) {
      try {
        log = java.nio.file.Files.newBufferedWriter(Path.of(evalLogPath));
      } catch (Exception e) {
        log = null;
      }
    } else if (!"off".equals(cfg.getOrDefault("evalLog", "on"))) {
      try {
        log = com.flow.core.FeatureLogs.open("orderflow_eval");
      } catch (Exception e) {
        log = null;
      }
    }
    write(Json.object().field("type", "init").field("strategy", ID)
        .field("configSource", c.source == null ? "defaults" : c.source)
        .field("configError", c.loadError == null ? "" : c.loadError)
        .fieldRaw("config", c.toJson()).build());
  }

  /** The config in force (the runtime builds the engine from the same file). */
  public OfConfig config() { return c; }

  @Override
  public void onIntentRejected(Intent intent, String reason) {
    if (intent.seq() != pendingMutationSeq) return;
    phase = savedPhase;
    positionDirection = savedDirection;
    positionStopTicks = savedStop;
    positionTargetTicks = savedTarget;
    sessionEntries = savedSessionEntries;
    if (savedZoneKey != null) zoneEntries.merge(savedZoneKey, -1, Integer::sum);
    pendingMutationSeq = -1;
    write(Json.object().field("type", "intent_rejected").field("seq", intent.seq()).field("reason", reason).build());
  }

  @Override
  public void onFlattened(String reason) {
    phase = Phase.SEARCHING;
    positionDirection = 0;
    positionStopTicks = positionTargetTicks = null;
    pendingMutationSeq = -1;
    closeIdea("flattened: " + reason);
  }

  /**
   * Never throws (an exception would disarm the pipeline for the session): an error is written to the evaluation log
   * and the strategy answers with the safe intent -- hold the open position (never an accidental flat), or none
   * while flat.
   */
  @Override
  public Intent onEvent(MarketState state) {
    try {
      return onEventUnguarded(state);
    } catch (RuntimeException ex) {
      strategyErrors++;
      if (strategyErrors <= 50) {
        write(Json.object().field("type", "error").field("where", "strategy").field("error", String.valueOf(ex))
            .field("at", ex.getStackTrace().length > 0 ? String.valueOf(ex.getStackTrace()[0]) : "").build());
      }
      if (phase == Phase.IN_POSITION && positionStopTicks != null && positionTargetTicks != null) {
        return new Intent(ID, intentSeq.incrementAndGet(), positionDirection * fixedContracts,
            positionStopTicks, positionTargetTicks, "holding");
      }
      return none();
    }
  }

  private long strategyErrors;
  public long errorCount() { return strategyErrors; }
  private long engineErrorsLogged;

  private Intent onEventUnguarded(MarketState state) {
    Integer price = state.lastPriceTicks();
    MarketStructureView ms = state.marketStructure();
    OrderFlowEngine eng = state.orderFlow();
    if (price == null || ms == null || !ms.isReady() || eng == null) return none();
    long now = state.exchangeTimeMs();
    if (eng.errorCount() != engineErrorsLogged) {
      engineErrorsLogged = eng.errorCount();
      write(Json.object().field("type", "error").field("where", "orderflow_engine").field("count", engineErrorsLogged)
          .field("error", String.valueOf(eng.lastError())).build());
    }
    liq.update(state.liquidityMap(), eng, now);
    long day = SessionClock.tradingDayStart(now);
    if (day != limitsDay) { limitsDay = day; zoneEntries.clear(); sessionEntries = 0; }

    if (phase == Phase.IN_POSITION) return manage(price, now);
    maintainIdea(ms, now);
    if (idea == null || eng.barCount() == lastEvaluatedBar) return none();
    lastEvaluatedBar = eng.barCount();
    return evaluate(eng, ms, now);
  }

  private Intent none() {
    return Intent.none(ID, intentSeq.incrementAndGet());
  }

  private void maintainIdea(MarketStructureView ms, long now) {
    ZoneRange aoi = ms.lastTjl2();
    Set<MarketStructureView.TradeableLevel> t = ms.tradeableLevels();
    boolean tradeable = t.contains(MarketStructureView.TradeableLevel.TJL2)
        || t.contains(MarketStructureView.TradeableLevel.DT) || t.contains(MarketStructureView.TradeableLevel.DB);
    if (aoi == null || !tradeable || ms.trend() == null) { closeIdea("no tradeable zone"); return; }
    int dir = ms.trend() == MarketStructureView.Trend.UP ? 1 : -1;
    if (idea != null && idea.direction() == dir && idea.zoneLowTicks() == aoi.lowTicks() && idea.zoneHighTicks() == aoi.highTicks()) {
      return;
    }
    closeIdea(idea == null ? null : "zone or trend changed");
    int invalidation = dir > 0 ? aoi.lowTicks() : aoi.highTicks();
    idea = new TradeIdea("I" + (++ideaCounter) + "-" + now, dir, aoi.lowTicks(), aoi.highTicks(), invalidation, now,
        "market_structure:" + t);
    write(Json.object().field("type", "idea_open").field("idea", idea.id()).field("dir", dir)
        .field("zoneLo", aoi.lowTicks()).field("zoneHi", aoi.highTicks()).field("t", now).build());
  }

  private void closeIdea(String reason) {
    if (idea == null) return;
    if (reason != null) {
      write(Json.object().field("type", "idea_close").field("idea", idea.id()).field("reason", reason).build());
    }
    idea = null;
  }

  private Intent evaluate(OrderFlowEngine eng, MarketStructureView ms, long now) {
    int htf = ms.trend() == MarketStructureView.Trend.UP ? 1 : -1;
    EvalContext x = new EvalContext(idea, eng, liq, now, htf);
    List<ModelResult> results = Models.evaluateAll(x);
    ExecutionPlanner.Plan plan = ExecutionPlanner.plan(x, results, limits);
    if (eng.errorCount() > 0 && plan.go) { plan.go = false; plan.blocks.add("engine_error"); } // fail closed (Q6.46)
    StringBuilder models = new StringBuilder("[");
    for (ModelResult r : results) models.append(models.length() == 1 ? "" : ",").append(r.toJson());
    models.append(']');
    var s = x.activeSession();
    write(Json.object().field("type", "eval").field("t", now).field("idea", idea.id()).field("dir", idea.direction())
        .field("bar", eng.barCount()).field("close", eng.lastClosed() == null ? 0 : eng.lastClosed().close)
        .field("session", String.valueOf(eng.profiles().sessionKind()))
        .fieldOrNull("vwap", eng.vwap())
        .fieldOrNull("poc", s == null ? null : s.poc(c)).fieldOrNull("vah", s == null ? null : s.vah(c))
        .fieldOrNull("val", s == null ? null : s.val(c))
        .field("shape", s == null ? "NONE" : s.shape(now, c).name())
        .field("liquidity", liq.available())
        .fieldRaw("models", models.toString()).fieldRaw("plan", plan.toJson()).build());
    if (!plan.go) return none();

    long seq = intentSeq.incrementAndGet();
    pendingMutationSeq = seq;
    savedPhase = phase;
    savedDirection = positionDirection;
    savedStop = positionStopTicks;
    savedTarget = positionTargetTicks;
    savedSessionEntries = sessionEntries;
    savedZoneKey = idea.zoneKey();
    zoneEntries.merge(idea.zoneKey(), 1, Integer::sum);
    sessionEntries++;
    phase = Phase.IN_POSITION;
    positionDirection = idea.direction();
    positionStopTicks = plan.stopTicks;
    positionTargetTicks = plan.targetTicks;
    positionModel = plan.entryModel;
    positionIdeaId = idea.id();
    String reason = "of_entry model=" + plan.entryModel + " idea=" + idea.id() + " rr=" + String.format("%.2f", plan.rr)
        + " target=" + plan.targetLabel;
    write(Json.object().field("type", "entry").field("seq", seq).field("idea", idea.id()).field("model", plan.entryModel)
        .field("dir", positionDirection).field("entry", plan.entryTicks).field("stop", plan.stopTicks)
        .field("target", plan.targetTicks).field("rr", plan.rr).build());
    return new Intent(ID, seq, positionDirection * fixedContracts, positionStopTicks, positionTargetTicks, reason);
  }

  private Intent manage(int price, long now) {
    boolean stopHit = positionDirection > 0 ? price <= positionStopTicks : price >= positionStopTicks;
    boolean targetHit = positionDirection > 0 ? price >= positionTargetTicks : price <= positionTargetTicks;
    if (stopHit || targetHit) {
      long seq = intentSeq.incrementAndGet();
      pendingMutationSeq = seq;
      savedPhase = phase;
      savedDirection = positionDirection;
      savedStop = positionStopTicks;
      savedTarget = positionTargetTicks;
      savedSessionEntries = sessionEntries;
      savedZoneKey = null;
      String why = stopHit ? "stop_hit" : "target_hit";
      write(Json.object().field("type", "exit").field("seq", seq).field("idea", positionIdeaId == null ? "" : positionIdeaId)
          .field("model", positionModel == null ? "" : positionModel).field("reason", why).field("price", price)
          .field("t", now).build());
      phase = Phase.SEARCHING;
      positionDirection = 0;
      return new Intent(ID, seq, 0, null, null, why);
    }
    return new Intent(ID, intentSeq.incrementAndGet(), positionDirection * fixedContracts,
        positionStopTicks, positionTargetTicks, "holding");
  }

  private void write(String line) {
    if (log == null) return;
    try {
      log.write(line);
      log.write('\n');
      log.flush();
    } catch (Exception e) {
      log = null; // a logging failure must never affect trading
    }
  }
}
