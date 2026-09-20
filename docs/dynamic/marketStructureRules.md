# Market Structure Rules — distilled for review

Source: the user's own trend/pullback/zone system, provided   as three
iterative Pine Script prompt drafts (2026-09-18), refined across drafts
until the final one added CHOCH initialization and DT/DB extension
direction. **This file distills the final, most complete version only**
— earlier drafts' inconsistent color scheme and missing initialization
logic are superseded, not preserved here as alternatives. This is a pure
rules distillation for review, no FLOW_V2 architecture/implementation
decisions are made in this file — those come after review, per the
user's explicit instruction.

Written price-action-first, deliberately not yet translated into this
project's own vocabulary (integer tick offsets D-21, `MarketState`,
`Feature`, event types, etc.) — that translation is next-step work once
this rule set itself is confirmed correct.

Flagged **⚠️ AMBIGUOUS** wherever the source text under-specifies
something an implementation would need to pin down exactly. These are
not filled in with a guessed answer — they're listed for the user to
resolve during review.

**Review status (2026-09-20): all 10 points — the original 7 plus 3 more
that surfaced along the way — are now reviewed and answered directly by
the user**, across three rounds: `C:\Users\MSI\Downloads\
market_structure_review.md` (points 1-7), `market_structure_new_review.md`
(points 8-9), and a direct chat answer (point 10). None of these files
are themselves checked into the repo — referenced here for the trail.
Each answered point is marked **REVIEWED**/**RESOLVED** below with the
resolution folded directly into the rule text; no ⚠️ blocks remain
unresolved in this document. Five of the ten answers **contradict** what
`marketStructureRulesTemp.md` guessed and what
`flow-core/src/com/flow/flow/MarketStructureFeature.java` (D-60) actually
implements today (points 1, 3, 4, 5, 6 below) — that file is now known
to disagree with the reviewed rules in concrete, specific ways, not just
philosophically; see the summary at the end of this document for exactly
what needs to change where. Point 9 also introduces a "tradeable levels"
concept with no representation at all in `MarketStructureView`'s
interface yet — a real gap beyond the 5 value-computation fixes.
`marketStructureRulesTemp.md` itself is superseded by this review and
should be treated as historical record only from here on.

---

## 1. Trend state

One persistent state machine, updated candle-by-candle (only on candle
**close** — no intrabar/repainting signals anywhere in this system):

- `trend` ∈ {Uptrend, Downtrend}. **Default at start: Uptrend.**
- `lastTJL1`, `lastTJL2` — the two most recently formed structural zones.
- `pullbackState` ∈ {none, forming, valid} — see §2.
- `lastAPlus`, `lastSBR_RBS`, `lastDT_DB` — the most recent post-flip
  role assignments (§6).
- `chochPoint` — the fallback reference before any real TJL pair exists
  (§1a).

### 1a. Initialization / CHOCH — REVIEWED 2026-09-20 (points 3 & 7)

- The CHOCH point is set at **initialization time**, not at the first
  candle's close: if initialization happens exactly at a candle's start,
  use that candle's **open**; if initialization happens mid-candle, use
  the **first current price encountered** after initialization (a live
  price, not any OHLC field of a not-yet-closed candle). This replaces
  the original guess (this document had assumed the first candle's
  *close*, following this system's general close-only framing) — the
  actual answer is about *when* the anchor is taken (at init), not which
  OHLC field of an eventually-closing candle.
- The CHOCH point acts as a **stand-in for `lastTJL2`** until the first
  real TJL1/TJL2 pair is formed through the normal cycle (§2→§4) —
  unchanged from the original distillation.
- **Never tradable, at any point in its lifecycle.** The CHOCH point is
  purely a pinned bootstrap anchor so the market-structure loop has
  *something* to reference before real structure exists. It has no
  independent trading significance, must never be interpreted as a
  genuine Change of Character, and a later price return to this exact
  level must **not** generate a trade idea merely because it's
  internally labelled/used as CHOCH — true both while it's still the
  active stand-in and after being superseded.
- Flip-watch (§5) still runs against the CHOCH point exactly like it
  would against a real TJL2 while it's active.
- Once the first real TJL1/TJL2 pair exists, CHOCH is **permanently
  discarded** — never referenced again, cannot become a structural or
  tradable reference later, even across future flips. Confirms this
  document's original inferred reading exactly.

✅ **RESOLVED 2026-09-20 (point 8)**: **historical warm-start candles ARE
included, and count as "the first candle."** If N historical bars are
fed through the state machine before any live event (`decisions.md`
D-64), the CHOCH anchor is that first fed bar's (`H1`'s) **open** — not
skipped, not overridden once live data begins. Reading (a) from the
question above turns out correct. This is fully specified only for the
warm-start case itself (a complete historical bar, so "open" is
unambiguous); it doesn't reopen or override the separate open-vs.
first-live-price branch immediately above for whenever *no* warm-start
bar exists at all (e.g. warm-start configured to 0 bars, or no history
available) — that case still reads as originally stated: open if
initialization lands exactly at a live candle's start, else the first
live price encountered. Both together is this document's best combined
reading, not itself flagged ambiguous, since point 8 only ever talks
about the warm-start scenario specifically.

---

## 2. Pullback identification — REVIEWED 2026-09-20 (points 1 & 2)

A pullback is a temporary move *against* the current trend, built from
**consecutive** candles of the counter-trend color.

**Validation — consecutive-PAIR check, not anchored to the run's first
candle** (a real correction: this document had originally left the N>2
case open and `marketStructureRulesTemp.md` guessed "anchored to the
run's first candle" — the actual answer is sliding pairwise comparison):

- **In Uptrend** (watching for a red-candle pullback): as each new red
  candle closes, compare it against the **immediately preceding** red
  candle in the same run — valid the moment `current close < previous
  low` for **any** consecutive pair, not only the 1st vs. 2nd. For a run
  of 5 red candles, every consecutive pair (R2-vs-R1, R3-vs-R2, R4-vs-R3,
  R5-vs-R4) is checked as it forms; if none of them ever satisfies the
  condition, the pullback stays **not valid**, no matter how long the
  counter-trend run continues.
- **In Downtrend** (watching for a green-candle pullback): symmetric —
  `current close > previous high` for any consecutive pair of green
  candles.
- This generalizes, rather than contradicts, the original 2-candle
  statement of the rule — for exactly 2 candles, "current vs. previous"
  and "2nd vs. 1st" are the same comparison.

States:
- **none** — no counter-trend candle currently running.
- **forming** — one or more counter-trend candles so far, no consecutive
  pair has satisfied the condition yet.
- **valid** — some consecutive pair has satisfied the condition.
- If the run of counter-trend candles breaks (a trend-colored candle
  appears) **before ever validating**, the attempt resets to `none`.

**After validation — the entire run remains the pullback, growing until
it ends** (confirms this document's original inferred reading exactly):
the specific pair that satisfied validation has **no special
significance afterward**. Once valid, every subsequent counter-trend
candle keeps extending the *same* pullback, growing for as long as
counter-trend candles keep appearing, until either trend continuation
(§3) or trend reversal (§5) is confirmed. When it eventually ends, §4's
highest/lowest candle is taken from the **entire run**, from its true
first candle onward — not just the pair that triggered validation.
Symmetric for both trend directions.

---

## 3. Trend continuation confirmation

Only evaluated once a pullback is `valid` (§2).

- **In Uptrend**: find the pullback's own highest **high** (a wick
  extreme, not a body/close value). If a later candle **closes above**
  that high → continuation confirmed, uptrend remains the trend.
- **In Downtrend**: find the pullback's own lowest **low**. If a later
  candle **closes below** that low → continuation confirmed, downtrend
  remains the trend.
- TJL zones (§4) are only generated **after** this confirmation — never
  at the moment a pullback merely becomes `valid`.

---

## 4. TJL zone formation

Triggered immediately after continuation is confirmed (§3), built from
the *same pullback's own candles* (the full run per §2's open question):

- **Highest candle** = the one candle with the highest high in the
  pullback.
- **Lowest candle** = the one candle with the lowest low in the
  pullback. (Generally a *different* candle from the highest one — one
  pullback produces one TJL1/TJL2 pair, one boundary from each extreme.)

**In Uptrend:**
- **TJL1** = zone from the **high** of the highest candle, down to a
  point **1% below the top of that candle's body** (body = open/close
  range, not the wick).
- **TJL2** = zone from the **low** of the lowest candle, up to a point
  **1% above the bottom of that candle's body**.

**In Downtrend** (labels swapped relative to Uptrend):
- **TJL2** = top zone: from the **high** of the highest candle, down 1%
  (same construction as Uptrend's TJL1, relabeled).
- **TJL1** = bottom zone: from the **low** of the lowest candle, up 1%
  (same construction as Uptrend's TJL2, relabeled).

**REVIEWED 2026-09-20 (point 4): 1% of that candle's own body height** —
`abs(close - open)`, regardless of candle color (a red candle's body
height is still `|close-open|`, not signed). Not the wick-to-wick range
and not the price level itself — this corrects
`marketStructureRulesTemp.md`'s guess of "1% of the high-low range,"
which is what `MarketStructureFeature.java` actually computes today
(`rangeOffsetTicks` uses `high-low`, not `|close-open|`). Applies
identically here (TJL1/TJL2) and in §6 (DT/DB). Degenerate case, not
itself ambiguous: a candle with `open == close` (a doji) has a body
height of 0, so its zone collapses to a single point at the wick extreme
— a valid, if narrow, outcome under this formula, not an error case.

**Structural invariant worth naming explicitly** (not stated as such in
the source, but consistent throughout every example given): **TJL2 is
always the boundary on the far side of price from the current trend —
below price in an Uptrend, above price in a Downtrend.** It is
structurally "the level that, if broken, invalidates the trend," which
is exactly why flip-watch (§5) always keys off `lastTJL2` specifically.
**TJL1 is always the near-side boundary**, closer to where the trend's
own resumption happened — which is exactly why it becomes `A+` (a pivot
marker, not an invalidation level) on a flip (§6).

Only one TJL1/TJL2 pair is "current" at a time — each new confirmed
continuation replaces the prior pair as `lastTJL1`/`lastTJL2`, but the
prior pair's *values* are what feed into §6 if a flip happens before a
new pair forms.

---

## 5. Trend flip detection

Runs independently and continuously — **not gated on the current
pullback's own state** (§2). It only watches `lastTJL2` (or the CHOCH
point, §1a, before any real TJL2 exists):

- **Condition**: price crosses `lastTJL2`, **and** 2 consecutive candles
  **close** beyond it (on the far side from the current trend).
- When met → trend flip confirmed, regardless of whether a pullback was
  mid-formation, valid, or `none` at that moment.

---

## 6. Flip consequences — role reassignment — REVIEWED 2026-09-20 (points 5 & 6)

Immediately on a confirmed flip (§5):

**Uptrend → Downtrend:**
- `lastTJL1` → becomes **A+** (a pivot marker, not a zone with its own
  new extension rule — it keeps TJL1's own zone geometry, just
  relabeled).
- `lastTJL2` → becomes **SBR** ("Support Becomes Resistance").
- **DT** ("Double Top") is computed from the highest point in a specific
  window (below). Zone construction: from that highest point, extending
  up to 1% (of that candle's own body height, §4) above that candle's
  body top — the opposite extension direction from Uptrend's own TJL1
  (which extends *down* from a high).
- **DT becomes the structural reference flip-watch (§5) keys off of
  next, replacing SBR in that role**, for as long as no new TJL1/TJL2
  pair forms. The confirmation mechanism itself is unchanged (§5's
  "price crosses the reference, 2 consecutive closes beyond it") — only
  *which level* is being watched changes, from `lastTJL2` to this DT.
  This corrects `marketStructureRulesTemp.md`'s guess (that SBR itself
  keeps playing `lastTJL2`'s role, unchanged) and what
  `MarketStructureFeature.java` implements today.

**Downtrend → Uptrend:** symmetric — `lastTJL1`→A+, `lastTJL2`→RBS,
lowest point in the window → **DB** ("Double Bottom"), extending down 1%
below that candle's body bottom, and **DB becomes the next structural
reference**.

**Window for the DT/DB search**: starts at the **open time of the
SBR/RBS candle** — i.e. the candle that produced the OLD `lastTJL2`, not
A+'s own candle. This corrects this document's own original inferred
window ("from the A+ candle forward"), which is also what
`MarketStructureFeature.java`'s `tjl1AnchorBar`/`barsSincePair` mechanism
implements today — A+ and SBR/RBS are usually different candles (one
pullback's high candle vs. its low candle, §4), so this is a real,
different anchor point, not a rewording. The window ends at the
**flip-confirmation time**.

If another flip happens before a new TJL pair forms, the **same
mechanism continues recursively**: e.g. `CHOCH1 → DT1 → CHOCH2 → DB1`,
where the window for `DB1` starts at **`DT1`'s own candle's open time**
(not the original SBR/RBS candle), through `CHOCH2`'s confirmation. This
chains across any number of flips until a real new TJL1/TJL2 pair
finally forms, at which point normal TJL-based role assignment resumes.

After reassignment: `trend` flips to the new direction, and pullback
detection (§2) resets and starts watching for the new trend's own
counter-trend color.

✅ **RESOLVED 2026-09-20 (point 9), and more precisely than either
guessed reading above**: A+/SBR-RBS neither stay frozen forever nor get
reassigned every link — they're tradeable for exactly **one** flip
(the chain's first) and then **permanently drop out** once the chain's
second flip happens, at which point the tradeable set becomes, and
stays, **whichever DT/DB pair currently exists**, refreshing one side at
a time:

- **Chain's 1st flip (`CHOCH1`)** — the flip out of *normal* TJL-based
  structure. Tradeable set: **A+ + SBR/RBS + DT/DB** (three levels, the
  only time A+/SBR-RBS are ever tradeable in this chain). The freshly
  computed DT/DB is *also* the reference for the next flip (§5/§6,
  already established).
- **Chain's 2nd flip (`CHOCH2`)**, triggered by that DT/DB being crossed
  and confirmed — **A+ and SBR/RBS drop out of the tradeable set for
  good.** A fresh level of the *other* kind is computed (DB if the chain
  started Up→Down, DT if it started Down→Up), using the window rule
  already established (§6: anchored to the just-crossed level's own
  candle open). Tradeable set becomes exactly **{DT, DB}** — the
  previous flip's DT/DB (untouched) plus the freshly computed one.
- **Every flip after that (`CHOCH3`, `CHOCH4`, ...)** — same pattern:
  whichever of DT/DB was just crossed gets replaced by a freshly
  computed one of the same kind; the other one (not crossed) carries
  over untouched. Tradeable set is **always exactly {DT, DB}** from the
  chain's 2nd flip onward, direction alternating with the newly
  confirmed trend (short-side levels after an Up→Down flip, long-side
  after Down→Up).
- A real new TJL1/TJL2 pair forming ends the chain at any point and
  resumes normal TJL-based structure — this can happen after any flip
  in the chain, not just the first.

✅ **RESOLVED 2026-09-20 (point 10)**: reading (a) above is correct, and
stated even more strongly than the option itself — **the moment a new
valid TJL1/TJL2 pair is generated (at continuation-confirmed, §3/§4, not
waiting for the next flip), every previously-tradeable DT/DB level
becomes non-tradeable immediately.** The fresh TJL1/TJL2 pair **fully
replaces** them as the tradeable set, and flip-watch's reference (§5)
switches back to the fresh `lastTJL2` at that same moment — confirming
what point 5's "for as long as no new TJL1/TJL2 pair forms" wording
already implied for the reference specifically, and now stated
explicitly for the *tradeable-levels* side too. **From this moment
forward, only the latest TJL1/TJL2 pair is tradeable** — same as normal
(non-chained) operation. No residual ambiguity here.

---

## 7. Role transition summary

| Previous element | New role | Direction | Description |
|---|---|---|---|
| `lastTJL1` | **A+** | either | Last pivot of the trend that just ended |
| `lastTJL2` | **SBR** | Up→Down | Support Becomes Resistance |
| `lastTJL2` | **RBS** | Down→Up | Resistance Becomes Support |
| Highest point in the SBR-candle-open→confirmation window | **DT** | Up→Down | Double Top — §6 REVIEWED |
| Lowest point in the RBS-candle-open→confirmation window | **DB** | Down→Up | Double Bottom — §6 REVIEWED |

**Not just a label change**: per §6's review, DT/DB don't just sit
alongside A+/SBR-RBS — DT/DB **replace `lastTJL2` as flip-watch's actual
reference** for the next flip, chaining across any number of
pair-less flips until a real new TJL1/TJL2 pair forms again.

---

## 8. Visualization reference (for later, not core logic)

From the final draft's color scheme (supersedes the first draft's
inconsistent per-trend coloring):

| Element | Color |
|---|---|
| TJL1 | Blue |
| TJL2 | Orange |
| A+ | Purple |
| SBR / RBS | Gray |
| DT / DB | Yellow |

Zones are meant to be shaded regions (filled between two price levels),
not single lines — matches how every zone in §4/§6 is defined as a
*range*, not a point.

---

## 9. Non-repainting principle

Every signal in this system — pullback validity, continuation, flip —
only confirms on a candle's **close**, never intrabar. Nothing here
should be evaluated against a still-forming candle's current price.
This is a Pine-Script-specific framing in the source, but the underlying
principle translates directly: this project's own `Trigger.BarClose`-vs-
event-level distinction (D-16) will need an explicit call for which
parts of this system, if any, should ever run at tick/event level versus
strictly bar-close — not addressed in the source at all, since Pine
Script itself only reasons in bars.

---

## Open questions — status as of 2026-09-20 (all 10 points now closed)

All 7 originally-flagged points, plus all 3 more raised along the way
(points 8, 9, 10), are now answered directly by the user. Nothing left
open in this document.

**Resolved:**

1. ✅ CHOCH point — **not** an OHLC field of the first candle at all; set
   at initialization time from that candle's open (if init is exactly at
   a candle start) or the first live price seen (if mid-candle). §1a.
2. ✅ Pullback validation with 3+ candles — **sliding consecutive-pair**
   check (current vs. immediately preceding), not anchored to the run's
   first candle. §2.
3. ✅ Does a `valid` pullback's range grow until continuation — **yes**,
   confirmed exactly as this document's original inferred reading. §2.
4. ✅ "1% of what" — **the candle's own body height** (`|close-open|`),
   not the high-low range this document originally guessed. §4/§6.
5. ✅ Flip-watch's reference before a new TJL pair forms — **DT/DB**, not
   SBR/RBS as this document's original analogy-based guess assumed. §6.
6. ✅ "Highest/lowest point after A+" window — starts at the **SBR/RBS
   candle's own open time** (not A+'s candle as originally guessed),
   through flip-confirmation, chaining forward from the prior DT/DB's
   own candle on subsequent pair-less flips. §6.
7. ✅ Does CHOCH ever get referenced again after the first real pair
   forms — **no, permanently discarded**, confirmed exactly as this
   document's original inferred reading. §1a.

**Resolved by the second round (`market_structure_new_review.md`):**

8. ✅ CHOCH-vs-warm-start (my own point 8) — **warm-start bars count**;
   the CHOCH anchor is the first *processed* candle's open, historical
   or live. See §1a.
9. ✅ Chained-flip A+/SBR-RBS fate (my own point 9) — **tradeable for
   exactly the chain's first flip, then permanently drop out**; from the
   chain's second flip onward the tradeable set is always exactly
   `{DT, DB}`, refreshing whichever one was just crossed. See §6.

**Resolved by the third round:**

10. ✅ Once a real new TJL1/TJL2 pair forms — **every previously
    tradeable DT/DB level becomes non-tradeable immediately** (at
    formation, not waiting for the next flip), fully replaced by the
    fresh pair; flip-watch's reference switches back to the fresh
    `lastTJL2` at the same moment. From then on, only the latest
    TJL1/TJL2 pair is tradeable — same as normal, non-chained operation.
    See §6.

**Implementation consequence, stated plainly**: 5 of the 7 original
points (1, 4, 5, 6, and the validation half of 2) **contradict** what
`marketStructureRulesTemp.md` guessed and what
`MarketStructureFeature.java` (D-60) currently implements — not a
philosophical gap, an actual behavioral rewrite. Point 9 additionally
introduces a **new concept, "tradeable levels," that doesn't exist
anywhere in `MarketStructureView`'s interface yet** (today's interface
only exposes the raw A+/SBR-RBS/DT-DB zone values, with no notion of
which of them a strategy should currently treat as tradeable, nor the
one-flip-then-drop-out lifecycle point 9 describes) — a real gap beyond
the 5 already-flagged behavioral fixes, not just a value-computation
correction. None of this has been picked up yet; sequencing is the
user's call.
