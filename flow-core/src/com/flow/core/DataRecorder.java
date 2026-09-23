package com.flow.core;

import com.flow.flow.BigTradeEvent;
import com.flow.flow.BigTradeView;
import com.flow.flow.LiquidityMapView;
import com.flow.flow.MarketStructureView;
import com.flow.flow.ZoneRange;
import com.flow.flow.VWAPView;
import com.flow.journal.ConstructDataStore;
import com.flow.journal.Json;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntToDoubleFunction;

/**
 * Per-construct retained data (D-88): every {@code dataIntervalSeconds}
 * (default 1) it writes a footprint candle, a VWAP reading and any new/
 * changed big trades; every {@code liquidityIntervalSeconds} (default 1)
 * a bounded-window liquidity map snapshot. Volume profile is NOT stored
 * separately -- summing footprint candle rows per price rebuilds it.
 * Market structure isn't stored either (cheap to regenerate from bars).
 *
 * The interval clock is the ClockEvent stream (recorded in the raw
 * journal), never the wall clock, so replaying a raw journal produces the
 * same files. A candle holds every tick drained since the previous flush;
 * {@code t} is the wall-clock ms of the boundary that closed it (interval
 * END), not its start. Footprint's own tick clock can differ from the
 * clock events' by exchange-vs-local skew, so no per-tick time bucketing is
 * attempted -- drain order between two flushes is the definition.
 *
 * Prices are written as decimals via priceDecoder, NOT tick offsets: the
 * tick anchor is per-activation (PriceCodec), so offsets from two
 * activations in one trading day would not be comparable. With no decoder
 * (replay) prices fall back to raw tick offsets and every line carries
 * {@code "unit":"ticks"} in the header instead of {@code "price"}.
 *
 * Drain-thread only, does no I/O (ConstructDataStore's own thread does).
 * Pipeline wraps every call in a try/catch: a recorder bug disables the
 * recorder, it never disarms trading.
 */
public final class DataRecorder {
  public static final String FOOTPRINT = "footprint";
  public static final String VWAP = "vwap";
  public static final String BIG_TRADES = "big_trades";
  public static final String LIQUIDITY_MAP = "liquidity_map";
  public static final String BARS = "bars";
  public static final String MARKET_STRUCTURE = "market_structure";
  private static final int LIQUIDITY_WINDOW_TICKS = 100;

  private final ConstructDataStore store;
  private final Map<String, Feature> features;
  private final IntToDoubleFunction priceDecoder; // nullable
  private final long dataIntervalMs;
  private final long liquidityIntervalMs;
  private final int keepTradingDays;

  private long currentSessionId = Long.MIN_VALUE;
  private long lastDataFlushMs = -1;
  private long lastLiquidityFlushMs = -1;
  private final java.util.Set<String> headerWritten = new java.util.HashSet<>();

  // footprint candle in progress
  private final TreeMap<Integer, long[]> rows = new TreeMap<>(); // priceTicks -> {ask, bid}
  private int open, high, low, close;
  private long candleVolume;
  private int candleTicks;

  // market structure (D-90): last recorded state, so only CHANGES are written. Baseline is
  // taken at construction -- i.e. AFTER any historical warm-start -- so warm-up itself never
  // shows up as changes ("store only from when the strategy was initialised").
  private record MsSnap(MarketStructureView.Trend trend, MarketStructureView.PullbackState pullback,
                        ZoneRange tjl1, ZoneRange tjl2, ZoneRange aPlus, ZoneRange sbrRbs,
                        ZoneRange dt, ZoneRange db, java.util.Set<MarketStructureView.TradeableLevel> tradeable) {}
  private MsSnap msLast;

  // big trades: last emitted size per (openTime|price|side)
  private final Map<String, Double> emittedBigTradeSize = new HashMap<>();

  public DataRecorder(ConstructDataStore store, Map<String, Feature> features, IntToDoubleFunction priceDecoder,
                      int dataIntervalSeconds, int liquidityIntervalSeconds, int keepTradingDays) {
    this.store = store;
    this.features = features;
    this.priceDecoder = priceDecoder;
    this.dataIntervalMs = Math.max(1, dataIntervalSeconds) * 1000L;
    this.liquidityIntervalMs = Math.max(1, liquidityIntervalSeconds) * 1000L;
    this.keepTradingDays = keepTradingDays;
    this.msLast = msSnapshot();
  }

  public void onEvent(Event e) {
    if (e instanceof TickEvent te) {
      accumulate(te);
    } else if (e instanceof ClockEvent) {
      onClock(e.eventTimeMs());
    } else if (e instanceof BarEvent be && be.phase() == BarPhase.CLOSE) {
      onBarClose(be);
    }
  }

  /**
   * The chart's own OHLCV, one line per closed bar (raw.jsonl keeps it only
   * 48 h), then a market-structure line iff its state changed on this bar.
   */
  private void onBarClose(BarEvent be) {
    long sessionId = SessionBoundary.sessionIdFor(be.eventTimeMs());
    write(BARS, sessionId, be.eventTimeMs(), Json.object()
        .field("t", be.eventTimeMs())
        .field("o", px(be.openTicks())).field("h", px(be.highTicks()))
        .field("l", px(be.lowTicks())).field("c", px(be.closeTicks()))
        .field("v", be.volume())
        .build());

    MsSnap now = msSnapshot();
    if (now == null || now.equals(msLast)) return;
    List<String> changes = msChanges(msLast, now);
    msLast = now;
    if (changes.isEmpty()) return;
    MarketStructureView ms = (MarketStructureView) features.get(MarketStructureView.FEATURE_ID);
    StringBuilder ch = new StringBuilder("[");
    for (int i = 0; i < changes.size(); i++) {
      if (i > 0) ch.append(',');
      ch.append('"').append(changes.get(i)).append('"');
    }
    ch.append(']');
    StringBuilder tr = new StringBuilder("[");
    boolean first = true;
    for (MarketStructureView.TradeableLevel l : new java.util.TreeSet<>(now.tradeable())) {
      if (!first) tr.append(',');
      first = false;
      tr.append('"').append(l.name()).append('"');
    }
    tr.append(']');
    Integer pbHigh = ms.pullbackHighTicks();
    Integer pbLow = ms.pullbackLowTicks();
    write(MARKET_STRUCTURE, sessionId, be.eventTimeMs(), Json.object()
        .field("t", be.eventTimeMs())
        .field("price", px(be.closeTicks()))
        .fieldRaw("changes", ch.toString())
        .field("trend", now.trend().name())
        .field("pullback", now.pullback().name())
        .fieldRaw("tjl1", zoneJson(now.tjl1()))
        .fieldRaw("tjl2", zoneJson(now.tjl2()))
        .fieldRaw("aPlus", zoneJson(now.aPlus()))
        .fieldRaw("sbrRbs", zoneJson(now.sbrRbs()))
        .fieldRaw("dt", zoneJson(now.dt()))
        .fieldRaw("db", zoneJson(now.db()))
        .fieldRaw("tradeable", tr.toString())
        .fieldOrNull("pullbackHigh", pbHigh == null ? null : px(pbHigh))
        .fieldOrNull("pullbackLow", pbLow == null ? null : px(pbLow))
        .build());
  }

  private MsSnap msSnapshot() {
    if (!(features.get(MarketStructureView.FEATURE_ID) instanceof MarketStructureView ms)) return null;
    return new MsSnap(ms.trend(), ms.pullbackState(), ms.lastTjl1(), ms.lastTjl2(), ms.lastAPlus(),
        ms.lastSbrRbs(), ms.lastDt(), ms.lastDb(), java.util.Set.copyOf(ms.tradeableLevels()));
  }

  private static List<String> msChanges(MsSnap a, MsSnap b) {
    List<String> c = new java.util.ArrayList<>();
    if (a == null) return c;
    if (a.trend() != b.trend()) c.add("trend " + a.trend() + "->" + b.trend());
    if (a.pullback() != b.pullback()) {
      if (a.pullback() == MarketStructureView.PullbackState.NONE) c.add("pullback started (forming, not yet valid)");
      else if (b.pullback() == MarketStructureView.PullbackState.VALID) c.add("pullback became valid");
      else if (a.pullback() == MarketStructureView.PullbackState.FORMING) c.add("pullback abandoned before becoming valid");
      else c.add("pullback ended (" + a.pullback() + "->" + b.pullback() + ")");
    }
    zoneChange(c, "tjl1", a.tjl1(), b.tjl1());
    zoneChange(c, "tjl2", a.tjl2(), b.tjl2());
    zoneChange(c, "a_plus", a.aPlus(), b.aPlus());
    zoneChange(c, "sbr_rbs", a.sbrRbs(), b.sbrRbs());
    zoneChange(c, "dt", a.dt(), b.dt());
    zoneChange(c, "db", a.db(), b.db());
    if (!a.tradeable().equals(b.tradeable())) c.add("tradeable levels changed");
    return c;
  }

  private static void zoneChange(List<String> c, String name, ZoneRange a, ZoneRange b) {
    if (java.util.Objects.equals(a, b)) return;
    c.add(b == null ? name + " cleared" : "new " + name + " created");
  }

  private String zoneJson(ZoneRange z) {
    if (z == null) return "null";
    return "{\"low\":" + px(z.lowTicks()) + ",\"high\":" + px(z.highTicks()) + "}";
  }

  /**
   * The historical bars a market-structure warm-start consumed, written
   * once at session start. They bypass the Sequencer/journal, so without
   * this a replay could never reproduce the state the first live bar was
   * judged against. Call before the first event is published (not
   * thread-safe against the drain thread).
   */
  public void recordMarketStructureWarmStart(List<BarEvent> bars, long nowMs) {
    if (bars.isEmpty()) return;
    long sessionId = SessionBoundary.sessionIdFor(nowMs);
    StringBuilder arr = new StringBuilder("[");
    for (int i = 0; i < bars.size(); i++) {
      BarEvent b = bars.get(i);
      if (i > 0) arr.append(',');
      arr.append("{\"t\":").append(b.eventTimeMs())
          .append(",\"o\":").append(px(b.openTicks())).append(",\"h\":").append(px(b.highTicks()))
          .append(",\"l\":").append(px(b.lowTicks())).append(",\"c\":").append(px(b.closeTicks()))
          .append(",\"v\":").append(b.volume()).append('}');
    }
    arr.append(']');
    write(MARKET_STRUCTURE, sessionId, nowMs, Json.object()
        .field("type", "warm_start")
        .field("t", nowMs)
        .field("barCount", bars.size())
        .fieldRaw("bars", arr.toString())
        .build());
  }

  private void accumulate(TickEvent te) {
    int p = te.priceTicks();
    long[] r = rows.computeIfAbsent(p, k -> new long[2]);
    if (te.isAskTick()) r[0] += te.volume(); else r[1] += te.volume();
    if (candleTicks == 0) {
      open = p;
      high = p;
      low = p;
    } else {
      if (p > high) high = p;
      if (p < low) low = p;
    }
    close = p;
    candleVolume += te.volume();
    candleTicks++;
  }

  private void onClock(long clockMs) {
    long sessionId = SessionBoundary.sessionIdFor(clockMs);
    if (sessionId != currentSessionId) {
      currentSessionId = sessionId;
      store.requestPrune(keepTradingDays, sessionId);
    }
    if (lastDataFlushMs < 0) lastDataFlushMs = alignDown(clockMs, dataIntervalMs);
    if (lastLiquidityFlushMs < 0) lastLiquidityFlushMs = alignDown(clockMs, liquidityIntervalMs);

    if (clockMs - lastDataFlushMs >= dataIntervalMs) {
      long boundary = alignDown(clockMs, dataIntervalMs);
      lastDataFlushMs = boundary;
      flushData(boundary, sessionId);
    }
    if (clockMs - lastLiquidityFlushMs >= liquidityIntervalMs) {
      long boundary = alignDown(clockMs, liquidityIntervalMs);
      lastLiquidityFlushMs = boundary;
      flushLiquidity(boundary, sessionId);
    }
  }

  private static long alignDown(long ms, long interval) {
    return (ms / interval) * interval;
  }

  // Decimal price for a tick offset, snapped to the tick grid: the decoder's
  // anchor is a float-precision SDK price (4322.900098 for 4322.9), so a
  // plain decode leaks that noise into every stored row (and ~35% of the
  // liquidity map's bytes). Grid = decoder(1) - decoder(0).
  private double tickGrid = Double.NaN;

  private double px(int ticks) {
    if (priceDecoder == null) return ticks;
    double v = priceDecoder.applyAsDouble(ticks);
    if (Double.isNaN(tickGrid)) tickGrid = priceDecoder.applyAsDouble(1) - priceDecoder.applyAsDouble(0);
    if (tickGrid > 0) v = Math.round(v / tickGrid) * tickGrid;
    return Math.round(v * 1e6) / 1e6;
  }

  private void write(String construct, long sessionId, long boundaryMs, String line) {
    if (headerWritten.add(construct + "/" + sessionId)) {
      Json h = Json.object()
          .field("type", "header")
          .field("construct", construct)
          .field("sessionId", sessionId)
          .field("unit", priceDecoder == null ? "ticks" : "price");
      if (construct.equals(BARS)) {
        h.field("trigger", "bar_close");
      } else if (construct.equals(MARKET_STRUCTURE)) {
        h.field("trigger", "state_change");
      } else {
        h.field("intervalSeconds", (construct.equals(LIQUIDITY_MAP) ? liquidityIntervalMs : dataIntervalMs) / 1000);
      }
      store.write(construct, sessionId, h.field("t", boundaryMs).build());
    }
    store.write(construct, sessionId, line);
  }

  private void flushData(long boundaryMs, long sessionId) {
    if (candleTicks > 0) {
      StringBuilder rowsJson = new StringBuilder("[");
      boolean first = true;
      for (Map.Entry<Integer, long[]> en : rows.entrySet()) {
        if (!first) rowsJson.append(',');
        first = false;
        rowsJson.append("{\"p\":").append(px(en.getKey()))
            .append(",\"a\":").append(en.getValue()[0])
            .append(",\"b\":").append(en.getValue()[1]).append('}');
      }
      rowsJson.append(']');
      write(FOOTPRINT, sessionId, boundaryMs, Json.object()
          .field("t", boundaryMs)
          .field("o", px(open)).field("h", px(high)).field("l", px(low)).field("c", px(close))
          .field("v", candleVolume).field("n", candleTicks)
          .fieldRaw("rows", rowsJson.toString())
          .build());
      rows.clear();
      candleTicks = 0;
      candleVolume = 0;
    }

    if (features.get(VWAPView.FEATURE_ID) instanceof VWAPView v && v.isReady() && v.vwap() != null) {
      write(VWAP, sessionId, boundaryMs, Json.object()
          .field("t", boundaryMs)
          .field("vwap", Math.round(v.vwap() * 1e6) / 1e6)
          .field("vol", v.totalVolume())
          .build());
    }

    if (features.get(BigTradeView.FEATURE_ID) instanceof BigTradeView bt) {
      flushBigTrades(bt, boundaryMs, sessionId);
    }
  }

  private void flushBigTrades(BigTradeView bt, long boundaryMs, long sessionId) {
    List<BigTradeEvent> recent = bt.recent();
    StringBuilder arr = new StringBuilder("[");
    boolean any = false;
    java.util.Set<String> current = new java.util.HashSet<>();
    for (BigTradeEvent b : recent) {
      String key = b.eventTimeMs() + "|" + b.priceTicks() + "|" + b.isAskTick();
      current.add(key);
      Double prev = emittedBigTradeSize.get(key);
      if (prev != null && prev == b.size()) continue;
      emittedBigTradeSize.put(key, b.size());
      if (any) arr.append(',');
      any = true;
      arr.append("{\"ts\":").append(b.eventTimeMs())
          .append(",\"p\":").append(px(b.priceTicks()))
          .append(",\"size\":").append(b.size())
          .append(",\"ask\":").append(b.isAskTick()).append('}');
    }
    // Forget only trades that have left recent() -- an age cutoff would
    // re-emit a still-listed old trade as if it were new (seen live).
    emittedBigTradeSize.keySet().retainAll(current);
    if (!any) return;
    arr.append(']');
    write(BIG_TRADES, sessionId, boundaryMs, Json.object()
        .field("t", boundaryMs)
        .fieldRaw("trades", arr.toString())
        .build());
  }

  private void flushLiquidity(long boundaryMs, long sessionId) {
    if (!(features.get(LiquidityMapView.FEATURE_ID) instanceof LiquidityMapView lm) || !lm.isReady()) return;
    Integer bb = lm.bestBidTicks();
    Integer ba = lm.bestAskTicks();
    write(LIQUIDITY_MAP, sessionId, boundaryMs, Json.object()
        .field("t", boundaryMs)
        .fieldOrNull("bid", bb == null ? null : px(bb))
        .fieldOrNull("ask", ba == null ? null : px(ba))
        .field("windowTicks", LIQUIDITY_WINDOW_TICKS)
        .fieldRaw("bids", rowsJson(lm.bidRowsWithin(LIQUIDITY_WINDOW_TICKS)))
        .fieldRaw("asks", rowsJson(lm.askRowsWithin(LIQUIDITY_WINDOW_TICKS)))
        .build());
  }

  private String rowsJson(List<DomRow> rs) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < rs.size(); i++) {
      if (i > 0) sb.append(',');
      DomRow r = rs.get(i);
      sb.append("{\"p\":").append(px(r.priceTicks())).append(",\"s\":").append(r.size()).append('}');
    }
    return sb.append(']').toString();
  }
}
