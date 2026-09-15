package com.flow.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One totally-ordered event stream, single-writer drain (README "The
 * event stream"). Three producer threads (SDK tick/bar callbacks, the
 * DOMListener thread, and this class's own clock thread) all call
 * publish(); exactly one dedicated thread ever drains and hands events to
 * the pipeline, which is what makes the feature layer race-free without
 * locks downstream of here.
 *
 * publish() is synchronized so "assign the next seq number" and "enqueue"
 * happen as one atomic step relative to other producers -- otherwise two
 * producer threads could race between incrementing a shared counter and
 * actually enqueuing, and seq order would stop matching enqueue order.
 * At this feed's measured rates (~1.5 ticks/sec average on @GC, E-3) that
 * small serialized critical section is not a throughput concern; it is a
 * correctness one.
 *
 * The queue blocks rather than drops on backpressure -- dropping a market
 * event here would silently corrupt every feature's incremental state,
 * which is exactly what the (not-yet-built) lag guard exists to detect
 * and disarm on, not something this class papers over by discarding data.
 * Contrast with JournalWriter's raw tier, which drops-and-gap-marks by
 * design (D-15) because it is not the event source of truth, the
 * sequencer's queue is.
 */
public final class Sequencer {
  public interface ExceptionHandler {
    void onPipelineException(Throwable t, Event event);
  }

  private final BlockingQueue<Event> queue = new LinkedBlockingQueue<>(100_000);
  private final AtomicLong seqCounter = new AtomicLong(0);
  private final java.util.function.Consumer<Event> handler;
  private final ExceptionHandler exceptionHandler;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile Thread drainThread;

  public Sequencer(java.util.function.Consumer<Event> handler, ExceptionHandler exceptionHandler) {
    this.handler = handler;
    this.exceptionHandler = exceptionHandler;
  }

  /** Assigns seq + receipt time (wall clock -- ingest is the allowed layer) and enqueues. */
  public synchronized <T extends Event> T publish(EventFactory<T> factory, long eventTimeMs) {
    long seq = seqCounter.incrementAndGet();
    long receiptTimeMs = System.currentTimeMillis();
    T event = factory.create(seq, eventTimeMs, receiptTimeMs);
    try {
      queue.put(event); // blocks under sustained backpressure; never silently drops
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted publishing seq=" + seq, ie);
    }
    return event;
  }

  public long lastSeq() {
    return seqCounter.get();
  }

  public int queueDepth() {
    return queue.size();
  }

  public void start() {
    if (!running.compareAndSet(false, true)) return;
    drainThread = new Thread(this::drainLoop, "flow-sequencer-drain");
    drainThread.setDaemon(true);
    drainThread.start();
  }

  public void stop() {
    running.set(false);
    Thread t = drainThread;
    if (t != null) t.interrupt();
  }

  private void drainLoop() {
    while (running.get()) {
      Event e;
      try {
        e = queue.take();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
      try {
        handler.accept(e);
      } catch (Throwable t) {
        // Exception boundary (README "Errors"): never let this thread die,
        // never propagate into a MotiveWave callback. The caller's
        // exceptionHandler decides what "disarm and journal" means.
        try {
          exceptionHandler.onPipelineException(t, e);
        } catch (Throwable inner) {
          System.err.println("FLOW: exception handler itself threw, dropping: " + inner);
        }
      }
    }
  }
}
