package com.flow.backtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Smoke test for the CLI wrapper itself (arg parsing, strategy registry lookup, JSON output shape) -- the part
 * {@link BacktestEngineTest} doesn't cover, since it calls {@link BacktestEngine} directly. This is the actual
 * file format {@code analysis/backtest_report.py}/{@code backtest_compare.py} depend on, so a subtle bug here
 * (a wrong field name, bad JSON escaping) would silently break every downstream Python tool.
 */
public final class BacktestRunnerTest {
  private static int failures = 0;

  private static void check(String label, boolean ok, String detail) {
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": " + detail);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) throws IOException {
    testEndToEndCliProducesValidJsonl();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all BacktestRunner CLI checks passed.");
  }

  private static void testEndToEndCliProducesValidJsonl() throws IOException {
    Path csv = Files.createTempFile("flow_v2_runner_test", ".csv");
    Path out = Files.createTempFile("flow_v2_runner_test_out", ".jsonl");
    try {
      // A touch + a later target hit, same shape MarketStructureBacktestTest's own setup uses.
      StringBuilder sb = new StringBuilder("timestampMs,open,high,low,close,volume\n");
      long t = 0;
      sb.append(row(t += 60_000, 50, 58, 40, 55));
      sb.append(row(t += 60_000, 1005, 1010, 900, 980));
      sb.append(row(t += 60_000, 980, 985, 850, 860));
      sb.append(row(t += 60_000, 860, 1050, 900, 1040));
      sb.append(row(t += 60_000, 900, 905, 845, 870)); // touches TJL2 -> LONG @870
      sb.append(row(t += 60_000, 870, 920, 865, 900)); // target hit
      Files.writeString(csv, sb.toString());

      BacktestRunner.main(new String[] {
          csv.toString(), "1.0", "market_structure_backtest", "--out=" + out, "--rr=2.0", "--stopParam=2"});

      List<String> lines = Files.readAllLines(out);
      check("output file has header + 1 trade + summary (3 lines)", lines.size() == 3,
          "got " + lines.size() + " lines: " + lines);
      check("line 0 is a backtest_run header", lines.get(0).contains("\"type\":\"backtest_run\""), lines.get(0));
      check("header names the right strategy", lines.get(0).contains("\"strategyId\":\"market_structure_backtest\""), lines.get(0));
      check("header carries the param back out", lines.get(0).contains("\"rr\":\"2.0\""), lines.get(0));
      check("line 1 is a backtest_trade", lines.get(1).contains("\"type\":\"backtest_trade\""), lines.get(1));
      check("the trade is the expected LONG @870", lines.get(1).contains("\"entryPrice\":870.0"), lines.get(1));
      check("line 2 is a backtest_summary", lines.get(2).contains("\"type\":\"backtest_summary\""), lines.get(2));
      check("summary shows exactly 1 trade, 1 win", lines.get(2).contains("\"trades\":1") && lines.get(2).contains("\"wins\":1"),
          lines.get(2));
    } finally {
      Files.deleteIfExists(csv);
      Files.deleteIfExists(out);
    }
  }

  private static String row(long t, double o, double h, double l, double c) {
    return t + "," + o + "," + h + "," + l + "," + c + ",0\n";
  }
}
