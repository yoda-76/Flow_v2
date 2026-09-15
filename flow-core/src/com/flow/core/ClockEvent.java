package com.flow.core;

/**
 * Synthetic event injected by the runtime at a fixed cadence (100ms,
 * README "Time is an event") so a quiet book doesn't freeze time-based
 * triggers. Recorded in the raw journal like anything else -- replay
 * feeds it back in order rather than regenerating it from the replay
 * machine's own clock.
 */
public record ClockEvent(
    long seq,
    long eventTimeMs,
    long receiptTimeMs
) implements Event {}
