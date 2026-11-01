package com.flow.strategies;

import com.flow.bars.BarHistoryView;
import com.flow.bars.CtTime;
import com.flow.bars.Resampler;
import com.flow.bars.ind.AnchoredVwap;
import com.flow.bars.ind.DailyRange;
import com.flow.core.FillEvent;
import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.StrategyLog;
import com.flow.core.Trigger;
import com.flow.journal.Json;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Strategy 1, Matteo Conti "Vault Break" (long only), port of analysis/gb_nq_backtest/vault_break.py with the BASE
 * flags (--open-ref globex1700 --k 0.30 --atr-len 15 --tr-mode tr). Runs on a 1-MINUTE chart and builds its own 30m CT
 * buckets (Resampler), daily range (DailyRange) and VWAP (AnchoredVwap) from state.bars().
 *
 * Rules: for CT date D, noise-up = (open of the first 1m bar at/after 17:00 CT of the previous CT date) + k * (mean TR of
 * the previous atrLen 00:00-16:00 CT sessions). When a 30m bucket CLOSES between firstSignalClose and lastSignalClose
 * (default 10:00 .. 14:00 CT) with close &gt; noise-up AND close &gt; VWAP (typical price, anchored 00:00 CT) as of that
 * close, and the strategy is flat and has entered fewer than maxEntries times today: go long 1, stop = close - slPts,
 * target = close + tpPts (anchorToFill: re-anchored on the real fill). After a stop/target the bucket in which the exit
 * happened may itself signal again (Python "exitbar" re-entry). When the 1m bar starting at timeExitMin-1 (14:29) closes
 * and a position is open, the strategy returns a flat intent (time exit).
 *
 * KNOWN LIVE vs BACKTEST DIFFERENCES (documented, not bugs):
 *  - The Python skips whole days that have no 1m bar at/after 14:30 CT (holidays / early closes) by looking AHEAD.
 *    Live cannot; list such dates in "skipDates" (e.g. "2026-11-27,2026-12-24") to reproduce the skip.
 *  - Python's time exit fills at the CLOSE of the 14:29 bar; live (and BarReplay) exit at market after that close, i.e.
 *    about the 14:30 open.
 *  - After a mid-day restart the strategy cannot know how many entries it already took today (counter restarts at 0)
 *    nor that it is in a position (D-24: the runtime never touches a position that predates the study).
 *
 * Parameters (flat JSON file at StrategyConfig "strategyConfigPath"; all optional):
 * <pre>
 * key               default  meaning
 * k                 0.30     noise-up = open + k * dailyRangeSma
 * atrLen            15       sessions in the daily-range mean
 * trModeHl          false    true: session range H-L instead of true range
 * sessionStartMin   0        daily-range session start, CT minute of day
 * sessionEndMin     960      daily-range session end (exclusive)
 * openRefMin        1020     the reference open = first 1m bar with minute &gt;= this on the PREVIOUS CT date
 * vwapAnchorMin     0        VWAP restarts at this CT minute (&gt;= 0 only)
 * bucketMin         30       signal bar length in minutes
 * firstSignalClose  600      earliest bucket-close minute (10:00)
 * lastSignalClose   840      latest bucket-close minute (14:00)
 * tpPts             40.0     target distance in points
 * slPts             75.0     stop distance in points
 * maxEntries        3        entries per CT date
 * timeExitMin       870      flat when the bar starting at timeExitMin-1 closes
 * skipDates         ""       comma separated CT dates with no trading, e.g. "2026-11-26,2026-12-24"
 * tickSize          (chart)  only used when the runtime passes none
 * </pre>
 */
public final class VaultBreakStrategy implements FlowStrategy {
  public static final String ID = "mc_vault_break";

  private enum Pos { FLAT, PENDING, OPEN }

  private final AtomicLong intentSeq = new AtomicLong(0);
  private final SLog lg = new SLog();

  // log-only state (never read by a decision)
  private boolean warmLogged = false;
  private long lastT = 0;
  private LocalDate loggedDay = null;
  private Integer dayOpenRef = null;
  private Double dayVol = null;
  private String dayWhy = null;
  private int dTp = 0, dSl = 0, dTime = 0, dRejected = 0, dEval = 0, dNoSignal = 0;

  // parameters
  private double k = 0.30;
  private int atrLen = 15;
  private boolean trHl = false;
  private int sessStart = 0, sessEnd = 960, openRefMin = 1020, vwapAnchor = 0, bucketMin = 30;
  private int firstClose = 600, lastClose = 840, maxEntries = 3, timeExitMin = 870;
  private int tpTicks, slTicks;
  private Set<LocalDate> skipDates = Set.of();

  // indicators
  private DailyRange dr;
  private AnchoredVwap vwap;
  private Resampler rs;

  // bar watermark
  private long seen = 0;

  // day state
  private LocalDate curDate = null;
  private final Map<LocalDate, Integer> refOpen = new HashMap<>();
  private boolean dayOk = false;
  private double noise = 0;
  private int entries = 0;

  // position state
  private Pos pos = Pos.FLAT;
  private LocalDate posDate = null;
  private int stopTicks, targetTicks;

  @Override public String id() { return ID; }

  @Override public void setLog(StrategyLog log) { lg.set(log); }

  @Override public Set<String> requires() { return Set.of(BarHistoryView.FEATURE_ID); }

  @Override public Set<Trigger> triggers() { return Set.of(new Trigger.BarClose()); }

  @Override
  public void onInit(StrategyConfig cfg) {
    StrategyParams p = StrategyParams.load(cfg, ID);
    double tick = p.tickSize();
    k = p.getDouble("k", 0.30);
    atrLen = p.getInt("atrLen", 15);
    trHl = p.getBoolean("trModeHl", false);
    sessStart = p.getInt("sessionStartMin", 0);
    sessEnd = p.getInt("sessionEndMin", 960);
    openRefMin = p.getInt("openRefMin", 1020);
    vwapAnchor = p.getInt("vwapAnchorMin", 0);
    bucketMin = p.getInt("bucketMin", 30);
    firstClose = p.getInt("firstSignalClose", 600);
    lastClose = p.getInt("lastSignalClose", 840);
    maxEntries = p.getInt("maxEntries", 3);
    timeExitMin = p.getInt("timeExitMin", 870);
    tpTicks = (int) Math.round(p.getDouble("tpPts", 40.0) / tick);
    slTicks = (int) Math.round(p.getDouble("slPts", 75.0) / tick);
    skipDates = p.getDates("skipDates");
    dr = new DailyRange(sessStart, sessEnd, atrLen, trHl ? DailyRange.TrMode.HL : DailyRange.TrMode.TR);
    vwap = new AnchoredVwap(vwapAnchor);
    rs = new Resampler(bucketMin);
    if (lg.on()) {
      lg.emit(0, "init", Json.object().field("tickSize", tick).field("k", k).field("atrLen", atrLen)
          .field("trModeHl", trHl).field("sessionStartMin", sessStart).field("sessionEndMin", sessEnd)
          .field("openRefMin", openRefMin).field("vwapAnchorMin", vwapAnchor).field("bucketMin", bucketMin)
          .field("firstSignalClose", firstClose).field("lastSignalClose", lastClose).field("maxEntries", maxEntries)
          .field("timeExitMin", timeExitMin).field("tpTicks", tpTicks).field("slTicks", slTicks)
          .field("tpPts", p.getDouble("tpPts", 40.0)).field("slPts", p.getDouble("slPts", 75.0))
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
    double hi = h.high(i), lo = h.low(i), cl = h.close(i), v = h.volume(i);
    final long t = s + 60_000L;
    lastT = t;

    Double vwapBefore = vwap.value();
    dr.update(s, hi, lo, cl);
    vwap.update(s, hi, lo, cl, v);
    if (lg.on() && !warmLogged && dr.smaVol() != null) {
      warmLogged = true;
      lg.emit(t, "warmup_done", lg.j(t).field("what", "daily range sma ready").field("volTicks", dr.smaVol())
          .field("barsSeen", h.appended()));
    }
    if (!d.equals(curDate)) startDay(d, t);
    if (m >= openRefMin && !refOpen.containsKey(d)) refOpen.put(d, h.open(i));
    Resampler.Bar bucket = rs.onMinuteBar(s, h.open(i), h.high(i), h.low(i), h.close(i), h.volume(i));

    if (act && lg.on() && !d.equals(loggedDay)) logDayStart(t);
    Intent out = null;
    // stale position carried past its date (should have been flattened) -> close it
    if (pos != Pos.FLAT && posDate != null && !posDate.equals(d)) {
      pos = Pos.FLAT;
      if (act) out = flat("stale position from " + posDate);
    }
    // time exit: the bar that ends at timeExitMin has just closed
    if (pos != Pos.FLAT && m >= timeExitMin - 1) {
      pos = Pos.FLAT;
      if (act) out = flat("time exit " + (timeExitMin / 60) + ":" + String.format("%02d", timeExitMin % 60) + " CT");
    }
    if (out != null) {
      if (lg.on()) {
        dTime++;
        lg.emit(t, "exit_intent", lg.j(t).field("reason", out.reason()).field("lastCloseTicks", cl));
      }
      return out;
    }
    if (lg.on() && act && bucket != null && bucket.ctDate().equals(curDate)) logEval(bucket, m, vwapBefore, t);
    if (!act || bucket == null || pos != Pos.FLAT || !dayOk || entries >= maxEntries) return null;
    if (!bucket.ctDate().equals(curDate)) return null;
    int closeMin = bucket.bucketStartMinute() + bucketMin;
    if (closeMin < firstClose || closeMin > lastClose) return null;
    // VWAP as of the bucket's last 1m bar: the current bar completed it (rule a) or the previous one did (rule b)
    Double vw = (m % bucketMin == bucketMin - 1) ? vwap.value() : vwapBefore;
    if (vw == null) return null;
    if (!(bucket.close() > noise && bucket.close() > vw)) return null;

    pos = Pos.PENDING;
    posDate = d;
    entries++;
    stopTicks = bucket.close() - slTicks;
    targetTicks = bucket.close() + tpTicks;
    Intent in = new Intent(ID, intentSeq.incrementAndGet(), 1, stopTicks, targetTicks,
        ID + " entry close=" + bucket.close() + " noise=" + String.format("%.1f", noise)
            + " vwap=" + String.format("%.1f", vw) + " n=" + entries, true);
    if (lg.on()) {
      lg.emit(t, "entry_intent", lg.j(t).field("side", "LONG").field("signalCloseTicks", bucket.close())
          .field("stopTicks", stopTicks).field("targetTicks", targetTicks).field("noiseUpTicks", noise)
          .field("vwapTicks", vw).field("entriesToday", entries).field("maxEntries", maxEntries)
          .field("reason", in.reason()));
    }
    return in;
  }

  /** Evaluation record: one per completed signal-window bucket (log only; mirrors the gates of processBar). */
  private void logEval(Resampler.Bar bucket, int m, Double vwapBefore, long t) {
    int closeMin = bucket.bucketStartMinute() + bucketMin;
    if (closeMin < firstClose || closeMin > lastClose) return;
    Double vw = (m % bucketMin == bucketMin - 1) ? vwap.value() : vwapBefore;
    boolean aboveNoise = bucket.close() > noise;
    boolean aboveVwap = vw != null && bucket.close() > vw;
    String dec;
    if (pos != Pos.FLAT) dec = "blocked_position_open";
    else if (!dayOk) dec = "blocked_day_not_tradable";
    else if (entries >= maxEntries) dec = "blocked_max_entries";
    else if (vw == null) dec = "no_vwap";
    else dec = aboveNoise && aboveVwap ? "ENTRY" : "no_signal";
    dEval++;
    if (dec.equals("no_signal")) dNoSignal++;
    lg.emit(t, "eval", lg.j(t).field("bucketCloseMin", closeMin).field("bucketCloseTicks", bucket.close())
        .field("noiseUpTicks", noise).fieldOrNull("vwapTicks", vw)
        .fieldOrNull("openRefTicks", dayOpenRef).fieldOrNull("volTicks", dayVol)
        .field("closeAboveNoise", aboveNoise).field("closeAboveVwap", aboveVwap)
        .field("entriesToday", entries).field("position", pos.name()).field("decision", dec));
  }

  private void logDayStart(long t) {
    loggedDay = curDate;
    lg.emit(t, "day_start", lg.j(t).field("ctDate", curDate.toString()).field("etDate", SLog.etDate(t))
        .field("tradable", dayOk).field("why", dayWhy == null ? "ok" : dayWhy)
        .fieldOrNull("openRefTicks", dayOpenRef).fieldOrNull("volTicks", dayVol)
        .field("noiseUpTicks", noise).field("maxEntries", maxEntries).field("position", pos.name()));
  }

  private void startDay(LocalDate d, long t) {
    if (lg.on() && curDate != null && curDate.equals(loggedDay)) {
      lg.emit(t, "day_summary", lg.j(t).field("ctDate", curDate.toString()).field("entries", entries)
          .field("targetFills", dTp).field("stopFills", dSl).field("timeExits", dTime).field("rejected", dRejected)
          .field("evals", dEval).field("noSignalEvals", dNoSignal));
    }
    dTp = dSl = dTime = dRejected = dEval = dNoSignal = 0;
    curDate = d;
    entries = 0;
    refOpen.keySet().removeIf(x -> x.isBefore(d.minusDays(7)));
    Integer open = refOpen.get(d.minusDays(1));
    Double vol = dr.smaVol();
    dayOk = open != null && vol != null && !skipDates.contains(d);
    noise = dayOk ? open + k * vol : 0;
    dayOpenRef = open;
    dayVol = vol;
    dayWhy = dayOk ? null : skipDates.contains(d) ? "skipDate" : open == null ? "no_open_ref" : "no_vol";
  }

  private Intent restate() {
    if (pos == Pos.FLAT) return none();
    return new Intent(ID, intentSeq.incrementAndGet(), 1, stopTicks, targetTicks, "holding", true);
  }

  private Intent none() { return Intent.none(ID, intentSeq.incrementAndGet()); }

  private Intent flat(String reason) {
    return new Intent(ID, intentSeq.incrementAndGet(), 0, null, null, ID + " " + reason);
  }

  @Override
  public void onFill(FillEvent fill) {
    if (lg.on()) {
      boolean entry = fill.role() == FillEvent.Role.ENTRY;
      lg.emit(fill.eventTimeMs(), "fill", lg.j(fill.eventTimeMs()).field("role", fill.role().name())
          .field("priceTicks", fill.fillPriceTicks()).field("isBuy", fill.isBuy())
          .field("positionAfter", fill.positionAfter()).field("strategyPositionBefore", pos.name())
          .field("ignored", pos == Pos.FLAT)
          .fieldOrNull("newStopTicks", entry ? (Integer) (fill.fillPriceTicks() - slTicks) : null)
          .fieldOrNull("newTargetTicks", entry ? (Integer) (fill.fillPriceTicks() + tpTicks) : null));
    }
    if (pos == Pos.FLAT) return;
    switch (fill.role()) {
      case ENTRY -> {
        int f = fill.fillPriceTicks();
        stopTicks = f - slTicks;
        targetTicks = f + tpTicks;
        pos = Pos.OPEN;
      }
      case STOP -> { dSl++; pos = Pos.FLAT; }
      case TARGET -> { dTp++; pos = Pos.FLAT; }
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
  }
}
