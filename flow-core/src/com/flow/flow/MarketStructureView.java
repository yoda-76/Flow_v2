package com.flow.flow;

import com.flow.core.Feature;

/**
 * Trend/pullback/TJL-zone/flip structure (D-60), built from the user's
 * own trend-state system -- see `docs/dynamic/marketStructureRules.md`
 * (the reviewed source of truth, pending review) and
 * `marketStructureRulesTemp.md` (the 7 ambiguities it left open,
 * resolved by best judgment so implementation could proceed now, per
 * explicit instruction -- NOT confirmed, flagged in todo.md to revisit).
 *
 * Bar-close-triggered only (BarPhase.CLOSE) -- every rule in the source
 * system keys off a candle's close, never an intrabar/tick value
 * (marketStructureRules.md §9, "non-repainting"). Zero SDK dependency --
 * pure bar-data logic (open/high/low/close), so this lives in flow-core
 * like every other zero-SDK-dependency construct built this session
 * (BigTradeFeature, VWAPFeature, LiquidityMapFeature).
 *
 * Ready immediately after the first bar close (which establishes the
 * CHOCH bootstrap, marketStructureRulesTemp.md #1) -- forward-only from
 * attach, no historical warm-start, same D-37 pattern every other
 * construct here already accepted.
 */
public interface MarketStructureView extends Feature {
  String FEATURE_ID = "market_structure";

  enum Trend { UP, DOWN }

  enum PullbackState { NONE, FORMING, VALID }

  Trend trend();

  PullbackState pullbackState();

  /** Null only before the first real TJL pair has formed (CHOCH bootstrap still active). */
  ZoneRange lastTjl1();

  /** Before the first real pair forms, this IS the CHOCH bootstrap zone (a single point). */
  ZoneRange lastTjl2();

  /** Null until the first flip has happened. */
  ZoneRange lastAPlus();

  /** SBR (Uptrend->Downtrend flip) or RBS (Downtrend->Uptrend flip) -- null until the first flip. */
  ZoneRange lastSbrRbs();

  /** DT (Uptrend->Downtrend flip) or DB (Downtrend->Uptrend flip) -- null until the first flip. */
  ZoneRange lastDtDb();
}
