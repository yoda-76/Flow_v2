package com.flow.strategies;

import com.flow.core.FlowStrategy;
import com.flow.core.Intent;
import com.flow.core.MarketState;
import com.flow.core.StrategyConfig;
import com.flow.core.Trigger;
import com.flow.flow.VolumeProfileView;
import com.flow.flow.ZoneView;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Same trading behaviour as NullStrategy -- always Intent.none(), no
 * position, nothing to arm -- but declares D-38's LevelCross/
 * ZoneTransition triggers on every POC/VAH/VAL/LVN/HVN event, so
 * Pipeline's trace journaling (D-45) actually fires against real market
 * data for the first time. This is deliberately still a pure observer,
 * not a trading strategy: the point right now is exercising and
 * recording the trigger/trace mechanism live, not making a call.
 *
 * requires() gates arming on the volume-profile feature existing and
 * being ready (D-22), even though nothing here trades on it yet.
 */
public final class LevelZoneObserverStrategy implements FlowStrategy {
  private final AtomicLong intentSeq = new AtomicLong(0);

  @Override
  public String id() {
    return "level_zone_observer";
  }

  @Override
  public Set<String> requires() {
    return Set.of(VolumeProfileView.FEATURE_ID);
  }

  @Override
  public Set<Trigger> triggers() {
    Set<Trigger> t = new HashSet<>();
    t.add(new Trigger.BarClose()); // keeps the same heartbeat/decision cadence NullStrategy has

    for (String level : new String[]{VolumeProfileView.POC, VolumeProfileView.VAH, VolumeProfileView.VAL}) {
      for (Trigger.LevelCross.CrossKind kind : Trigger.LevelCross.CrossKind.values()) {
        t.add(new Trigger.LevelCross(VolumeProfileView.FEATURE_ID, level, kind));
      }
    }
    for (ZoneView.Kind zoneKind : ZoneView.Kind.values()) {
      for (Trigger.ZoneTransition.TransitionKind kind : Trigger.ZoneTransition.TransitionKind.values()) {
        t.add(new Trigger.ZoneTransition(VolumeProfileView.FEATURE_ID, zoneKind, kind));
      }
    }
    return Set.copyOf(t);
  }

  @Override
  public void onInit(StrategyConfig cfg) {
    // nothing to configure yet
  }

  @Override
  public Intent onEvent(MarketState state) {
    return Intent.none(id(), intentSeq.incrementAndGet());
  }
}
