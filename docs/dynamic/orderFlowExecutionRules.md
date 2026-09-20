# Order-flow execution rules — distilled for review

Source: **not** an external spec (unlike `marketStructureRules.md`, which
distilled the user's own Pine Script drafts). This time there was no
source text to distill *from* — the rules below exist only as already-
written Java code, built directly by an assistant this session with, per
that code's own class-level javadoc
(`flow-core/src/com/flow/strategies/MarketStructureLvnReversalStrategy.java`),
"every remaining ambiguity resolved by best judgment." So this document
distills the code's **actual current behavior** back into prose rules —
the "spec" here is what the code does today, not what an external draft
said.

This is prep work only, for a review the user has explicitly said they
are not picking up yet. Same convention as `marketStructureRules.md`'s
original pre-review form: flagged **⚠️ AMBIGUOUS** wherever a real design
choice was made — a constant, a threshold, a combination rule — without
ever being explicitly confirmed by the user (as opposed to `
marketStructureRules.md`'s flags, which marked places the *source text*
under-specified something). Nothing here is guessed or fixed; each flagged
point is phrased as "this is what the code currently does — confirm this
is actually wanted," left for the user to answer whenever they pick this
up. No new rules are invented and no fixes are proposed — this is a
distillation of what exists today, not a redesign.

Everything here concerns the `MarketStructureLvnReversalStrategy` — D-62's
"first real strategy," explicitly **not** expected to be correct or
profitable yet (the user's own framing, 2026-09-18: "i dont need to test
the strategy itself, i want to see if the system can hold everything
together"). That framing matters for how to read every ⚠️ below: these
aren't bugs, they're placeholder judgment calls made to get an end-to-end
system working, now due for a real review.

---

## 1. Area of interest — brief cross-reference, not this document's territory

The strategy's area of interest is `MarketStructureView.lastTjl2()` in the
current trend (`MarketStructureLvnReversalStrategy` class javadoc, point
1; D-62). This reuses TJL2 as both "pullback zone" and "post-flip retest
zone" for free, since `marketStructureRules.md`'s own named invariant
already makes TJL2 the far-side/invalidation boundary. Expiration is
implicit, not a timer (point 2): the tracked zone is just "whatever
`lastTjl2` currently is," and the moment that value changes (new pair, or
a flip) the old one retires automatically and in-progress tracking
(the LVN search, the `SweepEvaluator`'s memory) resets.

As of the D-74 rework (`search()`, `MarketStructureLvnReversalStrategy.java`
lines 193–211), the strategy also gates on `ms.tradeableLevels()` containing
`TJL2`, `DT`, or `DB` before treating `lastTjl2()` as a real area of
interest — this keeps the strategy from ever trading the CHOCH bootstrap
placeholder (`marketStructureRules.md` §1a), which `lastTjl2()` can
transiently hold. The strategy deliberately does **not** expand to trade
off `A_PLUS`/`SBR_RBS` during a chain's first flip even though those are
tradeable per `marketStructureRules.md` §6 point 9 — this strategy's
area-of-interest concept stays single-zone by its own D-62 design; using
the other tradeable levels is future-strategy work (comment at
`MarketStructureLvnReversalStrategy.java` lines 200–206).

This is "which zone to trade from," not "how to execute" — it's really
market structure's own territory (`marketStructureRules.md` §4/§6), noted
here only for context. Not re-distilled in full; see that document.

---

## 2. Biggest LVN inside the area of interest

Recomputed fresh from `VolumeProfileView.zones()` on every wake (every
tick — see §7), filtered to `Kind.LVN`, filtered to zones overlapping the
area of interest's range, and the **widest** one (by tick range) wins
(`search()`, `MarketStructureLvnReversalStrategy.java` lines 225–234; D-62
point 3). Not tracked by a persistent zone id across wakes — if the
"biggest" LVN's identity changes tick to tick, that's accepted as-is for
this pass, not treated as a bug (D-62's own text).

**No LVN in the area of interest → no trade**, exactly as stated (D-62
point 4, `search()` line 235–237).

⚠️ **AMBIGUOUS — "biggest" = widest tick range, not highest volume.** The
code picks the LVN zone with the largest `highPriceTicks() -
lowPriceTicks()`, which is a *width* comparison. Nothing compares actual
traded volume between candidate LVN zones. For a "low volume node," width
and volume-thinness aren't necessarily the same ranking — confirm this is
the intended definition of "biggest."

⚠️ **AMBIGUOUS — recomputing fresh every tick with no identity tracking.**
Explicitly flagged as accepted-for-now in D-62's own text, not reviewed:
if the widest LVN's identity flips between two different zones tick to
tick (e.g. two LVNs of near-identical width), the strategy's target LVN
can jump around without any of that being visible in state. Confirm
whether this instability is acceptable or whether zone identity should be
sticky once picked.

---

## 3. Touch tolerance — `TOUCH_TOLERANCE_TICKS`

`TOUCH_TOLERANCE_TICKS = 2` (`MarketStructureLvnReversalStrategy.java`
line 86). Used in two places:

- **Touch detection**: price counts as "touching" the biggest LVN if it's
  within `TOUCH_TOLERANCE_TICKS` of either edge of the LVN's own range —
  `price >= biggestLvn.lowPriceTicks() - TOUCH_TOLERANCE_TICKS && price <=
  biggestLvn.highPriceTicks() + TOUCH_TOLERANCE_TICKS` (`search()` lines
  239–243; D-62 point 5, "Touch = price within a few ticks of the LVN's
  own range").
- **Reused, not re-derived, as the proximity tolerance for both
  Absorption and Aggression confirmation** — passed straight through as
  `toleranceTicks` to `AbsorptionEvaluator.absorptionAt()` and
  `AggressionEvaluator.aggressionAt()` (`search()` lines 246–248). Sweep
  detection doesn't take a tolerance parameter at all — it reads size
  directly at `price` (see §6).

⚠️ **AMBIGUOUS — `TOUCH_TOLERANCE_TICKS = 2` is a bare constant, not a
reviewed value.** No rationale is given anywhere in the code or D-62 for
why 2 ticks specifically (vs. 1, 3, or something instrument/volatility-
scaled). Confirm this is the intended tolerance, and whether it should be
a fixed tick count at all or scale with something like average spread or
ATR.

⚠️ **AMBIGUOUS — one tolerance value reused for touch, absorption, and
aggression proximity.** These are three conceptually different
questions ("is price near the LVN," "is footprint volume concentrated
near this exact price," "was a big trade near this exact price") sharing
one constant by convenience, not by any stated reasoning that they should
all use the same tick distance. Confirm whether they should be
independently tunable.

---

## 4. Absorption (footprint-based)

Implemented in `flow-core/src/com/flow/flow/AbsorptionEvaluator.java`.
Stateless — a pure function of the current footprint bar
(`footprint.current().rows()`).

What counts as absorption at a price (`absorptionAt(footprint, priceTicks,
toleranceTicks)`):

- For every footprint row, combined volume = `askVolume() + bidVolume()`.
- Rows within `toleranceTicks` of the target price (absolute tick
  distance) are the "target" group; their max combined volume is
  `targetVolume`. All other rows' max combined volume is `maxOtherVolume`.
- Absorption fires when a target row was found **and** `targetVolume >=
  maxOtherVolume * CONCENTRATION_MULTIPLE`, where `CONCENTRATION_MULTIPLE
  = 1.5` (line 20, the file's own comment: "arbitrary v1 threshold").

In prose: absorption means the row at (or within tolerance of) the
touched price shows at least 1.5× the combined bid+ask volume of the
single busiest row anywhere else in the same footprint bar — concentrated
activity at exactly this level.

The class's own javadoc (lines 6–18) is explicit that this is a **v1
proxy, not a rigorous definition**: a genuine "aggressive selling
absorbed without price giving way" signal needs multi-bar price-outcome
tracking this evaluator doesn't do. It calls out its own limitation and
says to "revisit with real price-outcome tracking if this proxy turns out
too loose in practice" — this is the evaluator's own stated caveat, not
this document inventing one.

⚠️ **AMBIGUOUS — `CONCENTRATION_MULTIPLE = 1.5` is an arbitrary v1
threshold**, by the code's own comment (line 20). No price-outcome
tracking backs this up at all — the evaluator only looks at volume
concentration in the current bar, never whether price actually held after
that volume traded. Confirm whether 1.5× is the right bar, and whether
absorption should ever incorporate the price-outcome dimension the
javadoc says is currently missing.

⚠️ **AMBIGUOUS — single-bar, no multi-bar confirmation.** The evaluator
reads only `footprint.current()` — one bar's snapshot. Whether absorption
should require this concentration to persist or repeat across bars is
unaddressed and unconfirmed.

---

## 5. Aggression (big-trades-based)

Implemented in `flow-core/src/com/flow/flow/AggressionEvaluator.java`.
Stateless — a pure function of `BigTradeView`'s current recent window plus
a caller-supplied "now" for recency filtering.

What counts as aggression at a price (`aggressionAt(bigTrades, priceTicks,
toleranceTicks, bullishBias, nowEventTimeMs, recencyMs)`):

- Iterate `bigTrades.recent()`. Skip any trade older than `recencyMs`
  relative to `nowEventTimeMs` (`nowEventTimeMs - t.eventTimeMs() >
  recencyMs`).
- Skip any trade whose price is more than `toleranceTicks` away from the
  target price.
- Fires (returns `true`) the moment one surviving trade's aggressor side
  matches the direction bias: `t.isAskTick() == bullishBias` — i.e. a
  **buy-side aggressor** trade (`isAskTick()`, matching D-54's green/red
  convention) counts as confirmation for a long, a **sell-side aggressor**
  trade counts for a short. Returns `false` if no trade in the window
  satisfies both recency and proximity and direction.

Recency window: called from the strategy with `AGGRESSION_RECENCY_MS =
60_000L` — 60 seconds (`MarketStructureLvnReversalStrategy.java` line
89). No rationale for this exact value is recorded anywhere in the code
or D-62 beyond the evaluator's own general framing ("a big trade from
hours ago at this price isn't a live confirmation" — javadoc lines 6–8).

⚠️ **AMBIGUOUS — `AGGRESSION_RECENCY_MS = 60_000` (60 seconds) is an
unexplained fixed value.** The javadoc only motivates *why* a recency
filter exists at all (an old big trade shouldn't count), not why 60
seconds specifically rather than, say, 10 or 300. Confirm this window is
actually wanted.

⚠️ **AMBIGUOUS — "big trade" threshold itself lives entirely in
`BigTradeView`/its upstream feature, not in this evaluator** — this
document doesn't have that feature's own definition in scope from the
files reviewed, but flags it here since it directly determines how loose
or strict "aggression" ends up being. Worth folding into whatever this
document's threshold review covers, or cross-referencing explicitly once
found.

---

## 6. Sweep (liquidity-map-based)

Implemented in `flow-core/src/com/flow/flow/SweepEvaluator.java`. Unlike
the other two evaluators, this one is **stateful** — detecting a sweep
means detecting *change* (resting size that was there, then wasn't), which
a single point-in-time read of `LiquidityMapView` can't tell on its own
(class javadoc, lines 5–12).

Mechanics:

- One `SweepEvaluator` instance is owned per area-of-interest attempt by
  the strategy (`MarketStructureLvnReversalStrategy` field
  `sweepEvaluator`, line 94).
- `sweepAt(lm, priceTicks, bullishBias)` reads the **near side's** resting
  size at the touched price: bid side for a bullish/long setup (the
  support liquidity price is pressing into), ask side for bearish.
- Compares the current reading to the **previous** reading
  (`previousSize`, a field carried across calls). If a previous reading
  exists, is non-null, and was `> 0`, computes `dropRatio = (previousSize
  - currentSize) / previousSize`. Sweep fires (`swept = true`) if
  `dropRatio >= DROP_RATIO_THRESHOLD`, where `DROP_RATIO_THRESHOLD = 0.5`
  (line 23, the file's own comment: "arbitrary v1 threshold") — i.e.
  resting size at that exact price must have dropped by **at least
  half** since the last check.
- `previousSize` is updated to the current reading every call, regardless
  of whether a sweep fired.

**Reset trigger**: `reset()` clears `previousSize` to `null`. Called by
the strategy in exactly two places: whenever the tracked area of interest
changes (`search()` line 215, "a fresh attempt shouldn't compare against a
stale prior reading from a different price/setup" — javadoc lines 9–12),
and whenever a position closes, either on stop/target exit
(`manageOpenPosition()` line 183) or implicitly by starting a fresh
`SEARCHING` phase. This means the "prior reading" memory only ever spans
one continuous area-of-interest attempt — it is never compared across a
zone change or across a trade.

The javadoc is explicit that this is a **DOM-visible proxy, not a claim
of detecting a real stop-hunt**: "A real stop-order sweep isn't visible in
resting-limit-order DOM data at all (stops aren't resting limit orders)"
(lines 17–20) — the evaluator watches only resting-limit-order size
disappearing, which is what the user's own framing asked for
("liquidity grab/sweep (heatmap)"), not a claim it detects the same thing
a real stop-hunt would.

⚠️ **AMBIGUOUS — `DROP_RATIO_THRESHOLD = 0.5` is an arbitrary v1
threshold** (the file's own comment, line 23). No empirical basis is
recorded for why a 50%+ drop specifically, rather than any other
fraction. Confirm this is the intended sensitivity.

⚠️ **AMBIGUOUS — sweep only compares to the single immediately-prior
reading, not any longer history.** `previousSize` holds exactly one prior
value; there's no rolling average or multi-sample baseline, so a single
noisy tick-to-tick fluctuation in resting size can register as a "sweep"
as easily as a genuine liquidity pull. Confirm whether this single-prior-
sample comparison is sufficient.

⚠️ **AMBIGUOUS — reset-on-position-close means sweep memory never
survives a trade, even if the same AOI/LVN is immediately revisited.**
Whether that's the intended lifecycle (vs., say, letting the memory
persist as long as the AOI itself is unchanged) is unconfirmed.

---

## 7. Combination logic — ANY one of absorption/aggression/sweep

The strategy requires **absorption OR aggression OR sweep** — any single
one, not all three, and not some weighted vote among them (`search()`
line 258: `if (!(absorption || aggression || sweep)) { return
Intent.none(...); }`; D-62 point 5 / class javadoc point 6). The class's
own javadoc states the reasoning directly (lines 60–66): the user's
original phrasing ("...or all of them combined") "was read as 'any of
these, together or alone,' not as requiring unanimous agreement — simpler
to implement and, for a discretionary order-flow read, matches how
multiple independent confirmations are normally treated (any one is
enough), not ANDed together."

All three evaluators are still computed and read every time (`search()`
lines 246–249) even once one has already fired, and all three booleans
are folded into the intent's `reason` string (`search()` lines 276–278:
`"lvn_entry trend=" + ... + " absorption=" + absorption + " aggression="
+ aggression + " sweep=" + sweep + ...`) — so which evaluator(s) actually
fired is visible after the fact even though only one was required.

⚠️ **AMBIGUOUS — ANY-one combination is an explicit interpretive choice
of ambiguous user phrasing, never separately confirmed as the final
answer.** The javadoc is candid that this was a judgment call resolving
genuinely ambiguous wording ("...or all of them combined"), not a
restatement of something already settled. Whether ANY-one is really
wanted long-term — versus AND (all three), a 2-of-3 majority, or a
weighted/scored combination — is exactly the kind of design decision this
document exists to flag for explicit review.

---

## 8. Stop rule — `STOP_BUFFER_TICKS`

`STOP_BUFFER_TICKS = 2` (`MarketStructureLvnReversalStrategy.java` line
87). Stop is placed a small buffer beyond the area of interest's own far
edge — the same edge that, if broken, would flip the trend per
`marketStructureRules.md`'s TJL2 invariant, so "stop" and "structural
invalidation point" are the same idea by construction (D-62 point 6 /
class javadoc point 7):

```
stopTicks = bullish ? aoi.lowTicks() - STOP_BUFFER_TICKS
                     : aoi.highTicks() + STOP_BUFFER_TICKS
```

(`search()` line 263.)

⚠️ **AMBIGUOUS — `STOP_BUFFER_TICKS = 2` is an unreviewed constant.** The
*placement logic* (beyond the AOI's far/invalidation edge) is explicitly
reasoned about in D-62/the javadoc, but the exact buffer size — 2 ticks —
has no stated rationale anywhere. Confirm whether 2 ticks is the intended
buffer, and whether it should be fixed at all versus scaled to something
like spread or volatility.

---

## 9. Target rule — fixed 2:1 reward:risk

`REWARD_RISK_MULTIPLE = 2.0` (`MarketStructureLvnReversalStrategy.java`
line 88):

```
riskTicks = |price - stopTicks|
targetTicks = bullish ? price + round(riskTicks * REWARD_RISK_MULTIPLE)
                       : price - round(riskTicks * REWARD_RISK_MULTIPLE)
```

(`search()` lines 264–267.) D-62's own text calls this "the simplest
defensible choice that needed no extra logic to find 'the next structural
level'" and says outright, in the class javadoc itself (line 74):
**"revisit once this actually gets tested for real."**

⚠️ **AMBIGUOUS — the 2:1 ratio itself, explicitly flagged by its own
author as provisional.** This is not this document inferring an
ambiguity — the code's own javadoc says to revisit it. Confirm whether
2:1 is the intended fixed multiple long-term, or whether target should
instead be structural (next opposing zone/level) as the javadoc's own
phrasing ("needed no extra logic to find 'the next structural level'")
implies was the harder, not-yet-built alternative.

---

## 10. Position management

- **Single position at a time**, tracked via `Phase ∈ {SEARCHING,
  IN_POSITION}` (`MarketStructureLvnReversalStrategy.java` lines 91, 96).
  No pyramiding, no scaling in/out — the strategy either has no position
  and is searching, or has exactly one position and is managing it.
- **`SEARCHING`** runs `search()`: checks area of interest, LVN, touch,
  and entry confirmation (§§1–7 above) every wake. On a qualifying entry,
  it optimistically flips to `IN_POSITION` and computes stop/target
  before the intent is known to be accepted (see rollback note below).
- **`IN_POSITION`** runs `manageOpenPosition()` (lines 175–191): on every
  wake, checks `stopHit` (`price <= stop` for long / `price >= stop` for
  short) and `targetHit` (`price >= target` for long / `price <= target`
  for short). If either is hit, emits a flat/exit intent (`0` size,
  `null` stop/target, reason `"stop_hit"` or `"target_hit"`), resets
  `phase` to `SEARCHING`, clears `trackedAreaOfInterest`, and calls
  `sweepEvaluator.reset()`. If neither is hit, it **re-emits the same
  desired position** every wake as an explicit "still holding" restatement
  (line 189-190 comment: an identical intent is a no-op at
  reconciliation, "not a new decision").
- **Optimistic-mutation rollback**: both the entry path (`search()`) and
  the exit path (`manageOpenPosition()`) mutate `phase`/position fields
  *before* knowing whether `RiskChain` will actually accept the intent,
  then snapshot the pre-mutation state keyed by the intent's `seq`
  (`saveStateFor()`, lines 118–125). `onIntentRejected()` rolls the
  snapshot back if the rejected intent's `seq` matches. This was a real
  correctness bug found and fixed in self-review before ever running live
  (D-62) — not a design choice under review here, just noted for
  completeness since it's load-bearing for how phase transitions actually
  behave.
- **Wake cadence**: the whole strategy runs on `Trigger.EveryTick()`
  (line 157), not `BAR_CLOSE` or a proximity trigger — the class javadoc
  is explicit this is deliberate for this build specifically ("this
  build's whole point is stress-testing the live pipeline at its most
  demanding cadence, not signal quality"), diverging from D-27's own
  stated preference for proximity-triggered wake-ups
  (`PRICE_CROSS`/`BOOK_CHANGE`) as "the primary wake-up mechanism."

⚠️ **AMBIGUOUS — `EveryTick` cadence is stated as intentional for this
stress-test build, but not reconciled with D-27's own architecture
preference for proximity-based wake triggers.** Confirm whether the next
real (signal-quality-focused) iteration of this or a similar strategy
should move to a proximity trigger, per D-27's original reasoning, or
whether `EveryTick` is fine to keep for order-flow-sensitive entries
specifically (which arguably do need every-tick attention once inside an
AOI).

---

## Open questions, collected

1. **§2 — "biggest" LVN = widest tick range, not highest volume.**
   Confirm this ranking is the intended definition of "biggest."
2. **§2 — LVN picked fresh every tick, no persistent identity tracking**,
   accepted as-is by D-62's own text but never reviewed. Confirm the
   resulting tick-to-tick instability is acceptable.
3. **§3 — `TOUCH_TOLERANCE_TICKS = 2`** is a bare, unexplained constant.
   Confirm the value.
4. **§3 — the same tolerance constant is reused for touch detection,
   absorption proximity, and aggression proximity**, three different
   questions sharing one number by convenience. Confirm whether they
   should be independently tunable.
5. **§4 — `CONCENTRATION_MULTIPLE = 1.5`** (Absorption), the code's own
   "arbitrary v1 threshold" comment. Confirm the multiple, and whether
   absorption should incorporate price-outcome tracking as the
   evaluator's own javadoc suggests it eventually should.
6. **§4 — Absorption is single-bar only**, no multi-bar persistence
   requirement. Confirm whether that's sufficient.
7. **§5 — `AGGRESSION_RECENCY_MS = 60_000` (60 seconds)**, unexplained
   fixed value. Confirm the window.
8. **§5 — the underlying "big trade" threshold** (in `BigTradeView`/its
   feature, not reviewed in the files covered by this document) directly
   shapes how loose or strict aggression confirmation is; flagged for
   inclusion in the same review pass or explicit cross-reference once
   located.
9. **§6 — `DROP_RATIO_THRESHOLD = 0.5`** (Sweep), the code's own
   "arbitrary v1 threshold" comment. Confirm the ratio.
10. **§6 — Sweep compares only to the single immediately-prior reading**,
    no rolling baseline. Confirm this is sufficient sensitivity/
    robustness.
11. **§6 — Sweep memory resets on every position close**, never persists
    across a trade even if the same AOI/LVN is immediately revisited.
    Confirm this lifecycle is intended.
12. **§7 — ANY-one-of-three combination (absorption OR aggression OR
    sweep)** is an explicit, candidly-acknowledged interpretive judgment
    call on ambiguous original phrasing, not a confirmed final answer.
    Confirm ANY-one vs. AND vs. a weighted/majority combination.
13. **§8 — `STOP_BUFFER_TICKS = 2`**, unreviewed constant (the
    beyond-the-AOI-edge *placement logic* is reasoned about; the exact
    buffer size is not). Confirm the value.
14. **§9 — `REWARD_RISK_MULTIPLE = 2.0`**, explicitly flagged by its own
    author's javadoc as provisional ("revisit once this actually gets
    tested for real" — D-62). Confirm whether a fixed 2:1 ratio is wanted
    long-term or whether target should become structural, as the
    javadoc's own phrasing implies was the intended eventual direction.
15. **§10 — `EveryTick` wake cadence** for this strategy, stated as
    intentional for the stress-test build but not reconciled with D-27's
    stated general preference for proximity-triggered wake-ups. Confirm
    whether a future iteration should move to a proximity trigger.

Fifteen points flagged, across all three entry evaluators, the
touch/stop/target constants, the combination rule, and the wake cadence.
None of these are bugs — every one is a deliberate v1 judgment call the
code's own comments and D-62 already admit to making without a real
review behind it. Nothing here has been picked up yet; sequencing is the
user's call, same as `marketStructureRules.md`'s own closing note.
