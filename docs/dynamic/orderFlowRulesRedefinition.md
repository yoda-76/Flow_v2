# Order-flow rules — redefinition review (from the course)

**Started:** 2026-10-08
**Status of every question: OPEN** until the user settles it.

## Purpose and ground rules

Redefine the order-flow entry rules from scratch. The source is the course, via
`docs/video_transcripts/orderflow course/distilled/distillation_2/entry_model_rules.txt`
(references like [E4] = course episode 4; `distillation_1/` has the fuller text).

- **Nothing in the existing code or in `orderFlowExecutionRules.md` is a reference or a constraint.** It was
  placeholder logic written to build the system. This file does not compare against it.
- Each section has: **what the course says** → **draft rule** (my reading, to be confirmed) → **open questions**
  (⚠️ = the course is silent, contradictory or discretionary, so a human decision is needed).
- A rule is **RESOLVED** only after the user settles it. Draft rules are proposals, not decisions.
- The course is one trader's discretionary method. His accuracy claims (70–90 %) are unverified and are not used.
  Every rule here is a hypothesis to forward-test.
- Instrument scope: Gold only (`@GC`).
- Method: user answers → fold back → only then does anything get built.

---

## 1. What a "bar" is (the unit all rules are evaluated on)

**Course.** The speaker reads order flow on 500-tick candles [E3]. Elsewhere he uses a 5-minute footprint [E4, E5],
a 20-range footprint to look inside a forming 5-minute candle [E5], and says range charts work poorly for Gold
[E5]. Higher-timeframe structure is read on 1h and 30m [E3].

**Draft.** One order-flow bar type, closed-bar evaluation. Candidate: 500-tick.

**Open**
- ⚠️ Q1.1 Which bar: 500-tick, a time bar (3 or 5 min), or range? The course mixes them.
- ⚠️ Q1.2 Is the 500-tick count fixed, or should it scale with activity (the course notes the evening session prints far more
  candles per hour than the morning)?
- ⚠️ Q1.3 Which timeframes define "structure" (1h main, 30m refine, per the course) and does the system need them at all, or
  is the volume profile enough as the only location source? (See §5.)

---

## 2. Footprint primitives

**Course.**
- Cell: bid volume = aggressive sell, ask volume = aggressive buy. Bar volume = bid+ask; delta = ask−bid [E1, E5].
- **Diagonal imbalance.** Bullish at price P: `ask(P) / bid(P−1 tick) × 100 ≥ threshold`. Bearish at P:
  `bid(P) / ask(P+1 tick) × 100 ≥ threshold` [E5]. Gold threshold 300 %. 100 % is too noisy, 200/400/500 are all
  "valid" and left to testing [E5, E8].
- **Stacked imbalance:** 3 or more adjacent levels, same direction = aggression sweeping levels, trend/impulse [E5]. A single
  candle printing one was the trigger for the A+ short [E4].
- **Finished auction:** a zero on one side at the bar's extreme (no counterparty). At the high it means buyers are exhausted
  and price tends to fall. At the low it means sellers are exhausted. **Unfinished** (no zero) means price tends to revisit
  that extreme [E4, E5]. Only meaningful at a meaningful location. Not mandatory for a move [E5].
- Row grouping ("tick interval"): for Gold use more than 5 ticks per row (6/7/10). Smaller rows fragment the data and
  create false zeros [E5, E8].

**Draft.**
- Use the diagonal imbalance formula, threshold 300 % to start.
- Stacked = 3 or more consecutive levels.
- Finished auction = zero (or ≤ n lots) at the bar's extreme row.

**Open**
- ⚠️ Q2.1 Imbalance threshold: 300 % as the starting point, or sweep 200/300/400/500 in testing?
- ⚠️ Q2.2 Row grouping size, which changes what counts as a zero. Pick one value, or treat it as a test parameter?
- ⚠️ Q2.3 Minimum volume for an imbalance to count (the ratio holds for 1 vs 0 or 3 vs 1, which is noise)? The course
  doesn't say.
- ⚠️ Q2.4 Finished auction: strictly zero, or "≤ 1–2 lots" for tolerance? And does it need a minimum volume at the
  opposite side?
- ⚠️ Q2.5 Is finished/unfinished auction a trigger, a confirmation, or only a context flag? (The course says "not mandatory"
  and "only at a location".)

---

## 3. Big trades (single large prints)

**Course.**
- A bubble = one print ≥ threshold. 1–2 lots are retail, 30/40/50/100 lots are larger players [E1, E3].
- Gold thresholds: 30 (default), 40–50 ("institutional"), lower to 10–20 to see retail/smaller players. Raise the
  threshold when volume is high, lower it when volume is low [E3, E4].
- Read in context, in two opposite ways:
  1. **Aggression that wins:** big buy at demand and the candle closes up, so buyers are entering and sellers are trapped [E3].
  2. **Aggression that fails:** big buys at supply or VAH and price will not advance, so buyers are being absorbed → short [E1, E3, E5].
  Same print, opposite meaning. The distinction is the price outcome after it.

**Draft.** A big trade is a context input, never a standalone trigger. Its meaning is judged by what price does after it.

**Open**
- ⚠️ Q3.1 Fixed threshold (30? 40?) or adaptive to recent volume?
- ⚠️ Q3.2 How is "price outcome after the print" measured: N ticks of progress within M seconds, or the bar close? This is
  the line between "wins" and "fails".
- ⚠️ Q3.3 Do repeated big prints on the same side (the course shows 2 back-to-back ~40s, or a 50 then a 98) count more than one?
- ⚠️ Q3.4 How close to the zone must the print be to count?

---

## 4. Delta, absorption, exhaustion, delta flip (the four "reads")

**Course.** These are central, and the course gives **no numbers** for any of them. They are read by eye.
- **Absorption** is effort without result. Sell-side pressure with no new low or an up-close = buy-side absorption (bullish).
  Buy-side pressure with no progress or a down-close = sell-side absorption (bearish). The textbook signature is bar delta
  positive but the bar closes negative, with seller imbalances present [E1, E5].
- **Exhaustion:** one side's prints shrink toward the extreme (e.g. 12/34 then 1|2 then 0|5), then price moves the other way [E4, E5].
- **Delta flip:** delta changes sign (bar or rolling window) while price fails to extend. Afterwards delta accelerates
  candle after candle in the new direction [E1, E5].
- **Delta over a window** (Delta Profile): 30 min = 6 five-minute bars, 60 min = 12; shows where dominance shifted [E5].
- The course's sequence: absorption → aggression → delta flip → auction finished → structure break → retest → entry [E1].

**Draft.** Each read needs a precise, testable definition. Candidates to react to:
- Absorption: bar |delta| large (≥ X × recent average) **and** the bar's close/extreme contradicts the delta sign.
- Exhaustion: max row volume (or big-print size) at the extreme falls over the last k bars while price still makes the extreme.
- Delta flip: sign change of rolling N-bar delta, with price failing to make a new extreme in the old direction.

**Open**
- ⚠️ Q4.1 Accept these candidate definitions, or give your own? (Open: X, k, N.)
- ⚠️ Q4.2 Absorption needs "price did not move". Measured in ticks over how many bars or seconds?
- ⚠️ Q4.3 Absorption as one bar or as a multi-bar pattern? (The A+ short needed ≥ 2 repeated exhaustion prints.)
- ⚠️ Q4.4 Is delta flip evaluated on bar delta, cumulative delta, or the windowed Delta Profile?
- ⚠️ Q4.5 Do we need all four reads, or do 1–2 of them cover most of the entries? (Redundancy: absorption ≈ failed aggression
  ≈ exhaustion in several of the course's examples.)

---

## 5. Location (where a read is allowed to count)

**Course principle.** Order flow is *when*; location is *where*. No location, no trade, even if a zero or stacked imbalance
prints [E3, E5].

**5a. Volume profile [E2, E3, E6]**
- POC = most-traded price. Value Area = 70 % of volume, with VAH and VAL. HVN = accepted/fair price, LVN = gap, unaccepted
  price, expect rejection. Deeper or larger LVN means stronger rejection.
- Above VAH = premium, sell candidate. Below VAL = discount, buy candidate. Targets: POC/VWAP. Works best in consolidation or
  balanced days, mostly London/Asia.
- Anchors: session start; at a fresh session open start from the prior session (London 12:30 IST → Asia 05:30 IST profile);
  use the whole day's profile for the big evening move; the NY-session profile (18:30–22:30 IST) is used the next
  morning (price returning to that POC = counter-trade) [E2, E6].
- Composite or long-term profile = confluence only, because its stop is wide. Session profile gives the precise entry and stop [E6].
- Profile shape = regime: D (balanced), P (value up, often a trap off short covering), b (value down), B/double
  distribution (value migrating) [E6].

**5b. VWAP [E3, E4].** Daily reset. Used as target, rejection location, and confirmation (closing beyond both VWAP and POC).
Never alone.

**5c. Higher-timeframe structure [E3, E5].** 1h/30m supply and demand zones. The course always wants structure and profile to agree.

**5d. Day extremes (OLC) [E4]** and the "business area" idea (price returns to the max-volume area before leaving) [E2].

**Open**
- ⚠️ Q5.1 Location source for the first version: profile only (LVN/VA edges/POC), profile + VWAP, or profile + 1h/30m
  structure zones? (Structure zones are subjective and hard to define objectively.)
- ⚠️ Q5.2 Profile anchor rule: one rule or a session-dependent set (Asia/London/NY/whole day)? What is the rule in code terms?
- ⚠️ Q5.3 What makes an LVN "tradeable": minimum depth (volume below x % of the profile mean), minimum width, minimum age?
  ("Bigger gap = better" has no cut-off.)
- ⚠️ Q5.4 Is the value-area edge (price beyond VAH/VAL) a location on its own, or only when also at an LVN/zone?
- ⚠️ Q5.5 Do we use the profile-shape regime (D/P/b/B) as a filter? If yes, how is the shape detected automatically?
- ⚠️ Q5.6 Time basis: all sessions, or RTH only (course: RTH has the large participants)? Which hours, in IST and in CT?
- ⚠️ Q5.7 Max distance from the location for a read to count.

---

## 6. Entry models (candidate list, pick which to build)

Each model = location + read(s) + trigger bar. The course's recurring order: structure/location says where → wait for
price to arrive (don't chase) → order-flow read → wait for the trigger bar to **close** → enter, stop beyond the zone,
target [E1, E3, E4].

| Model | Location | Order-flow trigger | Source |
|---|---|---|---|
| **M1 LVN rejection** | price enters an LVN | closed bar rejects back out and closes beyond the POC (and VWAP in NY example); no HVN/POC between entry and target | E3 |
| **M2 Value-area counter-trade** | beyond VAH or VAL, at HTF zone, consolidation day | failed breakout (messy candles, big prints that don't extend); enter at the failure | E2, E3 |
| **M3 Failed-aggression reversal at a zone** | HTF demand/supply, VAL/VAH, LVN | aggressors hit the zone and the bar recovers: strong delta against the zone, up-close, finished auction at the extreme, then opposite imbalances/delta | E3, E5 |
| **M4 Exhaustion → stacked-imbalance (A+)** | rejection at VWAP / value area | repeated zeros at the extreme (≥ 2) + aggression absorbed + delta turns, then one bar prints a stacked imbalance against the prior move | E4 |
| **M5 Delta-flip + retest** | resistance/VAH/LVN, trapped traders | sustained delta one way, stall, delta flips with imbalances, enter on flip or on the retest of the broken area | E1, E5 |
| **M6 Trapped buyers/sellers at LVN + VWAP** | VWAP on the wrong side + LVN | seller/buyer aggression, up-moves closing lower, zero at the extreme | E5 |
| **M7 Profile-shape failure** | P-shape extension that fails to hold | rejection with footprint confirmation | E6 |

M1–M3 share one skeleton (location + a failed-aggression read + a closed trigger bar). M4–M6 are variations of
exhaustion/trap reads.

**Open**
- ⚠️ Q6.1 Which models go in the first version? Suggested: M1 and M3 (the same skeleton), M2 as the second, M4 later as
  an extra trigger.
- ⚠️ Q6.2 Long and short symmetric, or short-biased first? (Most course examples are shorts; longs are the stated mirror.)
- ⚠️ Q6.3 One strategy that selects models, or one strategy per model?

---

## 7. Trigger timing

**Course.** The textbook is "wait for the trigger bar to close (500-tick close)" [E3]. In practice he sometimes enters
inside the last seconds of a bar (≈ 28 s left) [E5], or before a zero prints [E5 ex. 3], or before the bar fully closes
[E4].

**Draft.** Closed-bar entry only (reproducible, no look-ahead).

**Open**
- ⚠️ Q7.1 Closed-bar only, or allow earlier entries once price is at the zone and a defined set of conditions has already printed?
- ⚠️ Q7.2 Market order at the close/next tick, or a limit at a defined price (e.g. retest of the broken level)? The course
  mentions both "enter on the flip" and "enter on the retest".
- ⚠️ Q7.3 How long after the trigger bar is the setup valid (bars/seconds) before it expires?

---

## 8. How confirmations combine

**Course.** No example enters on a single read. The examples stack: location + structure + a closed trigger bar +
at least one footprint read. Example: HTF zone + VA edge + 34-lot print + recovering close [E3]. A zero print alone is
explicitly not enough [E4, E5]. A+ chain: VWAP rejection + repeated zeros + absorbed aggression + delta shift + stacked imbalance [E4].

**Open**
- ⚠️ Q8.1 Minimum required: location (mandatory) + trigger bar (mandatory) + N of {absorption, failed aggression, finished
  auction, delta flip, stacked imbalance}. What is N (1, 2)?
- ⚠️ Q8.2 Are some reads mandatory per model (e.g. M1 requires the close beyond the POC; M4 requires the stacked imbalance)?
- ⚠️ Q8.3 Do reads on different bars within a window count together (e.g. absorption on bar t−1 and finished auction on bar t)? Window length?

---

## 9. Stop

**Course.** Beyond the zone that invalidates the thesis: above the LVN/volume area for shorts, below the demand zone /
swept low for longs. Slightly beyond the edge, not on it. With a long-term profile the stop gets large, so the stop comes
from the smaller session profile [E3, E6, E10]. The A+ example had a loose, structure-based stop. No tick values are given.

**Open**
- ⚠️ Q9.1 Stop reference: edge of the LVN, edge of the zone, or the extreme of the trigger bar/swing?
- ⚠️ Q9.2 Buffer beyond it: fixed ticks, a fraction of the zone width, or ATR-based?
- ⚠️ Q9.3 Maximum stop distance. If the structural stop is wider than the cap, skip the trade or use a different reference?
- ⚠️ Q9.4 Time stop (exit if the thesis hasn't played out in N bars)? The course has none.

---

## 10. Target

**Course.** Minimum 1:2, 1:3 possible. Structural targets: VWAP first in counter-trades, then POC, or the opposite
value-area edge (VAL for shorts) [E2, E3]. Don't take a trade if a POC/HVN lies between entry and target [E3].
Partial management: after a small gain move the stop to cost ("SL CTC"), book about 50 % at the halfway point, hold the rest
for the full target, re-enter after a cost exit if the idea still holds [E6]. Start counter-trades with minimum size.

**Open**
- ⚠️ Q10.1 Fixed R-multiple (2R? 3R?), structural target, or "the nearer of the two with a minimum of 2R"?
- ⚠️ Q10.2 If structural: which nodes count (VWAP, POC, opposite VA edge, next HVN)? What if the nearest is closer than 2R?
- ⚠️ Q10.3 Partial profit and breakeven move: yes or no? Where (at 1R, at the midpoint)? What fraction? (Needs the runtime
  to support partials; with max size 1 contract it is a non-starter. Decision for later.)
- ⚠️ Q10.4 Re-entry after a stop or a breakeven exit: allowed? How many times per zone/day? (Course: usually one planned
  entry per zone per day, sometimes 3 in a row on the same setup.)

---

## 11. No-trade filters

**Course.**
- No location → no trade, even with a zero or stacked imbalance [E3, E5].
- A POC/HVN between entry and target → no trade [E3].
- Don't chase: wait for the retest/closed bar. Don't sell the middle of a fall or buy the middle of a rally ("falling knife / flying
  arrow") [E1, E3].
- A bar that closes just short of the POC means the POC is likely to break → skip the counter-trade [E3].
- P-shape longs are traps outside trending days [E6].
- Finished auction alone is not enough and not mandatory [E4, E5].
- (Heatmap / gamma only) no entries inside liquidity clutter [E7]; negative-gamma regime means don't fade [E10].

**Open**
- ⚠️ Q11.1 Which of these are hard filters in version 1?
- ⚠️ Q11.2 "Falling knife / strong momentum" needs a definition (e.g. N consecutive bars with the same-sign delta and a range
  expansion).
- ⚠️ Q11.3 Session/news blackouts (maintenance halt, the first minutes after the open, scheduled news)? The course doesn't
  say, though it says sudden moves cluster at session opens.
- ⚠️ Q11.4 Max trades per session or per zone, and cool-down after a loss. (The system's risk chain exists separately. The
  question is the strategy-level behaviour.)

---

## 12. Liquidity (heatmap) and options (gamma) layers

**Course.**
- **Heatmap [E7].** Walls of resting liquidity; stacking (growing) strengthens a level, pulling (vanishing) weakens it.
  Price is drawn to walls like a magnet. A wall that holds under aggression ("gap") → trade away from it. A wall consumed
  with stops triggered ("sweep") → expect a fast reversal. Don't trade in thin, scattered "garbage" liquidity. Gold: 100–150
  lots is already large.
- **Gamma exposure [E10].** HVL separates positive-gamma regime (reversals, fade the edges) from negative-gamma regime
  (momentum, don't fade). CR is a ceiling and PS a floor, both traded with absorption. G1–G3 are pin-or-explode levels.
  One-Day Max/Min are expected range edges. Needs an options-data vendor. Zero-DTE levels are not useful on Gold.

**Open**
- ⚠️ Q12.1 In scope for the first version, deferred, or dropped? (Both need data beyond tick/footprint: depth history, an options
  vendor.)
- ⚠️ Q12.2 If heatmap is in scope later, how should "wall" be defined from depth data (size threshold, persistence in seconds)?

---

## 13. Validation (how any of this will be judged)

- The speaker's results are screenshots on a messaging channel, not a track record. None of it is treated as evidence.
- The existing generic backtest engine runs on 1-minute OHLC and cannot test footprint, imbalance, big-print or delta
  rules (no historical tick data). So these rules can only be evaluated by (a) the recorded tick data we retain (rolling
  7 days), and (b) forward testing on the Sim account.

**Open**
- ⚠️ Q13.1 Acceptance test before any rule is allowed to trade: how much forward data / how many signals / what metrics?
- ⚠️ Q13.2 Should each rule's raw signal (without trading) be logged first so signal quality can be judged independently of
  P&L? (Suggested: yes, before any order wiring.)
- ⚠️ Q13.3 Is there any way to get historical tick or footprint data for Gold to backtest (vendor export, longer retention)?

---

## Decision table

| ID | Topic | Status | Decision |
|---|---|---|---|
| Q1.1–1.3 | Bar type, tick count, structure timeframes | OPEN | — |
| Q2.1–2.5 | Imbalance threshold, row grouping, min volume, finished-auction definition and role | OPEN | — |
| Q3.1–3.4 | Big-trade threshold, outcome measurement, repeats, proximity | OPEN | — |
| Q4.1–4.5 | Definitions of absorption, exhaustion, delta flip | OPEN | — |
| Q5.1–5.7 | Location sources, profile anchors, LVN qualification, shape, session | OPEN | — |
| Q6.1–6.3 | Which entry models, long/short, structure | OPEN | — |
| Q7.1–7.3 | Trigger timing, order type, expiry | OPEN | — |
| Q8.1–8.3 | How confirmations combine | OPEN | — |
| Q9.1–9.4 | Stop | OPEN | — |
| Q10.1–10.4 | Target, partials, re-entry | OPEN | — |
| Q11.1–11.4 | No-trade filters | OPEN | — |
| Q12.1–12.2 | Heatmap and gamma scope | OPEN | — |
| Q13.1–13.3 | Validation | OPEN | — |

## Suggested order for review

Questions answered earlier unlock the later ones:
1. §1 (bar) and §6 (which models) first, because everything else is defined relative to them.
2. §4 (definitions of the reads) and §2/§3 (thresholds) next.
3. §5 (location), then §7–§8 (timing and combination).
4. §9–§11 (stop, target, filters).
5. §12–§13 last.

## Review notes

Once a question is decided, update its section and the decision table, keeping the original question visible for audit.
