
# Reviewed Master — Governance and Review Findings

**Status:** Initial reviewed working copy. Questions remain OPEN unless explicitly marked RESOLVED during the review in this conversation.

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
9. **Core liquidity heatmap/sweep features; gamma remains deferred pending a reliable source.**
10. **Validation:** logging, data, forward-test acceptance criteria.

## Review log

| Item | Status | Note |
|---|---|---|
| Authority rule | Recorded | This reviewed document governs approved execution-model rules. |
| Q0.1 — V1 entry-model scope | **RESOLVED** | Include all seven candidate models (M1–M7) in V1 as candidate evidence/entry-model checks applied to trade ideas generated by the underlying strategy. This does not mean every model must independently generate a trade or that all models are already validated. |
| Q0.2 — Directional scope | **RESOLVED** | Evaluate long and short trade ideas without a directional bias. This is a research baseline, not an assumption that individual entry models perform symmetrically; any bias must be discovered from evidence. |
| Q0.3 — Execution-model architecture | **RESOLVED** | Use one unified execution model to evaluate each eligible strategy-generated trade idea against all seven candidate entry models, while logging each model's result separately. |
| Q0.4 — Heatmap and gamma scope | **RESOLVED** | Liquidity heatmap is a core Flow_v2 component in V1, not merely an optional visual. Keep the visualization and expose structured, timestamped DOM-derived liquidity features to models/strategies. Defer GEX/gamma until a reliable accurate source is available. |
| Q12.2 — Configurable liquidity-wall detector | **RESOLVED (architecture)** | Detect walls using separate configurable features for absolute/relative size, persistence, replenishment, proximity, and stacking/pulling. Preserve measurable features and research a combined score; exact thresholds and combination rules remain open. |
| Q13.1 — Staged validation and promotion authority | **RESOLVED (process)** | Use signal-only research, simulated execution, and an acceptance review informed by configurable criteria (e.g. sample size, R:R, win rate, expectancy, drawdown, and market-condition coverage). Criteria inform the decision but do not automatically promote a rule; the user retains final authority to move any rule from stage to production. |
| Q13.2 — Mandatory signal logging before order execution | **RESOLVED** | Log each rule's raw signal and supporting market context before order execution is connected, so signal quality can be assessed independently of execution quality and P&L. Capture timestamps, direction, model output/evidence, and subsequent price movement; exact schema/retention remain open. |
| Q13.3 — Historical Gold futures tick/order-flow data | **RESOLVED (current state / workflow)** | Historical tick/footprint data is not currently available to the project and is still being researched. If suitable data is obtained, begin order-flow backtesting straightaway. Until then, continue with available recorded data and forward testing; do not imply historical order-flow backtests have been completed. |
| Q1.1 — Bar size configurability | **RESOLVED** | Bar size is trader preference; keep bar construction dynamic/configurable so multiple variations can be tested rather than hard-coding 500-tick, 5-minute, or range bars. |
| Q1.2 — Adaptive bar sizing | **RESOLVED** | Use fixed, manually configured bar sizes for now. Adaptive sizing is deferred and may be reconsidered later if research shows a need. |
| Q1.3 — Execution/structure timeframes | **RESOLVED** | Keep execution and market-structure timeframes separate and configurable; do not force them to use the same bar size. |
| Q2.1 — Diagonal imbalance threshold | **RESOLVED** | Make the diagonal-imbalance threshold configurable for research; no single percentage is locked in as the universal rule. |
| Q2.2 — Imbalance minimum volume | **RESOLVED** | Make the minimum-volume requirement configurable as a separate parameter from the diagonal-imbalance ratio. |
| Q2.3 — Footprint row grouping | **RESOLVED** | Footprint row grouping will exist and be configurable so different groupings can be tested. |
| Q2.4 — Finished auction concept | **PARTIALLY RESOLVED** | Record the course/user concept: at a rising extreme, zero selling or buying at the top may indicate unwillingness to trade; at a falling extreme, zero participation on the relevant side at the bottom may indicate the auction is finished. Which zero-side observation is more significant in each direction remains OPEN for research. |
| Q3.1 — Big-trade threshold | **PARTIALLY RESOLVED** | Threshold is configurable; intended to become adaptive, but the adaptation rule will be determined through trial, error, research, and analysis. The adaptation method remains OPEN. |
| Q3.2 — Successful/failed aggression | **PARTIALLY RESOLVED** | Determine success/failure from price response to aggressive volume, not aggressive volume alone. Measurement window, price-progress metric, and success/failure thresholds remain OPEN. |
| Q3.3 — Repeated aggressive prints | **RESOLVED** | Support both independent-print analysis and aggregation of repeated prints into an aggression burst, with the representation configurable for research. |
| Q3.4 — Aggression proximity | **RESOLVED** | Make aggression proximity configurable and support measuring distance in both ticks and price range, so both units can be tested. |
| Q3.5 — Aggression definition/data source | **RESOLVED** | Use the feed's bid/ask trade execution classification, the same underlying data used to build footprint charts. Keep thresholds and aggregation rules configurable. |
| Q4.1 — Absorption concept | **PARTIALLY RESOLVED** | Absorption requires evidence of high resting liquidity supporting the failed-aggression interpretation. Liquidity thresholds and other numerical parameters are configurable; exact definitions and values remain OPEN. |
| Q4.2 — Absorption evidence sources | **RESOLVED** | Use full DOM from the Rithmic feed (the same source used to build the liquidity heatmap) plus executed-volume footprint evidence of failed aggression. Both sources together provide the evidence for absorption. |
| Q4.3 — Absorption measurement window | **RESOLVED** | Make the absorption measurement window configurable so different windows can be tested. Preserve event timestamps so DOM liquidity and executed-volume evidence can be aligned during research. |
| Q4.4 — Delta measurements | **PARTIALLY RESOLVED** | Support bar delta, price-level delta, and cumulative delta as configurable research inputs. For absorption completion/confirmation, only bar delta and price-level delta are currently in scope; which one(s) to use in which situations remains OPEN for research. |
| Q4.5 — Absorption stages and delta-flip confirmation | **PARTIALLY RESOLVED** | Failed aggression plus supporting resting-liquidity evidence indicates absorption is happening/being attempted; a delta flip confirms absorption completion. Aggressors may ultimately win, in which case no delta flip occurs. Exact delta-flip definitions and context-specific measurement selection remain OPEN. |
| Q4.6 — Failed aggression vs absorption | **RESOLVED** | Failed aggression is a broader, independent concept. Supporting resting-liquidity evidence is required to classify it as an absorption attempt; delta flip is the separate confirmation of absorption completion. |
| Q5.1 — Trade-location sources | **RESOLVED** | Make volume profile, VWAP, higher-timeframe market structure, and session extremes available as configurable location sources; their usefulness will be evaluated through research. |
| Q5.2 — Volume-profile types and anchors | **RESOLVED** | Support multiple profile types with configurable anchors and allow multiple profiles to be active simultaneously (e.g. weekly, daily, and current-session profiles) to provide broader context. Exact profile definitions and interaction rules remain OPEN. |
| Q5.3 — LVN qualification vs trade relevance | **RESOLVED** | Qualify LVNs using configurable relative-depth and width thresholds. Separately evaluate an LVN's relevance to a trade idea by its distance from the idea's zone, with configurable distance tolerance; a valid LVN can be meaningful without being relevant enough to prioritize for a particular trade. Exact formulas, thresholds, distance reference, and cutoff remain OPEN. |
| Q5.4 — HVN research scope | **RESOLVED** | Treat HVNs similarly to LVNs for exploratory analysis, using configurable qualification parameters and distance-to-trade-idea relevance, but HVNs are informational only and must not affect trade execution for now. |
| Q5.5 — VAH/VAL role and tradeability | **PARTIALLY RESOLVED** | Treat VAH and VAL as potential reversal zones with significance comparable to or greater than LVNs. Unlike an LVN range, each boundary is a single price level. They are tradable concepts, but rigid entry/confirmation rules are explicitly deferred for later research. |
| Q5.6 — VWAP role | **PARTIALLY RESOLVED** | VWAP may act as a counter-trade location in suitable conditions (e.g. overlap with POC plus a strategy idea in the same direction), but this is contextual, not an absolute rule. More commonly, VWAP is one of several inputs used to determine a target. Exact conditions and target logic remain OPEN. |
| Q5.7 — Higher-timeframe vs primary-timeframe structure | **RESOLVED** | Higher-timeframe market structure is used to identify overall directional bias. Trade ideas are generated by the primary (chosen) timeframe's structure/strategy. Currently market-structure rules are the only strategy and generate ideas; in future they may become supporting evidence rather than the core strategy, without changing the higher-timeframe bias role. |
| Q5.8 — Session extremes role | **PARTIALLY RESOLVED** | Session highs/lows are important levels but provide weaker standalone reversal evidence than VAH/VAL and POC. They are primarily target identifiers like VWAP, but may become tradable under specific conditions. Exact conditions remain OPEN. |
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
| Q8.1 — Model aggregation and Go/No-Go | **RESOLVED** | The aggregation layer combines the results of M1–M7 and produces the final Go/No-Go decision. There is no separate universal confirmation-count rule at this layer. Once Go is issued, initiate entry according to the configured trigger-timing and order-entry modes, provided the idea has not been invalidated/expired and applicable position-management and order-state rules permit action. |
| Q8.2 — Model-specific requirements and cross-model aggregation | **RESOLVED** | Each model enforces its own mandatory internal conditions and reports its result; the aggregation layer then combines M1–M7 results and may evaluate cross-model agreement and conflicts. Exact model conditions and aggregation rules remain subject to their dedicated specifications. |
| Q8.3 — Continuous model updates and aggregation refresh | **RESOLVED** | While a trade idea remains in execution evaluation, M1–M7 continue evaluating in real time. A model-result change immediately notifies the aggregation layer, which updates that model's current state and recalculates Go/No-Go from the latest available results. Use event-driven updates with maintained per-idea state rather than polling; continue until invalidation, configured expiry, or another defined lifecycle transition. Bar boundaries do not limit evidence accumulation; each model and the aggregation rules determine which current evidence remains valid. |
| Q9.1 — Stop-loss and target module architecture | **RESOLVED** | After the aggregation layer decides the entry point and direction, it uses separate stop-loss and target modules to determine the best stop level and target. Each module can use all available market-structure, price-action, order-flow-construct, and M1–M7 evidence, and should remain extensible to future models/data points. The exact selection algorithms and criteria remain open. |
| Q9.2 — Stop-loss candidate generation and ranking | **RESOLVED** | The dedicated stop-loss module evaluates stop candidates supplied by the strategy, entry models, and available constructs such as the liquidity heat map and volume profile. It combines or ranks the available evidence and generates the stop-loss level to use. The candidate-generation, scoring/ranking, conflict-resolution, and final-selection rules remain open for research. |
| Q9.3 — Stop-loss constraints vs trade acceptance | **RESOLVED** | The stop-loss module returns an SL level only. It evaluates structurally/logically valid candidates using configured rules, including the maximum single-trade loss limit and fixed R:R requirements where relevant, but it does not decide whether to take or skip the trade. The separate risk-management module owns trade acceptance/rejection and may skip an otherwise valid Go idea if the resulting SL breaches risk rules. |
| Q9.4 — Configurable and model-specific time-based exits | **RESOLVED** | Support an optional time-based exit that can be enabled or disabled through configuration, analogous to the configurable daily maximum-loss limit. Allow model/strategy-specific durations or rules where needed, alongside a global/default policy. Exact durations and exit behavior remain open for research. |
| Q10.1 — Target candidate generation and ranking | **RESOLVED** | The separate target module evaluates target candidates from the strategy, M1–M7, market structure, price action, order-flow constructs (including liquidity heat map and VP), and future evidence. It combines or ranks the available candidates/evidence using configured rules and returns the target level. It does not decide whether the trade should be accepted. Exact candidate scoring, ranking, and conflict-resolution rules remain open. |
| Q10.2 — Structural target distance and R:R | **RESOLVED** | The target module ranks candidates using configured structural and R:R rules, while the risk-management module retains responsibility for deciding whether the resulting trade is acceptable. If the best available target does not meet the configured requirements, risk management may reject/skip the trade. Exact ranking and thresholds remain open. |
| Q10.3 — Dynamic position management and partial exits | **RESOLVED** | A separate position-management module controls partial exits and stop adjustments, with access to the available real-time data, market structure, price action, order-flow constructs, and model evidence. Partial exits may be triggered by market conditions (e.g. a high-quality LVN before the target), not only fixed fractions of target distance; fixed rules such as partial-at-level or move-to-breakeven remain configurable options rather than universal behavior. Exact decision rules remain open for research. |
| Q10.4 — Configurable re-entry rules | **RESOLVED** | Support configurable re-entry conditions and attempt limits, with separate handling for stop-loss exits, breakeven exits, and partial/normal exits. Re-entry still requires the original idea/setup to remain valid and must respect applicable position-management and risk-management constraints. Exact conditions and limits remain open for research. |
| Q11.1 — No-trade filter architecture and risk-management ownership | **RESOLVED** | Use a hybrid of global filters and strategy/model-specific filters, with explicit ownership and recorded reasons. These filters are part of the risk-management layer discussed earlier, which can reject a trade even after entry aggregation returns Go. Strategy/model-specific rules remain configurable; exact V1 filters and thresholds are still open. |
| Q11.2 — Configurable chase-risk filter architecture | **RESOLVED** | Use a hybrid chase-risk filter: a configurable score/combination of signals with configurable hard-rejection thresholds and optional strategy-specific rules. The actual formula and momentum-strength definition are explicitly undecided and must be researched/validated before being fixed. |
| Q11.3 — Configurable session/news blackouts | **RESOLVED** | Support configurable blackout windows for new entries, including scheduled news, exchange maintenance, and session-open periods. Rules may have global defaults and strategy-specific overrides and are disabled unless explicitly enabled. Blackouts prevent new entries but do not automatically liquidate or alter management of existing positions. Exact windows, data sources, and exceptions remain open. |
| Q11.4 — Trade-count limits and post-loss cooldowns | **RESOLVED** | Support configurable trade-count limits per session and/or zone, plus configurable cooldowns after a loss. Use global defaults with optional strategy-specific rules; keep limits and cooldowns separately configurable rather than universal. Exact counting semantics, reset boundaries, cooldown triggers/durations, and interaction with re-entry remain open. |
| R1–R9 | OPEN | Findings recorded; address in review order. |
| All inherited questions | OPEN unless explicitly resolved here | Do not infer approval from a draft or recommendation. |

### Q0.4 — Heatmap and gamma scope (RESOLVED)

**Decision.** Include the liquidity heatmap in V1 as a **core Flow_v2 component**, not merely an optional visual layer. Keep the visual heatmap and also expose structured, programmatically consumable liquidity features to strategy and execution models. Do not include GEX/gamma levels in V1 at this stage.

**Heatmap data and event history.** Build the liquidity heatmap solely from full DOM data received through the Rithmic feed. Preserve timestamped events for liquidity appearance, changes, persistence, and disappearance so features can be derived and tested over time. Keep thresholds, persistence, replenishment, proximity, and measurement windows configurable for research rather than treating unvalidated values as universal rules.

**Relationship to absorption.** Absorption evidence must combine resting-liquidity evidence from the heatmap/full DOM with executed-volume footprint evidence of aggression that fails to produce the expected price response. A heatmap wall alone is not sufficient to declare absorption.

**Reason for deferring GEX.** There is currently no API/source available to the project that provides sufficiently reliable, accurate GEX levels, and the user does not consider deriving them locally accurate enough. GEX may be reconsidered once a reliable source becomes available.

**Scope boundary.** Heatmap and GEX are separate capabilities: including the heatmap does not imply that GEX is included. Exact wall qualification, structured feature definitions, and how each feature contributes to acceptance/rejection remain subject to research and explicit review.

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

## Q4.5 decision recorded during conversation — absorption stages and delta-flip confirmation

**Status: PARTIALLY RESOLVED**

**Conceptual distinction.**
- **Absorption attempt / absorption happening:** Failed aggression plus supporting resting-liquidity evidence indicates that passive liquidity is attempting to absorb aggressive orders.
- **Absorption complete/confirmed:** A delta flip is confirmation that the absorption has completed.

An absorption attempt does not guarantee success. Aggressors may eventually overcome the resting liquidity; in that case, the expected delta flip will not appear, and the attempt must not be treated as confirmed absorption.

**Delta inputs in scope.** For this absorption-completion/confirmation question, consider **price-level delta and bar delta**. Cumulative delta may remain available as a general research input for other models/questions, but it is not part of the current candidate set for confirming absorption completion.

**OPEN research questions.** The exact definition of a delta flip, whether bar delta, price-level delta, or both should be used in a given situation, and how that selection varies by market context/entry model must be answered through research and analysis. Do not assume one universal delta measurement.

---

## Q4.4 decision recorded during conversation — delta measurements

**Status: RESOLVED**

**Decision.** Support all three delta measurements as configurable research inputs:
- **Bar delta:** net aggressive buying versus selling across a bar.
- **Price-level delta:** delta at an individual footprint price level.
- **Cumulative delta:** running delta total over a session or configured window.

**Research implication.** Evaluate whether each measurement adds useful information, separately for each entry model. Do not assume the measurements are interchangeable or equally useful.

**Scope boundary.** Exact calculation conventions, reset boundaries for cumulative delta, and which measurements each model consumes remain OPEN for later specification.

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

**Configuration requirement.** The threshold for what qualifies as “high” resting liquidity, along with other numerical parameters used to detect absorption, must be configurable.

**OPEN research questions.** The liquidity source/measurement, comparison baseline for “high,” required persistence or replenishment, proximity to the relevant price/zone, measurement window, and exact relationship between the liquidity evidence and failed aggression remain OPEN. Do not invent fixed values at this stage.

---

## Q3.5 decision recorded during conversation — aggression definition and data source

**Status: RESOLVED**

**Decision.** Use bid/ask trade execution classification from the existing market-data feed as the base definition of aggression:
- Trades executed at the ask are classified as aggressive buying.
- Trades executed at the bid are classified as aggressive selling.

The user reports that the feed provides accurate bid/ask execution data and that this is the same data used to generate footprint charts.

**Configuration requirement.** Aggression thresholds and aggregation rules must be configurable.

**Scope boundary.** This settles the base data source and classification approach. Exact thresholds, burst/aggregation parameters, price-response windows, and success/failure criteria remain open unless resolved in their respective questions. Feed quality is the project's stated assumption and should still be monitored during validation.

---

## Q3.4 decision recorded during conversation — aggression proximity

**Status: RESOLVED**

**Decision.** Aggression proximity must be configurable and support both:
- Distance measured in ticks.
- Distance measured as a price range.

Both distance units must be available for research and comparison; do not restrict proximity to ticks alone.

**Scope boundary.** The exact reference price/zone, distance calculation, initial values, and whether both modes share the same underlying threshold or have separate configuration remain OPEN.

---

## Q3.3 decision recorded during conversation — repeated aggressive prints

**Status: RESOLVED**

**Decision.** Support both:
- Analyze qualifying aggressive prints independently.
- Aggregate repeated prints into a single aggression burst.

Keep the representation configurable for research so the approaches can be compared.

**Scope boundary.** The aggregation window, rules for deciding which prints belong to the same burst, and any maximum gap or reset conditions remain OPEN.

---

## Q3.2 decision recorded during conversation — successful versus failed aggression

**Status: PARTIALLY RESOLVED**

**Decision.** Keep these as separate measurements:
1. **Aggression:** how much aggressive buying or selling occurred.
2. **Price response:** how far price moved in the expected direction and over what measurement window.
3. **Outcome:** whether the aggression succeeded, failed, or remains inconclusive.

Successful versus failed aggression must be determined by the **price response to aggressive volume**, not by aggressive volume alone.

**OPEN research questions.** The measurement window, price-progress metric, success/failure thresholds, and conditions for an inconclusive outcome remain undecided. Do not invent or hard-code them before review and testing.

---

## Q3.1 decision recorded during conversation — big-trade threshold

**Status: PARTIALLY RESOLVED**

**Decision.** The big-trade threshold must be configurable. The intended direction is for the threshold to become adaptive rather than remain a permanently fixed value.

**OPEN research question.** The adaptation rule is not decided yet. It will be developed through trial and error, research, and analysis; do not assume a particular lookback, percentile, normalization method, session adjustment, or other formula before evidence supports it.

**Research implication.** Keep the threshold and adaptation method explicit in configuration/experiment records so fixed candidate values and later adaptive candidates can be compared. The initial baseline and the exact definition of “trade size” used for this threshold remain OPEN.

---

## Q2.4 decision recorded during conversation — finished auction

**Status: PARTIALLY RESOLVED**

**Concept recorded from the user's explanation.**
- In an uptrend, when price reaches the very top and the footprint suddenly shows zero selling or buying (the precise side to prioritize is not yet decided), this may indicate that participants are unwilling to do business at that extreme.
- In a downtrend, when price reaches the very bottom and the footprint shows no participation on the relevant side, this may indicate that the auction is finished.
- This is a candidate interpretation, not yet a fully specified or validated rule.

**OPEN research question.** Which observation is more significant at each extreme: zero buying or zero selling? The direction/side mapping and relative significance must be established through research and analysis rather than assumed.

**Not yet specified.** Exact footprint field meant by “zero buys/sells,” whether zero must occur at the extreme row or across a defined set of rows, whether the bar must be complete, handling of missing/filtered data, and whether this acts as a standalone signal or supporting evidence all remain OPEN.

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

## Q9.1 decision recorded during conversation — separate stop-loss and target modules

**Status: RESOLVED**

**Decision.** Stop-loss selection is handled by a dedicated **stop-loss module**, and target selection is handled by a separate **target module**. The aggregation layer first determines the entry point and direction, then invokes these modules to determine the best stop-loss level and target for that proposed trade.

**Available information.** Each module may use all relevant information already available to the system, including:
- Market-structure data and levels.
- Price action and relevant price extremes/zones.
- Order-flow constructs, including footprint, liquidity/DOM-derived constructs, and volume-profile information.
- Results and evidence from entry models **M1–M7**.
- Additional models or data points introduced in the future.

**Boundary.** This decision establishes module ownership, invocation order, and broad access to evidence. It does **not** prescribe a single universal structural reference, buffer formula, risk/reward rule, or selection algorithm. Those details must be specified and researched separately. The target module follows the same modular principle but remains independently responsible for target selection.

---

## Q11.4 decision recorded during conversation — trade-count limits and post-loss cooldowns

**Status: RESOLVED**

**Decision.** The risk-management module will support both configurable trade-count limits and configurable cooldowns after a loss. These are separate controls and will not be imposed as universal rules.

**Trade-count limits.**
- Support limits scoped to a trading session and/or a configured zone.
- Support global defaults and optional strategy-specific rules.
- When a configured limit blocks a new entry, record the applicable rule and reason.

**Post-loss cooldowns.**
- Support configurable cooldown rules after a loss.
- Keep cooldown configuration separate from trade-count limits so each can be enabled, disabled, and tuned independently.
- Record the cooldown rule and reason whenever it blocks a new entry.

**Still open.** Define what counts as a trade for the limit, session/zone boundaries and reset behavior, which outcomes trigger a cooldown, whether cooldown timing begins at exit or another defined event, duration, and interaction with re-entry and existing-position management. These details require explicit rules before implementation.

---

## Q11.3 decision recorded during conversation — configurable session/news blackouts

**Status: RESOLVED**

**Decision.** The risk-management module will support **configurable blackout rules** that prohibit new entries during designated windows. These rules remain disabled unless explicitly enabled in the active configuration.

**Potential blackout categories include:**
- Scheduled economic/news releases.
- Exchange maintenance or other configured exchange interruptions.
- Configured periods around session opens, such as the first few minutes after an open.

**Configuration.** Support global/default blackout settings and optional strategy-specific overrides where needed. Record the active configuration and the blackout rule/reason that blocks a proposed entry.

**Existing positions.** A blackout prevents new entries; it does **not** automatically liquidate existing positions or suspend their normal position-management rules. Open positions continue to be managed according to their applicable position-management and risk-management rules.

**Still open.** Exact blackout durations, session calendars, news/event data sources, timezone handling, strategy-specific exceptions, and conflict/priority rules remain to be specified and validated.

---

## Q11.2 decision recorded during conversation — configurable chase-risk filter architecture

**Status: RESOLVED — ARCHITECTURE ONLY; FORMULA OPEN**

**Decision.** Implement the “don't chase” concept as a hybrid, configurable chase-risk filter. It may combine multiple signals into a chase-risk score, use configured hard-rejection thresholds, and support strategy-specific rules where needed.

**Possible evidence inputs for later research** include consecutive directional bars, delta persistence, range expansion, distance from the intended entry zone, and current order-flow conditions. These are candidate inputs to investigate, not an approved formula or mandatory feature list.

**Explicitly undecided.** The formula for defining momentum strength, signal weights, normalization, lookback windows, score construction, thresholds, and strategy-specific overrides have **not** been decided. They must be researched and validated before the rule is finalized. Do not treat any example formula or signal list as an approved V1 specification.

**Ownership.** This is part of the no-trade/risk-management filter architecture. Its result and the evidence/reason for any rejection should be recorded, while the exact behavior and configuration remain open.

---

## Q11.1 decision recorded during conversation — no-trade filter architecture and risk-management ownership

**Status: RESOLVED**

**Decision.** Use a **hybrid filter architecture**: global filters plus strategy/model-specific filters. Each filter must have explicit ownership, configurable applicability where appropriate, and a recorded outcome/reason when it blocks or restricts a trade.

**Module ownership.** These no-trade filters belong to the **risk-management layer/module** discussed earlier. Entry aggregation can produce a Go decision, but that is not final permission to place a trade. The risk-management module evaluates applicable filters and can reject/skip the proposed trade when a rule blocks it. This is consistent with the earlier decisions that the stop-loss and target modules return levels, while risk management owns final trade acceptance/rejection.

**Configuration and traceability.**
- Global filters can apply across strategies where configured.
- Strategy/model-specific filters can apply to relevant setups without forcing every strategy to use identical rules.
- Record which filters were evaluated, their results, and the reason(s) for any rejection or restriction.
- Exact V1 hard filters, definitions, thresholds, and precedence/conflict behavior are **not** decided by this architecture decision and remain open.

---

## Q10.4 decision recorded during conversation — configurable re-entry rules

**Status: RESOLVED**

**Decision.** Support a **hybrid, configurable re-entry policy**. Re-entry is neither universally prohibited nor automatically allowed every time the original idea remains valid. It is governed by configured conditions and attempt limits, with distinct handling for different exit outcomes.

**Exit-specific handling.**
- **Stop-loss exit:** Apply the configured re-entry conditions for a stopped-out trade; a stop-out may be treated differently from a breakeven exit.
- **Breakeven exit:** Re-entry may be allowed under its own configured conditions if the original idea/setup remains valid.
- **Partial or normal exit:** Handle re-entry according to configured rules for the relevant exit type and remaining position/idea state.

**Constraints.** Any re-entry must respect the original idea's validity/invalidation/expiry state, current market evidence, applicable position-management rules, and risk-management limits. Re-entry must not be triggered solely because the original idea has not yet been invalidated.

**Still open.** Exact re-entry conditions, maximum attempt counts, cooldowns or timing rules (if any), and exit-type-specific policies remain to be specified and researched.

---

## Q10.3 decision recorded during conversation — dynamic position management and partial exits

**Status: RESOLVED**

**Decision.** A dedicated **position-management module** controls partial exits and stop adjustments. Like the stop-loss and target modules, it has access to the available real-time data and evidence across the system, including market structure, price action, order-flow constructs, and model outputs. It is not limited to fixed rules such as taking 50% off at the halfway point or moving the stop to breakeven after a predetermined gain.

**Dynamic, evidence-based behavior.**
- Partial exits may be selected based on current market conditions and the evidence available to the module.
- For example, if a high-quality LVN lies between the current price and the planned target, the module may choose to take partial profits just before that LVN because of the assessed possibility of reversal. This is an example of the intended behavior, not a universal rule that every LVN must trigger a partial exit.
- Fixed management rules (such as taking a configured fraction at a specified progress point or moving the stop to breakeven) may still be supported as configurable options. They are not mandatory universal behavior.
- Stop adjustments are also owned by the position-management module and may respond to configured rules and real-time evidence.

**Responsibility boundary.** The position-management module decides whether and when to partially exit or adjust the stop for an open position. This is separate from the target module, which selects the planned target, and the stop-loss module, which selects the initial stop-loss level. Exact decision logic, evidence thresholds, sizing, and rule priority remain open for research and explicit specification.

---

## Q10.2 decision recorded during conversation — structural target distance and R:R

**Status: RESOLVED**

**Decision.** Use a hybrid approach. The target module evaluates and ranks target candidates using configured structural and risk-to-reward (R:R) rules. The risk-management module remains responsible for deciding whether the trade is acceptable.

**Behavior.**
- Candidate targets may include relevant structural levels and configured fixed-R targets, as already established for Q10.1.
- The target module applies the configured selection/ranking rules; it should not assume that the nearest structural target always wins.
- If the best available target does not satisfy configured requirements, the risk-management module may reject/skip the trade even when the entry aggregation result is Go.
- The target module returns a target level and does not make the final trade-acceptance decision.

**Still open.** Exact structural candidate rules, R:R thresholds, ranking/weighting, and conflict resolution remain to be specified and researched. This decision establishes the responsibility split, not the numerical thresholds or ranking formula.

---

## Q10.1 decision recorded during conversation — target candidate generation and ranking

**Status: RESOLVED**

**Decision.** The dedicated target module will evaluate target candidates from all relevant sources, combine or rank the available evidence using configured rules, and return the target level to use. This mirrors the modular candidate-evaluation approach selected for stop-loss generation, while remaining a separate target-selection responsibility.

**Potential candidate sources include:**
- Target levels or suggestions supplied by the strategy that generated the trade idea.
- Target levels or relevant evidence supplied by entry models **M1–M7**.
- Market structure and price-action levels.
- Order-flow constructs, including the **liquidity heat map** and **volume profile (VP)**.
- Other available data points, constructs, or models added in the future.
- A configured fixed-R target where that mode is enabled and relevant to the target evaluation.

**Responsibility boundary.** The target module returns a target level; it does **not** make the final decision to accept or reject the trade. The aggregation layer has already decided the entry point and direction before the target module is used. Any overall trade acceptance/risk decision remains with the appropriate risk-management/decision layer.

**Still open.** The specific candidate-generation rules, scoring/ranking, weighting, conflict resolution, treatment of intervening profile nodes, and selection criteria remain to be specified and researched. This decision does not assume that the nearest structural level, a fixed R-multiple, or any single source always wins.

---

## Q9.4 decision recorded during conversation — configurable and model-specific time-based exits

**Status: RESOLVED**

**Decision.** Support a time-based exit as an optional, configurable risk/exit rule. It may be enabled or disabled according to the active configuration, in the same general way that the system can enable or disable a daily maximum-loss limit.

**Configuration scope.**
- Allow strategy/model-specific time limits or rules when different trade ideas have different expected durations.
- Support a global/default policy where appropriate, with model/strategy-specific settings taking precedence when explicitly configured.
- Record the active configuration and whether the time-based exit was enabled for the relevant evaluation/trade.

**Boundary.** This decision establishes configurability and the option for model/strategy-specific behavior. It does not set exact durations, define the timer's start point, or specify the exact exit/order-handling procedure; those remain open for research and explicit specification.

---

## Q9.3 decision recorded during conversation — stop-loss constraints vs trade acceptance

**Status: RESOLVED**

**Decision.** The stop-loss module's responsibility is to **produce an SL level**, not to decide whether the trade should be taken. It evaluates available candidates and evidence, applying the configured rules to select the level it returns.

**Inputs and constraints.** In addition to logical/structural validity, the SL evaluation must account for configured rules relevant to the level, including:
- The configured maximum loss limit for a single trade.
- The configured fixed risk-to-reward (R:R) requirement, where relevant to evaluating the SL level alongside the planned entry/target.
- The available market-structure, price-action, order-flow, strategy, and entry-model evidence already specified for the stop-loss module.

**Responsibility boundary.** An idea may have a valid setup and an aggregation-layer **Go** decision but still be unsuitable under risk management once the SL level is known. The SL module does not approve or reject that trade. The separate **risk-management module** owns the final risk-based acceptance/rejection decision and may skip the trade if the generated SL causes a configured risk constraint to be breached.

The SL module returns the selected SL level and relevant diagnostics; it must not silently force an invalid stop closer merely to make the trade fit the risk limits. Exact candidate ranking and configured rule details remain subject to specification and research.

---

## Q9.2 decision recorded during conversation — stop-loss candidate generation and ranking

**Status: RESOLVED**

**Decision.** The stop-loss module will consider the available candidate levels and evidence from across the system, then combine or rank that information to generate the stop-loss level used for the trade. It is not restricted to one universal structural reference or to levels supplied by a single model.

**Potential candidate sources include:**
- Stop-loss levels or suggestions supplied by the strategy that generated the trade idea.
- Stop-loss levels or relevant evidence supplied by entry models **M1–M7**.
- Order-flow constructs and their levels, including the **liquidity heat map** and **volume profile (VP)**.
- Market structure, price action, and other available data points or future models/constructs.

**Boundary.** This decision establishes broad candidate sources and the module's responsibility to combine or rank their evidence. It does not yet prescribe a ranking formula, weights, tie-breaking/conflict resolution, required buffers, maximum stop distance, or the final selection algorithm. Those remain open for research and explicit specification. The stop-loss module returns the stop level to use after the entry point and direction have been decided.

---

## Q8.3 decision recorded during conversation — continuous model updates and aggregation refresh

**Status: RESOLVED**

**Decision.** A trade idea remains under continuous execution evaluation after it enters that state. Entry models **M1–M7** keep evaluating their conditions in real time for as long as the idea remains active, including potentially several minutes, until its invalidation condition is met, a configured expiry is reached, or another explicitly defined lifecycle transition ends evaluation.

**Update mechanism.** Use an **event-driven model-result update with maintained current state per trade idea**:
- Each model maintains its latest result and associated evidence for that idea.
- Whenever a model result changes (for example, M2 changes from `-1` to `+2`), it publishes a result-change update for that idea.
- The aggregation layer consumes the update, replaces that model's current result in the idea's maintained state, and immediately recalculates Go/No-Go using the latest available model results and configured aggregation rules.
- The event triggers recalculation; the maintained current state is the input used to calculate the decision. Do not rely on repeated polling as the primary update mechanism, and do not let individual models independently overwrite the overall Go/No-Go result.

**Evidence timing.** This is not limited to a same-bar or fixed multi-bar confirmation window. While the idea is in execution evaluation, models and aggregation continue checking their configured conditions regardless of bar boundaries. Each model's rules and the aggregation configuration determine which evidence is currently valid, when it resets, and whether any explicit time/price expiry applies. Exact per-model conditions and evidence-retention rules remain to be specified.

**Implementation safeguards.** Updates and recalculations should be associated with the correct trade idea and processed consistently so that the aggregate decision reflects the latest model state rather than a stale or out-of-order update. The Go/No-Go result must still respect idea invalidation/expiry and applicable position-management and order-state rules; a Go result does not guarantee an order fill.

---

## Q8.2 decision recorded during conversation — model-specific requirements and cross-model aggregation

**Status: RESOLVED**

**Decision.** Use the hybrid approach:
- Each entry model **M1–M7** enforces its own explicitly defined mandatory internal conditions and reports its model result.
- The aggregation layer combines the seven model results and may additionally evaluate cross-model agreement and conflicts.
- Do not impose an extra universal confirmation-count rule outside this model/aggregation architecture.

**Boundary.** This settles where the responsibilities belong, not the detailed logic. The exact mandatory conditions for each model and the aggregation layer's combination/conflict rules remain governed by their specific definitions and any still-open decisions.

---

## Q8.1 decision recorded during conversation — model aggregation and Go/No-Go

**Status: RESOLVED**

**Decision.** The aggregation layer combines the outputs of entry models **M1–M7** and returns a final **Go/No-Go** decision. The confirmation-combination logic belongs in that aggregation layer; Section 8 must not impose an additional universal minimum number of raw confirmation reads on top of the model outputs.

**Entry behavior.** As soon as the aggregation layer returns **Go**, initiate entry according to the configured trigger-timing and order-entry modes. This does not mean every Go produces a guaranteed fill: market/limit order behavior, order state, and actual fills remain distinct. The idea must also remain valid under its invalidation condition and any configured expiry, and applicable position-management/order-state rules still govern whether an action can proceed.

**Boundary.** This records the Go/No-Go responsibility and intended handoff to entry. It does not define the internal aggregation formula, model weights, thresholds, or conflict-resolution logic beyond previously recorded decisions; those remain governed by the existing aggregation-layer decisions and any explicitly open items.

---

## 8. How confirmations combine

**Course.** No example enters on a single read. The examples stack: location + structure + a closed trigger bar +
at least one footprint read. Example: HTF zone + VA edge + 34-lot print + recovering close [E3]. A zero print alone is
explicitly not enough [E4, E5]. A+ chain: VWAP rejection + repeated zeros + absorbed aggression + delta shift + stacked imbalance [E4].

**Open**
**Q8.1 — RESOLVED: Aggregation layer and Go/No-Go.** The aggregation layer combines the results of M1–M7 and produces the final Go/No-Go decision. Do not add a separate universal count of confirmation reads at this layer. When it returns Go, initiate entry using the configured trigger-timing and order-entry modes, provided the idea remains valid and applicable position-management/order-state rules permit action.
- ⚠️ Q8.2 Are some reads mandatory per model (e.g. M1 requires the close beyond the POC; M4 requires the stacked imbalance)?
- **Q8.3 — RESOLVED:** While an idea remains in execution evaluation, M1–M7 run continuously; every model-result change triggers an immediate aggregation refresh. Evidence is evaluated according to configured model/aggregation rules, not a universal bar-window restriction.

---

## 9. Stop

**Course.** Beyond the zone that invalidates the thesis: above the LVN/volume area for shorts, below the demand zone /
swept low for longs. Slightly beyond the edge, not on it. With a long-term profile the stop gets large, so the stop comes
from the smaller session profile [E3, E6, E10]. The A+ example had a loose, structure-based stop. No tick values are given.

**Open**
- **Q9.1 — RESOLVED:** A dedicated stop-loss module selects the best stop level after entry point and direction are decided, using market structure, price action, order-flow constructs, M1–M7 and future evidence. Exact selection criteria remain open.
- **Q9.2 — RESOLVED:** The stop-loss module evaluates candidates from the strategy, entry models, and available constructs (including liquidity heat map and VP), then combines or ranks the evidence to generate the stop level. Exact ranking/selection rules and buffer remain open.
- **Q9.3 — RESOLVED:** The SL module returns an SL level using configured rules, including logical/structural validity, maximum single-trade loss, and fixed R:R where relevant. It does not decide whether to take the trade; the risk-management module may reject/skip a Go idea if risk constraints are breached. Exact candidate ranking and rule details remain open.
- **Q9.4 — RESOLVED:** Support an optional configurable time-based exit that can be enabled/disabled, with model/strategy-specific durations or rules where appropriate. Exact durations, timer start point, and exit handling remain open.

---

## 10. Target

**Course.** Minimum 1:2, 1:3 possible. Structural targets: VWAP first in counter-trades, then POC, or the opposite
value-area edge (VAL for shorts) [E2, E3]. Don't take a trade if a POC/HVN lies between entry and target [E3].
Partial management: after a small gain move the stop to cost ("SL CTC"), book about 50 % at the halfway point, hold the rest
for the full target, re-enter after a cost exit if the idea still holds [E6]. Start counter-trades with minimum size.

**Open**
- **Q10.1 — RESOLVED:** The target module evaluates candidates from the strategy, M1–M7, market structure, price action, order-flow constructs (including liquidity heat map and VP), and future evidence; it combines/ranks candidates using configured rules and returns a target level. Fixed-R may be a candidate when configured. Exact ranking and selection rules remain open.
- **Q10.2 — RESOLVED:** Use a hybrid: the target module ranks candidates using configured structural and R:R rules; the risk-management module decides whether the resulting trade is acceptable and may skip it if requirements are not met. Exact structural candidates, thresholds, and ranking rules remain open.
- **Q10.3 — RESOLVED:** A dedicated position-management module controls partial exits and stop adjustments using available real-time data and evidence. Partial exits can respond to market conditions (e.g. a high-quality LVN before the planned target), not only fixed target-progress fractions. Fixed partial/breakeven rules may remain configurable options. Exact logic, thresholds, sizing, and priority remain open.
- **Q10.4 — RESOLVED:** Support configurable re-entry conditions and attempt limits, with distinct handling for stop-loss, breakeven, and partial/normal exits. Re-entry requires the idea to remain valid and must respect position/risk controls. Exact conditions and limits remain open.

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
- **Q11.1 — RESOLVED (architecture/ownership):** Use global plus strategy/model-specific filters, with explicit ownership and recorded reasons. These no-trade filters belong to the risk-management module, which may reject a trade even after entry aggregation returns Go. Exact V1 hard filters and thresholds remain open.
- **Q11.2 — RESOLVED (architecture only):** Use a hybrid configurable chase-risk filter with a combined score, hard-rejection thresholds, and optional strategy-specific rules. The actual formula for momentum strength, signal weights/windows, and thresholds is explicitly undecided and requires research/validation.
- **Q11.3 — RESOLVED:** Support configurable session/news blackout windows for new entries (e.g. scheduled news, exchange maintenance, and session-open periods), disabled unless enabled. Use global defaults with optional strategy-specific overrides. Existing positions remain under normal position management. Exact windows, calendars/data sources, and exceptions remain open.
- **Q11.4 — RESOLVED:** Support configurable trade-count limits per session and/or zone, plus separately configurable post-loss cooldowns. Use global defaults with optional strategy-specific rules. Counting semantics, reset boundaries, cooldown triggers/durations, and re-entry interaction remain open.

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

**Resolved**
- **Q12.1 — RESOLVED (scope):** The liquidity heatmap is included in V1 as a core Flow_v2 component. GEX/gamma is deferred until a reliable source for accurate levels is available.
- **Q12.3 — RESOLVED (feature access):** Keep the visual heatmap and expose structured, timestamped DOM-derived liquidity features to strategy/execution models. Preserve appearance, changes, persistence, and disappearance events; keep feature thresholds/windows research-configurable. Heatmap resting-liquidity evidence must be combined with executed-volume footprint evidence to establish absorption.

**Open**
- **Q12.2 — RESOLVED (architecture):** Use a configurable multi-factor liquidity-wall detector covering absolute/relative size, persistence, replenishment, proximity, and stacking/pulling. Keep raw feature measurements and research their combination; exact thresholds, windows, normalization, weights, and score rules remain open.

---

### Q12.2 decision recorded during conversation — configurable liquidity-wall detector

**Status: RESOLVED (architecture; thresholds remain open)**

Use a configurable, multi-factor detector to identify potentially meaningful resting liquidity rather than relying on a fixed lot-size threshold alone. Preserve the component measurements independently so they can be researched before selecting a combined score.

Candidate features:
- **Size:** Absolute resting quantity and quantity relative to nearby depth.
- **Persistence:** How long liquidity remains at a price level.
- **Replenishment:** Whether liquidity reappears after being consumed.
- **Proximity:** Distance from current price, measured in ticks.
- **Change behavior:** Stacking/increasing liquidity and pulling/disappearing liquidity.

Keep these measurements and their parameters configurable. Research how to combine them into a wall score; do not assume that a quantity such as 100–150 lots is universally meaningful across market conditions.

**Still open.** Exact thresholds, lookback/persistence windows, normalization, weighting, score combination, and any downstream action or hard filter remain undecided. Do not treat a detected wall by itself as proof of absorption; absorption also requires executed-volume footprint evidence of aggression failing to produce the expected price response.

---

### Q12.3 decision recorded during conversation — visual heatmap plus structured liquidity features

**Status: RESOLVED**

The liquidity heatmap is an important, core Flow_v2 component. It must not be treated as merely a chart overlay or a later optional add-on.

- Retain the visual heatmap for human inspection and contextual analysis.
- Expose structured, programmatically consumable liquidity features to strategy and execution models.
- Build the heatmap from full DOM data supplied by the Rithmic feed.
- Preserve timestamped liquidity appearance, change, persistence, and disappearance events so feature behavior can be studied and replayed.
- Keep thresholds and measurement choices—such as size, persistence, replenishment, proximity, and windows—configurable for research; exact definitions remain open until reviewed and validated.
- A liquidity wall by itself is not sufficient evidence of absorption. Absorption requires resting-liquidity evidence from the heatmap/full DOM together with executed-volume footprint evidence of aggression failing to produce the expected price response.

This decision establishes the component's importance and data/feature access, but does not yet define a universal wall threshold or make every heatmap feature a mandatory trade filter.

---

### Q13.3 decision recorded during conversation — historical Gold futures tick/order-flow data

**Status: RESOLVED (current state and intended workflow)**

**Current state.** Suitable historical tick/footprint data is not currently available to the project. The user is actively researching possible sources.

**Decision.** If suitable historical data is obtained, begin order-flow backtesting straightaway. Do not wait for an unrelated milestone before starting that work.

**Until data is obtained.** Continue developing the signal-logging/research path and use available recorded data and forward testing where appropriate. Clearly distinguish results from live/recorded-data evaluation from genuine historical order-flow backtests; do not claim historical order-flow validation that has not been performed.

**Still open.** Which data source/vendor will be used, its historical coverage, data format, cost, depth/DOM fidelity, and whether the available data supports the specific footprint and order-book events required by Flow_v2. These remain research items, not settled decisions.

---

### Q13.2 decision recorded during conversation — mandatory signal logging before order execution

**Status: RESOLVED**

Signal logging is a mandatory step before a rule is connected to order execution. Log each rule's raw signal and relevant evidence even when no trade is placed, so predictive value can be evaluated independently of execution quality and trading P&L.

At minimum, preserve:
- Signal timestamp and direction.
- Rule/model identity and output.
- Relevant market context and supporting evidence.
- Subsequent price movement/outcome, including when no order was placed.

The exact event schema, outcome horizons, retention period, and storage implementation remain open for later definition. This decision requires the research/logging path to precede order execution; it does not by itself define the full acceptance thresholds or authorize production.

---

### Q13.1 decision recorded during conversation — staged validation and promotion authority

**Status: RESOLVED (process and decision authority; numerical criteria remain open)**

Use a staged process before a rule is considered for production:

1. **Signal-only research:** Record raw rule signals and supporting evidence without placing orders, so signal quality can be assessed independently of P&L.
2. **Simulated execution:** Test order and position behavior, including entries, fills, slippage, stop/target behavior, and risk controls.
3. **Acceptance review:** Evaluate the accumulated evidence against explicitly documented criteria before considering promotion.

**Acceptance criteria.** Criteria may include minimum trade/sample count, risk–reward (R:R), win rate, expectancy, drawdown, and coverage across different market conditions. Exact metrics, thresholds, sample requirements, and any rule-specific exceptions remain to be defined; do not invent universal values at this stage.

**Promotion authority.** These criteria are decision support, not an automatic gate that promotes a rule. The user retains final authority to decide when a rule advances from one stage to the next and when it is approved for production. Record the evidence and the decision, including any rationale for advancing or holding a rule.

---

## 13. Validation (how any of this will be judged)

- The speaker's results are screenshots on a messaging channel, not a track record. None of it is treated as evidence.
- The existing generic backtest engine runs on 1-minute OHLC and cannot test footprint, imbalance, big-print or delta
  rules (no historical tick data). So these rules can only be evaluated by (a) the recorded tick data we retain (rolling
  7 days), and (b) forward testing on the Sim account.

**Open**
- **Q13.1 — RESOLVED (process):** Require signal-only research, simulated execution, and an acceptance review using documented criteria. Candidate criteria include sample size, R:R, win rate, expectancy, drawdown, and market-condition coverage; exact values remain open. Criteria inform but do not automatically promote a rule: the user has final authority over stage-to-stage promotion and production approval.
- **Q13.2 — RESOLVED:** Mandatory signal-only logging before order execution is connected. Capture each rule's raw signal, timestamp, direction, model output/evidence, market context, and subsequent price movement, even when no trade is placed. Exact schema, outcome horizons, and retention remain open.
- **Q13.3 — RESOLVED (current state/workflow):** Suitable historical Gold futures tick/footprint data is not available yet and is being researched. If obtained, start order-flow backtesting straightaway. Until then, use available recorded data and forward testing as appropriate; the vendor, coverage, format, cost, and DOM fidelity remain open.

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
| Q8.1–8.3 | How confirmations combine | PARTIAL | Q8.1–Q8.3 resolved; exact model conditions and aggregation rules remain to be specified. |
| Q9.1–9.4 | Stop | PARTIAL | Q9.1–Q9.4 module boundaries, candidate sources, risk-management separation, and configurable time exit resolved; candidate ranking/selection algorithm, buffer, exact constraints, and time-exit details remain open. |
| Q10.1–10.4 | Target, partials, re-entry | PARTIAL | Q10.1–Q10.4 target candidate approach, risk-management boundary, dynamic position-management ownership, and configurable exit-specific re-entry resolved; exact target/management rules, sizing, and re-entry conditions/limits remain open. |
| Q11.1–11.4 | No-trade filters | PARTIAL | Q11.1 filter architecture/ownership, Q11.2 chase-risk architecture, Q11.3 configurable session/news blackouts, and Q11.4 configurable trade limits/cooldowns resolved; momentum formula, exact V1 filters/thresholds, blackout details, and limit/cooldown semantics remain open. |
| Q12.1–12.3 | Heatmap and gamma scope/features | PARTIAL | Q12.1 heatmap-in-V1/GEX-deferred, Q12.2 configurable multi-factor wall-detector architecture, and Q12.3 visual-plus-structured heatmap features resolved; exact wall thresholds and feature-combination rules remain open. |
| Q13.1–13.3 | Validation and promotion | PARTIAL | Q13.1 staged validation/user-controlled promotion, Q13.2 mandatory signal logging, and Q13.3 current data limitation/plan to start order-flow backtesting when suitable historical data is obtained are recorded; exact acceptance thresholds and data-source details remain open. |

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

The same aggressive activity can have opposite meanings:
- aggression that produces price progress = successful aggression;
- aggression that fails to produce expected price progress = failed aggression / possible absorption.

**Open:** Define objectively what counts as sufficient price progress or failure after aggression:
- ticks;
- percentage of zone/ATR;
- movement within a time window;
- movement by the end of the bar;
- or another definition.

---

## B. Relationship between failed aggression and absorption — add to §4

### Q4.6 — Is failed aggression the broader concept, with absorption as one manifestation?

The course repeatedly describes absorption as **effort without result**, while several examples describe aggressive orders hitting a location and failing to move price.

**Open:** Decide whether:
1. **Failed aggression** is the broader concept and **absorption** is one measurable manifestation of it; or
2. absorption and failed aggression are separate, independently measurable signals.

This distinction should be settled before implementing either as an entry condition so the system does not accidentally encode the same phenomenon under multiple names.

---

## C. LVN strength/ranking — add to §5

### Q5.8 — How should LVN strength be ranked?

The course describes a deeper/larger LVN as stronger, but does not provide a precise ranking formula.

**Open:** Should LVN strength consider:
- LVN width;
- depth of the volume depression;
- relative volume versus profile average;
- surrounding HVN volume;
- distance/separation between surrounding volume nodes;
- or a combined score?

The preferred direction is a **combined view of width and low-volume characteristics**, but the exact metric and weighting remain open and must be tested.

---

## D. LVN touch and proximity — add to §5

### Q5.9 — What exactly constitutes an LVN touch?

**Open:** Define whether an LVN is considered touched when:
- price enters the LVN range;
- price reaches its boundary;
- price comes within a configurable number of ticks;
- or another condition occurs.

### Q5.10 — Should touch, absorption proximity, and aggression proximity use the same tolerance?

**Open:** Decide whether they share one tolerance or have separate definitions.

**Provisional baseline for testing:** use a shared 2-tick tolerance for all three, then revisit after testing.

---

## E. Trade-idea lifecycle and ownership — add as a new section before entry models

### Trade-idea lifecycle

The underlying strategy creates and owns the trade idea. Order-flow logic monitors and evaluates that idea.

Proposed lifecycle to review:

1. Underlying strategy creates a trade idea.
2. Order-flow monitoring begins.
3. Candidate LVNs are ranked dynamically.
4. Before touch, the selected/highest-ranked LVN may change as rankings update.
5. Touch of the currently selected LVN activates the setup and locks that selected LVN.
6. The system waits for the required confirmation.
7. An order is placed only after confirmation.
8. If the underlying strategy withdraws or invalidates the idea **before order placement**, order-flow monitoring stops immediately.
9. Once an order is placed and the position becomes active, execution and position-management rules own the active trade.

### Q-L1 — What exactly activates the setup?

Is activation specifically the touch of the currently selected LVN, or does another condition need to occur?

### Q-L2 — What exactly constitutes confirmation after activation?

Define the event/evidence that changes an activated setup into an entry-ready setup.

### Q-L3 — At what exact point does ownership transfer?

Confirm that the underlying strategy owns the idea until order placement, and execution/position-management owns the active trade afterward.

---

## F. Activation vs. confirmation vs. execution — add to §8

These states should remain explicitly separate:

**Monitoring**  
→ underlying strategy has created an idea and order-flow is watching.

**Activated**  
→ the selected LVN has been touched/activation condition has occurred.

**Confirmed / entry-ready**  
→ the required order-flow confirmation has occurred.

**Executed**  
→ the order has actually been placed and the position is active.

### Q8.4 — What event changes monitoring into activation?

### Q8.5 — What evidence changes activation into confirmation/entry-ready?

### Q8.6 — What exact event constitutes execution?

This separation prevents “LVN touched”, “confirmation detected”, and “order placed” from being treated as the same event.

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
