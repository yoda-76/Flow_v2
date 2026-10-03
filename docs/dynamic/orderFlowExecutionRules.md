# Order-flow execution rules — review record

Source: current implementation of `MarketStructureLvnReversalStrategy` and its order-flow evaluators. This document is the execution-rule review record. It preserves the original v1 behavior, records each decision made during review, and notes implementation consequences.

## Review status

**Review started:** 2026-10-03

**Method:** review section-by-section. A rule is marked **RESOLVED** only after we explicitly settle the behavior. Unresolved design choices remain **OPEN**.

---

## 1. Area of interest

Current implementation uses `MarketStructureView.lastTjl2()` as the strategy's single area of interest.

The strategy gates this with `tradeableLevels()` so the CHOCH bootstrap is never treated as tradeable. During a flip chain it uses the current DT/DB reference held by `lastTjl2()`. It intentionally does not expand this strategy to A+/SBR/RBS even when those levels are technically tradeable in the market-structure model.

Area-of-interest replacement is implicit: when `lastTjl2()` changes, the old AOI is retired and sweep state resets.

**Status: CONTEXT ONLY — not being redesigned in this review unless an execution decision depends on it.**

---

## 2. LVN selection

### Current behavior

On every strategy wake, the strategy scans `VolumeProfileView.zones()`, keeps LVN zones that overlap the AOI, and selects the zone with the largest tick width:

`highPriceTicks - lowPriceTicks`

No LVN overlapping the AOI means no entry.

### Open questions

**Q1 — What does “biggest LVN” mean?**
Current code interprets biggest as **widest price range**, not highest/lowest volume or another LVN metric.

**Q2 — Should the selected LVN be sticky?**
The volume-profile feature assigns persistent zone IDs across recomputes, but the strategy currently ignores those IDs and simply re-ranks the current zones on every wake. Therefore the strategy can switch between LVN identities as the current profile changes.

**Important clarification:** this is not because LVN identity is unavailable. `ZoneView.id()` is explicitly persistent across profile recomputes when the feature considers the zone the same. The current strategy simply does not use that identity for entry tracking.

---

## 3. Touch and proximity

### Current behavior

`TOUCH_TOLERANCE_TICKS = 2`.

Price is considered touching the selected LVN when it lies inside the LVN range expanded by two ticks on either side.

The same two-tick tolerance is also passed into the absorption evaluator and aggression evaluator.

Sweep is different: it reads liquidity at the exact touched price and has no tolerance argument.

### Open questions

**Q3 — Is two ticks the correct touch tolerance?**

**Q4 — Should touch, absorption proximity, and aggression proximity share one tolerance?**

---

## 4. Absorption

### Current behavior

`AbsorptionEvaluator` is stateless and reads only the current footprint bar.

For each footprint row:

`combinedVolume = askVolume + bidVolume`

Rows within the supplied tolerance of the touched price form the target group. The evaluator compares the target group's maximum row volume with the busiest non-target row.

Absorption is true when:

`targetVolume >= maxOtherVolume * 1.5`

and a target row exists with non-zero volume.

The current evaluator is therefore a **volume-concentration proxy**, not a full “aggressive orders were absorbed and price failed to move” definition.

### Open questions

**Q5 — Is 1.5× the right concentration threshold?**

**Q6 — Should absorption require multi-bar confirmation or price-outcome confirmation?**

---

## 5. Aggression

### Current behavior

`AggressionEvaluator` checks the bounded recent big-trade window.

A trade qualifies when:
- it is no older than `AGGRESSION_RECENCY_MS = 60,000` ms;
- its price is within the supplied tolerance of the touched price;
- its aggressor side matches the directional bias.

For a long, one qualifying buy-aggressor trade is enough. For a short, one qualifying sell-aggressor trade is enough.

The evaluator itself does **not** define what makes a trade “big”; that threshold is owned by the upstream big-trade feature.

### Open questions

**Q7 — Is 60 seconds the correct recency window?**

**Q8 — What exact upstream threshold defines a “big trade,” and is that threshold appropriate as an entry confirmation?**

---

## 6. Sweep

### Current behavior

`SweepEvaluator` is stateful.

For a bullish setup it watches resting **bid** size at the touched price. For a bearish setup it watches resting **ask** size.

It compares the current size with exactly one previous reading. A sweep fires when the resting size drops by at least 50%:

`dropRatio >= 0.5`

The previous reading is replaced on every call.

Sweep state resets when the AOI changes and when the current position closes.

This is explicitly a **DOM-visible liquidity-removal proxy**, not a direct observation of stop orders.

### Open questions

**Q9 — Is a 50% drop the correct threshold?**

**Q10 — Is one immediately-prior sample enough, or should sweep use a baseline/history?**

**Q11 — Should sweep memory survive a position close while the AOI remains unchanged?**

---

## 7. Combination of entry confirmations

### Current behavior

Entry requires:

`absorption OR aggression OR sweep`

One qualifying evaluator is sufficient.

All three are still computed, and their individual results are included in the intent reason.

### Open question

**Q12 — Is ANY-one-of-three actually the intended combination rule, or should there be a stricter requirement such as AND, 2-of-3, or another explicit combination?**

---

## 8. Stop

### Current behavior

The stop is placed two ticks beyond the AOI's far edge:

`bullish: aoi.low - 2`

`bearish: aoi.high + 2`

The placement logic intentionally aligns the stop with the structural invalidation boundary represented by the far-side AOI edge.

### Open question

**Q13 — Is a two-tick stop buffer correct, and should the buffer be fixed or adaptive?**

---

## 9. Target

### Current behavior

Target is currently a fixed 2:1 reward:risk calculation from the actual signal price:

`risk = abs(entrySignalPrice - stop)`

`target = signalPrice +/- 2 * risk`

This is explicitly provisional in the current code.

### Open question

**Q14 — Should the strategy keep a fixed 2:1 target, or should the target eventually be structural (for example, another market-structure level)?**

---

## 10. Position lifecycle and wake cadence

### Current behavior

The strategy holds at most one position.

While searching, it evaluates entry conditions on every wake.

While in position, it checks stop/target on every wake and re-emits the same desired position as an idempotent holding intent when neither has been hit.

The strategy uses `EveryTick` intentionally for this build because the current objective is end-to-end pipeline stress, not signal quality.

### Open question

**Q15 — For the signal-quality version, should entry detection use proximity/book triggers instead of keeping the strategy on EveryTick all the time?**

---

## Review decisions

No execution-rule decisions have been made yet in this review.

| ID | Topic | Status | Decision |
|---|---|---|---|
| Q1 | Biggest LVN definition | OPEN | — |
| Q2 | LVN identity/stickiness | OPEN | — |
| Q3 | Touch tolerance | OPEN | — |
| Q4 | Shared proximity tolerance | OPEN | — |
| Q5 | Absorption threshold | OPEN | — |
| Q6 | Absorption confirmation horizon | OPEN | — |
| Q7 | Aggression recency | OPEN | — |
| Q8 | Big-trade definition | OPEN | — |
| Q9 | Sweep drop threshold | OPEN | — |
| Q10 | Sweep baseline/history | OPEN | — |
| Q11 | Sweep reset lifecycle | OPEN | — |
| Q12 | Confirmation combination | OPEN | — |
| Q13 | Stop buffer | OPEN | — |
| Q14 | Target model | OPEN | — |
| Q15 | Wake cadence | OPEN | — |

## Review notes

The goal of this document is to make every judgment explicit. Existing implementation behavior is not automatically treated as the desired final rule.

Where a rule depends on another subsystem's definition (for example, what qualifies as a big trade), that dependency is part of the review rather than being silently accepted.

Once a decision is made, update both the relevant section above and the table in **Review decisions**, while keeping the original question visible for auditability.
