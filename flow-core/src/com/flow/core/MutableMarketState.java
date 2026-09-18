package com.flow.core;

import java.util.Map;

/**
 * The one reusable MarketState instance, owned and advanced by the
 * sequencer's drain loop (the only thread that ever mutates feature
 * state, README "The event stream"). bump() is called once per event
 * drained, immediately before the strategy/feature layer sees it.
 *
 * Holds the same `features` map Pipeline does, purely to serve
 * MarketState's typed accessors (D-62) -- it never mutates any feature
 * itself, that's still Pipeline's own job in handle().
 */
public final class MutableMarketState implements MarketState {
  private final Map<String, Feature> features;

  private long generation = 0;
  private long exchangeTimeMs = 0;
  private long localTimeMs = 0;
  private Integer lastPriceTicks = null;

  public MutableMarketState(Map<String, Feature> features) {
    this.features = features;
  }

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

  private <T> T feature(String id, Class<T> type) {
    Feature f = features.get(id);
    return type.isInstance(f) ? type.cast(f) : null;
  }

  @Override public com.flow.flow.MarketStructureView marketStructure() {
    return feature(com.flow.flow.MarketStructureView.FEATURE_ID, com.flow.flow.MarketStructureView.class);
  }

  @Override public com.flow.flow.VolumeProfileView volumeProfile() {
    return feature(com.flow.flow.VolumeProfileView.FEATURE_ID, com.flow.flow.VolumeProfileView.class);
  }

  @Override public com.flow.flow.FootprintView footprint() {
    return feature(com.flow.flow.FootprintView.FEATURE_ID, com.flow.flow.FootprintView.class);
  }

  @Override public com.flow.flow.BigTradeView bigTrades() {
    return feature(com.flow.flow.BigTradeView.FEATURE_ID, com.flow.flow.BigTradeView.class);
  }

  @Override public com.flow.flow.LiquidityMapView liquidityMap() {
    return feature(com.flow.flow.LiquidityMapView.FEATURE_ID, com.flow.flow.LiquidityMapView.class);
  }

  @Override public com.flow.flow.VWAPView vwap() {
    return feature(com.flow.flow.VWAPView.FEATURE_ID, com.flow.flow.VWAPView.class);
  }

  @Override public com.flow.flow.OrderRepeatView orderRepeats() {
    return feature(com.flow.flow.OrderRepeatView.FEATURE_ID, com.flow.flow.OrderRepeatView.class);
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

  /** No live feature access -- freeze() is for the journal/tests only, which don't need it. */
  private record Frozen(long generation, long exchangeTimeMs, long localTimeMs, Integer lastPriceTicks) implements MarketState {
    @Override public com.flow.flow.MarketStructureView marketStructure() { return null; }
    @Override public com.flow.flow.VolumeProfileView volumeProfile() { return null; }
    @Override public com.flow.flow.FootprintView footprint() { return null; }
    @Override public com.flow.flow.BigTradeView bigTrades() { return null; }
    @Override public com.flow.flow.LiquidityMapView liquidityMap() { return null; }
    @Override public com.flow.flow.VWAPView vwap() { return null; }
    @Override public com.flow.flow.OrderRepeatView orderRepeats() { return null; }

    @Override
    public MarketState freeze() {
      return this;
    }
  }
}
