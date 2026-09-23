package com.flow.flow;

import com.flow.core.Feature;

import java.util.Set;

/**
 * Trend/pullback/TJL-zone/flip structure (D-60, reworked D-74 against the
 * fully reviewed rules -- see `docs/dynamic/marketStructureRules.md`,
 * now the confirmed source of truth, all 10 points resolved by the user
 * directly (`decisions.md` D-71/D-72/D-73). `marketStructureRulesTemp.md`
 * is superseded, historical record only.
 *
 * Bar-close-triggered only (BarPhase.CLOSE) -- every rule in the source
 * system keys off a candle's close, never an intrabar/tick value
 * (marketStructureRules.md §9, "non-repainting"). Zero SDK dependency --
 * pure bar-data logic (open/high/low/close), so this lives in flow-core
 * like every other zero-SDK-dependency construct (BigTradeFeature,
 * VWAPFeature, LiquidityMapFeature).
 *
 * Ready as soon as the CHOCH bootstrap anchor is set (marketStructureRules.md
 * §1a) -- which per point 8 can be from a historical warm-start bar (D-64),
 * not necessarily the first live bar.
 */
public interface MarketStructureView extends Feature {
  String FEATURE_ID = "market_structure";

  enum Trend { UP, DOWN }

  enum PullbackState { NONE, FORMING, VALID }

  /**
   * Which structural zone a level is, for `tradeableLevels()` below. TJL1
   * is deliberately not a member -- §4's own invariant names TJL1 as the
   * near-side pivot, never the thing flip-watch or a reversal entry keys
   * off (TJL2 is); it's exposed via `lastTjl1()` for completeness/display
   * only, never itself part of the tradeable set.
   */
  enum TradeableLevel { TJL2, A_PLUS, SBR_RBS, DT, DB }

  Trend trend();

  PullbackState pullbackState();

  /** Null only before the first real TJL pair has formed (CHOCH bootstrap still active). */
  ZoneRange lastTjl1();

  /**
   * Before the first real pair forms, this IS the CHOCH bootstrap zone (a
   * single point, never tradeable -- see `tradeableLevels()`). Once a
   * chain of pair-less flips is underway (marketStructureRules.md §6,
   * points 5/6/9), this holds whichever DT/DB is CURRENTLY flip-watch's
   * reference -- the same field, not a separate one, since that's
   * literally what "DT/DB replaces lastTJL2 as the reference" means.
   */
  ZoneRange lastTjl2();

  /** Null unless exactly one flip old within the current chain -- see `tradeableLevels()`'s point-9 lifecycle. */
  ZoneRange lastAPlus();

  /** SBR (Uptrend->Downtrend flip) or RBS (Downtrend->Uptrend flip) -- same one-flip-old lifecycle as `lastAPlus()`. */
  ZoneRange lastSbrRbs();

  /** Double Top -- set on an Up->Down flip, persists (untouched) through subsequent Down->Up flips in the same chain until a fresh real TJL pair retires it. */
  ZoneRange lastDt();

  /** Double Bottom -- set on a Down->Up flip, persists (untouched) through subsequent Up->Down flips in the same chain until a fresh real TJL pair retires it. */
  ZoneRange lastDb();

  /**
   * Which of the zones above a strategy should currently treat as a
   * live, entry-worthy structural level -- marketStructureRules.md §6,
   * points 9 & 10, a concept that exists nowhere else in this interface
   * (the raw getters above still return their last-computed value even
   * when NOT in this set, same as `lastTjl1()`/`lastTjl2()` always have --
   * this is the authoritative "is it actually tradeable right now" signal
   * on top of that, not a replacement for the getters).
   *
   * Lifecycle: empty while only the CHOCH bootstrap is active (§1a: never
   * tradeable). `{TJL2}` in normal (non-chained) operation -- TJL1 is
   * deliberately excluded, see `TradeableLevel`'s own javadoc. Exactly
   * `{A_PLUS, SBR_RBS, DT}` or `{A_PLUS, SBR_RBS, DB}` for one flip only,
   * the chain's first. `{DT, DB}` from the chain's second flip onward,
   * refreshing whichever side was just crossed each further flip. Reset
   * to `{TJL2}` the instant a fresh real TJL1/TJL2 pair forms, at any
   * point -- immediately, not waiting for the next flip (point 10).
   */
  Set<TradeableLevel> tradeableLevels();

  /**
   * Highest high / lowest low across the pullback run currently in
   * progress (FORMING or VALID) -- null when there is no run. Exposed for
   * recording (D-90): it's the number a pullback's continuation is judged
   * against, and it otherwise lives only in private state.
   */
  Integer pullbackHighTicks();

  Integer pullbackLowTicks();
}
