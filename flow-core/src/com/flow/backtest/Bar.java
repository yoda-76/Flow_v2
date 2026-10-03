package com.flow.backtest;

/**
 * One OHLCV bar for the generic backtest engine. Deliberately a separate type from
 * {@code MarketStructureBacktest.Bar} (same fields) rather than reusing it -- that class stays untouched, its own
 * 16 synthetic checks and D-79/D-80's historical run numbers keep meaning exactly what they always did. This is
 * the type every new, generic backtest class ({@link BacktestStrategy}, {@link BacktestEngine}, {@link BacktestCsv})
 * is built around.
 */
public record Bar(long timestampMs, double open, double high, double low, double close, long volume) {}
