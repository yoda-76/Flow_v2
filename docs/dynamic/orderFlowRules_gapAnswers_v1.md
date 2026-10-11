# Order-flow rules — Claude's answers to the gaps audit (v1)

**Written:** 2026-10-11 by Claude (Opus 5.5, SUPER mode). Answers every item in `orderFlowRules_gaps_v1.md` (G0–G44 and
S1–S12) plus the questions they raise. `orderFlowRules_gaps_v1.md` itself is unchanged.
**Status:** these are Claude's decisions for the user to review in one pass. They are not approved until the user says so.
Once approved they fold into `orderFlowRules_reviewed_v3.md`.
**Brief from the user (2026-10-11):**
- Stay as close to the course as possible; where it is silent or ambiguous, pick a sensible value.
- Decide clearly, without over-engineering.
- The goal is a **research machine**: many configurable items so nothing silently affects PnL.
- Everything runs on Sim; wrong guesses get found in testing.
- Construct data is kept longer than 7 days as 1-second snapshots; all system logs are kept.

**How to read this file**
- Every answer is **DECIDED** unless marked **⚠ ATTENTION NEEDED**. Those are listed first, so you can do them before the
  rest.
- "Course" cites the episode (E1–E10) and where useful the raw-transcript line. "Code" cites a FLOW_V2 file I checked.
- All numbers are starting values, configurable, and logged with every evaluation (Q6.44).

---

## 0. ⚠ ATTENTION NEEDED (review these first)

| # | Item | My decision | Why it needs you |
|---|---|---|---|
| A1 | **Raw ticks ARE already stored.** Every session writes `logs/<session>/raw.jsonl`. It holds every trade (price, size, aggressor side `isAskTick`, exchange order id, **aggressor order id**, exchange time), top-of-book DOM changes and bars. `logRetentionHours` defaults to 0 = keep forever (D-75, D-106; `docs/configuration.md` line 91). Example: one session file is 147 MB, holding 46k trades and 579k DOM events. | **Keep it as it is.** It is a system log, which you said we keep. It makes every footprint read exactly reconstructable, so all footprint rules can be backtested on our own recordings. | You said "we are not storing raw ticks". The system already does. Confirm keeping it, or say to prune it. |
| A2 | **Absorption without DOM.** Extended Q4.1/Q4.6 say absorption *requires* resting-liquidity (DOM) evidence. | Two levels. **Absorption (footprint)** is the course's own signature and counts as an absorption read on its own. **Confirmed absorption** = footprint signature plus DOM resting-liquidity evidence, and counts as a stronger read. | It reverses your explicit Q4.1/Q4.6 decision. The course reads absorption from the footprint alone in nearly every example (E1, E3, E5). |
| A3 | **Add an 8th model, M8 "business-area return" (Gold Extra Edge, E2).** | Add M8 (spec in §5). | Q0.1 fixed the scope at M1–M7. |
| A4 | **Re-entries per zone.** Original Q10.4 said start with 1 attempt. | **Up to 3 entries per zone per session**, each needing a fresh +2 trigger. | The course does 3 back-to-back entries at the same gap (E3); it overrides your "start with one". |
| A5 | **Which document is authoritative.** | `orderFlowRules_reviewed_vN.md` is the authority for order-flow rule content. `decisions.md` gets one entry pointing to it, and is updated at each review version. CLAUDE.md gets a one-line note. | Changes how CLAUDE.md's "decisions.md is authoritative" rule applies. |
| A6 | **Your extended decisions that I cut down as over-engineered.** The live late-event correction/replay block (X4.24–X4.33) and the incident/severity/Telegram block (X9.14–X9.38). | Map both onto what already exists (§8) instead of building new machinery. | They were your decisions; I am superseding parts of them. |
| A7 | **Storage numbers.** | Liquidity snapshot window from ±100 to **±250 ticks**; `dataKeepTradingDays` from 7 to **30**. That is about 1.1 GB per trading day, about 33 GB in total. | It costs disk on the VM. You said storage problems get fixed later. |

**User answers (2026-10-11):**

| # | Answer | Effect in this file |
|---|---|---|
| A1 | **Approved:** keep the raw journals. | — |
| A2 | **Approved, stronger:** the resting-liquidity rule was the user's own addition to cut false alerts; remove it. | Absorption is **footprint-only**. The resting size at the level is logged as context and is never a condition. The "confirmed absorption" level is dropped (§4). |
| A3 | **Approved:** add M8. | M8 in §5 stands. |
| A4 | **Approved:** 3 entries per zone per session. | §2 G0.11 and §9 stand. |
| A5 | **No CLAUDE.md change.** This work moves to branch `orderflow-rules-review` (created from `main`). Change authority there only if it actually blocks something. | It doesn't block anything, so authority stays as it is (`reviewed_vN.md` governs order-flow rules as its own header says; nothing changes in CLAUDE.md or `decisions.md`). |
| A6 | **Approved** (the user also saw it as overkill). | — |
| A7 | Question: does 1.1 GB/day include everything? **No**, see the storage table below. | Numbers corrected. |

**Storage per trading day (measured on the dev machine's recordings, 2026-09-28/29; full ~23 h Globex day):**

| What | Today | With my changes | Kept for |
|---|---|---|---|
| `data/liquidity_map` (1 s depth snapshots) | ≈ 0.35 GB at ±100 ticks (178 MB in 11.8 h) | ≈ **0.85 GB** at ±250 ticks (×2.5 rows) | 30 trading days |
| `data/` footprint, big trades, VWAP, bars, structure | ≈ 0.01 GB | ≈ 0.01 GB | 30 trading days |
| Raw journal `logs/<session>/raw.jsonl` (ticks + top of book + clock) | ≈ **0.28 GB** (162 MB in 13.3 h; busier NY days likely more) | same | forever (A1) |
| Feature logs `logs/*_feature*.log` | ≈ 0.02 GB | same | forever (`logRetentionHours` 0) |
| New evaluation/signal log, position-management log (planned) | — | ≈ 0.02–0.05 GB (one record per evaluation change) | forever |
| **Total** | ≈ 0.65 GB | ≈ **1.2–1.3 GB** | |

My earlier "1.1 GB" counted only `data/`. Steady state: `data/` levels off at about **26 GB** (30 days). Logs grow by about
**0.3 GB per trading day** (~7 GB/month, ~80 GB/year) because they're never deleted. Per your instruction, that gets dealt
with when it becomes a problem; the existing `logRetentionHours` setting is the lever.

Everything below is decided.

---

## 1. Facts found in the code and data (they settle several gaps)

| Fact | Evidence | Settles |
|---|---|---|
| Trades carry aggressor side, exchange order id and **aggressor order id** | `raw.jsonl` tick records: `isAskTick`, `exchOrderId`, `aggExchOrderId` | G4.1 (aggressor side yes; a "single transaction" can be rebuilt per aggressor order), G25, G0.9 |
| Trades carry exchange time and local receipt time | `eventTimeMs`, `receiptTimeMs` | G4.4 (exchange time yes). The example shows event time **≈1.9 s ahead** of receipt, a real clock offset (G27). |
| GC depth arrives at many levels | `data/liquidity_map/GC/*.jsonl`: every second, bid and ask sizes at every tick within ±100 ticks | G4.2 and G4.7 (Gold Level 2 works on the dev machine), G5, G0.8 |
| Full depth is stored only as 1-second snapshots; only top-of-book is stored per event | `DataRecorder` (liquidity window 100 ticks, 1 s); raw journal `dom` = best bid/ask only | G0.8: heatmap research works at 1-second resolution |
| 1-second footprint candles (bid/ask per price) are stored | `DataRecorder.FOOTPRINT` | Volume profile can be rebuilt from them |
| Order-id repeat (iceberg) tracking already exists | `OrderRepeatFeature` | G21 (iceberg detection) |
| Alert levels already exist: ALERT / WARN / INFO, a central prefix table, `alerts.log`, watchdog → Telegram | `AlertPolicy`, `AlertDispatcher`, `ops/watchdog.py` | G3, X9.19–X9.38 |
| The current strategy derives its zone from market structure (`lastTjl2`) and retires it when the value changes | `MarketStructureLvnReversalStrategy` javadoc | G13 (objective HTF zone already exists), G29 |
| Max position is **1 contract** | `config/risk.json` | Partial fills can't happen → X8.1-D/E/G become log-only |

---

## 2. G0 — conflicts between the two decision sets (resolved)

Principle: the original-source set states **what the trading rule is**, and the extended set states **how the machine
evaluates it**. Where they clash, the merged rule below replaces both.

| # | Topic | Merged decision |
|---|---|---|
| G0.1 | Confirmation minimum | Original Q8.1 lives **inside each model**: a model may only score **+2** when it has location + closed trigger bar + at least one read. The aggregation layer adds no read count; it uses scores (§6). |
| G0.2 | Cross-bar evidence | Models evaluate continuously, event-driven (extended Q8.3), but each read stays valid for **3 closed execution bars** (configurable, in bars or seconds). The course's reads stack within a few candles (E4 A+: 2–3 candles; E5 example 1: 2–3 candles). |
| G0.3 | Trigger timing | **Baseline = closed bar** (course: "wait for the 500-tick candle to close", E3). Intrabar mode is computed in parallel as a **shadow signal** (logged as "would-enter intrabar at t"), not traded, until research shows it helps. |
| G0.4 | Setup validity | The strategy owns idea validity (zone retired when `lastTjl2` changes, trend flip, session end). The execution layer owns only the **attempt**: after a Go, the order must be submitted within the same bar; an unfilled limit is cancelled after **3 bars**. Cancelling an attempt never invalidates the idea. |
| G0.5 | Location sources | V1 locations: **LVN, VAH/VAL, POC (session and prior-session), VWAP, and the idea's own structure zone**. The structure zone is objective already (it comes from the market-structure feature), which satisfies original Q1.3/Q5.1's "objective" condition. Session high/low: target only. |
| G0.6 | HVN | HVNs are **targets and blockers**, never entry locations. Extended Q5.4 is amended accordingly; original Q10.2 and Q11.1 stand. |
| G0.7 | Time exit | Setting exists, **disabled** in V1 (original Q9.4). |
| G0.8 | DOM data | **Resolved by the facts in §1.** Heatmap research uses the existing 1-second liquidity snapshots (window widened to ±250, A7). Event-level consumption-vs-pulling is approximated (§7, G4.3). |
| G0.9 | Raw ticks | **Resolved:** raw trades are already stored (A1). Burst timing, the price-response window and timestamp alignment all work on the raw journal. |
| G0.10 | Partials | The position-management module exists; partials and breakeven are **off** in V1 (1 contract). |
| G0.11 | Re-entry | Max **3 entries per zone per session** (A4). Each needs a fresh +2. After a stop-out, the zone stays eligible. A breakeven exit can't happen in V1. Plus a session cap (§9). |
| G0.12 | Delta | Delta flip and absorption completion use **bar delta** of the execution bar. The windowed Delta Profile (30 min) drives "delta shift" (G17). Cumulative delta is logged only. |
| G0.13 | Price response | Keep **both** MFE and end-of-window price (extended Q3.2). Original Q3.2's "ticks within a time window" is the threshold applied to them (§3). |

---

## 3. Footprint, aggression and big trades — numbers

| Item | Decision | Course basis |
|---|---|---|
| Execution bar | **500-tick** bars | E3 |
| Structure timeframes | 1h main, 30m refine (strategy's market structure); HTF bias 4h | E3, E8 |
| Diagonal imbalance | **300 %**. Bullish at P: `ask(P) ≥ 3 × bid(P−1 row)`. Bearish at P: `bid(P) ≥ 3 × ask(P+1 row)`. **Verify** against MotiveWave's own highlighting on one recorded bar (S1; the course never states the direction). | E5, E8 |
| Footprint row grouping | **6 ticks per row** (alternatives 7, 10) | E5 ("keep above 5: 6, 7, 10"), E8 |
| Imbalance minimum volume | **Dominant side ≥ 3 contracts**. A zero denominator counts as an imbalance if the dominant side ≥ 3. | course silent; extended Q2.7 baseline |
| Stacked imbalance | **≥ 3 consecutive configured rows**, same direction | E5 |
| Imbalance count per bar (G18) | Count bullish and bearish imbalances per bar. "Candle dominance" = ≥ 3 imbalances on one side and ≤ 1 on the other. | E5 ("many bullish imbalances = buyers dominating the candle") |
| Volume cluster (G18) | Highest-volume row in each bar (in-candle POC), recorded | E5 |
| Finished auction (S2, G22 corrected) | At the bar **high**: top row **bid volume = 0** and ask > 0. At the bar **low**: bottom row **ask volume = 0** and bid > 0. Strict zero. No extra opposite-side minimum. | E5 numbers "0 \| 3 at the high", "9 \| 0" (bid \| ask). This **matches** the standard convention: a one-sided print at an extreme = finished. **Correction to gaps G22:** the course's *numbers* are consistent; only the speaker's verbal explanation of "9 \| 0" is muddled. |
| Unfinished auction | Both sides > 0 on the extreme row | E5 |
| Big trade | **≥ 30 contracts per aggressor order** (sum of fills sharing one `aggExchOrderId`). Raw-print mode is logged alongside. | E3 (30), E1/E4 (40–50 = institutional); a bubble = "contracts in a single transaction" (E3) |
| Small-player prints (G16) | Aggressor orders of **10–29 contracts** | E3 (lowering to 10–20 shows smaller players) |
| Session threshold | Fixed 30 in V1. Alternative config: 40 during NY RTH. Session label logged on every print. | E4 ("raise when volume is high") |
| Aggression burst | Same side, ≤ **2 s** apart, within **3 ticks**, AND mode | course silent |
| Aggression proximity to a location | Inside the location, or ≤ **5 ticks** from its edge | course silent |
| Aggression success / failure | Window = **until the close of the next execution bar, capped at 120 s**. **Success**: MFE ≥ **10 ticks** in the aggression direction. **Failure**: MFE < **5 ticks** and the window ends at or beyond the print price against the aggressor. Anything else is inconclusive. | E1, E3, E5 judge by "the candle closes up / price can't go higher" |
| Wall-break (extended Q3.6) | Penetration = first trade beyond the wall. Confirmed break = **≥ 5 ticks beyond for ≥ 10 s**. | course silent |

---

## 4. Absorption, exhaustion, delta flip, delta shift — definitions

| Read | Definition (V1) | Course basis |
|---|---|---|
| **Failed aggression** | A big trade or burst at a location whose response is "failure" (§3) | E1, E3 |
| **Absorption (footprint)** (A2) | At a location, on one execution bar: \|bar delta\| ≥ **1.5 ×** the average \|delta\| of the last 20 bars, **and** the bar closes against the delta sign, with **close location ≤ 30 %** of range (for positive delta) or ≥ 70 % (for negative delta). | E5 ("buyers push hard, delta positive, but the candle closes negative"; "big candles that close far from the extreme") |
| ~~Confirmed absorption~~ | **Dropped (A2).** Resting size at the level (± 2 ticks) and its ratio to the ±20-tick median are **logged as context only**, so research can later test whether they cut false signals. | — |
| Multi-bar absorption (original Q4.3) | Counts if the signature appears on ≥ 2 of the last 3 bars at the same location | E4 (repeated attempts) |
| **Absorption completion** | Delta flip (below) after an absorption read | extended Q4.5 |
| **Delta flip** | Execution-bar delta changes sign versus the previous bar **and** the bar fails to make a new extreme beyond the previous bar in the old direction | E1, E5 |
| **Exhaustion** | Over the last **3** bars at the extreme, the largest aggressive row volume in the old direction declines on ≥ 2 consecutive bars while price still prints the extreme. Or a finished auction appears at that extreme. | E4, E5 ("12/34 → 1\|2 → 0\|5") |
| Exhaustion life | Valid for 3 bars; superseded by new qualifying aggression in the old direction | extended Q4.15 |
| **Delta shift** (G17) | The 30-minute Delta Profile (by price) marks the price band holding ≥ 60 % of the window's opposite-side delta. A delta shift = an execution bar closes beyond that band in the idea direction. | E5 ("when price moves below the seller-dominance area… delta shift") |
| Close location (G42) | `(close − low) / (high − low)` on every bar, logged; used by absorption and M3 | E3, E5 |
| Time at level (G41) | Seconds spent within ± 5 ticks of a location in the last 60 min; logged, used as a supporting read (≥ 20 min = "price stayed long") | E4, E5 |
| Trapped small players (G16) | ≥ 3 small-player prints against the current move within 5 bars, each followed by price continuing ≥ 5 ticks the other way. Supporting read for the move's continuation; also feeds the momentum filter. | E3 |

Reads used for model scoring and their independence (R4): **{failed aggression, absorption}** count as **one** evidence
group. **{exhaustion, finished auction}** is one group. **{delta flip, delta shift}** is one group. **{stacked imbalance,
candle dominance}** is one group. A model's "≥ 1 read" means ≥ 1 group, and "two reads" means two different groups.

---

## 5. Entry models (Q6.48–Q6.50, A3)

**Score meaning (extended Q6.2, the X4 gap), the same for every model:**
- **+2** = every mandatory condition including the closed trigger bar (full setup).
- **+1** = location + ≥ 1 read, no trigger yet.
- **0** = location present, no reads.
- **−1** = a read against the idea at the location.
- **−2** = the model's own invalidation fired, or the opposite-direction full setup exists.
- **NO_SIGNAL** = the location is not present. **NO_DATA** = inputs are missing.

All models use the idea's direction; they confirm or reject it. Stop = model reference + **5-tick** buffer (S9, course
"slightly beyond").

| Model | Location (mandatory) | +2 trigger (mandatory, closed bar) | Stop reference | Model invalidation (−2) |
|---|---|---|---|---|
| **M1 LVN rejection** (E3) | Qualified LVN overlapping the idea zone or ≤ 10 ticks from it; price touched it | Bar closes back **out of the LVN** on the idea side, with ≥ 1 read, **and not within 3 ticks short of the session POC** (G43) | Far edge of the LVN | Bar closes ≥ 3 ticks beyond the LVN's far edge (traversal, G20) |
| **M2 VA counter-trade** (E2, E3) | Price beyond VAH (short) / VAL (long) of the active session profile; session or prior session shape = D | Bar closes **back inside the value area** after a failed-breakout read (failed aggression or absorption) | Excursion extreme beyond VAH/VAL | Two consecutive bars close beyond the VA edge with stacked imbalance in the breakout direction (acceptance) |
| **M3 Failed aggression at a zone** (E3, E5) | Idea zone, VA edge or LVN | Failed aggression against the idea, then a bar closing in the idea direction with **close location ≥ 70 %** (≤ 30 % for shorts) **and** (finished auction at the extreme **or** delta flip). **No limit entry inside the zone** (S9). | Extreme of the failed move | Aggression against the idea succeeds (§3) |
| **M4 Exhaustion → stacked imbalance (A+)** (E4) | VWAP ≤ 10 ticks, or a VA edge | ≥ 2 finished auctions (or an exhaustion read) at the same extreme within 5 bars, **then** a bar printing a stacked imbalance in the idea direction with bar delta in the idea direction | Sequence extreme | A new extreme beyond the sequence extreme by ≥ 3 ticks |
| **M5 Delta flip + retest** (E1, E5) | Any V1 location | ≥ 3 bars of same-sign delta against the idea, a 2-bar stall (no new extreme), a delta flip with ≥ 1 imbalance in the idea direction, **then** a micro break of structure (G12) and a retest bar rejecting the broken level | Retest extreme | A bar closes back beyond the broken level by ≥ 3 ticks |
| **M6 Trapped traders at LVN + VWAP** (E5) | LVN ≤ 10 ticks from VWAP, with VWAP on the idea's adverse side (VWAP above price for a short) | ≥ 2 bars that move against the idea but close in its direction, then a finished auction at the extreme **and** bar delta in the idea direction | Trap extreme | A bar closes beyond VWAP against the idea with delta against it |
| **M7 Profile-shape failure** (E6) | Developing session shape **P (for shorts) / b (for longs)** while HTF bias is **not** trending in the shape's direction | Two bars rejected at the profile's upper (lower) HVN, then delta flip or stacked imbalance in the idea direction | Rejection extreme | The developing profile's POC migrates ≥ 10 ticks further in the shape's direction |
| **M8 Business-area return** (E2, A3) | The prior completed **NY-session POC** (09:00–13:00 ET) or another prior-session POC ≤ 10 ticks from the idea zone; first approach of the session | Rejection bar closing away from the POC on the idea side, with ≥ 1 read | The POC | Two bars close beyond the POC on the far side |

Q6.50: every model is in V1 because every read it needs is now defined (§4). Prerequisites and vetoes (Q6.49): a model's
**−2 acts as a veto** in aggregation (§6). No other veto.

**Micro structure (G12)**: execution-bar swing highs/lows by 2-bar fractal. **Break** = a close beyond the last opposite
swing. **Retest** = price returns to ≤ 5 ticks of the broken level within 10 bars.

---

## 6. Aggregation, lifecycle, order entry (state machine, kept minimal)

**Aggregation (V1 default):** **Go** = at least one model is at **+2** and no model is at **−2**. Everything else is
No-Go. A weighted-score mode exists in config but is off. The "more models = better" hypothesis (Q0.1) is tested from the
logs (score vector per evaluation), not encoded.

**State machine.** Three small machines, no more:

```
IDEA      ACTIVE ──(strategy invalidates / zone changes / session end)──► CLOSED(reason)
ATTEMPT   WATCHING ─Go + risk OK─► SUBMITTED ─fill─► FILLED (→ position mgmt)
             ▲                        │
             └──── cancel / reject / Go lost before fill (market orders: n/a) ◄┘
POSITION  OPEN ──(stop / target / flatten / position-mgmt exit)──► CLOSED
```

- One ATTEMPT at a time per idea. An idea can produce up to 3 FILLED attempts per zone per session (A4).
- **1 contract**, so no partial-fill states. If a partial ever appears, log it and let the existing tracker handle it
  (X8.1-D/E/G become log-only).
- Pre-submission Go recheck (X8.1-B) is kept: it's one line.

**Order entry:**
- **Market order** at the trigger-bar close (course: "enter at the close").
- **Limit-on-retest** only for **M5**, as a configurable mode, off by default.
- Unfilled limit: cancelled after 3 bars.

**Position management V1:**
- Bracket with stop and target. No early exit except the existing session-end flatten and the kill switch.
- Opposing idea while in a position: ignore and log.
- Same-direction idea while in a position: ignore and log (no adding).

---

## 7. Locations, profiles, sessions, VWAP

| Item | Decision | Course basis |
|---|---|---|
| Session times (Q5.22, R7, S8) | Defined in the financial centre's local time, so daylight saving is handled. **Asia** from 09:00 Asia/Tokyo (= 05:30 IST all year). **London** from 08:00 Europe/London (= 12:30 IST in summer). **NY** 09:00–13:00 America/New_York (= 18:30–22:30 IST in summer). Trading day starts 17:00 America/Chicago (CME). | E2, E3, E6 (times given in IST in a summer month: the E6 examples are in June) |
| Profiles kept | Developing session (Asia/London/NY), developing day, developing week (from Sunday 17:00 CT), plus **completed** previous Asia, London, NY session and previous day. Each POC carries a "naked until revisited" flag (G9). | E2, E6 |
| Minimum data (G44) | A session profile is usable after **30 min and ≥ 2,000 contracts**. Until then models use the previous session's completed profile. | E6 (London too thin, so start from Asia) |
| Profile rows | **4 ticks per row** | E4 ("tick interval 4"); E6 (3 or 5) |
| Value area | **70 %**, expanding from the POC by the higher adjacent pair of rows; ties go up | E2, E6 |
| HVN | Local maximum ≥ 1.3 × the profile's mean row volume | course: "high volume = accepted" |
| LVN | Local minimum ≤ **0.35 ×** mean row volume, **between two HVNs**, width ≥ **2 rows** (8 ticks). Contiguous qualifying rows are merged; one non-qualifying row may be bridged. | E1, E3, E6 ("deeper LVN = stronger") |
| LVN rank | score = 0.6 × depth (1 − vol/mean, normalised) + 0.4 × width (normalised); 5-update moving average | course: "bigger gap = more unfair" |
| LVN age / touches (G19) | Recorded; no filter in V1 | E3 (3 entries at the same gap) |
| Touch states (Q5.9) | **Approaching** ≤ 5 ticks outside; **touching** = a trade inside the range; **traversed** = a close ≥ 3 ticks beyond the far edge | E3 ("price enters the LVN") |
| Max distance from a location for a read (original Q5.7) | 10 ticks | course silent |
| Profile shape (G14, original Q5.5) | Computed on the developing session profile after 60 min. **D**: POC in the middle third and a single peak. **P**: POC in the upper third and ≥ 60 % of volume above the midpoint. **b**: mirror of P. **B**: two HVN peaks (the smaller ≥ 0.7 × the larger) separated by an LVN (≤ 0.5 × the smaller). Used by M2, M7 and the P-shape filter. | E6 |
| Day type (G14) | **Balanced** = session shape D or B, and price inside the prior session's value area for ≥ 50 % of the session. Otherwise **trending**. Logged on every evaluation; gates M2. | E2 ("apply when the market is consolidating"), E6 |
| VWAP (G23) | Trade-based, resets at **17:00 CT**, no bands, continuous across the daily halt (the halt is outside the trading day) | E3, E4 ("one day") |
| Unfinished auction (G10) | Unfinished extremes at execution-bar swing points are kept for 1 session as **low-priority target candidates**. A revisit is never assumed (S6). | E5 |
| Contract roll (G26) | Read and trade the contract @GC points to. On a roll, profiles, VWAP and prior-session levels from the old contract are dropped; the weekly profile restarts. | E4 (@GC, not MGC) |

---

## 8. System integration (G3, G28, G29, G30, A6)

**Mapping onto existing FLOW_V2 (G3):**

| Review concept | Existing component | Action |
|---|---|---|
| Risk-management module | `RiskChain` | **Extend.** Add filters: min R:R, max stop, zone/session trade limits, chase, P-shape. No new module. |
| Position management, order lifecycle, reconciliation, idempotency, cancel races (X6.32/33, X9.9–X9.18) | `exec/`, `LiveOrderTracker`, `OrderGateway`, position-mismatch detection, kill switch | **Exists.** Keep as is. |
| Central severity policy (X9.26, X9.33) | `AlertPolicy` (ALERT/WARN/INFO prefix table) | **Exists.** SEVERE = ALERT, WARNING = WARN, INFO = INFO. |
| Telegram, retries, outage handling (X9.19–X9.24, X9.38) | `alerts.log` → `ops/watchdog.py` → Telegram, independent of trading | **Exists.** Trading safety never depends on Telegram. |
| Policy fails to load (X9.34) | `AlertPolicy` is compiled in | **Moot.** |
| Unclassified condition (X9.27–X9.32) | An unknown prefix is not alerted | **Small add:** any log line at ERROR level from the order path that matches no prefix is alerted as `UNCLASSIFIED_*`. Nothing more. |
| Incident parent/child, classification history, recovery checklists (X9.30, X9.36, X9.37) | — | **Dropped as over-engineering** for a 1-contract Sim research machine. |
| Append-only audit trail (X9.14, X9.15) | `decisions.jsonl` (kept forever, D-15) plus the raw journal | **Exists.** Add the new evaluation records to the same journal. |
| Late/corrected market events, replay (X4.24–X4.33) | `ReplayHarness`, `RawEventCodec` | **Reduced.** No live correction handling (the feed gives no correction events we know of). Replay is offline research only, isolated from live by construction. |
| Missing/stale data (X4.20–X4.23) | Readiness, feed watchdog (F-19) | Models return **NO_DATA** when an input feature isn't ready. Existing feed blocking stays. |

**Layers (G28):**
- New reads and levels are **Features** in `flow-core/.../flow/`: imbalance, auction state, delta flip, exhaustion,
  absorption, profile levels, LVN rank, shape, walls, micro structure. They are exposed through `MarketState` accessors.
- One new `FlowStrategy`, **`OrderFlowExecutionStrategy`**, contains the idea source (today's market-structure zone logic),
  the M1–M8 evaluators, the aggregator and the SL/TP selector. It emits the existing `Intent`.
- Models are plain classes inside `flow-core`, SDK-free. No new layer, and no framework for "multiple strategies" until a
  second one exists.

**Idea interface (G29):**
- A record internal to `flow-core`: `TradeIdea(id, direction, zoneLow, zoneHigh, invalidationPrice, createdMs, source)`.
- `Intent` gains one optional field `ideaId`; the score vector goes into the reason/journal.
- Expiry stays the strategy's: zone change, trend flip, session end.

**Compute (G30):**
- Models evaluate on execution-bar close, and intrabar only when price is ≤ 10 ticks from a location.
- Budget: < 1 ms per evaluation, measured by the existing lag check. Profiles update incrementally (extended Q5.25).

---

## 9. Stops, targets, filters, limits

| Item | Decision | Course basis |
|---|---|---|
| Stop | Model reference (§5) + **5 ticks** | E3, E6, E10 ("slightly beyond, not on it") |
| Max stop | **60 ticks** ($6). If wider: use another structurally valid candidate within the cap, else skip (original Q9.3). | course silent; sized so 200-tick daily loss allows about 3 full losses |
| Target candidates | VWAP, session POC, prior-session POC (naked first), opposite VA edge (S7: a mirror of the course's VAL-for-shorts), HVN, nearest liquidity wall in the trade direction (G21 magnet), unfinished-auction extreme | E2, E3, E6, E7 |
| Target choice | The **nearest** candidate that is ≥ **2.0 R** away. Order of preference for counter-trades: VWAP → POC → opposite VA edge (course). If the nearest structure is < 2 R, skip. | E2, E3 ("minimum 1:2"; "don't trade with the POC in between") |
| POC/HVN blocker (original Q11.1, G39) | Blocked if any active POC, or an HVN ≥ 1.3 × mean, lies between entry and the 2 R point. Follows from "nearest ≥ 2 R" but is checked and logged separately. | E3 |
| Chasing (G39) | Blocked if the entry price is **> 10 ticks** beyond the location edge in the trade direction | E1, E3 ("don't chase, wait for retest/close") |
| Strong momentum (original Q11.2) | The last 3 execution bars closed in the same direction against the idea, each with same-sign delta ≥ its 20-bar average, combined range ≥ 2 × the average 3-bar range. A counter entry is blocked unless the trigger bar has opposite-sign delta (which ends the streak). | E1, E3 ("falling knife / flying arrow") |
| "No valid location" | NO_SIGNAL from every model → no Go (automatic) | E3, E5 |
| Bar closes just short of the POC (G43) | **Hard filter for M1/M2/M3**: a trigger bar closing within 3 ticks before the session POC → skip | E3 |
| P-shape longs (G43, S5) | **Hard filter**: no long idea from M2/M7 while the developing session shape is P and HTF bias is not up | E6 ("always a trap" outside trends) |
| Finished auction alone | Can never produce +2 by itself (it's one read group with exhaustion) | E4, E5 |
| Heatmap clutter filter | Computed and logged, **not** blocking in V1 | E7; original Q11.1 defers |
| Blackouts | Existing session-end flatten / halt window only. No news calendar in V1. | course silent |
| Trade limits | 3 per zone per session (A4); **6 per session**; no loss cooldown (the course has none; the 200-tick daily loss limit covers it) | E3 |
| Sizing (G24) | Fixed 1 contract | course: "minimum size on counter-trades" |
| Partials / breakeven | Off in V1 | E6 SL-CTC deferred |
| Time stop | Off in V1 | original Q9.4 |

---

## 10. Heatmap and sweep (G21, S4, pending Q12.3/12.4)

All of this is computed from the 1-second liquidity snapshots and logged. In V1 only the target candidate (magnet), the
stop-behind-wall rule and the sweep/gap reads use it (absorption no longer does, A2).

| Item | Decision | Course basis |
|---|---|---|
| Bot filter | Ignore levels < 5 contracts in wall maths | E7 (bot remover threshold) |
| Wall | Sum of resting size across 3 adjacent ticks ≥ **100 contracts**, **or** ≥ 4 × the median 3-tick sum in the window; persisting ≥ **30 s** | E7 ("Gold: 100–150 lots is already large") |
| Stacking / pulling | Wall size up / down ≥ 30 % over 10 s | E7 |
| Pool vs garbage | Pool = a wall. Garbage = no wall within ± 20 ticks of price. "Inside clutter" is logged. | E7 |
| Magnet | Nearest wall in the trade direction = target candidate | E7 |
| Stop behind a wall | If a wall sits ≤ 10 ticks beyond the structural stop, the stop moves to beyond the wall + 5 ticks, as long as the max stop allows | E7 ("liquidity tells you where to place the SL") |
| Iceberg | Existing order-id repeat, **or** traded volume at a level > 2 × its displayed size within 30 s | E1, E7 |
| Spoof | A wall loses ≥ 80 % within 10 s while price is ≤ 5 ticks away and < 30 % of the loss was traded | E1, E7 |
| Consumption vs pulling (G4.3) | Per second: size lost at a level is classed as traded up to the traded volume at that price in that second (from the raw journal); the rest counts as pulled | — |
| **Sweep (S4, Q12.3 → in V1, Q12.4)** | Two named events. **SWEEP_REVERSAL** = ≥ 70 % of a wall traded, price ≥ 3 ticks through it, then a close back on the original side within 2 bars (the course's main definition, E7 line 1957). **BREAKTHROUGH** = ≥ 70 % traded and price continues ≥ 10 ticks. Plus **GAP** = wall keeps ≥ 50 % under aggression and price reverses ≥ 5 ticks away. Supporting reads in the {failed aggression, absorption} group. | E7 |
| Gamma | Deferred (no source) | E10 |

---

## 11. Data, research method, validation

| Item | Decision |
|---|---|
| Retention (G2g, A7) | `dataKeepTradingDays` **30**; liquidity window **±250 ticks**; raw journals and all system logs kept (A1). D-88 is updated to say this. |
| Clock (G27) | Market logic uses **exchange time** (`eventTimeMs`). Add an NTP sync check on the VM to the runbook. The observed 1.9 s offset gets a finding entry. |
| Point-in-time (G33) | Every evaluation record stores the levels and reads as they were at that moment. Research never recomputes from end-of-session profiles. |
| Backtest path (G35) | Order-flow research = **replay of our raw journals plus liquidity snapshots** through `ReplayHarness`. The 1-minute OHLC engine is not used for order flow. If Rithmic historical ticks turn up, they feed the same replay (footprint only; no DOM). |
| Costs (G34) | **$5 per round turn** (commission + fees). Market fills: 1 tick slippage. Limit fills only when price trades **1 tick through** the limit. |
| Overfitting (G8) | Walk-forward. Calibrate on the first 10 trading days of a block, **freeze**, test on the next 10. At most **20 parameter sets per model per round**. Acceptance criteria fixed before the test block starts. |
| Sample size (G32) | V1 breakdowns: per model × direction. Per score level, session and day-type only once a cell has ≥ 30 events. |
| Acceptance (original Q13.1) | Before a rule trades unattended on Sim: ≥ **20 trading days** forward, ≥ **30 trades** for the rule, **expectancy > 0 after costs**, profit factor ≥ **1.3**, max drawdown ≤ **400 ticks** (2 × daily limit). You decide promotion. |
| Signal log (original Q13.2) | One record per evaluation change: ids (idea, evaluation, attempt, order), exchange time, direction, every model's score/status and reads, the levels, the filters with reasons, Go/No-Go, and later MFE/MAE at 5/15/60 min and the hypothetical 2 R outcome. Kept forever. |
| Visualisation (G31) | The `analysis/` viewer gets an overlay of ideas, model scores, levels and walls. A todo, built with the first models. |

---

## 12. Document housekeeping (G1, G2b, G2c, G2e, G2f, G36c, G37, G38)

- **G1 remainder.** Restore v1's Q12.3 (structured heatmap features); §10 here implements it. v1's richer Q12.2 is
  superseded by §10.
- **G2b/G2c.** Build `orderFlowRules_reviewed_v3.md` as one consolidated file. One row per question; the extended set
  renamed **X**-prefixed (X4.10…, X5.11…, X9.1…); a crosswalk table; duplicates removed; preserved course sections marked
  historical. Built once you've reviewed this file.
- **G2e.** Done (committed 2026-10-11).
- **G2f.** Extended decisions count as **approved-provisional**, except those superseded here (A2, A6, §2).
- **G36c.** Rebuild pass 2 from `distillation_1_v2` as `distillation_2_v2` (todo; not needed before v3, since this file
  already used the raw sweep).
- **G37.** The finished-auction convention is settled by the course's numbers plus the standard convention (§3). There
  is nothing left that needs the Hindi original.
- **G38.** Leave `docs/video_transcripts/strategies/` alone; it is git-ignored and empty.

## 13. Platform checks still to run (E items, not decisions)

1. Imbalance direction: compare our formula with MotiveWave's highlighted cells on one recorded bar (S1).
2. Finished-auction side: compare our top/bottom-row rule with the MotiveWave footprint on one recorded bar (§3).
3. Depth levels the SDK delivers for GC on the **VM** (Level 2 entitlement there; G4.7).
4. Whether Rithmic sends trade-correction events (G4.5). If it does, log them; no handling in V1.

## 14. Corrections to the gaps file (it stays unedited)

- **G22** said the course's finished-auction mapping is self-contradictory. Its **numbers** are consistent and match the
  standard convention; only the speaker's spoken explanation of "9 | 0" is confused. Decision in §3.
- **G0.8/G0.9/G5/G25** assumed DOM and raw ticks are not stored. In fact raw ticks plus top-of-book are stored forever,
  and full depth is stored as 1-second snapshots (§1). The data gaps are much smaller than the gaps file says.
