package com.flow.strategies;

import com.flow.bars.BarHistoryView;
import com.flow.bars.CtTime;
import com.flow.bars.ind.AnchoredVwap;
import com.flow.bars.ind.WilderAdx;
import com.flow.core.FillEvent;
import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.Trigger;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Strategy 2, Matteo Conti "VWAP Pullback + ADX" (MNQ, long only), port of analysis/gb_nq_backtest/vwap_adx.py with the
 * BASE configuration (script defaults + {@code --trail both}). Runs on a 1-MINUTE chart.
 *
 * Rules, per CT date D: OR high = highest 1m high of the bars starting in [orStartMin, orEndMin) (08:30-09:00). From
 * orEndMin on, every closed 1m bar: ARM when close &gt; OR high (stays armed for the day); TOUCH when armed and
 * low &lt;= VWAP (typical price, anchored 08:30 CT, same bar allowed); SIGNAL (recheck mode) = touched && close &gt; VWAP
 * && ADX &gt; adxThreshold && ADX &lt;= the previous bar's ADX (Wilder ADX(adxLen) over the CONTINUOUS 1m series, all
 * hours). The entry (market, filled at the next 1m open) must be at minute &lt;= lastEntryMin (14:30) and &lt; exitMin
 * (14:55). Initial TP = highest high of the last tpLen (5) bars incl. the signal bar, SL = lowest low of the last slLen
 * (20) bars incl. the signal bar, both ABSOLUTE levels (anchorToFill=false).
 *
 * TRAILING (trail=true, the Python "--trail both"): while open, after every closed bar the strategy recomputes TP/SL from
 * the windows ending at that bar and sends the SAME position with the new levels; the runtime moves the resting legs, so
 * they apply from the NEXT bar -- exactly the Python rule (the level for bar k comes from the window ending at bar k-1).
 * If a new level is already strictly crossed by the current close (close &gt; TP or close &lt; SL) the strategy returns
 * flat instead (cannot occur with these windows, TP &gt;= high &gt;= close &gt;= low &gt;= SL; kept as a safety net; an EQUAL
 * level is not "crossed" -- a resting limit/stop at the last price is legal and the Python keeps trading it).
 *
 * Day limits: at most maxEntries (4) entries per CT date, one at a time; the day stops after maxConsecLosses (2)
 * consecutive losing trades. A loss = net P&amp;L &lt; 0 with pnl = (exit - entry fill) * pointValue - commissionUsd. For
 * the runtime's own TP/SL fills the real fill price is used; for the strategy's own exits (cross / time) the exit price is
 * estimated one bar later as that bar's open (minus entrySlipEstTicks on the stop side). After any exit the touched flag is
 * cleared and the exit bar is not evaluated (the Python resumes at the bar after the exit bar); arming stays.
 * Time exit: flat intent when the 1m bar starting at exitMin-1 (14:54) closes.
 *
 * GAP SKIP (live equivalent): the Python skips a signal when the next bar's open is already at/above TP or at/below SL.
 * Live cannot know the next open, so the entry is emitted and, once the ENTRY fill is known, the estimated open
 * (fill - entrySlipEstTicks) is compared with the levels: beyond -> the trade is treated as skipped (not counted as an
 * entry or a loss; if the runtime has not already closed it on the gap, the strategy returns flat). In BarReplay such a
 * trade shows as a one-bar GAP_TP / GAP_SL round trip that the Python does not have.
 *
 * KNOWN LIVE vs BACKTEST DIFFERENCES: Python skips days with no bar at/after 14:30 CT (holidays, early closes) by looking
 * ahead -- list them in "skipDates"; days without an OR bar are skipped naturally (no OR high = never armed); the
 * Python's TIME exit fills at the CLOSE of the 14:54 bar, live exits at the next open; a mid-day restart restarts the
 * entry/loss counters; signals that "would have been traded" during startup history are not replayed.
 *
 * Parameters (flat JSON file at StrategyConfig "strategyConfigPath"; all optional):
 * <pre>
 * key                 default  meaning
 * orStartMin          510      opening range start, CT minute of day (08:30)
 * orEndMin            540      opening range end (exclusive) and first bar that can arm (09:00)
 * vwapAnchorMin       510      VWAP restarts at this CT minute
 * adxLen              14       Wilder ADX length
 * adxThreshold        20.0     ADX must be &gt; this
 * tpLen               5        TP = highest high of this many bars incl. the signal bar
 * slLen               20       SL = lowest low of this many bars incl. the signal bar
 * lastEntryMin        870      latest CT minute of the ENTRY (fill) bar
 * exitMin             895      no entries from here; flat when the bar starting at exitMin-1 closes
 * maxEntries          4        entries per CT date
 * maxConsecLosses     2        consecutive losing trades that stop the day
 * trail               true     false = keep the signal-time TP/SL for the whole trade
 * contracts           1        position size
 * pointValue          2.0      USD per point (MNQ), only for the win/loss test
 * commissionUsd       1.0      round-trip commission per contract, only for the win/loss test
 * entrySlipEstTicks   1        assumed slippage when estimating the next open (gap skip, own-exit prices)
 * skipDates           ""       comma separated CT dates with no trading, e.g. "2026-11-26,2026-12-24"
 * tickSize            (chart)  only used when the runtime passes none
 * </pre>
 */
public final class VwapPullbackAdxStrategy implements FlowStrategy {
  public static final String ID = "mc_vwap_adx";

  private enum Pos { FLAT, PENDING, OPEN }

  private final AtomicLong intentSeq = new AtomicLong(0);

  // parameters
  private int orStart = 510, orEnd = 540, vwapAnchor = 510, adxLen = 14, tpLen = 5, slLen = 20;
  private int lastEntry = 870, exitMin = 895, maxEntries = 4, maxConsec = 2, contracts = 1, slipEst = 1;
  private double adxTh = 20.0, pointValue = 2.0, commission = 1.0, tick = 0.25;
  private boolean trail = true;
  private Set<LocalDate> skipDates = Set.of();

  // indicators
  private WilderAdx adx;
  private AnchoredVwap vwap;

  private long seen = 0;

  // day state
  private LocalDate curDate = null;
  private boolean dayOk = false;
  private Integer orh = null;
  private boolean armed = false, touched = false;
  private int entries = 0, consec = 0;
  private boolean stopped = false;
  private boolean skipNext = false;

  // exit estimate waiting for the next bar's open
  private boolean pendingResolve = false, resolveWithSlip = false;

  // position state
  private Pos pos = Pos.FLAT;
  private LocalDate posDate = null;
  private int tpTicks, slTicks, entryFill;
  private boolean gapSkip = false;

  @Override public String id() { return ID; }

  @Override public Set<String> requires() { return Set.of(BarHistoryView.FEATURE_ID); }

  @Override public Set<Trigger> triggers() { return Set.of(new Trigger.BarClose()); }

  @Override
  public void onInit(StrategyConfig cfg) {
    StrategyParams p = StrategyParams.load(cfg, ID);
    tick = p.tickSize();
    orStart = p.getInt("orStartMin", 510);
    orEnd = p.getInt("orEndMin", 540);
    vwapAnchor = p.getInt("vwapAnchorMin", 510);
    adxLen = p.getInt("adxLen", 14);
    adxTh = p.getDouble("adxThreshold", 20.0);
    tpLen = p.getInt("tpLen", 5);
    slLen = p.getInt("slLen", 20);
    lastEntry = p.getInt("lastEntryMin", 870);
    exitMin = p.getInt("exitMin", 895);
    maxEntries = p.getInt("maxEntries", 4);
    maxConsec = p.getInt("maxConsecLosses", 2);
    trail = p.getBoolean("trail", true);
    contracts = p.getInt("contracts", 1);
    pointValue = p.getDouble("pointValue", 2.0);
    commission = p.getDouble("commissionUsd", 1.0);
    slipEst = p.getInt("entrySlipEstTicks", 1);
    skipDates = p.getDates("skipDates");
    adx = new WilderAdx(adxLen);
    vwap = new AnchoredVwap(vwapAnchor);
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

  /** Processes one closed 1m bar; only the newest bar of a batch may produce an intent (history just builds state). */
  private Intent processBar(BarHistoryView h, int i, boolean act) {
    long s = h.startMs(i);
    int m = CtTime.ctMinuteOfDay(s);
    LocalDate d = CtTime.ctDate(s);
    int hi = h.high(i), lo = h.low(i), cl = h.close(i);

    if (pendingResolve) {          // the strategy's own exit: this bar's open is the best estimate of its fill
      pendingResolve = false;
      closeTrade(h.open(i) - (resolveWithSlip ? slipEst : 0));
      touched = false;
    }
    Double prevAdx = adx.value();
    adx.update(hi, lo, cl);
    Double curAdx = adx.value();
    vwap.update(s, hi, lo, cl, h.volume(i));
    Double vw = vwap.value();
    if (!d.equals(curDate)) startDay(d);
    if (m >= orStart && m < orEnd) orh = orh == null ? hi : Math.max(orh, hi);
    boolean skip = skipNext;
    skipNext = false;

    if (pos != Pos.FLAT && posDate != null && !posDate.equals(d)) {   // stale position carried past its date
      pos = Pos.FLAT;
      gapSkip = false;
      return act ? flat("stale position from " + posDate) : null;
    }
    if (pos != Pos.FLAT) {
      if (!act) return null;
      if (m >= exitMin - 1) {
        if (pos == Pos.OPEN) { pendingResolve = true; resolveWithSlip = true; }
        pos = Pos.FLAT;
        gapSkip = false;
        return flat("time exit " + (exitMin / 60) + ":" + String.format("%02d", exitMin % 60) + " CT");
      }
      if (pos == Pos.PENDING) return null;
      if (gapSkip) {               // the fill was beyond TP / SL and the runtime has not closed it: undo and flatten
        gapSkip = false;
        pos = Pos.FLAT;
        entries = Math.max(0, entries - 1);
        return flat("gap skip");
      }
      if (!trail) return null;
      int tp = maxHigh(h, i, tpLen), sl = minLow(h, i, slLen);
      if (cl > tp || cl < sl) {
        pendingResolve = true;
        resolveWithSlip = cl < sl;
        pos = Pos.FLAT;
        skipNext = true;
        return flat("trailed level crossed tp=" + tp + " sl=" + sl + " close=" + cl);
      }
      tpTicks = tp;
      slTicks = sl;
      return new Intent(ID, intentSeq.incrementAndGet(), contracts, slTicks, tpTicks, ID + " trail", false);
    }

    if (skip || !dayOk || orh == null || m < orEnd || m >= exitMin || stopped || entries >= maxEntries) return null;
    if (!armed && cl > orh) armed = true;
    if (armed && vw != null && !touched && lo <= vw) touched = true;
    if (!touched) return null;
    boolean adxOk = curAdx != null && prevAdx != null && curAdx > adxTh && curAdx <= prevAdx;
    if (!(vw != null && cl > vw && adxOk) || !act) return null;
    if (m + 1 > lastEntry || m + 1 >= exitMin) return null;

    tpTicks = maxHigh(h, i, tpLen);
    slTicks = minLow(h, i, slLen);
    pos = Pos.PENDING;
    posDate = d;
    gapSkip = false;
    entries++;
    return new Intent(ID, intentSeq.incrementAndGet(), contracts, slTicks, tpTicks,
        ID + " entry close=" + cl + " vwap=" + String.format("%.1f", vw) + " adx=" + String.format("%.1f", curAdx)
            + " tp=" + tpTicks + " sl=" + slTicks + " n=" + entries, false);
  }

  private static int maxHigh(BarHistoryView h, int i, int len) {
    int best = Integer.MIN_VALUE;
    for (int q = Math.max(0, i - len + 1); q <= i; q++) best = Math.max(best, h.high(q));
    return best;
  }

  private static int minLow(BarHistoryView h, int i, int len) {
    int best = Integer.MAX_VALUE;
    for (int q = Math.max(0, i - len + 1); q <= i; q++) best = Math.min(best, h.low(q));
    return best;
  }

  private void startDay(LocalDate d) {
    curDate = d;
    dayOk = !skipDates.contains(d);
    orh = null;
    armed = false;
    touched = false;
    entries = 0;
    consec = 0;
    stopped = false;
  }

  /** Win/loss bookkeeping of a finished trade; exit price in ticks. */
  private void closeTrade(int exitTicks) {
    double pnl = (exitTicks - entryFill) * tick * pointValue * contracts - commission * contracts;
    if (pnl < 0) consec++; else consec = 0;
    if (consec >= maxConsec) stopped = true;
  }

  private Intent restate() {
    if (pos == Pos.FLAT) return none();
    return new Intent(ID, intentSeq.incrementAndGet(), contracts, slTicks, tpTicks, "holding", false);
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
        pos = Pos.OPEN;
        int est = entryFill - slipEst;
        gapSkip = est >= tpTicks || est <= slTicks;
      }
      case STOP, TARGET -> {
        pos = Pos.FLAT;
        if (gapSkip) {
          gapSkip = false;
          entries = Math.max(0, entries - 1);
          return;
        }
        closeTrade(fill.fillPriceTicks());
        touched = false;
        skipNext = true;
      }
      case OTHER -> { }
    }
  }

  @Override
  public void onIntentRejected(Intent intent, String reason) {
    if (intent.targetPosition() != 0 && intent.reason().startsWith(ID + " entry") && pos == Pos.PENDING) {
      pos = Pos.FLAT;
      entries = Math.max(0, entries - 1);
    }
  }

  @Override
  public void onFlattened(String reason) {
    pos = Pos.FLAT;
    gapSkip = false;
  }
}
