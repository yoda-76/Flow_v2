package com.flow.core;

/** Builds one Event once the sequencer has assigned its seq/receipt time. */
@FunctionalInterface
public interface EventFactory<T extends Event> {
  T create(long seq, long eventTimeMs, long receiptTimeMs);
}
