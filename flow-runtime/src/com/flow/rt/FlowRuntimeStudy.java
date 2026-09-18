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
public class FlowRuntimeStudy extends Study {
  private static final String STRATEGY_ID_KEY = "FLOW_STRATEGY_ID";
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
  private static final Path LOG_ROOT = Path.of("C:/yadvendra/trading/FLOW_V2/logs");

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
    grp.addRow(new BooleanDescriptor(ARMED_KEY, "Armed (no effect yet -- no order code exists)", false));
    grp.addRow(new StringDescriptor(MODE_KEY, "Mode", "DRY_RUN"));
    var vpGrp = tab.addGroup("Volume Profile");
    vpGrp.addRow(new IntegerDescriptor(VP_RANGE_TICKS_KEY, "Row Width (ticks)", 1, 1, 9999, 1));
    vpGrp.addRow(new BooleanDescriptor(DRAW_VP_KEY, "Draw on Chart", false));
    var fpGrp = tab.addGroup("Footprint");
    fpGrp.addRow(new IntegerDescriptor(FP_RANGE_TICKS_KEY, "Row Width (ticks)", 1, 1, 9999, 1));
    fpGrp.addRow(new IntegerDescriptor(FP_MERGE_ROWS_KEY, "Draw: Merge N Rows (display only, not stored)", 1, 1, 999, 1));
    fpGrp.addRow(new BooleanDescriptor(DRAW_FP_KEY, "Draw on Chart", true));
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
    strategy.onInit(new StrategyConfig(java.util.Map.of()));

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

    int rangeTicks = getSettings().getInteger(VP_RANGE_TICKS_KEY);
    volumeProfile = new SdkVolumeProfileFeature(
        com.flow.flow.VolumeProfileView.FEATURE_ID, instrument, priceCodec, rangeTicks);
    int fpRangeTicks = getSettings().getInteger(FP_RANGE_TICKS_KEY);
    footprint = new SdkFootprintFeature(
        com.flow.flow.FootprintView.FEATURE_ID, instrument, priceCodec, fpRangeTicks);
    Map<String, com.flow.core.Feature> features = Map.of(
        com.flow.flow.VolumeProfileView.FEATURE_ID, volumeProfile,
        com.flow.flow.FootprintView.FEATURE_ID, footprint);

    IntentSink sink = this::onIntentChanged;
    pipeline = new Pipeline(strategy, journal, sink, features, priceCodec::fromTicks);
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

    logLine("SESSION_START strategyId=" + strategyId + " symbol=" + instrument.getSymbol());
  }

  private void onIntentChanged(Intent intent, Event triggeringEvent) {
    OrderGateway gw = gateway;
    if (gw == null) {
      journal.writeDecision(triggeringEvent.seq(), Json.object()
          .field("type", "reconcile_skipped")
          .field("reason", "OrderGateway not yet constructed (onActivate has not fired)")
          .field("seq", triggeringEvent.seq())
          .build());
      return;
    }
    journal.writeDecision(triggeringEvent.seq(), gw.reconcileDryRun(intent));
  }

  @Override
  public void onTick(DataContext ctx, Tick tick) {
    Sequencer s = sequencer;
    PriceCodec codec = priceCodec;
    if (s == null || codec == null) return;
    int priceTicks = codec.toTicks(tick.getPrice());
    int bidTicks = codec.toTicks(tick.getBidPrice());
    int askTicks = codec.toTicks(tick.getAskPrice());
    s.publish((seq, et, rt) -> new TickEvent(seq, et, rt, priceTicks, tick.getVolume(),
        tick.isAskTick(), bidTicks, askTicks), tick.getTime());
    maybeRedraw();
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

  @Override
  public void onOrderFilled(OrderContext ctx, Order order) {
    logLine("ORDER_FILLED " + order);
  }

  @Override
  public void onOrderCancelled(OrderContext ctx, Order order) {
    logLine("ORDER_CANCELLED " + order);
  }

  @Override
  public void onOrderRejected(OrderContext ctx, Order order) {
    logLine("ORDER_REJECTED " + order);
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
