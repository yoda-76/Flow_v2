package com.flow.core;

/** One bar-lifecycle callback (onBarOpen/onBarUpdate/onBarClose), OHLC in integer ticks (D-21). */
public record BarEvent(
    long seq,
    long eventTimeMs,
    long receiptTimeMs,
    BarPhase phase,
    int openTicks,
    int highTicks,
    int lowTicks,
    int closeTicks,
    long volume
) implements Event {}
