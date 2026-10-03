package com.flow.backtest;

/**
 * A {@link BacktestStrategy}'s entry decision for the bar just fed to it: trade {@code direction} (+1 long,
 * -1 short), already-sized {@code stopPrice}/{@code targetPrice} in decimal price units (same units as
 * {@link Bar}'s own fields). The strategy owns how the stop/target distance is computed -- unlike the live
 * system's {@code Intent} (a desired end-state consumed by a shared execution layer), there is deliberately no
 * cross-strategy stop-sizing abstraction here (README's own rule: don't extract a shared mechanism until a
 * second real user needs the same thing). {@link BacktestEngine#run} never computes a stop/target itself.
 */
public record Signal(int direction, double stopPrice, double targetPrice) {}
