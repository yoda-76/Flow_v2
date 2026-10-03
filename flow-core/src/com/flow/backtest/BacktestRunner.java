package com.flow.backtest;

import com.flow.journal.Json;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point that runs {@link BacktestEngine} once and writes one JSONL file -- the shared record every
 * downstream tool reads: {@code analysis/backtest_report.py} renders ONE run's markdown report, and
 * {@code analysis/backtest_compare.py} reads MANY runs' files side by side (the user's own instruction,
 * 2026-10-03: a parameter sweep saves one report per combination rather than re-running the engine to compare).
 * Mirrors `MarketStructureBacktest.main()`'s plain CLI shape; adds a strategy registry keyed by id so a new
 * strategy is one new line here, not an engine change (`docs/backtest-strategy-guide.md` walks through the whole
 * thing end to end).
 *
 * Record shapes, one JSON object per line (same "flat object, {@code type} field dispatches it" convention the
 * live journal already uses, e.g. {@code RiskChain.resultLine}):
 * <pre>
 * {"type":"backtest_run", strategyId, csvPath, tickSize, barCount, firstBarMs, lastBarMs, params:{...}, risk:{...}, generatedAtMs}
 * {"type":"backtest_trade", entryTimeMs, entryPrice, direction, stopPrice, targetPrice, exitTimeMs, exitPrice, exitReason, rMultiple}  (one per trade)
 * {"type":"backtest_summary", trades, wins, losses, totalR, avgR, maxDrawdownR, deniedDailyLoss, deniedMaxReversals, deniedRateLimit, deniedMinDwell, killSwitchTrips}
 * </pre>
 */
public final class BacktestRunner {
  @FunctionalInterface
  interface StrategyFactory {
    BacktestStrategy create(Map<String, String> params, double tickSize);
  }

  /** Add one line here per new strategy -- see docs/backtest-strategy-guide.md. */
  private static final Map<String, StrategyFactory> REGISTRY = Map.of(
      "market_structure_backtest", (params, tickSize) -> new MarketStructureBacktestStrategy(
          tickSize,
          parseDouble(params, "rr", 2.0),
          MarketStructureBacktest.StopRule.valueOf(params.getOrDefault("stopRule", "FIXED_BUFFER_TICKS")),
          parseDouble(params, "stopParam", 2.0))
  );

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      System.err.println("usage: BacktestRunner <csv> <tickSize> <strategyId> [--key=value ...]");
      System.err.println("  strategy params: --rr=2.0 --stopRule=FIXED_BUFFER_TICKS|ZONE_SIZE_MULTIPLE|FIXED_PRICE_DISTANCE --stopParam=2 --aggregateMinutes=0");
      System.err.println("  date range:      --sinceMs=<epoch ms> --untilMs=<epoch ms> (filters bars before everything else)");
      System.err.println("  risk params:     --dailyLossLimitTicks=200 --maxContracts=1 --rateLimitPerMinute=6 --minDwellMs=5000 --maxReversalsPerSession=20");
      System.err.println("  output:          --out=<path> (default analysis/data/backtest_runs/<strategyId>_<epochMs>.jsonl)");
      System.err.println("known strategies: " + REGISTRY.keySet());
      System.exit(2);
    }
    Path csv = Path.of(args[0]);
    double tickSize = Double.parseDouble(args[1]);
    String strategyId = args[2];
    Map<String, String> params = new HashMap<>();
    for (int i = 3; i < args.length; i++) {
      String a = args[i];
      if (!a.startsWith("--") || !a.contains("=")) continue;
      int eq = a.indexOf('=');
      params.put(a.substring(2, eq), a.substring(eq + 1));
    }

    StrategyFactory factory = REGISTRY.get(strategyId);
    if (factory == null) {
      System.err.println("unknown strategyId '" + strategyId + "', known: " + REGISTRY.keySet());
      System.exit(2);
      return;
    }

    List<Bar> bars = BacktestCsv.readCsv(csv);
    long aggregateMinutes = (long) parseDouble(params, "aggregateMinutes", 0);
    if (aggregateMinutes > 0) {
      bars = BacktestCsv.aggregate(bars, aggregateMinutes * 60_000L);
    }
    if (params.containsKey("sinceMs") || params.containsKey("untilMs")) {
      long sinceMs = (long) parseDouble(params, "sinceMs", Long.MIN_VALUE);
      long untilMs = (long) parseDouble(params, "untilMs", Long.MAX_VALUE);
      bars = bars.stream().filter(b -> b.timestampMs() >= sinceMs && b.timestampMs() <= untilMs).toList();
    }
    BacktestStrategy strategy = factory.create(params, tickSize);
    BacktestEngine.RiskConfig risk = new BacktestEngine.RiskConfig(
        1,
        (int) parseDouble(params, "maxContracts", 1),
        (int) parseDouble(params, "dailyLossLimitTicks", 200),
        (int) parseDouble(params, "rateLimitPerMinute", 6),
        (long) parseDouble(params, "minDwellMs", 5_000),
        (int) parseDouble(params, "maxReversalsPerSession", 20));

    BacktestEngine.Report report = BacktestEngine.run(bars, tickSize, strategy, risk);

    Path out = params.containsKey("out") ? Path.of(params.get("out"))
        : Path.of("analysis/data/backtest_runs/" + strategyId + "_" + System.currentTimeMillis() + ".jsonl");
    Files.createDirectories(out.getParent());

    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
      Json header = Json.object()
          .field("type", "backtest_run")
          .field("strategyId", strategyId)
          .field("csvPath", csv.toString())
          .field("tickSize", tickSize)
          .field("barCount", bars.size());
      if (!bars.isEmpty()) {
        header.field("firstBarMs", bars.get(0).timestampMs()).field("lastBarMs", bars.get(bars.size() - 1).timestampMs());
      }
      header.fieldRaw("params", paramsToJson(params))
          .fieldRaw("risk", riskToJson(risk))
          .field("generatedAtMs", System.currentTimeMillis());
      w.println(header.build());

      for (BacktestEngine.Trade t : report.trades()) {
        w.println(Json.object()
            .field("type", "backtest_trade")
            .field("entryTimeMs", t.entryTimeMs())
            .field("entryPrice", t.entryPrice())
            .field("direction", t.direction())
            .field("stopPrice", t.stopPrice())
            .field("targetPrice", t.targetPrice())
            .field("exitTimeMs", t.exitTimeMs())
            .field("exitPrice", t.exitPrice())
            .field("exitReason", t.exitReason())
            .field("rMultiple", t.rMultiple())
            .build());
      }

      w.println(Json.object()
          .field("type", "backtest_summary")
          .field("trades", report.trades().size())
          .field("wins", report.wins())
          .field("losses", report.losses())
          .field("totalR", report.totalR())
          .field("avgR", report.avgR())
          .field("maxDrawdownR", report.maxDrawdownR())
          .field("deniedDailyLoss", report.denials().dailyLoss())
          .field("deniedMaxReversals", report.denials().maxReversals())
          .field("deniedRateLimit", report.denials().rateLimit())
          .field("deniedMinDwell", report.denials().minDwell())
          .field("killSwitchTrips", report.killSwitchTrips())
          .build());
    }

    System.out.println("wrote " + out + " (" + bars.size() + " bars, " + report.trades().size() + " trades, totalR="
        + String.format("%.2f", report.totalR()) + ")");
  }

  private static double parseDouble(Map<String, String> params, String key, double def) {
    String v = params.get(key);
    return v == null ? def : Double.parseDouble(v);
  }

  private static String paramsToJson(Map<String, String> params) {
    Json j = Json.object();
    for (var e : params.entrySet()) {
      if (e.getKey().equals("out")) continue; // not a strategy/risk param, just the output path
      j.field(e.getKey(), e.getValue());
    }
    return j.build();
  }

  private static String riskToJson(BacktestEngine.RiskConfig r) {
    return Json.object()
        .field("fixedContracts", r.fixedContracts())
        .field("maxContracts", r.maxContracts())
        .field("dailyLossLimitTicks", r.dailyLossLimitTicks())
        .field("rateLimitPerMinute", r.rateLimitPerMinute())
        .field("minDwellMs", r.minDwellMs())
        .field("maxReversalsPerSession", r.maxReversalsPerSession())
        .build();
  }
}
