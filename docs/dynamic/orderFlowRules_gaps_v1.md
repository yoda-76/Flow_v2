# Order-flow rules — things we missed (gaps audit v1)

**Built:** 2026-10-11. Audit of everything from the transcript distillations to the review file.
**Updated:** 2026-10-11 against the complete review file `orderFlowRules_reviewed_v2.md` (complete version, 3,366 lines). That file is
the earlier v2 plus an appended **"original-source decision log"** answering every original Q1.1–Q13.3, plus an updated
decision table.
**Scope:** only things **not** in the review, not pending in it, or that the review leaves inconsistent.
**Status:** every item is OPEN; nothing here is a decision. Destination: **R** = next review file, **T** = `todo.md`,
**E** = platform experiment in `../motivewave` (a question about MotiveWave/Rithmic, not our system).

Sources checked: `distillation_1/1–10.txt`, `distillation_2/entry_model_rules.txt`, `orderFlowRulesRedefinition.md`,
`orderFlowRules_master_review_additions.md`, `orderFlowRules_toBeReviewed_v1.md` / `_v2.md`, `orderFlowRules_reviewed_v1.md`,
`orderFlowRules_reviewed_v2.md`, and for the system side `decisions.md`, `CLAUDE.md`, `ops/watchdog.py`.

## What changed in this update

| Item | Change |
|---|---|
| G1 (v1 decisions lost) | **Mostly closed.** All original Q9–Q13 now have decisions again. But they were re-decided, not restored, so some now disagree with the older ones → see **G0**. The old Q12.3 "structured heatmap features" decision is still absent. |
| G7b (HVN as target) | Original Q10.2 now allows HVN targets → becomes a **conflict** with extended Q5.4 (G0.6). |
| G14 (regime) | Partly covered: profile shape is now recorded for research (original Q5.5). Day-type gating is still missing. |
| G17 (Delta Profile) | Partly covered: original Q4.4 adds the windowed Delta Profile. The "delta shift" event and the use of cumulative delta are still missing. |
| G19 (freshness) | Partly covered: original Q5.3 adds LVN age. Touch count is still missing. |
| G24 (sizing/partials) | Partials deferred (original Q10.3), so the 1-contract conflict is gone. Sizing is still undecided. |
| G5 / G25 (data) | **Sharper.** Original Q13.3 decides *no raw tick and no DOM storage*, which collides with the heatmap and absorption decisions (G0.8, G0.9). |
| New | **G0** (two decision sets conflict), G2f (status of the "extended" decisions), G2g (D-88 retention change), G39 (feasibility of the original Q11.1 filters). |

---

## Summary — the ones that matter most

| # | Gap | Why it matters |
|---|---|---|
| G0 | The file now holds **two decision sets for the same question IDs** (the earlier "extended" set and the new "original-source" set), and several **directly contradict** | an implementer can't tell which rule is approved |
| G2d | The review's "sole source of truth" rule conflicts with CLAUDE.md (`decisions.md` is authoritative) | two documents claim authority |
| G0.8 | **No DOM storage** (original Q13.3), while the heatmap is in V1 and absorption *requires* DOM evidence | DOM rules can never be researched on past data, only watched live |
| G3 | Alerting, audit, incidents and risk are designed from scratch, **not mapped to existing FLOW_V2** (RiskChain, kill switch, `AlertSink`, watchdog → Telegram, D-115) | duplicate or conflicting designs |
| G4 | Decisions assume **unverified feed capabilities** | rules may be unimplementable as written |
| G6 | Footprint-only absorption signature dropped | absorption can never be backtested, even if historical ticks arrive |
| G7a | **POC-revisit / Gold Extra Edge** model never became a question | a whole course model is missing |
| G8 | No guard against **overfitting** many configurable parameters on little data | false confidence |

---

## G0. Conflicts between the two decision sets (R, first thing to settle)

The appended log says it covers "the original `orderFlowRulesRedefinition.md` only; extended questions remain deferred".
The earlier sections answered the same IDs differently. Pairs that disagree:

| # | Topic | Earlier ("extended") decision | New ("original-source") decision | Conflict |
|---|---|---|---|---|
| G0.1 | **Confirmation minimum** (Q8.1) | Aggregation layer gives Go/No-Go; **no universal confirmation count** | **Location + trigger bar + at least one read** required | Direct: a universal minimum vs none |
| G0.2 | **Cross-bar evidence** (Q8.3) | Continuous, event-driven; **bar boundaries don't limit evidence** | **Configurable cross-bar window** in bars or time | Different model of evidence lifetime |
| G0.3 | **Trigger timing** (Q7.1) | Closed-bar **baseline**; intrabar as a separate research mode | **Option B**: entries before close allowed under defined conditions | Which is the baseline? |
| G0.4 | **Setup validity** (Q7.3, Q-L3) | Strategy alone owns expiry/invalidation; execution engine doesn't judge validity | Configurable validity window in bars/seconds, strategy invalidation takes precedence | Who owns the window: strategy or execution layer? |
| G0.5 | **Location sources** (Q5.1, Q1.3) | VP, VWAP, HTF structure, session extremes all configurable sources | **Start profile-only**; VWAP as context/target; HTF zones only once objectively defined and proven | Narrower; earlier "HTF as a source" and session extremes (extended Q5.8) are in doubt |
| G0.6 | **HVN** (Q5.4 vs Q10.2) | HVNs informational only, **must not affect execution** | HVN is an **eligible target**, and "POC/HVN between entry and target" is a **hard filter** (Q11.1) | Direct |
| G0.7 | **Time exit** (Q9.4) | Optional configurable time-based exit, model-specific durations | **No time-based stop in v1** | Compatible only if "optional" means disabled; say so |
| G0.8 | **DOM data** (Q13.3 vs Q0.4, Q4.2, Q12.1) | Heatmap is core in V1; absorption needs full DOM plus footprint; timestamped liquidity events preserved | **DOM logs are not stored** | Heatmap/absorption research has no data; Q12.1's own "confirm historical depth" note is unanswered |
| G0.9 | **Raw ticks** (Q13.3 vs Q3.3, Q3.6, Q4.3, Q8.1-E) | Burst grouping by time between prints; consumption vs pulling; DOM/trade timestamp alignment; first-fill time thresholds | **No raw tick storage**; 1-second construct snapshots only | Those decisions can't be researched from 1-second snapshots |
| G0.10 | **Partials** (Q10.3) | Position-management module controls partials/stop moves dynamically | **Defer** partials and breakeven in v1 | Fine if the module exists but is disabled; say so |
| G0.11 | **Re-entry** (Q10.4) | Separate handling per exit type (stop / breakeven / partial) | Limits by zone and session, start at one attempt | Complementary, but "does breakeven count" is open in one and decided by exit type in the other |
| G0.12 | **Absorption delta** (Q4.4) | Bar delta is the initial hypothesis for absorption completion; cumulative not in scope there | Bar, cumulative and Delta Profile as alternatives for delta-flip detection | Different scope; merge into one statement |
| G0.13 | **Price-response metric** (Q3.2) | MFE **and** end-of-window price, intrabar continuous | Ticks of progress within a time window | Probably compatible; state that MFE is kept |

*Decide:* which set wins per row, or write a merged rule. Then mark the loser superseded (don't delete it, for audit).

---

## A. Document integrity

**G1 — Lost v1 decisions: mostly closed, one remaining. (R)**
- Original Q9–Q13 now have decisions (re-decided, see G0).
- Still absent from the file: v1's **Q12.3** (keep the visual heatmap *and* expose structured, timestamped DOM liquidity
  features to models; preserve appearance, change, persistence and disappearance events).
- v1's richer **Q12.2** (multi-factor wall: size, persistence, replenishment, proximity, stacking/pulling) became
  "size and persistence" in the new Q12.2. Decide whether replenishment and pulling are dropped.
- v1's **Q13.3** wording ("start order-flow backtesting straight away if historical data is obtained") is replaced. The
  2026-10-11 todo for checking Rithmic historical ticks is still consistent with the new Q13.3.

**G2b — ID collisions. (R)**
- Each original ID now has an extended decision *and* an original-source decision.
- Q9.1–Q9.38 mix stop/target with incident/alerting.
- "Submitted Q4.2/4.3/4.6" collide; Q5.8 is used twice; Q3.5-A/B differ from `toBeReviewed_v2`.

*Fix:* one crosswalk table (ID → set → meaning → status), and give the extended set its own prefix (e.g. `X4.10`).

**G2c — Review log not updated. (R)**
- The decision table at ~line 1752 is updated.
- The **review log at the top** (line 63 on) still lacks every original-source decision and the extended Q2.4/2.6/2.7
  follow-ups, Q3 continuation, Q4.9–Q4.33, Q5.11–Q5.34 and Q9.1–Q9.38.
- Q5.17–Q5.19 are recorded twice.
- Preserved course sections still show resolved questions as ⚠️ open.

**G2d — Governance: two "sources of truth". (R + T)**
The review calls itself the sole source of truth; CLAUDE.md says `decisions.md` is authoritative. *Decide* how they link.

**G2e — File housekeeping. (closed 2026-10-11)**
The complete file replaced the older 3,073-line `orderFlowRules_reviewed_v2.md` and was committed.

**G2f — Status of the "extended" decisions. (R)**
- The appended log says extended questions (explicitly including Q5.23–Q5.34) "are deferred until explicitly requested".
- But the file already records *decisions* for many of them: Q4.10–Q4.33, Q5.11–Q5.34, Q8.1-B…H, and Q9.1–Q9.38
  (incidents/Telegram).
- Are those approved, provisional, or parked? An implementer can't tell.

**G2g — Data-retention decision changes D-88. (T)**
- New Q13.3: construct snapshots and entry-model logs kept **more than 7 days**.
- `decisions.md` D-88 says `data/` keeps a rolling **7 trading days**, and CLAUDE.md's directory table repeats it.
- One of them needs updating. The retention length ("more than 7 days") is also unspecified.

---

## B. Course content that never became a question

**G7a — POC-revisit / business-area model ("Gold Extra Edge", E2). (R)**
- The course trades returns to the POC: entry at the POC, stop just beyond.
- Gold Extra Edge: build a profile over the NY session (18:30–22:30 IST); the next morning, counter-trade the approach to
  that POC.
- If price opens *at* the POC, expect it to move away.

None of M1–M7 is this model. *Decide:* M8, fold into another model, or drop.

**G9 — Prior-session (completed) profile levels. (R)**
- The course carries yesterday's NY POC and value area into today.
- Original Q5.2 (session-dependent anchors) and extended Q5.12 don't distinguish *developing* from *completed* prior
  profiles, nor "naked" (not yet revisited) prior POCs.
- Models referencing "the POC" must say which one.

**G10 — Unfinished auction as a revisit target. (R)**
- E5: no zero at an extreme means price tends to revisit it.
- The review only uses *finished* auction (as confirmation).
- Natural input for the target and position-management modules.

**G11 / G6 — Footprint-only absorption signature. (R)**
- E5: bar delta positive but the bar closes negative, with seller imbalances (and the mirror).
- Extended Q4.1/Q4.2 require DOM for absorption, and original Q13.3 stores no DOM, so a DOM-backed absorption can't be
  researched on past data at all.
- Keep a footprint-only "absorption-like" signal so absorption can be backtested.

**G12 — Break-of-structure + retest on the execution timeframe. (R)**
- Course sequence: absorption → aggression → delta flip → auction finished → **structure break → retest** → entry (E1).
- Original Q7.2 now has a "limit-on-retest" mode, but neither "structure break" nor "retest" is defined.

**G13 — Objective HTF supply/demand zones. (R)**
Original Q1.3/Q5.1 defer HTF zones until "objectively defined". Nobody owns defining them, and M2/M3 need them as their
location. Without them, those two models can only use profile locations.

**G14 — Day-type / regime classifier. (R)**
- Profile shape is now recorded (original Q5.5).
- The course's gating isn't covered:
  - value-area counter-trades only on balanced/consolidation days;
  - P-shapes trap outside trend days;
  - the big move comes after balance, at the NY open.
- Gamma (the course's other regime tool) is deferred and nothing replaces it.

**G15 — Session participation as a model input. (R)**
- Original Q5.6 makes trading-session windows configurable.
- Session/time-of-day is not an *input* to models or to the big-trade threshold. Course: RTH has the big participants and
  tick bars print much faster in NY.
- A fixed 500-tick bar (original Q1.2) means very different durations by session.

**G16 — Small-trade (retail) aggression as evidence. (R)**
- E3: with the threshold lowered to 10–20, repeatedly failing retail buys in a falling market are the course's "falling
  knife" evidence.
- The original Q11.2 momentum definition (price plus delta) doesn't include it.

**G17 — "Delta shift" and cumulative delta use. (R)**
- Original Q4.4 adds the windowed Delta Profile as a delta-flip input.
- The course's **delta shift** (price moving below the area where sellers dominated) is not an event.
- Cumulative delta has no defined use beyond being an alternative input.

**G18 — Imbalance count per bar and per-candle volume cluster. (R)**
E5/E6 constructs (candle dominance by imbalance count; in-candle POC) are not defined.

**G19 — Touch count. (R)**
- LVN age is now a feature (original Q5.3).
- Number of prior touches/tests is not, for LVNs, VA edges or VWAP. The course re-enters the same gap up to 3 times, and
  walls weaken with tests.

**G20 — LVN traversal → fast move to the next HVN. (R)**
Extended Q5.9 tracks "moving through" but doesn't connect it to targets or continuation (E1/E6).

**G21 — Heatmap concepts beyond the wall definition. (R)**
- E7 concepts not covered: liquidity **pool vs garbage**; the **"no entry inside clutter"** filter (original Q11.1 defers
  heatmap filters); the **magnet** effect (wall as target); **stop placement behind a wall**; "high executed volume ≠
  liquidity pool"; a **bot-order filter**.
- Not covered either: **iceberg** and **spoof** detection (E1/E7).
- Sweep/gap are pending in `toBeReviewed_v2`.

**G22 — Finished-auction side mapping: the course gives one. (R, evidence for Q2.4)**
- E5 (bid|ask convention): "0 | 3 at the HIGH" means **zero bid** at the high; "9 | 0 at the LOW" means **zero ask** at the
  low. That differs from the common convention (zero *ask* at the high).
- Q2.4 keeps the mapping open pending feed verification. Record this as the claim to test; the translation itself could
  have swapped it.
- The **opposite-side minimum volume** sub-question is noted as open in the new Q2.4. Keep it on the list.

**G23 — VWAP computation. (R)**
Undefined: reset time (CME session open vs midnight), trade-based vs bar-based, behaviour across the daily halt, bands.
VWAP is now a target/context source (original Q5.1, Q10.2), so this needs settling.

**G24 — Position sizing. (R, low)**
- Partials are deferred, so the 1-contract cap no longer conflicts.
- Sizing by setup quality (course: minimum size on counter-trades) is undecided. Fine to leave at 1 contract; record that
  as the decision.

**G39 — The original Q11.1 hard filters need definitions before they can be enforced. (R)** *(new)*
These are hard blocks, but each depends on something still undefined:
- "**chasing**" (no definition; only the Q11.2 momentum policy exists);
- "**POC/HVN between entry and target**" (which profile's POC/HVN — G9; how big an HVN counts — G0.6);
- "**no valid location**" (which location sources count — G0.5).

Until defined, the filters either block nothing or block arbitrarily.

---

## C. Data, platform and feed capabilities

**G4 — Unverified feed capabilities. (E)** These need experiments in `../motivewave/experiments/`:
1. Per-trade aggressor side, and whether the feed is **non-aggregated** (E4 insists on it; affects big-trade sizes).
2. Full depth: number of levels; event updates vs snapshots; price-level vs order-level.
3. Telling **executed consumption from cancellation/pulling** (extended Q3.6). Needs trade-to-depth matching.
4. Exchange timestamps and sequence numbers (extended Q4.3, Q4.24, Q9.17).
5. **Trade correction/cancel events.** If the feed doesn't send them, the extended Q4.24–Q4.33 replay/correction block is
   unnecessary.
6. **Broker order/fill IDs** for dedup and partial-fill linkage (extended Q9.16, Q8.1-E).

**G5 / G0.8 — DOM capture. (R + T)**
- Rithmic has no historical DOM; MotiveWave's heatmap keeps no history (E9); and original Q13.3 now decides not to store
  DOM logs.
- So the heatmap (V1, core) and DOM-backed absorption can only be **observed live**, never researched or backtested.
- *Decide:* store at least a reduced DOM record (e.g. 1-second top-N depth snapshots, or wall-level events) — with storage
  size, retention and backup — or accept DOM rules as live-only and exclude them from Q13.1 acceptance.

**G25 / G0.9 — Raw ticks vs 1-second snapshots. (R)**
- Burst timing (Q3.3), consumption vs pulling (Q3.6), event alignment (Q4.3) and first-fill time thresholds (Q8.1-E) need
  event-level data.
- *Decide:* store raw trades (small: Gold trade volume per day is modest), or change those decisions to 1-second
  approximations.
- If the Rithmic historical-tick check succeeds, that gives past ticks but not *our own* future ones unless we store them.

**G26 — Contract roll and @GC continuity. (R)**
Undefined: how weekly/daily profiles, VWAP, LVNs and structure behave across a roll (price gap between contracts), and
which contract is read vs traded.

**G27 — Clock accuracy. (T)**
Event-time rules assume trustworthy timestamps. VM clock sync (NTP) and exchange-vs-local time aren't checked.

---

## D. System integration (existing FLOW_V2)

**G3 — Map the review onto existing components. (R + T)**
- Extended Q9.14–Q9.38 and Q6.46/Q6.47 design incidents, severities, Telegram, audit trail, idempotency and
  reconciliation from scratch.
- FLOW_V2 already has:
  - the RiskChain (armed / session / readiness / daily-loss / size-cap / rate-limit / churn / lag);
  - kill switch and flatten;
  - `LiveOrderTracker` and `OrderGateway`;
  - the D-115 account guard;
  - the journal;
  - `AlertSink` and `logs/alerts.log`;
  - `ops/watchdog.py` → Telegram.
- Original Q11.4 says "avoid duplicating controls already enforced by the risk chain", but nothing says which those are.

*Needed:* a table of decision → existing component → exists / extend / new / conflicts.

**G28 — Where new modules sit in the layer diagram. (R)**
- New modules: execution evaluator, M1–M7, aggregation, SL/TP, risk management, position management, ranking, heatmap
  features.
- They must fit CLAUDE.md's layers, with models SDK-free in `flow-core` and features from `flow-runtime` adapters.
- Original Q6.3 says "shared framework with configurable model modules" but doesn't place it.

**G29 — Strategy → idea interface. (R)**
- Every idea must carry an invalidation condition (Q7.3), and the strategy owns expiry (extended Q-L3).
- The current market-structure strategy emits an `Intent`. The new idea contract (direction, zone, invalidation, expiry,
  ID) isn't defined.

**G30 — Compute and latency budget. (R)**
Continuous M1–M7 evaluation, ranking, profiles and DOM features inside the MotiveWave study. There is no budget against
the RiskChain's lag check.

**G31 — Signal visualisation for review. (T)**
`analysis/` viewers don't show model scores, ideas, levels or DOM features. Needed for the Q13.2 signal-log review.

---

## E. Research methodology

**G8 — Overfitting / multiple-testing control. (R, §13)**
- Original Q13.1 sensibly says define acceptance criteria *before* looking at results.
- Missing: out-of-sample / walk-forward discipline, a cap on parameter sets tried, and freezing values before a forward
  period. Seven models × scores × many parameters × modes will find chance winners.

**G32 — Sample size reality. (R)**
- How many ideas per day does the structure strategy produce?
- Original Q6.2 validates long and short separately; extended Q6.13 analyses each score level.
- Decide which breakdowns are V1 and which wait for data.

**G33 — Point-in-time levels. (R)**
POC, value area, LVN ranks and VWAP move intraday. Research and hypothetical outcomes must use levels *as of* evaluation
time, not end of session. This also applies to the 1-second construct snapshots (original Q13.3): store the as-of levels
in them.

**G34 — Costs and Sim fill realism. (R)**
- Commissions/fees are absent from outcome and acceptance metrics.
- MotiveWave Sim fills limit orders without queue position, which biases limit-on-retest research (original Q7.2).

**G35 — Footprint-only backtest variants. (R + T)**
- If Rithmic historical ticks become available, everything except DOM is backtestable.
- Decide which models get a footprint-only variant (G11).
- The generic engine needs a tick/event path, not 1-minute OHLC.

---

## F. Distillation itself

**G36 — Pass-2 distillation dropped content. (T)**
`entry_model_rules.txt` (which the review was built from) dropped:
- POC revisit / Gold Extra Edge (G7a);
- HVN-for-exits (G7b, now partly back via original Q10.2);
- unfinished-auction revisit (G10);
- the small-print retail signal (G16);
- the delta shift (G17);
- pool vs garbage / magnet / icebergs (G21).

Re-check pass 2 against pass 1 before the next review.

**G37 — Translation fidelity. (T, low)**
Where a rule hinges on one word or convention (finished-auction side, G22), spot-check the Hindi original or a course
screenshot.

**G38 — `docs/video_transcripts/strategies/` is empty. (T, low)**
It has `raw/` and `distilled/` folders with no files. Either it is planned work for a todo entry, or it can be removed.

---

## Closed by the complete review file (kept for traceability)

| Item | Closed by |
|---|---|
| G1 (most of it) | Original-source Q9.1–Q13.3 decisions; remainder kept above |
| G7b (HVN as target) | Original Q10.2 (now a conflict, G0.6) |
| G24 (partials vs 1-contract cap) | Original Q10.3 defers partials |
| `toBeReviewed_v2` Q5.5-A (profile shape) | Original Q5.5 (record for research first) |
| `toBeReviewed_v2` Q1.4 (starting bar size) | Original Q1.1/Q1.2 (500-tick baseline) |
| `toBeReviewed_v2` Q4.5-A (need all reads) | Original Q4.5 (keep all, test incremental value) |
| `toBeReviewed_v2` Q5.9 (session scope) | Original Q5.6 plus extended Q5.22 (exchange timezone/calendar) |
