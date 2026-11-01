package com.flow.strategies;

import com.flow.core.StrategyConfig;
import com.flow.journal.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-strategy parameters from the optional flat JSON file named by StrategyConfig key "strategyConfigPath"
 * (runtime default: config/strategies/&lt;strategyId&gt;.json). A missing file means "all defaults"; an unreadable or
 * malformed file throws at onInit so a typo can never silently run a strategy on defaults. The tick size comes from
 * StrategyConfig key "tickSize" (set by the runtime from the instrument), else the file's "tickSize", else 0.25.
 * Numeric values must be written as JSON numbers (numeric strings are not accepted); only skipDates is a string.
 */
final class StrategyParams {
  private final JsonObject json; // null = no file
  private final double tickSize;

  private StrategyParams(JsonObject json, double tickSize) {
    this.json = json;
    this.tickSize = tickSize;
  }

  static StrategyParams load(StrategyConfig cfg, String strategyId) {
    JsonObject json = null;
    String path = cfg == null ? null : cfg.get("strategyConfigPath");
    if (path != null && !path.isBlank() && Files.isRegularFile(Path.of(path))) {
      try {
        json = JsonObject.parse(Files.readString(Path.of(path), StandardCharsets.UTF_8).trim());
      } catch (IOException | RuntimeException e) {
        throw new IllegalArgumentException(strategyId + ": cannot read strategy config " + path + ": " + e.getMessage(), e);
      }
    }
    double tick = 0.25;
    String t = cfg == null ? null : cfg.get("tickSize");
    if (t != null && !t.isBlank()) tick = Double.parseDouble(t);
    else if (json != null && json.has("tickSize")) tick = json.getDouble("tickSize");
    if (!(tick > 0)) throw new IllegalArgumentException(strategyId + ": tickSize must be > 0, got " + tick);
    return new StrategyParams(json, tick);
  }

  double tickSize() { return tickSize; }

  int getInt(String key, int def) { return json != null && json.has(key) ? json.getInt(key) : def; }

  double getDouble(String key, double def) { return json != null && json.has(key) ? json.getDouble(key) : def; }

  boolean getBoolean(String key, boolean def) { return json != null && json.has(key) ? json.getBoolean(key) : def; }

  String getString(String key, String def) {
    if (json == null || !json.has(key) || json.isNull(key)) return def;
    return json.getString(key);
  }

  /** "2026-11-26,2026-12-24" -> set of dates (empty if absent/blank). */
  Set<LocalDate> getDates(String key) {
    Set<LocalDate> out = new HashSet<>();
    String s = getString(key, "");
    for (String part : s.split(",")) {
      String p = part.trim();
      if (!p.isEmpty()) out.add(LocalDate.parse(p));
    }
    return out;
  }
}
