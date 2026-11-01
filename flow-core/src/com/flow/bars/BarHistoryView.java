package com.flow.bars;

import com.flow.core.Feature;

/**
 * Read-only history of closed fixed-length bars (1 minute in practice), prices in integer ticks (D-21). Strategies read
 * it from MarketState.bars() and resample/indicate internally, so live behaviour equals the Python backtests, which are
 * all built from 1-minute bars. Index 0 is the OLDEST retained bar. Not thread-safe: read only on the pipeline thread.
 */
public interface BarHistoryView extends Feature {
  String FEATURE_ID = "bar_history";

  int size();

  /** Bar start time (epoch ms), aligned to barMs. */
  long startMs(int i);
  int open(int i);
  int high(int i);
  int low(int i);
  int close(int i);
  long volume(int i);

  /** Total bars ever appended (monotonic, survives ring wrap) -- compare to a remembered value to detect new bars. */
  long appended();

  long barMs();
}
