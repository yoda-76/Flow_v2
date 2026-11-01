package com.flow.core;

import java.util.Set;

/**
 * Which instruments this system may trade, and how an instrument's symbol becomes a folder name under data/
 * (2026-09-27, user: "for now only GC is allowed"; findings F-1).
 *
 * The runtime runs on whatever chart the study is added to -- on the laptop trial it was first added to an ES chart
 * by mistake. Every strategy and risk limit here is tuned for COMEX gold (tick 0.1; lvn_fade_test's 5-tick bracket,
 * the 200-tick daily loss limit), so on any other instrument the runtime refuses to arm (it still records, into that
 * symbol's own data folder). Gold is the continuous chart "@GC" or an explicit contract "GC" + month code + year
 * ("GCZ6", "GCG27"); the tick size must also be gold's 0.1.
 */
public final class InstrumentPolicy {
  /**
   * Roots allowed to be traded, with the tick size each must have. Widen deliberately, with the strategies and limits
   * re-checked for the new market. 2026-11-02 (user lifted the gold-only rule, forwardTest_plan_v1.md): the micro
   * index futures MNQ (Matteo Conti S1-S3) and MES (Daily Anchored VWAP), tick 0.25. Full-size NQ/ES stay refused --
   * the forward test is sized at 1 micro, and a full contract is 10x the risk.
   */
  public static final java.util.Map<String, Double> ALLOWED = java.util.Map.of("GC", 0.1, "MNQ", 0.25, "MES", 0.25);
  public static final Set<String> ALLOWED_ROOTS = ALLOWED.keySet();

  private InstrumentPolicy() {}

  /** Null if this instrument may be traded, otherwise a reason for the REFUSE_TO_ARM log line. */
  public static String refuseReason(String symbol, double tickSize) {
    String root = rootOf(symbol);
    if (root == null || !ALLOWED.containsKey(root)) {
      return "instrument " + symbol + " is not allowed -- only " + new java.util.TreeSet<>(ALLOWED_ROOTS)
          + " (continuous @ROOT or a ROOT contract) may be traded";
    }
    double expected = ALLOWED.get(root);
    if (Math.abs(tickSize - expected) > 1e-9) {
      return "instrument " + symbol + " has tick size " + tickSize + ", expected " + expected + " for " + root;
    }
    return null;
  }

  /** "GC" for "@GC", "GCZ6", "GCG27"; null for anything that is not a recognisable futures symbol. */
  static String rootOf(String symbol) {
    if (symbol == null) return null;
    String s = symbol.trim().toUpperCase();
    if (s.startsWith("@")) s = s.substring(1);
    if (s.matches("[A-Z]{1,4}")) return s;  // a bare root such as "GC"
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Z]{1,3})[FGHJKMNQUVXZ][0-9]{1,2}").matcher(s);
    return m.matches() ? m.group(1) : null;
  }

  /**
   * The folder name for a symbol under data/<construct>/ (F-1): "@GC" -> "GC", "GCZ6" -> "GCZ6"; anything outside
   * [A-Za-z0-9._-] becomes '_'. Mirrored by analysis/daily_report.py symbol_dir() -- keep the two identical.
   */
  public static String symbolDir(String symbol) {
    if (symbol == null) return "unknown";
    String s = symbol.trim();
    if (s.startsWith("@")) s = s.substring(1);
    s = s.replaceAll("[^A-Za-z0-9._-]", "_");
    return s.isEmpty() ? "unknown" : s;
  }
}
