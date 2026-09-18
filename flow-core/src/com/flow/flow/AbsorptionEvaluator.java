package com.flow.flow;

import java.util.List;

/**
 * D-27's first entry evaluator: absorption, read from the footprint.
 * Stateless -- a pure function of the current footprint bar.
 *
 * v1 proxy, not a rigorous definition (the user's own framing left this
 * open -- "fill the ambiguity"): a genuine "aggressive selling absorbed
 * without price giving way" signal needs multi-bar price-outcome
 * tracking this evaluator doesn't do. Instead: the row at the touched
 * price shows meaningfully more combined (bid+ask) volume than every
 * other row in the same bar -- concentrated activity at exactly this
 * level, the footprint-visible half of what "absorption" colloquially
 * means. Revisit with real price-outcome tracking if this proxy turns
 * out too loose in practice.
 */
public final class AbsorptionEvaluator {
  private static final double CONCENTRATION_MULTIPLE = 1.5; // arbitrary v1 threshold

  private AbsorptionEvaluator() {}

  public static boolean absorptionAt(FootprintView footprint, int priceTicks, int toleranceTicks) {
    if (footprint == null || !footprint.isReady()) return false;
    List<FootprintRow> rows = footprint.current().rows();
    if (rows.isEmpty()) return false;

    double targetVolume = 0;
    double maxOtherVolume = 0;
    boolean found = false;
    for (FootprintRow r : rows) {
      double vol = r.askVolume() + r.bidVolume();
      if (Math.abs(r.priceTicks() - priceTicks) <= toleranceTicks) {
        targetVolume = Math.max(targetVolume, vol);
        found = true;
      } else {
        maxOtherVolume = Math.max(maxOtherVolume, vol);
      }
    }
    return found && targetVolume > 0 && targetVolume >= maxOtherVolume * CONCENTRATION_MULTIPLE;
  }
}
