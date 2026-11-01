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
import com.flow.core.Trigger;

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
  }

  @Override
  public Intent onEvent(MarketState state) {
    BarHistoryView h = state.bars();
    if (h == null || h.size() == 0) return none();
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
    if (pendingResolve) {
      pendingResolve = false;
      closeTrade(h.open(i) - posSideForResolve * slipEst);
    }
    Resampler.Bar b = rs.onMinuteBar(h.startMs(i), h.open(i), h.high(i), h.low(i), h.close(i), h.volume(i));
    return b == null ? null : onSignalBar(b, act);
  }

  private int posSideForResolve = 0;

  /** The Python's per-bar body, line for line. */
  private Intent onSignalBar(Resampler.Bar b, boolean act) {
    ZonedDateTime z = Instant.ofEpochMilli(b.startMs()).atZone(ET);
    LocalDate dnum = z.toLocalDate();
    int hhmm = z.getHour() * 100 + z.getMinute();
    double c = b.close();

    double emaV;
    ema.update(c);
    atr.update(b.high(), b.low(), b.close());
    batr.update(b.high(), b.low(), b.close());

    if (!dnum.equals(day)) {
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
    Double e = ema.value();
    if (e == null) return null;
    emaV = e;
    if (vol <= 0.0) return null;
    double vwap = pv / vol;
    Double a = atr.value(), ba = batr.value();
    if (a == null || ba == null || a <= 0.0 || ba <= 0.0) return null;
    double rel = a / ba;
    boolean volOk = !useRel || rel <= Math.max(maxRelAtr, 0.1);

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
    if (!firstClosed) {
      firstClosed = true;
      firstWon = pnl > 0.0;
    } else {
      attempts++;
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
    if (intent.targetPosition() != 0 && intent.reason().startsWith(ID + " entry") && pos == Pos.PENDING) {
      pos = Pos.FLAT;
    }
  }

  @Override
  public void onFlattened(String reason) {
    pos = Pos.FLAT;
  }
}
