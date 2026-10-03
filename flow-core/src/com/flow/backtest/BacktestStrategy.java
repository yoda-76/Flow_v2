package com.flow.backtest;

/**
 * The pluggable seam `backtestEnginePlan.md` (2026-10-03) calls for: one new class per strategy, no changes to
 * {@link BacktestEngine} -- same "a new strategy touches one box" rule the live {@code FlowStrategy} interface
 * follows (README "Adding a strategy"), re-cut for bar-close-only, order-flow-free backtesting (the scope the
 * user resolved 2026-10-03: no tick/DOM data is available historically, so this can only ever test a strategy's
 * pure price-action setup/signal logic, never its order-flow-confirmed execution).
 *
 * Call order per bar, enforced by {@link BacktestEngine#run}, never by the implementation: {@link #onBar} always
 * runs first (so a strategy's own internal state -- indicators, structure, whatever it wants -- is current for
 * this bar); {@link #entrySignal} runs after, and ONLY when the engine is flat, not mid-bar-managing an exit, and
 * the risk chain hasn't already blocked new entries for other reasons.
 */
public interface BacktestStrategy {
  String id();

  /** Update internal state for the bar just closed. Called for every bar, flat or in a position. */
  void onBar(Bar bar, long seq);

  /** Whether this strategy has enough history/state to be trusted yet (its own warmup, if any). */
  boolean isReady();

  /**
   * Called only while flat and ready. Null means no entry this bar. A non-null {@link Signal} is the strategy's
   * own fully-sized stop/target -- the engine applies it as-is, no adjustment.
   */
  Signal entrySignal(Bar bar);
}
