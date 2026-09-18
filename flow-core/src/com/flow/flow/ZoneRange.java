package com.flow.flow;

/**
 * A price zone (low/high, integer ticks per D-21) -- used throughout
 * MarketStructureView for TJL1/TJL2/A+/SBR/RBS/DT/DB, all of which are
 * ranges, never single points (marketStructureRulesTemp.md).
 */
public record ZoneRange(int lowTicks, int highTicks) {
  public static ZoneRange of(int a, int b) {
    return a <= b ? new ZoneRange(a, b) : new ZoneRange(b, a);
  }
}
