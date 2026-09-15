package com.flow.core;

/**
 * Base type for everything on the sequencer's single totally-ordered
 * stream. Every event carries a monotonic sequence number (assigned at
 * publish time, matching enqueue order exactly -- see Sequencer), the
 * platform/exchange event time, and the local receipt time. Both times are
 * read at ingest, the last layer allowed to touch the wall clock (README
 * "Time is an event") -- nothing that consumes an Event may call
 * System.currentTimeMillis() itself.
 */
public sealed interface Event
    permits TickEvent, BarEvent, DomEvent, ClockEvent, OrderEvent, FillEvent {
  long seq();
  long eventTimeMs();
  long receiptTimeMs();
}
