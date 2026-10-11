# Order-flow rules — MASTER review file (to be reviewed)

**Built:** 2026-10-10, by merging `orderFlowRulesRedefinition.md` and `orderFlowRules_master_review_additions.md`.
**Status of every question: OPEN.** No decision has been taken. Draft rules are proposals, not decisions.

## How to use this file

- Source for all course claims: `docs/video_transcripts/orderflow course/distilled/distillation_2/entry_model_rules.txt`
  ([E4] = course episode 4). Fuller text in `distillation_1/`.
- **The old placeholder rules (`orderFlowExecutionRules.md` and the coded strategy) are not a reference.** Nothing here is
  compared against them.
- Each section: **Course says** → **Draft** (my reading) → **Open questions** (⚠️ = the course is silent, contradicts itself or is
  discretionary).
- The course is one trader's discretionary method; his accuracy claims are unverified and not used. Gold only (`@GC`).
- When a question is decided: update its section and the decision table, keeping the original question visible.

## Changes made while merging (so you can reject any of them)

| # | Change | Why |
|---|---|---|
| C1 | **Q3.6** (did aggression succeed/fail) merged with old **Q3.2** and **Q4.2** into one question, **Q3.2** | all three asked how to measure "price progress after aggression" |
| C2 | Additions' **Q5.9 / Q5.10** (touch definition, shared tolerance) folded into **Q5.7**; the "provisional shared 2-tick baseline" **dropped** | 2 ticks is the old placeholder value; the course gives no tick number |
| C3 | Lifecycle (additions §E/§F) rewritten **location-agnostic and owner-agnostic**, new §7 | additions assumed LVN-only activation and an "underlying strategy" owning the idea; both are open decisions, not facts |
| C4 | Lifecycle gains **expiry, invalidation, reset** states and the "touch of a lower-ranked LVN" case | missing from the additions |
| C5 | **Q4.6** and **Q5.8** carry my *leaning*, clearly marked as not decided | the user asked for a take; still open |
| C6 | Review order changed (§ "Review order" at the end): model scoping first | additions' order kept, plus a quick scope call up front |
| C7 | Everything else from the additions kept as written (Q3.5, Q4.6, Q5.8, Q-L1–L3, Q8.4–8.6, Q12.3–12.4) | no objection |

---

## 0. Scope call (do this first)

The models in §6 decide which primitives matter. Settling the scope first avoids defining reads no chosen model uses.

- ⚠️ **Q0.1** Which entry models are in version 1? (Candidates in §6. Suggested: M1 and M3, same skeleton; M2 second; M4 later.)
- ⚠️ **Q0.2** Long and short symmetric, or short-biased first? (Most course examples are shorts; longs are the stated mirror.)
- ⚠️ **Q0.3** One strategy that selects among models, or one strategy per model?
- ⚠️ **Q0.4** Is the heatmap/gamma layer (§12) in version 1, deferred or dropped? (Needs depth history and an options vendor.)

---

## 1. Bar / data unit

**Course says.** Order flow is read on 500-tick candles [E3]. Elsewhere: a 5-minute footprint [E4, E5], a 20-range footprint to look
inside a forming 5-minute candle [E5]. Range charts "work poorly for Gold" [E5]. Structure is read on 1h/30m [E3].

**Draft.** One bar type, closed-bar evaluation. Candidate: 500-tick.

- ⚠️ **Q1.1** Which bar: 500-tick, a time bar (3 or 5 min), or range?
- ⚠️ **Q1.2** Fixed 500-tick count, or scaled to activity? (The course notes the evening session prints far more candles per hour.)
- ⚠️ **Q1.3** Which timeframes define "structure" (1h main, 30m refine), and is any structure layer needed at all, or is the volume
  profile the only location source? (Links to Q5.1.)

---

## 2. Footprint primitives

**Course says.**
- Cell: bid volume = aggressive sell, ask volume = aggressive buy. Bar volume = bid+ask; delta = ask−bid [E1, E5].
- **Diagonal imbalance.** Bullish at P: `ask(P) / bid(P−1 tick) × 100 ≥ threshold`. Bearish at P: `bid(P) / ask(P+1 tick) × 100 ≥ threshold` [E5].
  Gold threshold 300 %. 100 % too noisy; 200/400/500 "valid", left to testing [E5, E8].
- **Stacked imbalance:** 3 or more adjacent levels, same direction = aggression sweeping levels [E5]. A single candle printing one triggered the A+ short [E4].
- **Finished auction:** zero on one side at the bar's extreme. At the high = buyers exhausted, price tends to fall; at the low = sellers
  exhausted. **Unfinished** (no zero) = price tends to revisit that extreme [E4, E5]. Only meaningful at a location; not mandatory [E5].
- Row grouping: Gold more than 5 ticks per row (6/7/10). Smaller rows fragment data and create false zeros [E5, E8].

**Draft.** Diagonal imbalance, 300 % to start. Stacked = 3 or more consecutive levels. Finished auction = zero (or ≤ n lots) on the extreme row.

- ⚠️ **Q2.1** Imbalance threshold: start at 300 %, or sweep 200/300/400/500 in testing?
- ⚠️ **Q2.2** Row grouping: one value, or a test parameter? (It changes what counts as a zero.)
- ⚠️ **Q2.3** Minimum volume for an imbalance to count (the ratio is true for 1 vs 0 or 3 vs 1, which is noise)? The course doesn't say.
- ⚠️ **Q2.4** Finished auction: strictly zero, or ≤ 1–2 lots? Does it need a minimum opposite-side volume?
- ⚠️ **Q2.5** Is finished/unfinished auction a trigger, a confirmation, or only a context flag?

---

## 3. Aggression and big trades

**Course says.**
- Aggression appears in several forms: big-trade bubbles, strong delta, diagonal imbalances, stacked imbalances, repeated prints [E1, E3, E5].
- A bubble = one print ≥ threshold. 1–2 lots are retail; 30/40/50/100 are larger players. Gold thresholds: 30 default, 40–50 "institutional",
  10–20 to see smaller players; raise when volume is high, lower when low [E1, E3, E4].
- The same print can mean opposite things: aggression that **wins** (big buy at demand, bar closes up, sellers trapped [E3]) vs aggression that
  **fails** (big buys at supply/VAH, price won't advance, buyers absorbed → short [E1, E3, E5]). The difference is the price outcome afterwards.

**Draft.** Aggression is a context input, not a standalone trigger. Its meaning is judged by what price does after it.

- ⚠️ **Q3.1** Big-trade threshold: fixed (30? 40?) or adaptive to recent volume?
- ⚠️ **Q3.2** *(merged: old Q3.2 + Q3.6 + Q4.2)* **How is "aggression succeeded / failed" measured?** After aggression, what is sufficient price progress, or
  failure? Options: N ticks; a fraction of the zone width or ATR; movement within a time window; movement by the end of the bar; another definition.
  This one definition also decides "price did not move" in absorption (§4).
- ⚠️ **Q3.3** Do repeated big prints on the same side (two back-to-back ~40s, or a 50 then a 98) count more than one print?
- ⚠️ **Q3.4** How close to the zone must the print be to count? (Joins Q5.7.)
- ⚠️ **Q3.5** *(from additions)* **What counts as meaningful aggression?** Which of big prints, strong delta, diagonal imbalances, stacked imbalances and
  repeated prints are valid inputs to the generic idea of aggression, and how are they measured or combined?

---

## 4. Absorption, exhaustion, delta flip

**Course says.** The course gives **no numbers** for these; they are read by eye.
- **Absorption** = effort without result. Sell pressure with no new low or an up-close = buy-side absorption (bullish). Buy pressure with no
  progress or a down-close = sell-side absorption (bearish). Signature: bar delta positive but the bar closes negative, with seller imbalances [E1, E5].
- **Exhaustion:** one side's prints shrink toward the extreme (12/34, then 1|2, then 0|5), then price moves the other way [E4, E5].
- **Delta flip:** delta changes sign (bar or rolling window) while price fails to extend; afterwards delta accelerates in the new direction [E1, E5].
- **Delta over a window** (Delta Profile): 30 min = 6 five-minute bars, 60 min = 12 [E5].
- Course sequence: absorption → aggression → delta flip → auction finished → structure break → retest → entry [E1].

**Draft candidates (mine, to react to).**
- Absorption: bar |delta| ≥ X × recent average **and** the bar's close/extreme contradicts the delta sign.
- Exhaustion: max row volume (or big-print size) at the extreme falls over k bars while price still makes the extreme.
- Delta flip: sign change of rolling N-bar delta while price fails to make a new extreme in the old direction.

- ⚠️ **Q4.1** Accept these candidates or give your own? (X, k, N open.)
- ⚠️ **Q4.2** → *merged into Q3.2.*
- ⚠️ **Q4.3** Absorption as a single bar or a multi-bar pattern? (The A+ short needed 2 or more repeated exhaustion prints.)
- ⚠️ **Q4.4** Delta flip on bar delta, cumulative delta, or the windowed Delta Profile?
- ⚠️ **Q4.5** Do we need all four reads (absorption, failed aggression, exhaustion, delta flip) or do 1–2 cover most entries?
- ⚠️ **Q4.6** *(from additions)* **Is failed aggression the broader concept, with absorption one measurable manifestation?** Or are they separate,
  independently measurable signals? Settle before implementing either so one phenomenon isn't encoded under several names.
  *My leaning (not a decision):* failed aggression is the broad concept (an attempt at a level with no price progress, per Q3.2); absorption, exhaustion
  and delta flip are measurements or confirmations of it.

---

## 5. Location (where a read is allowed to count)

**Course principle.** Order flow is *when*, location is *where*. No location → no trade, even with a zero or stacked imbalance [E3, E5].

**5a. Volume profile [E2, E3, E6].** POC = most traded price. Value Area = 70 % of volume (VAH/VAL). HVN = accepted price; LVN = gap, unaccepted,
expect rejection; deeper or larger LVN = stronger. Above VAH = premium (sell candidate); below VAL = discount (buy candidate); targets POC/VWAP; best on
consolidation/balanced days, mostly London/Asia. Anchors: session start; at a fresh session open use the prior session (London 12:30 IST → Asia 05:30 IST
profile); whole-day profile for the big evening move; the NY-session profile (18:30–22:30 IST) is used the next morning [E2, E6]. A composite/long-term
profile is confluence only (wide stop); the session profile gives the entry and stop [E6]. Shape = regime: D balanced, P value up (often a trap off short
covering), b value down, B / double distribution = value migrating [E6].

**5b. VWAP [E3, E4].** Daily reset. Target, rejection location, confirmation (close beyond VWAP and POC). Never alone.
**5c. Higher-timeframe structure [E3, E5].** 1h/30m supply and demand; the course wants structure and profile to agree.
**5d. Day extremes (OLC) [E4]** and the "business area" idea (price returns to the max-volume area before leaving) [E2].

- ⚠️ **Q5.1** Location source for version 1: profile only (LVN/VA edges/POC), profile + VWAP, or profile + 1h/30m structure zones? (Structure zones are subjective.)
- ⚠️ **Q5.2** Profile anchor rule: one rule or session-dependent (Asia/London/NY/whole day)? What is it in code terms?
- ⚠️ **Q5.3** What makes an LVN tradeable: minimum depth (volume below x % of profile mean), minimum width, minimum age? ("Bigger = better" has no cut-off.)
- ⚠️ **Q5.4** Is the value-area edge (price beyond VAH/VAL) a location on its own, or only when also at an LVN/zone?
- ⚠️ **Q5.5** Use the profile-shape regime (D/P/b/B) as a filter? If so, how is the shape detected automatically?
- ⚠️ **Q5.6** Time basis: all sessions or RTH only (course: RTH has the large participants)? Which hours, in IST and CT?
- ⚠️ **Q5.7** *(merged: old Q5.7 + additions Q5.9 + Q5.10)* **What exactly is a "touch", and how close must a read be to the location?**
  - Touch options: price enters the LVN range; price reaches its boundary; price comes within a configurable number of ticks; another condition.
  - Do the touch, the absorption proximity and the aggression proximity share one tolerance or have separate definitions?
  - **No baseline value is proposed** (the course gives no tick number). Treat the tolerance as a test parameter, or scale it to tick size or zone width.
- ⚠️ **Q5.8** *(from additions)* **How should LVN strength be ranked?** Options: width; depth of the volume depression; relative volume vs profile average;
  surrounding HVN volume; separation between neighbouring nodes; a combined score.
  *My leaning (not a decision):* start with one metric (depth, relative to the profile mean), test width as a second, and avoid a weighted combined score
  until there is enough data (about 7 days of retained ticks invites overfitting).

---

## 6. Entry models (candidates)

Common order in the course: location says where → wait for price to arrive (don't chase) → order-flow read → wait for the trigger bar to **close** → enter,
stop beyond the zone, target [E1, E3, E4].

| Model | Location | Order-flow trigger | Source |
|---|---|---|---|
| **M1 LVN rejection** | price enters an LVN | closed bar rejects back out and closes beyond the POC (and VWAP in the NY example); no HVN/POC between entry and target | E3 |
| **M2 Value-area counter-trade** | beyond VAH or VAL, at an HTF zone, consolidation day | failed breakout (messy candles, big prints that don't extend); enter at the failure | E2, E3 |
| **M3 Failed-aggression reversal at a zone** | HTF demand/supply, VAL/VAH, LVN | aggressors hit the zone, bar recovers: strong delta against the zone, up-close, finished auction at the extreme, then opposite imbalances/delta | E3, E5 |
| **M4 Exhaustion → stacked imbalance (A+)** | rejection at VWAP / value area | repeated zeros at the extreme (2 or more) + absorbed aggression + delta turns, then one bar prints a stacked imbalance against the prior move | E4 |
| **M5 Delta flip + retest** | resistance/VAH/LVN, trapped traders | sustained delta one way, stall, delta flips with imbalances; enter on the flip or on the retest of the broken area | E1, E5 |
| **M6 Trapped buyers/sellers at LVN + VWAP** | VWAP on the wrong side + LVN | seller/buyer aggression, up-moves closing lower, zero at the extreme | E5 |
| **M7 Profile-shape failure** | P-shape extension that fails to hold | rejection with footprint confirmation | E6 |

M1–M3 share one skeleton (location + failed-aggression read + closed trigger bar). M4–M6 are variations of exhaustion/trap reads.
Which models to build is decided by **Q0.1–Q0.3** above; the first-version finalisation of each model's details waits until §2–§5 are settled.

---

## 7. Trade-idea lifecycle (rewritten — location-agnostic, owner-agnostic)

**Why this section was rewritten.** The additions proposed: an underlying strategy creates the idea → order-flow monitoring → LVNs ranked → touch of the
selected LVN activates and locks it → confirmation → order → ownership transfers. Two parts of that assume things not yet decided: activation is
**LVN-only** (it doesn't fit M2, M4, M5, whose location is a value-area edge, VWAP or a broken level), and an **"underlying strategy" owns the idea**
(that is the open Q1.3 / Q5.1 decision, and the course has no such layer). The state machine below keeps the useful part (separate states) without those assumptions.

**States (kept separate on purpose)**

| State | Meaning |
|---|---|
| **Monitoring** | a location of interest exists and order flow is being watched |
| **Activated** | the activation condition for that location type has occurred (e.g. LVN touched, price beyond VAH/VAL, VWAP reached) |
| **Confirmed / entry-ready** | the required order-flow confirmation has occurred (§8) |
| **Executed** | the order has actually been placed and the position is active |
| **Expired** | the setup stayed activated or confirmed longer than allowed without executing |
| **Invalidated** | the thesis broke after activation (e.g. price traded through the LVN, left the zone, or the location itself changed) |
| **Reset** | state cleared after a fill / stop-out / target, ready to monitor again |

Separating "touched", "confirmed" and "ordered" prevents them being treated as one event.

- ⚠️ **Q7.1** Closed-bar entries only, or earlier entries once price is at the location and a defined set of conditions has already printed?
  (The course usually waits for the close [E3], but sometimes enters with ~28 s left [E5] or before a zero prints [E5 ex. 3].)
- ⚠️ **Q7.2** Market order at the trigger, or a limit at a defined price (e.g. retest of the broken level)? The course mentions "enter on the flip" and "enter on the retest".
- ⚠️ **Q7.3 / Q-L5** *Expiry:* how long is a setup valid after activation or confirmation (bars or seconds) before it expires?
- ⚠️ **Q-L1** What exactly activates a setup, **per location type**? (LVN touched; price beyond VAH/VAL; VWAP reached; other.) Equals additions Q8.4.
- ⚠️ **Q-L2** What changes an activated setup into entry-ready? Defined in §8. Equals additions Q8.5.
- ⚠️ **Q-L3** *Ownership:* does anything own the idea before the order (an upstream strategy / structure layer, or the order-flow logic itself)? Where does
  responsibility transfer to execution and position management? Settle only after Q1.3 / Q5.1.
- ⚠️ **Q-L4** If several LVNs are ranked dynamically before activation, what happens when price touches a **lower-ranked** LVN than the selected one? Is the
  selection locked at touch, can any qualifying LVN activate, can several be active at once? (In the course any qualifying LVN price reaches counts.)
- ⚠️ **Q-L6** *Invalidation:* which events invalidate an activated setup (price trades through the LVN; leaves the zone; the location is re-ranked or changes;
  the upstream idea is withdrawn before an order)? If an idea is withdrawn before an order, monitoring stops immediately (additions step 8, kept as proposed).
- ⚠️ **Q-L7** *Reset:* after a fill, stop-out or target, may the same location be re-armed? How many times per location or per day? (Merges with Q10.4.)
- ⚠️ **Q8.6** What exact event constitutes **execution**? (Order submitted, order filled, position active?)

---

## 8. How confirmations combine

**Course says.** No example enters on a single read. Examples stack location + structure + a closed trigger bar + at least one footprint read. Example: HTF zone +
VA edge + 34-lot print + recovering close [E3]. A zero print alone is explicitly not enough [E4, E5]. A+ chain: VWAP rejection + repeated zeros + absorbed
aggression + delta shift + stacked imbalance [E4].

- ⚠️ **Q8.1** Minimum required: location (mandatory) + trigger bar (mandatory) + N of {absorption, failed aggression, finished auction, delta flip, stacked imbalance}.
  What is N (1, 2)?
- ⚠️ **Q8.2** Are some reads mandatory per model (e.g. M1 requires the close beyond the POC; M4 requires the stacked imbalance)?
- ⚠️ **Q8.3** Do reads on different bars in a window count together (absorption on bar t−1, finished auction on bar t)? Window length?
- ⚠️ **Q8.4 / Q8.5** → merged into **Q-L1 / Q-L2** (activation and confirmation events).

---

## 9. Stop

**Course says.** Beyond the zone that invalidates the thesis: above the LVN/volume area for shorts, below the demand zone or swept low for longs. Slightly beyond
the edge, not on it. With a long-term profile the stop is wide, so take the stop from the smaller session profile [E3, E6, E10]. No tick values.

- ⚠️ **Q9.1** Stop reference: edge of the LVN, edge of the zone, or the extreme of the trigger bar/swing?
- ⚠️ **Q9.2** Buffer beyond it: fixed ticks, fraction of zone width, or ATR?
- ⚠️ **Q9.3** Maximum stop distance. If the structural stop exceeds the cap: skip the trade or use another reference?
- ⚠️ **Q9.4** Time stop (exit if the thesis hasn't played out in N bars)? The course has none.

---

## 10. Target and management

**Course says.** Minimum 1:2, 1:3 possible. Structural targets: VWAP first in counter-trades, then POC, or the opposite VA edge (VAL for shorts) [E2, E3]. No trade if a
POC/HVN lies between entry and target [E3]. Management seen: after a small gain move the stop to cost ("SL CTC"), book about 50 % at the halfway point, hold the
rest for the full target, re-enter after a cost exit if the idea holds [E6]. Start counter-trades with minimum size.

- ⚠️ **Q10.1** Fixed R-multiple (2R? 3R?), structural target, or "the nearer of the two with a minimum of 2R"?
- ⚠️ **Q10.2** If structural: which nodes count (VWAP, POC, opposite VA edge, next HVN)? What if the nearest is closer than 2R?
- ⚠️ **Q10.3** Partial profit and breakeven move: yes or no? Where (1R, midpoint)? What fraction? (With a 1-contract cap, partials aren't possible; decide for later.)
- ⚠️ **Q10.4** Re-entry after a stop or cost exit: allowed? How many times per zone/day? (Course: usually one planned entry per zone per day, sometimes 3 on the same setup.) Same as **Q-L7**.

---

## 11. No-trade filters

**Course says.** No location → no trade even with a zero or stacked imbalance [E3, E5]. A POC/HVN between entry and target → no trade [E3]. Don't chase: wait for retest/closed
bar; don't sell the middle of a fall or buy the middle of a rally ("falling knife") [E1, E3]. A bar closing just short of the POC means the POC likely breaks → skip the counter-trade [E3].
P-shape longs are traps outside trending days [E6]. Finished auction alone isn't enough or mandatory [E4, E5]. Heatmap/gamma only: no entries inside liquidity clutter [E7]; negative-gamma regime → don't fade [E10].

- ⚠️ **Q11.1** Which of these are hard filters in version 1?
- ⚠️ **Q11.2** "Falling knife / strong momentum" needs a definition (e.g. N consecutive same-sign delta bars plus range expansion).
- ⚠️ **Q11.3** Session/news blackouts (maintenance halt, first minutes after an open, scheduled news)? The course doesn't say, but says sudden moves cluster at opens.
- ⚠️ **Q11.4** Max trades per session or per zone, and cool-down after a loss? (The risk chain exists separately; this asks about strategy-level behaviour.)

---

## 12. Liquidity (heatmap), sweep, and options (gamma)

**Course says.**
- **Heatmap [E7].** Walls of resting liquidity. Stacking strengthens a level, pulling weakens it. Price is drawn to walls like a magnet. A wall that holds under aggression
  ("gap") → trade away from it. A wall consumed with stops triggered ("sweep") → expect a fast reversal. Don't trade in thin, scattered "garbage" liquidity. Gold: 100–150 lots is already large.
- **Gamma [E10].** HVL separates positive-gamma (reversals, fade edges) from negative-gamma (momentum, don't fade). CR is a ceiling and PS a floor, both traded with absorption. G1–G3 pin-or-explode.
  One-Day Max/Min are expected range edges. Needs an options-data vendor. Zero-DTE not useful on Gold.

- ⚠️ **Q12.1** In scope for version 1, deferred or dropped? (Same as **Q0.4**; both layers need data beyond tick/footprint.)
- ⚠️ **Q12.2** If the heatmap is in scope later, how is a "wall" defined from depth data (size threshold, persistence in seconds)?
- ⚠️ **Q12.3** *(from additions)* **Is liquidity sweep part of version 1?** Include, defer, or drop because it needs L2/depth history?
- ⚠️ **Q12.4** *(from additions, if included)* **What objectively constitutes a sweep?** Define: required wall size; required persistence; amount consumed or removed;
  whether price must trade through the wall; whether a subsequent reversal is required; whether a sweep is a location, a confirmation, or both.
  Use the course's terms: **sweep** = wall consumed, stops triggered, then reversal; **gap** = wall holds and absorbs, price reverses off it. Keep them separate.

---

## 13. Validation

- The speaker's results are screenshots on a messaging channel, not a track record; none of it is evidence.
- The generic backtest engine runs on 1-minute OHLC and cannot test footprint, imbalance, big-print or delta rules (no historical tick data). These rules can only be judged on the retained tick data
  (rolling 7 days) and by forward testing on the Sim account.

- ⚠️ **Q13.1** Acceptance test before any rule may trade: how much forward data, how many signals, what metrics?
- ⚠️ **Q13.2** Log each rule's raw signal (no trading) first so signal quality can be judged apart from P&L? (Suggested: yes, before any order wiring.)
- ⚠️ **Q13.3** Is there any way to get historical tick or footprint data for Gold to backtest (vendor export, longer retention)?

---

## Review order

Entry models depend on the definitions of the reads and locations, so models aren't finalised before those primitives are settled.

1. **§0 — Scope call** (which models, direction, one strategy or many, heatmap/gamma in or out)
2. **§1 — Bar / data unit**
3. **§2–§3 — Footprint primitives, aggression and big trades**
4. **§4 — Absorption, exhaustion, delta flip**
5. **§5 — Location and LVN qualification**
6. **§6 — Entry models** (finalise details now that the primitives are settled)
7. **§7–§8 — Lifecycle, trigger timing, confirmation**
8. **§9–§11 — Stop, target, no-trade filters**
9. **§12 — Heatmap / sweep / gamma (optional layers)**
10. **§13 — Validation**

## Decision table

| ID | Topic | Status | Decision |
|---|---|---|---|
| Q0.1–0.4 | Model scope, direction, structure, heatmap/gamma scope | OPEN | — |
| Q1.1–1.3 | Bar type, tick count, structure timeframes | OPEN | — |
| Q2.1–2.5 | Imbalance threshold, row grouping, min volume, finished-auction definition and role | OPEN | — |
| Q3.1, Q3.2, Q3.3–3.5 | Big-trade threshold; **aggression success/failure (merged)**; repeats; proximity; what counts as aggression | OPEN | — |
| Q4.1, Q4.3–4.6 | Candidate definitions; absorption bar/multi-bar; delta-flip basis; need for all reads; failed aggression vs absorption | OPEN | — |
| Q5.1–5.6 | Location sources, anchors, LVN qualification, VA edge, shape, session | OPEN | — |
| Q5.7 | **Touch / proximity / tolerance (merged)** | OPEN | — |
| Q5.8 | LVN strength ranking | OPEN | — |
| Q7.1–7.3, Q-L1–L7, Q8.6 | Trigger timing, order type, expiry, activation, ownership, multi-LVN case, invalidation, reset, execution event | OPEN | — |
| Q8.1–8.3 | How confirmations combine | OPEN | — |
| Q9.1–9.4 | Stop | OPEN | — |
| Q10.1–10.4 | Target, partials, re-entry | OPEN | — |
| Q11.1–11.4 | No-trade filters | OPEN | — |
| Q12.1–12.4 | Heatmap, sweep, gamma scope and definitions | OPEN | — |
| Q13.1–13.3 | Validation | OPEN | — |
