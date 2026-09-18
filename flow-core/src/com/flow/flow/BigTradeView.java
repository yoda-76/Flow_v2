package com.flow.flow;

import com.flow.core.Feature;

import java.util.List;

/**
 * Big trades (D-36/E-5): wraps the SDK's AggregateFilter, aggByOrder=true,
 * fed our own ticks like every other SDK-engine construct here. Ready
 * immediately (D-22 class 1) -- the size threshold is fixed, not relative,
 * so there is nothing to warm up before a reading means something.
 *
 * recent() is a bounded, chronological (oldest-first) window, not the
 * full session history -- unbounded retention of every big trade is a
 * retention-policy question (see todo.md 4b), not this feature's job.
 */
public interface BigTradeView extends Feature {
  String FEATURE_ID = "big_trades";

  /** Most recent big trades, oldest first, capped at a fixed window size. */
  List<BigTradeEvent> recent();

  /** Distinct big trades seen this session, after T-3 dedupe -- not raw emission count. */
  long sessionCount();
}
