package com.flow.strategies;

import com.flow.bars.BarHistoryView;
import com.flow.bars.CtTime;
import com.flow.bars.Resampler;
import com.flow.bars.ind.DailyRange;
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
 * Strategy 3, Matteo Conti "Overnight-Bias ORB" (long and short), port of analysis/gb_nq_backtest/overnight_orb.py with
 * its DEFAULTS (variants 1A..12A). Runs on a 1-MINUTE chart and builds 15m CT bars itself (Resampler).
 *
 * Rules, per CT date D: ONR = high/low of the 15m bars starting in [onrStartMin, orMin) (00:00-08:30). The OR is the
 * 15m bar starting at orMin (08:30); the bias comes from the OR bar's OPEN: position in the ONR = (open - onrLow) /
 * (onrHigh - onrLow); &gt;= 2/3 long, &lt;= 1/3 short (an open outside the ONR counts as top / bottom). After the OR, the
 * first 15m bar that CLOSES beyond the OR in the bias direction (long: close &gt; OR high; short: close &lt; OR low) with
 * Wilder ADX(adxLen) &gt; adxThreshold -- ADX runs over the CONTINUOUS 15m bar series (all hours, no reset) -- and whose
 * close time is in [firstFillMin, lastFillMin] (09:00 .. 14:15 CT) gives the entry: one trade per CT date.
 * Stop = signal close -/+ slK * vol, target = tpR * that distance, vol = mean true range of the previous volLen
 * 00:00-16:00 CT sessions (DailyRange). Long levels are rounded DOWN to the tick, short levels UP, from the unrounded
 * distance (like the Python), then re-anchored to the real fill (anchorToFill; on the ENTRY fill the strategy recomputes
 * the same levels from the fill price so its restated intent equals the real bracket). Flat when the bar starting at
 * timeExitMin-1 (14:29) closes.
 *
 * KNOWN LIVE vs BACKTEST DIFFERENCES: the Python skips days with no 1m bar between 14:30 and 16:00 CT (early closes,
 * holidays) by looking AHEAD -- list such dates in "skipDates"; a mid-day restart loses today's "already traded" flag;
 * the Python requires the NEXT 15m bar to exist, live simply fills at the next 1m open.
 *
 * Parameters (flat JSON file at StrategyConfig "strategyConfigPath"; all optional):
 * <pre>
 * key               default  meaning
 * onrStartMin       0        ONR start, CT minute of day
 * orMin             510      OR bar start (08:30) and ONR end
 * barMin            15       bar length in minutes (must divide 1440)
 * adxLen            14       Wilder ADX length
 * adxThreshold      20.0     entry needs ADX &gt; this
 * volLen            15       sessions in the daily-range mean
 * sessionStartMin   0        daily-range session start (CT minute)
 * sessionEndMin     960      daily-range session end (exclusive)
 * slK               0.30     stop distance = slK * vol
 * tpR               3.0      target distance = tpR * stop distance
 * firstFillMin      540      earliest signal-bar close minute (09:00)
 * lastFillMin       855      latest signal-bar close minute (14:15)
 * timeExitMin       870      flat when the bar starting at timeExitMin-1 closes
 * skipDates         ""       comma separated CT dates with no trading
 * tickSize          (chart)  only used when the runtime passes none
 * </pre>
 */
public final class OvernightBiasOrbStrategy implements FlowStrategy {
  public static final String ID = "mc_overnight_orb";

  private enum Pos { FLAT, PENDING, OPEN }

  private final AtomicLong intentSeq = new AtomicLong(0);

  // parameters
  private int onrStart = 0, orMin = 510, barMin = 15, adxLen = 14, volLen = 15, sessStart = 0, sessEnd = 960;
  private int firstFill = 540, lastFill = 855, timeExitMin = 870;
  private double adxTh = 20.0, slK = 0.30, tpR = 3.0;
  private Set<LocalDate> skipDates = Set.of();

  // indicators
  private DailyRange dr;
  private WilderAdx adx;
  private Resampler rs;

  private long seen = 0;

  // day state
  private LocalDate curDate = null;
  private boolean volOk = false;
  private double vol = 0;
  private double onrHi, onrLo;
  private boolean haveOnr = false;
  private int side = 0;              // +1 / -1 once the bias is known, else 0
  private int orh, orl;
  private boolean tradedToday = false;

  // position state
  private Pos pos = Pos.FLAT;
  private int posSide = 0;
  private LocalDate posDate = null;
  private double distTicks;          // unrounded stop distance in ticks, for re-anchoring on the fill
  private int stopTicks, targetTicks;

  @Override public String id() { return ID; }

  @Override public Set<String> requires() { return Set.of(BarHistoryView.FEATURE_ID); }

  @Override public Set<Trigger> triggers() { return Set.of(new Trigger.BarClose()); }

  @Override
  public void onInit(StrategyConfig cfg) {
    StrategyParams p = StrategyParams.load(cfg, ID);
    onrStart = p.getInt("onrStartMin", 0);
    orMin = p.getInt("orMin", 510);
    barMin = p.getInt("barMin", 15);
    adxLen = p.getInt("adxLen", 14);
    adxTh = p.getDouble("adxThreshold", 20.0);
    volLen = p.getInt("volLen", 15);
    sessStart = p.getInt("sessionStartMin", 0);
    sessEnd = p.getInt("sessionEndMin", 960);
    slK = p.getDouble("slK", 0.30);
    tpR = p.getDouble("tpR", 3.0);
    firstFill = p.getInt("firstFillMin", 540);
    lastFill = p.getInt("lastFillMin", 855);
    timeExitMin = p.getInt("timeExitMin", 870);
    skipDates = p.getDates("skipDates");
    dr = new DailyRange(sessStart, sessEnd, volLen, DailyRange.TrMode.TR);
    adx = new WilderAdx(adxLen);
    rs = new Resampler(barMin);
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
    long s = h.startMs(i);
    int m = CtTime.ctMinuteOfDay(s);
    LocalDate d = CtTime.ctDate(s);

    dr.update(s, h.high(i), h.low(i), h.close(i));
    if (!d.equals(curDate)) startDay(d);
    Resampler.Bar bar = rs.onMinuteBar(s, h.open(i), h.high(i), h.low(i), h.close(i), h.volume(i));

    Intent out = null;
    if (pos != Pos.FLAT && posDate != null && !posDate.equals(d)) {
      pos = Pos.FLAT;
      if (act) out = flat("stale position from " + posDate);
    }
    if (pos != Pos.FLAT && m >= timeExitMin - 1) {
      pos = Pos.FLAT;
      if (act) out = flat("time exit " + (timeExitMin / 60) + ":" + String.format("%02d", timeExitMin % 60) + " CT");
    }

    Intent entry = null;
    if (bar != null) {
      // the ADX runs over every 15m bar, in order, whether or not we can trade it
      adx.update(bar.high(), bar.low(), bar.close());
      if (bar.ctDate().equals(curDate)) entry = onBar(bar, act);
    }
    return out != null ? out : entry;
  }

  private void startDay(LocalDate d) {
    curDate = d;
    Double v = dr.smaVol();
    volOk = v != null && v > 0 && !skipDates.contains(d);
    vol = volOk ? v : 0;
    haveOnr = false;
    onrHi = Double.NEGATIVE_INFINITY;
    onrLo = Double.POSITIVE_INFINITY;
    side = 0;
    tradedToday = false;
  }

  /** A completed 15m bar of today. */
  private Intent onBar(Resampler.Bar b, boolean act) {
    int start = b.bucketStartMinute();
    if (start >= onrStart && start < orMin) {
      haveOnr = true;
      onrHi = Math.max(onrHi, b.high());
      onrLo = Math.min(onrLo, b.low());
      return null;
    }
    if (start == orMin) {
      orh = b.high();
      orl = b.low();
      double rng = onrHi - onrLo;
      if (!haveOnr || rng <= 0) return null;
      double p = (b.open() - onrLo) / rng;
      side = p >= 2.0 / 3 ? 1 : (p <= 1.0 / 3 ? -1 : 0);
      return null;
    }
    if (start < orMin || side == 0 || !volOk || tradedToday || pos != Pos.FLAT || !act) return null;
    int closeMin = start + barMin;
    if (closeMin < firstFill || closeMin > lastFill) return null;
    boolean beyond = side == 1 ? b.close() > orh : b.close() < orl;
    Double a = adx.value();
    if (!beyond || a == null || !(a > adxTh)) return null;

    double d = slK * vol;   // ticks, unrounded
    int stop = roundLevel(b.close() - side * d, side);
    int target = roundLevel(b.close() + side * tpR * d, side);
    if (side == 1 ? !(stop < b.close() && target > b.close()) : !(target < b.close() && stop > b.close())) {
      tradedToday = true;   // the Python counts the day as used (bad_levels) and moves on
      return null;
    }
    distTicks = d;
    pos = Pos.PENDING;
    posSide = side;
    posDate = curDate;
    tradedToday = true;
    stopTicks = stop;
    targetTicks = target;
    return new Intent(ID, intentSeq.incrementAndGet(), side, stopTicks, targetTicks,
        ID + " entry side=" + (side > 0 ? "L" : "S") + " close=" + b.close() + " adx=" + String.format("%.1f", a)
            + " vol=" + String.format("%.1f", vol), true);
  }

  /** Round against the trade like the Python: long levels down, short levels up (1e-9 guard as in rnd_down / rnd_up). */
  private static int roundLevel(double ticks, int side) {
    return side == 1 ? (int) Math.floor(ticks + 1e-9) : (int) Math.ceil(ticks - 1e-9);
  }

  private Intent restate() {
    if (pos == Pos.FLAT) return none();
    return new Intent(ID, intentSeq.incrementAndGet(), posSide, stopTicks, targetTicks, "holding", true);
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
        int f = fill.fillPriceTicks();
        stopTicks = roundLevel(f - posSide * distTicks, posSide);
        targetTicks = roundLevel(f + posSide * tpR * distTicks, posSide);
        pos = Pos.OPEN;
      }
      case STOP, TARGET -> pos = Pos.FLAT;
      case OTHER -> { }
    }
  }

  @Override
  public void onIntentRejected(Intent intent, String reason) {
    if (intent.targetPosition() != 0 && intent.reason().startsWith(ID + " entry") && pos == Pos.PENDING) {
      pos = Pos.FLAT;
      tradedToday = false;
    }
  }

  @Override
  public void onFlattened(String reason) {
    pos = Pos.FLAT;
  }
}
