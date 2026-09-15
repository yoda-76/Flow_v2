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

  /** Genuinely immutable snapshot copy. Used only by the journal and tests. */
  MarketState freeze();
}
