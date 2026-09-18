package com.flow.strategies;

import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.Trigger;
import com.flow.flow.AbsorptionEvaluator;
import com.flow.flow.AggressionEvaluator;
import com.flow.flow.BigTradeView;
import com.flow.flow.FootprintView;
import com.flow.flow.LiquidityMapView;
import com.flow.flow.MarketStructureView;
import com.flow.flow.OrderRepeatView;
import com.flow.flow.SweepEvaluator;
import com.flow.flow.VWAPView;
import com.flow.flow.VolumeProfileView;
import com.flow.flow.ZoneRange;
import com.flow.flow.ZoneView;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * D-62: the first real strategy, exercising every construct built this
 * session end-to-end -- not a signal expected to be correct or
 * profitable yet (explicitly, per the user, 2026-09-18: "i dont need to
 * test the strategy itself, i want to see if the system can hold
 * everything together"). Per the user's own design, with every
 * remaining ambiguity resolved by best judgment (documented inline,
 * same "temp, revisit later" stance as marketStructureRulesTemp.md):
 *
 * 1. **Area of interest** = MarketStructureView.lastTjl2() in the
 *    current trend. Chosen deliberately, not arbitrarily: TJL2 is
 *    already the system's own "far-side/invalidation boundary" (the
 *    zone flip-watch keys off, marketStructureRules.md's named
 *    invariant) -- reusing it as the trade zone means the "pullback
 *    zone" and "post-flip retest zone" cases are the SAME concept
 *    (lastTjl2 always means the right thing, whether it's a real TJL2,
 *    the CHOCH bootstrap, or a post-flip SBR/RBS), no separate logic
 *    needed for each. Trend UP -> long bias (zone is support below
 *    price); DOWN -> short bias (zone is resistance above price).
 * 2. **Area-of-interest expiration**: implicit, not a separate timer --
 *    the tracked zone is only ever "the current lastTjl2 value." The
 *    moment that value changes (a new TJL pair forms, or a flip
 *    reassigns it), the old one is retired automatically and all
 *    in-progress tracking (the LVN search, the sweep evaluator's
 *    memory) resets. No time/distance-based staleness rule added on
 *    top -- if a zone turns out to go stale for a long time without
 *    ever being replaced, that's a real case to revisit with actual
 *    data, not guessed at now.
 * 3. **Biggest LVN inside the area of interest**: recomputed fresh from
 *    VolumeProfileView.zones() every wake, filtered to Kind.LVN,
 *    filtered to overlapping the area of interest's range, the widest
 *    one wins. Not tracked by persistent zone id across wakes (simpler;
 *    if the "biggest" LVN changes identity tick to tick that's accepted
 *    for this pass, not treated as a bug).
 * 4. **No LVN in the area of interest -> no trade**, exactly as stated.
 * 5. **Touch** = price within a few ticks of the LVN's own range.
 * 6. **Entry confirmation**: absorption (footprint) OR aggression (big
 *    trades) OR sweep (liquidity map) -- ANY one, not all three
 *    required. The user's own phrasing ("...or all of them combined")
 *    was read as "any of these, together or alone," not as requiring
 *    unanimous agreement -- simpler to implement and, for a discretionary
 *    order-flow read, matches how multiple independent confirmations
 *    are normally treated (any one is enough), not ANDed together.
 * 7. **Stop**: a small buffer beyond the area of interest's own far
 *    edge -- the same edge that, if broken, would flip the trend
 *    (marketStructureRules.md again), so the stop and the structural
 *    invalidation point are the same idea by construction.
 * 8. **Target**: a fixed 2:1 reward:risk multiple off the stop distance.
 *    Simplest defensible choice that needed no extra logic to find
 *    "the next structural level" -- revisit once this actually gets
 *    tested for real.
 *
 * VWAP and order-repeat tracking are read but never gate the trade --
 * included per the user's explicit ask that every construct built this
 * session get exercised, folded into the intent's own reason string as
 * non-blocking context rather than invented as a fake requirement.
 *
 * requires() lists every one of the 7 features built this session --
 * deliberately, so arming exercises every construct's readiness path
 * together, matching the same "used freely" instruction.
 */
public final class MarketStructureLvnReversalStrategy implements FlowStrategy {
  private static final int TOUCH_TOLERANCE_TICKS = 2;
  private static final int STOP_BUFFER_TICKS = 2;
  private static final double REWARD_RISK_MULTIPLE = 2.0;
  private static final long AGGRESSION_RECENCY_MS = 60_000L;

  private enum Phase { SEARCHING, IN_POSITION }

  private final AtomicLong intentSeq = new AtomicLong(0);
  private final SweepEvaluator sweepEvaluator = new SweepEvaluator();

  private Phase phase = Phase.SEARCHING;
  private ZoneRange trackedAreaOfInterest;
  private int fixedContracts = 1;

  private int positionDirection; // +1 long, -1 short -- only meaningful while phase == IN_POSITION
  private Integer positionStopTicks;
  private Integer positionTargetTicks;

  // Bugfix (D-62, found in self-review before this ever ran live): a
  // rejected intent (RiskChain blocked it -- e.g. armed=false, the
  // literal default) never reaches OrderGateway, so phase/position
  // fields mutated in anticipation of that intent must be rolled back,
  // not left as if the change had actually happened. Matched by seq
  // since only one mutating intent is ever "pending" at a time (this
  // strategy processes one event at a time, never concurrently).
  private long pendingMutationSeq = -1;
  private Phase savedPhase;
  private ZoneRange savedAreaOfInterest;
  private int savedPositionDirection;
  private Integer savedPositionStopTicks;
  private Integer savedPositionTargetTicks;

  private void saveStateFor(long seq) {
    pendingMutationSeq = seq;
    savedPhase = phase;
    savedAreaOfInterest = trackedAreaOfInterest;
    savedPositionDirection = positionDirection;
    savedPositionStopTicks = positionStopTicks;
    savedPositionTargetTicks = positionTargetTicks;
  }

  @Override
  public void onIntentRejected(Intent intent, String reason) {
    if (intent.seq() != pendingMutationSeq) return; // not the mutation we're tracking (e.g. an unrelated stale notification)
    phase = savedPhase;
    trackedAreaOfInterest = savedAreaOfInterest;
    positionDirection = savedPositionDirection;
    positionStopTicks = savedPositionStopTicks;
    positionTargetTicks = savedPositionTargetTicks;
    pendingMutationSeq = -1;
  }

  @Override
  public String id() {
    return "market_structure_lvn_reversal";
  }

  @Override
  public Set<String> requires() {
    return Set.of(
        MarketStructureView.FEATURE_ID, VolumeProfileView.FEATURE_ID, FootprintView.FEATURE_ID,
        BigTradeView.FEATURE_ID, LiquidityMapView.FEATURE_ID, VWAPView.FEATURE_ID,
        OrderRepeatView.FEATURE_ID);
  }

  @Override
  public Set<Trigger> triggers() {
    // EveryTick, deliberately: this build's whole point is stress-testing
    // the live pipeline at its most demanding cadence, not signal quality
    // (README's "first strategies declare BAR_CLOSE" phasing is for
    // settling the CORE, which is already well past that point here).
    return Set.of(new Trigger.EveryTick());
  }

  @Override
  public void onInit(StrategyConfig cfg) {
    fixedContracts = Integer.parseInt(cfg.getOrDefault("fixedContracts", "1"));
  }

  @Override
  public Intent onEvent(MarketState state) {
    Integer price = state.lastPriceTicks();
    MarketStructureView ms = state.marketStructure();
    if (price == null || ms == null || !ms.isReady()) {
      return Intent.none(id(), intentSeq.incrementAndGet());
    }
    return phase == Phase.IN_POSITION ? manageOpenPosition(price) : search(state, ms, price);
  }

  private Intent manageOpenPosition(int price) {
    boolean stopHit = positionDirection > 0 ? price <= positionStopTicks : price >= positionStopTicks;
    boolean targetHit = positionDirection > 0 ? price >= positionTargetTicks : price <= positionTargetTicks;
    if (stopHit || targetHit) {
      long seq = intentSeq.incrementAndGet();
      saveStateFor(seq); // roll back to still-IN_POSITION if this exit gets rejected -- see onIntentRejected()
      phase = Phase.SEARCHING;
      trackedAreaOfInterest = null;
      sweepEvaluator.reset();
      return new Intent(id(), seq, 0, null, null, stopHit ? "stop_hit" : "target_hit");
    }
    // Still holding -- re-assert the same desired state (README: an
    // identical intent is a no-op at reconciliation, this is just an
    // honest restatement of "still want this," not a new decision).
    return new Intent(id(), intentSeq.incrementAndGet(), positionDirection * fixedContracts,
        positionStopTicks, positionTargetTicks, "holding");
  }

  private Intent search(MarketState state, MarketStructureView ms, int price) {
    ZoneRange aoi = ms.lastTjl2();
    if (aoi == null) return Intent.none(id(), intentSeq.incrementAndGet());

    if (trackedAreaOfInterest == null || !trackedAreaOfInterest.equals(aoi)) {
      trackedAreaOfInterest = aoi; // (re)defined -- see class javadoc point 2
      sweepEvaluator.reset();
    }

    if (price < aoi.lowTicks() || price > aoi.highTicks()) {
      return Intent.none(id(), intentSeq.incrementAndGet()); // not in the area of interest yet
    }

    VolumeProfileView vp = state.volumeProfile();
    if (vp == null || !vp.isReady()) return Intent.none(id(), intentSeq.incrementAndGet());

    ZoneView biggestLvn = null;
    for (ZoneView z : vp.zones()) {
      if (z.kind() != ZoneView.Kind.LVN) continue;
      boolean overlaps = z.lowPriceTicks() <= aoi.highTicks() && z.highPriceTicks() >= aoi.lowTicks();
      if (!overlaps) continue;
      if (biggestLvn == null
          || (z.highPriceTicks() - z.lowPriceTicks()) > (biggestLvn.highPriceTicks() - biggestLvn.lowPriceTicks())) {
        biggestLvn = z;
      }
    }
    if (biggestLvn == null) {
      return Intent.none(id(), intentSeq.incrementAndGet()); // no LVN in the AOI -- no trade, per explicit instruction
    }

    boolean touchingLvn = price >= biggestLvn.lowPriceTicks() - TOUCH_TOLERANCE_TICKS
        && price <= biggestLvn.highPriceTicks() + TOUCH_TOLERANCE_TICKS;
    if (!touchingLvn) {
      return Intent.none(id(), intentSeq.incrementAndGet());
    }

    boolean bullish = ms.trend() == MarketStructureView.Trend.UP;
    boolean absorption = AbsorptionEvaluator.absorptionAt(state.footprint(), price, TOUCH_TOLERANCE_TICKS);
    boolean aggression = AggressionEvaluator.aggressionAt(
        state.bigTrades(), price, TOUCH_TOLERANCE_TICKS, bullish, state.exchangeTimeMs(), AGGRESSION_RECENCY_MS);
    boolean sweep = sweepEvaluator.sweepAt(state.liquidityMap(), price, bullish);

    // Non-gating confluence context -- read because the user asked for
    // every construct to be exercised, never blocks the trade.
    VWAPView vwapFeature = state.vwap();
    Double vwapValue = vwapFeature != null ? vwapFeature.vwap() : null;
    OrderRepeatView orderRepeats = state.orderRepeats();
    long orderRepeatCount = orderRepeats != null ? orderRepeats.recent().size() : 0;

    if (!(absorption || aggression || sweep)) {
      return Intent.none(id(), intentSeq.incrementAndGet());
    }

    int direction = bullish ? 1 : -1;
    int stopTicks = bullish ? aoi.lowTicks() - STOP_BUFFER_TICKS : aoi.highTicks() + STOP_BUFFER_TICKS;
    int riskTicks = Math.abs(price - stopTicks);
    int targetTicks = bullish
        ? price + (int) Math.round(riskTicks * REWARD_RISK_MULTIPLE)
        : price - (int) Math.round(riskTicks * REWARD_RISK_MULTIPLE);

    long seq = intentSeq.incrementAndGet();
    saveStateFor(seq); // roll back to SEARCHING if this entry gets rejected -- see onIntentRejected()
    phase = Phase.IN_POSITION;
    positionDirection = direction;
    positionStopTicks = stopTicks;
    positionTargetTicks = targetTicks;

    String reason = "lvn_entry trend=" + ms.trend()
        + " absorption=" + absorption + " aggression=" + aggression + " sweep=" + sweep
        + " vwap=" + vwapValue + " orderRepeats=" + orderRepeatCount;

    return new Intent(id(), seq, direction * fixedContracts, stopTicks, targetTicks, reason);
  }
}
