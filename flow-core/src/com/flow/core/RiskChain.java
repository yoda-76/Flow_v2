package com.flow.core;

import com.flow.journal.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Ordered filters between intent and gateway (D-61), each verdict
 * journaled so a suppressed trade is visible rather than invisible
 * (README "The risk chain"): armed, session open, readiness, daily-loss
 * limit, size cap, rate limit, churn guard, lag guard -- exactly
 * README's own listed order, evaluated in that order, **short-circuiting
 * on the first block** (later filters are moot once one blocks, so
 * there's nothing meaningful to journal for them).
 *
 * evaluate() is read-only -- it does not mutate churn/rate/PnL state.
 * Pipeline calls recordAccepted() separately, and only when evaluate()
 * returned allowed, so a blocked intent never pollutes churn/rate/PnL
 * tracking as if it had actually happened.
 *
 * "Session open" gates on TradingWindow since D-92: entries are blocked
 * in the last minutes before the daily halt and all weekend, flat intents
 * are always allowed. (It was a hard-coded ALLOW until then -- D-29 had
 * no defined closed window to check against.) What SessionBoundary DOES do here:
 * reversalsThisSession (D-19) and realizedPnlTicks (D-30's "daily"-loss
 * PnL) are zeroed at the 17:00 CT rollover, same reset SessionBoundary's
 * own javadoc explains. lastPosition/entryPriceTicks are deliberately
 * NOT touched by this -- they track the actual live position, which must
 * survive a calendar boundary untouched (flatten-at-session-end, D-24's
 * sibling policy for the *start* of a session, isn't itself built yet,
 * so a position can genuinely still be open when the boundary crosses).
 *
 * Daily-loss PnL is tracked in integer ticks, not dollars -- sidesteps
 * needing a per-instrument dollar-per-tick multiplier as a separate
 * config value for this v1 (see ExternalConfig's own javadoc).
 * RiskChain assumes every ALLOWED intent is exactly what gets reconciled
 * (true today: OrderGateway is dry-run only, D-14) -- once real Sim
 * fills exist, this needs to sync against actual fill prices instead of
 * assuming the intent's own target took effect immediately, noted here
 * rather than fixed now.
 */
public final class RiskChain {
  public record Verdict(String filter, boolean allowed, String reason) {}
  public record Result(boolean allowed, List<Verdict> verdicts) {}

  /**
   * Per-call context that varies with each evaluation -- kept explicit
   * at the call site rather than pulled from ambient state, same
   * "explicit, not implicit" stance Pipeline's own constructor already
   * takes for `features`/`priceDecoder`.
   */
  public record Context(
      boolean armed,
      boolean ready,
      Integer currentPriceTicks,
      long nowEventTimeMs,
      int queueDepth,
      long processingTimeMs
  ) {}

  private final ExternalConfig config;
  private final SessionBoundary.Tracker sessionTracker = new SessionBoundary.Tracker();

  // Churn guard + PnL state (drain-thread-only, same as every Feature).
  private int lastPosition = 0;
  private long lastChangeAtMs = -1;
  private int reversalsThisSession = 0;
  private Integer entryPriceTicks = null;
  private int realizedPnlTicks = 0;

  // Rate limiter: timestamps of the last N accepted changes, pruned to a rolling 60s window.
  private static final long RATE_WINDOW_MS = 60_000L;
  private final Deque<Long> recentChangeTimestamps = new ArrayDeque<>();

  public RiskChain(ExternalConfig config) {
    this.config = config;
  }

  /**
   * Shared by evaluate() (intent-change cadence) and dailyLossBreached()
   * (every-event cadence, 2026-09-21's kill-switch requirement) -- both
   * need the same rollover check before reading reversalsThisSession/
   * realizedPnlTicks, so it lives in one place rather than two copies
   * drifting apart.
   */
  private void maybeRolloverSession(long nowEventTimeMs) {
    if (sessionTracker.advance(nowEventTimeMs)) {
      reversalsThisSession = 0;
      realizedPnlTicks = 0;
    }
  }

  public Result evaluate(Intent intent, Context ctx) {
    maybeRolloverSession(ctx.nowEventTimeMs());

    List<Verdict> verdicts = new ArrayList<>();

    Verdict armedV = ctx.armed() ? allow("armed") : block("armed", "not armed");
    verdicts.add(armedV);
    if (!armedV.allowed()) return new Result(false, verdicts);

    Verdict sessionV = checkSessionWindow(intent, ctx);
    verdicts.add(sessionV);
    if (!sessionV.allowed()) return new Result(false, verdicts);

    Verdict readyV = ctx.ready() ? allow("readiness") : block("readiness", "required features not all ready");
    verdicts.add(readyV);
    if (!readyV.allowed()) return new Result(false, verdicts);

    Verdict lossV = checkDailyLoss(ctx);
    verdicts.add(lossV);
    if (!lossV.allowed()) return new Result(false, verdicts);

    Verdict sizeV = checkSizeCap(intent);
    verdicts.add(sizeV);
    if (!sizeV.allowed()) return new Result(false, verdicts);

    Verdict rateV = checkRateLimit(ctx);
    verdicts.add(rateV);
    if (!rateV.allowed()) return new Result(false, verdicts);

    Verdict churnV = checkChurn(intent, ctx);
    verdicts.add(churnV);
    if (!churnV.allowed()) return new Result(false, verdicts);

    Verdict lagV = checkLag(ctx);
    verdicts.add(lagV);
    if (!lagV.allowed()) return new Result(false, verdicts);

    return new Result(true, verdicts);
  }

  /** Only call when evaluate() returned allowed=true for this same intent/ctx. */
  public void recordAccepted(Intent intent, Context ctx) {
    if (intent.targetPosition() != lastPosition) {
      if (lastChangeAtMs >= 0) reversalsThisSession++; // don't count the session's very first entry
      if (lastPosition != 0 && entryPriceTicks != null && ctx.currentPriceTicks() != null) {
        realizedPnlTicks += (ctx.currentPriceTicks() - entryPriceTicks) * Integer.signum(lastPosition);
      }
      entryPriceTicks = intent.targetPosition() != 0 ? ctx.currentPriceTicks() : null;
      lastChangeAtMs = ctx.nowEventTimeMs();
      lastPosition = intent.targetPosition();
    }
    recentChangeTimestamps.addLast(ctx.nowEventTimeMs());
  }

  /**
   * Independent of evaluate()/an intent change -- meant to be called on
   * EVERY event (2026-09-21, user's explicit "no matter what, close
   * everything the instant the daily loss limit is hit" requirement), so
   * a breach is caught immediately rather than only the next time the
   * strategy's own intent happens to change (D-69's originally-flagged
   * gap: a strategy re-asserting the same "holding" intent would never
   * re-trigger evaluate() at all, letting a breach sit unacted-on
   * indefinitely). Read-only, same PnL state checkDailyLoss() already
   * tracks; does the same rollover check evaluate() does so the two
   * cadences never drift out of sync on which trading day they think
   * they're in.
   */
  public boolean dailyLossBreached(Context ctx) {
    maybeRolloverSession(ctx.nowEventTimeMs());
    return !checkDailyLoss(ctx).allowed();
  }

  /**
   * D-92: true while an open position must be closed -- the flatten window
   * before the daily halt and the whole weekend (TradingWindow). Called on
   * every event by Pipeline, like dailyLossBreached().
   */
  public boolean flattenDue(long nowEventTimeMs) {
    return phaseAt(nowEventTimeMs) == TradingWindow.Phase.FLATTEN;
  }

  private TradingWindow.Phase phaseAt(long nowEventTimeMs) {
    return TradingWindow.phaseAt(nowEventTimeMs, config.noEntryLeadMinutes(), config.flattenLeadMinutes());
  }

  /**
   * D-92: the session-end flatten just ran (or the window was entered), so
   * the account is treated as flat from here. Books the open trade's PnL at
   * the current price and forgets the position -- without this, lastPosition/
   * entryPriceTicks would keep marking a position that no longer exists
   * against every later price, and a gap across the weekend could read as a
   * daily-loss breach and trip the kill switch on an empty account.
   * Deliberately not counted as a reversal.
   */
  public void onFlattened(Context ctx) {
    if (lastPosition != 0 && entryPriceTicks != null && ctx.currentPriceTicks() != null) {
      realizedPnlTicks += (ctx.currentPriceTicks() - entryPriceTicks) * Integer.signum(lastPosition);
    }
    lastPosition = 0;
    entryPriceTicks = null;
  }

  /**
   * Blocks anything that would OPEN or CHANGE a position outside the OPEN
   * window; an intent to go flat is always allowed (it reduces risk, and
   * blocking it would leave a strategy believing it is still in).
   */
  private Verdict checkSessionWindow(Intent intent, Context ctx) {
    TradingWindow.Phase phase = phaseAt(ctx.nowEventTimeMs());
    if (phase == TradingWindow.Phase.OPEN || intent.targetPosition() == 0) return allow("session_open");
    return block("session_open", "no new entries: " + phase + " window before the daily halt / weekend");
  }

  private Verdict checkDailyLoss(Context ctx) {
    int unrealized = 0;
    if (entryPriceTicks != null && ctx.currentPriceTicks() != null && lastPosition != 0) {
      unrealized = (ctx.currentPriceTicks() - entryPriceTicks) * Integer.signum(lastPosition);
    }
    int totalPnlTicks = realizedPnlTicks + unrealized;
    int limit = config.dailyLossLimitTicks();
    if (totalPnlTicks <= -limit) {
      return block("daily_loss", "pnl " + totalPnlTicks + " ticks breaches limit -" + limit);
    }
    return allow("daily_loss");
  }

  private Verdict checkSizeCap(Intent intent) {
    int max = config.maxContracts();
    if (Math.abs(intent.targetPosition()) > max) {
      return block("size_cap", "target " + intent.targetPosition() + " exceeds max " + max);
    }
    return allow("size_cap");
  }

  private Verdict checkRateLimit(Context ctx) {
    long windowStart = ctx.nowEventTimeMs() - RATE_WINDOW_MS;
    while (!recentChangeTimestamps.isEmpty() && recentChangeTimestamps.peekFirst() < windowStart) {
      recentChangeTimestamps.pollFirst();
    }
    int limit = config.rateLimitPerMinute();
    if (recentChangeTimestamps.size() >= limit) {
      return block("rate_limit", recentChangeTimestamps.size() + " changes in the last 60s, limit " + limit);
    }
    return allow("rate_limit");
  }

  private Verdict checkChurn(Intent intent, Context ctx) {
    if (intent.targetPosition() == lastPosition) return allow("churn"); // no actual change requested
    if (lastChangeAtMs >= 0) {
      long dwell = ctx.nowEventTimeMs() - lastChangeAtMs;
      long minDwell = config.minDwellMs();
      if (dwell < minDwell) {
        return block("churn", "min dwell " + minDwell + "ms not elapsed (" + dwell + "ms since last change)");
      }
    }
    int maxReversals = config.maxReversalsPerSession();
    if (reversalsThisSession >= maxReversals) {
      return block("churn", "max reversals per session (" + maxReversals + ") reached");
    }
    return allow("churn");
  }

  private Verdict checkLag(Context ctx) {
    long queueThreshold = config.lagQueueDepthThreshold();
    if (ctx.queueDepth() >= queueThreshold) {
      return block("lag", "queue depth " + ctx.queueDepth() + " >= threshold " + queueThreshold);
    }
    long procThreshold = config.lagProcessingMsThreshold();
    if (ctx.processingTimeMs() >= procThreshold) {
      return block("lag", "processing time " + ctx.processingTimeMs() + "ms >= threshold " + procThreshold);
    }
    return allow("lag");
  }

  private static Verdict allow(String filter) {
    return new Verdict(filter, true, "ok");
  }

  private static Verdict block(String filter, String reason) {
    return new Verdict(filter, false, reason);
  }

  /** Journal line for a Result, one line per evaluate() call regardless of outcome (D-13: verdicts are always visible). */
  public static String resultLine(long seq, Intent intent, Result result) {
    var arr = new StringBuilder("[");
    for (int i = 0; i < result.verdicts().size(); i++) {
      if (i > 0) arr.append(',');
      Verdict v = result.verdicts().get(i);
      arr.append("{\"filter\":\"").append(v.filter()).append("\",\"allowed\":").append(v.allowed())
          .append(",\"reason\":\"").append(v.reason().replace("\"", "'")).append("\"}");
    }
    arr.append(']');
    return Json.object()
        .field("type", "risk_verdict")
        .field("seq", seq)
        .field("strategyId", intent.strategyId())
        .field("intentSeq", intent.seq())
        .field("allowed", result.allowed())
        .fieldRaw("verdicts", arr.toString())
        .build();
  }
}
