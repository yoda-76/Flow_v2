package com.flow.orderflow;

import com.flow.core.Event;
import com.flow.core.Feature;
import com.flow.core.Intent;
import com.flow.core.MutableMarketState;
import com.flow.core.RawEventCodec;
import com.flow.core.StrategyConfig;
import com.flow.core.TickEvent;
import com.flow.flow.MarketStructureFeature;
import com.flow.flow.MarketStructureView;
import com.flow.strategies.OrderFlowExecutionStrategy;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Research replay (gapAnswers §11, G35): drives one or more recorded raw journals ({@code logs/<session>/raw.jsonl})
 * through the market-structure feature, the order-flow engine and {@link OrderFlowExecutionStrategy}, exactly as the
 * live pipeline would (every feature sees every event; the strategy wakes on every trade), and writes the strategy's
 * evaluation log. Intents are not sent anywhere: a position is assumed filled at the trade that produced the entry
 * intent and closed at the trade that produced the flat intent, which gives a first, cost-free view of the rules.
 * No liquidity-map history exists in raw journals, so heatmap reads are absent (NO liquidity) in replay.
 *
 * Usage: OrderFlowReplay <evalOut.jsonl> <raw.jsonl> [raw.jsonl ...]   (optional -Dorderflow.config=path)
 */
public final class OrderFlowReplay {
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("usage: OrderFlowReplay <evalOut.jsonl> <raw.jsonl> [raw.jsonl ...]");
      System.exit(2);
    }
    String cfgPath = System.getProperty("orderflow.config");
    OfConfig cfg = cfgPath == null ? OfConfig.defaults() : OfConfig.load(Path.of(cfgPath));
    OrderFlowEngine engine = new OrderFlowEngine(cfg);
    MarketStructureFeature ms = new MarketStructureFeature(MarketStructureView.FEATURE_ID, null);
    Map<String, Feature> features = new LinkedHashMap<>();
    features.put(MarketStructureView.FEATURE_ID, ms);
    features.put(OrderFlowEngine.FEATURE_ID, engine);
    MutableMarketState state = new MutableMarketState(features);
    OrderFlowExecutionStrategy strategy = new OrderFlowExecutionStrategy();
    Map<String, String> sc = new LinkedHashMap<>();
    sc.put("fixedContracts", "1");
    sc.put("evalLogPath", args[0]);
    if (cfgPath != null) sc.put("orderflowConfigPath", cfgPath);
    strategy.onInit(new StrategyConfig(sc));

    long events = 0, ticks = 0, entries = 0, wins = 0, losses = 0;
    long pnlTicks = 0;
    int position = 0, entryPrice = 0;
    for (int fi = 1; fi < args.length; fi++) {
      try (BufferedReader r = Files.newBufferedReader(Path.of(args[fi]))) {
        String line;
        while ((line = r.readLine()) != null) {
          if (line.isBlank()) continue;
          Event e;
          try { e = RawEventCodec.decode(line); } catch (RuntimeException ex) { continue; }
          if (e == null) continue;
          events++;
          state.bump(e);
          for (Feature f : features.values()) f.onEvent(e);
          if (!(e instanceof TickEvent te)) continue;
          ticks++;
          Intent in = strategy.onEvent(state);
          if (in == null || "none".equals(in.reason())) continue;
          if (position == 0 && in.targetPosition() != 0) {
            position = in.targetPosition();
            entryPrice = te.priceTicks();
            entries++;
          } else if (position != 0 && in.targetPosition() == 0) {
            int pnl = (te.priceTicks() - entryPrice) * Integer.signum(position);
            pnlTicks += pnl;
            if (pnl > 0) wins++; else losses++;
            position = 0;
          }
        }
      }
    }
    System.out.println("events=" + events + " trades=" + ticks + " bars=" + engine.barCount()
        + " entries=" + entries + " wins=" + wins + " losses=" + losses + " pnlTicks(gross)=" + pnlTicks
        + (position != 0 ? " (one position still open)" : ""));
    if (engine.lastRollNote() != null) System.out.println("note: " + engine.lastRollNote());
    System.out.println("engineErrors=" + engine.errorCount() + " strategyErrors=" + strategy.errorCount()
        + (engine.lastError() != null ? " lastEngineError=" + engine.lastError() : ""));
    if (engine.errorCount() > 0 || strategy.errorCount() > 0) System.exit(1); // build.sh smoke step fails on any error
  }
}
