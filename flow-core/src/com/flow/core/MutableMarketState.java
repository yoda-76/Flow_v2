package com.flow.core;

/**
 * The one reusable MarketState instance, owned and advanced by the
 * sequencer's drain loop (the only thread that ever mutates feature
 * state, README "The event stream"). bump() is called once per event
 * drained, immediately before the strategy/feature layer sees it. No
 * accessor exists yet beyond generation/time -- feature-specific
 * accessors (e.g. volume profile POC) get added here, guarded the same
 * way, as each real feature is built.
 */
public final class MutableMarketState implements MarketState {
  private long generation = 0;
  private long exchangeTimeMs = 0;
  private long localTimeMs = 0;
  private Integer lastPriceTicks = null;

  /** Advances the generation and updates whichever clock the driving event carries. */
  public void bump(Event e) {
    generation++;
    if (e instanceof ClockEvent) {
      localTimeMs = e.eventTimeMs();
    } else {
      exchangeTimeMs = e.eventTimeMs();
    }
    Integer p = Event.priceOf(e);
    if (p != null) lastPriceTicks = p;
  }

  @Override
  public long generation() {
    return generation;
  }

  @Override
  public long exchangeTimeMs() {
    return exchangeTimeMs;
  }

  @Override
  public long localTimeMs() {
    return localTimeMs;
  }

  @Override
  public Integer lastPriceTicks() {
    return lastPriceTicks;
  }

  @Override
  public MarketState freeze() {
    return new Frozen(generation, exchangeTimeMs, localTimeMs, lastPriceTicks);
  }

  /** Checks the caller's captured generation against the current one; throws if stale. */
  public void checkFresh(long capturedGeneration) {
    if (capturedGeneration != generation) {
      throw new IllegalStateException(
          "MarketState read at stale generation " + capturedGeneration
              + " (current " + generation + ") -- do not stash this reference across events");
    }
  }

  private record Frozen(long generation, long exchangeTimeMs, long localTimeMs, Integer lastPriceTicks) implements MarketState {
    @Override
    public MarketState freeze() {
      return this;
    }
  }
}
