# Market Structure Rules — distilled for review

Source: the user's own trend/pullback/zone system, provided as three
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

### 1a. Initialization / CHOCH

- On the very first candle the indicator sees, that candle's price is
  recorded as the **CHOCH point** ("Change of Character"). ⚠️
  **AMBIGUOUS**: "first recorded price" isn't specified as open, close,
  high, or low of that first candle.
- The CHOCH point acts as a **stand-in for `lastTJL2`** until the first
  real TJL1/TJL2 pair is formed through the normal cycle (§2→§4). It
  does not change while it's serving this role.
- Flip-watch (§5) runs against the CHOCH point exactly like it would
  against a real TJL2: if price crosses it and 2 consecutive candles
  close beyond it, Downtrend is confirmed and normal TJL formation
  begins from that point on.
- Once the first real TJL1/TJL2 pair exists, CHOCH is permanently
  superseded — `lastTJL2` from then on always means the most recent real
  TJL2, never falls back to CHOCH again. (Not stated explicitly in the
  source; this is the only reading that makes CHOCH a one-time bootstrap
  rather than a recurring fallback. Confirm this reading is correct.)

---

## 2. Pullback identification

A pullback is a temporary move *against* the current trend, built from
**consecutive** candles of the counter-trend color.

- **In Uptrend** (watching for a red-candle pullback):
  - Valid when there are **≥2 consecutive red candles**, AND
  - the **2nd** red candle's **close** is below the **low** of the
    **1st** red candle.
- **In Downtrend** (watching for a green-candle pullback):
  - Valid when there are **≥2 consecutive green candles**, AND
  - the **2nd** green candle's **close** is above the **high** of the
    **1st** green candle.

States:
- **none** — no counter-trend candle currently running.
- **forming** — exactly one counter-trend candle so far (validity can't
  be assessed yet).
- **valid** — the 2-candle rule above has been met.
- Reading: if the run of counter-trend candles breaks (a trend-colored
  candle appears) before validating, the attempt resets to `none`.

⚠️ **AMBIGUOUS**: if a 3rd (or later) consecutive counter-trend candle
appears without the 2nd-vs-1st check having validated, is validity
re-checked using candles 2&3 (sliding window), or 1&3 (anchored to the
run's first candle), or does the run simply keep extending under
`forming` until *some* adjacent pair in it satisfies the rule? The
source only describes the 2-candle case explicitly.

⚠️ **AMBIGUOUS**: once a pullback is `valid`, does it keep *growing*
(more counter-trend candles extending it) while continuation (§3) is
awaited, and if so, do §4's "highest/lowest candle of the pullback" span
the *entire* run (from the 1st counter-trend candle through to
whichever candle immediately precedes confirmed continuation), or just
the first 2 candles that validated it? This document assumes **the
entire run** — matches "the highest point (swing high) of the latest
pullback" reading most naturally — but this is inferred, not stated
outright, and should be confirmed.

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

⚠️ **AMBIGUOUS**: "1%" of *what*? Candidate readings: 1% of the price
level itself (e.g., 1% of 4400 ≈ 44 points — implausibly large for a
zone width), 1% of that candle's own body height, 1% of that candle's
full high-low range, or a fixed tick/point amount unrelated to "%"
wording. Needs an explicit definition before this is implementable.

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

## 6. Flip consequences — role reassignment

Immediately on a confirmed flip (§5):

**Uptrend → Downtrend:**
- `lastTJL1` → becomes **A+** (a pivot marker, not a zone with its own
  new extension rule — it keeps TJL1's own zone geometry, just
  relabeled).
- `lastTJL2` → becomes **SBR** ("Support Becomes Resistance").
- The **highest point reached after A+** → becomes **DT** ("Double
  Top"). Zone construction: **from that highest point, extending up to
  1% above that candle's body top** — the opposite extension direction
  from Uptrend's own TJL1 (which extends *down* from a high). ⚠️ Same
  "1% of what" ambiguity as §4 applies here too.

**Downtrend → Uptrend:**
- `lastTJL1` → becomes **A+**.
- `lastTJL2` → becomes **RBS** ("Resistance Becomes Support").
- The **lowest point reached after A+** → becomes **DB** ("Double
  Bottom"). Zone construction: **from that lowest point, extending down
  to 1% below that candle's body bottom** — opposite extension direction
  from Downtrend's own TJL2.

After reassignment: `trend` flips to the new direction, and pullback
detection (§2) resets and starts watching for the new trend's own
counter-trend color.

⚠️ **AMBIGUOUS, not addressed anywhere in the source**: once a flip
happens and produces A+/SBR-RBS/DT-DB, what does flip-watch (§5) key off
of *next*, before the new trend's first real TJL1/TJL2 pair has formed?
By analogy with §1a's CHOCH bootstrap, the natural guess would be that
the new SBR/RBS level plays that same fallback role — but the source
never states this, and it's exactly the same shape of question §1a
answers for the very first flip only. Needs an explicit answer, not an
assumed analogy.

**"Highest/lowest point reached after A+"** — over what window,
exactly? ⚠️ **AMBIGUOUS**: "after A+" could mean from the A+ pivot's own
candle forward through the flip-confirming candles, or from the flip
confirmation moment forward, or something else. Needs pinning down.

---

## 7. Role transition summary

| Previous element | New role | Direction | Description |
|---|---|---|---|
| `lastTJL1` | **A+** | either | Last pivot of the trend that just ended |
| `lastTJL2` | **SBR** | Up→Down | Support Becomes Resistance |
| `lastTJL2` | **RBS** | Down→Up | Resistance Becomes Support |
| Highest point after A+ | **DT** | Up→Down | Double Top |
| Lowest point after A+ | **DB** | Down→Up | Double Bottom |

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

## Open questions, collected (all ⚠️ items above, in one place)

1. CHOCH point: which of the first candle's OHLC values is "the first
   recorded price"?
2. Pullback validation with a 3rd+ consecutive counter-trend candle: is
   the 2-candle check anchored to the run's first candle, sliding, or
   something else?
3. Does a `valid` pullback's own candle range (used for §4's highest/
   lowest candle) grow until continuation, or freeze at the 2 candles
   that validated it? (This document assumes "grows," unconfirmed.)
4. "1% of what" — price level, body height, high-low range, or a fixed
   amount? Applies to TJL1/TJL2 (§4) and DT/DB (§6) identically.
5. After the *first* flip, what does flip-watch key off before the new
   trend's first real TJL1/TJL2 pair forms — does SBR/RBS play the same
   bootstrap role CHOCH played initially? Not stated.
6. "Highest/lowest point after A+" (§6) — over what exact window?
7. Does CHOCH ever get referenced again after the first real TJL pair
   forms (e.g., if the trend flips back), or is it strictly a one-time
   bootstrap value, never revisited?
