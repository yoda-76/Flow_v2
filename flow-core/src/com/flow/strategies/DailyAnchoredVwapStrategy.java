package com.flow.strategies;

import com.flow.bars.BarHistoryView;
import com.flow.bars.Resampler;
import com.flow.bars.ind.Ema;
import com.flow.bars.ind.WilderAtr;
import com.flow.core.FillEvent;
import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.StrategyLog;
import com.flow.core.Trigger;
import com.flow.journal.Json;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Daily Anchored VWAP" (MES, long and short, NO stop, holds overnight), port of analysis/gb_nq_backtest/anchored_vwap.py
 * run() with the platform settings (--tf 3 --ema 11 --no-atr-stop --max-rel-atr 1.1, ATR 14, baseline ATR 50, 2 additional
 * attempts, 1 contract). Runs on a 1-MINUTE chart and builds the tfMin-minute bars itself (Resampler; the 3m buckets are
 * the same on the ET and CT clocks).
 *
 * Per completed signal bar (all times ET): the daily VWAP (volume-weighted typical price (h+l+c)/3 OF THE SIGNAL BARS)
 * restarts at the ET calendar date change and accumulates every bar incl. overnight; EMA(emaLen, SMA-seeded), ATR(atrLen)
 * and baseline ATR(baseAtrLen) run continuously. Signals only for bars starting inside [sessionStartHhmm, sessionEndHhmm]
 * (09:30-16:00 ET, the 16:00 bar included). The Python's if / else-if regime ladder is kept line by line, including its
 * quirks: early returns (no EMA, no volume, ATR not ready) happen BEFORE prevClose / prevVwap are updated; one event per
 * bar; marker colour names are reversed (a GREEN dot enters LONG, a RED dot SHORT; a GREEN triangle closes a long, a RED
 * triangle a short); no time exit, no session flatten, positions are held overnight.
 *
 * Entry: market (no stop, no target = an intent with null levels) at the close of the signal bar, if allowed (first trade
 * of the ET day, or after a first LOSS up to additionalAttempts more closed trades; after a first win none), the
 * relative-ATR filter passes (ATR/baselineATR &lt;= maxRelAtr) and the strategy is flat. Exit: the opposite-colour
 * triangle -> flat intent (the position is never flipped directly). A trade counts as a win when its net P&amp;L
 * (exit - entry fill) * pointValue - commissionUsd &gt; 0; the exit price of the strategy's own exit is estimated as the
 * open of the next 1m bar minus entrySlipEstTicks (adverse), evaluated on the next closed bar.
 *
 * KNOWN LIVE vs BACKTEST DIFFERENCES: a restart loses the position and the daily win/attempt counters (the runtime never
 * touches a position that predates the study, D-24; history only rebuilds EMA / ATR / VWAP / regime); the Python's final
 * "data_end" close does not exist live; when the last 1m bar of a signal bucket never traded, the bucket completes one 1m
 * bar late (Resampler rule b) and the market order fills one bar later than the Python's.
 *
 * Parameters (flat JSON file at StrategyConfig "strategyConfigPath"; all optional):
 * <pre>
 * key                  default  meaning
 * tfMin                3        signal bar length in minutes (must divide 1440)
 * emaLen               11       EMA length (SMA seed)
 * atrLen               14       ATR length (Wilder)
 * baseAtrLen           50       baseline ATR length
 * useRelVolFilter      true     require ATR / baseline ATR &lt;= maxRelAtr
 * maxRelAtr            1.1      the limit (the Python uses max(value, 0.1))
 * additionalAttempts   2        extra closed trades allowed per ET day after a first losing trade
 * sessionStartHhmm     930      ET, signals from this hhmm (bar start)
 * sessionEndHhmm       1600     ET, signals up to and including this hhmm
 * useTypicalPrice      true     VWAP on (h+l+c)/3 of the signal bar; false = close
 * contracts            1        position size
 * pointValue           5.0      USD per point (MES), only for the win/loss test
 * commissionUsd        1.0      round-trip commission per contract, only for the win/loss test
 * entrySlipEstTicks    1        assumed slippage when estimating the exit fill of a triangle exit
 * tickSize             (chart)  only used when the runtime passes none
 * </pre>
 */
public final class DailyAnchoredVwapStrategy implements FlowStrategy {
  public static final String ID = "mc_daily_avwap";

  private static final ZoneId ET = ZoneId.of("America/New_York");

  private enum Pos { FLAT, PENDING, OPEN }

  private final AtomicLong intentSeq = new AtomicLong(0);
  private final SLog lg = new SLog();

  // log-only state (never read by a decision)
  private boolean warmLogged = false;
  private long lastT = 0;
  private LocalDate loggedDay = null;
  private int dEntries = 0, dExits = 0, dWins = 0, dLosses = 0, dRejected = 0, dEval = 0, dBlocked = 0, dRegimeChanges = 0;

  // parameters
  private int tfMin = 3, emaLen = 11, atrLen = 14, baseAtrLen = 50, additional = 2;
  private int sessStart = 930, sessEnd = 1600, contracts = 1, slipEst = 1;
  private boolean useRel = true, typical = true;
  private double maxRelAtr = 1.1, pointValue = 5.0, commission = 1.0, tick = 0.25;

  // indicators
  private Resampler rs;
  private Ema ema;
  private WilderAtr atr, batr;

  private long seen = 0;

  // ET-day state (the Python's day, pv, vol, regime, waiting_*, has_prev, first_*, attempts)
  private LocalDate day = null;
  private double pv = 0, vol = 0;
  private int regime = 0;
  private boolean waitingRed = false, waitingGreen = false, hasPrev = false;
  private double prevClose = 0, prevVwap = 0;
  private boolean firstClosed = false, firstWon = false;
  private int attempts = 0;

  // exit estimate waiting for the next 1m bar's open
  private boolean pendingResolve = false;

  // position state
  private Pos pos = Pos.FLAT;
  private int posSide = 0;
  private int entryFill;

  @Override public String id() { return ID; }

  @Override public void setLog(StrategyLog log) { lg.set(log); }

  @Override public Set<String> requires() { return Set.of(BarHistoryView.FEATURE_ID); }

  @Override public Set<Trigger> triggers() { return Set.of(new Trigger.BarClose()); }

  @Override
  public void onInit(StrategyConfig cfg) {
    StrategyParams p = StrategyParams.load(cfg, ID);
    tick = p.tickSize();
    tfMin = p.getInt("tfMin", 3);
    emaLen = p.getInt("emaLen", 11);
    atrLen = p.getInt("atrLen", 14);
    baseAtrLen = p.getInt("baseAtrLen", 50);
    useRel = p.getBoolean("useRelVolFilter", true);
    maxRelAtr = p.getDouble("maxRelAtr", 1.1);
    additional = p.getInt("additionalAttempts", 2);
    sessStart = p.getInt("sessionStartHhmm", 930);
    sessEnd = p.getInt("sessionEndHhmm", 1600);
    typical = p.getBoolean("useTypicalPrice", true);
    contracts = p.getInt("contracts", 1);
    pointValue = p.getDouble("pointValue", 5.0);
    commission = p.getDouble("commissionUsd", 1.0);
    slipEst = p.getInt("entrySlipEstTicks", 1);
    rs = new Resampler(tfMin);
    ema = new Ema(Math.max(emaLen, 1), Ema.SeedMode.SMA_N);
    atr = new WilderAtr(Math.max(atrLen, 1));
    batr = new WilderAtr(Math.max(baseAtrLen, 2));
    if (lg.on()) {
      lg.emit(0, "init", Json.object().field("tickSize", tick).field("tfMin", tfMin).field("emaLen", emaLen)
          .field("atrLen", atrLen).field("baseAtrLen", baseAtrLen).field("useRelVolFilter", useRel)
          .field("maxRelAtr", maxRelAtr).field("additionalAttempts", additional).field("sessionStartHhmm", sessStart)
          .field("sessionEndHhmm", sessEnd).field("useTypicalPrice", typical).field("contracts", contracts)
          .field("pointValue", pointValue).field("commissionUsd", commission).field("entrySlipEstTicks", slipEst)
          .field("skipDates", "").field("skipDatesCount", 0)
          .field("tickUnits", "bar prices are in ticks (live: price/tickSize; BarReplay: relative to the first bar's open)"));
    }
  }

  @Override
  public Intent onEvent(MarketState state) {
    BarHistoryView h = state.bars();
    if (h == null || h.size() == 0) return none();
    lg.historyOnce(h);
    long app = h.appended();
    long fresh = app - seen;
    if (fresh <= 0) return restate();
    int n = h.size();
    int take = (int) Math.min(fresh, n);
    seen = app;
    Intent result = null;
    for (int i = n - take; i < n; i++) {
      Intent it = processBar(h, i, i == n - 1);
      if (it != null) result = it;
    }
    return result != null ? result : restate();
  }

  private Intent processBar(BarHistoryView h, int i, boolean act) {
    final long t = h.startMs(i) + 60_000L;
    lastT = t;
    if (pendingResolve) {
      pendingResolve = false;
      closeTrade(h.open(i) - posSideForResolve * slipEst);
    }
    Resampler.Bar b = rs.onMinuteBar(h.startMs(i), h.open(i), h.high(i), h.low(i), h.close(i), h.volume(i));
    return b == null ? null : onSignalBar(b, act, t, h.appended());
  }

  private int posSideForResolve = 0;

  /** The Python's per-bar body, line for line. */
  private Intent onSignalBar(Resampler.Bar b, boolean act, long t, long barsSeen) {
    ZonedDateTime z = Instant.ofEpochMilli(b.startMs()).atZone(ET);
    LocalDate dnum = z.toLocalDate();
    int hhmm = z.getHour() * 100 + z.getMinute();
    double c = b.close();

    double emaV;
    ema.update(c);
    atr.update(b.high(), b.low(), b.close());
    batr.update(b.high(), b.low(), b.close());

    if (!dnum.equals(day)) {
      if (lg.on() && day != null && day.equals(loggedDay)) {
        lg.emit(t, "day_summary", lg.j(t).field("etDate", day.toString()).field("entries", dEntries)
            .field("exits", dExits).field("wins", dWins).field("losses", dLosses).field("rejected", dRejected)
            .field("evals", dEval).field("blockedSignals", dBlocked).field("regimeChanges", dRegimeChanges)
            .field("firstClosed", firstClosed).field("firstWon", firstWon).field("attempts", attempts)
            .field("position", pos.name()));
      }
      dEntries = dExits = dWins = dLosses = dRejected = dEval = dBlocked = dRegimeChanges = 0;
      day = dnum;
      pv = 0;
      vol = 0;
      regime = 0;
      waitingRed = false;
      waitingGreen = false;
      hasPrev = false;
      firstClosed = false;
      firstWon = false;
      attempts = 0;
    }
    double px = typical ? (b.high() + b.low() + b.close()) / 3.0 : b.close();
    double v = Math.max((double) b.volume(), 0.0);
    pv += px * v;
    vol += v;
    if (act && lg.on() && !dnum.equals(loggedDay)) {
      loggedDay = dnum;
      lg.emit(t, "day_start", lg.j(t).field("etDate", dnum.toString()).field("ctDate", SLog.ct(t).substring(0, 10))
          .field("position", pos.name()).field("firstClosed", firstClosed).field("attempts", attempts)
          .field("note", "daily VWAP, regime and attempts reset at the ET date change; positions are held overnight")
          .fieldOrNull("emaTicks", ema.value()).fieldOrNull("atr", atr.value()).fieldOrNull("baseAtr", batr.value()));
    }
    Double e = ema.value();
    if (e == null) return null;
    emaV = e;
    if (vol <= 0.0) return null;
    double vwap = pv / vol;
    Double a = atr.value(), ba = batr.value();
    if (a == null || ba == null || a <= 0.0 || ba <= 0.0) return null;
    if (lg.on() && !warmLogged) {
      warmLogged = true;
      lg.emit(t, "warmup_done", lg.j(t).field("what", "EMA, ATR and baseline ATR ready").field("emaTicks", emaV)
          .field("atr", a).field("baseAtr", ba).field("barsSeen", barsSeen));
    }
    double rel = a / ba;
    boolean volOk = !useRel || rel <= Math.max(maxRelAtr, 0.1);

    final int regimeBefore = regime;
    boolean redDot = false, greenDot = false, redTri = false, greenTri = false;
    if (inSession(hhmm)) {
      if (waitingRed && c > emaV) {
        waitingRed = false; regime = -2; redTri = true;
      } else if (waitingGreen && c < emaV) {
        waitingGreen = false; regime = 2; greenTri = true;
      } else if (regime == -2 && emaV > vwap) {
        regime = -3;
      } else if (regime == 2 && emaV < vwap) {
        regime = 3;
      } else if (regime == -3 && hasPrev && prevClose >= prevVwap && c < vwap) {
        regime = 0; greenDot = true;
      } else if (regime == 3 && hasPrev && prevClose <= prevVwap && c > vwap) {
        regime = 0; redDot = true;
      } else if (regime == 0) {
        if (emaV < vwap && c < emaV) {
          waitingRed = true; regime = -1; redDot = true;
        } else if (emaV > vwap && c > emaV) {
          waitingGreen = true; regime = 1; greenDot = true;
        }
      }
    }

    // log-only snapshot of the gates as they stand BEFORE the entry / exit are applied
    final boolean lgOn = act && lg.on() && inSession(hhmm);
    final Pos posBefore = pos;
    final boolean allowedBefore = lgOn && allowed();

    Intent out = null;
    if (allowed() && volOk && pos == Pos.FLAT && (greenDot || redDot) && act) {
      int side = greenDot ? 1 : -1;
      pos = Pos.PENDING;
      posSide = side;
      out = new Intent(ID, intentSeq.incrementAndGet(), side * contracts, null, null,
          ID + " entry side=" + (side > 0 ? "L" : "S") + " close=" + b.close() + " ema=" + String.format("%.1f", emaV)
              + " vwap=" + String.format("%.1f", vwap) + " rel=" + String.format("%.2f", rel), false);
    }
    if (out == null && pos == Pos.OPEN && ((greenTri && posSide > 0) || (redTri && posSide < 0)) && act) {
      pendingResolve = true;
      posSideForResolve = posSide;
      pos = Pos.FLAT;
      out = flat("triangle exit");
    }
    if (lgOn) {
      boolean signal = greenDot || redDot;
      boolean entry = out != null && out.targetPosition() != 0;
      boolean exit = out != null && out.targetPosition() == 0;
      String dec;
      if (entry) dec = "ENTRY";
      else if (exit) dec = "EXIT";
      else if (signal && !allowedBefore) dec = firstWon ? "blocked_first_trade_won" : "blocked_attempts_used";
      else if (signal && !volOk) dec = "blocked_rel_atr";
      else if (signal) dec = "blocked_in_position";
      else if (greenTri || redTri) dec = "triangle_no_matching_position";
      else dec = "no_signal";
      dEval++;
      if (regime != regimeBefore) dRegimeChanges++;
      if (dec.startsWith("blocked")) dBlocked++;
      if (entry) dEntries++;
      if (exit) dExits++;
      lg.emit(t, "eval", lg.j(t).field("barStartCt", SLog.ct(b.startMs())).field("closeTicks", b.close())
          .field("emaTicks", emaV).field("vwapTicks", vwap).field("atr", a).field("baseAtr", ba).field("relAtr", rel)
          .field("maxRelAtr", maxRelAtr).field("relAtrOk", volOk).field("regimeBefore", regimeBefore)
          .field("regimeAfter", regime).field("redDot", redDot).field("greenDot", greenDot).field("redTriangle", redTri)
          .field("greenTriangle", greenTri).field("position", posBefore.name()).field("firstClosed", firstClosed)
          .field("firstWon", firstWon).field("attempts", attempts).field("additionalAttempts", additional)
          .field("decision", dec));
      if (entry) {
        lg.emit(t, "entry_intent", lg.j(t).field("side", posSide > 0 ? "LONG" : "SHORT").field("signalCloseTicks", b.close())
            .field("stopTicks", (String) null).field("targetTicks", (String) null).field("emaTicks", emaV)
            .field("vwapTicks", vwap).field("relAtr", rel).field("attempts", attempts).field("reason", out.reason()));
      } else if (exit) {
        lg.emit(t, "exit_intent", lg.j(t).field("reason", out.reason()).field("lastCloseTicks", b.close())
            .field("side", posSideForResolve > 0 ? "LONG" : "SHORT"));
      }
    }
    prevClose = c;
    prevVwap = vwap;
    hasPrev = true;
    return out;
  }

  private boolean inSession(int hhmm) {
    int a = Math.max(Math.min(sessStart, 2359), 0), b = Math.max(Math.min(sessEnd, 2359), 0);
    return a <= b ? (hhmm >= a && hhmm <= b) : (hhmm >= a || hhmm <= b);
  }

  private boolean allowed() {
    if (!firstClosed) return true;
    return !firstWon && attempts < additional;
  }

  /** on_trade(PositionClosed): exit price in ticks. */
  private void closeTrade(int exitTicks) {
    double pnl = posSideForResolve * (exitTicks - entryFill) * tick * pointValue * contracts - commission * contracts;
    boolean firstBefore = firstClosed;
    if (!firstClosed) {
      firstClosed = true;
      firstWon = pnl > 0.0;
    } else {
      attempts++;
    }
    if (lg.on()) {
      if (pnl > 0.0) dWins++; else dLosses++;
      lg.emit(lastT, "trade_closed", lg.j(lastT).field("side", posSideForResolve > 0 ? "LONG" : "SHORT")
          .field("entryFillTicks", entryFill).field("exitTicks", exitTicks).field("pnlUsd", pnl)
          .field("wasFirstTradeOfDay", !firstBefore).field("firstWon", firstWon).field("attempts", attempts)
          .field("additionalAttempts", additional));
    }
  }

  private Intent restate() {
    if (pos == Pos.FLAT) return none();
    return new Intent(ID, intentSeq.incrementAndGet(), posSide * contracts, null, null, "holding", false);
  }

  private Intent none() { return Intent.none(ID, intentSeq.incrementAndGet()); }

  private Intent flat(String reason) {
    return new Intent(ID, intentSeq.incrementAndGet(), 0, null, null, ID + " " + reason);
  }

  @Override
  public void onFill(FillEvent fill) {
    if (lg.on()) {
      lastT = Math.max(lastT, fill.eventTimeMs());   // log-only: trade_closed below is stamped with the fill time
      lg.emit(fill.eventTimeMs(), "fill", lg.j(fill.eventTimeMs()).field("role", fill.role().name())
          .field("priceTicks", fill.fillPriceTicks()).field("isBuy", fill.isBuy())
          .field("positionAfter", fill.positionAfter()).field("strategyPositionBefore", pos.name())
          .field("ignored", pos == Pos.FLAT).field("note", "AV has no stop/target; levels are not re-based"));
    }
    if (pos == Pos.FLAT) return;
    switch (fill.role()) {
      case ENTRY -> {
        entryFill = fill.fillPriceTicks();
        posSideForResolve = posSide;
        pos = Pos.OPEN;
      }
      case STOP, TARGET -> {      // no legs are ever sent; kept for safety
        posSideForResolve = posSide;
        closeTrade(fill.fillPriceTicks());
        pos = Pos.FLAT;
      }
      case OTHER -> { }
    }
  }

  @Override
  public void onIntentRejected(Intent intent, String reason) {
    boolean undo = intent.targetPosition() != 0 && intent.reason().startsWith(ID + " entry") && pos == Pos.PENDING;
    if (lg.on()) {
      dRejected++;
      lg.emit(lastT, "rejected", lg.j(lastT).field("why", reason).field("intentTarget", intent.targetPosition())
          .field("intentReason", intent.reason()).field("action", undo ? "reverted_to_flat" : "none"));
    }
    if (undo) {
      pos = Pos.FLAT;
    }
  }

  @Override
  public void onFlattened(String reason) {
    if (lg.on()) lg.emit(lastT, "flattened", lg.j(lastT).field("why", reason).field("positionBefore", pos.name()));
    pos = Pos.FLAT;
  }
}
