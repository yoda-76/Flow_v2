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
import com.flow.core.StrategyLog;
import com.flow.core.Trigger;
import com.flow.journal.Json;

import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
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
  private final SLog lg = new SLog();

  // log-only state (never read by a decision)
  private boolean warmLogged = false, orLogged = false;
  private long lastT = 0;
  private LocalDate loggedDay = null;
  private int dWins = 0, dLosses = 0, dTp = 0, dSl = 0, dTime = 0, dCross = 0, dGap = 0, dRejected = 0, dEval = 0;

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

  @Override public void setLog(StrategyLog log) { lg.set(log); }

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
    if (lg.on()) {
      lg.emit(0, "init", Json.object().field("tickSize", tick).field("orStartMin", orStart).field("orEndMin", orEnd)
          .field("vwapAnchorMin", vwapAnchor).field("adxLen", adxLen).field("adxThreshold", adxTh)
          .field("tpLen", tpLen).field("slLen", slLen).field("lastEntryMin", lastEntry).field("exitMin", exitMin)
          .field("maxEntries", maxEntries).field("maxConsecLosses", maxConsec).field("trail", trail)
          .field("contracts", contracts).field("pointValue", pointValue).field("commissionUsd", commission)
          .field("entrySlipEstTicks", slipEst)
          .field("skipDates", String.join(",", new TreeSet<String>(skipDates.stream().map(LocalDate::toString).toList())))
          .field("skipDatesCount", skipDates.size())
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

  /** Processes one closed 1m bar; only the newest bar of a batch may produce an intent (history just builds state). */
  private Intent processBar(BarHistoryView h, int i, boolean act) {
    long s = h.startMs(i);
    int m = CtTime.ctMinuteOfDay(s);
    LocalDate d = CtTime.ctDate(s);
    int hi = h.high(i), lo = h.low(i), cl = h.close(i);
    final long t = s + 60_000L;
    lastT = t;

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
    if (lg.on() && !warmLogged && curAdx != null) {
      warmLogged = true;
      lg.emit(t, "warmup_done", lg.j(t).field("what", "ADX ready").field("adx", curAdx).field("barsSeen", h.appended()));
    }
    if (!d.equals(curDate)) startDay(d, t);
    if (m >= orStart && m < orEnd) orh = orh == null ? hi : Math.max(orh, hi);
    boolean skip = skipNext;
    skipNext = false;
    if (act && lg.on()) {
      if (!d.equals(loggedDay)) logDayStart(t);
      if (!orLogged && m >= orEnd) {
        orLogged = true;
        lg.emit(t, "or_ready", lg.j(t).fieldOrNull("orHighTicks", orh).field("tradable", dayOk));
      }
    }

    if (pos != Pos.FLAT && posDate != null && !posDate.equals(d)) {   // stale position carried past its date
      pos = Pos.FLAT;
      gapSkip = false;
      Intent st = act ? flat("stale position from " + posDate) : null;
      if (st != null && lg.on()) logExit(t, st, cl);
      return st;
    }
    if (pos != Pos.FLAT) {
      if (!act) return null;
      if (m >= exitMin - 1) {
        if (pos == Pos.OPEN) { pendingResolve = true; resolveWithSlip = true; }
        pos = Pos.FLAT;
        gapSkip = false;
        Intent te = flat("time exit " + (exitMin / 60) + ":" + String.format("%02d", exitMin % 60) + " CT");
        if (lg.on()) { dTime++; logExit(t, te, cl); }
        return te;
      }
      if (pos == Pos.PENDING) return null;
      if (gapSkip) {               // the fill was beyond TP / SL and the runtime has not closed it: undo and flatten
        gapSkip = false;
        pos = Pos.FLAT;
        entries = Math.max(0, entries - 1);
        Intent gs = flat("gap skip");
        if (lg.on()) { dGap++; logExit(t, gs, cl); }
        return gs;
      }
      if (!trail) return null;
      int tp = maxHigh(h, i, tpLen), sl = minLow(h, i, slLen);
      if (cl > tp || cl < sl) {
        pendingResolve = true;
        resolveWithSlip = cl < sl;
        pos = Pos.FLAT;
        skipNext = true;
        Intent tc = flat("trailed level crossed tp=" + tp + " sl=" + sl + " close=" + cl);
        if (lg.on()) { dCross++; logExit(t, tc, cl); }
        return tc;
      }
      if (lg.on() && (tp != tpTicks || sl != slTicks)) {
        lg.emit(t, "levels_update", lg.j(t).field("oldTpTicks", tpTicks).field("newTpTicks", tp)
            .field("oldSlTicks", slTicks).field("newSlTicks", sl).field("closeTicks", cl));
      }
      tpTicks = tp;
      slTicks = sl;
      return new Intent(ID, intentSeq.incrementAndGet(), contracts, slTicks, tpTicks, ID + " trail", false);
    }

    if (lg.on() && act && touched && !skip && dayOk && orh != null && m >= orEnd && m < exitMin
        && (stopped || entries >= maxEntries)) {
      dEval++;
      lg.emit(t, "eval", lg.j(t).field("closeTicks", cl).fieldOrNull("vwapTicks", vw).fieldOrNull("adx", curAdx)
          .fieldOrNull("adxPrev", prevAdx).field("entriesToday", entries).field("consecLosses", consec)
          .field("decision", stopped ? "stopped_for_day" : "max_entries"));
    }
    if (skip || !dayOk || orh == null || m < orEnd || m >= exitMin || stopped || entries >= maxEntries) return null;
    boolean armedB = armed, touchedB = touched;
    if (!armed && cl > orh) armed = true;
    if (armed && vw != null && !touched && lo <= vw) touched = true;
    if (lg.on() && act) {
      if (!armedB && armed) {
        lg.emit(t, "armed", lg.j(t).field("closeTicks", cl).field("orHighTicks", orh));
      }
      if (!touchedB && touched) {
        lg.emit(t, "touch", lg.j(t).field("lowTicks", lo).fieldOrNull("vwapTicks", vw).field("closeTicks", cl));
      }
    }
    if (!touched) return null;
    boolean adxOk = curAdx != null && prevAdx != null && curAdx > adxTh && curAdx <= prevAdx;
    if (lg.on() && act) logEval(t, cl, vw, curAdx, prevAdx, m);
    if (!(vw != null && cl > vw && adxOk) || !act) return null;
    if (m + 1 > lastEntry || m + 1 >= exitMin) return null;

    tpTicks = maxHigh(h, i, tpLen);
    slTicks = minLow(h, i, slLen);
    pos = Pos.PENDING;
    posDate = d;
    gapSkip = false;
    entries++;
    Intent in = new Intent(ID, intentSeq.incrementAndGet(), contracts, slTicks, tpTicks,
        ID + " entry close=" + cl + " vwap=" + String.format("%.1f", vw) + " adx=" + String.format("%.1f", curAdx)
            + " tp=" + tpTicks + " sl=" + slTicks + " n=" + entries, false);
    if (lg.on()) {
      lg.emit(t, "entry_intent", lg.j(t).field("side", "LONG").field("signalCloseTicks", cl).field("stopTicks", slTicks)
          .field("targetTicks", tpTicks).field("vwapTicks", vw).field("adx", curAdx).field("entriesToday", entries)
          .field("maxEntries", maxEntries).field("consecLosses", consec).field("reason", in.reason()));
    }
    return in;
  }

  private void logExit(long t, Intent it, int closeTicks) {
    lg.emit(t, "exit_intent", lg.j(t).field("reason", it.reason()).field("lastCloseTicks", closeTicks));
  }

  /** Evaluation record: one per closed bar while touched (log only; mirrors the gates of processBar). */
  private void logEval(long t, int cl, Double vw, Double curAdx, Double prevAdx, int m) {
    String dec;
    if (vw == null) dec = "no_vwap";
    else if (cl <= vw) dec = "below_vwap";
    else if (curAdx == null || prevAdx == null) dec = "adx_not_ready";
    else if (!(curAdx > adxTh)) dec = "adx_low";
    else if (!(curAdx <= prevAdx)) dec = "adx_rising";
    else if (m + 1 > lastEntry || m + 1 >= exitMin) dec = "after_last_entry";
    else dec = "ENTRY";
    dEval++;
    lg.emit(t, "eval", lg.j(t).field("closeTicks", cl).fieldOrNull("vwapTicks", vw).fieldOrNull("adx", curAdx)
        .fieldOrNull("adxPrev", prevAdx).field("adxThreshold", adxTh).field("entriesToday", entries)
        .field("consecLosses", consec).field("decision", dec));
  }

  private void logDayStart(long t) {
    loggedDay = curDate;
    lg.emit(t, "day_start", lg.j(t).field("ctDate", curDate.toString()).field("etDate", SLog.etDate(t))
        .field("tradable", dayOk).field("why", dayOk ? "ok" : "skipDate").fieldOrNull("orHighTicks", orh)
        .field("armed", armed).field("touched", touched).field("entriesToday", entries)
        .field("consecLosses", consec).field("stopped", stopped).field("position", pos.name()));
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

  private void startDay(LocalDate d, long t) {
    if (lg.on() && curDate != null && curDate.equals(loggedDay)) {
      lg.emit(t, "day_summary", lg.j(t).field("ctDate", curDate.toString()).field("entries", entries)
          .field("wins", dWins).field("losses", dLosses).field("targetFills", dTp).field("stopFills", dSl)
          .field("timeExits", dTime).field("trailCrossExits", dCross).field("gapSkips", dGap)
          .field("rejected", dRejected).field("evals", dEval).field("stoppedForDay", stopped)
          .field("consecLosses", consec).field("armed", armed).field("touched", touched));
    }
    dWins = dLosses = dTp = dSl = dTime = dCross = dGap = dRejected = dEval = 0;
    orLogged = false;
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
    int consecBefore = consec;
    boolean stoppedBefore = stopped;
    if (pnl < 0) consec++; else consec = 0;
    if (consec >= maxConsec) stopped = true;
    if (lg.on()) {
      if (pnl < 0) dLosses++; else dWins++;
      lg.emit(lastT, "trade_closed", lg.j(lastT).field("entryFillTicks", entryFill).field("exitTicks", exitTicks)
          .field("pnlUsd", pnl).field("consecLossesBefore", consecBefore).field("consecLosses", consec)
          .field("stoppedForDay", stopped).field("stoppedNow", stopped && !stoppedBefore));
    }
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
    if (lg.on()) {
      lastT = Math.max(lastT, fill.eventTimeMs());   // log-only: trade_closed below is stamped with the fill time
      lg.emit(fill.eventTimeMs(), "fill", lg.j(fill.eventTimeMs()).field("role", fill.role().name())
          .field("priceTicks", fill.fillPriceTicks()).field("isBuy", fill.isBuy())
          .field("positionAfter", fill.positionAfter()).field("strategyPositionBefore", pos.name())
          .field("ignored", pos == Pos.FLAT).field("stopTicks", slTicks).field("targetTicks", tpTicks));
    }
    if (pos == Pos.FLAT) return;
    switch (fill.role()) {
      case ENTRY -> {
        entryFill = fill.fillPriceTicks();
        pos = Pos.OPEN;
        int est = entryFill - slipEst;
        gapSkip = est >= tpTicks || est <= slTicks;
        if (lg.on()) {
          lg.emit(fill.eventTimeMs(), "fill_levels", lg.j(fill.eventTimeMs()).field("entryFillTicks", entryFill)
              .field("estNextOpenTicks", est).field("stopTicks", slTicks).field("targetTicks", tpTicks)
              .field("gapSkip", gapSkip).field("note", "S2 levels are absolute (not re-based on the fill)"));
        }
      }
      case STOP, TARGET -> {
        pos = Pos.FLAT;
        if (lg.on()) { if (fill.role() == FillEvent.Role.STOP) dSl++; else dTp++; }
        if (gapSkip) {
          gapSkip = false;
          entries = Math.max(0, entries - 1);
          if (lg.on()) {
            lg.emit(fill.eventTimeMs(), "gap_undo", lg.j(fill.eventTimeMs()).field("role", fill.role().name())
                .field("entriesToday", entries).field("note", "same-bar gap round trip: not counted as entry or loss"));
          }
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
    boolean undo = intent.targetPosition() != 0 && intent.reason().startsWith(ID + " entry") && pos == Pos.PENDING;
    if (lg.on()) {
      dRejected++;
      lg.emit(lastT, "rejected", lg.j(lastT).field("why", reason).field("intentTarget", intent.targetPosition())
          .field("intentReason", intent.reason())
          .field("action", undo ? "reverted_to_flat_entries_decremented" : "none")
          .field("entriesToday", undo ? Math.max(0, entries - 1) : entries));
    }
    if (undo) {
      pos = Pos.FLAT;
      entries = Math.max(0, entries - 1);
    }
  }

  @Override
  public void onFlattened(String reason) {
    if (lg.on()) lg.emit(lastT, "flattened", lg.j(lastT).field("why", reason).field("positionBefore", pos.name()));
    pos = Pos.FLAT;
    gapSkip = false;
  }
}
