package com.flow.rt;

import com.flow.core.BarEvent;
import com.flow.core.BarPhase;
import com.flow.core.Intent;
import com.flow.core.IntentSink;
import com.flow.core.Pipeline;
import com.flow.core.Sequencer;
import com.flow.core.StrategyConfig;
import com.flow.core.StrategyRegistry;
import com.flow.core.TickEvent;
import com.flow.core.Event;
import com.flow.strategies.StrategyRegistrations;
import com.flow.flow.ZoneView;
import com.flow.journal.Json;
import com.flow.journal.JournalWriter;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.DOM;
import com.motivewave.platform.sdk.common.DOMListener;
import com.motivewave.platform.sdk.common.DOMRow;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.common.Tick;
import com.motivewave.platform.sdk.common.TimeFrame;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.IntegerDescriptor;
import com.motivewave.platform.sdk.common.Coordinate;
import com.motivewave.platform.sdk.common.desc.StringDescriptor;
import com.motivewave.platform.sdk.draw.Box;
import com.motivewave.platform.sdk.draw.Label;
import com.motivewave.platform.sdk.draw.Line;
import com.motivewave.platform.sdk.draw.Marker;
import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The runtime (README "What this is"): one deployed Strategy hosting
 * interchangeable strategy plug-ins. Walking skeleton only -- registers
 * just NullStrategy, dry-run only (no order-submission code exists in
 * this class tree at all -- see OrderGateway), built to prove the
 * sequencer/journal/pipeline plumbing before any real feature.
 *
 * HARD RULE (../../CLAUDE.md, ../../motivewave/CLAUDE.md): every
 * OrderContext-taking hook inherited from Study is explicitly overridden
 * below with a no-op/log-only body -- see the 2026-09-11 incident
 * (onEnterNow places a real market order by default even with no order
 * call anywhere in the subclass). SafetyHookReflectionTest asserts this
 * list is complete against the actual jar, not from memory.
 *
 * StudyHeader flags match ../../motivewave/experiments/src/flow_diag/
 * FlowStrategySkeleton.java's already-live-validated combination exactly
 * (2026-09-14 session, pos=0/cash flat throughout) rather than
 * re-deriving them.
 */
@StudyHeader(
    namespace = "com.flow.rt",
    id = "FLOW_RUNTIME",
    name = "FLOW Runtime",
    label = "FLOW Runtime (walking skeleton, dry-run only)",
    desc = "Hosts FlowStrategy plug-ins. No order-submission code exists yet; armed has no effect.",
    menu = "FLOW",
    overlay = true,
    strategy = true,
    autoEntry = true,
    manualEntry = false,
    supportsPositionType = false,
    supportsEnterOnActivate = false,
    supportsCloseOnDeactivate = false,
    requiresBarUpdates = true,
    supportsBarUpdates = true,
    barUpdatesByDefault = true)
public class FlowRuntimeStudy extends Study implements DOMListener {
  private static final String STRATEGY_ID_KEY = "FLOW_STRATEGY_ID";
  // D-82/Q-11 (CLAUDE.md's second exception): ARMED_KEY + MODE_KEY=="SIM_LIVE"
  // together authorize automatic real order placement on the Simulated
  // account for the rest of this session, no per-order confirmation --
  // conditioned on the user having stated account/instrument/size-and-loss
  // bounds out loud and confirmed them immediately before flipping ARMED_KEY
  // on. See isLiveModeArmed()/reconcileLive() below.
  private static final String ARMED_KEY = "FLOW_ARMED";
  private static final String MODE_KEY = "FLOW_MODE";
  private static final String VP_RANGE_TICKS_KEY = "FLOW_VP_RANGE_TICKS";
  private static final String FP_RANGE_TICKS_KEY = "FLOW_FP_RANGE_TICKS";
  // Draw-time-only row merging (2026-09-18) -- never changes what
  // FootprintView computes or stores, only how many raw rows get grouped
  // into one displayed zone. See drawFootprintBar()'s javadoc.
  private static final String FP_MERGE_ROWS_KEY = "FLOW_FP_MERGE_ROWS";
  // Per-construct draw flags (2026-09-18): a construct is always
  // calculated and journaled regardless of this flag -- it only controls
  // whether redrawFigures() draws it. One flag per construct, checked in
  // one place (redrawFigures()'s dispatcher), so a new construct's own
  // drawing method never has to know or care whether any other
  // construct is currently shown.
  private static final String DRAW_VP_KEY = "FLOW_DRAW_VP";
  private static final String DRAW_FP_KEY = "FLOW_DRAW_FP";
  private static final String DRAW_BT_KEY = "FLOW_DRAW_BT";
  // D-59: heatmap test draw -- one colored Box per DOM row within the
  // window, blue (thin) -> red (thick) by resting size. Separate from
  // Pipeline's own DOM_SNAPSHOT_WINDOW_TICKS (journal snapshot width) --
  // this one only controls what gets drawn, cosmetic, no storage impact.
  private static final String DRAW_LM_KEY = "FLOW_DRAW_LM";
  private static final String LM_DRAW_WINDOW_TICKS_KEY = "FLOW_LM_DRAW_WINDOW_TICKS";
  // D-64: historical warm-start on activation, configurable per the
  // user's explicit request ("that 'last 100 bars' will be configurable").
  private static final String MS_WARMSTART_BARS_KEY = "FLOW_MS_WARMSTART_BARS";
  private static final String DRAW_MS_KEY = "FLOW_DRAW_MS";
  // Entry signal arrows (user request, 2026-09-19): drawn from
  // onIntentChanged, which only fires once the risk chain ALLOWS an
  // intent -- so with armed=false (the default) this stays empty; arrows
  // appear once the session is actually armed. Matches the "order
  // signals" framing of the original request, not "everything the
  // strategy considered."
  private static final String DRAW_ENTRY_KEY = "FLOW_DRAW_ENTRY_SIGNALS";
  // Big trades (D-53): built directly against our own TickEvent stream,
  // not a wrapper around AggregateFilter (that engine casts its Tick
  // argument to an internal concrete class -- crashed live the moment it
  // was tried, see D-53). Fixed threshold, no warmup (D-22 class 1). No
  // draw flag yet -- this pass is feature + log validation only, drawing
  // deferred the same way footprint's was (D-50 before D-51).
  private static final String BT_MIN_SIZE_KEY = "FLOW_BT_MIN_SIZE";
  // D-55: aggregation window, not an order-id key -- see BigTradeFeature's
  // javadoc for why (matches AggregateFilter's own aggPeriod semantics,
  // the mode the built-in "Big Trades" study evidently uses).
  private static final String BT_AGG_PERIOD_MS_KEY = "FLOW_BT_AGG_PERIOD_MS";
  private static final Path LOG_ROOT = Path.of("C:/yadvendra/trading/FLOW_V2/logs");
  // D-61: hand-edited by the user, runtime only ever reads it (README
  // "External inputs and config") -- not a secret like .env, so it's a
  // plain repo path, not gitignored.
  private static final Path RISK_CONFIG_PATH = Path.of("C:/yadvendra/trading/FLOW_V2/config/risk.json");

  private final int instanceId = System.identityHashCode(this);
  private final AtomicBoolean subscribed = new AtomicBoolean(false);

  private volatile Sequencer sequencer;
  private volatile Pipeline pipeline;
  private volatile JournalWriter journal;
  private volatile ScheduledExecutorService clockExecutor;
  private volatile PriceCodec priceCodec;
  private volatile OrderGateway gateway;
  private volatile Instrument instrument;
  private volatile SdkVolumeProfileFeature volumeProfile;
  private volatile SdkFootprintFeature footprint;
  private volatile com.flow.flow.BigTradeFeature bigTrades;
  private volatile BigTradeFileLogger bigTradeLogger;
  private volatile com.flow.flow.OrderRepeatFeature orderRepeats;
  private volatile OrderRepeatFileLogger orderRepeatLogger;
  private volatile com.flow.flow.VWAPFeature vwap;
  private volatile java.io.PrintWriter vwapLog;
  private volatile long lastVwapLogTime = 0;
  private static final long VWAP_LOG_INTERVAL_MS = 1000;
  // D-63: anchors update(DOM)'s own best-bid/ask against the last
  // genuinely traded price -- same fix as LiquidityMapFeature's.
  private volatile Integer lastTradedPriceTicks = null;
  // D-58: liquidity map, built from the live MBO DOM stream (DOMListener,
  // subscribed once the instrument is known -- same pattern already
  // proven in ../../motivewave/experiments/src/flow_diag/DomDetailCapture.java).
  private volatile com.flow.flow.LiquidityMapFeature liquidityMap;
  private volatile java.io.PrintWriter domLog;
  private volatile long lastDomLogTime = 0;
  private static final long DOM_LOG_INTERVAL_MS = 1000;
  // D-60: unreviewed rule resolutions, see docs/dynamic/marketStructureRulesTemp.md
  private volatile com.flow.flow.MarketStructureFeature marketStructure;
  private volatile MarketStructureFileLogger marketStructureLogger;
  // Entry signal arrows: appended from onIntentChanged (drain thread, via
  // IntentSink), read from maybeRedraw()'s MotiveWave-invoked thread --
  // same cross-thread read-of-recent-events pattern as BigTradeFeature's
  // recent(), so a plain synchronized list (bounded, oldest evicted) is
  // enough; no generation-guarded view needed for a cosmetic draw list.
  private record EntrySignal(long timeMs, double price, boolean isLong, String reason) {}
  private final java.util.List<EntrySignal> entrySignals =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());
  private static final int MAX_ENTRY_SIGNALS = 200;
  // Automatic real order routing (D-82/Q-11), generalizing the proven
  // one-shot test-trade pattern (D-67) to any ALLOWED intent instead of a
  // single hardcoded test. liveOrderInFlight blocks a second automatic
  // submission while the entry's fill hasn't resolved yet -- deliberately
  // conservative: a flip/resize while an order is still working is skipped
  // and journaled, not attempted (see reconcileLive()'s javadoc).
  private volatile boolean liveOrderInFlight = false;
  private volatile int pendingBracketTargetPosition = 0;
  private volatile Integer pendingBracketStopTicks = null;
  private volatile Integer pendingBracketTargetTicks = null;
  private volatile String pendingBracketReason = null;
  // 2026-09-21: a bracket leg belongs to the position that created it and
  // must never outlive it -- tracked by reference here rather than assumed
  // to self-cancel via broker-side OCO, which OrderContext's own method
  // list confirms doesn't exist at this SDK level. Cleared (and the
  // survivor cancelled) when one leg fills naturally (onOrderFilled) --
  // the ONLY path that closes a position now, since the same-day rework
  // removed reconcileLive's software-side close entirely (SL/TP is
  // bracket-only; see reconcileLive()'s closing-branch javadoc).
  private volatile com.motivewave.platform.sdk.order_mgmt.Order restingStopOrder = null;
  private volatile com.motivewave.platform.sdk.order_mgmt.Order restingTargetOrder = null;
  // Populated immediately before WE call cancelOrders() ourselves, so the
  // resulting onOrderCancelled callback for that same order id is
  // recognized as expected cleanup, not a broker-side anomaly worth
  // disarming over (exactly the false-positive the near-miss's disarm
  // reasoning hit -- it blamed the wrong order for a cancellation that
  // was actually this sweep).
  private final java.util.Set<String> selfCancelledOrderIds =
      java.util.Collections.synchronizedSet(new java.util.HashSet<>());
  // D-61: refuse-to-arm (D-24) overrides the raw ARMED_KEY setting --
  // set once in onActivate, never cleared for the life of this instance
  // (clearing the position/orders manually and reactivating creates a
  // fresh instance anyway, per the zombie-instance discussion elsewhere).
  private volatile boolean armDenied = false;
  private volatile long sessionStartMs;

  // Redraw throttling -- called from onTick, a MotiveWave-invoked
  // callback thread, never a spawned one (see VolumeProfileSnapshot's
  // javadoc for why that distinction matters).
  private static final long REDRAW_INTERVAL_MS = 1000;
  private volatile long lastRedrawTime = 0;

  @Override
  public void initialize(Defaults defaults) {
    var sd = createSD();
    var tab = sd.addTab("Runtime");
    var grp = tab.addGroup("Strategy");
    grp.addRow(new StringDescriptor(STRATEGY_ID_KEY, "Strategy Id", "level_zone_observer"));
    grp.addRow(new BooleanDescriptor(ARMED_KEY,
        "Armed (with Mode=SIM_LIVE: places real Sim orders automatically -- CLAUDE.md confirmation required before checking this)",
        false));
    grp.addRow(new StringDescriptor(MODE_KEY, "Mode (DRY_RUN or SIM_LIVE)", "DRY_RUN"));
    var vpGrp = tab.addGroup("Volume Profile");
    vpGrp.addRow(new IntegerDescriptor(VP_RANGE_TICKS_KEY, "Row Width (ticks)", 1, 1, 9999, 1));
    vpGrp.addRow(new BooleanDescriptor(DRAW_VP_KEY, "Draw on Chart", false));
    var fpGrp = tab.addGroup("Footprint");
    fpGrp.addRow(new IntegerDescriptor(FP_RANGE_TICKS_KEY, "Row Width (ticks)", 1, 1, 9999, 1));
    fpGrp.addRow(new IntegerDescriptor(FP_MERGE_ROWS_KEY, "Draw: Merge N Rows (display only, not stored)", 1, 1, 999, 1));
    fpGrp.addRow(new BooleanDescriptor(DRAW_FP_KEY, "Draw on Chart", true));
    var btGrp = tab.addGroup("Big Trades");
    btGrp.addRow(new IntegerDescriptor(BT_MIN_SIZE_KEY, "Min Size (contracts, fixed threshold)", 10, 1, 99999, 1));
    btGrp.addRow(new IntegerDescriptor(BT_AGG_PERIOD_MS_KEY, "Agg Period (ms, same price+side window)", 20, 0, 60000, 1));
    btGrp.addRow(new BooleanDescriptor(DRAW_BT_KEY, "Draw on Chart", true));
    var lmGrp = tab.addGroup("Liquidity Map");
    lmGrp.addRow(new IntegerDescriptor(LM_DRAW_WINDOW_TICKS_KEY, "Draw Window (ticks each side)", 50, 1, 500, 1));
    lmGrp.addRow(new BooleanDescriptor(DRAW_LM_KEY, "Draw on Chart", true));
    var msGrp = tab.addGroup("Market Structure");
    msGrp.addRow(new IntegerDescriptor(MS_WARMSTART_BARS_KEY, "Historical Warm-Start Bars", 100, 0, 5000, 1));
    msGrp.addRow(new BooleanDescriptor(DRAW_MS_KEY, "Draw on Chart", true));
    var entryGrp = tab.addGroup("Entry Signals");
    entryGrp.addRow(new BooleanDescriptor(DRAW_ENTRY_KEY, "Draw Arrows on Chart", true));
    createRD();
  }

  @Override
  protected void calculate(int index, DataContext ctx) {
    if (subscribed.compareAndSet(false, true)) {
      startSession(ctx);
    }
  }

  private void startSession(DataContext ctx) {
    instrument = ctx.getInstrument();
    priceCodec = new PriceCodec(instrument.getTickSize());

    String strategyId = getSettings().getString(STRATEGY_ID_KEY);
    StrategyRegistry registry = StrategyRegistrations.buildDefault();
    var strategy = registry.create(strategyId);

    long sessionStartMs = System.currentTimeMillis();
    this.sessionStartMs = sessionStartMs;
    Path sessionDir = LOG_ROOT.resolve(strategyId + "_" + sessionStartMs + "_inst" + instanceId);
    try {
      journal = new JournalWriter(sessionDir);
    } catch (IOException e) {
      error("FLOW_RUNTIME: failed to open journal at " + sessionDir + ": " + e.getMessage());
      return;
    }
    journal.start();
    journal.writeDecision(0, Json.object()
        .field("type", "session_header")
        .field("strategyId", strategyId)
        .field("symbol", instrument.getSymbol())
        .field("tickSize", instrument.getTickSize())
        .field("sessionStartMs", sessionStartMs)
        .field("instanceId", instanceId)
        .field("mode", "DRY_RUN")
        .build());

    // D-75: raw-log retention (com.flow.journal.LogRetention, flow-core
    // -- zero SDK dependency, tested synthetically there). decisions.jsonl
    // is NEVER touched here (D-15's own "long retention" tier, and
    // already exactly what the user called "the analysed data" -- price
    // trace, liquidity snapshots, etc. all live there, not in raw.jsonl).
    int prunedRawLogs = com.flow.journal.LogRetention.pruneOldRawLogs(
        LOG_ROOT, sessionStartMs, com.flow.journal.LogRetention.DEFAULT_RETENTION_MS);
    journal.writeDecision(0, Json.object()
        .field("type", "log_retention_pruned")
        .field("retentionHours", com.flow.journal.LogRetention.DEFAULT_RETENTION_MS / 3_600_000L)
        .field("rawJsonlFilesDeleted", prunedRawLogs)
        .build());

    int rangeTicks = getSettings().getInteger(VP_RANGE_TICKS_KEY);
    volumeProfile = new SdkVolumeProfileFeature(
        com.flow.flow.VolumeProfileView.FEATURE_ID, instrument, priceCodec, rangeTicks);
    int fpRangeTicks = getSettings().getInteger(FP_RANGE_TICKS_KEY);
    footprint = new SdkFootprintFeature(
        com.flow.flow.FootprintView.FEATURE_ID, instrument, priceCodec, fpRangeTicks);
    int btMinSize = getSettings().getInteger(BT_MIN_SIZE_KEY);
    int btAggPeriodMs = getSettings().getInteger(BT_AGG_PERIOD_MS_KEY);
    bigTradeLogger = new BigTradeFileLogger(priceCodec, btMinSize, btAggPeriodMs);
    bigTrades = new com.flow.flow.BigTradeFeature(
        com.flow.flow.BigTradeView.FEATURE_ID, btMinSize, btAggPeriodMs, bigTradeLogger);
    // D-56: order-id repeat tracking, kept alive alongside big trades (not
    // merged into it) for future iceberg/hidden-liquidity analysis -- see
    // OrderRepeatView's javadoc. No settings/drawing yet, log-only.
    orderRepeatLogger = new OrderRepeatFileLogger(priceCodec);
    orderRepeats = new com.flow.flow.OrderRepeatFeature(
        com.flow.flow.OrderRepeatView.FEATURE_ID, orderRepeatLogger);
    // D-57: zero SDK dependency, no rotation/settings needed -- see
    // VWAPFeature's javadoc. Log-only for now, same build-then-layer
    // discipline as every other construct's first pass.
    vwap = new com.flow.flow.VWAPFeature(com.flow.flow.VWAPView.FEATURE_ID, priceCodec::fromTicks);
    try {
      vwapLog = new java.io.PrintWriter(new java.io.FileWriter(
          "C:/yadvendra/trading/FLOW_V2/logs/vwap_feature.log", true));
      vwapLog.println("# feature start " + System.currentTimeMillis() + " id=vwap");
      vwapLog.flush();
    } catch (IOException e) {
      vwapLog = null;
    }
    // D-58: liquidity map, fed by the DOMListener subscription below.
    // Aggregate rows only, no per-order detail (deferred, see todo.md).
    liquidityMap = new com.flow.flow.LiquidityMapFeature(com.flow.flow.LiquidityMapView.FEATURE_ID);
    try {
      domLog = new java.io.PrintWriter(new java.io.FileWriter(
          "C:/yadvendra/trading/FLOW_V2/logs/liquidity_map_feature.log", true));
      domLog.println("# feature start " + System.currentTimeMillis() + " id=liquidity_map");
      domLog.flush();
    } catch (IOException e) {
      domLog = null;
    }
    // D-60: unreviewed rule resolutions -- see docs/dynamic/marketStructureRulesTemp.md.
    marketStructureLogger = new MarketStructureFileLogger(priceCodec);
    marketStructure = new com.flow.flow.MarketStructureFeature(
        com.flow.flow.MarketStructureView.FEATURE_ID, marketStructureLogger);
    // D-64: historical warm-start, per direct user request -- pulls the
    // last N closed bars from the SDK's own DataSeries and runs them
    // through the SAME state machine BEFORE any live event does, so
    // activating mid-session doesn't start trend/TJL tracking from a
    // blank slate. Bypasses Sequencer/Pipeline entirely (they don't
    // exist yet at this point in startSession(), and this is a one-time
    // bootstrap, not part of the live, replay-relevant event stream) --
    // synthetic BarEvents with negative seq numbers so they can never
    // collide with the real sequencer's own (which start at 1).
    int warmStartBars = getSettings().getInteger(MS_WARMSTART_BARS_KEY);
    DataSeries seriesForWarmStart = ctx.getDataSeries();
    int availableBars = seriesForWarmStart == null ? 0 : Math.max(0, seriesForWarmStart.size() - 1);
    int warmStartedCount = warmStartMarketStructure(ctx, warmStartBars, priceCodec, marketStructure);
    journal.writeDecision(0, Json.object()
        .field("type", "market_structure_warm_start")
        .field("requestedBars", warmStartBars)
        .field("availableBars", availableBars) // how much history the chart actually had, for comparison against actualBars
        .field("actualBars", warmStartedCount)
        .field("trendAfterWarmStart", marketStructure.trend().toString())
        .build());

    Map<String, com.flow.core.Feature> features = Map.of(
        com.flow.flow.VolumeProfileView.FEATURE_ID, volumeProfile,
        com.flow.flow.FootprintView.FEATURE_ID, footprint,
        com.flow.flow.BigTradeView.FEATURE_ID, bigTrades,
        com.flow.flow.OrderRepeatView.FEATURE_ID, orderRepeats,
        com.flow.flow.VWAPView.FEATURE_ID, vwap,
        com.flow.flow.LiquidityMapView.FEATURE_ID, liquidityMap,
        com.flow.flow.MarketStructureView.FEATURE_ID, marketStructure);

    // D-61: hand-edited, runtime-read-only (README "External inputs and
    // config") -- missing file falls back to ExternalConfig's own
    // conservative defaults rather than failing the session.
    com.flow.core.ExternalConfig riskConfig;
    try {
      riskConfig = com.flow.core.ExternalConfig.load(RISK_CONFIG_PATH);
    } catch (IOException e) {
      riskConfig = com.flow.core.ExternalConfig.empty();
      logLine("RISK_CONFIG_MISSING path=" + RISK_CONFIG_PATH + " -- using built-in defaults");
    }
    // D-62: strategy.onInit() now gets real values (fixedContracts, at
    // minimum) instead of an empty map -- StrategyConfig itself stays a
    // plain string map (README's own stated "typed accessors get added
    // once a real strategy needs them" plan), this is that first real need.
    strategy.onInit(new StrategyConfig(java.util.Map.of(
        "fixedContracts", String.valueOf(riskConfig.fixedContracts()),
        "maxContracts", String.valueOf(riskConfig.maxContracts()))));
    journal.writeDecision(0, Json.object()
        .field("type", "risk_config_loaded")
        .field("fixedContracts", riskConfig.fixedContracts())
        .field("maxContracts", riskConfig.maxContracts())
        .field("dailyLossLimitTicks", riskConfig.dailyLossLimitTicks())
        .field("rateLimitPerMinute", riskConfig.rateLimitPerMinute())
        .field("minDwellMs", riskConfig.minDwellMs())
        .field("maxReversalsPerSession", riskConfig.maxReversalsPerSession())
        .field("lagQueueDepthThreshold", riskConfig.lagQueueDepthThreshold())
        .field("lagProcessingMsThreshold", riskConfig.lagProcessingMsThreshold())
        .field("fileLastModifiedMs", riskConfig.fileLastModifiedMs())
        .field("sessionStartMs", sessionStartMs) // staleness: compare against fileLastModifiedMs (README "traceable... not silently assumed current")
        .build());
    com.flow.core.RiskChain riskChain = new com.flow.core.RiskChain(riskConfig);
    java.util.function.BooleanSupplier armedSupplier = () -> getSettings().getBoolean(ARMED_KEY) && !armDenied;
    java.util.function.IntSupplier queueDepthSupplier = () -> {
      Sequencer s = sequencer;
      return s == null ? 0 : s.queueDepth();
    };
    // Daily-loss kill switch (2026-09-21, user's explicit "no matter what"
    // requirement): a blunt, deliberate exception to every other real-order
    // path here being surgical (cancel by reference, close only what's
    // asked). Disarms first so no new automatic entry can race the flatten.
    java.util.function.Consumer<String> killSwitch = reason -> {
      armDenied = true;
      restingStopOrder = null;
      restingTargetOrder = null;
      clearPendingLiveOrder();
      OrderGateway gw = gateway;
      if (gw == null) {
        logLine("KILL_SWITCH_TRIGGERED reason=" + reason + " (no gateway -- nothing to flatten)");
        return;
      }
      logLine("KILL_SWITCH_TRIGGERED " + gw.cancelAllAndClose(reason));
    };

    IntentSink sink = this::onIntentChanged;
    pipeline = new Pipeline(strategy, journal, sink, features, priceCodec::fromTicks,
        riskChain, armedSupplier, queueDepthSupplier, killSwitch);
    sequencer = new Sequencer(pipeline::handle, pipeline);
    sequencer.start();

    clockExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "flow-runtime-clock-inst" + instanceId);
      t.setDaemon(true);
      return t;
    });
    clockExecutor.scheduleAtFixedRate(() -> {
      Sequencer s = sequencer;
      if (s != null) {
        long now = System.currentTimeMillis();
        s.publish((seq, et, rt) -> new com.flow.core.ClockEvent(seq, et, rt), now);
      }
    }, 100, 100, TimeUnit.MILLISECONDS);

    // Subscribed last, once the sequencer is already running -- update(DOM)
    // below no-ops safely if sequencer is still null (same defensive check
    // onTick() already has), but starting the subscription only after
    // everything else is ready avoids that race altogether rather than
    // just tolerating it.
    instrument.addListener(this);
    logLine("SUBSCRIBED_DOM symbol=" + instrument.getSymbol());

    logLine("SESSION_START strategyId=" + strategyId + " symbol=" + instrument.getSymbol());
  }

  private void onIntentChanged(Intent intent, Event triggeringEvent) {
    recordEntrySignal(intent, triggeringEvent);
    OrderGateway gw = gateway;
    if (gw == null) {
      journal.writeDecision(triggeringEvent.seq(), Json.object()
          .field("type", "reconcile_skipped")
          .field("reason", "OrderGateway not yet constructed (onActivate has not fired)")
          .field("seq", triggeringEvent.seq())
          .build());
      return;
    }
    String line = isLiveModeArmed() ? reconcileLive(intent, gw) : gw.reconcileDryRun(intent);
    journal.writeDecision(triggeringEvent.seq(), line);
  }

  /**
   * CLAUDE.md's second exception (D-82/Q-11): Mode=SIM_LIVE + armed
   * authorizes automatic real order placement, no per-order confirmation,
   * for the rest of this session -- Sim account only, bounded by the risk
   * chain. onIntentChanged is only ever invoked for an intent RiskChain has
   * already ALLOWED (Pipeline's own contract), so reaching this method at
   * all already means armed/session/readiness/daily-loss/size-cap/
   * rate-limit/churn/lag all passed for this intent.
   */
  private boolean isLiveModeArmed() {
    return "SIM_LIVE".equals(getSettings().getString(MODE_KEY))
        && getSettings().getBoolean(ARMED_KEY) && !armDenied;
  }

  /**
   * Real-order counterpart to OrderGateway.reconcileDryRun() -- diffs the
   * intent against the account's actual position (gw.currentPosition()),
   * not the strategy's own optimistic belief about it. Only handles
   * OPENING (flat -> non-flat): submits the minimal entry order, then
   * stashes the intent's stop/target so onOrderFilled() can attach the
   * bracket once the entry is confirmed filled -- same submit-entry-then-
   * bracket-on-fill sequence D-67/D-81 proved out, just driven by the
   * strategy's own intents instead of a hardcoded one-shot test.
   *
   * CLOSING (non-flat -> flat) is deliberately NOT acted on here anymore
   * (2026-09-21 rework, after two live near-misses in one session): the
   * real bracket is now the ONLY thing that ever closes a position on a
   * stop/target level, full stop. Before this rework, this method also
   * submitted a fresh close order whenever the strategy's own software-
   * side check independently decided the position should be flat --
   * which raced the real bracket doing the same job through a different
   * order entirely, and both incidents were exactly that race. The
   * strategy's "stop_hit"/"target_hit" intent is still computed and still
   * journaled (Pipeline's intent_changed record, unconditionally) for
   * audit, but it no longer causes any real action. See the closing
   * branch below for the exact reasoning.
   *
   * Also deliberately conservative on OPENING, same as before: a direct
   * flip or a resize while a bracket is still resting is skipped and
   * journaled rather than risking a new order stacked on state that
   * hasn't settled -- neither strategy shipped so far ever asks for that
   * (both go through flat first via their own SEARCHING/IN_POSITION state
   * machines), so this is a real, flagged scope limit, not a silent
   * shortcut.
   *
   * Known gap, flagged rather than silently ignored: a real fill is never
   * fed back to the strategy (FlowStrategy.onFill() has no caller anywhere
   * yet -- see its own placeholder note). If the broker rejects or cancels
   * the entry, this method disarms (see onOrderRejected/onOrderCancelled)
   * rather than let the strategy's own optimistic position belief diverge
   * from the real, still-flat account.
   */
  private String reconcileLive(Intent intent, OrderGateway gw) {
    if (liveOrderInFlight) {
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "previous real order still in flight (unresolved fill/reject/cancel)")
          .build();
    }
    int currentPosition = gw.currentPosition();
    int target = intent.targetPosition();
    if (target == currentPosition) {
      return Json.object().field("type", "reconcile_live_noop")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("currentPosition", currentPosition).build();
    }
    boolean opening = currentPosition == 0 && target != 0;
    boolean closing = currentPosition != 0 && target == 0;
    if (!opening && !closing) {
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "direct flip/resize (from " + currentPosition + " to " + target
              + ") not auto-handled -- see reconcileLive()'s javadoc")
          .build();
    }
    if (opening && gw.hasRestingOrders()) {
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "resting orders found on the account -- refusing to stack a new entry on them")
          .build();
    }

    if (closing) {
      // 2026-09-21 rework, after two live near-misses in one session: SL/TP
      // is bracket-only now, full stop -- the strategy's own software-side
      // stop/target re-check and the real resting bracket were two
      // independent things both trying to close the same position, and
      // both incidents were exactly that race. The strategy's
      // "stop_hit"/"target_hit" intent is kept for audit (already
      // journaled by Pipeline's intent_changed record regardless of what
      // this method does with it) but reconcileLive takes no real action
      // on it anymore -- no cancel, no close, nothing. The bracket is the
      // only thing that ever closes a position on a stop/target level.
      // Universal events (the daily-loss kill switch, D-85; a future
      // session-end auto-flatten) are a deliberately different case --
      // those bypass the strategy's intent stream entirely and sweep
      // everything unconditionally, which is unaffected by this change.
      return Json.object().field("type", "reconcile_live_skipped")
          .field("strategyId", intent.strategyId()).field("intentSeq", intent.seq())
          .field("reason", "sl/tp is bracket-only -- strategy's own close intent logged, not re-executed")
          .build();
    }

    int delta = target - currentPosition; // opening only, from here on
    liveOrderInFlight = true;
    pendingBracketTargetPosition = target;
    pendingBracketStopTicks = intent.stopPriceTicks();
    pendingBracketTargetTicks = intent.targetPriceTicks();
    pendingBracketReason = intent.reason();
    return gw.submitRealEntry(delta > 0, Math.abs(delta), intent.reason());
  }

  /** Clears in-flight/pending-bracket state -- shared by onOrderRejected/onOrderCancelled below. */
  private void clearPendingLiveOrder() {
    liveOrderInFlight = false;
    pendingBracketTargetPosition = 0;
    pendingBracketStopTicks = null;
    pendingBracketTargetTicks = null;
    pendingBracketReason = null;
  }

  /**
   * Only fires for intents the risk chain already ALLOWED (Pipeline only
   * calls the IntentSink on allowed changes -- see its javadoc) -- with
   * armed=false every attempt is blocked before it gets here, so this
   * list stays empty until the session is actually armed. Only records
   * entries (targetPosition != 0), not flattens, per the user's "at the
   * entry price" request.
   */
  private void recordEntrySignal(Intent intent, Event triggeringEvent) {
    if (intent.targetPosition() == 0) return;
    PriceCodec codec = priceCodec;
    Integer priceTicks = Event.priceOf(triggeringEvent);
    if (codec == null || priceTicks == null) return;
    entrySignals.add(new EntrySignal(triggeringEvent.eventTimeMs(), codec.fromTicks(priceTicks),
        intent.targetPosition() > 0, intent.reason()));
    synchronized (entrySignals) {
      while (entrySignals.size() > MAX_ENTRY_SIGNALS) entrySignals.remove(0);
    }
  }

  @Override
  public void onTick(DataContext ctx, Tick tick) {
    Sequencer s = sequencer;
    PriceCodec codec = priceCodec;
    if (s == null || codec == null) return;
    int priceTicks = codec.toTicks(tick.getPrice());
    int bidTicks = codec.toTicks(tick.getBidPrice());
    int askTicks = codec.toTicks(tick.getAskPrice());
    lastTradedPriceTicks = priceTicks; // D-63: reference for update(DOM)'s own best-bid/ask, same fix as LiquidityMapFeature's
    s.publish((seq, et, rt) -> new TickEvent(seq, et, rt, priceTicks, tick.getVolume(),
        tick.isAskTick(), bidTicks, askTicks, tick.getExchOrderId(), tick.getAggExchOrderId()), tick.getTime());
    maybeRedraw();
    maybeLogVwap();
  }

  /**
   * VWAPFeature (D-57) is pure/no-I/O by design (flow-core discipline) --
   * the runtime owns logging for it, same as every other construct's
   * live-validation log, just periodic instead of event-driven since VWAP
   * is one continuously-updating value rather than discrete occurrences.
   */
  private void maybeLogVwap() {
    java.io.PrintWriter w = vwapLog;
    com.flow.flow.VWAPFeature v = vwap;
    if (w == null || v == null) return;
    long now = System.currentTimeMillis();
    if (now - lastVwapLogTime < VWAP_LOG_INTERVAL_MS) return;
    lastVwapLogTime = now;
    Double value = v.vwap();
    w.println(now + " vwap=" + (value == null ? "null" : String.format("%.4f", value))
        + " totalVolume=" + v.totalVolume());
    w.flush();
  }

  /**
   * D-58: DOMListener callback -- a different MotiveWave-invoked thread
   * than onTick()'s (per README "The event stream"), not the sequencer's
   * drain thread either. Converts the full resting book into DomRows and
   * publishes one DomEvent through the sequencer, same single-writer
   * discipline as ticks/bars -- LiquidityMapFeature only ever mutates on
   * the drain thread, this callback just builds the immutable payload.
   *
   * No exchange timestamp is available from the SDK for a DOM update
   * (DOM/DOMListener.update() carry none, confirmed from the actual jar,
   * unlike Tick.getTime()) -- local receipt time is used for both
   * eventTimeMs and receiptTimeMs, an accepted limitation, not a
   * placeholder to fix later.
   */
  @Override
  public void update(DOM dom) {
    Sequencer s = sequencer;
    PriceCodec codec = priceCodec;
    if (s == null || codec == null) return;
    List<com.flow.core.DomRow> bidRows = convertDomRows(dom.getBidRows(), codec, false);
    List<com.flow.core.DomRow> askRows = convertDomRows(dom.getAskRows(), codec, true);
    Integer ref = lastTradedPriceTicks;
    int bestBidTicks = maxPriceAtOrBelow(bidRows, ref);
    double bestBidSize = sizeAtMax(bidRows, bestBidTicks);
    int bestAskTicks = minPriceAtOrAbove(askRows, ref);
    double bestAskSize = sizeAtMin(askRows, bestAskTicks);
    long now = System.currentTimeMillis();
    s.publish((seq, et, rt) -> new com.flow.core.DomEvent(seq, et, rt,
        bestBidTicks, bestBidSize, bestAskTicks, bestAskSize, bidRows, askRows), now);
    maybeLogDom(bidRows, askRows, bestBidTicks, bestAskTicks);
  }

  /**
   * D-63: `dom.getBidRows()`/`getAskRows()` trusted blindly to assign
   * every row to the right side -- this cross-checks each row's own
   * `isAsk()` flag against which list it came from and discards any row
   * that disagrees. Kept as defense-in-depth even though it turned out
   * NOT to be the cause of the actual bug found live (see
   * maxPriceAtOrBelow's javadoc): the one row responsible really was a
   * genuine, correctly-flagged resting order, just an unusual one.
   */
  @SuppressWarnings("unchecked") // DOM.getBidRows()/getAskRows() return a raw List in this jar -- same T-6-class mismatch as VolumeProfile.getRows()
  private static List<com.flow.core.DomRow> convertDomRows(java.util.List rawRows, PriceCodec codec, boolean expectAsk) {
    List<com.flow.core.DomRow> out = new java.util.ArrayList<>(rawRows.size());
    for (Object o : rawRows) {
      DOMRow row = (DOMRow) o;
      if (row.isAsk() != expectAsk) continue; // disagrees with which list it came from -- discard, don't trust the outer split alone
      out.add(new com.flow.core.DomRow(codec.toTicks(row.getPrice()), row.getSize()));
    }
    return List.copyOf(out); // immutable -- LiquidityMapFeature stores this reference directly, never copies it again
  }

  /**
   * D-63 bugfix, found live 2026-09-19 and root-caused (not just
   * patched blind): a genuine resting sell order was sitting ~$22 below
   * the actual `@GC` market for many minutes straight -- confirmed via
   * `DOMRow.isAsk()` agreeing with its list (not a misclassification)
   * and via the diagnostic log showing `bestBid` tracking price
   * normally while `bestAsk` stayed frozen at that one far price the
   * whole time. "The lowest ask anywhere in the ~900-row book" isn't a
   * meaningful "best ask" regardless of why that one row exists.
   *
   * Same fix as `LiquidityMapFeature.maxPriceAtOrBelow`/
   * `minPriceAtOrAbove` (that class's javadoc has the fuller writeup,
   * including why an earlier attempt -- filtering each side against the
   * other side's own raw extreme -- turned out fragile): anchor against
   * the last genuinely TRADED price instead, which is authoritative in
   * a way resting orders aren't. `ref == null` (no trade seen yet) or
   * filtering removing every row both fall back to the plain extreme.
   */
  private static int maxPriceAtOrBelow(List<com.flow.core.DomRow> rows, Integer ref) {
    Integer max = null;
    for (com.flow.core.DomRow r : rows) {
      if (ref != null && r.priceTicks() > ref) continue;
      if (max == null || r.priceTicks() > max) max = r.priceTicks();
    }
    if (max != null) return max;
    return plainMax(rows);
  }

  private static int minPriceAtOrAbove(List<com.flow.core.DomRow> rows, Integer ref) {
    Integer min = null;
    for (com.flow.core.DomRow r : rows) {
      if (ref != null && r.priceTicks() < ref) continue;
      if (min == null || r.priceTicks() < min) min = r.priceTicks();
    }
    if (min != null) return min;
    return plainMin(rows);
  }

  private static int plainMax(List<com.flow.core.DomRow> rows) {
    int max = 0;
    boolean any = false;
    for (com.flow.core.DomRow r : rows) {
      if (!any || r.priceTicks() > max) { max = r.priceTicks(); any = true; }
    }
    return max;
  }

  private static int plainMin(List<com.flow.core.DomRow> rows) {
    int min = 0;
    boolean any = false;
    for (com.flow.core.DomRow r : rows) {
      if (!any || r.priceTicks() < min) { min = r.priceTicks(); any = true; }
    }
    return min;
  }

  private static double sizeAtMax(List<com.flow.core.DomRow> rows, int priceTicks) {
    for (com.flow.core.DomRow r : rows) if (r.priceTicks() == priceTicks) return r.size();
    return 0;
  }

  private static double sizeAtMin(List<com.flow.core.DomRow> rows, int priceTicks) {
    return sizeAtMax(rows, priceTicks);
  }

  /** Diagnostic-only live-validation log, same discipline as every other construct's first pass. */
  private void maybeLogDom(List<com.flow.core.DomRow> bidRows, List<com.flow.core.DomRow> askRows,
                            int bestBidTicks, int bestAskTicks) {
    java.io.PrintWriter w = domLog;
    PriceCodec codec = priceCodec;
    if (w == null || codec == null) return;
    long now = System.currentTimeMillis();
    if (now - lastDomLogTime < DOM_LOG_INTERVAL_MS) return;
    lastDomLogTime = now;
    w.println(now + " bidRowCount=" + bidRows.size() + " askRowCount=" + askRows.size()
        + " bestBid=" + String.format("%.4f", codec.fromTicks(bestBidTicks))
        + " bestAsk=" + String.format("%.4f", codec.fromTicks(bestAskTicks)));
    w.flush();
  }

  // Drawing, for visual comparison against the chart's built-in Volume
  // Profile study (same purpose as E-2). Called from onTick -- a
  // MotiveWave-invoked callback thread -- never from the sequencer's own
  // drain thread; see VolumeProfileSnapshot's javadoc for why that
  // distinction is load-bearing here, not stylistic. Uses the plain
  // clearFigures()/addFigure(Figure) overloads, called synchronously,
  // matching the one pattern already confirmed to work
  // (../../motivewave/docs/dynamic/findings.md).
  private void maybeRedraw() {
    long now = System.currentTimeMillis();
    if (now - lastRedrawTime < REDRAW_INTERVAL_MS) return;
    lastRedrawTime = now;
    redrawFigures();
  }

  /**
   * The one shared dispatcher: clears every figure this Study has drawn,
   * then redraws whichever constructs the user has asked to see, each in
   * its own method. Each construct's draw flag (settings.getBoolean) is
   * checked here, once, rather than each drawing method deciding for
   * itself whether it should run -- so adding a fourth/fifth construct
   * later is "add a flag + a redrawXFigures() method + one line here,"
   * not a change to how any existing construct's drawing works.
   */
  private void redrawFigures() {
    clearFigures();
    if (getSettings().getBoolean(DRAW_VP_KEY)) redrawVolumeProfileFigures();
    if (getSettings().getBoolean(DRAW_FP_KEY)) redrawFootprintFigures();
    if (getSettings().getBoolean(DRAW_BT_KEY)) redrawBigTradeFigures();
    if (getSettings().getBoolean(DRAW_LM_KEY)) redrawLiquidityMapFigures();
    if (getSettings().getBoolean(DRAW_MS_KEY)) redrawMarketStructureFigures();
    if (getSettings().getBoolean(DRAW_ENTRY_KEY)) redrawEntrySignalFigures();
  }

  /**
   * One arrow Marker per recorded entry signal (bounded list, see
   * entrySignals' javadoc) -- up/green for long, down/red for short,
   * positioned at the exact price the triggering event carried (D-59-era
   * convention: a historical stamp, not a "current state" redraw).
   */
  private void redrawEntrySignalFigures() {
    java.util.List<EntrySignal> snapshot;
    synchronized (entrySignals) {
      snapshot = new java.util.ArrayList<>(entrySignals);
    }
    for (EntrySignal sig : snapshot) {
      Color color = sig.isLong() ? new Color(0, 200, 0) : new Color(220, 60, 60);
      Marker marker = MarkerAdapter.arrow(sig.timeMs(), sig.price(), color, sig.isLong());
      marker.setTextValue(sig.isLong() ? "B" : "S");
      addFigure(marker);
    }
  }

  /**
   * All reads here go through VolumeProfileSnapshot (volatile-published)
   * or this Study's own settings/final fields -- never SdkVolumeProfileFeature's
   * mutable poc()/vah()/zones() directly, which are drain-thread-only.
   * See VolumeProfileSnapshot's javadoc.
   *
   * Labeling rule (user-specified, 2026-09-16): POC is the zero
   * reference. A level/zone's number is its row/bucket distance from
   * POC's row -- positive above, negative below, magnitude growing with
   * distance. A zone whose range contains VAH or VAL takes that level's
   * own number exactly (forced equal, not independently computed and
   * coincidentally close) rather than its own midpoint-based one.
   */
  private void redrawVolumeProfileFigures() {
    SdkVolumeProfileFeature vp = volumeProfile;
    PriceCodec codec = priceCodec;
    if (vp == null || codec == null) return;
    VolumeProfileSnapshot snap = vp.snapshot();
    if (snap.poc() == null) return;

    int rangeTicks = getSettings().getInteger(VP_RANGE_TICKS_KEY);
    int pocTicks = snap.poc();

    long start = sessionStartMs;
    long now = System.currentTimeMillis();

    addFigure(makeLine(start, codec.fromTicks(pocTicks), now, codec.fromTicks(pocTicks),
        Color.YELLOW, "FLOW POC " + formatRelative(0) + " (" + String.format("%.4f", codec.fromTicks(pocTicks)) + ")"));

    Integer vahRow = null;
    Integer valRow = null;
    if (snap.vah() != null) {
      vahRow = rowsFromPoc(snap.vah(), pocTicks, rangeTicks);
      addFigure(makeLine(start, codec.fromTicks(snap.vah()), now, codec.fromTicks(snap.vah()),
          Color.CYAN, "FLOW VAH " + formatRelative(vahRow) + " (" + String.format("%.4f", codec.fromTicks(snap.vah())) + ")"));
    }
    if (snap.val() != null) {
      valRow = rowsFromPoc(snap.val(), pocTicks, rangeTicks);
      addFigure(makeLine(start, codec.fromTicks(snap.val()), now, codec.fromTicks(snap.val()),
          Color.CYAN, "FLOW VAL " + formatRelative(valRow) + " (" + String.format("%.4f", codec.fromTicks(snap.val())) + ")"));
    }

    for (ZoneView z : snap.zones()) {
      Color fill = z.kind() == ZoneView.Kind.LVN
          ? new Color(255, 140, 0, 70)   // translucent orange, matches the earlier LVN probe
          : new Color(0, 200, 0, 60);    // translucent green for HVN
      double lo = codec.fromTicks(z.lowPriceTicks());
      double hi = codec.fromTicks(z.highPriceTicks()) + instrument.getTickSize(); // cover the row's own width
      Box box = new Box(start, lo, now, hi);
      box.setFillColor(fill);
      box.setLineColor(fill);
      addFigure(box);

      int row;
      if (vahRow != null && z.lowPriceTicks() <= snap.vah() && snap.vah() <= z.highPriceTicks()) {
        row = vahRow;
      } else if (valRow != null && z.lowPriceTicks() <= snap.val() && snap.val() <= z.highPriceTicks()) {
        row = valRow;
      } else {
        int midTicks = Math.round((z.lowPriceTicks() + z.highPriceTicks()) / 2.0f);
        row = rowsFromPoc(midTicks, pocTicks, rangeTicks);
      }

      Label label = new Label(new Coordinate(now, (lo + hi) / 2.0), z.kind() + " " + formatRelative(row));
      label.setLineColor(fill);
      addFigure(label);
    }
  }

  /**
   * Directly over the candles: each row sits at the bar's own time
   * midpoint (not "now" or session start, unlike the volume profile
   * lines above), so it visually lines up with that bar's own candle.
   * Draws the last CLOSED bar and the CURRENT (still forming) bar only --
   * FootprintView doesn't keep a longer history than that (D-50's stated
   * scope), so there's nothing further back to draw yet.
   */
  private void redrawFootprintFigures() {
    SdkFootprintFeature fp = footprint;
    PriceCodec codec = priceCodec;
    if (fp == null || codec == null) return;
    FootprintSnapshot snap = fp.snapshot();
    int mergeRows = Math.max(1, getSettings().getInteger(FP_MERGE_ROWS_KEY));
    int rawRangeTicks = getSettings().getInteger(FP_RANGE_TICKS_KEY);
    drawFootprintBar(snap.lastClosed(), codec, mergeRows, rawRangeTicks);
    drawFootprintBar(snap.current(), codec, mergeRows, rawRangeTicks);
  }

  /**
   * Merge is draw-time only (user, 2026-09-18): the raw per-tick rows
   * from FootprintView are never touched or re-stored at reduced
   * granularity -- this method reads them, groups them into wider price
   * buckets purely for this redraw, and throws the grouping away.
   * Gridded by price (bucketLow = floor(priceTicks / mergeSpan) *
   * mergeSpan), not by row index/count -- a row that never traded at some
   * tick still correctly contributes zero to its bucket, rather than the
   * grouping shifting if rows are missing because nothing traded there.
   * mergeRows=1 collapses to one bucket per raw row, so this is the only
   * drawing code path regardless of the setting -- no special case.
   */
  private void drawFootprintBar(com.flow.flow.BarFootprint bar, PriceCodec codec, int mergeRows, int rawRangeTicks) {
    if (bar.rows().isEmpty()) return;
    int mergeSpan = rawRangeTicks * mergeRows;
    java.util.TreeMap<Integer, double[]> merged = new java.util.TreeMap<>();
    for (com.flow.flow.FootprintRow r : bar.rows()) {
      int bucketLow = Math.floorDiv(r.priceTicks(), mergeSpan) * mergeSpan;
      double[] acc = merged.computeIfAbsent(bucketLow, k -> new double[2]);
      acc[0] += r.bidVolume();
      acc[1] += r.askVolume();
    }

    long mid = (bar.startMs() + bar.endMs()) / 2;
    for (var e : merged.entrySet()) {
      int lowTicks = e.getKey();
      int highTicks = lowTicks + mergeSpan - rawRangeTicks; // inclusive high tick of this bucket
      double bid = e.getValue()[0];
      double ask = e.getValue()[1];
      double delta = ask - bid;
      Color color = delta > 0 ? new Color(0, 200, 0) : delta < 0 ? new Color(220, 60, 60) : Color.LIGHT_GRAY;

      double lo = codec.fromTicks(lowTicks);
      double hi = codec.fromTicks(highTicks) + instrument.getTickSize(); // cover the top row's own width
      if (mergeRows > 1) {
        // A merged bucket is a genuine zone (low/high), not one price point --
        // draw the box so that's visible, same pattern as the LVN/HVN zones.
        Box box = new Box(mid - 1, lo, mid + 1, hi);
        Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 50);
        box.setFillColor(fill);
        box.setLineColor(color);
        addFigure(box);
      }

      String text = String.format("%.0fx%.0f", bid, ask);
      Label label = new Label(new Coordinate(mid, (lo + hi) / 2.0), text);
      label.setLineColor(color);
      addFigure(label);
    }
  }

  /**
   * One Marker circle per big trade in BigTradeView.recent() (D-54),
   * positioned at the trade's own eventTimeMs/price -- a historical stamp
   * on the chart, not a "current state" redraw the way VP/footprint are
   * (a big trade has no ongoing state to reflect, just a point it
   * occurred at). Green = buy aggressor (isAskTick, "+ve"), red = sell
   * aggressor ("-ve") -- same delta-sign color convention as
   * drawFootprintBar(). Text is the contract size, per the user's
   * explicit request (2026-09-18), not just a bare shape.
   */
  private void redrawBigTradeFigures() {
    com.flow.flow.BigTradeFeature bt = bigTrades;
    PriceCodec codec = priceCodec;
    if (bt == null || codec == null) return;
    for (com.flow.flow.BigTradeEvent t : bt.recent()) {
      double price = codec.fromTicks(t.priceTicks());
      Color color = t.isAskTick() ? new Color(0, 200, 0) : new Color(220, 60, 60);
      Marker marker = MarkerAdapter.circle(t.eventTimeMs(), price, color);
      marker.setTextValue(String.format("%.0f", t.size()));
      addFigure(marker);
    }
  }

  /**
   * D-59, test drawing per direct request: one colored Box per DOM row
   * within the draw window (bid and ask both read through the SAME
   * gradient, not separate color families -- "according to the resting
   * order... on both sides", not by side). Positioned as a thin trailing
   * column at "now", not spread across history -- there is no historical
   * per-row data to draw beyond the live state (D-58: raw DOM updates are
   * never persisted, only periodic bounded snapshots), so a scrolling
   * Bookmap-style heatmap across time is not attempted here.
   *
   * Normalization is a plain linear min-max across the combined bid+ask
   * window at each redraw -- simplest defensible v1 for a first visual
   * test. A single very large resting order would compress everything
   * else toward the cold end; not addressed here, revisit from what the
   * live picture actually looks like rather than guessing a fix now.
   */
  private void redrawLiquidityMapFigures() {
    com.flow.flow.LiquidityMapFeature lm = liquidityMap;
    PriceCodec codec = priceCodec;
    if (lm == null || codec == null) return;
    int windowTicks = getSettings().getInteger(LM_DRAW_WINDOW_TICKS_KEY);
    List<com.flow.core.DomRow> bidRows = lm.bidRowsWithin(windowTicks);
    List<com.flow.core.DomRow> askRows = lm.askRowsWithin(windowTicks);
    if (bidRows.isEmpty() && askRows.isEmpty()) return;

    double minSize = Double.MAX_VALUE, maxSize = -Double.MAX_VALUE;
    for (var r : bidRows) { minSize = Math.min(minSize, r.size()); maxSize = Math.max(maxSize, r.size()); }
    for (var r : askRows) { minSize = Math.min(minSize, r.size()); maxSize = Math.max(maxSize, r.size()); }

    long now = System.currentTimeMillis();
    drawHeatRows(bidRows, codec, minSize, maxSize, now);
    drawHeatRows(askRows, codec, minSize, maxSize, now);
  }

  private void drawHeatRows(List<com.flow.core.DomRow> rows, PriceCodec codec, double minSize, double maxSize, long now) {
    long columnHalfWidthMs = 15_000; // arbitrary v1 visual width, not tied to bar interval
    for (com.flow.core.DomRow r : rows) {
      double t = maxSize > minSize ? (r.size() - minSize) / (maxSize - minSize) : 0.5;
      Color color = heatColor(t);
      double lo = codec.fromTicks(r.priceTicks());
      double hi = lo + instrument.getTickSize();
      Box box = new Box(now - columnHalfWidthMs, lo, now, hi);
      box.setFillColor(color);
      box.setLineColor(color);
      addFigure(box);
    }
  }

  /**
   * Deep blue -> light blue -> white -> yellow -> orange -> red -> deep
   * red, per the user's exact spec (2026-09-18). t in [0,1], clamped;
   * piecewise-linear interpolation between the 7 stops.
   */
  private static Color heatColor(double t) {
    t = Math.max(0, Math.min(1, t));
    Color[] stops = {
        new Color(0, 0, 139),     // deep blue
        new Color(173, 216, 230), // light blue
        new Color(255, 255, 255), // white
        new Color(255, 255, 0),   // yellow
        new Color(255, 165, 0),   // orange
        new Color(255, 0, 0),     // red
        new Color(139, 0, 0)      // deep red
    };
    double scaled = t * (stops.length - 1);
    int idx = (int) Math.floor(scaled);
    if (idx >= stops.length - 1) return stops[stops.length - 1];
    double frac = scaled - idx;
    Color a = stops[idx], b = stops[idx + 1];
    int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * frac);
    int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * frac);
    int bch = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * frac);
    return new Color(r, g, bch);
  }

  /**
   * D-64, reworked D-74: draws the current TJL1/TJL2/A+/SBR-RBS/DT/DB
   * zones per marketStructureRules.md §8's color scheme. DT and DB are
   * now separate fields (`lastDt()`/`lastDb()`, D-74 -- the reviewed
   * rules require both to persist simultaneously during a chain of
   * pair-less flips, §6 points 9/10) rather than one shared slot
   * relabeled by current trend, so both get drawn directly, whichever
   * are non-null; SBR-vs-RBS is still the one field
   * (`lastSbrRbs()`) relabeled by current trend, since only one of the
   * two is ever meaningful at a time (point 9: A+/SBR-RBS are tradeable
   * for exactly one flip, never both directions at once). Non-tradeable
   * zones (per `tradeableLevels()`) are still drawn, just dimmer --
   * matches the getters' own "still returns the last value even when
   * not tradeable" contract (`MarketStructureView`'s javadoc) rather
   * than disappearing outright, so a chart-watcher can see what a level
   * WAS without the drawing implying it's currently actionable. Zones
   * drawn from session start to now, same convention
   * `redrawVolumeProfileFigures()` already uses for POC/VAH/VAL, since a
   * TJL zone has no historical anchor time of its own currently exposed
   * (`ZoneRange` is price-only).
   */
  private void redrawMarketStructureFigures() {
    com.flow.flow.MarketStructureFeature ms = marketStructure;
    PriceCodec codec = priceCodec;
    if (ms == null || codec == null || !ms.isReady()) return;
    long start = sessionStartMs;
    long now = System.currentTimeMillis();
    boolean down = ms.trend() == com.flow.flow.MarketStructureView.Trend.DOWN;
    java.util.Set<com.flow.flow.MarketStructureView.TradeableLevel> tradeable = ms.tradeableLevels();

    drawMsZone(ms.lastTjl1(), codec, start, now, new Color(0, 100, 255), "TJL1", true);
    drawMsZone(ms.lastTjl2(), codec, start, now, new Color(255, 140, 0), "TJL2",
        tradeable.contains(com.flow.flow.MarketStructureView.TradeableLevel.TJL2));
    drawMsZone(ms.lastAPlus(), codec, start, now, new Color(160, 32, 240), "A+",
        tradeable.contains(com.flow.flow.MarketStructureView.TradeableLevel.A_PLUS));
    drawMsZone(ms.lastSbrRbs(), codec, start, now, Color.GRAY, down ? "SBR" : "RBS",
        tradeable.contains(com.flow.flow.MarketStructureView.TradeableLevel.SBR_RBS));
    drawMsZone(ms.lastDt(), codec, start, now, Color.YELLOW, "DT",
        tradeable.contains(com.flow.flow.MarketStructureView.TradeableLevel.DT));
    drawMsZone(ms.lastDb(), codec, start, now, Color.YELLOW, "DB",
        tradeable.contains(com.flow.flow.MarketStructureView.TradeableLevel.DB));

    com.flow.flow.ZoneRange anchor = ms.lastTjl2();
    if (anchor != null) {
      double y = codec.fromTicks(anchor.highTicks()) + instrument.getTickSize() * 4;
      Label trendLabel = new Label(new Coordinate(now, y), "Trend: " + ms.trend());
      trendLabel.setLineColor(down ? new Color(220, 60, 60) : new Color(0, 200, 0));
      addFigure(trendLabel);
    }
  }

  private void drawMsZone(com.flow.flow.ZoneRange zone, PriceCodec codec, long start, long now, Color color,
                           String label, boolean tradeable) {
    if (zone == null) return;
    double lo = codec.fromTicks(zone.lowTicks());
    double hi = codec.fromTicks(zone.highTicks()) + instrument.getTickSize();
    Box box = new Box(start, lo, now, hi);
    Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), tradeable ? 50 : 15);
    box.setFillColor(fill);
    box.setLineColor(tradeable ? color : new Color(color.getRed(), color.getGreen(), color.getBlue(), 90));
    addFigure(box);
    Label labelFigure = new Label(new Coordinate(now, (lo + hi) / 2.0), tradeable ? label : label + " (inactive)");
    labelFigure.setLineColor(color);
    addFigure(labelFigure);
  }

  private static int rowsFromPoc(int levelTicks, int pocTicks, int rangeTicks) {
    return Math.round((levelTicks - pocTicks) / (float) rangeTicks);
  }

  private static String formatRelative(int row) {
    if (row == 0) return "0";
    return row > 0 ? "+" + row : String.valueOf(row);
  }

  private Line makeLine(long startTime, double startValue, long endTime, double endValue, Color color, String label) {
    Line line = new Line(startTime, startValue, endTime, endValue);
    line.setColor(color);
    line.setExtendRightBounds(true);
    line.setText(label, new Font("Dialog", Font.PLAIN, 11));
    return line;
  }

  @Override
  public void onBarClose(DataContext ctx) {
    Sequencer s = sequencer;
    PriceCodec codec = priceCodec;
    if (s == null || codec == null) return;
    DataSeries series = ctx.getDataSeries();
    int idx = series.size() - 1;
    int openTicks = codec.toTicks(series.getOpen(idx));
    int highTicks = codec.toTicks(series.getHigh(idx));
    int lowTicks = codec.toTicks(series.getLow(idx));
    int closeTicks = codec.toTicks(series.getClose(idx));
    long volume = (long) series.getVolume(idx);
    long endTime = series.getEndTime(idx);
    s.publish((seq, et, rt) -> new BarEvent(seq, et, rt, BarPhase.CLOSE,
        openTicks, highTicks, lowTicks, closeTicks, volume), endTime);
  }

  /**
   * D-64: feeds up to `maxBars` closed historical bars (oldest first)
   * through `MarketStructureFeature` directly, before any live event
   * does.
   *
   * Deliberately does NOT use `DataSeries.isComplete()` to find the
   * boundary -- confirmed live it returns `false` even for a bar from
   * many minutes in the past on this jar, so it can't distinguish
   * "still forming" from "closed long ago" here (a T-6-class jar
   * quirk, not chased further since a simpler rule works). Instead:
   * the series' own last index is unconditionally treated as "current/
   * forming" and excluded, exactly matching how the *live*
   * `onBarClose(DataContext)` handler already behaves (it never checks
   * `isComplete()` either -- it trusts its own callback timing and
   * always uses `size()-1` as "the bar that just closed"). This also
   * guarantees zero overlap with future live bar-close events, which
   * will only ever fire for indices at or beyond the current `size()-1`
   * as the series keeps extending.
   *
   * Returns however many bars were actually fed, which may be less
   * than requested if the chart doesn't have that much history loaded
   * yet -- journaled by the caller so that's traceable, not silently
   * assumed to match the setting.
   */
  private static int warmStartMarketStructure(DataContext ctx, int maxBars, PriceCodec codec,
                                                com.flow.flow.MarketStructureFeature marketStructure) {
    if (maxBars <= 0) return 0;
    DataSeries series = ctx.getDataSeries();
    if (series == null || series.size() < 2) return 0;
    int lastIdx = series.size() - 2; // size()-1 is always "current/forming", excluded
    int startIdx = Math.max(0, lastIdx - maxBars + 1);
    long syntheticSeq = -1_000_000L; // negative range, never collides with the real Sequencer's own (starts at 1)
    int count = 0;
    for (int idx = startIdx; idx <= lastIdx; idx++) {
      int openTicks = codec.toTicks(series.getOpen(idx));
      int highTicks = codec.toTicks(series.getHigh(idx));
      int lowTicks = codec.toTicks(series.getLow(idx));
      int closeTicks = codec.toTicks(series.getClose(idx));
      long volume = (long) series.getVolume(idx);
      long endTime = series.getEndTime(idx);
      BarEvent be = new BarEvent(syntheticSeq--, endTime, endTime, BarPhase.CLOSE,
          openTicks, highTicks, lowTicks, closeTicks, volume);
      marketStructure.onEvent(be);
      count++;
    }
    return count;
  }

  @Override
  public void destroy() {
    logLine("DESTROY instance=" + instanceId);
    ScheduledExecutorService ce = clockExecutor;
    if (ce != null) {
      ce.shutdownNow();
      clockExecutor = null;
    }
    Sequencer s = sequencer;
    if (s != null) {
      s.stop();
      sequencer = null;
    }
    JournalWriter j = journal;
    if (j != null) {
      j.flushAndClose();
      journal = null;
    }
    SdkVolumeProfileFeature vp = volumeProfile;
    if (vp != null) {
      vp.closeLog();
      volumeProfile = null;
    }
    SdkFootprintFeature fp = footprint;
    if (fp != null) {
      fp.closeLog();
      footprint = null;
    }
    bigTrades = null;
    BigTradeFileLogger btLog = bigTradeLogger;
    if (btLog != null) {
      btLog.closeLog();
      bigTradeLogger = null;
    }
    orderRepeats = null;
    OrderRepeatFileLogger orLog = orderRepeatLogger;
    if (orLog != null) {
      orLog.closeLog();
      orderRepeatLogger = null;
    }
    vwap = null;
    java.io.PrintWriter vwLog = vwapLog;
    if (vwLog != null) {
      vwLog.flush();
      vwLog.close();
      vwapLog = null;
    }
    Instrument instr = instrument;
    if (instr != null) {
      instr.removeListener(this); // matches SdkCapabilityProbe.java's proven pattern -- an un-removed listener keeps a "removed" instance running as a DOM-fed zombie
    }
    liquidityMap = null;
    java.io.PrintWriter dLog = domLog;
    if (dLog != null) {
      dLog.flush();
      dLog.close();
      domLog = null;
    }
    marketStructure = null;
    MarketStructureFileLogger msLog = marketStructureLogger;
    if (msLog != null) {
      msLog.closeLog();
      marketStructureLogger = null;
    }
  }

  // ------------------------------------------------------------------
  // Every OrderContext-taking hook inherited from Study, explicitly
  // overridden. SafetyHookReflectionTest asserts this set is complete
  // against the jar. None of these call buy/sell/createXOrder/
  // submitOrders/cancelOrders/closeAtMarket -- confirmed by inspection,
  // not by omission (the 2026-09-11 incident was exactly "omission looks
  // safe but isn't").
  // ------------------------------------------------------------------

  @Override
  public void onActivate(OrderContext ctx) {
    gateway = new OrderGateway(ctx);
    logLine("ACTIVATE pos=" + ctx.getPosition() + " cash=" + ctx.getCashBalance()
        + " -- CONFIRM: is this the Simulated account? (Sim Trade Only must stay enabled)");

    String refuseReason = gateway.refuseToArmReason();
    if (refuseReason != null) {
      armDenied = true;
      logLine("REFUSE_TO_ARM " + refuseReason);
    } else {
      armDenied = false;
    }
  }

  @Override
  public void onDeactivate(OrderContext ctx) {
    logLine("DEACTIVATE pos=" + ctx.getPosition());
    gateway = null;
  }

  @Override
  public void onEnterNow(OrderContext ctx) {
    logLine("ENTER_NOW_IGNORED (no-op override; base class default places a market order)");
  }

  @Override
  public void onSignal(OrderContext ctx, Object signal) {
    logLine("SIGNAL_IGNORED " + signal);
  }

  @Override
  public void onSessionStarted(OrderContext ctx, TimeFrame session) {
    logLine("SESSION_STARTED " + session);
  }

  @Override
  public void onSessionEnded(OrderContext ctx, TimeFrame session) {
    logLine("SESSION_ENDED " + session);
  }

  @Override
  public void onBarOpen(OrderContext ctx) {
    // market-data bar events come from onBarClose(DataContext) above; this
    // overload exists only to be safely no-op'd, per the hard rule.
  }

  @Override
  public void onBarUpdate(OrderContext ctx) {
    // see onBarOpen(OrderContext) note
  }

  @Override
  public void onBarClose(OrderContext ctx) {
    // see onBarOpen(OrderContext) note -- market data path is
    // onBarClose(DataContext), a different overload, handled above.
  }

  @Override
  public void onReset(OrderContext ctx) {
    logLine("RESET");
  }

  @Override
  public void onPositionClosed(OrderContext ctx) {
    logLine("POSITION_CLOSED pos=" + ctx.getPosition());
  }

  /**
   * D-82/Q-11, reworked 2026-09-21 after a live near-miss. Three distinct
   * cases, in order:
   *
   * 1. This fill is our tracked entry (liveOrderInFlight) -- attach the
   *    bracket if the intent that opened it wanted one (targetPosition
   *    != 0), stash both leg Order refs in restingStopOrder/
   *    restingTargetOrder for later cancellation. A closing fill
   *    (targetPosition == 0, set by reconcileLive's closing branch) needs
   *    no bracket -- logged, not an error.
   * 2. This fill is one of the CURRENT position's own tracked bracket
   *    legs resolving naturally -- cancel the sibling explicitly rather
   *    than assume broker-side OCO cleaned it up. This is the fix: the
   *    near-miss happened because nothing occupied this branch at all
   *    (liveOrderInFlight was already false by the time a leg fills), so
   *    a leg could sit resting indefinitely after its position was
   *    already closed some other way.
   * 3. Neither -- log only.
   *
   * Runs on whatever thread MotiveWave calls this hook on (proven fine
   * for case 1's call sequence by D-81's live retest); deliberately does
   * NOT touch pipeline/feature/strategy state, only OrderGateway (which
   * itself only wraps ctx) -- see reconcileLive()'s onFill() gap note for
   * why strategy state is out of scope here.
   */
  @Override
  public void onOrderFilled(OrderContext ctx, Order order) {
    logLine("ORDER_FILLED " + order);

    if (liveOrderInFlight) {
      int targetPosition = pendingBracketTargetPosition;
      Integer stopTicks = pendingBracketStopTicks;
      Integer targetTicks = pendingBracketTargetTicks;
      String reason = pendingBracketReason;
      clearPendingLiveOrder();
      if (targetPosition == 0 || stopTicks == null || targetTicks == null) {
        logLine("LIVE_FILL_NO_BRACKET targetPosition=" + targetPosition
            + " stopTicks=" + stopTicks + " targetTicks=" + targetTicks);
        return;
      }
      PriceCodec codec = priceCodec;
      OrderGateway gw = gateway;
      if (codec == null || gw == null) {
        logLine("LIVE_BRACKET_SKIPPED reason=codec_or_gateway_unavailable");
        return;
      }
      boolean closingIsBuy = targetPosition < 0; // a short position closes with a BUY bracket
      float stopPrice = (float) codec.fromTicks(stopTicks);
      float targetPrice = (float) codec.fromTicks(targetTicks);
      OrderGateway.BracketOrders bracket =
          gw.submitRealBracket(closingIsBuy, Math.abs(targetPosition), stopPrice, targetPrice, reason);
      restingStopOrder = bracket.stop();
      restingTargetOrder = bracket.target();
      logLine("LIVE_BRACKET_SUBMITTED " + bracket.journalLine());
      return;
    }

    String filledId = order.getOrderId();
    com.motivewave.platform.sdk.order_mgmt.Order stop = restingStopOrder;
    com.motivewave.platform.sdk.order_mgmt.Order target = restingTargetOrder;
    boolean wasStop = stop != null && filledId != null && filledId.equals(stop.getOrderId());
    boolean wasTarget = !wasStop && target != null && filledId != null && filledId.equals(target.getOrderId());
    if (!wasStop && !wasTarget) {
      return; // not one of ours to track -- log line above is enough
    }
    com.motivewave.platform.sdk.order_mgmt.Order sibling = wasStop ? target : stop;
    restingStopOrder = null;
    restingTargetOrder = null;
    OrderGateway gw = gateway;
    if (gw != null && sibling != null) {
      selfCancelledOrderIds.add(sibling.getOrderId());
      String line = gw.cancelIfActive(sibling, wasStop ? "target" : "stop",
          "sibling leg after " + (wasStop ? "stop" : "target") + " filled");
      if (line != null) {
        logLine("LIVE_SIBLING_LEG_CANCELLED " + line);
      } else {
        selfCancelledOrderIds.remove(sibling.getOrderId()); // already resolved -- no callback coming
      }
    }

    // 2026-09-23 (plumbingEdgeCases.md §8): confirm we actually ended up
    // flat after handling the sibling either way. Every bracket today
    // closes the WHOLE position, so anything other than 0 here means the
    // sibling also genuinely filled (a real double-fill, not just a clean
    // cancel) -- cancelIfActive() can't tell "already cancelled" from
    // "already filled" apart on its own (see §8's own writeup), so this
    // checks the one thing that actually reveals which one happened.
    if (gw != null) {
      int posAfter = gw.currentPosition();
      if (posAfter != 0) {
        armDenied = true;
        logLine("POSITION_MISMATCH_DETECTED expectedFlat=true actualPosition=" + posAfter
            + " -- both bracket legs likely filled, flattening");
        logLine("POSITION_MISMATCH_CORRECTED " + gw.cancelAllAndClose(
            "double-fill correction: expected flat, was " + posAfter));
      }
    }
  }

  /**
   * D-82/Q-11, reworked 2026-09-21: a cancellation whose order id is one
   * WE just cancelled ourselves (onOrderFilled's sibling-leg cleanup, the
   * only remaining self-initiated cancel path now that reconcileLive's
   * closing branch takes no action) is expected -- not disarm-worthy. The
   * near-miss's
   * original version disarmed on ANY cancellation while an entry was in
   * flight, which misattributed a bracket-leg cleanup cancellation to the
   * entry itself. Only an unmarked cancellation while our own entry is
   * genuinely in flight still means the entry never became a real
   * position -- that's still disarm-worthy, same reasoning as before.
   */
  @Override
  public void onOrderCancelled(OrderContext ctx, Order order) {
    logLine("ORDER_CANCELLED " + order);
    String id = order.getOrderId();
    if (id != null && selfCancelledOrderIds.remove(id)) {
      return;
    }
    if (liveOrderInFlight) {
      clearPendingLiveOrder();
      armDenied = true;
      logLine("LIVE_ENTRY_CANCELLED_DISARMING -- entry cancelled before filling, disarming");
    }
  }

  /** D-82/Q-11: same reasoning as onOrderCancelled() above. */
  @Override
  public void onOrderRejected(OrderContext ctx, Order order) {
    logLine("ORDER_REJECTED " + order);
    if (liveOrderInFlight) {
      clearPendingLiveOrder();
      armDenied = true;
      logLine("LIVE_ENTRY_REJECTED_DISARMING -- entry rejected, disarming");
    }
  }

  @Override
  public void onOrderModified(OrderContext ctx, Order order) {
    logLine("ORDER_MODIFIED " + order);
  }

  private void logLine(String s) {
    info("FLOW_RUNTIME: " + s);
    JournalWriter j = journal;
    if (j != null) {
      j.writeDecision(0, Json.object().field("type", "log").field("msg", s).build());
    }
  }
}
