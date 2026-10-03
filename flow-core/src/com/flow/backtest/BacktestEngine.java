package com.flow.backtest;

import com.flow.core.SessionBoundary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Generalizes {@code MarketStructureBacktest.run()}'s bar-by-bar mechanics (manage an open position against this
 * bar's range first, no same-bar re-entry, conservative stop-wins-on-a-tie convention) behind a pluggable
 * {@link BacktestStrategy}, and adds the one thing the user explicitly asked for that the old tool never had: a
 * ported risk chain (daily loss limit, size cap, max reversals, rate limit, min dwell -- `backtestEnginePlan.md`,
 * 2026-10-03). Order-flow execution modeling is permanently out of scope (no tick/DOM historical data exists) --
 * this engine answers "does this strategy's signal logic show any edge on bar data," nothing more.
 *
 * <h2>Risk chain semantics, ported from {@code RiskChain} to mean the same thing</h2>
 * Field names/defaults match {@code ExternalConfig} so the same mental model applies. What's deliberately
 * DIFFERENT from a straight port, each a stated simplification, not an oversight:
 * <ul>
 *   <li><b>Trading-day boundary</b> reuses {@link SessionBoundary.Tracker} directly (17:00 America/Chicago,
 *       DST-aware) -- the exact same class the live risk chain uses, not a reimplementation.</li>
 *   <li><b>Daily-loss kill switch</b> is checked against every bar an open position marks to (mark at the bar's
 *       CLOSE, not intrabar) -- live checks on every event; a 1-minute bar is this engine's finest event. A
 *       breach force-closes at that bar's close price (reason {@code "daily_loss"}) and blocks new entries for
 *       the REST OF THAT TRADING DAY only (next day's rollover re-opens it) -- live's actual disarm is a human
 *       operational state with no bar-data equivalent to simulate faithfully, so "blocked until next session" is
 *       this engine's stand-in.</li>
 *   <li>Per {@code RiskChain.onFlattened}'s own convention, a forced kill-switch close is <b>not</b> counted as a
 *       reversal and does not consume a rate-limit slot; an ordinary stop/target exit (or entry) does, matching
 *       {@code recordAccepted}'s "any real position change" rule, except the very first change in a session,
 *       exactly as {@code RiskChain} exempts it.</li>
 *   <li><b>Min dwell</b> is implemented faithfully but, at the config default (5000ms) and this engine's finest
 *       granularity (1-minute bars, i.e. a minimum 60s between any two decisions already), it can never actually
 *       bind -- kept for correctness and in case a finer bar size is used later, not because it does anything at
 *       1-minute resolution today.</li>
 *   <li><b>Session-open window / feed / lag guards are excluded entirely</b> -- not resolved into this engine
 *       (feed/lag have no backtest meaning; session-open blocking near the daily halt was not asked for and
 *       would need its own design pass if ever wanted).</li>
 * </ul>
 */
public final class BacktestEngine {
  public record Trade(
      long entryTimeMs, double entryPrice, int direction,
      double stopPrice, double targetPrice,
      long exitTimeMs, double exitPrice, String exitReason, double rMultiple) {}

  /** Counts of denied entries by reason -- the backtest's stand-in for "every verdict journaled" at aggregate scale. */
  public record DenialCounts(int dailyLoss, int maxReversals, int rateLimit, int minDwell) {}

  public record RiskConfig(
      int fixedContracts, int maxContracts, int dailyLossLimitTicks,
      int rateLimitPerMinute, long minDwellMs, int maxReversalsPerSession) {

    /** Matches `ExternalConfig`'s own defaults exactly (config/risk.json), for the same mental model. */
    public static RiskConfig defaults() {
      return new RiskConfig(1, 1, 200, 6, 5_000L, 20);
    }

    /** Every limit effectively disabled -- used by the regression check against the old, risk-chain-free tool. */
    public static RiskConfig unlimited() {
      return new RiskConfig(1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0L, Integer.MAX_VALUE);
    }
  }

  public record Report(
      List<Trade> trades, int wins, int losses, double totalR, double avgR, double maxDrawdownR,
      DenialCounts denials, int killSwitchTrips) {}

  private BacktestEngine() {}

  private static int toTicks(double price, double tickSize) { return (int) Math.round(price / tickSize); }
  private static double fromTicks(int ticks, double tickSize) { return ticks * tickSize; }

  private static final long RATE_WINDOW_MS = 60_000L;

  public static Report run(List<Bar> bars, double tickSize, BacktestStrategy strategy, RiskConfig risk) {
    List<Trade> trades = new ArrayList<>();
    int deniedDailyLoss = 0, deniedReversals = 0, deniedRateLimit = 0, deniedDwell = 0, killSwitchTrips = 0;

    SessionBoundary.Tracker tracker = new SessionBoundary.Tracker();
    long realizedTicksToday = 0;
    int reversalsThisSession = 0;

    int lastPosition = 0;
    long lastChangeAtMs = -1; // sentinel: -1 = no change yet this whole run (mirrors RiskChain's init)
    Deque<Long> recentChangeTimestamps = new ArrayDeque<>();

    Integer posDirection = null;
    double entryPrice = 0, stopPrice = 0, targetPrice = 0;
    long entryTimeMs = 0;
    int entryPriceTicks = 0;

    long seq = 0;
    for (Bar bar : bars) {
      seq++;
      boolean closedThisBar = false;

      if (tracker.advance(bar.timestampMs())) {
        realizedTicksToday = 0;
        reversalsThisSession = 0;
      }

      // 1. Manage an open position against THIS bar, kill switch first (it would otherwise pre-empt a stop/target
      //    that lands on the exact same bar -- the kill switch is the stricter, "no matter what" guard per
      //    RiskChain's own 2026-09-21 requirement).
      if (posDirection != null) {
        int closeT = toTicks(bar.close(), tickSize);
        long unrealizedTicks = (long) (closeT - entryPriceTicks) * posDirection;
        if (realizedTicksToday + unrealizedTicks <= -risk.dailyLossLimitTicks()) {
          double exitPrice = bar.close();
          double risk_ = Math.abs(entryPrice - stopPrice);
          double r = risk_ == 0 ? 0 : ((exitPrice - entryPrice) * posDirection) / risk_;
          trades.add(new Trade(entryTimeMs, entryPrice, posDirection, stopPrice, targetPrice,
              bar.timestampMs(), exitPrice, "daily_loss", r));
          realizedTicksToday += unrealizedTicks; // not a reversal, no rate-limit slot -- RiskChain.onFlattened's rule;
          // also now permanently <= -dailyLossLimitTicks for the rest of this session (nothing resets it before the
          // next rollover), which is exactly what blocks every later entry attempt below -- no separate flag needed.
          lastPosition = 0;
          posDirection = null;
          entryPriceTicks = 0;
          killSwitchTrips++;
          closedThisBar = true;
        } else {
          boolean stopHit = posDirection > 0 ? bar.low() <= stopPrice : bar.high() >= stopPrice;
          boolean targetHit = posDirection > 0 ? bar.high() >= targetPrice : bar.low() <= targetPrice;
          if (stopHit || targetHit) {
            boolean isStop = stopHit; // conservative: stop wins if both hit the same bar
            double exitPrice = isStop ? stopPrice : targetPrice;
            double risk_ = Math.abs(entryPrice - stopPrice);
            double r = risk_ == 0 ? 0 : ((exitPrice - entryPrice) * posDirection) / risk_;
            trades.add(new Trade(entryTimeMs, entryPrice, posDirection, stopPrice, targetPrice,
                bar.timestampMs(), exitPrice, isStop ? "stop" : "target", r));
            int exitTicks = toTicks(exitPrice, tickSize);
            realizedTicksToday += (long) (exitTicks - entryPriceTicks) * posDirection;
            if (lastChangeAtMs >= 0) reversalsThisSession++; // RiskChain.recordAccepted: not the session's first change
            lastChangeAtMs = bar.timestampMs();
            recentChangeTimestamps.addLast(bar.timestampMs());
            lastPosition = 0;
            posDirection = null;
            entryPriceTicks = 0;
            closedThisBar = true;
          }
        }
      }

      // 2. Feed the bar into the strategy's own state, always -- same order the old tool used (position
      //    management happens against the bar that's about to be fed, not the bar after).
      strategy.onBar(bar, seq);

      // 3. Entry check: flat, didn't just close this same bar, strategy ready. (A kill-switch fire earlier this
      //    session is caught by the realizedTicksToday check just below, not a separate flag -- see the comment
      //    where realizedTicksToday is updated on a forced close, above.)
      if (posDirection == null && !closedThisBar && strategy.isReady()) {
        if (realizedTicksToday <= -risk.dailyLossLimitTicks()) {
          deniedDailyLoss++;
        } else {
          Signal sig = strategy.entrySignal(bar);
          if (sig != null) {
            if (reversalsThisSession >= risk.maxReversalsPerSession()) {
              deniedReversals++;
            } else if (rateLimited(recentChangeTimestamps, bar.timestampMs(), risk.rateLimitPerMinute())) {
              deniedRateLimit++;
            } else if (lastChangeAtMs >= 0 && (bar.timestampMs() - lastChangeAtMs) < risk.minDwellMs()) {
              deniedDwell++;
            } else {
              posDirection = sig.direction();
              entryPrice = bar.close();
              stopPrice = sig.stopPrice();
              targetPrice = sig.targetPrice();
              entryTimeMs = bar.timestampMs();
              entryPriceTicks = toTicks(entryPrice, tickSize);
              if (lastChangeAtMs >= 0) reversalsThisSession++;
              lastChangeAtMs = bar.timestampMs();
              recentChangeTimestamps.addLast(bar.timestampMs());
              lastPosition = sig.direction();
            }
          }
        }
      }
    }

    int wins = 0, losses = 0;
    double totalR = 0, running = 0, peak = 0, maxDrawdown = 0;
    for (Trade t : trades) {
      if (t.rMultiple() > 0) wins++; else if (t.rMultiple() < 0) losses++;
      totalR += t.rMultiple();
      running += t.rMultiple();
      peak = Math.max(peak, running);
      maxDrawdown = Math.max(maxDrawdown, peak - running);
    }
    double avgR = trades.isEmpty() ? 0 : totalR / trades.size();

    return new Report(trades, wins, losses, totalR, avgR, maxDrawdown,
        new DenialCounts(deniedDailyLoss, deniedReversals, deniedRateLimit, deniedDwell), killSwitchTrips);
  }

  /** Prunes the rolling 60s window, then reports whether it's already at the limit -- same order RiskChain uses. */
  private static boolean rateLimited(Deque<Long> recentChangeTimestamps, long nowMs, int limit) {
    long windowStart = nowMs - RATE_WINDOW_MS;
    while (!recentChangeTimestamps.isEmpty() && recentChangeTimestamps.peekFirst() < windowStart) {
      recentChangeTimestamps.pollFirst();
    }
    return recentChangeTimestamps.size() >= limit;
  }
}
