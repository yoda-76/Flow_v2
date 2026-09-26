package com.flow.strategies;

import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.Trigger;
import com.flow.flow.VolumeProfileView;
import com.flow.flow.ZoneView;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deliberately simple, high-frequency test strategy (2026-09-21, user's
 * explicit request) -- not a real edge, exists to stress-test the real-
 * order plumbing (entry, bracket, fill, SL/TP hit) with a rule that fires
 * far more often than MarketStructureLvnReversalStrategy's single-zone,
 * multi-confirmation entry. Fades every LVN touch: entering an LVN from
 * below means price is pushing UP into thin volume, so this fades that
 * with a SHORT (betting on rejection back down); entering from above
 * fades with a LONG. Fixed $0.5 price-distance SL and TP (1:1, tightened
 * from $2 on 2026-09-21 per direct request -- same entry trigger,
 * smaller SL/TP so the plumbing test cycles through entry/fill/bracket/
 * exit faster), same FIXED_PRICE_DISTANCE convention
 * MarketStructureBacktest.StopRule already uses for backtesting (D-80),
 * independent of the zone's own size.
 *
 * SL_TP_TICKS is hardcoded for @GC's 0.1 tick size ($0.5 / 0.1 = 5 ticks)
 * rather than threaded through StrategyConfig -- StrategyConfig is still
 * an empty-map placeholder (see todo.md), and this is a throwaway
 * plumbing test, not a strategy meant to generalize across instruments.
 * If this ever becomes a real strategy, tick size belongs in config, not
 * a literal here.
 *
 * WARMUP_MS (user's explicit request, 2026-09-21; shortened from 5 to 2
 * minutes same day): no entries in the first 2 minutes after this
 * strategy instance's first observed event -- lets volume-profile zones
 * settle past their earliest, thinnest-sample state before trading off
 * them. Anchored to event time (state.exchangeTimeMs()), not the wall
 * clock -- nothing below ingest may read it (README), and this keeps
 * replay honest.
 */
public final class LvnFadeTestStrategy implements FlowStrategy {
  private static final int SL_TP_TICKS = 5; // @GC, 0.1 tick size: $0.5 / 0.1 = 5 ticks
  private static final long WARMUP_MS = 2 * 60 * 1000L;

  private enum Phase { SEARCHING, IN_POSITION }

  private final AtomicLong intentSeq = new AtomicLong(0);
  private Phase phase = Phase.SEARCHING;
  private Integer prevPriceTicks = null;
  private Long firstEventTimeMs = null;
  private int positionDirection = 0; // +1 long, -1 short
  private int positionStopTicks;
  private int positionTargetTicks;

  @Override
  public String id() {
    return "lvn_fade_test";
  }

  @Override
  public Set<String> requires() {
    return Set.of(VolumeProfileView.FEATURE_ID);
  }

  @Override
  public Set<Trigger> triggers() {
    return Set.of(new Trigger.EveryTick());
  }

  @Override
  public void onInit(StrategyConfig cfg) {
    // No params -- fixedContracts is always 1, matching config/risk.json's
    // maxContracts=1 cap; not worth a config knob for a throwaway test.
  }

  @Override
  public Intent onEvent(MarketState state) {
    if (firstEventTimeMs == null) {
      firstEventTimeMs = state.exchangeTimeMs();
    }
    Integer price = state.lastPriceTicks();
    if (price == null) {
      return Intent.none(id(), intentSeq.incrementAndGet());
    }
    // Warmup gate: still let an already-open position manage itself (stop/
    // target must keep working regardless), only suppress new entries.
    if (phase == Phase.SEARCHING && state.exchangeTimeMs() - firstEventTimeMs < WARMUP_MS) {
      prevPriceTicks = price;
      return Intent.none(id(), intentSeq.incrementAndGet());
    }
    Intent result = phase == Phase.IN_POSITION ? manageOpenPosition(price) : search(state, price);
    prevPriceTicks = price;
    return result;
  }

  @Override
  public void onFlattened(String reason) {
    phase = Phase.SEARCHING; // D-92: the runtime closed everything -- drop the phantom position
    positionDirection = 0;
  }

  private Intent manageOpenPosition(int price) {
    boolean stopHit = positionDirection > 0 ? price <= positionStopTicks : price >= positionStopTicks;
    boolean targetHit = positionDirection > 0 ? price >= positionTargetTicks : price <= positionTargetTicks;
    if (stopHit || targetHit) {
      phase = Phase.SEARCHING;
      return new Intent(id(), intentSeq.incrementAndGet(), 0, null, null, stopHit ? "stop_hit" : "target_hit");
    }
    // Still holding -- honest restatement of "still want this," a no-op at reconciliation (README).
    return new Intent(id(), intentSeq.incrementAndGet(), positionDirection, positionStopTicks, positionTargetTicks, "holding");
  }

  private Intent search(MarketState state, int price) {
    if (prevPriceTicks == null) {
      return Intent.none(id(), intentSeq.incrementAndGet()); // need a prior tick to know entry direction
    }
    VolumeProfileView vp = state.volumeProfile();
    if (vp == null || !vp.isReady()) {
      return Intent.none(id(), intentSeq.incrementAndGet());
    }

    for (ZoneView z : vp.zones()) {
      if (z.kind() != ZoneView.Kind.LVN) continue;

      boolean enteredFromBelow = prevPriceTicks < z.lowPriceTicks() && price >= z.lowPriceTicks();
      boolean enteredFromAbove = prevPriceTicks > z.highPriceTicks() && price <= z.highPriceTicks();
      if (!enteredFromBelow && !enteredFromAbove) continue;

      int direction = enteredFromBelow ? -1 : 1; // entered from below -> short; from above -> long
      int stopTicks = direction > 0 ? price - SL_TP_TICKS : price + SL_TP_TICKS;
      int targetTicks = direction > 0 ? price + SL_TP_TICKS : price - SL_TP_TICKS;

      phase = Phase.IN_POSITION;
      positionDirection = direction;
      positionStopTicks = stopTicks;
      positionTargetTicks = targetTicks;

      String reason = "lvn_fade entered=" + (enteredFromBelow ? "below" : "above")
          + " zone=[" + z.lowPriceTicks() + "," + z.highPriceTicks() + "]";
      return new Intent(id(), intentSeq.incrementAndGet(), direction, stopTicks, targetTicks, reason);
    }
    return Intent.none(id(), intentSeq.incrementAndGet());
  }
}
