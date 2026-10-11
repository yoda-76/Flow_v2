# Order-flow rules — OPEN QUESTIONS (to be reviewed, v2)

**Built:** 2026-10-11 from `orderFlowRules_reviewed_v1.md` (your review of `orderFlowRules_toBeReviewed_v1.md`).
**Scope:** only what is still open after that review. Nothing already RESOLVED is re-asked. Every question here is OPEN until you decide.
**Authority:** `orderFlowRules_reviewed_v1.md` stays the source of truth for approved rules. When you answer, the answer goes into a
new `orderFlowRules_reviewed_v2.md` (copy of v1 + the new decisions).

## File naming (new)

| File | Role |
|---|---|
| `orderFlowRules_toBeReviewed_vN.md` | questions we ask |
| `orderFlowRules_reviewed_vN.md` | the same N, with your answers folded in (approved rules) |
| `orderFlowRulesRedefinition.md`, `orderFlowRules_master_review_additions.md` | inputs to v1, kept for history |
| `orderFlowExecutionRules.md` | old placeholder, not a reference |

v1 pair = `toBeReviewed_v1` ↔ `reviewed_v1`. (`reviewed_v1` was called `reviewed_v18` in your draft; renamed so the numbers match.)

## ID notes (R9 — merged and colliding IDs)

The review log used the *redefinition* numbering; the master added IDs that collide with it. In this file the master/additions question gets an **-A** suffix.

| This file | Meaning | Collides with (reviewed_v1) |
|---|---|---|
| Q3.5-A | what counts as meaningful aggression (additions Q3.5) | Q3.5 = data source (resolved) |
| Q4.5-A | do we need all four reads | Q4.5 = absorption stages (partial) |
| Q5.7-A | touch definition + tolerance (master Q5.7 = additions Q5.9 + Q5.10) | Q5.7 = HTF vs primary structure (resolved) |
| Q5.8-A | LVN strength ranking (additions Q5.8) | Q5.8 = session extremes (partial) |
| Q12.3-A | is sweep in V1 (additions Q12.3) | Q12.3 = heatmap features (resolved) |
| Q3.6, Q4.2, Q5.9, Q5.10, Q8.4, Q8.5 | merged into Q3.2, Q3.2, Q5.7-A, Q5.7-A, Q-L1, Q-L2 | — |

## Things in reviewed_v1 worth a second look (found while reading it)

| # | Observation | Needs |
|---|---|---|
| X1 | **Lifecycle vs Q8.1.** v1 says the aggregation layer produces Go/No-Go with no separate confirmation count, and models keep updating (Q8.3). The Monitoring → Activated → Confirmed → Executed machine from v1 §7 is therefore not yet reconciled with it. Is **Go = Confirmed**? Is "Activated" still a state? | Q-L1/Q-L2 below |
| X2 | **M4, M5, M7 depend on undefined reads.** Q0.1 puts all seven models in V1, but exhaustion (M4), delta flip (M4, M5), and profile-shape detection (M7) have no definition yet. R1 says scope is provisional until these are defined. | Q4.x, Q5.5-A |
| X3 | **Touch ≠ read proximity (R5)** but Q3.4 only settled proximity (ticks and price range, configurable). The touch event itself (what activates a location) is open. | Q5.7-A |
| X4 | **Q6.2 scale (-2..+2) has no meaning yet.** Five outcomes are fixed but what earns +2 vs +1 per model is undefined, as is the "low aggregate score" in Q6.20. | Q6.48 |
| X5 | **Duplicates in v1:** Q6.14 ≈ Q6.41; Q6.15 ≈ Q6.44; Q6.17-19 ≈ Q6.34-35. Consistent, not conflicting; nothing to decide, only noting so the next review can merge them. | — |

---

## A. Bar and data unit (review order 2)

Nothing open. (Q1.1–1.3 resolved: configurable bars, fixed sizes, separate execution/structure timeframes. Adaptive sizing deferred, so R6 applies only to Q3.1 below.)

- ⚠️ **Q1.4** *(new)* Which bar sizes do we run first as initial test values (e.g. 500-tick, 5-min)? Configurable is settled; the starting set isn't. The course uses 500-tick for order flow and 1h/30m for structure.

## B. Footprint primitives and aggression (review order 3)

- ⚠️ **Q2.4** *(partial)* Finished auction: which zero-side is significant at each extreme? Also: strictly zero or ≤ 1–2 lots, and does it need a minimum opposite-side volume?
- ⚠️ **Q2.5** Is finished/unfinished auction a trigger, a confirmation, or only a context flag? (Course: "not mandatory, only at a location".)
- ⚠️ **Q2.6** *(new)* Stacked imbalance: ≥ 3 consecutive levels is the course's number. Fixed or configurable? Does it need a minimum volume per level (links to Q2.2)?
- ⚠️ **Q2.7** *(new)* Initial test values for the configurable parameters (imbalance 300 %, row grouping > 5 ticks, minimum volume)? Configurable is settled; the first values to run aren't.
- ⚠️ **Q3.1** *(partial)* Big-trade threshold: how does it adapt (what adapts, lookback, session handling)? R6: don't implement "adaptive" as an unspecified heuristic. Starting value is 30 per the course.
- ⚠️ **Q3.2** *(partial; absorbs Q3.6, Q4.2)* How is "aggression succeeded / failed" measured? v1 settled: judge by price response. Open: measurement window; progress metric (ticks, fraction of zone, ATR); success/failure thresholds; whether it is evaluated at bar end or inside a time window.
- ⚠️ **Q3.5-A** Which forms of aggression feed the generic "aggression" concept (big prints, strong delta, diagonal imbalance, stacked imbalance, repeated prints), and how are they combined? (Q3.3 settled that repeats can be analysed singly or as bursts.)

## C. Absorption, exhaustion, delta flip (review order 4)

- ⚠️ **Q4.1** *(partial)* Absorption: v1 requires failed aggression + high resting liquidity (DOM). Open: the numeric definitions. Candidate from the master: bar |delta| ≥ X × recent average and the close/extreme contradicts the delta sign. X, the "high liquidity" threshold, proximity?
- ⚠️ **Q4.7** *(new)* **Exhaustion has no definition at all.** Candidate: max row volume (or big-print size) at the extreme shrinks over k bars while price still makes the extreme. Accept, change, or defer? (M4 depends on it.)
- ⚠️ **Q4.4** *(partial)* Which delta measure for which situation (bar delta vs price-level delta)? Cumulative delta stays out of absorption confirmation.
- ⚠️ **Q4.5** *(partial)* Exact delta-flip definition: candidate = sign change of rolling N-bar delta while price fails to make a new extreme in the old direction. N? Bar or price-level basis? (Windowed "Delta Profile" in the course: 6 and 12 five-minute bars.)
- ⚠️ **Q4.5-A** Do we need all four reads (absorption, failed aggression, exhaustion, delta flip) or do 1–2 cover most entries? Related to R4 (double-counting): which reads are independent evidence?

## D. Location and LVN (review order 5)

- ⚠️ **Q5.2** *(exact definitions open)* Profile definitions: what is the weekly / daily / session profile in code terms (anchor, reset, which session start in which timezone)? How do overlapping levels from different profiles combine or rank?
- ⚠️ **Q5.3** *(formulas open)* LVN qualification: exact depth threshold (volume below x % of profile mean), minimum width, distance reference for relevance, cutoff.
- ⚠️ **Q5.5** *(partial)* VAH/VAL: tradable, entry/confirmation rules deferred. Do you want a minimal first rule, or none until data exists?
- ⚠️ **Q5.5-A** *(new; not in v1 log)* Profile-shape regime (D/P/b/B) as a filter or location? How is the shape detected automatically? M7 depends on this.
- ⚠️ **Q5.6** *(partial)* VWAP: which conditions make it a counter-trade location (POC overlap + idea in same direction) and how does it enter target logic?
- ⚠️ **Q5.8** *(partial)* Session extremes: under what conditions do they become tradable?
- ⚠️ **Q5.7-A** *(from master Q5.7 + additions Q5.9/5.10; R5)* What exactly is a **touch**? Options: price enters the LVN range; reaches its boundary; comes within n ticks; other. Do touch, absorption proximity and aggression proximity share a tolerance? Q3.4 made proximity configurable in ticks and range; the touch event and whether it shares those values remain. No baseline value proposed.
- ⚠️ **Q5.8-A** How is LVN strength ranked (width, depth, relative volume, surrounding HVN, spacing, combined)? *My leaning (not decided):* start with depth relative to the profile mean, test width second, avoid a weighted score until there is enough data (7 days of ticks invites overfitting).
- ⚠️ **Q5.9** *(new; R7)* Time basis: all sessions or RTH only? Authoritative timezone (IST vs exchange CT), daylight saving, exchange calendar, holidays. Course times (London 12:30 IST, Asia 05:30 IST, NY 18:30–22:30 IST) must not become silent constants.

## E. Entry models (review order 6)

v1 includes M1–M7 (Q0.1) and says details are subject to dedicated specifications. Each needs, once the primitives above are settled:
- ⚠️ **Q6.48** *(new; R1, R3, X4)* For each of M1–M7: exact conditions, mandatory conditions (Q8.2), what earns −2/−1/0/+1/+2, and the **model-specific invalidation condition**. Suggested to do M1 and M3 first (same skeleton), M2, then M4–M7.
- ⚠️ **Q6.49** *(new)* Is any model's result a prerequisite or veto rather than an input to the aggregate? v1 (Q6.5) says none overrides another unless a veto rule is established later.
- ⚠️ **Q6.50** *(new)* Models M4/M5/M7 reference reads (exhaustion, delta flip, shape) that are undefined (X2). Keep them in V1 while logging NO_SIGNAL, or defer them until the reads are defined?

## F. Trigger timing and lifecycle (review order 7)

- ⚠️ **Q7.1** *(residual)* Intrabar trigger: exact conditions and event timing. (The course sometimes enters with ~28 s left or before a zero prints.)
- ⚠️ **Q7.2** *(residual)* Retest-entry definition; which order type for which model; limit-order expiry/cancellation; price protection.
- ⚠️ **Q7.3** *(residual)* Expiry schema (time vs price range), and what happens to submitted/outstanding orders when an idea is invalidated or expires.
- ⚠️ **Q-L1** *(X1)* What activates a setup, per location type? Or is activation dropped in favour of "models update continuously until Go" (Q8.3)?
- ⚠️ **Q-L2** *(X1)* What turns an idea entry-ready? Is that exactly the aggregate Go (Q8.1)?
- ⚠️ **Q-L3** *(partly settled)* v1 says the strategy generates the idea and order-flow evaluates it; the exact handoff point to execution/position management (order submitted? filled?) is open together with R2.
- ⚠️ **Q-L4** If several LVNs are ranked before activation and price touches a lower-ranked one, what happens? Lock at touch, any qualifying LVN activates, or several active at once?
- ⚠️ **Q-L6** Invalidation: Q7.3 requires every idea to supply one; R3 says it must be model-specific. Which events, per model? (Merges into Q6.48.)
- ⚠️ **Q-L7** Reset and re-arming of the same location after fill / stop / target. Merges with Q10.4.
- ⚠️ **Q8.6 / R2** Which events are tracked separately: order submitted, partially filled, fully filled, position active? Which one ends the idea and starts position management?
- ⚠️ **Q8.1** *(residual)* Aggregation: the combination formula, weights, the Go threshold, and how correlated evidence is handled (R4).
- ⚠️ **Q8.2** *(residual)* The actual mandatory conditions per model (e.g. M1 close beyond POC, M4 stacked imbalance).

## G. Stops, targets, management, filters (review order 8)

- ⚠️ **Q9.1–9.2** Stop: reference (LVN edge, zone edge, trigger-bar extreme, swing), buffer (ticks, fraction of zone, ATR), candidate ranking and conflict resolution.
- ⚠️ **Q9.3** Max single-trade loss and fixed R:R values the SL module uses; what the risk module does when the structural stop breaches it (skip vs other reference).
- ⚠️ **Q9.4** Time-exit durations, timer start, exit behaviour.
- ⚠️ **Q10.1–10.2** Target: candidate set (VWAP, POC, opposite VA edge, next HVN, fixed R), ranking, minimum R:R (course: ≥ 1:2), what if the nearest structure is closer than the minimum, and the "POC/HVN between entry and target = no trade" rule.
- ⚠️ **Q10.3** Partials and breakeven: logic and thresholds (note the 1-contract cap prevents partials today).
- ⚠️ **Q10.4** Re-entry conditions and attempt limits per exit type (course: usually one planned entry per zone per day).
- ⚠️ **Q11.1** Which no-trade filters are hard filters in V1 (course list: no location, POC/HVN in the way, chasing, bar closes short of POC, P-shape longs off trend days)?
- ⚠️ **Q11.2** Chase-risk: momentum-strength formula, weights, windows, thresholds.
- ⚠️ **Q11.3** Blackout windows, calendars/data sources, exceptions.
- ⚠️ **Q11.4** Trade-count limits and post-loss cooldown: counting semantics, reset boundaries, triggers, durations, interaction with re-entry.
- ⚠️ **Q6.51** *(new)* Position-management policy (v1 settled only the architecture and defaults: no add, no auto-reverse): what the module does with opposing ideas, ignored ideas, and outstanding orders in V1.

## H. Liquidity heatmap, sweep (review order 9)

- ⚠️ **Q12.2** *(thresholds)* Wall detector: size thresholds (absolute and relative), persistence window, replenishment rule, proximity, stacking/pulling, normalisation and any combined score.
- ⚠️ **Q12.3-A** Is liquidity **sweep** in V1? (v1 puts heatmap in V1 but didn't answer sweep specifically; it needs DOM history.)
- ⚠️ **Q12.4** If yes: what is a sweep (required wall size, persistence, amount consumed, price must trade through, reversal required) and is it a location, a confirmation or both? Keep **sweep** (wall consumed, then reversal) and **gap** (wall holds, absorbs, reverses) separate.
- *Deferred by decision:* gamma/GEX until a reliable source exists.

## I. Validation (review order 10)

- ⚠️ **Q13.1** Acceptance criteria values: minimum sample count, R:R, win rate, expectancy, drawdown, market-condition coverage (you decide the promotion; these only inform it).
- ⚠️ **Q13.2** Logging schema, outcome horizons, retention, storage. (Mandatory logging itself is settled.)
- ⚠️ **Q13.3 / R8** Historical Gold tick/footprint data: vendor, coverage, format, cost, DOM fidelity. Current state is recorded accurately in v1: no historical data now, researching.
- ⚠️ **Q6.38 / Q6.40** Horizon and assumptions for hypothetical outcomes; metric definitions and windows for per-score analysis.

---

## Suggested order to answer

1. **B** and **C** first (primitives): Q3.2, Q4.1, Q4.7, Q4.5, Q2.4–2.7, Q3.1, Q3.5-A, Q4.5-A.
2. **D** (locations): Q5.7-A, Q5.8-A, Q5.3, Q5.5-A, Q5.9.
3. **E** (model specs, Q6.48–6.50) once B–D are set.
4. **F**, then **G**, **H**, **I**.
Questions that don't block anything and can wait for data: Q4.1 numbers, Q5.3 numbers, Q11.2, Q12.2, Q13.1.

## Decision table

| ID group | Topic | Status |
|---|---|---|
| Q1.4 | Starting bar sizes | OPEN |
| Q2.4–2.7 | Finished auction, stacked imbalance, initial values | OPEN |
| Q3.1, Q3.2, Q3.5-A | Big-trade adaptation, aggression success/failure, forms of aggression | OPEN |
| Q4.1, Q4.4, Q4.5, Q4.5-A, Q4.7 | Absorption, delta measure, delta flip, need for all reads, exhaustion | OPEN |
| Q5.2, Q5.3, Q5.5, Q5.5-A, Q5.6, Q5.8, Q5.7-A, Q5.8-A, Q5.9 | Profiles, LVN, VA edges, shape, VWAP, extremes, touch, ranking, time basis | OPEN |
| Q6.48–6.50 | Model specs, veto/prerequisite, undefined-read models | OPEN |
| Q7.1–7.3, Q-L1–L7, Q8.1, Q8.2, Q8.6 | Timing, lifecycle, aggregation residuals, execution event | OPEN |
| Q9.x, Q10.x, Q11.x, Q6.51 | Stop, target, filters, position-management policy | OPEN |
| Q12.2, Q12.3-A, Q12.4 | Wall detector, sweep | OPEN |
| Q13.x, Q6.38, Q6.40 | Validation details, data | OPEN |
