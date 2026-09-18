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

  /**
   * Typed feature accessors (D-62) -- the mechanism MutableMarketState's
   * very first version already anticipated ("feature-specific accessors
   * ... get added here ... as each real feature is built") but nothing
   * needed until the first real strategy did. Each returns null if that
   * feature isn't registered for this session (e.g. ReplayHarness's
   * Map.of() for anything SDK-engine-backed, D-43) or hasn't reported
   * ready yet -- callers check isReady() themselves, same as any other
   * Feature consumer. Deliberately typed one-by-one rather than a
   * generic `<T> T feature(String id, Class<T> type)` -- explicit
   * accessors are greppable and can't typo a feature id silently into a
   * null.
   */
  com.flow.flow.MarketStructureView marketStructure();
  com.flow.flow.VolumeProfileView volumeProfile();
  com.flow.flow.FootprintView footprint();
  com.flow.flow.BigTradeView bigTrades();
  com.flow.flow.LiquidityMapView liquidityMap();
  com.flow.flow.VWAPView vwap();
  com.flow.flow.OrderRepeatView orderRepeats();

  /** Genuinely immutable snapshot copy. Used only by the journal and tests. */
  MarketState freeze();
}
