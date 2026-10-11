
# Reviewed Master v2 — Governance and Review Findings

**Status:** Local v2 working copy, based on reviewed_v1 with decisions from the ongoing review incorporated. Questions remain OPEN unless explicitly marked RESOLVED during the review in this conversation.

## Authority rule — read before implementation

**When changing or implementing the execution model, this reviewed document is the sole source of truth for the approved rules.** The course files, old `orderFlowExecutionRules.md`, current code, prior drafts, and other notes may be used as background or evidence during review, but they do not override a decision recorded here.

- A question stays **OPEN** until the user explicitly settles it.
- Drafts, examples, assistant recommendations, and course descriptions are not approved rules by themselves.
- When a decision is made, update the relevant question and decision log in this reviewed document.
- Do not implement an unresolved rule by guessing. Ask or leave it unimplemented.
- No code is to be pushed as part of the review unless the user separately authorizes that work.
- Review questions will be addressed in the listed review order. Later issues should be recorded, not prematurely resolved.

## Initial review findings — recorded for later resolution

These findings were identified when checking the merged master review. They are recorded now so they are not lost; they do **not** decide the questions in advance.

### R1 — Scope should be provisional until dependencies are clear
Choose candidate entry models early to establish scope, but treat that choice as provisional until their required primitives and location rules are defined. Revisit model selection after §§2–5 are reviewed.

### R2 — Separate order submission, fill, and active-position states
The lifecycle must not define “Executed” as both order placement and an active position while leaving the execution boundary open. Review whether to track distinct events such as order submitted, partially filled, fully filled, and position active. No boundary is decided yet.

### R3 — Invalidation must be model-specific
Examples such as price trading through an LVN or leaving a zone are candidates, not universal invalidation rules. Different entry models have different locations and thesis conditions. Define invalidation for each selected model; do not assume a generic rule.

### R4 — Avoid double-counting correlated confirmation evidence
The document explores failed aggression as a broader concept and absorption, exhaustion, and delta flip as possible measurements or confirmations. If these overlap, counting them as separate confirmations may double-count the same evidence. When reviewing confirmation logic, decide what counts as independent evidence, not only how many signals are present.

### R5 — Keep touch and read proximity conceptually distinct
The event that activates a location (a “touch”) and the distance rule that allows an individual print/read to count are separate concepts. They may ultimately share a tolerance, but that is not assumed. Big-trade proximity to a location and other read-proximity rules should be explicitly considered.

### R6 — Define adaptive thresholds and bar construction precisely
If a bar size or big-trade threshold is adaptive, define what adapts, the lookback period, and how session/activity differences are handled. Do not implement “adaptive” as an unspecified heuristic.

### R7 — Make session-time assumptions implementable
Course examples mention session times in IST and refer to RTH. Before coding session/profile anchors, resolve the authoritative timezone, daylight-saving treatment, exchange calendar, session boundaries, and holidays. Course example times must not silently become fixed constants.

### R8 — Describe current data limitations accurately
The current setup is described as having seven days of retained tick data and Sim forward testing. Treat that as the current constraint, not a permanent claim that no historical data can be acquired. Keep the historical-data availability question open.

### R9 — Keep question IDs and merge aliases traceable
Where questions have been merged, retain the original question IDs as aliases so decisions can be traced. Avoid ambiguous or duplicated IDs when updating the decision table.

## Review order

Follow this order. Address each item when reached; do not try to settle all open questions at once.

1. **Scope:** select candidate entry models, direction, strategy structure, and whether heatmap/gamma are in scope. Treat the selection as provisional where later definitions could change it.
2. **Bar and data unit:** bar type, tick count, structural timeframes.
3. **Footprint primitives and aggression:** imbalance, row grouping, finished auction, big trades, meaningful aggression and price response.
4. **Absorption, exhaustion, delta flip:** definitions, windows, and overlap with failed aggression.
5. **Location:** profile sources/anchors, LVN qualification/ranking, VWAP/structure, touch and proximity.
6. **Entry models:** finalize only after their primitives and locations are defined.
7. **Lifecycle, timing, and confirmation:** monitoring, activation, confirmation, order submission, fills, invalidation, expiry, reset, and ownership.
8. **Stops, targets, management, and no-trade filters.**
9. **Optional heatmap/sweep/gamma layers.**
10. **Validation:** logging, data, forward-test acceptance criteria.

## Review log

| Item | Status | Note |
|---|---|---|
| Authority rule | Recorded | This reviewed document governs approved execution-model rules. |
| Q0.1 — V1 entry-model scope | **RESOLVED** | Include all seven candidate models (M1–M7) in V1 as candidate evidence/entry-model checks applied to trade ideas generated by the underlying strategy. This does not mean every model must independently generate a trade or that all models are already validated. |
| Q0.2 — Directional scope | **RESOLVED** | Evaluate long and short trade ideas without a directional bias. This is a research baseline, not an assumption that individual entry models perform symmetrically; any bias must be discovered from evidence. |
| Q0.3 — Execution-model architecture | **RESOLVED** | Use one unified execution model to evaluate each eligible strategy-generated trade idea against all seven candidate entry models, while logging each model's result separately. |
| Q0.4 — Heatmap and gamma scope | **RESOLVED** | Include heatmap in V1. Defer GEX/gamma until a reliable source for accurate levels is available; do not treat derived estimates as accurate enough for V1. |
| Q1.1 — Bar size configurability | **RESOLVED** | Bar size is trader preference; keep bar construction dynamic/configurable so multiple variations can be tested rather than hard-coding 500-tick, 5-minute, or range bars. |
| Q1.2 — Adaptive bar sizing | **RESOLVED** | Use fixed, manually configured bar sizes for now. Adaptive sizing is deferred and may be reconsidered later if research shows a need. |
| Q1.3 — Execution/structure timeframes | **RESOLVED** | Keep execution and market-structure timeframes separate and configurable; do not force them to use the same bar size. |
| Q2.1 — Diagonal imbalance threshold | **RESOLVED** | Make the diagonal-imbalance threshold configurable for research; no single percentage is locked in as the universal rule. |
| Q2.2 — Imbalance minimum volume | **RESOLVED** | Make the minimum-volume requirement configurable as a separate parameter from the diagonal-imbalance ratio. |
| Q2.3 — Footprint row grouping | **RESOLVED** | Footprint row grouping will exist and be configurable so different groupings can be tested. |
| Q2.4 — Finished auction concept | **PARTIALLY RESOLVED** | Initial rule: require exactly zero volume on the relevant footprint side at the extreme; do not use a near-zero tolerance in the initial baseline. The side/direction mapping and other implementation details remain OPEN for research. Numerical settings generally begin as temporary, configurable guesses and are calibrated through analysis. |
| Q2.5 — Finished-auction role | **RESOLVED** | A finished auction is confirmation only: it may strengthen another signal at a relevant location but cannot independently trigger a trade idea. It is not mandatory for every move. |
| Q2.6 — Stacked-imbalance minimum | **PARTIALLY RESOLVED** | Start with 3 consecutive qualifying imbalance levels as a provisional baseline; keep the minimum configurable for research. This is a temporary guess to be calibrated through analysis, not a validated universal threshold. |
| Q2.7 — Initial footprint parameter values | **PARTIALLY RESOLVED** | Provisional research baseline: 300% diagonal-imbalance ratio, 6-tick footprint row grouping, and 3 contracts minimum volume per compared level. Keep all three configurable and calibrate through research; these are starting guesses, not validated thresholds. |
| Q3.1 — Big-trade threshold | **PARTIALLY RESOLVED** | Start with a configurable fixed threshold of 30 contracts per individual print as the provisional Gold baseline. Investigate adaptation after testing the fixed baseline; the adaptive formula and precise trade-size definition remain OPEN. Thirty contracts is a temporary research guess, not a validated universal threshold. |
| Q3.2 — Successful/failed aggression | **PARTIALLY RESOLVED** | Track both maximum favorable excursion and price at the end of a configurable fixed time window, evaluating continuously intrabar. Keep progress/outcome thresholds configurable and calibrate them through research. When a qualifying big aggressive print produces price progress in its direction, initially anchor the marked aggressive-history level/zone at the print price. Cluster-based expansion may be considered later. On a retest, treat potential defense by those participants as a hypothesis to evaluate jointly with resting liquidity at that level/zone, not as a guaranteed outcome or standalone trigger. Window duration and thresholds remain OPEN. Bars are optional abstractions for selected constructs, not the default unit of significance for most order-flow constructs. |
| Q3.3 — Repeated aggressive prints | **PARTIALLY RESOLVED** | Support both independent-print analysis and aggregation of repeated prints into an aggression burst. Group prints using both elapsed time between prints and price distance between them. Keep both criteria configurable; exact values are temporary research guesses to be selected and calibrated through testing, not fixed constants. |
| Q3.4 — Aggression proximity | **PARTIALLY RESOLVED** | Support configurable tick-distance and price-range proximity methods. Research should compare them independently and in combination. Exact reference point/zone, formulas, provisional values, and combination behavior remain OPEN; numerical values start as provisional guesses and are calibrated through testing. |
| Q3.5 — Aggression definition/data source and meaningful-aggression inputs | **PARTIALLY RESOLVED** | Base aggression on the feed's bid/ask trade execution classification. Preserve big prints, delta, diagonal imbalance, stacked imbalance, and repeated prints/bursts as separate configurable signals. Support both configurable weighted numerical scoring and rule-based combinations, selectable by entry model. Weights, normalization, thresholds, and specific rules remain OPEN. These inputs may share underlying data, so avoid automatically double-counting correlated evidence. |
| Q3.5-A — Combined aggression-score architecture | **PARTIALLY RESOLVED** | Support both configurable weighted numerical scoring and configurable rule-based combinations. Individual entry models choose the approach. Keep raw component signals inspectable; weighting, normalization, thresholds, and model-specific rules remain OPEN for research. |
| Q3.5-B — Handling disagreement between aggression signals | **PARTIALLY RESOLVED** | Preserve each component signal and explicitly record agreement/disagreement alongside the combined result. A strong net score must not hide material conflict. Keep the combined output available, but expose conflicts for downstream model logic and research; exact materiality thresholds and model-specific responses remain OPEN. |
| Q3.6 — Successful versus failed aggression | **PARTIALLY RESOLVED** | Track MFE and end-of-window price using Q3.2, but distinguish ordinary directional price progress from evidence that aggression overcame opposing resting liquidity. A move in the direction of aggression when there is no meaningful resting-liquidity challenge is directionally successful, but does not by itself establish that aggression was stronger than liquidity. For the stronger signal of interest, identify a relevant resting-liquidity wall/zone and evaluate aggressive execution and price response. Track executed consumption, cancellation/pulling, mixed cases, and price trading through the wall as separate observations with different evidential meanings; do not treat cancellation-only disappearance as equivalent to execution consuming liquidity. Record the first trade beyond the wall as **penetration**; classify a **confirmed break** only after configurable follow-through criteria are met. Preserve successful, failed, and mixed/inconclusive outcomes; keep wall/zone qualification, break criteria, follow-through, time window, price progress, and numerical thresholds configurable and provisional until tested. |
| Q4.1 — Absorption concept | **PARTIALLY RESOLVED** | Absorption requires evidence of high resting liquidity supporting the failed-aggression interpretation. Start with provisional baseline guesses for research, but keep all numerical thresholds configurable and calibrate them later. Exact definitions, baseline values, and thresholds remain OPEN. |
| Q4.2 — Absorption evidence sources | **RESOLVED** | Use full DOM from the Rithmic feed (the same source used to build the liquidity heatmap) plus executed-volume footprint evidence of failed aggression. Both sources together provide the evidence for absorption. |
| Q4.3 — Absorption measurement window | **RESOLVED** | Make the absorption measurement window configurable so different windows can be tested. Preserve event timestamps so DOM liquidity and executed-volume evidence can be aligned during research. |
| Q4.4 — Delta measurements | **PARTIALLY RESOLVED** | Support bar delta, price-level delta, and cumulative delta as configurable research inputs. Bar delta is the initial working hypothesis for absorption-completion confirmation, but this is not yet validated or final. Research must determine whether to use bar delta, price-level delta, or both in each context; if bar delta is selected, its timeframe must also be determined through analysis. All numerical settings start as provisional, configurable guesses and are calibrated iteratively through research. |
| Q4.5 — Absorption stages and delta-flip confirmation | **PARTIALLY RESOLVED** | Failed aggression plus supporting resting-liquidity evidence indicates absorption is happening/being attempted; a delta flip confirms absorption completion. Aggressors may ultimately win, in which case no delta flip occurs. Test both a delta sign change alone and a sign change accompanied by price non-extension. Use the latter as the initial working hypothesis, not a final rule; compare both through research. Context-specific measurement selection remains OPEN. |
| Q4.6 — Failed aggression vs absorption | **RESOLVED** | Failed aggression is a broader, independent concept. Supporting resting-liquidity evidence is required to classify it as an absorption attempt; delta flip is the separate confirmation of absorption completion. |
| Q4.7 — Exhaustion definition and sequence | **PARTIALLY RESOLVED** | Support footprint-based declining aggressive volume and big-print-based declining aggressive-trade size as separate configurable exhaustion signals. Exhaustion comes after absorption is complete and is identified from price moving opposite to the failed aggression while aggressive activity in the original aggression direction weakens. Do not require a fixed count of failed-aggression events; their number varies by situation. Reuse Q3.2's configurable time window and price-progress measurement; evaluate continuously intrabar rather than requiring bar close. Exact window duration, thresholds, and signal-combination logic remain OPEN. |
| Q4.8 — Combining exhaustion measurements | **PARTIALLY RESOLVED** | Make the combination of footprint-based declining aggressive volume and big-print-based declining aggressive-trade size configurable for research. These measurements derive from the same underlying trade/execution data and are related views, not fully independent confirmations; avoid double-counting them as independent evidence. Reuse Q3.2's configurable time window and price-progress measurement for the opposing price movement. Exact combination/scoring logic, window duration, and thresholds remain OPEN. |
| Q5.1 — Trade-location sources | **RESOLVED** | Make volume profile, VWAP, higher-timeframe market structure, and session extremes available as configurable location sources; their usefulness will be evaluated through research. |
| Q5.2 — Volume-profile types and anchors | **RESOLVED** | Support multiple profile types with configurable anchors and allow multiple profiles to be active simultaneously (e.g. weekly, daily, and current-session profiles) to provide broader context. Exact profile definitions and interaction rules remain OPEN. |
| Q5.3 — LVN qualification vs trade relevance | **RESOLVED** | Qualify LVNs using configurable relative-depth and width thresholds. Separately evaluate an LVN's relevance to a trade idea by its distance from the idea's zone, with configurable distance tolerance; a valid LVN can be meaningful without being relevant enough to prioritize for a particular trade. Exact formulas, thresholds, distance reference, and cutoff remain OPEN. |
| Q5.4 — HVN research scope | **RESOLVED** | Treat HVNs similarly to LVNs for exploratory analysis, using configurable qualification parameters and distance-to-trade-idea relevance, but HVNs are informational only and must not affect trade execution for now. |
| Q5.5 — VAH/VAL role and tradeability | **PARTIALLY RESOLVED** | Treat VAH and VAL as potential reversal zones with significance comparable to or greater than LVNs. Unlike an LVN range, each boundary is a single price level. They are tradable concepts, but rigid entry/confirmation rules are explicitly deferred for later research. |
| Q5.6 — VWAP role | **PARTIALLY RESOLVED** | VWAP may act as a counter-trade location in suitable conditions (e.g. overlap with POC plus a strategy idea in the same direction), but this is contextual, not an absolute rule. More commonly, VWAP is one of several inputs used to determine a target. Exact conditions and target logic remain OPEN. |
| Q5.7 — Higher-timeframe vs primary-timeframe structure | **RESOLVED** | Higher-timeframe market structure is used to identify overall directional bias. Trade ideas are generated by the primary (chosen) timeframe's structure/strategy. Currently market-structure rules are the only strategy and generate ideas; in future they may become supporting evidence rather than the core strategy, without changing the higher-timeframe bias role. |
| Q5.8 — Session extremes role (existing numbering) | **PARTIALLY RESOLVED** | Session highs/lows are important levels but provide weaker standalone reversal evidence than VAH/VAL and POC. They are primarily target identifiers like VWAP, but may become tradable under specific conditions. Exact conditions remain OPEN. |
| Q5.8 — LVN strength/ranking (new review item; numbering collision to resolve) | **PARTIALLY RESOLVED** | Preserve LVN width, depth of the volume depression, relative volume, surrounding HVN volume, and node separation as separate features. Support configurable scoring and rule-based ranking. Calibrate feature definitions, weights, thresholds, and ranking logic through research; begin with provisional guesses rather than treating them as validated constants. |
| Q5.9 — LVN approach/touch/traversal states | **PARTIALLY RESOLVED** | Track approaching an LVN, touching/entering its range, and moving through it as distinct events. Support a configurable proximity tolerance for the approach state. Exact state-transition criteria and provisional tolerance values remain OPEN and should be calibrated through research. |
| Q5.10 — Separate proximity tolerances | **RESOLVED** | Use separate configurable tolerances for LVN approach, absorption proximity, and aggression proximity. Do not force these constructs to share a common distance threshold; initialize each with a provisional guess and calibrate independently through research. |
| Q-L1 / Q8.4 — Trade-idea activation and monitoring | **RESOLVED** | A strategy-generated trade idea becomes active immediately and remains active until expiry or invalidation. The execution engine begins monitoring immediately. LVN touch is not a universal activation gate; it remains a location event/input for entry models that use it. |
| Q-L2 / Q8.5 — Dynamic execution evaluation | **PARTIALLY RESOLVED** | Execution evaluation is a continuously updated state, not a terminal result. Its current Go/No-Go can change repeatedly as M1–M7 and market context update, while the idea remains active. Go makes the current candidate eligible for risk management; risk management independently decides whether entry is permitted. Immediately before order submission, recheck Go/No-Go; if No-Go, do not submit and keep monitoring the active idea. The execution engine does not own idea validity, expiry, or invalidation. Exact model criteria and aggregation remain governed by their own decisions. |
| Q-L3 / Q8.6 — Expiry/invalidation ownership and execution handoff | **PARTIALLY RESOLVED** | The underlying strategy alone owns trade-idea expiry/invalidation while the idea is in execution evaluation. Once a position is open, execution evaluation and entry risk management relinquish authority; position management takes over, has access to the data used by execution evaluation, and decides whether to keep the position open or close it. If position management decides to close, the system exits the position. Exact behavior for submitted but unfilled orders and partial fills remains OPEN. Keep order submission, partial fill, full fill, position-active, and position-management states distinct. |
| Q6.1 — Per-model evidence outputs | **RESOLVED** | Every model returns a separate result for a trade idea: supports, neutral/no signal, or contradicts. Preserve individual results for analysis before defining aggregation, scoring, thresholds, or veto rules. |
| Q6.2 — Model-level evidence scale | **RESOLVED** | Each model returns one of five signed outcomes: -2, -1, 0, +1, +2. The scale represents direction and strength at the model level; the exact semantic definitions and how scores are aggregated remain OPEN. |
| Q6.3 — Cross-model score comparability | **RESOLVED** | Use the same five-point output interface for all models, but allow model-specific scoring criteria initially. Do not assume equal scores are equally strong across models until research/calibration establishes comparability. |
| Q6.4 — No signal/data versus neutral bias | **RESOLVED** | Keep no-signal and no-data states separate from score 0. A score of 0 means neutral bias; it must not be used to represent unavailable, missing, incomplete, or unreliable evidence. |
| Q6.5 — Conflicting model evidence | **RESOLVED** | Preserve each model's score independently and pass the collection to a separate aggregation layer. No model automatically overrides another unless a specific veto rule is explicitly established later. |
| Q6.6 — Trade-idea research logging | **RESOLVED** | V1 must log each model's score/status, underlying measurements, relevant market context, and eventual trade outcome. Context includes time-series/price data and the order-flow constructs already stored at 1-second resolution. Consider short-duration tick-by-tick capture of price and order-flow constructs around entry evaluation for later inspection; capture duration, scope, storage format, and activation criteria remain OPEN. |
| Q6.7 — Logging rejected ideas | **RESOLVED** | Apply the same model-output and market-context logging to every eligible strategy-generated idea, including rejected ideas. Track the hypothetical subsequent outcome for rejected ideas as well as actual outcomes for executed trades, while distinguishing simulated/hypothetical outcomes from real executions. |
| Q6.8 — Repeated ideas and position-state scenarios | **PARTIALLY RESOLVED** | Preserve every evaluation event as a separate record. Distinguish repeated same-direction ideas while flat, repeated same-direction ideas while already in a position, and opposite-direction ideas while a position is open. Position-management actions for these cases remain OPEN. |
| Q6.9 — Entry evaluation vs position management | **RESOLVED** | Keep entry evaluation and position management as separate responsibilities. Entry evaluation scores each new trade idea using model evidence; position management decides what action, if any, is appropriate given the current position. |
| Q6.10 — Position-management research logging | **RESOLVED** | V1 must log position state when each idea arrives, the position-management decision and rationale, and resulting order/position changes. This record must be linked to entry evaluation so research can distinguish idea quality from position-management quality. |
| Q6.11 — Independent per-model evaluation | **RESOLVED** | V1 research must evaluate each model's predictive contribution independently of the final aggregation decision and actual execution. Retain outcomes for accepted/rejected ideas and executed/non-executed cases so model quality can be studied separately from aggregation and position management. |
| Q6.12 — Data granularity for model research | **RESOLVED** | Use existing 1-second price/order-flow data and constructs as the standard research granularity for V1. Do not add tick-level raw capture for this purpose now; revisit only if analysis later demonstrates a need. If data volume or analysis performance becomes a problem, reduce/aggregate the 1-second data then. |
| Q6.13 — Score-level outcome analysis | **RESOLVED** | Evaluate outcomes for each model's five scores (-2, -1, 0, +1, +2) separately; validate whether score levels distinguish outcomes instead of assuming stronger scores perform better. Preserve raw timestamped execution-model logs separately from 1-second-aggregated order-flow constructs. |
| Q6.14 — NO_SIGNAL / NO_DATA frequency research | **RESOLVED: OUT OF V1** | Keep NO_SIGNAL and NO_DATA distinct from numeric scores, but defer outcome/predictive analysis of these statuses to a separate research topic outside V1. First measure how frequently each occurs and decide whether further investigation is worth the effort. |
| Q6.15 — Versioning model changes | **RESOLVED** | Version model logic and scoring-criteria changes, and preserve the version/configuration used for each evaluation so results from different rule sets are not silently mixed. |
| Q6.16 — Updating model versions during research | **RESOLVED** | Do not require parallel operation of multiple model versions. Minimize disruption by keeping changes additive and preserving fundamental functions such as data collection. Switch model versions only during off-market hours; retain version/configuration traceability. |
| Q6.17 — Model evaluation errors | **RESOLVED** | Translate a model evaluation error into the already-defined `NO_SIGNAL` status rather than numeric score `0` or a new top-level status. Record the exact error and supporting context for diagnosis. |
| Q6.18 — Handling a model error in aggregation | **PARTIALLY RESOLVED** | Continue evaluating with all non-errored models; the errored model is excluded from score calculation, and aggregation otherwise runs as configured. Separately define critical models whose failure forces record-only/no-trade behavior regardless of the aggregate result. Critical-model list and exact policy remain OPEN. |
| Q6.19 — Critical-model configuration | **RESOLVED** | Critical-model designations must be configurable and versioned, not hard-coded. Changes should follow the established off-market change process and preserve fundamental functions such as data collection. |
| Q6.20 — Final execution eligibility and block reasons | **RESOLVED** | The execution model's ultimate gate is `Final execution eligibility`. It may be blocked for low aggregate score, critical-model failure, or other reasons. Record every trade idea and its supporting context regardless of eligibility; preserve the reason(s) for the final eligibility result. Different block reasons are handled consistently for recording, without implying they have identical operational causes. |
| Q6.21 — Immutability of a completed eligibility decision | **RESOLVED** | Once an evaluation is completed, its final execution-eligibility decision is immutable. If the strategy generates the idea again, evaluate it as a new event with a new record; do not revise or overwrite the earlier blocked/approved result. |
| Q6.22 — Additional rules for repeated ideas | **RESOLVED — NO CURRENT RULE; FUTURE EXTENSIBILITY REQUIRED** | A repeated idea may become tradable later as market conditions change. No additional rule currently applies solely because an earlier evaluation was blocked, but the design must leave room to add such rules in the future. Each evaluation remains independent and historical decisions remain immutable. |
| Q6.23 — Eligible evaluation while a position is open | **RESOLVED** | A newly eligible evaluation proceeds to the position-management layer, which decides what to do based on current position and order state (e.g. ignore, add, exit, or reverse). Eligibility itself does not authorize or dictate the position-management action. |
| Q6.24 — Record ignored eligible ideas | **RESOLVED** | If position management ignores an eligible idea, record that as a separate position-management decision linked to the original entry evaluation, including the reason for ignoring it. Preserve the eligible evaluation result unchanged. |
| Q6.25 — Record no-action decisions caused by current state | **RESOLVED** | Record position-management decisions that take no action because the current position/order state prevents or makes action inappropriate, including the reason. Keep the record separate from the entry evaluation and link it to that evaluation where applicable. |
| Q6.26 — Separate management decisions from order outcomes | **RESOLVED** | Record the position-management decision and the resulting order lifecycle/outcome as separate events. A decision to place an order does not imply successful submission or fill; record rejection, cancellation, non-fill, and other outcomes distinctly and link them to the originating decision. |
| Q6.27 — Track partial fills and remaining quantity | **RESOLVED** | Record each partial fill immediately as its own event and track the remaining order quantity separately until it is filled, cancelled, or otherwise resolved. Partial fills update actual position state and may affect subsequent position-management decisions. |
| Q6.28 — Evaluate opposing ideas against open position and outstanding orders | **RESOLVED** | When a new opposing idea arrives, position management must consider both the actual open position and any outstanding unfilled order, since the outstanding quantity may still fill and change exposure. |
| Q6.29 — Same-direction idea while flat | **RESOLVED** | Evaluate repeated ideas independently and preserve the option to add configurable suppression rules later; do not suppress or merge them by default. |
| Q6.30 — Same-direction idea while in a position | **RESOLVED** | Use configurable position-management rules. Adding to an existing position is disabled by default until explicit risk and sizing rules are defined. |
| Q6.31 — Opposing idea while in a position | **RESOLVED** | Let position management decide through explicit/configurable rules, based on current position and order state. Do not automatically reverse by default before the behavior is tested. |
| Q6.32 — New idea with outstanding order | **RESOLVED** | Position management must consider the actual position and all outstanding orders. Cancellation is an event; a cancel request must not be treated as proof of cancellation. |
| Q6.33 — Cancellation racing with a fill | **RESOLVED** | Treat confirmed broker/exchange order and fill events as authoritative, reconcile actual exposure, and then re-evaluate safe actions. Never assume a cancel request succeeded. |
| Q6.34 — Critical models in V1 | **RESOLVED** | Critical-model designations are configurable, but no model is critical by default. Explicit designation is required before critical-model behavior is active in live use. |
| Q6.35 — Critical model failure | **RESOLVED** | Record the idea and failure, block execution when a designated critical model fails, and allow other models to finish evaluating where possible. |
| Q6.36 — Eligibility block reasons | **RESOLVED** | Record the final eligibility result plus all applicable block reasons, using stable identifiers where possible. Do not retain only a single highest-priority reason. |
| Q6.37 — Order failure after eligibility | **RESOLVED** | Keep the original eligibility decision immutable and record order submission/execution failure separately. |
| Q6.38 — Hypothetical outcomes for rejected ideas | **RESOLVED** | For rejected ideas, record both simple hypothetical price movement and a simulated trade outcome. Keep hypothetical results separate from actual execution and P&L; horizon and assumptions remain configurable/open. |
| Q6.39 — Overlapping ideas in research | **RESOLVED** | Preserve individual outcomes for each idea and also analyze overlap, dependencies, and overlapping exposure. Do not merge individual evaluation records. |
| Q6.40 — Model predictive metrics | **RESOLVED** | Analyze win rate, favorable/adverse price movement, and outcome distributions by each model's score. Keep metric definitions and windows configurable; do not assume higher scores imply better outcomes. |
| Q6.41 — NO_SIGNAL / NO_DATA outcome analysis | **RESOLVED — FREQUENCY FIRST** | Measure the frequency of NO_SIGNAL and NO_DATA first, then revisit whether deeper outcome analysis is worthwhile. Keep these statuses distinct from score 0; full predictive analysis remains outside V1 for now. |
| Q6.42 — Research record schema | **RESOLVED** | Use a common required record schema plus versioned model-specific evidence fields. |
| Q6.43 — Event linkage | **RESOLVED** | Use unique IDs for evaluations, position-management decisions, orders, and fills, with explicit links between related events. Timestamps alone are insufficient. |
| Q6.44 — Version traceability | **RESOLVED** | Record model/code version and runtime configuration separately for every evaluation, so both behavior and the exact configuration are traceable. |
| Q6.45 — Version-change safeguards | **RESOLVED** | Require pre-deployment checks, post-deployment verification, and a documented rollback procedure. Scale the depth of checks to the change's risk; changes remain off-market and must protect data collection. |
| Q6.46 — Overall evaluation-system failure | **RESOLVED** | Define response by failure type/severity; fail closed when eligibility or exposure state is unreliable. Preserve the idea and failure diagnostics. |
| Q6.47 — Retries and replacement orders | **RESOLVED** | Allow retries only under explicit configurable rules, link each attempt to the originating management decision, and reconcile broker/order state before retrying. Never retry blindly while the prior order's status is uncertain. |
| Q7.1 — Trigger timing mode | **RESOLVED** | Support both closed-bar and intrabar entry modes as configurable research modes. Use closed-bar entry as the initial baseline; compare intrabar entry separately without look-ahead bias. |
| Q7.2 — Entry order type | **RESOLVED** | Support market and limit entry as configurable research modes, including limit entries such as retest-based entries. Record intended price, submission time, actual fills, slippage, and missed entries where applicable. |
| Q7.3 — Setup expiry and invalidation | **RESOLVED** | Every trade idea supplies an invalidation condition; when it is satisfied, the idea is invalidated. If an expiry based on time or price range is configured, that expiry is also enforced. The applicable configured expiry and the idea's invalidation condition both govern validity. |
| Q8.1 — Model aggregation and Go/No-Go | **RESOLVED** | The aggregation layer combines the results of M1–M7 and produces the current Go/No-Go state. There is no separate universal confirmation-count rule at this layer. Go makes the current candidate eligible for risk management; risk management decides whether entry is permitted. Recheck Go/No-Go immediately before submission; if No-Go, do not submit and continue monitoring the active idea. No-Go does not invalidate or expire the idea. |
| Q8.2 — Model-specific requirements and cross-model aggregation | **RESOLVED** | Each model enforces its own mandatory internal conditions and reports its result; the aggregation layer then combines M1–M7 results and may evaluate cross-model agreement and conflicts. Exact model conditions and aggregation rules remain subject to their dedicated specifications. |
| Q8.3 — Continuous model updates and aggregation refresh | **RESOLVED** | While a trade idea remains in execution evaluation, M1–M7 continue evaluating in real time. A model-result change immediately notifies the aggregation layer, which updates that model's current state and recalculates Go/No-Go from the latest available results. Use event-driven updates with maintained per-idea state rather than polling; continue until invalidation, configured expiry, or another defined lifecycle transition. Bar boundaries do not limit evidence accumulation; each model and the aggregation rules determine which current evidence remains valid. |
| R1–R9 | OPEN | Findings recorded; address in review order. |
| All inherited questions | OPEN unless explicitly resolved here | Do not infer approval from a draft or recommendation. |

### Q0.4 — Heatmap and gamma scope (RESOLVED)

**Decision.** Include liquidity heatmap in V1. Do not include GEX/gamma levels in V1 at this stage.

**Reason for deferring GEX.** There is currently no API/source available to the project that provides sufficiently reliable, accurate GEX levels, and the user does not consider deriving them locally accurate enough. GEX may be reconsidered once a reliable source becomes available.

**Scope boundary.** Heatmap and GEX are separate capabilities: including heatmap does not imply that GEX is included. The heatmap's data source, definition of relevant liquidity/walls, and how it affects acceptance/rejection remain to be reviewed.

### Q0.3 — Execution-model architecture (RESOLVED)

**Decision.** Use one unified execution model. For each eligible trade idea generated by the underlying strategy, evaluate all seven candidate entry models (M1–M7) within the same framework.

**Observability requirement.** Log each entry model's result independently so the research can later measure its contribution, combinations with other models, and performance by direction, time of day, and market situation.

**Scope boundary.** A unified framework does not mean the models must have equal weight or that their signals are independent. The aggregation/acceptance rule remains OPEN pending the later review and validation work.

### Q0.2 — Directional scope (RESOLVED)

**Decision.** Evaluate long and short trade ideas without a pre-imposed directional bias.

**Research framing.** This is a research task: do not assume that each entry model is directionally symmetric, and do not hard-code a long or short preference in advance. Measure outcomes separately by direction and by entry model, then determine from evidence whether any model exhibits a directional bias.

**Scope boundary.** The decision establishes neutral evaluation, not a claim that long and short outcomes should be equal. Any later directional filtering or model-specific bias must be supported by validation results and explicitly reviewed.

### Q0.1 — V1 entry-model scope (RESOLVED)

**Decision.** V1 will include all seven candidate models, M1–M7. The underlying strategy generates trade ideas; the execution model evaluates those ideas using the entry models as candidate evidence for deciding whether to take or ignore each idea.

**User's working hypothesis.** The more entry-model conditions satisfied by a given trade idea, the better the candidate trade may be. This is a hypothesis to test, not an established scoring rule or a claim that the models are equally reliable.

**Validation requirement.** During testing, log each model's individual result and the combined number/pattern of satisfied models. Evaluate whether the count or combination predicts better outcomes, and whether usefulness changes by time of day or market situation. Do not assume a simple count is valid before testing.

**Important design caution.** The models may overlap or measure correlated aspects of the same order-flow event. A naive count could double-count evidence (see R4). The review must later decide whether all models contribute equally, whether some are prerequisites or vetoes, and whether model weights/regime-specific use are justified by evidence. Those details remain OPEN.

**Scope boundary.** Including M1–M7 in V1 means implementing and evaluating them as candidate checks. It does not authorize treating every model as a standalone trade generator, nor does it imply that any model's definitions, thresholds, location requirements, or confirmation logic are settled.

---

# Source material retained below

The sections below preserve the prior review material as the starting inventory. They are not automatically approved rules. The additions are included as a companion section so none of the identified missing questions are lost.


---

# Prior redefinition review (preserved)

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

## Heatmap/GEX scope decision recorded during conversation

**Q0.4 RESOLVED:** Include heatmap in V1. Defer GEX/gamma until a reliable source for accurate GEX levels is available. Do not treat locally derived GEX estimates as accurate enough for V1. Heatmap implementation details remain OPEN.

---

## Execution-model architecture decision recorded during conversation

**Q0.3 RESOLVED:** Use one unified execution model to evaluate all seven candidate entry models for each eligible strategy-generated trade idea. Record every model's result separately. The final aggregation rule, weights, and acceptance threshold remain OPEN.

---

## Directional-scope decision recorded during conversation

**Q0.2 RESOLVED:** Long and short trade ideas will be evaluated without a pre-imposed directional bias. Whether any entry model exhibits directional bias is an empirical research question; do not assume symmetry or encode a directional preference before testing.

---

## Scope decision recorded during conversation

**Q0.1 RESOLVED:** V1 includes all seven candidate entry models (M1–M7) as evidence/entry-model checks applied to trade ideas generated by the underlying strategy. The working hypothesis is that a greater number of satisfied model conditions may indicate a better trade, but this must be validated. Model overlap, weighting, prerequisites/vetoes, and time-of-day or market-regime dependence remain OPEN.

---

## Q5.2 decision recorded during conversation — volume-profile types and anchors

**Status: RESOLVED**

**Decision.** Support multiple volume-profile types and make their anchors configurable. Multiple profiles may be active at the same time.

**Example multi-profile context.** Weekly + daily + current-session profiles may be considered together to understand the broader context of where price is coming from and where it may move next.

**Research implication.** Keep the profile instances distinguishable in logs/configuration so testing can determine which profile horizons and combinations add value. Do not assume that all profiles must agree or that any single profile is universally dominant.

**Scope boundary.** Exact definitions of weekly/daily/session profiles, anchor and reset conventions, how overlapping levels are combined or prioritized, and per-entry-model profile requirements remain OPEN.









---

## Q6.29 — Same-direction idea while flat decision recorded during conversation

**Status: RESOLVED**

**Decision.** Evaluate repeated ideas independently and preserve the option to add configurable suppression rules later; do not suppress or merge them by default.


---

## Q6.30 — Same-direction idea while in a position decision recorded during conversation

**Status: RESOLVED**

**Decision.** Use configurable position-management rules. Adding to an existing position is disabled by default until explicit risk and sizing rules are defined.


---

## Q6.31 — Opposing idea while in a position decision recorded during conversation

**Status: RESOLVED**

**Decision.** Let position management decide through explicit/configurable rules, based on current position and order state. Do not automatically reverse by default before the behavior is tested.


---

## Q6.32 — New idea with outstanding order decision recorded during conversation

**Status: RESOLVED**

**Decision.** Position management must consider the actual position and all outstanding orders. Cancellation is an event; a cancel request must not be treated as proof of cancellation.


---

## Q6.33 — Cancellation racing with a fill decision recorded during conversation

**Status: RESOLVED**

**Decision.** Treat confirmed broker/exchange order and fill events as authoritative, reconcile actual exposure, and then re-evaluate safe actions. Never assume a cancel request succeeded.


---

## Q6.34 — Critical models in V1 decision recorded during conversation

**Status: RESOLVED**

**Decision.** Critical-model designations are configurable, but no model is critical by default. Explicit designation is required before critical-model behavior is active in live use.


---

## Q6.35 — Critical model failure decision recorded during conversation

**Status: RESOLVED**

**Decision.** Record the idea and failure, block execution when a designated critical model fails, and allow other models to finish evaluating where possible.


---

## Q6.36 — Eligibility block reasons decision recorded during conversation

**Status: RESOLVED**

**Decision.** Record the final eligibility result plus all applicable block reasons, using stable identifiers where possible. Do not retain only a single highest-priority reason.


---

## Q6.37 — Order failure after eligibility decision recorded during conversation

**Status: RESOLVED**

**Decision.** Keep the original eligibility decision immutable and record order submission/execution failure separately.


---

## Q6.38 — Hypothetical outcomes for rejected ideas decision recorded during conversation

**Status: RESOLVED**

**Decision.** For rejected ideas, record both simple hypothetical price movement and a simulated trade outcome. Keep hypothetical results separate from actual execution and P&L; horizon and assumptions remain configurable/open.


---

## Q6.39 — Overlapping ideas in research decision recorded during conversation

**Status: RESOLVED**

**Decision.** Preserve individual outcomes for each idea and also analyze overlap, dependencies, and overlapping exposure. Do not merge individual evaluation records.


---

## Q6.40 — Model predictive metrics decision recorded during conversation

**Status: RESOLVED**

**Decision.** Analyze win rate, favorable/adverse price movement, and outcome distributions by each model's score. Keep metric definitions and windows configurable; do not assume higher scores imply better outcomes.


---

## Q6.41 — NO_SIGNAL / NO_DATA outcome analysis decision recorded during conversation

**Status: RESOLVED — FREQUENCY FIRST**

**Decision.** Measure the frequency of NO_SIGNAL and NO_DATA first, then revisit whether deeper outcome analysis is worthwhile. Keep these statuses distinct from score 0; full predictive analysis remains outside V1 for now.


---

## Q6.42 — Research record schema decision recorded during conversation

**Status: RESOLVED**

**Decision.** Use a common required record schema plus versioned model-specific evidence fields.


---

## Q6.43 — Event linkage decision recorded during conversation

**Status: RESOLVED**

**Decision.** Use unique IDs for evaluations, position-management decisions, orders, and fills, with explicit links between related events. Timestamps alone are insufficient.


---

## Q6.44 — Version traceability decision recorded during conversation

**Status: RESOLVED**

**Decision.** Record model/code version and runtime configuration separately for every evaluation, so both behavior and the exact configuration are traceable.


---

## Q6.45 — Version-change safeguards decision recorded during conversation

**Status: RESOLVED**

**Decision.** Require pre-deployment checks, post-deployment verification, and a documented rollback procedure. Scale the depth of checks to the change's risk; changes remain off-market and must protect data collection.


---

## Q6.46 — Overall evaluation-system failure decision recorded during conversation

**Status: RESOLVED**

**Decision.** Define response by failure type/severity; fail closed when eligibility or exposure state is unreliable. Preserve the idea and failure diagnostics.


---

## Q6.47 — Retries and replacement orders decision recorded during conversation

**Status: RESOLVED**

**Decision.** Allow retries only under explicit configurable rules, link each attempt to the originating management decision, and reconcile broker/order state before retrying. Never retry blindly while the prior order's status is uncertain.


---

## Q6.28 decision recorded during conversation — evaluate opposing ideas against open position and outstanding orders

**Status: RESOLVED**

**Decision.** When a new opposing trade idea arrives, position management must evaluate it using both the actual open position and any outstanding unfilled order. The remaining order quantity may still fill and change exposure, so current filled position alone is not a complete representation of the system's exposure.

**Record linkage.** Preserve the new entry evaluation independently and record the position-management decision using the position and outstanding-order state at that time.

**OPEN.** The specific actions to take with an opposing idea (for example, cancel/replace an outstanding order, reduce/exit the position, reverse, or take no action) remain part of the broader position-management policy and are not decided here.

---

## Q6.27 decision recorded during conversation — track partial fills and remaining quantity

**Status: RESOLVED**

**Decision.** Record a partial fill immediately as an order-lifecycle event. Track the remaining unfilled quantity separately until it is filled, cancelled, or otherwise resolved.

**Position-management implication.** Partial fills change the actual position state and may affect subsequent position-management decisions. The system must not treat an order as either wholly unfilled or wholly filled when only part of its quantity has executed.

**Record linkage.** Link each fill event and remaining-order state to the originating order and the position-management decision that initiated it.

**OPEN.** Exact event schema, treatment of amendments/replacements, and reconciliation of order and position state remain open.

---

## Q6.26 decision recorded during conversation — separate management decisions from order outcomes

**Status: RESOLVED**

**Decision.** Record a position-management decision and the resulting order lifecycle/outcome as separate events. A decision to place an order is not the same as successful order submission, acceptance, or fill.

**Record separately.**
- The position-management action chosen and its rationale.
- The order action attempted and its result, including rejection, cancellation, failure to fill, partial fill, or successful fill as applicable.
- Links between the management decision and the associated order lifecycle events.

**Boundary.** Do not infer successful execution from an intent or decision to place an order. Preserve the decision record even when the order fails or does not fill. Detailed event schema, status taxonomy, and handling of retries or replacement orders remain open.

---

## Q6.25 decision recorded during conversation — record no-action decisions caused by current state

**Status: RESOLVED**

**Decision.** Record a position-management decision even when no action is taken because the current position or order state prevents or makes action inappropriate. Examples include an outstanding order already handling the same idea or a current state that means no further action should be taken.

**Required record.** Preserve the no-action decision as a separate position-management event and record the reason. Link it to the associated entry evaluation where applicable.

**Record integrity.** Do not let a no-action outcome erase the entry evaluation or make an eligible evaluation appear ineligible. This logging helps distinguish an intentional position-management decision from a missed evaluation or a system failure.

**OPEN.** The detailed reason taxonomy and record schema remain open.

---

## Q6.24 decision recorded during conversation — record ignored eligible ideas

**Status: RESOLVED**

**Decision.** If an entry evaluation is eligible but position management decides to ignore the idea, record the ignore action as a separate position-management decision linked to the original entry evaluation. Record the reason for ignoring it.

**Record integrity.** Preserve the entry evaluation and its eligibility result unchanged. The position-management decision explains what happened after eligibility; it must not rewrite the entry evaluation or make it appear that the idea was ineligible.

**OPEN.** The exact reason taxonomy and detailed record schema remain open.

---

## Q6.23 decision recorded during conversation — eligible evaluation while a position is open

**Status: RESOLVED**

**Decision.** If a new evaluation is eligible while a position is already open, it proceeds to the **position-management layer**. Position management decides what action, if any, is appropriate using the current position and order state. Possible actions include ignoring the new idea, adding to the position, exiting, or reversing, subject to the separately defined position-management rules.

**Boundary.** The new evaluation's eligibility result remains its own recorded result; it does not itself authorize or dictate an order or position-management action. Do not impose a default block solely because a position is already open. The exact position-management policy remains open for its dedicated review.

---

## Q6.22 decision recorded during conversation — repeated ideas and future additional rules

**Status: RESOLVED — NO CURRENT RULE; FUTURE EXTENSIBILITY REQUIRED**

**Decision.** A trade idea that is blocked now may become tradable later as market conditions change. This is normal behavior. There is currently **no additional rule** that automatically blocks or otherwise changes the handling of a new evaluation merely because an earlier evaluation of a similar/repeated idea was blocked.

**Future extensibility.** The design must leave room to introduce additional rules for repeated ideas in the future, if desired. Do not invent or activate such a rule in V1. Any future rule must be explicitly specified and versioned rather than silently inferred from prior evaluations.

**Record integrity.** Each new strategy-generated occurrence is evaluated independently and receives its own timestamped record and eligibility decision. Earlier completed evaluations remain immutable and must not be overwritten by later outcomes.

**Boundary.** This decision does not itself authorize an order. The new evaluation remains subject to the configured aggregation, final execution-eligibility, position-management, and order-state rules.

---

## Q6.21 decision recorded during conversation — immutability of completed eligibility decisions

**Status: RESOLVED**

**Decision.** Once a trade-idea evaluation is completed, its final execution-eligibility decision is fixed and must not be changed or overwritten later. If the strategy generates the idea again, the execution model evaluates that new occurrence independently and records a new evaluation event and eligibility decision.

**Implications.**
- Preserve the original evaluation and its blocked/eligible result exactly as recorded.
- A later strategy-generated occurrence is a new evaluation, even if it is in the same direction or near the same price.
- Later market changes do not retroactively alter the earlier decision.
- Keep each evaluation separately timestamped and linked as appropriate; do not merge repeated ideas or overwrite their historical results.

**Boundary.** This decision establishes immutability of a completed evaluation record. It does not define whether a new evaluation may lead to an order; that remains governed by the applicable aggregation, final eligibility, position-management, and order-state rules.

---

## Q6.20 decision recorded during conversation — final execution eligibility and block reasons

**Status: RESOLVED**

**Decision — final gate.** The execution model ultimately evaluates **Final execution eligibility**. This is the final gate that determines whether the trade idea is eligible to proceed toward execution.

Eligibility may be blocked for multiple possible reasons, including (but not limited to):
- Aggregate score/decision does not meet the configured requirement (e.g. low score).
- A designated critical model fails.
- Another execution-eligibility rule or failure condition applies.

Do not assume there are only two possible fields or two possible reasons. The record may contain multiple supporting fields and more than one relevant reason.

**Decision — record every idea consistently.** Every trade idea and its supporting context must be recorded regardless of final execution eligibility. A blocked idea is still a research observation. Record the final eligibility result and the reason or reasons for it, along with the model outputs, supporting measurements, timestamped execution-model logs, and relevant market context.

**Boundary.** Different block reasons are treated consistently for the purposes of recording and research. This does not mean their operational causes or later remediation are identical. The aggregation result and any intermediate fields remain useful supporting information, but **Final execution eligibility** is the ultimate execution gate.

**OPEN.** The exact eligibility output schema, taxonomy and multiplicity of block reasons, precedence when multiple reasons apply, and handling of downstream order submission failures remain open for later specification.

---

## Q6.19 decision recorded during conversation — critical-model configuration

**Status: RESOLVED**

**Decision.** Critical-model designations must be configurable and versioned rather than hard-coded. This allows criticality policy to be changed and evaluated explicitly while preserving traceability of which policy applied to each trade-idea evaluation.

**Change process.** Changes to critical-model designations follow the previously agreed operating constraints: make changes additively where possible, protect fundamental functions such as data collection, and switch the active configuration only during off-market hours.

**OPEN.** The exact configuration schema, initial critical-model list, validation checks, and rollback procedure remain open. No model is designated critical by this decision alone.

---

## Q6.18 decision recorded during conversation — handling a model error in aggregation

**Status: PARTIALLY RESOLVED**

**Decision — non-errored models and aggregation.** If one model errors, preserve its `NO_SIGNAL` status and diagnostic record, exclude only that errored model from score calculation, and allow the remaining models to evaluate normally. The aggregation and score-calculation logic otherwise runs as configured; an error in one model does not automatically invalidate all other model outputs.

**Critical-model exception — explicitly OPEN for definition.** Some models may be designated **critical**. If a critical model fails, the idea must be recorded but not traded, even if the aggregation layer's score/decision would otherwise approve it. Which models are critical, how criticality is configured/versioned, and the exact failure criteria remain to be defined.

**Boundary.** The aggregation layer determines the score/decision from the available non-errored model outputs, but the eventual execution eligibility policy may apply the separately defined critical-model failure rule. Do not invent the critical-model list or treat every model as critical by default.

---

## Q6.17 decision recorded during conversation — model evaluation errors

**Status: RESOLVED**

**Decision.** If a model errors while evaluating a trade idea, represent the model's evaluation status as the already-defined `NO_SIGNAL` status. Do not translate the error to numeric score `0`, and do not introduce a new top-level output status solely for this case.

**Required diagnostic record.** Even though the externally reported evaluation status is `NO_SIGNAL`, record:
- The exact error details/message.
- Supporting context needed to diagnose the failure, such as the model/version/configuration, evaluation event and timestamp, and relevant available input/data context.

This preserves the agreed status vocabulary while retaining enough detail to investigate errors. `NO_SIGNAL` still must not be conflated with neutral numeric bias (`0`).

**Boundary / OPEN.** The exact diagnostic schema and which context fields are mandatory remain open. This decision defines the reported status and logging requirement, not the final system-level response to a model failure or whether a trade can proceed when one model fails; that policy remains OPEN.

---

## Q6.16 decision recorded during conversation — updating model versions during research

**Status: RESOLVED**

**Decision.** Do not assume that running multiple model versions in parallel is practical. The approach is to control the risk of changes and minimize disruption:

- Keep changes **purely additive** wherever possible.
- Do not disrupt fundamental functions, especially data collection.
- Change/switch the active model version only during **off-market hours**.
- Continue versioning model behavior/scoring changes and preserve the model version/configuration associated with each evaluation.

**Boundary.** Parallel comparison of old and new versions is not a V1 requirement. This decision prioritizes continuity of data collection and safe operational changes over simultaneous version testing.

**OPEN.** Define the deployment/change procedure, verification steps, rollback procedure, exact definition of off-market change windows, and how to handle changes that cannot be made purely additive.

---

## Q6.15 decision recorded during conversation — versioning model changes

**Status: RESOLVED**

**Decision.** Version model changes, including changes to model behavior and scoring criteria. Each trade-idea evaluation must be traceable to the model version and relevant configuration that produced it, so observations from different rule sets are not silently mixed during research.

**Research implication.** Preserve version/configuration identifiers alongside the raw timestamped execution-model logs and associated trade-idea records. This supports fair comparison between old and new model behavior.

**OPEN.** The exact versioning scheme, which configuration changes require a version bump, and how versions/configurations are stored and exposed in logs remain open for implementation specification.

---

## Q6.14 decision recorded during conversation — NO_SIGNAL / NO_DATA frequency research

**Status: RESOLVED — separate research topic outside V1**

**Decision.** Keep `NO_SIGNAL` and `NO_DATA` as distinct statuses, separate from numeric score `0`. However, evaluating their subsequent outcomes or predictive value is **out of scope for V1** and must be tracked as a separate research topic.

**Initial research step.** First measure the frequency of `NO_SIGNAL` and `NO_DATA` occurrences. Use those frequency results to decide whether further investigation is worth the effort. Do not commit to a full outcome/predictive analysis before this initial frequency check.

**Boundary.** This scope decision does not permit collapsing these statuses into score `0`; the existing output/data distinction remains in force. The separate frequency study's implementation and reporting details remain OPEN.

---

## Q6.13 decision recorded during conversation — score-level outcome analysis and logging granularity

**Status: RESOLVED**

**Decision — evaluate all five score levels independently.** For each model, analyze outcomes associated with each of its five possible numeric scores (`-2`, `-1`, `0`, `+1`, `+2`) separately. Do not assume in advance that stronger positive or negative scores produce better or more directional outcomes; validate the relationship empirically before relying on score ordering in execution decisions.

**Critical data distinction — execution-model logs vs order-flow constructs.** Do not conflate the existing 1-second snapshots/aggregations of order-flow constructs with the logs emitted by execution models.

- **Execution-model logs:** Store the model's outputs and relevant log events as raw records with timestamps. These are absolute observations/events and must not be reduced to 1-second snapshots or aggregated in a way that loses the original event-level information.
- **Order-flow constructs:** Constructs such as footprint and liquidity maps may be aggregated or represented at 1-second granularity for V1 research, as already decided. This does not imply that execution-model logs can be treated the same way.

**Boundary.** Keep timestamps and linkage to the relevant trade-idea evaluation so model outputs can be aligned with market context and later analyzed without losing event timing.

---

## Q6.12 decision recorded during conversation — data granularity for model research

**Status: RESOLVED**

**Decision.** For V1, use the existing price/time-series data and order-flow constructs stored at **1-second granularity** as the standard research context. This is the intended raw research data for evaluating model scores and outcomes.

**Tick-level capture.** Do not add tick-by-tick raw capture for this purpose at present. Revisit it only if later analysis demonstrates that 1-second data is insufficient to answer a specific research question.

**Data reduction.** Keep the 1-second data initially. If data volume, storage, or analysis performance becomes problematic, consider reducing or aggregating it at that point rather than pre-emptively reducing the research resolution.

**Boundary.** This decision establishes the initial research granularity; it does not prohibit revisiting higher-resolution capture or later data reduction if evidence shows either is needed.

---

## Q6.11 decision recorded during conversation — independent per-model evaluation

**Status: RESOLVED**

**Decision.** V1 research must evaluate each model's predictive contribution independently of the final aggregation decision and whether a trade was actually executed. The research record must allow analysis of whether a model's score/status predicted a favorable or unfavorable subsequent outcome even when:
- The aggregation layer rejected the idea.
- Position management prevented an otherwise accepted idea from being executed.
- The idea was evaluated while a position was already open.

Use the separately recorded model outputs, underlying measurements, market context, and subsequent outcomes to assess model quality apart from the aggregation and position-management policies.

**Outcome-label integrity.** Preserve the distinction between hypothetical outcomes for non-executed/rejected ideas and actual outcomes from executed trades. Define the outcome metric and evaluation horizon separately; do not assume a metric or horizon here.

**OPEN.** Exact predictive metrics, outcome definitions/horizons, handling of overlapping/repeated ideas, and methods for accounting for dependencies between observations remain open for later research design.

---

## Q6.10 decision recorded during conversation — position-management research logging

**Status: RESOLVED**

**Decision.** V1 must log position-management decisions as a distinct research record linked to the corresponding entry-evaluation event. At minimum, record:
- The position state when the new idea arrives, including whether a position is open and its direction.
- Relevant order state and protective/target-order state needed to understand the decision.
- The position-management decision (e.g. open, ignore, add, exit, reverse, or other defined action).
- The reason/rationale for that decision.
- Any resulting order changes, fills/executions, position transitions, and realized outcome where applicable.

**Research purpose.** Keep this record linked to the entry-evaluation scores, statuses, underlying measurements, and market context. This enables later analysis to distinguish a weak/poor trade idea from a reasonable idea that was handled poorly because of the existing position or the position-management policy.

**OPEN.** Exact schema, required state fields, decision reason taxonomy, linkage identifiers, and handling of partial fills/rejections/cancelled orders remain open for later specification.

---

## Q6.9 decision recorded during conversation — entry evaluation vs position management

**Status: RESOLVED**

**Decision.** Keep these responsibilities separate:

- **Entry evaluation:** Evaluate and score each new strategy-generated trade idea using the model evidence and market context, independently of the action taken on any existing position.
- **Position management:** Decide what action, if any, is appropriate given the current position and the new idea. Potential actions include opening a position when flat, ignoring a repeated idea, adding to an existing position, exiting, or reversing—but the policy for choosing among these actions remains OPEN.

**Boundary.** The entry-evaluation result does not itself authorize an order or determine position-management behavior. Position state and order/SL/TP state must be considered by the separate position-management responsibility.

**OPEN.** Define the interfaces and state inputs between these responsibilities, the policy for each repeated/opposing-idea case, and the order/position transition rules.

---

## Q6.8 decision recorded during conversation — repeated ideas and position-state scenarios

**Status: PARTIALLY RESOLVED**

**Decision — preserve each evaluation event.** Every strategy-generated evaluation event must be recorded separately, even when the strategy repeats the same direction around the same price shortly after an earlier idea. Do not merge repeated evaluations into a single record. Related/repeated ideas may be linked for analysis, but each event retains its own model outputs and context.

**Three position-state cases to evaluate separately.**

1. **Position is flat; strategy repeats a same-direction idea around the same price.** The earlier idea may have been accepted or rejected, but no position is currently open. Evaluate the new idea as a separate event. The policy for whether to accept it, reject it, or otherwise relate it to the earlier idea remains OPEN.

2. **A position is open; strategy produces another idea in the same direction.** This raises a separate position-management decision: ignore the new idea or add to/double down on the existing position. Neither behavior is selected yet.

3. **A position is open; strategy produces an idea in the opposite direction.** The possible responses identified are:
   - Exit the existing position.
   - Reverse the position.
   - Ignore the opposing idea and wait for the existing position's stop-loss/take-profit outcome.

   No response is selected yet.

**Boundary.** These are distinct decision cases and must not be collapsed into a generic “duplicate idea” rule. Recording a new evaluation event does not itself authorize a new order, position add, exit, or reversal.

**OPEN.** Define the policy for each case, including how the current position, pending orders, stop-loss/take-profit orders, and new idea are considered. Also define how repeated events are linked for research and how hypothetical outcomes are measured when an idea occurs while a position is already open.

---

## Q6.7 decision recorded during conversation — logging rejected ideas

**Status: RESOLVED**

**Decision.** Apply the same model-output and market-context logging requirements to **every eligible strategy-generated trade idea**, whether the unified execution model accepts or rejects it.

For rejected ideas, record:
- Each model's individual score and status.
- The underlying model measurements/features.
- The relevant timestamped price/time-series and 1-second order-flow context.
- The rejection decision/disposition and the information needed to evaluate the idea's subsequent hypothetical outcome.

For accepted ideas, retain the same evidence record plus actual order/execution details and realized outcome where applicable.

**Outcome-label integrity.** Keep hypothetical outcomes for rejected ideas clearly distinguished from actual executions and realized P&L. A rejected idea must not be represented as if a trade was placed or profit/loss was realized. Define the hypothetical outcome measurement and evaluation horizon separately.

**OPEN.** The exact hypothetical outcome method/horizon, handling of ideas that overlap or recur, and any additional raw tick-capture triggers remain open.

---

## Q6.6 decision recorded during conversation — trade-idea research logging

**Status: RESOLVED for logging requirement; capture implementation details remain OPEN**

**Decision — required research record.** V1 must record enough information to evaluate which model evidence improves trade outcomes. For each strategy-generated trade idea, retain:
- Each model's individual score and status, including the distinct `NO_SIGNAL` and `NO_DATA` states.
- The underlying measurements/features used to derive each model's output.
- Relevant market context at evaluation time.
- The eventual trade outcome, including the idea's decision/execution disposition and outcome data needed for later analysis.

**Market-context clarification.** “Relevant market context” includes the time-series and price data, as well as the order-flow constructs already being stored at 1-second resolution. Preserve timestamps and align context with the trade idea/evaluation so the record can be reconstructed and analyzed later.

**Optional higher-resolution capture.** The user proposes that, if needed, the system may also store raw tick-by-tick price and order-flow-construct data for a short duration around trade-idea entry evaluation, enabling later inspection of events at finer resolution than the 1-second series.

This higher-resolution capture is a research option to investigate, not yet a mandatory always-on requirement.

**OPEN.** Define the capture window before/after evaluation, which events/ideas trigger capture, whether raw tick capture is always on or selectively enabled, exact payload/schema, storage/retention policy, storage cost limits, timestamp alignment, and how the captured data is linked to the trade-idea record. Do not assume a duration or implement continuous raw capture without a separate decision.

---

## Q6.5 decision recorded during conversation — conflicting model evidence

**Status: RESOLVED**

**Decision.** Preserve each model's result independently, including conflicting results, and pass the collection of results to a separate aggregation layer for the eventual trade decision. For example, a footprint score of `+2` and an absorption score of `-2` must both remain visible in the record.

**Override/veto boundary.** No model automatically overrides another under the current decision. A model-specific veto may be introduced only through an explicit later decision supported by research; do not infer veto behavior from the score alone.

**OPEN.** The aggregation method, weighting, conflict resolution, acceptance thresholds, and any explicit veto rules remain undecided.

---

## Q6.4 decision recorded during conversation — no signal/data versus neutral bias

**Status: RESOLVED**

**Decision.** Keep **no signal** and **no data / unable to evaluate** separate from the numeric score `0`.

- `0` means the model evaluated the available evidence and found a neutral bias / no meaningful directional support or contradiction.
- **No signal** means the model did not identify a signal for this idea.
- **No data / unable to evaluate** means the model could not produce a reliable evaluation, for example because data is missing, insufficient, incomplete, or unreliable.

**Invariant.** No signal/data is **not** equivalent to zero bias. Do not encode unavailable or unevaluable evidence as `0`; preserve a distinct status in the model output and logs.

**OPEN.** The exact output schema/status labels, and how no-signal versus no-data cases are treated by downstream aggregation or eligibility checks, remain OPEN.

---

## Q6.3 decision recorded during conversation — cross-model score comparability

**Status: RESOLVED**

**Decision.** All models use the same five-point signed output interface (-2, -1, 0, +1, +2), but scoring criteria may be model-specific initially. A given score must not be assumed to represent the same empirical strength across different models until research and, if warranted, calibration establish comparability.

**Implication.** Preserve each model's raw evidence/measurements and its assigned score so later analysis can assess whether scores are predictive and comparable. Do not assume that scores should be directly summed or equally weighted solely because they share a numeric scale.

**OPEN.** The model-specific score definitions, validation methodology, and any later cross-model calibration or aggregation rule remain undecided.

---

## Q6.2 decision recorded during conversation — model-level evidence scale

**Status: RESOLVED for scale shape; score semantics remain OPEN**

**Decision.** Each model, as a whole, supports five possible signed outcomes:

| Score | Direction |
|---:|---|
| -2 | Negative / contradictory side |
| -1 | Negative / contradictory side |
| 0 | Neutral / no signal |
| +1 | Positive / supportive side |
| +2 | Positive / supportive side |

The scale must preserve both direction and strength. The numeric labels above establish the five outcomes and their ordering only; they do **not** yet define the precise conditions that distinguish -2 from -1 or +1 from +2.

**OPEN.** Define the model-specific or shared semantics for each score, whether the meanings are comparable across models, how underlying measurements map to a score, and how scores are aggregated or used in trade acceptance. Do not assume that scores can simply be summed until aggregation is explicitly decided.

---

## Q6.1 decision recorded during conversation — per-model evidence outputs

**Status: RESOLVED**

**Decision.** For every eligible strategy-generated trade idea, each model must report its own result independently using these categories:
- **Supports** the proposed trade direction.
- **Neutral / no signal** — no meaningful support or contradiction.
- **Contradicts** the proposed trade direction.

Record these per-model outputs so their individual and combined contributions can be studied empirically.

**Boundary.** This defines the evidence-reporting interface only. It does not define how results are aggregated, whether some models have greater weight, whether any result acts as a veto, or what threshold is required to accept a trade. Those decisions remain OPEN.

---

## Q5.8 decision recorded during conversation — session extremes role

**Status: PARTIALLY RESOLVED**

**Decision — importance and reversal evidence.** Session highs and lows are important levels, but by themselves they do not provide as much evidence for a reversal as VAH/VAL and POC. Do not treat a session extreme as an equally strong reversal signal by default.

**Decision — target and contextual role.** Like VWAP, session extremes will mostly be used as target identifiers (one input among others), while remaining available as context.

**Conditional tradability.** Under certain conditions, a session high or low may become tradable in its own right. The conditions that promote it from a target/context reference to entry evidence remain OPEN and must be defined later; do not assume an automatic reversal rule.

---

## Q5.7 decision recorded during conversation — higher-timeframe vs primary-timeframe structure

**Status: RESOLVED**

**Decision — overall directional bias.** Higher-timeframe market structure is used to identify the overall market bias. This role applies whether market structure is the core strategy or later becomes supporting evidence.

**Decision — trade-idea generation.** Trade ideas are generated by the primary (selected/configured) timeframe's structure or strategy. Currently, market-structure rules are the only strategy available and therefore trigger trade ideas. In the future, market structure may become supporting evidence rather than the core strategy; that future possibility does not change the separation between higher-timeframe bias and primary-timeframe idea generation.

**OPEN.** The exact higher-timeframe bias rules, the selected primary timeframe and its structure/strategy rules, and how bias influences evaluation or execution remain open for separate decisions.

---

## Q5.6 decision recorded during conversation — VWAP role

**Status: PARTIALLY RESOLVED**

**Decision — potential entry/location role.** VWAP can be a tradable location for a counter-trade when the surrounding market conditions support it. This is contextual, not an absolute rule. One example is VWAP overlapping with the Point of Control (POC) while the underlying strategy also suggests a trade in the same direction. This example is illustrative, not a finalized requirement or complete entry rule.

**Decision — target-setting role.** More commonly, VWAP is one of several inputs used to determine a trade target. It is not the sole target condition.

**OPEN.** Define suitable market conditions for a VWAP counter-trade, whether/how VWAP bands participate, what qualifies as meaningful overlap with POC, how strategy direction is combined with VWAP evidence, and how VWAP contributes to target selection alongside other inputs. No rigid entry or target formula is established by this decision.

---

## Q5.5 decision recorded during conversation — VAH/VAL role and tradeability

**Status: PARTIALLY RESOLVED — rule design deferred**

**Decision — role and significance.**
- Value Area High (VAH) and Value Area Low (VAL) are potential reversal zones, similar to LVNs.
- They have significance comparable to LVNs, potentially greater.
- The structural distinction is that an LVN is a price range, while VAH and VAL are each a single price level.
- VAH/VAL are considered tradable concepts; unlike HVNs, they are not informational-only features.

**Observed behavior / hypothesis to investigate.** Around a high-quality VAH/VAL, price may cross the level slightly and then reverse aggressively. This is an observed pattern to investigate, not yet a rigid execution rule.

**Explicitly OPEN / deferred.** How to translate this behavior into deterministic rules remains unanswered. Definitions of a high-quality VAH/VAL, acceptable penetration beyond the level, reversal/aggression confirmation, timing window, invalidation, and how these conditions interact with other execution-model evidence are deferred for a later review. Do not invent or implement these rules from this note alone.

---

## Q5.4 decision recorded during conversation — HVN research scope

**Status: RESOLVED**

**Decision.** Treat High Volume Nodes (HVNs) similarly to LVNs for exploratory analysis. The work to analyze HVNs can run alongside LVN analysis, and the same broad distinction applies: characterize whether an HVN is meaningful and assess its location relative to a trade idea when studying relevance. Keep any qualification and distance parameters configurable; the exact metrics and thresholds remain OPEN.

**Execution boundary.** HVNs are currently a miscellaneous/research concept and are not used by the user. Their presence, absence, or measured characteristics **must not affect trade execution at this stage**. HVN results may be logged and analyzed as research evidence, but they must not change whether the unified execution model accepts or rejects a trade idea unless this decision is explicitly revisited later.

---

## Q5.3 decision recorded during conversation — LVN qualification vs trade relevance

**Status: RESOLVED**

**Decision — qualifying an LVN.** Evaluate LVN qualification using both:
- **Relative depth:** how low the volume is compared with a relevant surrounding-profile or baseline measure.
- **Width:** how many price levels/ticks the low-volume region spans.

Keep each parameter and its threshold configurable for research. These parameters determine whether a region qualifies as a potentially meaningful LVN; they do not, by themselves, determine whether a trade should be taken there.

**Decision — relevance to a specific trade idea.** Also evaluate the LVN's location relative to the trade idea's zone. A region may qualify as a valid, meaningful LVN but receive little or no preference for a particular idea if it is too far from that idea's zone. The allowed distance must be configurable.

**Scope boundary / OPEN details.** The exact relative-depth formula and comparison baseline, width definition, distance reference (including how the trade idea zone is represented), distance metric/units, initial thresholds, and cutoff for “too far” remain OPEN for research. This decision does not by itself establish that a trade should be accepted; the other execution-model conditions and final aggregation rule remain separate.

---

## Q5.1 decision recorded during conversation — trade-location sources

**Status: RESOLVED**

**Decision.** Make all four location sources available in V1 as configurable research inputs:
- Volume profile (including candidate LVN/HVN and Value Area locations).
- VWAP (and bands if separately specified).
- Higher-timeframe market structure.
- Session extremes (session high and low).

**Research implication.** Evaluate the usefulness of each location source and its interactions with the entry models through testing. Do not assume every source is required for every model or that all sources contribute equally.

**Scope boundary.** Exact profile construction/anchors, VWAP settings, market-structure definitions, session boundaries, and per-model location requirements remain OPEN.

---

## Q4.6 decision recorded during conversation — failed aggression vs absorption

**Status: RESOLVED**

**Decision.** Failed aggression is a broader concept and can exist independently of absorption.

The concepts are distinct:
- **Failed aggression:** Aggressive activity fails to produce the expected price progress.
- **Absorption attempt:** Failed aggression has supporting evidence of high resting liquidity from the DOM/heatmap.
- **Confirmed absorption completion:** A delta flip confirms that the absorption attempt completed.

Do not automatically classify every failed-aggression event as absorption. Supporting resting-liquidity evidence is required for an absorption attempt, and the delta-flip confirmation remains a separate stage.

---

## Q4.7 decision recorded during conversation — exhaustion definition and sequence

**Status: PARTIALLY RESOLVED**

**Decision.** Support both measurements as separate, configurable exhaustion signals:
- **Footprint-based exhaustion:** declining volume at successive price levels near the price extreme.
- **Big-print-based exhaustion:** declining size of aggressive trades near the price extreme.

**Sequence constraint.** Exhaustion comes after absorption is complete (the current reviewed definition uses a delta flip as the confirmation of completion). Do not treat exhaustion as an earlier substitute for failed aggression or absorption completion.

**Operational concept.** Do not require a fixed count of failed-aggression events: the number varies from situation to situation and cannot be reliably quantified as a universal count. Instead, define exhaustion through the relationship between price response and weakening aggressive activity:
- Price begins moving in the direction opposite to the aggression that failed.
- Big-print size of aggressive trades in the original aggression direction decreases.
- Footprint volume in the original aggression direction decreases.

Keep footprint-based and big-print-based weakening as separate configurable signals. Reuse Q3.2's configurable time window and price-progress measurement for the opposing price response, evaluating continuously intrabar rather than waiting for bar close. The exact window duration, thresholds, and rule for combining the observations remain OPEN; numerical thresholds stay configurable for research.

---

## Q4.9 decision recorded during conversation — price-response measurement for exhaustion

**Status: PARTIALLY RESOLVED**

**Decision.** Reuse the Q3.2 successful/failed-aggression framework for exhaustion price response rather than creating a separate price-measurement mechanism.
- Use Q3.2's configurable time window and configurable price-progress measurement.
- Evaluate continuously intrabar; do not require bar close as the default.
- Exhaustion adds the contextual requirement that price moves opposite to the aggression that failed while aggressive activity in the original direction weakens.

**OPEN research questions.** Exact window duration, price-progress metric settings, thresholds, and the configurable combination of footprint/big-trade weakening signals remain open. Keep numerical thresholds configurable.

---

## Q4.8 decision recorded during conversation — combining exhaustion measurements

**Status: PARTIALLY RESOLVED**

**Decision.** Make the combination rule configurable so research can compare using either signal, requiring both, or another configurable combination/scoring rule:
- Footprint-based declining aggressive volume.
- Big-print-based declining aggressive-trade size.

**Important dependency/correlation caveat.** Both measurements are derived from the same underlying trade/execution data, so they are related views of aggressive activity rather than fully independent evidence. Do not automatically count them as two independent confirmations or double-count their contribution when combining model evidence.

**Sequence and context retained from Q4.7.** Evaluate exhaustion only after absorption is complete. Do not require a fixed count of failed-aggression events. The exhaustion interpretation depends on price moving opposite to the aggression that failed while aggressive activity in the original direction weakens.

**OPEN research questions.** The precise combination/scoring rule, window duration, and thresholds remain open. Reuse Q3.2's configurable time window and price-progress measurement for the opposing price response, with continuous intrabar evaluation. Keep numerical thresholds configurable for research.

---

## Q4.5 decision recorded during conversation — absorption stages and delta-flip confirmation

**Status: PARTIALLY RESOLVED**

**Conceptual distinction.**
- **Absorption attempt / absorption happening:** Failed aggression plus supporting resting-liquidity evidence indicates that passive liquidity is attempting to absorb aggressive orders.
- **Absorption complete/confirmed:** A delta flip is confirmation that the absorption has completed.

An absorption attempt does not guarantee success. Aggressors may eventually overcome the resting liquidity; in that case, the expected delta flip will not appear, and the attempt must not be treated as confirmed absorption.

**Delta inputs in scope.** For this absorption-completion/confirmation question, consider **price-level delta and bar delta**. Cumulative delta may remain available as a general research input for other models/questions, but it is not part of the current candidate set for confirming absorption completion.

**Delta-flip definition — provisional working hypothesis.** Begin with a delta sign change accompanied by price failing to extend further in the original direction. Keep the simpler sign-change-only definition available as a separate configurable alternative for comparison. This is a research starting point, not a final rule; do not assume the stricter definition is always superior.

**OPEN research questions.** Compare the two delta-flip definitions and determine whether bar delta, price-level delta, or both should be used in each situation. How measurement selection varies by market context/entry model must be answered through research and analysis. Do not assume one universal delta measurement.

---

## General numerical-parameter calibration principle recorded during conversation

**Decision.** For numerical parameters throughout the reviewed rules, begin with a reasonable temporary baseline guess so implementation and research can proceed. Keep values configurable, explicitly treat them as provisional hypotheses rather than validated constants, and revise/calibrate them iteratively based on data analysis and testing. Do not mistake an initial guess for a final choice.

---

## Q4.4 decision recorded during conversation — delta measurements

**Status: PARTIALLY RESOLVED**

**Decision.** Support all three delta measurements as configurable research inputs:
- **Bar delta:** net aggressive buying versus selling across a bar.
- **Price-level delta:** delta at an individual footprint price level.
- **Cumulative delta:** running delta total over a session or configured window.

**Initial working hypothesis.** Bar delta may be more useful for confirming absorption completion, but this is a provisional guess only—not a final decision or a validated conclusion. Compare bar delta against price-level delta (and relevant combinations) during analysis. If bar delta is selected for a particular model/context, determine the appropriate bar timeframe through analysis rather than hard-coding one now.

**General calibration principle.** Start with a reasonable temporary guess for numerical values and settings so research can proceed. Keep those values configurable, treat them as hypotheses rather than established facts, and iteratively revise them based on analysis and testing. This principle applies broadly to numerical parameters across the reviewed rules, not only delta settings.

**Research implication.** Evaluate whether each measurement adds useful information, separately for each entry model. Do not assume the measurements are interchangeable or equally useful.

**Scope boundary.** Exact calculation conventions, reset boundaries for cumulative delta, the chosen confirmation measurement(s), and any bar timeframe remain OPEN for later specification.

---

## Q4.3 decision recorded during conversation — absorption measurement window

**Status: RESOLVED**

**Decision.** Make the absorption measurement window configurable so different time windows can be tested.

**Research/implementation implication.** Preserve timestamps for DOM/liquidity events and executed trades so the two evidence streams can be aligned consistently under each tested window.

**Scope boundary.** The initial/default window, exact event-alignment rule, and whether multiple windows are evaluated concurrently or one configuration at a time remain OPEN.

---

## Q4.2 decision recorded during conversation — absorption evidence sources

**Status: RESOLVED**

**Decision.** Absorption evidence must combine both:
1. **Resting-liquidity evidence:** Full depth of market (DOM) from the Rithmic feed. This is also the source used to construct Flow's liquidity heatmap.
2. **Executed-volume evidence:** Footprint data showing failed aggression, using the same bid/ask trade execution data used for footprint charts.

The combined evidence—visible resting liquidity from DOM/heatmap plus failed aggression from executed-volume footprint—supports classifying an event as absorption.

**Scope boundary.** Neither source is to be silently substituted for the other in the agreed definition. Thresholds, DOM liquidity measurement, persistence/replenishment requirements, proximity, and measurement windows remain OPEN and configurable where numerical.

---

## Q4.1 decision recorded during conversation — absorption

**Status: PARTIALLY RESOLVED**

**Decision.** Absorption is supported when high resting liquidity is visible and supports the failed-aggression interpretation. Failed aggression alone is not automatically sufficient to label an event as absorption.

**Baseline and configuration approach.** Use provisional baseline guesses to get initial research/testing started, but keep every numerical threshold configurable. Treat baseline values as starting hypotheses, not approved universal constants; research and calibrate them against data later. No specific numeric values have been selected yet.

**OPEN research questions.** The liquidity source/measurement, comparison baseline for “high,” initial baseline values, required persistence or replenishment, proximity to the relevant price/zone, measurement window, and exact relationship between the liquidity evidence and failed aggression remain OPEN. Do not hard-code thresholds or treat provisional guesses as validated.

---

## Q3.5 decision recorded during conversation — aggression definition, data source, and meaningful-aggression inputs

**Status: PARTIALLY RESOLVED**

**Decision.** Use bid/ask trade execution classification from the existing market-data feed as the base definition of aggression:
- Trades executed at the ask are classified as aggressive buying.
- Trades executed at the bid are classified as aggressive selling.

The user reports that the feed provides accurate bid/ask execution data and that this is the same data used to generate footprint charts.

**Meaningful-aggression inputs.** Preserve all five inputs as separate signals:
- Big aggressive prints.
- Strong positive/negative delta.
- Diagonal imbalances.
- Stacked imbalances.
- Repeated aggressive prints or bursts.

Also support both a configurable weighted numerical score and configurable rule-based combinations for models that need an aggregate aggression interpretation. Individual signals must remain inspectable, and each entry model may choose the approach that fits it. The score's normalization, weights, thresholds, and model-specific combination rules remain OPEN.

**Correlation caution.** These inputs are not necessarily independent; some derive from the same underlying trade/execution data. Do not automatically count correlated measurements as independent confirmations or double-count their contribution in a combined score.

**Conflict handling (Q3.5-B).** Preserve disagreement explicitly. The system should retain each individual signal and record which inputs agree or disagree alongside any combined result. A strong net score must not hide material conflict. The combined score/rule result may still be produced, but the disagreement must remain visible to the entry model and research logs. Exact definitions of material conflict and any model-specific response remain OPEN.

**Combined-score architecture (Q3.5-A).** Support both:
- **Weighted numerical scoring:** normalize component signals and combine them with configurable weights; and
- **Rule-based combinations:** evaluate configurable logical conditions among signals rather than requiring a weighted sum.

Individual entry models choose which approach to use. These are alternative modeling tools, not evidence that every signal is independent. Keep component outputs visible for analysis; weights, normalization, thresholds, and exact rules remain OPEN and will be developed from provisional guesses and calibrated through research.

**Configuration requirement.** Aggression thresholds, aggregation rules, and combined-score settings must be configurable. Numerical settings begin as provisional guesses and are calibrated through research.

**Scope boundary.** This settles the base data source/classification and the set of supported signals, but not the score design. Exact thresholds, burst/aggregation parameters, price-response windows, and success/failure criteria remain open unless resolved in their respective questions. Feed quality is the project's stated assumption and should still be monitored during validation.

---

## Q3.4 decision recorded during conversation — aggression proximity

**Status: PARTIALLY RESOLVED**

**Decision.** Keep aggression proximity configurable and support both measurement methods:
- Distance measured in ticks.
- Distance measured as a price range.

Research may compare the two methods independently or combine them. Do not restrict proximity to ticks alone or assume that the two measures are equivalent.

**Calibration principle.** Start with provisional guesses for any numerical values, keep them configurable, and test/calibrate them through research rather than treating them as validated constants.

**Scope boundary.** The exact reference price/zone, distance calculations, provisional values, and how independent/combined modes are implemented remain OPEN.

---

## Q3.3 decision recorded during conversation — repeated aggressive prints

**Status: RESOLVED**

**Decision.** Support both:
- Analyze qualifying aggressive prints independently.
- Aggregate repeated prints into a single aggression burst.

For burst grouping, consider both:
- **Elapsed time** between qualifying big prints.
- **Price distance** between qualifying big prints.

Keep independent-print and burst representations configurable for research. Both grouping criteria must also be configurable. Their initial numerical values will be provisional guesses, then tested and calibrated through analysis like the other numerical parameters; do not treat any initial values as validated constants.

**Scope boundary.** The actual time window, permitted price difference, how the two criteria combine, and any reset/cluster-boundary rules remain OPEN for research. No specific numeric values are selected yet.

---

## Q3.2 decision recorded during conversation — successful versus failed aggression

**Status: PARTIALLY RESOLVED**

**Decision.** Keep these as separate measurements:
1. **Aggression:** how much aggressive buying or selling occurred.
2. **Price response:** track both the maximum favorable excursion after the print and the price at the end of the measurement window.
3. **Outcome:** use both measures to distinguish temporary favorable movement followed by reversal from sustained progress; classify the outcome as successful, failed, or inconclusive according to configurable rules.

Successful versus failed aggression must be determined by the **price response to aggressive volume**, not by aggressive volume alone.

**Measurement window.** Use a fixed time window after the aggression event. The duration remains open and configurable.

**Progress metric.** Keep the progress metric and outcome thresholds configurable per model so research can compare approaches.

**Successful-aggression zone hypothesis.** When a qualifying big aggressive buy/sell is followed by price movement in that same direction, initially anchor the marked aggressive-history level/zone at the price where the qualifying big print executed. Cluster-based expansion may be considered later, but it is not part of the initial rule. If price later returns to the marked area, the participants may defend it, but this is a hypothesis—not an assumption that they will still be present or defend. Evaluate the retest jointly with resting liquidity at that level/zone. Do not treat the marked zone alone as a standalone trade trigger.

**Evaluation timing.** Evaluate continuously intrabar as order-flow events arrive; do not wait for bar close by default.

**Bar semantics.** A bar has no significance in most cases when evaluating order-flow constructs directly. In selected cases, a bar (a defined time range) may be used as an abstraction to make data easier to handle. Bars are therefore an optional representation/aggregation unit, not the default source of significance for order-flow events.

**OPEN research questions.** Exact time-window duration, per-model progress formulas, success/failure thresholds, and conditions for an inconclusive outcome remain undecided. Do not invent or hard-code them before review and testing.

---

## Q3.1 decision recorded during conversation — big-trade threshold

**Status: PARTIALLY RESOLVED**

**Decision.** Start with a **fixed threshold of 30 contracts per individual print** as the provisional Gold baseline. Keep the threshold configurable. Investigate an adaptive threshold based on recent market activity later, after the fixed baseline has been tested.

**Calibration principle.** Thirty contracts is a temporary research guess, not a validated or universal threshold. As with numerical parameters throughout Flow_v2, start with a reasonable configurable value, then calibrate it through data analysis and testing. Do not treat this baseline as a final decision.

**OPEN research questions.** The adaptive rule is not decided yet. Do not assume a particular lookback, percentile, normalization method, session adjustment, or other formula before evidence supports it. The precise definition of “trade size” used for this threshold also remains OPEN.

**Research implication.** Record the threshold and method explicitly in configuration/experiment records. Establish results using the fixed 30-contract baseline first, then compare adaptive candidates through research.

---

## Q2.4 decision recorded during conversation — finished auction

**Status: PARTIALLY RESOLVED**

**Decision.** Use **exactly zero volume on the relevant footprint side at the extreme** as the initial finished-auction rule. Do not use a near-zero tolerance in the initial baseline.

**Concept recorded from the user's explanation.**
- In an uptrend, when price reaches the very top and the footprint shows zero volume on the relevant side, this may indicate unwillingness to trade at that extreme.
- In a downtrend, when price reaches the very bottom and the footprint shows zero volume on the relevant side, this may indicate that the auction is finished.
- This remains a provisional research rule, not a validated standalone signal.

**OPEN research question.** The exact mapping of which footprint side is relevant at each extreme must be established through research and analysis rather than assumed.

**Not yet specified.** The exact footprint field meant by “zero buys/sells,” whether zero must occur at the extreme row or across a defined set of rows, whether the bar must be complete, handling of missing/filtered data, and whether this acts as a standalone signal or supporting evidence all remain OPEN. If a later analysis suggests near-zero tolerance could help, it can be tested as a separate alternative rather than silently changing the initial rule.

---

## Q2.5 decision recorded during conversation — finished-auction role

**Status: RESOLVED**

**Decision.** A finished auction acts as **confirmation of another signal** and cannot independently trigger a trade idea. It may strengthen an existing idea when it appears at a relevant location, but it is neither mandatory for every move nor sufficient on its own.

**Implementation implication.** Keep the finished-auction read available as confirmatory evidence in the execution model. Do not allow it, by itself, to generate a trade idea. Its exact weighting or contribution to a combined score remains open for research if/when scoring is specified.

---

## Q2.6 decision recorded during conversation — stacked-imbalance minimum

**Status: PARTIALLY RESOLVED**

**Decision.** Start with **3 consecutive qualifying imbalance levels** as the provisional baseline for a stacked imbalance. Keep the required minimum configurable so research can compare alternatives.

**Calibration principle.** Three levels is a temporary starting guess, not a validated universal threshold. As with numerical parameters across Flow_v2, begin with a provisional configurable value and calibrate it through research and analysis.

**Scope boundary.** This decision resolves only the provisional minimum count and its configurability. The exact qualifying-imbalance criteria, how adjacency is interpreted under configurable footprint row grouping, and any context-dependent/adaptive requirement remain open for research.

## Q2.7 decision recorded during conversation — initial footprint parameter values

**Status: PARTIALLY RESOLVED**

**Decision.** Use the following provisional starting values for initial research:
- **Diagonal-imbalance ratio:** 300%.
- **Footprint row grouping:** 6 ticks per row.
- **Minimum volume:** 3 contracts per compared level.

Keep all three parameters configurable. These are temporary starting guesses for initial testing, not validated or universal thresholds. Calibrate them iteratively through research and analysis.

**Scope boundary.** This sets initial test values only. The exact imbalance comparison implementation and the minimum-volume filter's application to both compared levels remain subject to the previously recorded open questions; do not silently infer or change those semantics from the baseline values.

---

## Q2.3 decision recorded during conversation — footprint row grouping

**Status: RESOLVED**

**Decision.** Footprint row grouping will be supported and configurable so different grouping sizes can be tested. It is a separate configuration dimension from the diagonal-imbalance ratio and minimum-volume threshold.

**Scope boundary.** The available grouping modes, default grouping, and whether grouping is specified in ticks or another unit remain OPEN.

---

## Q2.2 decision recorded during conversation — imbalance minimum volume

**Status: RESOLVED**

**Decision.** Make the minimum-volume requirement configurable as a separate parameter from the diagonal-imbalance threshold.

**Research implication.** Test the ratio threshold and minimum-volume threshold independently and in combination. A qualifying imbalance must be evaluated against both configured criteria when minimum-volume filtering is enabled.

**Scope boundary.** The initial minimum-volume value, the exact volume measure to apply, and whether the filter is applied to both compared price levels or another defined footprint measure remain OPEN.

---

## Q2.1 decision recorded during conversation — diagonal imbalance threshold

**Status: RESOLVED**

**Decision.** Make the diagonal-imbalance threshold configurable so multiple threshold values can be tested. Do not hard-code 300% as the universal threshold.

**Scope boundary.** This decision establishes configurability only. The initial default/baseline, the exact comparison formula, minimum-volume requirements, and any distinction between diagonal imbalance and sweep/aggression thresholds remain OPEN and must be reviewed separately.

---

## Q1.3 decision recorded during conversation — execution and structure timeframes

**Status: RESOLVED**

**Decision.** Keep the execution timeframe and market-structure timeframe separate and configurable. They must not be forced to use the same bar size.

**Scope boundary.** This resolves the configurability/independence requirement only. The actual timeframe or bar configurations to use in testing, and the exact market-structure rules, remain OPEN.

---

## Q1.2 decision recorded during conversation — adaptive bar sizing

**Status: RESOLVED**

**Decision.** Use fixed, manually configured bar sizes for now. Do not implement automatic/adaptive changes to bar size in the current scope.

**Revisit condition.** Adaptive sizing may be reconsidered later if testing demonstrates a need. This is a deferral, not a permanent rejection.

---

## Q1.1 decision recorded during conversation — bar size configurability

**Status: RESOLVED**

**Decision.** Bar size/construction is a trader preference. Keep it dynamic and configurable so multiple bar variations can be tested; do not hard-code one bar size as the only supported choice.

**Scope implication.** The system should allow comparison of configurations such as tick-based, time-based, and range-based bars where supported by the data pipeline. The exact supported bar types, configuration schema, and any constraints remain to be specified.

**Research implication.** Backtests must record the bar configuration used so results from different configurations can be compared without ambiguity.

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
- Q2.5 Finished-auction role: RESOLVED — confirmation only; it cannot independently trigger a trade idea. It may confirm another signal at a relevant location and is not mandatory for every move.

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
- ⚠️ Q3.1 Initial baseline is provisionally 30 contracts per individual print for Gold; threshold stays configurable. Test adaptation to recent volume later; the adaptive formula remains open.
- ⚠️ Q3.2 Exact time-window duration and progress/outcome thresholds remain open. Track both maximum favorable excursion and price at window end. Initially anchor a successful-aggression zone at the qualifying big-print price; cluster-based expansion may be considered later. Evaluate possible retest defense jointly with resting liquidity at that area.
- ⚠️ Q3.3 Resolved in part: analyze prints individually and as bursts; burst grouping uses configurable elapsed-time and price-distance criteria. Initial values and the exact combination/reset rules remain OPEN for research.
- ⚠️ Q3.4 Proximity measurement methods are configurable: compare tick distance, price-range distance, and a combined approach. Exact reference point/zone, formulas, and provisional values remain OPEN.

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

---### Q7.2 decision recorded during conversation — entry order type

**Status: RESOLVED**

**Decision.** Support both **market** and **limit** entry as configurable research modes. Limit-entry research may include entries at a defined price such as a retest of a broken level; the exact retest rule is not decided here.

**Research logging.** Where applicable, record the intended entry price, order submission time, actual fill price/quantity, slippage, and whether the entry was missed or remained unfilled. A limit order's intended price does not guarantee a fill.

**OPEN.** The specific order type to use for each entry model, exact retest definition, limit-order expiry/cancellation behavior, and any price-protection rules remain open.


### Q7.3 decision recorded during conversation — setup expiry and invalidation

**Status: RESOLVED**

**Decision.** Every trade idea must include an **invalidation condition**. When that condition is satisfied, the idea is invalidated.

**Configured expiry.** If an expiry based on time or price range is configured, it must also be followed. Idea validity is governed by its explicit invalidation condition and any configured expiry; a setup must not remain valid indefinitely after either condition has been met.

**OPEN.** The exact expiry configuration schema, time/price-range expiry calculation, and handling of already-submitted or outstanding orders when an idea is invalidated or expires remain open for later lifecycle and position-management decisions.

---

## Q7.1 decision recorded during conversation — trigger timing mode

**Status: RESOLVED**

**Decision.** Support both **closed-bar** and **intrabar** entry modes as configurable research modes. Use closed-bar entry as the initial baseline, then compare intrabar entry as a separately configured mode.

**Research and correctness constraint.** Intrabar evaluation must use only information that was actually available at the event time. Do not use the eventual bar close or other future information to simulate an earlier intrabar decision; avoid look-ahead bias.

**OPEN.** The precise intrabar trigger conditions, event timing, order submission timing, and setup expiry remain open for Q7.2 and Q7.3.


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

## Q8.3 decision recorded during conversation — continuous model updates and aggregation refresh

**Status: RESOLVED**

**Decision.** A trade idea remains under continuous execution evaluation after it enters that state. Entry models **M1–M7** keep evaluating their conditions in real time. The execution engine does **not** decide whether the idea itself is valid, expired, or invalidated; that lifecycle is owned by the underlying strategy. While the idea remains in execution evaluation, the current aggregate **Go/No-Go is a dynamic state**, not a terminal result: it may switch repeatedly as model results and market context change. Evaluation continues until the flow transitions into position management or the strategy expires/invalidates the idea (with the exact order/fill handoff semantics still to be specified).

**Update mechanism.** Use an **event-driven model-result update with maintained current state per trade idea**:
- Each model maintains its latest result and associated evidence for that idea.
- Whenever a model result changes (for example, M2 changes from `-1` to `+2`), it publishes a result-change update for that idea.
- The aggregation layer consumes the update, replaces that model's current result in the idea's maintained state, and immediately recalculates Go/No-Go using the latest available model results and configured aggregation rules.
- The event triggers recalculation; the maintained current state is the input used to calculate the decision. Do not rely on repeated polling as the primary update mechanism, and do not let individual models independently overwrite the overall Go/No-Go result.

**Evidence timing.** This is not limited to a same-bar or fixed multi-bar confirmation window. While the idea is in execution evaluation, models and aggregation continue checking their configured conditions regardless of bar boundaries. Each model's rules and the aggregation configuration determine which evidence is currently valid, when it resets, and whether any explicit time/price expiry applies. Exact per-model conditions and evidence-retention rules remain to be specified.

**Implementation safeguards.** Updates and recalculations should be associated with the correct trade idea and processed consistently so that the aggregate decision reflects the latest model state rather than a stale or out-of-order update. The current Go/No-Go state informs execution but does not itself invalidate or expire the idea. Strategy-owned expiry/invalidation and applicable order/fill/position-management states remain separate; a Go state does not guarantee an order fill.

---

## Q8.2 decision recorded during conversation — model-specific requirements and cross-model aggregation

**Status: RESOLVED**

**Decision.** Use the hybrid approach:
- Each entry model **M1–M7** enforces its own explicitly defined mandatory internal conditions and reports its model result.
- The aggregation layer combines the seven model results and may additionally evaluate cross-model agreement and conflicts.
- Do not impose an extra universal confirmation-count rule outside this model/aggregation architecture.

**Boundary.** This settles where the responsibilities belong, not the detailed logic. The exact mandatory conditions for each model and the aggregation layer's combination/conflict rules remain governed by their specific definitions and any still-open decisions.

### Reaffirmed in the latest review turn

The user again selected **C — Hybrid**: each entry model may define mandatory conditions alongside optional supporting signals; mandatory requirements are model-specific, and the aggregation layer can assess cross-model agreement/conflicts. No universal confirmation count is imposed. This reaffirms the existing resolved Q8.2 decision; it does not change its scope.


---

## Q8.1 decision recorded during conversation — model aggregation and Go/No-Go

**Status: RESOLVED**

**Decision.** The aggregation layer combines the outputs of entry models **M1–M7** and returns a final **Go/No-Go** decision. The confirmation-combination logic belongs in that aggregation layer; Section 8 must not impose an additional universal minimum number of raw confirmation reads on top of the model outputs.

**Entry behavior.** When the aggregation layer's current state is **Go**, the current candidate becomes eligible for risk management. Risk management independently determines whether the proposed trade is permitted. Go does not directly submit an order or guarantee a fill. Immediately before submission, recheck the current Go/No-Go state; if it is **No-Go**, do not submit and continue evaluating the still-active idea. Strategy-owned expiry/invalidation and applicable order/position state remain separate.

**Boundary.** This records the Go/No-Go responsibility and intended handoff to entry. It does not define the internal aggregation formula, model weights, thresholds, or conflict-resolution logic beyond previously recorded decisions; those remain governed by the existing aggregation-layer decisions and any explicitly open items.

### Q8.1-B — Go-to-No-Go after risk approval, before submission

**RESOLVED:** Recheck the current aggregate Go/No-Go immediately before submitting an entry order, even if risk management has already approved the candidate. If the latest state is **No-Go**, do not submit the order. Keep the strategy-generated idea active and continue execution evaluation; a temporary No-Go is not idea invalidation or expiry. If the state remains **Go**, submission still requires risk approval and the applicable order-entry rules. This rule addresses the pre-submission gate. After submission, Q8.1-C applies: pending-order response is configurable by entry model/order type, with cancellation on No-Go as the provisional default and asynchronous fill/cancel reconciliation required.

### Q8.1-C — Go-to-No-Go after order submission, before fill

**PARTIALLY RESOLVED:** Pending-order handling is configurable by entry model and/or order type. The provisional default is to request cancellation when the current execution-evaluation state changes from **Go** to **No-Go** while an entry order is submitted but not yet filled. This does not invalidate or expire the underlying idea; execution evaluation continues while the strategy keeps the idea active. Because cancellation is asynchronous, a cancel request is not proof that the order is cancelled. Track the order until authoritative broker/exchange events establish whether it was cancelled, filled, partially filled, rejected, or remains working. Handle fills that race with cancellation by reconciling actual exposure and then applying the appropriate position/order lifecycle rules. The policy and exceptions remain configurable and subject to testing.

### Q8.1-D — Partial fill while cancellation is pending

**RESOLVED:** Use the **separate-lifecycles** approach. As soon as a partial fill creates an open position, position management takes ownership of the filled quantity and makes the hold/close decision for that exposure. Independently, order-lifecycle handling continues to track and cancel the unfilled remainder. If cancellation succeeds, record the confirmed cancellation and retain position management over the filled quantity. If cancellation does not succeed and the remainder fills, reconcile the additional fill into the actual position/exposure and position management continues with the updated quantity. Do not wait for the remainder's final outcome before handing the already-filled quantity to position management. A cancellation request is not a confirmed cancellation; broker/exchange order and fill events remain authoritative. This cross-references the existing partial-fill and cancellation-race decisions in Q6.26/Q6.28/Q6.32/Q6.33.

### Q8.1-E — Late remainder fill and execution-quality deviation **PARTIALLY RESOLVED; AND/OR LOGIC RESOLVED**

**Decision:** Keep position management and order-lifecycle handling separate but coordinated. Define configurable time-difference and price-difference thresholds between the first execution/fill and any later execution/fill of the remaining entry quantity. If a later fill is outside the accepted threshold(s), reject that later-added exposure: if its quantity is still unfilled, cancel/reject the remaining entry quantity as permitted by the broker/order state; if it has already filled and created exposure, position management should immediately close the later-added quantity. Do not automatically close the original filled quantity solely because a later remainder fill was poor.

If the first execution was executed as intended and continues to satisfy the applicable rules, leave that original position open under normal position management. The later fill is evaluated separately against the thresholds and the trade idea's execution context. Link records using the trade idea ID and execution/order/fill IDs wherever the broker or platform makes those identifiers available; preserve linkage even if one identifier is unavailable. Reconcile actual fills and exposure from authoritative broker/exchange events.

**Purpose / rationale:** This is intended to limit slippage caused by a delayed remainder filling in a low-liquidity environment. The user expects order size to be small enough that this edge case will be uncommon, but it must be handled safely. In very high volatility, slippage may instead affect the full order; that is a separate execution-quality case and should not be misclassified as only a late-remainder-fill problem.

**Full-order slippage decision — RESOLVED:** If unusually high slippage affects the **entire entry order** (for example, during very high volatility), accept the fill and let position management manage the resulting position normally. Record the slippage for later analysis. Do not automatically close the position solely because full-order slippage was high; if this occurs frequently, investigate and fix the underlying cause rather than adding extra decision logic for the initial implementation. This is distinct from a delayed remainder fill that breaches the late-fill thresholds.

**Threshold-combination decision — RESOLVED:** Use **OR logic** as the selected rule: if either the time-difference threshold or the price-difference threshold is exceeded, treat the later remainder fill as outside the accepted execution limits. The time and price thresholds themselves remain configurable; this decision does not set numeric values.

**Price-reference decision — RESOLVED:** Compare each later remainder fill against the **price of the first execution/fill** associated with that entry, rather than the rolling or volume-weighted average price of prior fills or the intended entry price. The time-difference reference is likewise the first execution/fill. Keep the thresholds configurable and calibrate them through research/testing.

**Fragmented remainder-fill decision — RESOLVED:** Evaluate **each later fill individually**. For every later execution/fill, compare its fill price and timestamp with the first execution/fill and apply the selected OR rule: if either configurable threshold is exceeded, treat that later-added quantity as failing the execution-quality check and close that quantity if already filled, while preserving a valid original position. Continue tracking the remaining working quantity independently; each subsequent fill is evaluated on its own against the same first-fill reference, not a rolling reference. Reconcile actual order state and exposure using authoritative broker/exchange events and available trade idea/order/execution/fill IDs. All numeric values should begin as configurable provisional guesses and be calibrated with research/testing.

**Price-difference calculation — RESOLVED:** Use the **absolute price difference** between each later fill and the first fill: `abs(later_fill_price - first_fill_price)`. The threshold can therefore be breached by a price move in either direction; a favorable price difference does not automatically exempt the later fill from this execution-quality guardrail. Record whether the difference was favorable or adverse separately for analysis. The numerical threshold and its units remain open/configurable and must be calibrated.

**Price-difference units — RESOLVED:** Express the price-difference threshold in **absolute price units**, not ticks. Compare `abs(later_fill_price - first_fill_price)` directly with the configured price-unit threshold. Keep the threshold configurable and instrument/model appropriate; do not silently convert the selected rule to ticks. The numerical value remains open for research and calibration.

**Execution of the corrective exit — RESOLVED (user clarification):** When a later-added fill breaches the execution-quality threshold, flag the violation and immediately hand the affected quantity to position management. The corrective response is deliberately simple: **exit the unwanted exposure at market** and accept the resulting loss/slippage. Do not add a discretionary wait, price-repricing analysis, or extra decision layer for this exception. Preserve the valid original position unless position management independently decides otherwise. Reconcile actual fills and exposure using authoritative broker/exchange events.

### Q8.1-G — Partial fill of a corrective market exit **RESOLVED**

**Decision:** The expected order size is small, so partial corrective exits are not expected to be a frequent practical problem. If a market exit nevertheless leaves unwanted exposure open, continue the market-exit process for the residual quantity as permitted by the actual broker/order state. Track confirmed exit fills and residual exposure separately; never assume that submitting a market order means the position is already flat. Accept exit slippage rather than delaying to optimize the price. If this exception occurs frequently, investigate and fix the underlying cause later instead of adding complexity to the initial handling rule.

### Q8.1-H — Slippage logging granularity **RESOLVED; calculation formula OPEN**

**Decision:** Record execution slippage at **both the individual-fill level and the whole-order level**. Preserve each fill's execution price, quantity, timestamp, and relevant available identifiers, and calculate/store aggregate order-level slippage across the executions belonging to that order. This allows later analysis to distinguish an isolated poor fill from slippage affecting the order overall. Link records to the trade idea, order, and execution/fill IDs wherever available. Full-order slippage by itself remains an observation for analysis, not an automatic exit trigger; see Q8.1-E.

**Slippage-formula decision — OPEN (Option B):** For now, preserve the raw execution data needed for later analysis, including intended/candidate entry price when available, actual fill price, fill quantity, timestamp, order type/side, and relevant trade idea/order/execution identifiers. Do **not** finalize the exact formula or sign convention at this review stage. Define and validate the calculation during implementation and testing, with appropriate handling for instrument and order type. Do not infer a formula from this decision alone.

### Q8.6-A — Authority after a position is open

**PARTIALLY RESOLVED:** Once a position is open, execution evaluation and entry risk management relinquish authority over that position. Position management has access to the data used by the execution engine and makes the ongoing decision whether the position should stay open or be closed. If position management decides to close, the system exits the position. This is an ongoing position-management decision, not a one-time handoff result. This rule does not settle how the system handles submitted-but-unfilled orders or partially filled orders; those intermediate order states remain separate and require their own lifecycle rules.

### Q8.1-A — Go/No-Go and risk-management handoff

**RESOLVED:** A current **Go** makes the candidate eligible for risk management; it does not directly trigger order submission. Risk management decides whether the candidate is permitted to proceed. A current **No-Go** means the candidate is not currently eligible to proceed, but does not invalidate or expire the strategy-generated idea. Because Go/No-Go is dynamic, it may switch repeatedly while the idea remains in execution evaluation. The strategy alone owns idea expiry/invalidation. Immediately before order submission, recheck the current Go/No-Go state. If it is No-Go, do not submit the order and continue monitoring the still-active idea. This decision covers the pre-submission transition; behavior after an order has already been submitted remains governed by separate order-lifecycle rules.

---

## 8. How confirmations combine

**Course.** No example enters on a single read. The examples stack: location + structure + a closed trigger bar +
at least one footprint read. Example: HTF zone + VA edge + 34-lot print + recovering close [E3]. A zero print alone is
explicitly not enough [E4, E5]. A+ chain: VWAP rejection + repeated zeros + absorbed aggression + delta shift + stacked imbalance [E4].

**Open**
**Q8.1 — RESOLVED: Aggregation layer and Go/No-Go.** The aggregation layer combines the results of M1–M7 and produces the current Go/No-Go state. Do not add a separate universal count of confirmation reads at this layer. Go makes the current candidate eligible for risk management; risk management decides whether entry is permitted. No-Go does not invalidate or expire the idea, and no order is submitted merely because the state is Go.
- ⚠️ Q8.2 Are some reads mandatory per model (e.g. M1 requires the close beyond the POC; M4 requires the stacked imbalance)?
- **Q8.3 — RESOLVED:** While an idea remains in execution evaluation, M1–M7 run continuously; every model-result change triggers an immediate aggregation refresh. Evidence is evaluated according to configured model/aggregation rules, not a universal bar-window restriction.

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
| Q1.1–1.3 | Bar type, tick count, structure timeframes | RESOLVED | Configurable bar type, 500-tick baseline; test alternatives; start profile-only for locations and add objective HTF zones only if useful. |
| Q2.1–2.5 | Original-source footprint threshold, row grouping, minimum volume, finished-auction definition and role | PARTIAL | Q2.1–Q2.3 and Q2.5 decided; Q2.4 strict zero chosen, but feed-side mapping remains open for verification. See the original-source decision log appended below. |
| Q3.1–3.5 | Big-trade threshold, outcome measurement, repeats, proximity, aggression inputs | PARTIAL | Provisional thresholds and measurement approaches recorded; five separate aggression inputs, weighted/rule-based combinations, and explicit agreement/disagreement tracking are supported. Score design, conflict thresholds, calibration, and remaining implementation details remain open. |
| Q4.1–4.9 | Absorption, delta flip, and exhaustion definitions | PARTIAL | Several concepts and sequencing decisions are recorded; exact measurement definitions, context-specific choices, and calibrated thresholds remain open. |
| Q5.1–5.7 | Location sources, profile anchors, LVN qualification, shape, session | OPEN | — |
| Q6.1–6.3 | Which entry models, long/short, structure | RESOLVED | Include M1–M7 as independently testable models; implement both directions and validate separately; shared framework with configurable model modules. |
| Q7.1–7.3 | Trigger timing, order type, expiry | OPEN | — |
| Q8.1–8.3 | Aggregation, dynamic Go/No-Go, and order lifecycle | PARTIAL | Q8.1–Q8.3 resolved; pending-order response to No-Go is configurable by model/order type, with cancellation as the provisional default. Exact model conditions and aggregation rules remain open. |
| Q9.1–9.4 | Stop | RESOLVED AT POLICY LEVEL | Model-specific structural invalidation; configurable buffer method; skip if no valid stop fits maximum distance; no time-based stop in v1. Numerical settings and model mappings remain open. |
| Q10.1–10.4 | Target, partials, re-entry | RESOLVED AT POLICY LEVEL | Structural targets subject to configurable minimum R:R and intervening POC/HVN checks; defer partials/breakeven; configurable re-entry limits starting at one attempt. Parameters/model mappings remain open. |
| Q11.1–11.4 | No-trade filters | PARTIAL | Q11.1 and Q11.2–Q11.4 policy decisions recorded; momentum thresholds, blackout schedules, trade limits and cooldown values remain open. See original-source decision log. |
| Q12.1–12.2 | Heatmap and gamma scope | PARTIAL | Heatmap is included for v1; gamma is deferred. Define heatmap walls using configurable size and persistence thresholds; calibration remains open. |
| Q13.1–13.3 | Validation | PARTIAL | Q13.1 requires a forward-test period and minimum signal count plus pre-defined metrics; numerical acceptance thresholds remain open. Q13.2 is resolved: log raw signals before order wiring. Q13.3 is resolved as a data-storage decision: retain 1-second snapshots of each order-flow construct and entry-model logs for more than 7 days; raw tick and DOM logs are not currently stored. This supports replay/research on recorded constructs but does not provide raw tick-by-tick reconstruction or historical DOM/heatmap replay. |

## Suggested order for review

Questions answered earlier unlock the later ones:
1. §1 (bar) and §6 (which models) first, because everything else is defined relative to them.
2. §4 (definitions of the reads) and §2/§3 (thresholds) next.
3. §5 (location), then §7–§8 (timing and combination).
4. §9–§11 (stop, target, filters).
5. §12–§13 last.

## Review notes

Once a question is decided, update its section and the decision table, keeping the original question visible for audit.


---

# Additional questions to incorporate (preserved)

# Additions for Master Order-Flow Rules Review

**Purpose:** Add only the missing questions/sections identified during review of `orderFlowRulesRedefinition.md`.  
**Status:** All additions remain OPEN until explicitly decided.

---

## A. Aggression — add to §3

### Q3.5 — What constitutes meaningful aggression?

The course uses several forms of aggressive activity:
- large aggressive buy/sell prints (big-trade bubbles);
- strong positive/negative delta;
- buy/sell diagonal imbalances;
- stacked imbalances;
- repeated aggressive prints.

**Open:** Which of these are valid inputs to the generic concept of **aggression**, and how should they be measured/combined?

### Q3.6 — How do we determine whether aggression succeeded or failed?

**Status: PARTIALLY RESOLVED**

The same aggressive activity can have different meanings. Track price response using the Q3.2 framework (both maximum favorable excursion and price at the end of a configurable time window, with continuous intrabar evaluation), but distinguish two questions:

1. **Did price move in the direction of the aggression?** This is directional price progress. If there was no meaningful resting liquidity challenging that aggression, the move may be the obvious response to an unopposed market and does not, by itself, show that aggression was stronger than liquidity.
2. **Did aggression overcome opposing resting liquidity?** This is the stronger signal of interest. When a relevant resting-liquidity wall/zone is present, evaluate whether aggressive execution actually breaks through it and whether the resulting price response supports that interpretation. This should be considered alongside the liquidity evidence, not inferred from price progress alone.

A liquidity wall being broken can count as successful aggression for this stronger classification. A move in the aggressive direction without a meaningful liquidity challenge may still be directionally successful, but should not automatically receive the stronger “aggression overcame liquidity” interpretation.

**Wall-break evidence (partially resolved):** Track these separately: (a) resting liquidity consumed by executions, (b) liquidity canceled/pulled, (c) mixed consumption and pulling, and (d) price trading through the wall/zone. Assign these different evidential meanings rather than collapsing them into one “wall broken” flag. In particular, cancellation-only disappearance is not equivalent to aggressive executions consuming the resting liquidity. Preserve uncertainty when the feed cannot reliably distinguish these cases.

**Penetration versus confirmed break (partially resolved):** Record the first trade beyond the wall/zone as **penetration**. Do not automatically treat that first trade as proof that aggression overcame the liquidity. Classify a **confirmed break** only after configurable follow-through criteria are satisfied. The follow-through definition, such as time or distance beyond the wall, starts with provisional configurable values and must be calibrated through research. Keep penetration and confirmed-break events separately observable and logged.

Preserve at least the conceptual distinction between **successful**, **failed**, and **mixed/inconclusive** outcomes. Do not force a fixed count of failed events. Keep wall/zone qualification, the definition of a meaningful liquidity challenge, what constitutes a break, measurement window, price-progress criteria, and all numerical thresholds configurable. Begin with provisional guesses and calibrate them through research; do not treat the guesses as validated constants.

**Still open:** The exact wall/zone qualification criteria, thresholds for classifying a wall as consumed/pulled/mixed, how much price must trade through the wall/zone to count as a break, and the numerical thresholds/time window. These need separate research decisions.

---

## B. Relationship between failed aggression and absorption — add to §4

### Q4.6 — Is failed aggression the broader concept, with absorption as one manifestation? **RESOLVED**

**Decision:** Failed aggression is the broader, independent concept. Supporting resting-liquidity evidence is required to classify a failed-aggression event as an absorption attempt. A delta flip is a separate confirmation of absorption completion; it is not part of the definition of failed aggression itself.

Keep the concepts and their event outputs distinct so the system does not encode the same phenomenon under multiple names. Exact thresholds and numerical parameters remain configurable and subject to research calibration.

---

## C. LVN strength/ranking — add to §5

### Q5.8 — How should LVN strength be ranked? **PARTIALLY RESOLVED**

**Decision:** Preserve these as separate features rather than collapsing them prematurely into a single hard-coded definition of LVN strength:
- LVN width;
- depth of the volume depression;
- relative volume versus the profile baseline;
- surrounding HVN volume;
- separation between surrounding volume nodes.

Support both configurable scoring and rule-based ranking. Keep feature definitions, normalization, weights, thresholds, and ranking logic configurable. Start with provisional research guesses and calibrate them through analysis; no feature or weighting is assumed to be universally predictive.

**Numbering note:** The existing review log already uses Q5.8 for session-extremes role. Keep that earlier decision intact; this newly added LVN-ranking item also appears as Q5.8 in the source material. Resolve the duplicate question identifier during document cleanup without conflating the two decisions.

---

## D. LVN touch and proximity — add to §5

### Q5.9 — What exactly constitutes an LVN touch? **PARTIALLY RESOLVED**

**Decision:** Track these as separate events/states:
- **Approaching:** price is within a configurable proximity tolerance of the LVN but has not entered it;
- **Touching/entering:** price reaches/enters the LVN range;
- **Moving through:** price progresses through the LVN range, distinct from merely entering it.

Keep the proximity tolerance configurable. Exact transition criteria, how price is sampled/qualified for each state, and provisional tolerance values remain OPEN; begin with provisional guesses and calibrate them through research.

### Q5.10 — Should touch, absorption proximity, and aggression proximity use the same tolerance? **RESOLVED**

**Decision:** Use separate configurable tolerances for LVN approach, absorption proximity, and aggression proximity. Each construct measures a different phenomenon, so do not force them to share one distance threshold.

**Calibration:** Start each tolerance with a provisional guess, keep it configurable, and calibrate it independently through research. No specific numerical value is approved yet.

---

## E. Trade-idea lifecycle and execution ownership — revised during review

### Approved lifecycle direction

A strategy-generated trade idea becomes **active immediately when generated** and remains active until it expires or is invalidated under the applicable strategy/model rules. An LVN touch is **not** a universal activation gate; that earlier design came from a time when the system did not yet have distinct entry models M1–M7.

The execution engine begins monitoring the idea immediately. It evaluates the available entry models and market context to determine whether/where an entry is justified. The intended high-level sequence is:

1. **Strategy generates a trade idea.** The idea becomes active immediately.
2. **Execution engine starts monitoring immediately.** It evaluates the idea continuously while it remains valid; LVN approach/touch/movement are inputs or events for relevant models, not universal lifecycle gates.
3. **Execution evaluation determines a candidate entry and associated stop-loss/target.** Model-specific rules and evidence determine the entry point; the exact evaluation/aggregation criteria remain governed by the entry-model decisions.
4. **Risk management evaluates the proposed trade only while the current execution state is Go.** It decides whether the proposed entry, stop, target, and risk are acceptable under the risk rules. Go is eligibility for risk review, not automatic approval.
5. **Immediately before order submission, recheck the current Go/No-Go state.** Submit only if it is still Go, risk management has approved the candidate, and the applicable execution/order-entry rules permit submission. If the state is No-Go, do not submit; continue monitoring the still-active idea. This does not invalidate or expire the idea. Behavior after an order has already been submitted remains a separate order-lifecycle question.
6. **Position management takes over once the position is open.** Execution evaluation and entry risk management no longer have authority over the open position. Position management has access to the data used by execution evaluation and independently decides whether the position should remain open or be closed. If position management decides to close, the system exits the position.

The trade idea remains active until it expires or is invalidated while it is in the idea/execution-evaluation lifecycle. Once the position is open, ongoing hold/close decisions belong to position management, not the execution engine or entry risk management. Submitted-but-unfilled orders and partial fills remain distinct from open-position management. Q8.1-C sets a configurable pending-order policy, with cancellation on No-Go as the provisional default. Under Q8.1-D, position management takes over any filled quantity immediately while order-lifecycle handling independently tracks/cancels the remainder and reconciles any later fills. Under Q8.1-E, later remainder fills are also checked against configurable time/price deviation thresholds; if the later-added exposure exceeds the accepted limits, cancel it if still unfilled or immediately hand the filled quantity to position management for a market exit, accepting the loss/slippage and leaving a valid original execution open unless position management independently decides otherwise. Link events by trade idea and execution/order/fill IDs where available. If the corrective market exit leaves residual exposure, continue exiting the residual quantity according to the actual order state; do not add extra optimization logic. Full-order slippage in very high volatility is accepted and recorded for analysis at both individual-fill and whole-order levels (Q8.1-H); position management handles the position normally, with no automatic exit solely for slippage. If it occurs frequently, investigate the root cause.

### Q-L1 — What activates a trade idea? **RESOLVED**

**Decision:** A strategy-generated idea is active immediately when the strategy generates it and remains active until expiry or invalidation. Execution monitoring starts immediately. LVN touch does not activate the trade idea; it is a location event that may matter to specific entry models.

### Q-L2 — What is the execution-evaluation state?

**PARTIALLY RESOLVED:** Execution evaluation is continuous and dynamic, not a one-time result. M1–M7 and the aggregation layer update the current Go/No-Go state in real time; it may switch between Go and No-Go repeatedly while the idea remains active. Evaluation informs whether and where an entry currently makes sense and determines the proposed entry, stop-loss, and target. **Go makes the current candidate eligible for risk management; risk management decides whether entry is permitted. No-Go means the candidate is not currently eligible to proceed, but it does not invalidate or expire the idea.** Exact model criteria and aggregation remain governed by their own decisions.

### Q-L3 — Who owns expiry and invalidation?

**PARTIALLY RESOLVED:** The underlying strategy alone determines whether its idea expires or is invalidated. The execution engine does not judge the idea's validity; it reports the current dynamic execution Go/No-Go while evaluation is active. Evaluation continues until transition into position management or strategy-owned expiry/invalidation. Exact expiry/invalidation conditions and effects on pending orders, partial fills, and open positions remain OPEN.

---

## F. Trade idea, execution evaluation, risk, order entry, and position management — revised for §8

Keep these as distinct stages:

**Idea active / monitoring**  
→ The strategy has generated an idea; the execution engine starts evaluating it immediately. Only the underlying strategy owns idea expiry/invalidation. The execution engine does not decide whether the idea remains valid.

**Execution evaluation (dynamic state)**  
→ Applicable entry models are evaluated continuously; the current aggregate Go/No-Go may switch repeatedly as evidence changes. This is not a terminal result and does not determine idea validity. The engine evaluates whether/where entry currently makes sense and determines a candidate entry point and proposed stop-loss/target. LVN states and other order-flow constructs are model inputs, not a universal activation gate. Evaluation continues until transition into position management or strategy-owned expiry/invalidation.

**Risk management**  
→ A current Go makes the candidate eligible for risk review. Risk management independently decides whether entry is permitted; Go is not risk approval. A No-Go candidate is not currently eligible to proceed, but the idea remains active.

**Order entry**  
→ An order is submitted only after risk management approves the candidate and applicable execution/order-entry rules permit it. Go alone does not submit an order. Order submission, partial fills, full fills, and position-active state must remain distinguishable; their exact state-transition contract remains OPEN where not otherwise decided.

**Position management (authority after position opens)**  
→ Once the position is open, execution evaluation and entry risk management lose authority over the position. Position management receives/accesses the data used by execution evaluation and decides dynamically whether the position should remain open or close. If it decides to close, the system exits the position. Keep this distinct from the unresolved handling of submitted-but-unfilled orders and partial fills. Do not conflate an active idea with an active position.

### Q8.4 — What event starts monitoring?

**RESOLVED:** Generation of a trade idea by the underlying strategy. Execution monitoring starts immediately; LVN touch is not the universal activation event. The strategy alone owns idea expiry/invalidation; the execution engine does not decide idea validity.

### Q8.5 — What determines the candidate entry, stop, and target?

**PARTIALLY RESOLVED:** Execution evaluation applies the relevant entry models and determines the candidate entry point and proposed stop-loss/target before risk management. Exact model criteria and aggregation remain governed by their respective open/resolved questions.

### Q8.6 — What exact event constitutes execution and position-management handoff?

**PARTIALLY RESOLVED:** Once a position is open, position management owns the hold/close decision. Execution evaluation and entry risk management no longer have authority over the open position. Position management has access to the data used by execution evaluation and decides whether the position remains open or exits; if it decides to close, the system exits. Keep order submission, partial fill, full fill, and position-active states distinct. The exact handoff/authority behavior for submitted-but-unfilled orders and partial fills remains OPEN.

This separation prevents idea generation, strategy-owned idea validity, dynamic execution Go/No-Go, risk approval, order submission, fills, and position management from being treated as the same event. Go/No-Go can change repeatedly during execution evaluation; it is not itself an expiry, invalidation, or final result.

---

## G. Sweep — add to §12

### Q12.3 — Is liquidity sweep part of the first version?

**Open:** Include in V1, defer, or drop because it requires L2/depth history?

If included:

### Q12.4 — What objectively constitutes a sweep?

Define:
- required wall/liquidity size;
- required persistence;
- amount of liquidity consumed/removed;
- whether price must trade through the wall;
- whether a subsequent reversal is required;
- whether sweep is location, confirmation, or both.

---

## H. Review-order adjustment

Replace the suggested review order with:

1. **§1 — Bar/data unit**
2. **§2–§3 — Footprint primitives, aggression and big-trade definitions**
3. **§4 — Absorption, exhaustion and delta-flip definitions**
4. **§5 — Location and LVN qualification**
5. **§6 — Entry models**
6. **§7–§8 — Trigger timing and confirmation/lifecycle**
7. **§9–§11 — Stop, target and no-trade filters**
8. **§12 — Optional heatmap/gamma layers**
9. **§13 — Validation**

Reason: entry models depend on the definitions of the underlying reads and locations, so the models should not be finalized before those primitives are settled.

---

## Latest review decisions — Q9 Stop-loss / take-profit model

### Q9.1 — How is the final stop-loss reference determined? **RESOLVED — Model-specific hybrid**

**Decision:** The SL/TP model considers all applicable entry models and available order-flow constructs, then determines the final stop-loss level. Different entry models may produce different stop-loss candidates or require different structural references; there is no universal stop reference forced across every model.

- The SL/TP model combines and evaluates relevant candidates/evidence from the entry models and order-flow constructs to select the final SL.
- The rules used to rank, combine, or select candidates are initially provisional, based on educated guesses, and must be tested and refined through research/backtesting.
- Do not force an arbitrary closer stop simply to satisfy a preferred distance if it would no longer make structural sense.
- The SL/TP module outputs the proposed SL level; risk management separately decides whether the trade is acceptable under constraints such as maximum single-trade loss and required/fixed risk-reward ratio. An unacceptable proposed stop can result in rejecting the trade rather than silently moving the stop to an illogical level.
- Keep model-specific rules and numerical parameters configurable where appropriate; validate them rather than treating initial guesses as established facts.

This resolves the stop-reference choice as a model-specific hybrid, while leaving the exact candidate-ranking and final-selection algorithm open for research and testing.

### Q9.2 — How should the SL/TP model resolve conflicting stop-loss candidates? **RESOLVED — Option C: Hybrid**

**Decision:** Use a hybrid, model-aware process to resolve competing stop-loss candidates:

1. Each entry model defines its mandatory stop requirements and may provide optional supporting evidence or candidate references.
2. The SL/TP model filters out candidates that fail applicable mandatory requirements or are otherwise invalid for that model/context.
3. The remaining valid candidates are ranked/selected using configurable rules and/or scoring. The exact ranking, weighting, and tie-breaking logic remains OPEN for research and testing.
4. Initial rules and parameter values are provisional educated guesses, not established truths; test and refine them through replay/backtesting and validation.
5. The SL/TP model produces the final proposed stop-loss. Risk management separately determines whether the trade is acceptable under risk constraints; do not silently move the stop to a structurally illogical level merely to make the trade pass.

This is model-aware rather than a single universal stop-selection rule. It allows each entry model's essential structural requirements to be preserved while still producing one final stop for the candidate trade.


### Q9.3 — How should the SL/TP model determine the target? **RESOLVED — Option C: Model-specific hybrid**

**Decision:** The SL/TP model evaluates target candidates from the applicable entry models and relevant order-flow constructs. Each entry model may propose structural targets, while the SL/TP model evaluates those candidates and may apply configurable risk-to-reward constraints. The target-selection rules are provisional and must be tested/refined through research and backtesting.

- Do not force a universal target reference or fixed R:R rule across all entry models.
- Preserve model-specific target requirements and supporting evidence.
- The SL/TP model selects the final proposed target; risk management separately determines whether the trade is acceptable under its constraints.
- Exact candidate filtering, ranking, R:R handling, and tie-breaking remain OPEN for research and testing. Initial rules and values are educated guesses, not established truths.

**Numbering note:** The source review notes already use “Q9.3” for maximum stop distance. Keep that existing question open and resolve the numbering collision when consolidating the review document; this target-selection decision refers to the sequential question answered in the current review thread.

### Q9.4 — What if a structurally valid stop is too wide for the accepted risk limit? **RESOLVED — Option C, with risk management as the decision owner**

**Decision:** Stop/target selection and risk acceptance are separate responsibilities.

- The SL/TP model evaluates the entry models and relevant order-flow constructs and outputs the final structurally valid proposed stop-loss (and target) using provisional, testable rules.
- Risk management owns the decision about whether the otherwise-valid trade fits the system's accepted risk limits.
- A valid entry is not sufficient for approval: if the trade does not fit the accepted risk limit, **do not trade**.
- Risk management must not force an illogical stop or silently move the SL just to make the trade fit a risk limit.
- Candidate-selection rules and thresholds remain provisional and will be tested. The exact internal workflow for considering alternative valid candidates is not fixed beyond preserving the separation between SL/TP selection and risk acceptance.

### Q9.5 — What happens after risk management rejects an otherwise-valid trade? **RESOLVED — Option A: Reject current attempt, keep monitoring the idea**

**Decision:** A risk rejection does not automatically invalidate or permanently discard the underlying trade idea.

- If an entry idea is valid but risk management rejects the current trade attempt because it falls outside accepted risk limits, do not submit that trade.
- Keep monitoring the idea while it remains active under the strategy's own expiry/invalidation rules.
- If conditions change, the entry models and SL/TP model may reevaluate the opportunity and produce updated entry/stop/target candidates.
- Any later attempt must pass risk management again under the then-current conditions. A prior rejection is not approval for a later attempt.
- Only the underlying strategy owns idea expiry/invalidation; risk rejection by itself does not permanently invalidate the idea.

This preserves the separation between idea validity and current trade acceptability. The idea may remain active, but no order is submitted unless the current candidate passes the required evaluation and risk checks.

### Q9.6 — Should repeated risk rejections eventually stop an idea from being considered? **RESOLVED — Option A: Keep monitoring until strategy expiry/invalidation**

**Decision:** Risk rejection alone does not create a separate expiry rule or rejection counter.

- Keep monitoring the idea while it remains active under the underlying strategy's own expiry/invalidation rules.
- Repeatedly failing the risk check does not by itself permanently discard the idea.
- If conditions change, the candidate can be reevaluated; every new trade attempt must independently pass the current risk checks.
- Do not add an arbitrary rejection limit unless future research demonstrates a need and a separately reviewed rule is approved.

This preserves the authority boundary: the underlying strategy owns idea expiry/invalidation, while risk management decides whether each current trade attempt is acceptable.

### Q9.7 — What if the SL/TP proposal changes while the idea is still active? **RESOLVED — Option C: Hybrid by lifecycle stage**

**Decision:** SL/TP proposals may update dynamically before entry submission. Once a position is open, changes to its stop or target are governed by position-management rules rather than silently changing the open position from the entry-evaluation path.

- **Before order submission:** Keep the SL/TP proposal dynamic as relevant entry models and order-flow constructs change. Before submitting an entry, use the current valid proposal and rerun risk management; if the updated candidate fails accepted risk limits, do not submit.
- **After position opening:** Position management owns decisions about the open position. Changes to an open position's SL/TP must go through its rules and authority, not be applied silently by the entry evaluation or risk-approval path.
- **Responsibility boundary:** The strategy owns idea expiry/invalidation; the SL/TP model proposes levels; risk management approves/rejects the trade attempt; position management governs an open position.
- Exact update cadence and detailed position-management rules remain open for separate review and testing.

### Q9.8 — What if the SL/TP proposal changes after entry submission but before the order fills? **RESOLVED — Option C: Configurable, with risk-invalidating changes triggering cancellation**

**Decision:** The response is configurable by entry model and/or order type. Do not cancel a working entry order merely because the SL/TP proposal changed; cancel it when the change makes the existing candidate unacceptable under current risk rules.

- If the updated SL/TP proposal means the pending entry candidate no longer satisfies accepted risk limits, request cancellation of the working entry order.
- Cancellation is asynchronous: a cancel request is not confirmation that the order is cancelled. Continue tracking authoritative broker/exchange order and fill events, and reconcile any fills that race with cancellation.
- If the existing pending candidate remains valid and risk-approved despite the proposal changing, do not cancel solely because a newer proposal exists.
- Any new/replacement entry attempt must use the current valid candidate and independently pass the required execution evaluation and risk checks before submission.
- The precise response policy can be configured by entry model and/or order type and should be validated in testing. Preserve the established lifecycle boundaries: the strategy owns idea expiry/invalidation, risk management approves the current trade candidate, and position management owns any open position.


### Q9.9 — What if the pending entry fills while cancellation is being processed? **RESOLVED — Option C: Reconcile the fill and hand the position to position management**

**Decision:** If an entry order fills before a requested cancellation is confirmed, treat the actual fill as real exposure immediately. Reconcile authoritative broker/exchange events and hand the resulting position to position management, which decides what to do under its current rules.

- A cancellation request is not proof that the order is cancelled.
- If a fill occurs during the cancellation race, record and reconcile it immediately; do not assume the order remained unfilled.
- Position management evaluates the resulting open position using its normal rules. The system must not automatically force a market exit solely because cancellation raced with a fill; the response is governed by position management.
- Link the trade idea, order, execution/fill, cancellation request, and resulting position using available IDs, and log the race for later analysis.
- Any remaining working quantity continues to be tracked and cancelled/reconciled as appropriate.

This preserves the lifecycle boundary: order handling reports actual order/fill state, while position management owns decisions about any resulting open position.

### Q9.10 — What context should position management receive for a fill during cancellation? **RESOLVED — Option B: Position data plus an exception flag**

**Decision:** When an entry fills during a cancellation race, position management receives the actual reconciled position plus an exception flag describing the exceptional entry context. This provides relevant context without tightly coupling position management to the full order-event stream.

- Pass the actual position data and an exception flag indicating that the fill occurred while cancellation was pending.
- Where applicable, include whether the entry had already failed a current risk check or execution-quality check.
- Keep the full order/cancellation/fill event history in the order lifecycle and logs; provide position management with the relevant exception metadata rather than requiring it to consume the entire raw event stream.
- Position management still owns the action for the open position under the previously agreed Q9.9 rule; the flag supplies context and does not, by itself, prescribe an automatic exit.
- Link the exception, trade idea, order, execution/fill, and position by their IDs wherever available.

The exact exception schema and any action policy associated with particular flags remain open for implementation/research. Do not infer an automatic exit from the flag alone; that would require a separate explicit decision.

### Q9.11 — What should position management do when an exception flag indicates a risk-rejected entry? **RESOLVED — Option B: Position management applies its normal rules**

**Decision:** Even if an entry filled during a cancellation race after risk management rejected the pending candidate, position management assesses the resulting actual position under its normal rules. The exception flag provides context; it does not itself mandate an immediate exit.

- Reconcile the actual fill and exposure immediately and pass the position plus relevant exception metadata to position management.
- Position management determines the appropriate action under its normal position-management rules; do not add a separate automatic market-exit rule solely because the entry had been risk-rejected.
- Keep tracking any remaining working entry quantity and reconcile subsequent fill/cancel events so additional exposure cannot go unnoticed.
- Log the risk rejection, cancellation request, fill/cancellation race, exception flag, and resulting position, linking the trade idea, order, execution/fill, and position IDs wherever available.
- This decision does not change the rule that risk management must reject unacceptable candidates before submission. It defines recovery behavior for the exceptional case where an order fills before cancellation takes effect.

This resolves the exception response as **normal position-management handling with risk-rejection context**, not a forced exit. The precise exception schema and any later evidence-based refinements remain open for implementation and testing.

### Q9.12 — Should position management know the reason for every entry exception? **RESOLVED — Option A: Pass the specific reason and relevant context**

**Decision:** When an entry fills during a cancellation race or another entry exception, pass position management the specific exception reason and relevant context, rather than only a generic exception flag.

- Include the specific reason where known, such as risk rejection, execution-quality failure, a Go-to-No-Go change, or another cancellation reason.
- Include the relevant context needed to interpret the exception, alongside the actual reconciled position data.
- Preserve traceability by linking the trade idea, order, cancellation request, execution/fill, exception, and resulting position using available IDs.
- Keep the full order/cancellation/fill event history in order-lifecycle handling and logs; passing the reason and relevant context does not require position management to consume the entire raw event stream.
- **The exception reason provides context, not an automatic action.** Position management applies its normal rules unless a separate explicit decision defines a different response for an exception type. In particular, the existing Q9.11 decision remains: a risk-rejected entry that fills during cancellation is handled under normal position-management rules, not automatically market-exited solely because of the risk rejection.

The exact exception schema and context fields remain open for implementation and testing. Do not infer different position-management behavior for each exception type without a separate reviewed decision.

### Q9.13 — Should position management use exception reasons to change its behavior? **RESOLVED — Option A: No special behavior initially**

**Decision:** For the initial implementation, exception reasons and context are informational. Position management applies its normal rules; do not add exception-specific actions unless later research demonstrates a need and a separate decision approves them.

- Pass the specific exception reason and relevant context to position management, as agreed in Q9.12.
- Do not define special position-management behavior for each exception type in the initial implementation.
- Keep logging the exception details so the system can investigate patterns and evaluate whether specialized handling is warranted.
- Any future exception-specific response must be proposed as a separate, explicit rule and tested before adoption; it must not be inferred merely from the presence of an exception reason.

This keeps initial behavior simple while preserving the information needed for debugging, analysis, and later improvement.

### Q9.14 — Should exception events be included in the trade audit trail? **RESOLVED — Option C: Dedicated exception log plus linked trade audit trail**

**Decision:** Maintain both a dedicated exception log and the relevant events in the trade's audit trail. The dedicated log supports system-wide exception analysis; the trade audit trail preserves the complete chronology for an individual trade.

- Record exception events in a dedicated exception log so recurring failures and patterns can be queried across trades.
- Also link each relevant exception event into the corresponding trade audit trail so the full sequence of decisions, orders, cancellations, fills, and resulting position changes can be reconstructed.
- Where available, correlate records using trade idea, order, cancellation request, execution/fill, exception, and position IDs, along with event timestamps.
- Keep the two views consistent through shared identifiers and event references; avoid treating the separate log as a replacement for the trade's audit trail.
- Exact schemas, storage layout, retention, and consistency mechanisms remain open for implementation design.

### Q9.15 — Should the trade audit trail be append-only? **RESOLVED — Option C: Append-only event history plus mutable current-state records**

**Decision:** Keep the authoritative event history append-only, while maintaining separate mutable current-state records for efficient live reads.

- Do not overwrite or delete historical audit events to represent a correction or later reconciliation. Append a new event linked to the original event or record, preserving the sequence of what the system observed and when.
- Maintain separate current-state projections/records for orders, executions, and positions where useful for fast access by the live trading system.
- Update current-state records as new authoritative events arrive, while retaining the append-only event history as the audit source.
- Use stable identifiers and event references to connect current state to its underlying event history, including trade idea, order, cancellation, execution/fill, exception, and position IDs wherever available.
- Define event schemas, ordering/reconciliation semantics, idempotency, projection rebuild/recovery, and storage details during implementation design. Do not assume the current-state projection is more authoritative than the broker/exchange events for actual order and fill outcomes.

This combines auditability with efficient live state access without conflating the immutable event record and the current operational view.

### Q9.16 — What should happen if the same broker/exchange event is received more than once? **RESOLVED — Option C: Idempotent processing with reconciliation**

**Decision:** Make event processing idempotent and reconcile authoritative order/fill state after reconnects or inconsistencies. Repeated delivery of the same event must not apply its effects more than once.

- Deduplicate events using stable broker/exchange event identifiers wherever available.
- Ensure repeated processing cannot apply the same fill twice or incorrectly increase recorded position quantity.
- When stable identifiers are missing, event history is incomplete, or inconsistencies are detected, use the available authoritative broker/exchange order and fill state to reconcile the system's records rather than relying only on a local duplicate check.
- Preserve the append-only event history: record relevant reconciliation/correction events instead of overwriting historical events. Update the mutable current-state projection only in an idempotent, reconciled manner.
- Link events and reconciliation records to trade idea, order, execution/fill, exception, and position identifiers wherever available.
- Exact deduplication keys, handling for missing identifiers, reconnect recovery, reconciliation procedures, and storage schemas remain open for implementation design and testing.

This protects position accounting from duplicate event delivery while retaining a recovery path for incomplete or inconsistent event streams.

### Q9.17 — What if broker/exchange events arrive out of order? **RESOLVED — Option C: Reconcile using event identity, timestamps, and authoritative state**

**Decision:** Tolerate out-of-order event delivery. Do not assume arrival order is the same as the actual broker/exchange event order. Process events idempotently and reconcile order/fill state using event identity, available timestamps/sequence information, and authoritative broker/exchange state.

- A fill event may arrive before the corresponding order-update event, and a cancellation acknowledgement may arrive after a fill that occurred earlier. Handle these cases without assuming that the last-arriving event necessarily describes the latest real-world state.
- Use stable event identifiers and available broker/exchange timestamps or sequence information to correlate and interpret events. Preserve both event occurrence time, when supplied, and local receipt/processing time where available.
- Keep processing idempotent so duplicate or replayed events do not apply their effects more than once.
- When event ordering is ambiguous, events are missing, or local state conflicts with the event stream, reconcile against authoritative broker/exchange order and fill state rather than relying solely on arrival order.
- Preserve the append-only event history and record reconciliation/correction outcomes as new events; update mutable current-state projections only after safe, idempotent processing.
- Do not block indefinitely waiting for an expected earlier event that may never arrive. Exact buffering, ordering, timeout, and recovery policies remain implementation details to define and test.
- Link events to trade idea, order, cancellation request, execution/fill, exception, and position identifiers wherever available.

This decision complements Q9.15's append-only event history/current-state projection and Q9.16's idempotent duplicate handling. It defines the required behavior without prematurely fixing the exact event-ordering or recovery algorithm.

### Q9.18 — What if reconciliation shows local position state differs from the broker's actual position? **RESOLVED — Option C: Reconcile and enter a safety state**

**Decision:** Treat the broker's confirmed position as the source of actual exposure, record and investigate the discrepancy, prevent new entries that could worsen it, and reconcile orders/fills before resuming normal operation. Position management continues to handle the actual exposure.

- When local position state disagrees with the broker's confirmed position, do not continue trading as if local state were correct.
- Use the broker's confirmed position to represent actual exposure, while preserving the previously recorded local state and the discrepancy in the append-only audit history rather than silently overwriting history.
- Enter a safety state that prevents new entries which could worsen the discrepancy until the relevant orders, fills, and position records have been reconciled.
- Continue tracking and reconciling outstanding orders and executions. Update mutable current-state projections only through safe reconciliation, with correction/reconciliation events appended to the audit history.
- Position management remains responsible for managing the actual exposure during the discrepancy; the safety state is not permission to ignore or abandon an open position.
- Resume normal entry operation only after the discrepancy has been resolved sufficiently to establish consistent position/order state.
- Record the discrepancy and reconciliation outcome in both the dedicated exception log and the linked trade audit trail, using trade idea, order, execution/fill, exception, and position IDs wherever available.
- Exact safety-state transitions, reconciliation procedures, and criteria for resuming normal operation remain implementation details to define and test.

This decision complements Q9.15–Q9.17: the event history remains append-only, duplicate and out-of-order events are handled idempotently, and confirmed actual exposure is reconciled before new entries resume.

### Q9.19 — What if the broker's position cannot be confirmed? **RESOLVED — Option C: Configurable safety state, with a severe alert**

**Decision:** If broker position state cannot be confirmed—for example, because the broker API is unavailable, reconnect is in progress, or a position query returns incomplete data—enter a safety state. Block new entries, continue tracking existing orders and known exposure as safely as possible, retry reconciliation, and resume normal entries only when broker state is sufficiently confirmed. Mark this condition **SEVERE** and send a Telegram alert.

- Do not continue opening new positions based only on local state while actual broker exposure is unconfirmed.
- Continue tracking known positions and outstanding orders, and attempt to restore broker connectivity/reconciliation according to the recovery process.
- Resume normal entry operation only after the broker's position/order state is sufficiently confirmed and reconciled.
- Log the condition and its resolution in the dedicated exception log and linked trade audit trail, with relevant IDs and timestamps wherever available.
- This condition is explicitly classified as **SEVERE**.
- **Telegram alerting rule:** Every condition classified/marked as SEVERE must trigger a Telegram alert. This is a system-wide severity-to-alert rule, not a special rule only for this condition.
- Exact alert message format, retry/deduplication behavior, Telegram configuration, and the full severity taxonomy remain open for implementation design and testing.

This extends Q9.18's safety-state behavior to cases where broker position truth cannot currently be confirmed, and establishes Telegram notifications for all SEVERE conditions.

### Q9.20 — Should Telegram alerts repeat while a SEVERE condition remains unresolved? **RESOLVED — Option B: Alert on detection and recovery**

**Decision:** For each SEVERE incident, send a Telegram alert when the condition is first detected and a recovery alert when it is resolved. Do not repeatedly alert while the same incident remains active.

- Send an initial Telegram alert when a condition transitions into the active SEVERE state.
- Do not send repeated notifications for the same continuously active incident.
- Send a recovery Telegram alert when the condition is confirmed resolved.
- If the condition resolves and later recurs, treat the recurrence as a new incident and send a new initial alert.
- Keep the system-wide rule from Q9.19: every condition marked SEVERE triggers Telegram alerting, with detection and recovery notifications following this policy.
- Record detection, notification, and recovery events in the exception log and relevant audit trail. Use stable incident/event identifiers where available so retries do not create duplicate notifications for the same transition.
- Exact message format, delivery retry policy, notification-failure handling, and Telegram configuration remain open for implementation design and testing.

This policy aims to provide prompt awareness and confirmation of recovery without flooding Telegram during a prolonged incident.

### Q9.21 — What if Telegram delivery fails? **RESOLVED — Option C: Retry with bounded backoff and preserve the alert**

**Decision:** When a SEVERE-condition notification cannot be delivered, retry using configurable backoff delays, preserve the alert and its delivery status for later retry/recovery, and ensure notification-delivery failure never disables or weakens the trading safety state.

- Keep the SEVERE condition's safety response active regardless of Telegram availability or delivery outcome. A notification failure must never allow new entries that the safety state blocks.
- Retry failed Telegram deliveries using configurable, bounded-backoff timing rather than retrying continuously without limits.
- Preserve failed/pending alerts durably with incident identifiers, alert type (detection or recovery), creation time, attempt count, last delivery result, and next retry eligibility where supported by the implementation.
- Record delivery attempts and outcomes for diagnosis. Make retries idempotent where possible so repeated attempts do not create duplicate logical alerts for the same incident transition.
- Keep the Q9.20 behavior: one initial alert on detection and one recovery alert when resolved; do not repeatedly notify for the same continuously active incident merely because it remains unresolved. Delivery retries are retries of the same alert, not new incident alerts.
- If the condition resolves while its initial alert is still pending, preserve the initial alert and the recovery alert as distinct incident transitions and deliver/reconcile them in a sensible order where possible.
- Exact backoff schedule, retry limits, pending-alert storage, delivery acknowledgements, and behavior when the alert service remains unavailable are implementation details to define and test.

This separates the trading safety response from the notification channel: Telegram improves operator awareness, but a Telegram outage must not disable safety controls or erase the record of a SEVERE event.

### Q9.22 — What if Telegram remains unavailable for a long time? **RESOLVED — Option C: Configurable retry policy; preserve alerts and safety state**

**Decision:** Support a configurable policy for prolonged Telegram outages: either stop retrying after a configured limit or continue low-frequency retries. Regardless of the chosen retry policy, preserve the alert and its delivery status, and never weaken the trading safety state because Telegram is unavailable.

- Support configuration for a maximum retry limit or continued low-frequency retries after the bounded-backoff schedule in Q9.21.
- Preserve undelivered detection and recovery alerts so they are not lost when retrying stops or Telegram remains unavailable for an extended period.
- Record the alert's delivery state, attempts, last result, and any transition to a retry-exhausted/pending state in the notification/exception records.
- A retry policy decision must not change the underlying incident status or trading safety response. SEVERE conditions continue to enforce their required safety behavior regardless of Telegram delivery.
- Preserve Q9.20's notification semantics: one logical alert on incident detection and one on recovery, with delivery retries treated as attempts to deliver those same alerts rather than new incident notifications.
- Exact retry-limit defaults, low-frequency retry intervals, alert persistence, and any operator-triggered replay/recovery workflow remain open for implementation design and testing.

This keeps prolonged notification outages configurable without coupling Telegram delivery success to trading safety controls.

### Q9.23 — What information should a SEVERE Telegram alert contain? **RESOLVED — Option B: Actionable alert**

**Decision:** A SEVERE Telegram alert should contain enough concise, actionable context to quickly understand what happened and what Flow_v2 is doing, without making the notification unnecessarily large. Include the condition name, severity, timestamp, affected component, brief explanation, current safety action, and incident ID. Include relevant trade/order IDs when applicable.

- Include the condition/incident name and **SEVERE** severity.
- Include the detection or recovery timestamp, as applicable, and a stable incident ID.
- Identify the affected component and briefly explain the condition.
- State the current safety action/status (for example, new entries blocked while broker position state is unconfirmed).
- Include relevant trade idea, order, execution/fill, or position IDs when applicable and available.
- Keep detailed diagnostics in the logs and exception records rather than bloating the Telegram message. Additional diagnostic context can be consulted during investigation.
- Detection and recovery messages should follow Q9.20; failed deliveries follow Q9.21–Q9.22. Retries must not create duplicate logical alerts.
- Exact message templates, field formatting, and size limits remain implementation details to define and test.

This gives the operator enough context to understand the incident and immediate system response while keeping detailed diagnostics in the audit trail.

### Q9.24 — What should a recovery alert contain? **RESOLVED — Option B: Actionable recovery summary**

**Decision:** A recovery Telegram alert should include the minimal incident/recovery identifiers plus an actionable summary: incident ID, condition name, recovery timestamp/status, incident duration, what recovered, and whether the relevant safety restriction has been lifted or remains active.

- Identify the same incident using its stable incident ID and condition name so the recovery can be correlated with the original SEVERE alert.
- Include the recovery timestamp and resolved status.
- Include the incident duration where it can be calculated reliably.
- State what recovered and whether the associated safety restriction has actually been lifted or remains active. Do not imply normal trading has resumed merely because one underlying fault appears resolved.
- Preserve the Q9.20 policy: send a recovery alert when the incident is confirmed resolved, with no repeated alerts while the same incident remains active. A later recurrence is a new incident.
- Follow Q9.21–Q9.22 for delivery retries and prolonged Telegram outages: preserve undelivered recovery alerts, retry according to configuration, and never couple notification delivery to the trading safety state.
- Record the recovery event and notification outcome in the exception log and linked audit trail. Detailed diagnostics and retry counts remain available in logs; they are not required in the recovery message by default.
- Exact message templates, incident-duration calculation, and the definition of when a safety restriction can be lifted remain implementation details to define and test.

This provides confirmation of recovery while explicitly communicating whether Flow_v2 has resumed normal entry operation or remains in a safety state.

### Q9.25 — Who decides when a SEVERE incident is resolved? **RESOLVED — Option C: Condition-specific recovery criteria**

**Decision:** Each SEVERE condition must define its own recovery criteria. Some conditions may resolve automatically after verified recovery checks; others may require manual intervention or additional confirmation. A condition's safety restriction must not be lifted until its defined recovery requirements are satisfied.

- Define recovery criteria per condition rather than applying one universal recovery rule.
- Where safe and appropriate, a condition may resolve automatically after its required checks pass and the relevant state is verified.
- Conditions that require manual intervention, additional reconciliation, or operator confirmation must remain active until those requirements are satisfied.
- Incident resolution and lifting a trading safety restriction are related but distinct states. Do not lift a restriction merely because an alert was sent or one underlying symptom disappeared.
- Only after the condition's recovery criteria are satisfied should the incident transition to resolved and its recovery Telegram alert be generated, stating whether the relevant safety restriction has been lifted or remains active.
- Preserve the Q9.20–Q9.24 alerting rules: alert on detection and recovery, do not repeatedly alert for the same active incident, preserve/retry undelivered alerts, and include actionable incident/recovery context.
- Exact recovery checks, manual-approval requirements, timeouts, and transitions for each SEVERE condition remain to be specified and tested individually.

This keeps recovery behavior appropriate to each failure mode while preventing the system from resuming entries before its safety requirements are met.

### Q9.26 — Who defines which conditions are classified as SEVERE? **RESOLVED — Option C: Central severity policy**

**Decision:** Maintain a central, configurable severity policy that classifies conditions and defines the required response for each condition. New conditions must be explicitly classified; they must not silently default to SEVERE or non-SEVERE.

- Maintain one central policy for condition severity and required system response, rather than letting each component independently invent severity behavior.
- Each known condition must have an explicit severity classification and associated response policy.
- The policy is configurable, but changes should be explicit and traceable.
- Every condition classified as **SEVERE** must trigger Telegram alerting under Q9.19–Q9.24, including detection and recovery notifications and the established delivery retry behavior.
- Each SEVERE condition must define condition-specific recovery criteria under Q9.25. A safety restriction must not be lifted until those criteria are satisfied.
- New conditions must be explicitly classified before being treated as an ordinary operational condition; no silent default to SEVERE or non-SEVERE is allowed. The exact handling of an unclassified condition remains to be specified during implementation design.
- The complete condition inventory, severity levels beyond the currently defined SEVERE category, response mapping, configuration format, and change-control mechanism remain open for implementation and testing.

This centralizes severity decisions so alerting, safety actions, and recovery behavior remain consistent across Flow_v2.

### Q9.27 — What if Flow_v2 encounters an unclassified condition at runtime? **RESOLVED — Option B: Fail safely in the affected component**

**Decision:** If a condition has no explicit severity/response classification, fail safely within the affected component: block new entries from that component, record a severity-policy/configuration error, and require explicit classification before normal operation resumes there. Do not silently treat the unknown condition as ordinary or invent a severity classification at runtime.

- Block new entries in the affected component while its condition is unclassified.
- Record the unknown condition and the missing severity/response mapping in the exception log and relevant audit trail.
- Require an explicit policy classification before that component returns to normal entry operation.
- Keep the response scoped to the affected component by default; do not automatically halt unrelated components unless the condition's actual impact or a separate safety rule requires it.
- Preserve existing central-policy rules: known conditions use their explicit severity and response mapping; every explicitly SEVERE condition follows the established Telegram detection/recovery alert behavior.
- The severity and Telegram treatment of an unclassified condition itself, and the exact recovery/re-enable checks, remain to be defined by implementation policy; do not silently classify it as SEVERE or non-SEVERE.

This prevents an unknown condition from continuing to create new exposure without unnecessarily disabling unrelated components.

### Q9.28 — What if an unclassified condition affects multiple components? **RESOLVED — Option C: Scope the safety response to actual impact; fail system-wide if impact cannot be isolated**

**Decision:** Scope the safety response to every component that depends on the affected service or is otherwise affected by the condition. If the impact cannot be reliably isolated, block all new entries until the condition is understood and safely classified.

- Identify the affected service/component and its dependency relationships where known.
- Block new entries in all dependent or affected components; do not limit the response to only the component that first detected the condition if shared dependencies may be compromised.
- If the impact scope cannot be confidently established, block all new entries system-wide until the condition is understood and the safety scope can be determined.
- Preserve the Q9.27 rule: an unclassified condition must not silently default to SEVERE or non-SEVERE, and affected components must not resume normal entry operation until explicit classification and required recovery checks are complete.
- Record the condition, affected/dependent components, safety actions, and resolution in the exception log and relevant audit trail. Apply the central severity/alert policy once the condition is classified; do not invent a severity classification at runtime.
- Exact dependency mapping, impact-isolation checks, and recovery/re-enable criteria remain implementation details to define and test.

This limits disruption when impact can be reliably isolated, while failing safely across the system when an unknown shared failure could affect multiple entry paths.

### Q9.29 — Should an unclassified runtime condition trigger a Telegram alert immediately? **RESOLVED — Option B: Send a special unclassified-condition alert**

**Decision:** Send a Telegram alert when an unclassified condition is detected, without falsely assigning it a severity. This is a distinct alert category, not an implicit SEVERE or non-SEVERE classification.

- Notify the operator promptly that Flow_v2 encountered an unclassified condition.
- Identify it explicitly as **UNCLASSIFIED CONDITION**; do not label it SEVERE or non-SEVERE until the central policy assigns a classification.
- Include the incident ID, detection timestamp, affected component(s), brief explanation, and current safety action. Include relevant trade/order/fill/position IDs where applicable and available.
- Apply Q9.27–Q9.28 safety behavior independently of Telegram delivery: block entries in affected components, or system-wide if impact cannot be reliably isolated.
- Preserve the alert in the notification/audit system and apply the established delivery retry and deduplication behavior; notification failure must not weaken the safety state.
- Explicit classification and the applicable recovery checks are still required before affected components resume normal entry operation.
- Exact message template and lifecycle of an unclassified-condition alert, including recovery notification after classification, remain to be specified and tested.

This makes unknown runtime conditions visible without silently inventing their severity.

### Q9.30 — What happens when an unclassified condition is subsequently classified? **RESOLVED — Option C: Keep one incident with a classification history**

**Decision:** Keep one continuous incident record. Preserve the original unclassified detection and alert, append the classification decision and timestamp, and apply the newly assigned central severity/response policy.

- Do not erase or replace the original detection, alert, safety actions, or prior incident state.
- Append the classification decision, timestamp, policy version, and resulting response changes to the incident history.
- Apply the newly assigned policy to the active incident through the controlled policy-change process in Q9.35.
- Keep the incident identity stable; do not create a separate incident solely because the classification was added.
- A condition that has already resolved operationally may retain an outstanding classification/review task under Q9.32.

This preserves a single auditable incident lifecycle from initial unknown detection through classification and resolution.

### Q9.31 — Should Telegram notify the operator when the classification changes? **RESOLVED — Option A: Alert if the response materially changes**

**Decision:** Send a Telegram classification-update alert when the new classification materially changes the required response. If the response does not materially change, record the classification update in the incident history without sending another Telegram alert.

- Include the incident ID, prior and new classification, timestamp, affected components, and any material change to the safety response.
- Preserve the original detection alert and incident history.
- Apply the new response policy through the controlled change process; a notification must not itself change or lift a safety restriction.
- Avoid unnecessary Telegram noise when the classification changes but the required response does not.
- The exact definition of a material response change and the message template remain implementation details to define and test.

### Q9.32 — What if an unclassified condition is resolved before it is classified? **RESOLVED — Option C: Close operationally, retain classification follow-up**

**Decision:** Allow the condition to be marked operationally resolved when its observed fault and required operational recovery checks have cleared, while retaining an explicit outstanding classification/review task. Resolution does not silently assign a severity.

- Preserve the original unclassified detection, safety actions, and event history.
- Record operational resolution separately from classification/review completion.
- Retain the incident or linked review record as pending classification until an explicit policy classification is assigned; do not invent a severity after the fact.
- Apply any retrospective classification and required audit/update behavior once the condition is classified.
- Do not lift any safety restriction until the applicable recovery requirements are satisfied. If the condition's safety scope cannot be established, continue the relevant safety state until it can be.
- The exact status model for “operationally resolved but awaiting classification” remains an implementation detail to define and test.

### Q9.33 — What severity levels should the central policy support? **RESOLVED — Option B: INFO, WARNING, SEVERE**

**Decision:** The central severity policy supports three explicit levels: **INFO**, **WARNING**, and **SEVERE**.

- **INFO:** Informational condition; apply its explicitly configured response, if any.
- **WARNING:** A condition requiring attention or monitoring; apply its explicitly configured response.
- **SEVERE:** A condition requiring the configured severe response and Telegram detection/recovery alerts under the existing Q9.19–Q9.25 rules.
- Every known condition must be explicitly mapped to a severity and response policy. Unknown conditions remain **UNCLASSIFIED** until explicitly classified; they must not silently default to any of these levels.
- The precise definitions, per-condition response mappings, and any entry-blocking behavior for INFO and WARNING must be documented in the central policy rather than inferred from the level name alone.

This defines the severity taxonomy without assuming that every condition at a given level has an identical operational response.

### Q9.34 — What if the central severity policy is missing, invalid, or fails to load? **RESOLVED — Option B: Block all new entries until a valid policy loads**

**Decision:** If Flow_v2 cannot load and validate a usable central severity policy, block all new entries until a valid policy is loaded and validated.

- Do not start or resume normal entry operation without a valid severity/response policy.
- Continue managing and reconciling known open positions and outstanding orders as safely as possible; the entry block must not disable existing exposure management.
- Log the policy-loading/validation failure and record it in the exception system and audit trail.
- Where the Telegram channel is configured, notify the operator according to the applicable alert policy; Telegram failure must not weaken the entry block.
- Resume new entries only after a valid policy is loaded and validated and the applicable startup/recovery checks pass.
- Exact validation rules, policy versioning, and startup/reload behavior remain implementation details to define and test.

### Q9.35 — When should a severity-policy change take effect? **RESOLVED — Option C: Controlled reload with active-incident rules**

**Decision:** Severity-policy changes take effect through an explicit controlled reload or deployment step. Active incidents must be re-evaluated according to defined migration rules rather than being changed silently mid-operation.

- Validate the proposed policy before activating it; retain the last known valid policy while preparing a change where safe and supported.
- Record the active policy version and the version used for each incident decision.
- Define how active incidents are re-evaluated under the new policy, including severity changes, response changes, notification behavior, and recovery criteria.
- Do not automatically lift an existing safety restriction merely because a policy version changed. The condition's recovery requirements must still be satisfied.
- If a valid policy cannot be established during reload, apply Q9.34 and block new entries until a valid policy is available.
- Exact reload mechanics, validation, rollback, and active-incident migration rules remain to be designed and tested.

### Q9.36 — What if several alerts originate from the same underlying failure? **RESOLVED — Option C: Parent incident with linked component incidents**

**Decision:** Represent a shared root cause as a parent incident with linked component-level incidents. Send one primary Telegram alert for the shared root cause while retaining each affected component's individual failures and impact for diagnosis.

- Create or associate component-level incident records for the affected entry models/services and link them to the parent incident.
- Send one primary alert for the shared cause rather than flooding Telegram with duplicate alerts for each dependent component.
- Preserve affected-component details, local symptoms, safety actions, and recovery status in the linked records and audit trail.
- Keep the safety scope based on actual impact: block every affected/dependent entry path, and block all new entries if impact cannot be reliably isolated, consistent with Q9.28.
- Track recovery for the parent and component incidents so one component recovering does not incorrectly imply that all affected components have recovered.
- If an independently meaningful component incident requires separate notification under the explicit alert policy, it may be notified according to that policy; grouping must not hide a material change in risk or safety state.
- Exact correlation/grouping criteria and parent/child lifecycle rules remain implementation details to define and test.

### Q9.37 — Should every SEVERE condition have a predefined recovery checklist? **RESOLVED — Option C: Full lifecycle specification required**

**Decision:** Every condition in the central policy must have a complete lifecycle specification before it is enabled for normal operation. For each SEVERE condition, that specification must explicitly define detection, severity, safety response, Telegram notifications, recovery checks, and re-enable criteria.

- **Detection:** Define the observable condition and when an incident starts.
- **Classification:** Define the explicit severity level and policy mapping.
- **Safety response:** Define which components/entry paths are blocked and how existing exposure and outstanding orders continue to be managed.
- **Notifications:** Define detection, material classification-change, and recovery notification behavior, including required content and delivery policy.
- **Recovery checks:** Define the evidence required to mark the condition resolved, including any reconciliation or operator confirmation.
- **Re-enable criteria:** Define when affected components may resume new entries; incident resolution alone does not lift restrictions unless the recovery requirements say so.
- **Auditability:** Define the incident events, identifiers, policy version, and relevant component/trade/order references to retain.
- The full inventory and individual lifecycle specifications must be completed and tested; no SEVERE condition should rely on an undocumented recovery assumption.

This standardizes condition handling while allowing each condition to have its own justified detection and recovery logic.

### Q9.38 — What if Telegram configuration is missing or invalid at startup? **RESOLVED — Option C: Keep trading safety independent; preserve alerts and retry**

**Decision:** Missing or invalid Telegram configuration must be reported through logs and the exception system. Preserve pending alerts and retry notification delivery when configuration or service availability is restored. Do not automatically block all new entries solely because Telegram is misconfigured; trading safety controls remain independent of the notification channel.

- Detect and record missing/invalid bot credentials, chat configuration, or other required Telegram settings at startup/configuration validation.
- Preserve pending notifications and their incident/alert identities so they can be delivered later; do not discard detection or recovery alerts because the channel is unavailable.
- Retry according to the established configurable delivery policy once valid configuration is available, without creating duplicate logical alerts.
- Do not let Telegram configuration or delivery failure disable a required safety state, lift an entry block, or otherwise weaken trading safety.
- Do not halt all new entries solely because Telegram is misconfigured. If a separate trading condition or safety policy independently requires an entry block, enforce that block normally.
- Record notification configuration/delivery failures for diagnosis and recovery. Exact startup validation, retry trigger, and operator-notification fallback when Telegram itself is unavailable remain implementation details to define and test.

This keeps Telegram as an alerting channel rather than a mechanism that enforces trading safety, while ensuring failed notifications remain visible and recoverable.

## Footprint review follow-up — Q2.4, Q2.6, Q2.7

### Q2.4 — How should finished-auction side mapping be settled? **RESOLVED — Option C: Verify feed fields and test examples first**

**Decision:** Keep finished-auction side mapping unresolved until the Rithmic/feed bid/ask fields and high/low footprint examples have been verified. Do not implement a guessed side mapping.

- Preserve the existing provisional definition: a finished auction requires exactly zero volume on the relevant footprint side at the extreme; no near-zero tolerance is assumed in the baseline.
- Before implementation, verify which feed fields represent bid-side and ask-side traded volume and how they map to the high and low extreme rows.
- Test representative high- and low-extreme examples against raw feed data and the rendered footprint.
- Record the verified mapping and test evidence before enabling the finished-auction detector.
- Until verified, treat the exact side mapping as unresolved rather than inferring it from general footprint conventions.

### Q2.6 — What counts as consecutive stacked-imbalance levels? **RESOLVED — Option B: Consecutive configured footprint rows**

**Decision:** Stacked imbalance is measured across consecutive footprint rows after the configured row grouping has been applied. The provisional minimum remains three consecutive qualifying rows, and the threshold remains configurable.

- A stack consists of adjacent rows in the actual configured footprint representation; a gap or non-qualifying row breaks the stack.
- Changing the row-grouping configuration may change whether a stack qualifies, because the evaluated units are the configured footprint rows.
- Preserve the existing configurable diagonal imbalance ratio and minimum-volume settings; this decision does not independently change their values or application.
- Keep the initial parameters provisional and validate them through research/backtesting.

### Q2.7 — Where should the minimum-volume filter apply? **RESOLVED — Option C: Configurable research modes**

**Decision:** Make the minimum-volume filter application configurable so research can compare both-side, dominant-side, and no-separate-minimum modes.

- Preserve the provisional baseline of three contracts per compared level, with the value configurable.
- Support at least these research modes: (1) both sides of each diagonal comparison must meet the minimum, (2) only the dominant side must meet the minimum, and (3) no separate minimum-volume filter is applied.
- Record the active mode and threshold with strategy/configuration metadata so results can be reproduced and compared fairly.
- Do not silently change the mode between live operation and backtesting; require an explicit configuration choice.
- The selected production mode remains a research decision and must be justified by testing rather than assumed from this decision alone.

## Review decisions — §3 footprint and aggression (continued)

### Q3.1 — Big-trade size definition
**Decision: Option C — Support raw-print and aggregated-print modes as distinct research modes.**
- Preserve the provisional Gold baseline of 30 contracts per individual print.
- In raw-print mode, evaluate each exchange trade print independently.
- In aggregated-print mode, group prints at the same price within a configurable time window before evaluating the threshold.
- Keep raw-print and aggregated-print results distinct so research can compare them without losing source evidence.
- **Open numerical parameters:** aggregation time window and any instrument-specific thresholds. The 30-contract baseline remains provisional and configurable.

### Q3.2 — Aggression-response measurement window
**Decision: Option C — Support configurable windows per model and compare multiple candidate durations in research.**
- Continue tracking maximum favorable excursion (MFE) and the price at the end of each measurement window.
- Allow each entry model to configure its own window settings.
- Research should support comparison of multiple candidate durations rather than silently selecting one universal duration.
- **Open numerical parameters:** candidate durations, model-specific default duration, and any associated excursion/follow-through thresholds.

### Q3.3 — Repeated aggressive-print burst grouping
**Decision: Option C — Make AND/OR grouping configurable for research; preserve individual prints regardless of grouping.**
- Keep both time proximity and price-distance thresholds configurable.
- Support an AND mode, where both thresholds must pass, and an OR mode, where either threshold can establish burst membership.
- Retain the underlying individual prints and their timestamps/prices so grouped signals remain auditable and can be recomputed under alternate settings.
- **Open numerical parameters:** time window and price-distance threshold. The selected AND/OR mode is a research/configuration choice, not a universal hard-coded rule.

These decisions extend the existing §3 rules without changing prior decisions. Local review document only; no GitHub changes made.

## Review decisions submitted — absorption research batch (submitted Q4.2, Q4.3, Q4.6)

**Numbering/cross-reference note:** The submitted batch reused identifiers that already exist in this document with different meanings. To preserve all prior decisions, the choices below are recorded as the decisions from this review batch and are not used to overwrite the earlier Q4.2/Q4.3/Q4.6 entries. The canonical question-number crosswalk should be cleaned up during a later document pass.

### Submitted Q4.2 — Absorption measurement window — Option C

**Decision:** Support model-specific configurable absorption measurement windows and compare multiple candidate durations during research.
- Keep timestamps for DOM liquidity and executed-volume footprint evidence so events can be aligned for each tested window.
- Allow different absorption models/contexts to use different window settings.
- Research multiple candidate durations rather than treating one duration as universally correct.
- **Open numerical parameters:** candidate durations, model-specific defaults, and exact event-alignment tolerances. Values remain provisional and configurable.

### Submitted Q4.3 — Significant resting liquidity — Option C

**Decision:** Support absolute, relative, and combined thresholds as configurable research modes for determining whether resting liquidity is significant enough to support an absorption interpretation.
- Absolute mode evaluates resting volume against a configurable contract threshold.
- Relative mode compares resting volume with nearby price levels and/or a local baseline.
- Combined mode applies both absolute and relative criteria.
- Preserve the requirement that failed aggression alone is not absorption; supporting DOM/resting-liquidity evidence is required.
- **Open numerical parameters:** absolute volume threshold, comparison neighborhood, local-baseline calculation, relative threshold, persistence/replenishment criteria, and any combined-mode logic. No threshold is validated yet.

### Submitted Q4.6 — Absorption completion — Option C

**Decision:** Keep absorption detection/attempt separate from absorption completion. Make completion criteria configurable and compare them in research; completion is not itself a trade trigger.
- An absorption attempt is supported by failed aggression plus resting-liquidity evidence.
- Delta flip remains an existing candidate for confirming completion, with both sign-change-only and sign-change-plus-price-non-extension definitions available for comparison.
- Do not overwrite the existing Q4.6 definition that failed aggression is broader than absorption. This submitted answer expands the completion research approach rather than changing that conceptual distinction.
- **Open research parameters:** whether bar delta, price-level delta, or both are appropriate by model/context; candidate windows; price non-extension definition; and thresholds. All remain provisional/configurable.

These decisions preserve prior content and add no validated numerical thresholds. Local document only; no GitHub changes made.

## Review decisions submitted — delta confirmation and exhaustion batch (submitted Q4.10–Q4.12)

**Numbering/cross-reference note:** These identifiers follow the interactive review batch and may overlap with identifiers used elsewhere in the original document. Preserve earlier entries; reconcile the canonical question-number crosswalk in a later cleanup pass rather than overwriting prior decisions.

### Submitted Q4.10 — Delta-flip confirmation by model — Option C

**Decision:** Keep bar delta and price-level delta selectable by model/context, and compare both confirmation definitions in research.
- Support bar-delta and price-level-delta measurements as distinct inputs; preserve the underlying data and the measurement scope used for each result.
- Compare a delta sign change alone against a delta sign change accompanied by price non-extension.
- Do not make delta flip a universal mandatory confirmation for every model; model-specific rules may define whether it is required, supporting, or unused.
- **Open research parameters:** delta aggregation period, sign-change semantics, price non-extension window/threshold, and model-specific applicability. All remain provisional/configurable.

### Submitted Q4.11 — Exhaustion price reference — Option C

**Decision:** Make the reference price configurable, with the last qualifying aggressive print and the extreme reached during the failed-aggression/absorption sequence available as separate research modes.
- Retain which reference was used in every event and research result so alternatives can be compared reproducibly.
- **Open research parameters:** how to define the sequence extreme when multiple prints/zones qualify, measurement-window boundaries, and any reference reset rules.

### Submitted Q4.12 — Meaningful opposing price progress — Option C, with an additional exhaustion signal

**Decision:** Support absolute tick-distance and volatility-relative distance as configurable research modes, including a combined mode. Additionally, treat price moving beyond the level/zone where the aggression began as a potentially useful exhaustion signal.
- Keep the original candidate measures: absolute price-distance threshold in ticks, volatility-relative threshold, and a combined mode.
- Add a distinct configurable signal that detects price moving beyond the level/zone where the relevant aggression started. This can strengthen an exhaustion interpretation, but is not by itself a universal trade trigger.
- Preserve the aggression-start level/zone used by the event, its direction, the price reference, and the time at which price crosses beyond it. Do not infer the zone retrospectively without recording the rule used to establish it.
- Evaluate the beyond-start-zone signal independently and in combination with the other exhaustion signals; do not automatically count correlated signals as independent evidence.
- **Open research parameters:** tick-distance threshold, volatility measure and lookback, combined-mode logic, definition and width of the aggression-start level/zone, required penetration distance, timing window, and whether follow-through is required after crossing. These remain provisional/configurable and need validation.

These decisions extend the reviewed rules without replacing prior decisions. Local review document only; no GitHub changes made.

## Review decisions submitted — exhaustion detection and signal aggregation batch (submitted Q4.13–Q4.15)

**Numbering/cross-reference note:** These identifiers follow the interactive review batch and may overlap with identifiers used elsewhere in the original document. Preserve earlier entries; reconcile the canonical question-number crosswalk in a later cleanup pass rather than overwriting prior decisions.

### Submitted Q4.13 — Exhaustion signal aggregation — Option C

**Decision:** Support weighted scoring and explicit rule combinations as separate configurable research modes, while preserving each signal's individual result.
- Keep individual exhaustion evidence observable, including declining aggression footprint volume, declining big-print size, opposing price progress, and price moving beyond the level/zone where aggression began.
- Support a configurable weighted-score mode and a separate explicit rule-combination mode.
- Retain the contributing signals, their raw measurements, timestamps, configuration, and final assessment so results can be reproduced and reviewed.
- Avoid treating correlated signals as independent evidence automatically; correlation/grouping treatment must be explicit in the model or research configuration.
- Exhaustion assessment is evidence, not an automatic trade trigger. Entry eligibility remains subject to the existing strategy, aggregation, and risk-management rules.
- **Open research parameters:** signal weights, score threshold, rule combinations, normalization, missing-data behavior, and correlation treatment. No values are validated yet.

### Submitted Q4.14 — Declining aggression measurement — Option C

**Decision:** Support both immediate preceding-window comparisons and rolling-baseline comparisons, with configurable lookbacks and separate outputs.
- Preserve the current measurement window and the comparison window/baseline used for each result.
- Compare current aggression footprint volume and big-print size against the immediately preceding window as one mode.
- Support a rolling baseline over a configurable number of prior windows as a separate mode.
- Keep aggression volume and big-print size as distinct signals; do not assume a decline in one implies a decline in the other.
- **Open research parameters:** window duration, rolling lookback count, baseline statistic, minimum sample requirements, decline threshold, and handling of sparse/zero-volume windows. Values remain provisional/configurable.

### Submitted Q4.15 — Exhaustion lifecycle and reset — Option C

**Decision:** Support both time-based expiry and event/context-based invalidation, with configurable rules by model; preserve state transitions.
- An exhaustion assessment may expire after a configured duration, or become invalid when the originating context is invalidated or superseded by new qualifying aggression.
- Retain explicit lifecycle states and transition reasons, including creation, update, completion, expiry, invalidation, and supersession where applicable.
- Preserve the original aggression-start level/zone, reference price, evidence window, and configuration used to create the assessment.
- Do not allow stale exhaustion evidence to remain active indefinitely; a model must define its expiry/invalidation policy before normal use.
- Resetting or expiring an exhaustion assessment does not itself close an existing position or bypass the existing position-management rules.
- **Open research parameters:** expiry duration, invalidation conditions, supersession rules, reset/re-arm conditions, and whether each model permits a new assessment immediately after invalidation. Values remain provisional/configurable.

These decisions extend the reviewed rules without replacing prior decisions. Local review document only; no GitHub changes made.

## Review decisions submitted — exhaustion interpretation and event integrity batch (submitted Q4.16–Q4.18)

**Numbering/cross-reference note:** These identifiers follow the interactive review batch and may overlap with identifiers used elsewhere in the original document. Preserve earlier entries; reconcile the canonical question-number crosswalk in a later cleanup pass rather than overwriting prior decisions.

### Submitted Q4.16 — Downstream exhaustion representation — Option C

**Decision:** Preserve both an exhaustion detection state and a strength score, together with supporting evidence and direction. Each consuming model decides how to use these outputs.
- Expose detection state and strength score as separate fields; do not collapse them into one opaque result.
- Include direction, contributing signals, underlying measurements, timestamps, event/context identity, and configuration/version information where available.
- Downstream models may treat exhaustion as mandatory, supporting, or unused evidence according to their own explicit rules; exhaustion is not a universal trade trigger.
- **Open research/implementation details:** state taxonomy, score scale and normalization, missing/invalid input handling, and per-model consumption rules. These remain to be specified and validated.

### Submitted Q4.17 — Overlapping exhaustion signal identity — Option C

**Decision:** Preserve model-specific signals and link them to a shared event/context when appropriate, using configurable time, price, and context criteria. Never discard raw signals.
- Each model's signal remains independently auditable, including raw values and creation/update timestamps.
- A shared event/context link may express that multiple signals relate to the same underlying aggression/absorption sequence; linking does not imply that signals are statistically independent or should each add full weight.
- Use explicit, configurable criteria for linking signals and retain the link rationale/configuration so research can reproduce grouping.
- **Open research parameters:** time window, price-distance tolerance, context-match rules, event-link merge/split policy, and correlation handling. Values remain provisional/configurable.

### Submitted Q4.18 — Exhaustion re-arming — Option C

**Decision:** Make re-arming configurable by model: allow either immediate re-detection or waiting for context reset, while recording the chosen rule and preventing duplicate events.
- Each model must declare its re-arm policy rather than inheriting an undocumented global default.
- Preserve the prior assessment's lifecycle and identity; a new qualifying assessment must have a distinct identity, with a link to the prior assessment/context when appropriate.
- Deduplicate repeated processing of the same underlying input/event, but do not suppress a genuinely new assessment that meets the configured policy.
- **Open research/implementation details:** context-reset definition, deduplication keys/window, repeated-signal update versus new-event criteria, and per-model defaults. Values remain provisional/configurable.

These decisions extend the reviewed rules without replacing prior decisions. Numerical and behavioral parameters that have not been validated remain explicitly open. Local document only; no GitHub changes made.

## Review decisions submitted — signal conflict and data-quality batch (submitted Q4.19–Q4.21)

**Numbering/cross-reference note:** These identifiers follow the interactive review batch and may overlap with identifiers used elsewhere in the original document. Preserve earlier entries; reconcile the canonical question-number crosswalk in a later cleanup pass rather than overwriting prior decisions.

### Submitted Q4.19 — Conflicting exhaustion signals — Option C

**Decision:** Preserve each signal and any conflict explicitly; use configurable weighted scoring or model-specific rules to determine the exhaustion assessment.
- Retain each signal's value, direction, timestamp, data-quality status, and contribution to the assessment.
- Explicitly represent disagreement—for example, declining aggression while price continues to advance strongly—rather than hiding it behind a single result.
- Let each model's configured scoring/rule mode decide how conflicting evidence affects its assessment; do not impose a universal precedence rule.
- Avoid double-counting correlated signals as independent evidence.
- **Open research parameters:** conflict taxonomy, scoring/rule behavior for each conflict class, thresholds, and how conflicts affect confidence/state. Values remain provisional/configurable.

### Submitted Q4.20 — Missing, stale, or invalid data — Option C

**Decision:** Track data quality per input. Each model must specify which inputs are mandatory, optional, or assessment-invalidating. Missing data must never be silently treated as a genuine negative signal.
- Distinguish at least valid, missing, stale, and invalid input states where applicable.
- Preserve the reason and timestamp for a data-quality failure and identify which signals/assessments depend on the affected input.
- If an optional input is unavailable, continue only under the model's explicitly defined degraded-data behavior and expose that degradation in the result.
- If a mandatory input is unavailable or invalidating, mark the affected assessment unavailable/invalid rather than manufacturing a negative signal or silently substituting zero.
- Propagate data-quality restrictions to downstream consumers so a degraded assessment cannot be mistaken for a fully valid one.
- **Open implementation parameters:** freshness limits, stale-data detection, required-input declarations per model, degraded-mode behavior, recovery criteria, and whether particular failures block only the affected model or dependent components. Values remain provisional/configurable.

### Submitted Q4.21 — Exhaustion strength decay — Option C

**Decision:** Support configurable time decay and event-driven updates. Preserve the raw historical assessment and record why the current strength changed.
- Keep the original assessment and evidence immutable/auditable; derive current strength as a separate state/projection.
- Support configurable time-based decay functions and event-driven changes when new aggression, price progress, context invalidation, or other qualifying evidence arrives.
- Record the input event, rule/configuration version, prior value, new value, and reason for each update where applicable.
- Decay must not conceal a hard invalidation or expiry condition; lifecycle rules remain independently enforced.
- **Open research parameters:** decay function, half-life/rate, update cadence, event-driven adjustment rules, minimum/maximum strength, and interactions with expiry/invalidation. No numerical values are validated yet.

These decisions extend the reviewed rules without replacing prior decisions. All unvalidated numerical and behavioral parameters remain explicitly open. Local document only; no GitHub changes made.


## Review decisions submitted — data recovery and downstream consumption (submitted Q4.22–Q4.24)

**Numbering/cross-reference note:** These identifiers follow the interactive review batch and may overlap with identifiers used elsewhere in the original document. Preserve earlier entries; reconcile the canonical question-number crosswalk in a later cleanup pass rather than overwriting prior decisions.

### Submitted Q4.22 — Data recovery behavior — Option C

**Decision:** Make recovery behavior model-specific. Support immediate resumption or requiring a fresh measurement window, and record the recovery transition.
- When an input becomes valid again, record the transition from missing/stale/invalid to recovered, including timestamps and the affected inputs/assessments.
- Each model must explicitly specify whether recovery permits immediate evaluation or requires a fresh valid measurement window.
- Do not retroactively treat the gap as if valid observations had been available during it.
- Apply the model's recovery policy before allowing the assessment to return to a fully valid state.
- **Open implementation parameters:** recovery confirmation criteria, fresh-window duration, minimum consecutive valid observations, and per-model defaults. Values remain provisional/configurable.

### Submitted Q4.23 — Downstream use of degraded assessments — Option C

**Decision:** Each downstream consumer declares whether it accepts fully valid assessments, degraded assessments, or only fully valid assessments for trade eligibility. Data quality must always be exposed.
- Include data-quality state and relevant missing/stale/invalid reasons in the assessment interface.
- A consumer may use degraded evidence for observation or scoring only if its explicit policy allows it.
- Trade eligibility must respect the consumer's configured acceptance policy; a degraded assessment must never silently appear fully valid.
- Log when an assessment is rejected, downgraded, or accepted in degraded mode, including the applicable consumer policy/version.
- **Open implementation parameters:** allowed quality states per consumer, whether degraded evidence can contribute to scoring versus eligibility, and propagation behavior through aggregation. These remain configurable and must be explicitly declared.

### Submitted Q4.24 — Late or corrected market events — Option C

**Decision:** Preserve the original assessment and audit history, then append a revision or superseding assessment when correction rules require it. Record the source event, revision reason, and rule version.
- Never silently overwrite the assessment that was produced from the data available at the time.
- Retain event identity, event time, receive/processing time when available, source sequence/version, and correction relationship where supplied by the feed.
- Recompute affected derived assessments according to explicit correction/replay rules; record whether a revision updates a current-state projection or creates a superseding assessment.
- Downstream consumers must be able to distinguish original, revised, and superseded results. Any already-triggered trading action remains an auditable historical action and is not retroactively erased by a data correction.
- **Open implementation parameters:** correction horizon, replay scope, event ordering/watermark rules, revision-versus-supersession criteria, and treatment of already-consumed assessments. Values and policies remain provisional until feed capabilities and replay behavior are validated.

These decisions extend the reviewed rules without replacing prior decisions. All unvalidated numerical and behavioral parameters remain explicitly open. Local document only; no GitHub changes made.


## Review decisions submitted — correction and replay (submitted Q4.25–Q4.27)

**Numbering/cross-reference note:** These identifiers follow the interactive review batch and may overlap with identifiers used elsewhere in the original document. Preserve earlier entries; reconcile the canonical question-number crosswalk in a later cleanup pass rather than overwriting prior decisions.

### Submitted Q4.25 — Late-event replay scope — Option C

**Decision:** Support configurable replay scope: current assessment only, a bounded historical window, or replay through the present. Preserve original outputs and record the replay boundary and rule version.
- The replay policy must define which derived assessments are affected by a late or corrected event and how far recomputation propagates.
- Preserve original live outputs as immutable history; replay produces explicitly identified revisions or superseding outputs rather than silently replacing the historical record.
- Record the triggering event/correction, replay start and end boundaries, source-data version/watermark where available, rule/configuration version, and completion status.
- **Open implementation parameters:** default replay scope per data/event type, bounded-window duration, replay cutoff/watermark behavior, dependency propagation, resource limits, and failure/retry behavior. These remain unvalidated/configurable.

### Submitted Q4.26 — Downstream effects of revised assessments — Option C

**Decision:** Emit an explicit revision event linked to the original assessment. Consumers declare whether to recompute, supersede, or retain their historical decision. Never erase an executed action or silently reverse it.
- Revision events must carry stable identity and lineage to the original assessment and the correction/replay that caused the revision.
- Each consumer must declare its revision policy: recompute current derived state, emit a superseding result, or retain its prior decision as historical-only.
- Any trading action already taken remains an immutable auditable fact. A revision may affect current eligibility or future decisions only under explicit consumer rules; it must not pretend to undo an order or fill.
- **Open implementation parameters:** consumer policy defaults, propagation depth, handling of in-flight orders/ideas, idempotency keys, and reconciliation behavior when a consumer misses or receives duplicate revision events.

### Submitted Q4.27 — Replayed versus original live results — Option C

**Decision:** Preserve immutable live history and tag replay outputs explicitly. Current projections may update only under defined replay rules, with provenance, timestamps, and revision links.
- Distinguish original live observations/results from replayed or corrected results in event identity, metadata, logs, and queryable projections.
- Preserve both event time and processing/receive time when available; record replay execution time and the source/rule versions used.
- Update a current-state projection only according to an explicit projection/replay policy, with the prior projection and lineage retained.
- **Open implementation parameters:** replay result identity scheme, projection update policy, conflict resolution, retention, and query semantics for original versus corrected views.

These decisions extend the reviewed rules without replacing prior decisions. All unvalidated numerical and behavioral parameters remain explicitly open. Local document only; no GitHub changes made.

## Review decisions submitted — replay reliability (submitted Q4.28–Q4.30)

**Numbering/cross-reference note:** These identifiers follow the interactive review batch and may overlap with identifiers used elsewhere in the original document. Preserve earlier entries; reconcile the canonical question-number crosswalk in a later cleanup pass rather than overwriting prior decisions.

### Submitted Q4.28 — Replay failure handling — Option C

**Decision:** Track replay progress and checkpoints. Preserve completed work as a non-authoritative partial result, then resume or restart safely. Publish updated current projections only after required validation succeeds.
- Give each replay run a stable identity and explicit states such as queued, running, partially completed, failed, validated, and published.
- Persist checkpoints sufficient to resume safely where feasible; retries must be idempotent and must not duplicate events or corrupt projections.
- Partial output remains non-authoritative and must be clearly marked. It must not be published into authoritative current projections before validation succeeds.
- On failure, record the failure point, affected range, completed checkpoint, and retry/restart decision.
- **Open implementation parameters:** checkpoint granularity, checkpoint storage/retention, retry limits/backoff, resume eligibility, validation requirements, and atomic publication strategy. These remain unresolved pending implementation and testing.

### Submitted Q4.29 — Reproducibility — Option C

**Decision:** Capture source-data snapshot/version, event ordering, configuration, rule/code version, and other required dependencies. Aim for deterministic output from identical inputs and record any divergence.
- Record the data/feed snapshot or reproducible source references, event ordering/sequence information, configuration and policy versions, code/build version, and relevant dependency versions.
- Identical declared inputs should produce deterministic outputs wherever practical; nondeterministic dependencies must be identified rather than hidden.
- Compare replay results against expected or prior results when appropriate and record any divergence with enough context to investigate it.
- **Open implementation parameters:** exact reproducibility manifest, data snapshot mechanism, dependency capture scope, deterministic ordering rules, comparison tolerances, and treatment of unavoidable nondeterminism.

### Submitted Q4.30 — Replay/live isolation — Option C

**Decision:** Replay is isolated by default. A validated result may affect live projections only through an explicit, audited promotion/reconciliation policy; replay cannot retroactively erase orders, fills, or actions.
- Run replay in a logically isolated context with separate outputs and status until validation and promotion criteria pass.
- Promotion must be explicit, auditable, and governed by defined eligibility checks; failed or partial replay cannot update authoritative live projections.
- Reconciliation may update current derived state only through documented rules and must preserve original observations, decisions, orders, fills, and action history.
- Replay must not directly submit, cancel, or modify live trading orders merely because historical derived state changed.
- **Open implementation parameters:** promotion authority, validation gates, projection reconciliation semantics, operational safeguards, and treatment of active ideas/in-flight orders when a promoted correction affects current context.

These decisions extend the reviewed rules without replacing prior decisions. Unvalidated numerical and behavioral parameters remain explicitly open. Local document only; no GitHub changes made.

## Review decisions submitted — replay coordination and validation (recommended defaults accepted; Q4.31–Q4.33)

**Process direction from user:** For the replay/event topic, accept the previously recommended options rather than extending the design indefinitely. Prefer straightforward, testable defaults; implement the necessary baseline and use testing to expose issues for later correction. Do not continue adding complexity without a concrete dependency. Keep all earlier decisions and record these as the selected defaults.

### Q4.31 — Overlapping replay jobs — Option C (recommended default accepted)

**Decision:** Allow safe concurrency for independent scopes; coordinate overlapping scopes using version checks and explicit conflict handling. A stale replay must not overwrite a newer validated result.

**Practical baseline:** Use scope/version checks and reject or mark stale a run whose inputs are superseded. Do not build a more elaborate scheduler unless tests or operational needs justify it.

### Q4.32 — Replay cancellation/obsolescence — Option C (recommended default accepted)

**Decision:** Support cancellation and obsolescence states. Stop safely at checkpoints, retain partial output for diagnostics as non-authoritative, and prevent obsolete runs from publishing.

**Practical baseline:** Implement a simple cancellation flag/checkpoint check and a final freshness check before publishing. Advanced resume/retry orchestration is deferred until needed.

### Q4.33 — Replay promotion validation — Option C (recommended default accepted)

**Decision:** Require a straightforward configurable validation contract covering successful completion, input/version integrity, basic event/state invariants, consistency checks, and any truly necessary model-specific checks. Promotion is rejected if a required check fails.

**Practical baseline:** Start with a small explicit set of checks and add checks in response to concrete defects found in tests. Do not build a broad generic validation framework prematurely.

### Scope and implementation principle

For replay/event handling, prioritize correctness, idempotency, auditable state transitions, and isolation from live trading. Use the simplest implementation that enforces those invariants. Defer speculative complexity and refine only when tests, observed failures, or a real dependency demonstrate the need. Numerical values and implementation-specific settings remain open until testing provides evidence. No GitHub changes made.

## Next review batch — Volume profile and LVN/HVN ranking

Continue with the existing Flow_v2 volume-profile direction. The existing decisions already call for weekly, daily, and current-session anchors, and for LVN ranking features including width, depth, relative volume, surrounding HVN volume, and node separation. Keep parameters configurable and avoid over-designing before tests.

### Q5.11 — How should the LVN/HVN ranking engine combine its features?

**A —** Use a single fixed weighted score for all contexts.

**B —** Use explicit rule-based ranking only, without a numeric score.

**C — Recommended:** Support a simple configurable weighted score and explicit rule filters. Keep feature contributions visible so results can be evaluated and tuned in research. Start with a basic implementation rather than a generalized ranking framework.

### Q5.12 — How should profile anchors be compared?

**A —** Always prefer the current-session profile over daily and weekly profiles.

**B —** Always prefer the weekly profile because it contains more data.

**C — Recommended:** Evaluate weekly, daily, and current-session profiles independently, preserve each anchor's identity and features, and let the consuming model decide how to combine or prioritize them. Avoid hard-coded universal precedence.

### Q5.13 — What should happen when LVN candidates from different anchors overlap or conflict?

**A —** Merge overlapping candidates into one node and discard their separate anchor identities.

**B —** Keep every candidate separate and never link overlapping candidates.

**C — Recommended:** Preserve candidates and their anchor identities, and optionally link overlapping candidates as a shared price-area/context. Let downstream rules decide whether confluence matters; do not count the same underlying area as independent confirmation without explicit justification.

**User response format:** `Q5.11 C, Q5.12 C, Q5.13 C` (or choose different options). After submission, record the answers and automatically present the next batch in the same response. Local file only; no GitHub changes.

## Review decisions submitted — Volume profile and LVN/HVN ranking (Q5.11–Q5.13)

### Q5.11 — LVN/HVN feature ranking — Option C

**Decision:** Use a configurable weighted score alongside explicit rule filters. Keep feature contributions visible and start with a simple implementation.
- Retain width, depth, relative volume, surrounding HVN volume, and node separation as separately observable features.
- Keep score weights and rule filters configurable for research; avoid introducing a generalized framework before the baseline is tested.
- Do not treat correlated features as independent evidence without an explicit rule.
- **Open parameters:** feature definitions and normalization, initial weights, score range, filter thresholds, missing-feature handling, and how ties are resolved. Numerical values remain provisional pending testing.

### Q5.12 — Profile-anchor comparison — Option C

**Decision:** Evaluate weekly, daily, and current-session profiles independently. Preserve each anchor's identity and features; let the consuming model decide how to combine or prioritize them.
- Do not impose a universal precedence order across anchors.
- Keep anchor-specific candidate metrics visible so model behavior can be inspected in research.
- **Open parameters:** exact profile construction/rollover rules, lookback settings, and downstream combination rules.

### Q5.13 — Overlapping LVN candidates — Option C

**Decision:** Preserve candidates and their anchor identities, while optionally linking overlapping candidates into a shared price-area/context. Downstream rules decide whether confluence matters.
- Do not count overlapping candidates as independent confirmation solely because they originate from different anchors.
- Preserve original candidate records even when a shared-area link is created.
- **Open parameters:** overlap tolerance, shared-area linking rules, and how linked candidates affect downstream scores. Keep these configurable and validate with test cases.

Implementation direction: favor the simplest testable score, explicit feature outputs, and anchor-aware candidates. Defer generalized abstractions until testing demonstrates a need. Local document only; no GitHub changes made.

## Review decisions submitted — LVN/HVN ranking behavior (Q5.14–Q5.16)

### Q5.14 — Feature normalization — Option C

**Decision:** Use simple configurable normalization appropriate to each feature. Start with basic transformations and improve only if tests show a concrete need.
- Keep raw feature values alongside normalized values so ranking behavior remains inspectable.
- **Open parameters:** the specific transformation for each feature, reference/lookback window where relevant, clipping/outlier treatment, and initial ranges. Set numerical values during implementation and testing rather than inventing universal values now.

### Q5.15 — Ranking stability — Option B

**Decision:** Smooth the score using a configurable moving average.
- Keep the underlying raw score available for debugging and research, even though the selected stability approach is a moving average.
- **Open parameters:** averaging method, window length, initialization/warm-up behavior, and handling of missing observations. Start with a simple moving average and test whether it introduces unacceptable lag; do not add hysteresis unless evidence later justifies it.

### Q5.16 — Candidate eligibility — Option C

**Decision:** Apply a small set of explicit validity and minimum-quality filters. Optional missing features are handled according to configuration, and candidate exclusion reasons are recorded.
- Avoid requiring every optional ranking feature to be present.
- **Open parameters:** mandatory validity checks, minimum-quality thresholds, and optional-feature handling. Keep the initial filter set small and test-driven.

Implementation direction: preserve raw inputs, use simple configurable transformations, smooth the score with a basic moving average, and make candidate exclusions explainable. No GitHub changes made.

## Next review batch — LVN/HVN ranking output and updates

Keep this batch focused on the minimum behavior needed by downstream models.

### Q5.17 — What should the ranking engine return for each candidate?

**A —** Return only a final rank/score.

**B —** Return the score and a short list of features that contributed most.

**C — Recommended:** Return candidate identity/anchor, raw and normalized feature values, final raw and smoothed scores, rank, eligibility status, and exclusion reasons where applicable. Keep the output structure simple; don’t add a generalized explanation framework.

### Q5.18 — How should ties in candidate scores be handled?

**A —** Allow tied ranks; downstream models decide how to handle them.

**B —** Always break ties using one fixed feature, such as proximity to current price.

**C — Recommended:** Preserve the equal score and use a configurable, deterministic tie-break rule only when an ordered list is required. Keep the tie-break rule simple and expose it in output.

### Q5.19 — When should a candidate's ranking be recalculated?

**A —** Recalculate all candidates on every market-data update.

**B —** Recalculate only at the end of each footprint bar.

**C — Recommended:** Recalculate when relevant profile/features change, with an optional configurable throttling/update cadence if profiling shows it is needed. Ensure consumers can tell when a score was last updated. Start simple and optimize only if tests or profiling show a problem.

**User response format:** `Q5.17 C, Q5.18 C, Q5.19 C` (or choose different options). After submission, record the answers and automatically present the next batch in the same response. Local file only; no GitHub changes.

## Review decisions submitted — LVN/HVN ranking output and updates (Q5.17–Q5.19)

### Q5.17 — Ranking output — Option C

**Decision:** Return candidate identity and profile anchor, raw and normalized feature values, raw and smoothed scores, rank, eligibility status, and exclusion reason where applicable.
- Keep the output simple and directly useful for debugging and research; no generalized explanation framework is required.
- **Open parameters:** exact field names/types and which optional fields are needed by downstream consumers.

### Q5.18 — Equal candidate scores — Option C

**Decision:** Preserve equal scores. Apply a configurable deterministic tie-break only when an ordered list is required.
- Keep the tie-break simple and expose which rule was used.
- **Open parameters:** default tie-break rule and stable ordering for candidates that remain equal after tie-breaking.

### Q5.19 — Ranking recalculation — Option C

**Decision:** Recalculate when relevant profile features change. Add configurable throttling only if profiling demonstrates it is needed, and expose the last-update time.
- Begin with event-driven updates from changed features rather than recalculating all candidates on every market-data event.
- **Open parameters:** exact feature-change triggers and any update/throttling interval, to be determined through implementation and profiling.

**Implementation direction:** Keep the ranking output inspectable, use deterministic tie handling only where required, and avoid premature optimization. Preserve all earlier decisions. Local document only; no GitHub changes made.

## Review decisions submitted — LVN/HVN ranking output and updates (Q5.17–Q5.19)

### Q5.17 — Ranking output — Option C

**Decision:** Return candidate identity and profile anchor, raw and normalized feature values, raw and smoothed scores, rank, eligibility status, and exclusion reason where applicable.
- Keep the output structure simple and useful for debugging/research.
- **Open parameters:** exact field names/types and optional fields needed by downstream consumers.

### Q5.18 — Equal candidate scores — Option C

**Decision:** Preserve equal scores. Use a configurable deterministic tie-break only when an ordered list is required.
- **Open parameters:** default tie-break rule and stable ordering when candidates remain equal.

### Q5.19 — Ranking recalculation — Option C

**Decision:** Recalculate when relevant profile features change. Add throttling only if profiling demonstrates a need, and expose last-update time.
- **Open parameters:** exact feature-change triggers and any update cadence.

## Review decisions submitted — Profile location rules (Q5.20–Q5.22)

### Q5.20 — Profile anchor rollover — Option C

**Decision:** Use exchange-calendar-aware session boundaries for session profiles, with explicit configurable boundaries for daily and weekly profiles where needed.
- **Open parameters:** exact CME session templates, holiday/early-close handling, and daily/weekly anchor boundary configuration. Verify against test dates rather than over-designing now.

### Q5.21 — LVN candidate validity — Option C

**Decision:** Apply a small set of configurable validity checks; each entry model decides whether a valid candidate fits its location requirements. Ranking alone does not make a candidate tradeable.
- **Open parameters:** minimal validity checks and quality thresholds; set initial values during implementation and test them.

### Q5.22 — Timezone and session boundaries — Option C

**Decision:** Use the exchange timezone and calendar as the authoritative market-time source. Convert timestamps explicitly, and configure strategy-specific session windows separately.
- **Open parameters:** chosen timezone/calendar library or data source and strategy-specific session windows. Test daylight-saving transitions and session boundaries.

Implementation direction: keep profile handling exchange-aware and testable, but do not build a generalized calendar framework unless the implementation needs it. Local document only; no GitHub changes made.

## Review decisions submitted — Volume-profile construction (Q5.23–Q5.28)

### Q5.23 — Profile data source — Option C

**Decision:** Use trade-level data when available. Provide an explicit bar-based fallback and record which source produced each profile.
- Do not silently treat bar-based and trade-based profiles as equivalent; expose source/quality so downstream research can distinguish them.
- **Open parameters:** data-source quality labels and the minimum data requirements for each mode. Validate the fallback against sample sessions.

### Q5.24 — Profile price-row grouping — Option C

**Decision:** Make price-row grouping configurable by instrument/profile. Retain raw price-level data when available and start with a simple default suitable for Gold.
- Avoid a complex universal grouping framework; add only the configuration needed for actual instruments and profile types.
- **Open parameters:** initial Gold row grouping and any per-profile overrides. Select simple defaults and validate with representative data.

### Q5.25 — Profile update method — Option C

**Decision:** Update profiles incrementally during normal operation, with a straightforward rebuild/reconciliation path to check correctness.
- Keep incremental updates simple; use rebuild comparisons in tests to catch drift or update bugs.
- **Open parameters:** rebuild triggers/frequency and acceptable reconciliation tolerance. Determine from correctness tests and profiling rather than pre-optimizing.

### Q5.26 — LVN detection — Option C

**Decision:** Start with a simple local-minimum rule, with configurable relative-volume thresholds and neighborhood size; validate it against sample profiles.
- Preserve the underlying row volumes so detection can be inspected and tuned without changing the data model.
- **Open parameters:** relative-volume threshold, neighborhood size, and edge-row handling. Keep defaults provisional and tune through tests.

### Q5.27 — LVN boundaries — Option C

**Decision:** Group contiguous qualifying rows using configurable criteria. Preserve the low-volume point, node width, and full price range for each detected LVN.
- Keep the first implementation to contiguous qualifying rows; do not introduce more elaborate segmentation unless test cases show it is needed.
- **Open parameters:** qualifying-row threshold, rules for bridging a non-qualifying row, and minimum/maximum node width if needed.

### Q5.28 — Incomplete or unreliable profile data — Option C

**Decision:** Expose profile data quality explicitly. Allow display and research use where appropriate, but prevent profiles with invalid or insufficient required inputs from qualifying for trading.
- Missing data must not silently become zero-volume evidence. Record why a profile is degraded or ineligible.
- **Open parameters:** required-input checks, quality states, and eligibility thresholds. Begin with a small set of clear checks and expand only if testing reveals a gap.

**Implementation direction:** Prefer trade-level data when available, use a clearly labeled fallback, incrementally maintain profiles with rebuild checks, and start with simple configurable LVN detection. Leave thresholds provisional for test-driven calibration. All previous decisions are preserved. Local file only; no GitHub changes made.

## Next review batch — Volume-profile levels and value area (Q5.29–Q5.34)

Keep the first implementation conventional and easy to validate against known profiles. Numeric settings remain provisional until tested.

### Q5.29 — How should the Point of Control (POC) be selected?

**A —** Choose the single price row with the greatest volume; resolve ties using a fixed price-side rule.

**B —** Choose the greatest-volume row, but leave ties unresolved and return multiple POC candidates.

**C — Recommended:** Choose the greatest-volume row and preserve ties explicitly; use a simple deterministic tie-break only when a single POC is required by a consumer. Retain the tied candidates for research/debugging.

### Q5.30 — How should value area be calculated?

**A —** Use a fixed percentage of total profile volume around the POC, expanding to adjacent rows by volume.

**B —** Use a fixed price-distance around the POC.

**C — Recommended:** Use a configurable target percentage of total profile volume, expanding outward from the POC using a simple documented rule. Keep the target percentage provisional and validate against reference profiles.

### Q5.31 — How should value-area expansion handle equal-volume rows on either side?

**A —** Always choose the lower-price row first.

**B —** Always choose the higher-price row first.

**C — Recommended:** Use a deterministic, configurable tie rule and record it; start with one simple default. Keep the result reproducible rather than adding complex tie logic.

### Q5.32 — How should profile totals and row volumes be reconciled?

**A —** Trust incremental totals and do not check them against row data.

**B —** Recompute every total on every update.

**C — Recommended:** Maintain totals incrementally, and verify them against row-level sums during rebuilds and tests. Add runtime reconciliation only if correctness or profiling warrants it.

### Q5.33 — What should happen when a profile has no usable volume or invalid price rows?

**A —** Produce a profile anyway, using zeros for missing or invalid values.

**B —** Discard the profile without retaining a reason.

**C — Recommended:** Mark the profile invalid/degraded with a specific reason, prevent it from qualifying for trading when required inputs fail, and retain enough diagnostics for research. Do not silently replace missing values with zero.

### Q5.34 — How should profile calculations be validated initially?

**A —** Validate only that the code runs without exceptions.

**B —** Add a large generalized property-testing framework before using the profile engine.

**C — Recommended:** Build a small set of hand-checkable fixtures covering POC, value area, ties, incremental updates versus rebuilds, missing data, and LVN boundaries. Expand tests only when concrete failures or new requirements justify it.

**User response format:** `Q5.29 C, Q5.30 C, Q5.31 C, Q5.32 C, Q5.33 C, Q5.34 C` (or choose different options). After submission, record the answers and automatically present the next batch of six. Local file only; no GitHub changes.

## Q5.29–Q5.34 — Volume-profile levels and validation

### Q5.29 — Point of Control (POC) selection
**Decision: Option C.** Preserve tied highest-volume rows as tied candidates. Apply a deterministic tie-break only when a consumer explicitly requires a single POC.

### Q5.30 — Value-area calculation
**Decision: Option C.** Use a configurable target volume percentage and a documented outward-expansion rule starting from the POC. Keep the target percentage provisional and calibrate it with tests.

### Q5.31 — Equal-volume rows during value-area expansion
**Decision: Option C.** Use one deterministic, configurable tie rule so results are reproducible.

### Q5.32 — Profile-total reconciliation
**Decision: Option C.** Maintain totals incrementally during normal updates; verify totals against row sums during rebuilds and tests.

### Q5.33 — Invalid or unusable profile data
**Decision: Option C.** Mark invalid/degraded profiles with an explicit reason. Prevent trading eligibility when required inputs fail, while retaining quality information for diagnostics and research.

### Q5.34 — Initial calculation validation
**Decision: Option C.** Add small, hand-checkable fixtures covering POC, value area, ties, rebuild consistency, missing/invalid data, and LVN boundaries. Avoid a large testing framework before the basic implementation proves useful.

**Open parameters to settle through tests:** target value-area percentage; expansion/tie behavior where it affects boundaries; any minimum data-quality requirements; and instrument/profile-specific row-grouping settings. Keep these configurable where needed and do not prematurely optimize.

## Original-source review decisions — Q1.1–Q1.3 and Q6.1–Q6.3

These decisions are from the original `orderFlowRulesRedefinition.md` only. Extended questions remain deferred and are not part of the active review queue.

### Q1.1 — Bar type — Option D
**Decision:** Make the bar type configurable. Start with 500-tick bars as the baseline and test alternatives later.
- Keep closed-bar evaluation as the reproducible starting point unless separately changed by the original trigger-timing questions.
- **Open parameters:** which alternative bar types/counts to test and what evidence would justify changing the baseline.

### Q1.2 — Fixed or activity-scaled tick count — Option C
**Decision:** Keep 500 ticks as the baseline and make alternative counts configurable for testing; do not automatically adapt the count before tests justify it.
- **Open parameters:** candidate alternative tick counts and the activity window/metric, if adaptive behavior is tested later.

### Q1.3 — Higher-timeframe structure — Option D
**Decision:** Start with profile-based locations. Add objectively defined 1-hour/30-minute structure zones only if testing shows value.
- **Open parameters:** objective structure-zone definition and measurable criteria for deciding whether adding those zones improves results.

### Q6.1 — Initial entry models — Option C
**Decision:** Include all seven entry models (M1–M7) in the initial model list.
- Treat each model as independently testable/configurable; inclusion in the list does not establish that every model is approved for live trading.
- **Open parameters:** validation criteria and rollout gates before any model is enabled for actual order submission.

### Q6.2 — Long/short direction — Option C
**Decision:** Implement both long and short directions, and validate their performance separately.
- **Open parameters:** minimum evidence/sample size and performance criteria for judging each direction; set these during the original validation questions rather than inventing values now.

### Q6.3 — Strategy organization — Option C
**Decision:** Use a shared strategy framework with independently configurable model modules.
- Keep shared mechanics common while allowing each model to declare its own location, reads, and trigger conditions.
- **Open parameters:** only implementation-level module interfaces that become necessary during coding; avoid speculative abstraction.

**Scope note:** Continue presenting batches only from unresolved questions in the original `orderFlowRulesRedefinition.md`. All extended questions, including the extended Q5.23–Q5.34 profile-construction series, are deferred until explicitly requested. Local file only; GitHub was not modified.


## Original-source review decisions — Q2.1–Q2.5 and Q3.1

These are the user's explicit decisions against the original `orderFlowRulesRedefinition.md` question IDs. Keep these IDs distinct from older extended-review sections where Q2 numbering was used differently.

### Q2.1 — Diagonal-imbalance threshold — Option C
Use 300% as the baseline and test 200%, 400%, and 500% as alternatives. Keep the threshold configurable. **Open numerical work:** validate thresholds on recorded/forward data; none is a proven universal setting.

### Q2.2 — Footprint row grouping — Option C
Start with one default grouping and make 6-, 7-, and 10-tick rows configurable test candidates. **Open numerical work:** select the default after checking Gold footprint examples and signal quality.

### Q2.3 — Minimum volume for an imbalance — Option C
Keep a configurable minimum-volume filter separate from the imbalance ratio; retain the raw ratio and compared volumes so low-volume signals can be inspected. **Open numerical work:** test the minimum and do not treat it as finalized.

### Q2.4 — Finished-auction definition — Option A
Use strictly zero volume on the relevant side at the bar extreme for the initial baseline; do not apply a 1–2 lot near-zero tolerance initially. **Still open:** verify bid/ask side mapping against the actual feed and known examples; the original question also asks whether an opposite-side minimum volume is needed, which was not specified by Option A and remains open.

### Q2.5 — Finished/unfinished auction role — Option C
Treat finished auction as contextual confirmation, not a standalone trigger. A model may require it, but it is not universally mandatory.

### Q3.1 — Big-trade threshold — Option C
Start at 30 contracts per individual print as a configurable Gold baseline. Test fixed versus activity-adaptive thresholds before deciding whether to adapt. **Open numerical work:** threshold calibration and the adaptive formula remain unresolved; 30 contracts is provisional.

**Original-source decision-table update:** Q2.1, Q2.2, Q2.3, and Q2.5 are resolved at the policy level; Q2.4 is partially resolved pending feed-side mapping and the opposite-side minimum-volume question. Q3.1 is partially resolved because the starting threshold is selected but calibration/adaptation remain open. Extended questions remain deferred; GitHub was not modified.

## Original-source review decisions — Q3.2–Q3.4 and Q4.1–Q4.3

These decisions apply only to the matching IDs in the original `orderFlowRulesRedefinition.md`. Extended questions remain deferred.

### Q3.2 — Measure price outcome after a big print — Option A
Measure progress in ticks within a configurable time window. **Open numerical parameters:** the required tick progress and the time-window duration. Do not set final values until they are tested on Gold data.

### Q3.3 — Repeated big prints on the same side — Option C
Preserve individual prints and also support configurable grouping of repeated same-side prints, allowing both interpretations to be tested. **Open numerical parameters:** grouping time window, price proximity, and any threshold for treating prints as a cluster.

### Q3.4 — Big-print proximity to a zone — Option C
Make proximity configurable, with both tick-distance and price-range methods available for testing. **Open numerical parameters:** acceptable distance and the precise zone reference; calibrate during testing.

### Q4.1 — Candidate definitions for absorption, exhaustion, and delta flip — Option C
Use the original document's candidate definitions as initial hypotheses, preserve their raw evidence, and validate the definitions and parameters through testing. **Open parameters:** X for relative delta magnitude, k for exhaustion lookback, N for delta-flip window, and exact price-outcome conditions.

### Q4.2 — Measure whether price failed to move for absorption — Option C
Keep the measurement window and threshold configurable; compare tick/time-based displacement with bar/extreme-based measures during testing. **Open numerical parameters:** displacement threshold, elapsed-time window, and/or number of bars.

### Q4.3 — Single-bar or multi-bar absorption — Option C
Support single-bar and multi-bar evidence as configurable research modes, then validate whether combining bars improves the signal. **Open numerical parameters:** multi-bar lookback and any repeated-evidence requirement.

### Original-source decision-table status update
- Q3.2: Policy resolved (tick progress within a configurable time window); thresholds/window remain open.
- Q3.3: Policy resolved (preserve individual prints and configurable grouping); grouping parameters remain open.
- Q3.4: Policy resolved (configurable proximity using tick-distance and price-range methods); distance remains open.
- Q4.1: Candidate-definition approach resolved; exact definitions and parameters remain hypotheses to validate.
- Q4.2: Measurement approach remains configurable for comparative testing; thresholds/windows remain open.
- Q4.3: Single-bar and multi-bar modes supported for testing; lookback remains open.

Scope remains restricted to unresolved questions in the original `orderFlowRulesRedefinition.md`. All extended questions are deferred. GitHub was not modified.

## Original-source review decisions — Q4.4–Q4.5 and Q5.1–Q5.4

These decisions are from the original `orderFlowRulesRedefinition.md` only. Extended questions remain deferred.

### Q4.4 — Delta measure for delta-flip detection — Option D
Make bar delta, cumulative delta, and windowed Delta Profile configurable research alternatives; compare them before selecting a default. **Open parameters:** the candidate window lengths and criteria for selecting a default remain for testing.

### Q4.5 — Which order-flow reads are needed — Option C
Keep absorption, exhaustion, delta flip, and failed-aggression evidence available; preserve each read's individual evidence and test whether each adds value. Do not assume correlated reads are independent. **Open work:** test incremental signal value and decide whether any read can be deferred based on results.

### Q5.1 — Initial location sources — Option D
Start with profile-based locations. VWAP may be used as configurable context/target. Add objectively defined structure zones only when they are defined reproducibly and testing justifies them. **Open work:** objective structure-zone rules and evidence for adding them.

### Q5.2 — Profile anchor rule — Option C
Use explicit session-dependent profile-anchor rules, configurable by session. Validate the actual session boundaries before relying on them. **Open parameters:** exact session templates and boundary times, including the sessions used for each profile.

### Q5.3 — What makes an LVN tradeable — Option C
Keep depth, width, and age as separate configurable features/filters. Begin with a simple implementation and test which criteria add value. **Open numerical parameters:** relative-depth threshold, minimum width, minimum age, and any normalization used.

### Q5.4 — Value-area edge as a location — Option C
Make the requirement for a standalone value-area edge versus additional LVN/zone confluence configurable by entry model; compare outcomes. **Open work:** model-specific defaults and evidence to determine whether standalone edges are sufficient.

### Original-source decision-table status update
- Q4.4: Resolved at policy level; candidate delta measures and window lengths remain for testing.
- Q4.5: Resolved at policy level; incremental value of individual reads remains to be tested.
- Q5.1: Resolved at policy level; objective structure-zone definition and validation remain open.
- Q5.2: Resolved at policy level; concrete session anchors/boundaries remain open.
- Q5.3: Resolved at policy level; LVN qualification thresholds remain open.
- Q5.4: Resolved at policy level; model-specific settings and validation remain open.

Scope remains restricted to unresolved questions in the original `orderFlowRulesRedefinition.md`. All extended questions are deferred. GitHub was not modified.

## Original-source review decisions — Q5.5–Q5.7 and Q7.1–Q7.3

These decisions apply only to the original `orderFlowRulesRedefinition.md`. Extended questions remain deferred.

### Q5.5 — Profile-shape regime filter — Option C
Calculate and record profile-shape classifications (D, P, b, B) for research first; use them as trade filters only if testing justifies it.
- **Open work:** define reproducible classification rules and validate whether shape adds useful information before enabling it as a filter.

### Q5.6 — Trading session scope — Option C
Make session windows configurable; start with a clearly defined baseline and compare session-specific results before restricting trading.
- **Open parameters:** baseline session hours in IST and CT, session calendar, and minimum evidence required before restricting a session.

### Q5.7 — Maximum distance from a location — Option C
Make maximum distance configurable, preserve the measured distance, and calibrate it through testing.
- **Open numerical parameters:** distance threshold and any distinction by location type or entry model.

### Q7.1 — Trigger-bar timing — Option B
Allow entries before the trigger bar closes when price is at the zone and a defined set of conditions has already printed.
- **Open implementation details:** define the exact intrabar conditions, ensure signals use only information available at that moment (no look-ahead), and test against the closed-bar baseline. The specific conditions and timing thresholds remain unresolved.

### Q7.2 — Entry order placement — Option C
Make market-on-trigger and limit-on-retest separate configurable entry modes and test them independently.
- **Open parameters:** exact market submission timing, retest price/offset, and order-validity window.

### Q7.3 — Triggered setup validity — Option C
Use a configurable validity window in bars or seconds, with explicit strategy invalidation taking precedence; calibrate the window in testing.
- **Open numerical parameters:** expiry duration and whether the initial default is expressed in bars or seconds.

### Original-source decision-table status update
- Q5.5: Policy resolved; profile-shape algorithm and evidence for use as a filter remain open.
- Q5.6: Policy resolved; baseline session schedule and concrete session boundaries remain open.
- Q5.7: Policy resolved; maximum-distance threshold remains open.
- Q7.1: Decision recorded as pre-close entries allowed under defined conditions; exact intrabar conditions and safeguards remain open.
- Q7.2: Policy resolved; market-on-trigger and limit-on-retest remain independently testable modes, with execution parameters open.
- Q7.3: Policy resolved; setup validity is configurable and subordinate to strategy invalidation; duration remains open.

Scope remains restricted to unresolved questions in the original `orderFlowRulesRedefinition.md`. All extended questions are deferred. GitHub was not modified.

## Original-source review decisions — Q8.1–Q8.3 and Q9.1–Q9.3

These decisions apply only to the matching question IDs in the original `orderFlowRulesRedefinition.md`. Extended questions with overlapping IDs remain separate and are deferred unless explicitly requested.

### Q8.1 — Minimum confirmation requirement — Option A
Require location + trigger bar + at least one order-flow read. **Open implementation detail:** identify which read qualifies for each enabled model; Q8.2 allows model-specific mandatory reads. The minimum is one read, not a universal requirement that multiple correlated reads agree.

### Q8.2 — Model-specific mandatory reads — Option C
Define model-specific mandatory conditions and optional supporting reads, preserving the evidence for each read. **Open work:** specify the exact mandatory conditions for each entry model before implementation.

### Q8.3 — Combining reads across bars — Option C
Allow a configurable cross-bar window and record each read's timestamp. **Open numerical parameter:** window length in bars or time; compare reasonable candidates during testing.

### Q9.1 — Stop-loss reference — Option D
Choose the structural reference that invalidates the specific trade thesis. Make the reference model-specific and keep the buffer separate. **Open work:** map each enabled entry model to its valid structural invalidation reference.

### Q9.2 — Stop buffer — Option D
Make the buffer method configurable: fixed ticks, a fraction of zone width, or ATR-based. Compare simple candidates in testing. **Open numerical parameters:** buffer size, zone-width fraction, ATR period/multiplier, and any model-specific defaults.

### Q9.3 — Maximum stop distance — Option C
If the structural stop exceeds the configurable maximum, use another structurally valid stop candidate only if one exists and fits the risk limit; otherwise skip the trade. Never force an illogical stop. **Open numerical parameter:** maximum stop distance and its unit; calibrate for Gold during testing.

### Original-source decision-table status update
- Q8.1: Resolved — location + trigger bar + at least one read.
- Q8.2: Policy resolved — model-specific mandatory conditions and optional supporting reads; exact conditions remain open.
- Q8.3: Policy resolved — configurable cross-bar window; window length remains open.
- Q9.1: Policy resolved — model-specific structural invalidation reference; model mappings remain open.
- Q9.2: Policy resolved — configurable buffer method; numerical settings remain open.
- Q9.3: Policy resolved — skip if no structurally valid stop fits the maximum; maximum distance remains open.

Scope remains restricted to unresolved questions in the original `orderFlowRulesRedefinition.md`. All extended questions are deferred. GitHub was not modified.

## Original-source review decisions — Q9.4, Q10.1–Q10.4 and Q11.1

These decisions apply only to the matching questions in the original `orderFlowRulesRedefinition.md`. Extended questions remain deferred. GitHub was not modified.

### Q9.4 — Time-based stop — Option A
Do not use a time-based stop in v1. Exit according to the structural trade thesis and the normal position-management rules. Avoid adding a time-based exit before the basic structural exit behavior is validated.
- **Open work:** clarify and test the normal position-management rules and structural invalidation behavior. No time-stop duration is needed for v1 unless a later test justifies revisiting this decision.

### Q10.1 — Profit-target selection — Option C
Use a structural target, subject to a configurable minimum reward-to-risk ratio. Skip the trade if no valid structural target satisfies the minimum rather than substituting an arbitrary target.
- **Open numerical parameter:** minimum acceptable reward-to-risk ratio, including whether it varies by model or session.

### Q10.2 — Eligible structural targets — Option C
Allow model-specific target eligibility from VWAP, POC, the opposite value-area edge, and HVNs. Reject a target if an intervening POC/HVN blocks the path or if the target fails the minimum reward-to-risk requirement.
- **Open work:** define eligible target types per model, how to identify an intervening POC/HVN objectively, how target selection works when several levels qualify, and how the target is recalculated if profile levels change.

### Q10.3 — Partial profits and breakeven moves — Option A
Defer partial profit-taking and moving the stop to breakeven in v1. Validate the basic entry, structural stop, and target behavior first.
- **Open work:** revisit partial exits and breakeven rules only after baseline results are available. No partial-size or trigger thresholds are required for the initial version.

### Q10.4 — Re-entry after stop/breakeven — Option C
Make re-entry limits configurable by zone and session. Start with one attempt and test alternatives rather than assuming repeated entries are beneficial.
- **Open numerical parameters:** default maximum attempts per zone and per session; define whether a breakeven exit counts as an attempt; define any cooldown after an exit and whether a materially new setup resets the attempt count.

### Q11.1 — Hard no-trade filters in v1 — Option C
Hard-block trades with no valid location, a POC/HVN between entry and target, chasing, or an entry based only on a finished auction. Add other objectively defined filters—such as profile shape—only if validation supports them. Defer heatmap/gamma filters until those data layers are in scope.
- **Open implementation details:** define “chasing” reproducibly, formalize the POC/HVN-between-entry-and-target test, and ensure “finished auction alone” is not mistaken for a mandatory universal read. Profile-shape classification remains research-only until its algorithm and value are validated.

### Original-source decision-table status update
- Q9.4: Resolved — no time-based stop in v1; normal structural and position-management exits apply.
- Q10.1: Resolved at policy level — structural target plus configurable minimum reward-to-risk; minimum ratio remains open.
- Q10.2: Resolved at policy level — model-specific eligible structural targets, with intervening POC/HVN and minimum reward-to-risk rejection; model mappings and level-selection details remain open.
- Q10.3: Resolved — defer partial profits and breakeven moves in v1.
- Q10.4: Resolved at policy level — configurable re-entry limits, starting with one attempt; attempt counting and limits remain open.
- Q11.1: Resolved at policy level — specified hard filters apply; precise definitions and validated optional filters remain open.

Scope remains restricted to unresolved questions in the original `orderFlowRulesRedefinition.md`. All extended questions are deferred.


## Original-source review decisions — Q11.2–Q11.4, Q12.1–Q12.2 and Q13.1

These six decisions apply only to the matching question IDs in the original `orderFlowRulesRedefinition.md`. Extended questions remain deferred. GitHub was not modified.

### Q11.2 — Define “falling knife / strong momentum” — Option B
Use price movement and delta over a configurable window to identify strong momentum; tune the thresholds using tests rather than hard-coding a universal definition.
- **Open numerical parameters:** window length, required price displacement/range expansion, delta criteria, and whether thresholds vary by session or model. Ensure the calculation uses only data available at the decision time.

### Q11.3 — Session/news blackout windows — Option C
Make blackout windows configurable and compare results for session opens, maintenance halts, and scheduled-news windows against a no-blackout baseline.
- **Open parameters:** which events are supported, event calendar/source, blackout duration before/after each event, session-open duration, and timezone/session-calendar handling.

### Q11.4 — Strategy-level trade limits and cooldown — Option C
Make maximum trades per session and per zone, plus a cooldown after a loss, configurable. Start conservatively and avoid duplicating controls already enforced by the separate risk chain.
- **Open numerical/behavioral parameters:** default session and zone limits, cooldown duration, what qualifies as a loss, whether breakeven exits count toward limits, and how overlapping session/zone limits interact.

### Q12.1 — Heatmap and gamma scope — Option B
Include the heatmap layer in v1 and defer gamma. Heatmap implementation depends on suitable depth data; gamma remains out of scope until an options-data source and its data requirements are explicitly addressed.
- **Open work:** confirm availability and retention of historical depth data and define the minimum feed/data contract required for heatmap testing. No gamma-derived trade filter should be enabled in v1.

### Q12.2 — Heatmap “wall” definition — Option C
Define walls using configurable size and persistence thresholds, retain the underlying observations, and test candidate definitions against outcomes rather than treating a single fixed lot count as universal.
- **Open numerical parameters:** size threshold, persistence duration, aggregation across price levels, and any size normalization for changing market activity. The original source's 100–150-lot Gold reference is context, not a validated final threshold.

### Q13.1 — Acceptance test before a rule may trade — Option B
Require a defined forward-testing period and minimum number of signals, with performance and risk metrics specified in advance, before allowing a rule to trade. The course screenshots are not treated as evidence of a track record; validation should use the project's own data and Sim forward tests as described in the original source.
- **Open numerical/acceptance parameters:** forward-test duration, minimum signal count, required metrics, and pass/fail thresholds. Define these before reviewing results to avoid tuning the acceptance gate after the fact.

### Original-source decision-table status update
- Q11.2: Policy resolved — configurable price-movement and delta window; thresholds remain open.
- Q11.3: Policy resolved — configurable session/news blackouts compared with a no-blackout baseline; event definitions and durations remain open.
- Q11.4: Policy resolved — configurable session/zone limits and loss cooldown; numerical limits and counting behavior remain open.
- Q12.1: Resolved — heatmap included in v1; gamma deferred. Suitable historical depth data remains a prerequisite to validate.
- Q12.2: Policy resolved — configurable wall-size and persistence criteria; thresholds and aggregation rules remain open.
- Q13.1: Policy resolved — require forward-test duration, minimum signal count and pre-defined metrics; numerical acceptance criteria remain open.
- At the time of this earlier status update, Q13.2 and Q13.3 were still pending. They are subsequently resolved in the final original-source decision log below; refer to that later log for the current status.

Scope remains restricted to the original `orderFlowRulesRedefinition.md`. All extended questions are deferred. GitHub was not modified.

## Original-source review decisions — Q13.2–Q13.3 (final original-source questions)

These decisions apply only to the original `orderFlowRulesRedefinition.md`. All extended questions remain deferred. GitHub was not modified.

### Q13.2 — Log raw signals before order wiring — Option A
Log each rule's raw signal and its supporting evidence before connecting order execution, so signal quality can be assessed independently of P&L. Preserve the individual model/rule result and the evidence available at signal time; do not limit records to signals that result in trades.
- **Open implementation details:** exact schema and fields, event/record identifiers, timestamp convention, reason codes for accepted/rejected signals, and log retention/rotation policy. The schema should be sufficient to compare signal quality independently from order outcomes.

### Q13.3 — Historical Gold tick/footprint data — resolved using the user's current recording design
The current plan is to record data for **more than 7 days** for all order-flow constructs and entry-model logs, storing **1-second snapshots of each order-flow construct**. Raw tick logs and DOM logs are **not** currently stored.
- This changes the prior assumption that the only retained data is a rolling 7-day tick recording: the project is retaining derived construct snapshots and entry-model logs for longer than 7 days.
- The recorded snapshots can support analysis/backtesting of the order-flow constructs and entry-model decisions represented in those snapshots, subject to the fields actually persisted and snapshot timing.
- **Important limitation:** because raw tick events and DOM/order-book logs are not stored, the snapshots cannot recreate the original tick-by-tick event sequence or historical depth/DOM evolution. They therefore cannot, by themselves, support a true historical DOM/heatmap replay or testing of features that require the missing raw event stream.
- **Open data-design details:** exact retention duration beyond seven days; the full persisted field set for every construct and entry-model log; snapshot timestamp and event ordering semantics; handling of missing/stale snapshots; and whether a future need justifies adding raw tick or DOM capture. No claim is made that the existing Rithmic account independently supplies historical tick or DOM exports; that availability has not been verified by this decision.

### Final original-source decision-table status update
- Q13.1: Policy resolved — require a forward-test period, minimum signal count, and predefined performance/risk metrics before allowing a rule to trade. Numerical thresholds remain open.
- Q13.2: Resolved — log each rule's raw signal and evidence before order wiring. Schema and retention details remain open.
- Q13.3: Resolved for the current data-recording approach — retain 1-second snapshots of each order-flow construct and entry-model logs for more than seven days; do not currently store raw tick or DOM logs. This enables testing against the stored construct snapshots but does not replace raw tick/DOM history for event-level or heatmap replay. Exact retention and schema details remain open.

All original-source questions Q1.1–Q13.3 have now received a decision at the policy/scope level. Remaining numerical thresholds, implementation details, data schema, and validation criteria are explicitly listed as open work rather than silently assumed. Extended questions remain deferred. GitHub was not modified.

