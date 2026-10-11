package com.flow.orderflow;

/**
 * A strategy-generated trade idea (gapAnswers §8, G29). Owned by the idea source: it alone opens and closes it (zone
 * change, trend flip, session end). The execution model only evaluates it. direction: +1 long, -1 short.
 * invalidationTicks = the far edge of the zone: a structural stop can never be on the wrong side of it.
 */
public record TradeIdea(String id, int direction, int zoneLowTicks, int zoneHighTicks, int invalidationTicks,
                        long createdMs, String source) {
  public String zoneKey() { return zoneLowTicks + ":" + zoneHighTicks; }
}
