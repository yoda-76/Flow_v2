package com.flow.core;

/**
 * Read-only view handed to a strategy at each decision point (README
 * "MarketState"). Not a per-decision copy -- one reusable object read
 * through to feature state, generation-guarded instead: any accessor
 * called against a stale generation must throw, which is what lets a
 * strategy stashing this reference and reading it later fail loudly
 * instead of silently reading stale data. freeze() is the one place an
 * actual immutable copy gets made, for the journal and for tests only.
 */
public interface MarketState {
  long generation();

  /** Exchange/platform event time from the last market event (not a clock event). */
  long exchangeTimeMs();

  /** Local time from the last clock event (README "Time is an event"). */
  long localTimeMs();

  /**
   * The most recent price seen from a TickEvent or BarEvent (via
   * Event.priceOf()), integer ticks (D-21). Null until the first such
   * event. Added for the risk chain's daily-loss PnL marking (D-61) --
   * a general-purpose "current price" accessor was missing before that,
   * not something any prior feature needed.
   */
  Integer lastPriceTicks();

  /** Genuinely immutable snapshot copy. Used only by the journal and tests. */
  MarketState freeze();
}
