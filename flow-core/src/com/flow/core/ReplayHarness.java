package com.flow.core;

import com.flow.journal.JournalWriter;
import com.flow.strategies.StrategyRegistrations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Feeds a recorded raw.jsonl back through the identical Pipeline/strategy
 * code (README "Replay"). Deliberately does not go through Sequencer at
 * all -- replay is inherently sequential (read a file top to bottom in
 * original seq order) and calling Pipeline.handle() directly in a plain
 * loop is both simpler and more deterministic than routing through the
 * queue/thread machinery that exists only to order concurrent live
 * producers, which replay doesn't have.
 *
 * Writes its own decisions.jsonl/raw.jsonl under <sessionDir>/replay/ via
 * the same JournalWriter live uses, so a mismatch can be diffed file-to-
 * file, not just intent-list-to-intent-list.
 *
 * features is required explicitly, same reasoning as Pipeline's own
 * constructor -- and per D-43, any feature wrapping an SDK engine class
 * (e.g. SdkVolumeProfileFeature) cannot be constructed here at all, since
 * this runs under a plain JDK with no live MotiveWave Instrument
 * available. Callers pass Map.of() for those today; replay-inside-
 * MotiveWave (not yet built) is the acknowledged path to closing that
 * gap, not a workaround attempted here.
 */
public final class ReplayHarness {
  public record Result(List<Intent> intents, int eventsReplayed, int gapsSkipped) {}

  /**
   * The decoder the live run used to turn tick offsets into decimals, rebuilt
   * from the session's own decisions.jsonl (`price_anchor` + session_header's
   * tickSize). Null if the session predates `price_anchor` (recordings made
   * before 2026-09-24) -- the caller must then derive or go without one.
   */
  public static java.util.function.IntToDoubleFunction priceDecoderFor(Path sessionDir) throws IOException {
    Path decisions = sessionDir.resolve("decisions.jsonl");
    if (!Files.exists(decisions)) return null;
    Double anchor = null;
    Double tickSize = null;
    for (String line : Files.readAllLines(decisions)) {
      if (line.isBlank()) continue;
      com.flow.journal.JsonObject o = com.flow.journal.JsonObject.parse(line);
      String type = o.getString("type");
      if ("price_anchor".equals(type)) {
        anchor = o.getDouble("price");
        tickSize = o.getDouble("tickSize");
        break;
      }
    }
    if (anchor == null) return null;
    final double a = anchor;
    final double ts = tickSize;
    return ticks -> a + ticks * ts;
  }

  public static Result replay(Path sessionDir, String strategyId, Map<String, Feature> features) throws IOException {
    return replay(sessionDir, strategyId, features, p -> {});
  }

  /**
   * beforeEvents runs once against the freshly built Pipeline, before the
   * first replayed event -- how a caller attaches something like a
   * DataRecorder (D-88) to the same replay without this class having to
   * know about it.
   */
  public static Result replay(Path sessionDir, String strategyId, Map<String, Feature> features,
                              java.util.function.Consumer<Pipeline> beforeEvents) throws IOException {
    StrategyRegistry registry = StrategyRegistrations.buildDefault();
    FlowStrategy strategy = registry.create(strategyId);
    strategy.onInit(new StrategyConfig(Map.of()));

    Path replayDir = sessionDir.resolve("replay");
    JournalWriter journal = new JournalWriter(replayDir);
    journal.start();

    List<Intent> collected = new ArrayList<>();
    IntentSink sink = (intent, event) -> collected.add(intent);
    // null priceDecoder -- replay has no live price context (D-43); trace
    // lines fall back to tick-offset-only when this runs.
    Pipeline pipeline = new Pipeline(strategy, journal, sink, features, priceDecoderFor(sessionDir));
    beforeEvents.accept(pipeline);

    int eventsReplayed = 0;
    int gapsSkipped = 0;
    Path rawFile = sessionDir.resolve("raw.jsonl");
    for (String line : Files.readAllLines(rawFile)) {
      if (line.isBlank()) continue;
      Event e;
      try {
        e = RawEventCodec.decode(line);
      } catch (RuntimeException ex) {
        journal.flushAndClose();
        throw new IllegalStateException("failed to decode raw line: " + line, ex);
      }
      if (e == null) {
        gapsSkipped++; // GAP_MARKER -- an honest admission of loss, not an event to replay
        continue;
      }
      pipeline.handle(e);
      eventsReplayed++;
    }

    journal.flushAndClose();
    return new Result(collected, eventsReplayed, gapsSkipped);
  }
}
