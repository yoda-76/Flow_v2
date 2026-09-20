# Decisions

Closed architectural decisions, one per question, each with a date and a
link to the evidence behind it. Same convention as FLOW's and motivewave's
`decisions.md` — current best answer, not final; expect these to get
reopened as the open questions in `README.md` get tested empirically.

These decisions are about **this system**, not about the MotiveWave
platform itself — platform-level decisions/findings live in
[[../../motivewave/docs/dynamic/decisions.md]] and stay there.

Entries amended later keep their original text and carry an explicit
**Amended** line, so the reasoning that was live at the time stays
readable rather than being silently rewritten.

- **D-01** (2026-09-12) — FLOW_V2 is **the real system**, not another
  scoping/experiments repo. `../motivewave` keeps its role as the
  experiments lab (SDK reference docs, throwaway diagnostic studies,
  platform-level findings) and is not superseded — its docs/static and
  findings are not copied here, only referenced. Rationale: keeps exactly
  one place each kind of fact lives — platform facts in motivewave, system
  facts here — rather than two repos slowly drifting out of sync. See
  README.md's "Relationship to the two sibling repos" section.

- **D-02** (2026-09-12) — **All-Java, in-platform runtime.** Data
  ingestion, feature computation, strategy execution and journaling all run
  inside a single deployed MotiveWave Strategy — no external process, no
  IPC. Rationale: lowest latency, direct `OrderContext`/`DataContext`
  access, one runtime to reason about instead of two failure domains. Cost
  accepted: no Python/pandas in the hot path, and strategy iteration means
  a recompile+redeploy cycle rather than editing a script. A bridge to an
  external process was explicitly considered and rejected for now (see
  README.md's "Runtime" discussion) — revisit only if a real strategy needs
  something Java can't reasonably do.

- **D-03** (2026-09-12) — **One host Strategy; the specific strategy is a
  setting, not a separate deployment.** A single `@StudyHeader` Strategy
  ("the runtime") discovers registered `FlowStrategy` implementations and
  the active one is picked via a dropdown in the study settings, with that
  strategy's own params exposed through the same panel. Rationale: adding a
  strategy means one new class + one registry entry — no new StudyHeader,
  no new deployment target, no repeated boilerplate per strategy. Rejected
  alternative: one MotiveWave Strategy class per strategy idea (more
  "native" to the platform, but every strategy repeats deployment
  boilerplate) — may still be worth it later for a strategy that's
  graduated to being a long-term keeper, but not the default.

  **Amended 2026-09-14 by D-18**: the "one host Strategy, strategy chosen
  by setting" half stands. The "that strategy's own params exposed through
  the same panel" half does not — params move to the external config
  store; the panel carries only `strategyId`, `armed` and `mode`.

- **D-04** (2026-09-12) — **Strategies emit `Intent`; only the runtime
  holds `OrderContext`.** A `FlowStrategy` receives a read-only
  `MarketState` snapshot and returns an `Intent` (direction, size, stop,
  target, reason). The runtime translates intent into orders, sizing,
  brackets, session-end flattening, and journaling. Rationale, in order of
  weight: (1) safety — a strategy plug-in cannot place an order even by
  accident, since it's never handed the capability, which structurally
  rules out a repeat of the 2026-09-11 `onEnterNow` incident recorded in
  `../motivewave/docs/dynamic/findings.md`; (2) comparability — every
  strategy shares one execution path, so a difference in results is a
  difference in signal, not in bespoke order handling; (3) testability — an
  `Intent` from a synthetic `MarketState` is assertable in a plain unit
  test with no platform running. An escape-hatch interface for direct order
  control (scaling in/out, bracket ladders) is deliberately not built yet —
  add it only once a real strategy needs it, per an explicit decision at
  that time.

  **Amended 2026-09-14 by D-12, D-13 and D-14**: the capability boundary
  and all three rationales stand unchanged. Three mechanics are re-cut —
  the `MarketState` handed in is a generation-guarded read-through view
  rather than a per-decision immutable copy (D-12), `Intent` is a desired
  end state reconciled idempotently rather than a command to act (D-13),
  and the `OrderContext` holder is specifically `OrderGateway` rather than
  the runtime class at large (D-14).

- **D-05** (2026-09-12) — **Strategies must not import `com.motivewave.*`.**
  Mirrors FLOW's "no Nautilus import in `signal.py`" rule (D-04-equivalent
  there). If a strategy can't be written without reaching into the SDK
  directly, that's a missing feature in `core/`/`price/`/`flow/`, not a
  reason to let the platform leak into strategy code. Keeps strategies
  unit-testable in isolation and insulated from platform changes.

  **Amended 2026-09-14 by D-09**: unchanged as a rule, but no longer
  enforced by convention — the split into two compilation units makes the
  violation a compile error.

- **D-06** (2026-09-12) — **No backtesting for now; forward test only**
  (dry-run → sim → live, gated in that order). Matches MotiveWave's own
  documented recommendation to forward-test before trusting a backtest (see
  `../motivewave/docs/dynamic/findings.md`, `[DOC]` entries on Backtesting
  Limitations) and avoids depending on bar-granularity replay for
  order-flow logic that's inherently tick/DOM-level. Backtest is future
  scope, not rejected outright — see D-07.

- **D-07** (2026-09-12) — **Record now, replay later, with bounded raw
  retention.** The journal records every input event the runtime sees
  (ticks, DOM, bar closes), not just decisions, so a future replay harness
  is a straight-line project rather than something bolted on after the
  fact. Raw tick/DOM-level journal data is retained for a **short rolling
  window only — last 2–3 days**, exact number still TBD once real MBO data
  volume is known; higher-level records (intents, orders, fills, bar
  summaries) are cheap and can be kept longer. Rationale: MBO-granularity
  order-flow data is large enough that unbounded retention is a real
  disk-space decision, not a free default, and forward-testing (this
  project's actual current goal) doesn't need the raw stream kept forever.

  **Amended 2026-09-14 by D-15 and D-16**: "replay later" is no longer
  optional or deferred. Once decisions are made at event level (D-16), the
  decision log alone is no longer human-readable as an explanation of why
  something fired, so replay becomes the primary debugging tool and its
  requirements (D-11) bind the feature layer from day one. Retention
  numbers are unchanged and still to be set from measurement (Q-03).

- **D-08** (2026-09-12) — **External/manual inputs (GEX levels, daily
  bias, key levels, etc.) are a first-class pluggable input**, not
  computed by this system. Held in a small file-based store, hand-edited
  by the user only (same write discipline as `.env`), loaded at session
  start and exposed to strategies as a read-only field on `MarketState`
  alongside live-feed features — a strategy doesn't distinguish where a
  number came from. The journal records which external input was active at
  every decision point so staleness (e.g. a three-day-old GEX level) is
  traceable after the fact rather than silently assumed current. Exact
  file format deferred until a first strategy actually needs one.

  **Extended 2026-09-14 by D-18**: the same store also carries strategy
  params, so there is one hand-edited config mechanism rather than two.

- **D-09** (2026-09-14) — **Two compilation units; the core compiles
  without the SDK on the classpath.** `flow-core` (event types,
  `MarketState`, `Intent`, `FlowStrategy`, registry, `price/`, `flow/`,
  `external/`, `exec/` policy, journal record types, and all strategy
  plug-ins) is compiled with a plain JDK and **no `mwave_sdk.jar` on the
  classpath**. `flow-runtime` (the `@StudyHeader` Study, the SDK→core
  event adapters, `OrderGateway`, `BuiltInVolumeProfile`, the clock)
  compiles against both the SDK and `flow-core`. Deploy concatenates the
  two class trees into `%USERPROFILE%\MotiveWave Extensions\dev\`.
  Rationale: turns D-05 from a rule someone has to remember into a compile
  error, and gives the unit test story for free, since `flow-core` runs
  under a plain JDK with no platform present. Cost accepted: one extra
  `javac` invocation in the build script, and any SDK capability a strategy
  needs must be surfaced as a core type first — which is the intended
  pressure, not a side effect.

- **D-10** (2026-09-14) — **One totally-ordered event stream; single
  writer; funnel-and-drain on platform callbacks.** Every platform
  callback (`Study.onTick`, the `DOMListener` thread, bar hooks, order/fill
  hooks) does exactly one thing: wrap its payload in an immutable event
  stamped with a monotonic sequence number, the platform event time, and
  the local receipt time, then push it onto an MPSC queue. The pipeline
  (ingest → features → triggers → strategy → intent) drains that queue and
  runs on a **single thread**, so exactly one thread ever mutates feature
  state. Rationale: the DOM listener does not run on the study callback
  thread, so without this the feature layer races, and the resulting bugs
  are intermittent and silent rather than loud. A single ordered stream is
  also precisely the artifact a replay needs (D-07, D-11), so determinism
  and thread safety are solved by the same structure. Rejected: processing
  inline on whichever thread arrives (cheapest, produces heisenbugs in the
  flow aggregates). Deferred: a continuously draining worker thread —
  available later as a swap of the drain loop only, because the
  event-stream boundary already exists; see D-17 and Q-02.

- **D-11** (2026-09-14) — **Event time only below `INGEST`; replay must be
  bit-identical to live.** No code below the ingest boundary may call
  `System.currentTimeMillis()`/`nanoTime()` or read any other ambient
  state — time comes off the event. Further, nothing in the feature or
  strategy path may depend on iteration order of a hash-ordered
  collection, thread scheduling, or the identity hash of an object. A
  self-check goes in early and stays: record a session, replay it through
  the identical feature and strategy code, assert the intent sequence is
  identical. Rationale: with event-level decisions (D-16) replay is the
  primary way any "why did it fire there" question gets answered, and a
  replay that diverges from live is worse than none. Built in week one
  because retrofitting it means hunting the three places it isn't true.

  **Completed 2026-09-14 by D-23**: the mechanism that makes this workable
  for time-based logic is the synthetic clock event.

- **D-12** (2026-09-14) — **`MarketState` is a generation-guarded
  read-through view, not a per-decision immutable copy.** Features own
  mutable, incrementally updated internal state (primitive arrays,
  price-keyed by tick offset per D-21). `MarketState` is an interface
  implemented by one reusable object that reads through to those features,
  handed to the strategy read-only and allocation-free. It carries a
  generation counter bumped at each decision point; any accessor called on
  a stale generation throws. `MarketState.freeze()` returns a genuinely
  immutable copy and is used only by the journal and by tests. The raw DOM
  is never exposed — strategies see derived views (bucketed liquidity map,
  imbalance at N levels, resting size at a price, large-order presence).
  Rationale: `../motivewave/docs/dynamic/findings.md` (2026-09-11) records
  ~50,988 `DOMOrder` entries across 20 DOM updates on `@GC`; an immutable
  object graph per decision point is not affordable at that rate. The
  generation guard preserves what immutability was actually protecting
  against — a strategy stashing a state reference and reading it later —
  without the allocation.

- **D-13** (2026-09-14) — **`Intent` is a desired end state, reconciled
  idempotently.** An intent is `{targetPosition, stopPrice, targetPrice,
  reason, strategyId, seq}`, not a command to act. `exec/` diffs it
  against the actual position and live orders and emits the minimal set of
  order actions; re-submitting an identical intent is a no-op. Risk guards
  sit between intent and gateway as an **ordered chain of filters**
  (armed, session open, readiness per D-22, daily-loss limit, size cap,
  rate limit, churn guard per D-19), each recording its verdict to the
  journal so a suppressed trade is visible rather than invisible.
  Rationale: a command model goes ambiguous the moment a fill is partial, a
  stop is already resting, or the same intent repeats on consecutive events
  — all of which are normal at event level. Reconciliation is also what
  makes a restart mid-position recoverable in principle: the desired state
  is re-derivable, a command history is not (though the actual restart
  policy is refuse-to-arm, see D-24).

- **D-14** (2026-09-14) — **`OrderGateway` is the only class that ever
  holds an `OrderContext`, and the override rule is enforced by a test.**
  Package-private constructor, instantiated by the runtime, no reference
  handed anywhere else. In addition, a reflection test walks
  `com.motivewave.platform.sdk.study.Study`'s declared methods, finds every
  method taking an `OrderContext`, and asserts the runtime class overrides
  each one. Rationale: the 2026-09-11 incident
  (`../motivewave/docs/dynamic/findings.md`) was caused by an inherited
  concrete default on a hook nobody had thought about. A test that
  enumerates the hooks from the jar rather than from memory keeps that
  true the day MotiveWave ships a new one in a 7.x update. This is the one
  test that must run against the SDK, so it lives in `flow-runtime`, not
  `flow-core`.

- **D-15** (2026-09-14) — **Two-tier journal, with a decided backpressure
  policy.** Tier one, **decisions**: JSONL, human-readable, long
  retention — intents that differ from the previous intent, risk verdicts,
  trigger reasons, orders, fills, readiness transitions, active external
  inputs and their staleness, plus a periodic heartbeat snapshot.
  Change-only, because at event level a record per decision is no longer
  readable by a human. Tier two, **raw**: the sequenced event stream in a
  compact encoding, rotated per session, short rolling retention per D-07.
  Both are written by a dedicated writer thread off bounded queues; the
  pipeline thread never does I/O. Backpressure: if the raw queue fills,
  **drop and write a gap marker recording the lost sequence range** — a
  replay that silently skipped events is worse than one that refuses to
  run. If the decision queue fills, that is a bug and it fails loudly.
  Every session file opens with a header record carrying strategy id, git
  commit, full param set and instrument/session config, since
  cross-strategy comparison is meaningless without knowing which build
  produced a run.

- **D-16** (2026-09-14) — **Event-level decisions are the destination;
  bar-close is a starting trigger, not the model.** Bar-close-only
  decisions quantise entry timing to the bar and will not work for the way
  this methodology is actually traded, so the core is built for event-level
  from day one. Three things follow and are not deferrable: features are
  **correct after every event** (no work deferred to a bar boundary), the
  hot path **allocates nothing per event**, and the strategy call does no
  I/O, no logging and no string-keyed lookups. Decision cadence is a
  property the strategy declares, not a global constant:

  ```java
  Set<Trigger> triggers();
  // BAR_CLOSE | EVERY_TICK | THROTTLE(millis)
  // PRICE_CROSS(level) | BOOK_CHANGE(minSize)   — registered dynamically
  ```

  Dynamic registration matters: a flat strategy watching one level should
  not be woken 40 times a second, and should be able to swap to
  `EVERY_TICK` once in a position. The runtime owns trigger evaluation and
  journals **why** the strategy was woken, which is half of debugging a
  missed entry. Phasing: the first two or three strategies declare
  `BAR_CLOSE` while the core settles, keeping early journals small and
  readable; moving a later strategy to event level is then one line rather
  than a migration. Rationale for building the mechanism now and using it
  later: the always-consistent features and allocation discipline are the
  expensive-to-retrofit half, and they touch every feature's internal
  representation.

- **D-17** (2026-09-14) — **Deciding and acting are separated by an
  explicit flush point.** The pipeline always produces intents at event
  level and puts them on an intent queue; a separate flush step drains
  that queue through the risk chain into `OrderGateway`. Where the flush
  runs is the **only** thing that varies with the unresolved
  `OrderContext` retention question (Q-02): if a retained context is valid
  and callable off-thread, flush inline for full event-level latency; if
  it is retainable but not thread-safe, or not retainable at all, flush at
  the top of the next platform callback. Strategies, features and intent
  types are identical either way. The gap between intent sequence number
  and execution sequence number is journaled on every trade, so the cost
  of a lagging flush is measured rather than argued about. Rationale: this
  keeps Q-02 off the critical path — the experiment can resolve whenever
  it resolves, and one boundary absorbs the answer.

- **D-18** (2026-09-14) — **Strategy params live in the external config
  store, not in MotiveWave's settings panel.** The panel carries
  `strategyId`, `armed` and `mode` only; everything else (per-strategy
  params, risk defaults, session config) is read from the same
  hand-edited, user-owned file store as D-08's external inputs.
  Rationale: this sidesteps the open question about whether one settings
  panel can conditionally show only the selected strategy's params (now
  closed by being routed around), uses one config mechanism instead of two,
  makes params journalable as data rather than scraped from a UI, and turns
  a param sweep into a file edit rather than a recompile-and-redeploy. Cost
  accepted: the settings-UI conveniences (type-checked widgets, validation,
  defaults in the GUI) are lost, which for a forward-testing rig is a small
  loss against reproducibility.

- **D-19** (2026-09-14) — **Churn and lag guards live in `exec/`, not in
  strategies.** Two guards, both journaled when they bite. **Churn**:
  minimum dwell time in a position and a max-reversals-per-session cap —
  desired-state reconciliation (D-13) already makes a repeated identical
  intent a no-op, but it does not stop long/flat/long across three
  consecutive ticks. **Lag**: the runtime watches drain-queue depth and
  per-event processing time; if lag crosses a threshold it **disarms and
  journals the reason** while continuing to compute, so the session is
  still diagnosable. Rationale for both being core rather than
  per-strategy: comparability (D-04 rationale 2) is lost the moment each
  strategy implements its own throttling. Rationale for disarm over drop:
  dropping events corrupts feature state silently, and the failure mode
  without the guard is a strategy trading on a book that is seconds stale.

- **D-20** (2026-09-14) — **Tests are event sequences, and recorded
  sessions become fixtures.** `flow-core`'s test tree gets an
  event-sequence DSL from the start — feed a sequence of synthetic events,
  assert the sequence of intents:

  ```java
  seq().trade(4391.2, 3, ASK)
       .bookPull(4391.5, 120)
       .trade(4391.4, 8, ASK)
       .expectIntent(LONG, 1);
  ```

  Interesting slices cut from a raw session log (30 seconds around a real
  setup) are committed as fixtures and asserted against permanently.
  Rationale: at event level "one hand-built state in, one intent out" is
  no longer the unit of behaviour; a sequence is. This is also the
  regression suite that makes D-11's replay-equivalence check useful, and
  it only exists if the raw log format is stable early.

- **D-21** (2026-09-14) — **Prices are integer tick offsets throughout the
  core.** Every price below `INGEST` is an integer offset from a session
  anchor, with tick size and anchor supplied by instrument config.
  Conversion to and from decimal happens only at the ingest and journal
  boundaries. Rationale: every feature does price-keyed array indexing,
  level equality comparison and profile bucketing on every event. Integers
  make all three exact and fast; doubles make level equality a tolerance
  question permanently and force boxed or hashed lookups in the hot path,
  which D-16's allocation rule forbids. This choice touches every feature's
  internal representation, so it is settled before the first feature exists
  rather than after.

- **D-22** (2026-09-14) — **Readiness is per-feature, and the runtime
  refuses to arm until every feature the active strategy uses is ready.**
  There is no single global warmup period. Each feature declares whether
  it is warmable and what history it needs; readiness transitions are
  journaled, and if arming is blocked the journal names the feature
  holding it up. Four classes:

  1. **Ready immediately** — everything DOM-derived (liquidity map, book
     imbalance), since a full book snapshot arrives on subscription; and
     per-bar delta/footprint for bars beginning after attach.
  2. **Warmable from historical bars** — market structure and swings,
     session and prior-session levels, ATR, overnight high/low, VWAP,
     volume profile. Tick-level history is not required for these.
  3. **Warmable only from tick history** — anything needing per-trade
     aggressor detail from before attach, e.g. session cumulative delta.
  4. **Forward-only** — anything needing DOM *history* (order resting
     time, book-change rates, liquidity-pull frequency). Historical
     order-book reconstruction is permission-gated on our Rithmic tier per
     `../motivewave/docs/dynamic/findings.md`, so these are ready only
     after N minutes of live running.

  Two cases that belong in the readiness check and are easy to miss:
  **relative thresholds are a warmup** (a fixed "big trade = 50 contracts"
  needs nothing; "95th percentile of the last 30 minutes" needs a filled
  window and a not-ready state — same for delta z-scores, normalized book
  imbalance, adaptive absorption thresholds), so each feature declares
  whether its threshold is fixed or relative; and **the partial bar at
  attach** is incomplete for every bar-scoped feature, so it is either
  backfilled from tick history or marked invalid with triggers skipping it.
  Rationale: starting mid-session makes several of these silently wrong
  rather than absent, which is the dangerous failure mode — an unwarmed
  ATR, for instance, is an unwarmed position size.

  Note on VWAP: computed from bars it is an approximation (typical price ×
  volume per bar) rather than true tick-weighted VWAP. On 1-minute bars the
  error is small and accepted; the journal records which method produced
  the value so the two are comparable if it ever matters.

  **Amended 2026-09-15 by D-36 and `sdk-capability-findings.md`
  (`../motivewave/motivewave-studies` E-10 inventory)**: two corrections
  to class 2 and the VWAP note above, both now closed with live/source
  evidence rather than assumption. First, **volume profile moves from
  class 2 to class 3** — the SDK's `VolumeProfile` engine D-36 adopted is
  fed by ticks (`onTick`), not bars, so warming it at startup means
  replaying historical ticks through it, the same as session cumulative
  delta already required, not just loading bar history. Second, the VWAP
  note's "bar approximation, typical price × volume" premise was a
  guess about what we'd end up building — the actual published
  MotiveWave `VWAP.java` source is **genuinely tick-weighted**
  (`totalPrice += tick.getPrice() * tick.getVolumeAsFloat()`, fed via
  `instr.forEachTick(...)`), so once VWAP is adapted from that source
  (per D-36's sibling conclusion for volume profile — reuse over
  rebuild) it is also **class 3**, not class 2, and the approximation
  caveat doesn't apply: matching the chart's VWAP line exactly is the
  expected outcome, not a known gap.

- **D-23** (2026-09-14) — **Time is an event: the runtime injects
  synthetic `ClockEvent`s into the sequenced stream.** D-11 forbids
  reading the wall clock below ingest and D-10 means the pipeline only
  advances when the drain loop runs, so without this, time would freeze for
  the feature and strategy layers whenever the book goes quiet, and a rule
  like *flat by 15:59* would never fire. The runtime sits above ingest,
  where the clock is allowed, and injects a `ClockEvent` on a fixed cadence
  (100ms); it carries a sequence number and is recorded in the raw journal
  like any other event. Replay feeds recorded clock events back in order
  rather than generating fresh ones from the replay machine's clock, which
  is what keeps D-11's equivalence property true for time-based logic. Two
  mechanics: clock events do not wake a strategy unless it declared a
  time-based trigger (otherwise every strategy is called ten times a second
  while flat), and `MarketState` exposes exchange event time and local
  clock time separately — time-of-day rules read the latter, and the gap
  between the two is the feed-latency measurement, journaled.

- **D-24** (2026-09-14) — **On startup or reload with a live position or
  resting orders, refuse to arm.** The runtime journals what it found and
  waits; it neither adopts the position nor flattens it. The user clears it
  manually before arming. Rationale: adopting a position whose reasoning no
  longer exists in memory is how a small loss becomes a large one, and
  auto-flattening is an order placed as a side effect of startup, which
  `CLAUDE.md`'s hard rule forbids. Note that this is a policy choice, not a
  capability limit — D-13's reconciliation would make adoption technically
  straightforward, which is exactly why the refusal needs to be explicit.

- **D-25** (2026-09-14) — **Exceptions are caught at the pipeline
  boundary: disarm, journal, keep ingesting.** A feature or strategy that
  throws does not propagate into a MotiveWave callback. The runtime
  disarms, journals the exception together with the sequence number of the
  event that caused it, and continues consuming events so the session stays
  diagnosable and replayable. Rationale: an exception escaping into a
  platform callback risks MotiveWave deactivating the study or flooding its
  output log, and either way the evidence needed to diagnose the fault is
  what gets lost. Uniform across all modules rather than per-module.

- **D-26** (2026-09-14) — **Volume profile is built behind a provider
  interface with two implementations running simultaneously.**
  `VolumeProfileProvider` in `flow-core`, implemented by
  `CustomVolumeProfile` (built from our own tick stream, in `flow-core`)
  and `BuiltInVolumeProfile` (reading MotiveWave's own study, necessarily
  in `flow-runtime` since strategies cannot import the SDK). Both write
  POC/VAH/VAL to the journal on a fixed cadence; the active strategy reads
  exactly one, named in config. Rationale: custom-versus-built-in
  comparison becomes an offline diff of two journal columns rather than a
  separate exercise, and the strategy is insulated from which one is in
  use. Additionally, the value-area algorithm is **pluggable inside
  `CustomVolumeProfile`** and the journal records which one produced the
  numbers — FLOW's live volume profile found POC holding up well while
  VAH/VAL/HVN/LVN showed real inaccuracy on the same data, with multiple
  value-area methods still untested, so that is the known disagreement
  point and the built-in is the reference FLOW never had. Whether the
  built-in profile is readable from another study at all is unverified —
  see Q-08; if it is not, the fallback is journaling our values on a
  cadence and comparing against the chart by hand, which loses the
  automation but not the comparison.

  **Amended 2026-09-15 by D-36**: the "two competing implementations"
  premise is retired — there is one engine, the SDK's own
  `sdk.profile.VolumeProfile`, confirmed live to produce accurate output
  (matching the chart once scoping is apples-to-apples). No
  `CustomVolumeProfile` gets built; `BuiltInVolumeProfile` in the sense
  meant here (reading the built-in *study's* rendered output) was
  separately confirmed impossible by D-32/Q-08 and was never the actual
  mechanism anyway — D-36 uses the SDK's *engine class*, fed by our own
  ticks like the abandoned `CustomVolumeProfile` would have been, not the
  built-in study. The pluggable-value-area-algorithm idea is also moot:
  E-6 found `VAMethod` doesn't exist in our jar at all, so only
  `getValueArea(double)` (one fixed algorithm) is available regardless —
  FLOW's own VAH/VAL/HVN/LVN disagreement (noted above) is a live
  question worth re-checking against *this* engine specifically, not
  assumed to reproduce, since FLOW never had this SDK's engine to lean
  on.

- **D-27** (2026-09-14) — **The reusable library is the entry evaluator,
  not the setup logic.** Nearly every strategy here is expected to take the
  form *area of interest* (from market structure, GEX levels, or another
  concept) *plus an order-flow entry* inside it. So absorption, imbalance
  stacking, sweep-and-reclaim and similar entry evaluators live in `flow/`
  and are composed by every strategy, while the zone logic stays per
  strategy. This is also why proximity triggers (`PRICE_CROSS`,
  `BOOK_CHANGE`) are the primary wake-up mechanism rather than
  `EVERY_TICK`: a strategy registers interest in a zone, sleeps until price
  is near it, and swaps to event-level attention once the zone is in play —
  which is what keeps D-16's event-level cadence affordable. A
  `ZoneEntryStrategy` base class encoding the two-phase shape (zone logic
  at a slow cadence, entry logic at event level) is the obvious eventual
  abstraction and is deliberately **not** written until two real strategies
  have shown the same shape, per the same rule FLOW's
  `flow/backtest/common/` follows.

- **D-28** (2026-09-14) — **Q-04 closed: `@GC` first, 1-minute bars by
  default, and a strategy may declare the bar interval(s) it needs.** The
  first forward-tested instrument is `@GC`, matching every experiment run
  so far in `../motivewave`. The walking skeleton's default bar interval is
  **1-minute**, matching the VWAP/ATR approximations D-22 already assumes.
  A strategy is not locked to the default: it may declare a required bar
  interval alongside its triggers (D-16), and the ingest layer builds bars
  at each interval actually declared rather than assuming one global
  timeframe. Rationale: this is the same declarative pattern D-16 already
  established for trigger cadence, so extending it to bar interval adds no
  new mechanism — only a second field alongside `Set<Trigger> triggers()`.

- **D-29** (2026-09-14) — **Q-05 partially closed: 24h session, not
  RTH-only; flatten at session end by default.** The session runs the full
  electronic day rather than RTH-only. Positions are flattened at session
  end by default (no overnight carry unless a strategy explicitly opts in
  later). What is **not** yet decided: where the sub-session boundaries
  fall inside that 24h day (e.g. Asia/London/NY splits) and whether
  per-session counters (D-19's reversal cap, D-21's price anchor, the
  journal's session-file boundary) reset at each sub-session or once daily.
  Spun off as **Q-09** below rather than left dangling on Q-05, since the
  direction (24h, flatten-at-end) is settled and only the internal
  boundary is outstanding.

- **D-30** (2026-09-14) — **Q-06 closed: fixed contracts to start; daily-
  loss kill switch on realized + open PnL; exact value left as config, not
  a hardcoded decision.** Sizing is a fixed contract count per trade, not
  risk-per-trade off stop distance — simplest for the walking skeleton and
  keeps early cross-strategy comparability (D-04's rationale 2) from being
  confounded by a sizing model no strategy has been forward-tested against
  yet. Risk-per-trade sizing is not rejected, just deferred until fixed
  sizing has produced a comparable baseline. The daily-loss kill switch is
  evaluated on **realized + open (mark-to-market)** PnL, consistent with
  D-19's principle that a guard should catch a bad trade still running, not
  only a closed one. The exact dollar/percentage threshold is deliberately
  **not** recorded here — it lives in the external config store (D-08,
  D-18) as a per-session value, to be set before the first sim session
  rather than frozen into `decisions.md`.

- **D-31** (2026-09-14) — **Q-02 stage (a) closed: a retained
  `OrderContext` is valid and safely callable (read-only) from a thread
  other than the one that supplied it, with a stable identity across
  calls.** Live evidence from `ContextRetentionProbe.java`
  (`../motivewave/docs/dynamic/findings.md`, 2026-09-14): the same
  `System.identityHashCode(ctx)` was logged across `onActivate` (a
  platform thread), `onBarClose` (a different platform thread), and
  repeated `getPosition()`/`getCashBalance()` polls from this probe's own
  background timer thread — no exceptions, no state drift. This is a
  single long-lived handle, not a fresh wrapper per callback, at least for
  reads. **Does not extend to write calls** (`buy`/`sell`) — only
  read-only methods were exercised, by design (Q-02(a) was specified as
  zero-risk). D-17's flush point therefore still can't assume off-thread
  *write* safety from this evidence alone: its safe default (flush at the
  top of the next platform callback) stands unless Q-02 stage (b) — an
  actual order placement test, order-placement risk, explicit
  in-the-moment confirmation required — is deliberately run later. Stage
  (b) is no longer *required* to unblock D-17 (a safe default already
  exists); it would only add the option of inline flush for lower
  latency, not resolve a blocker.

  **Process note, worth keeping:** getting here took two wrong turns on
  live MotiveWave activation UI quirks unrelated to Q-02 itself — a
  StudyHeader flag combination (`autoEntry=false` + `manualEntry=false`)
  that produces a dead-end "Please Choose Long or Short" dialog with no
  actual chooser. Full trail and the standing fix
  (`autoEntry=true, manualEntry=false, supportsPositionType=false`) are in
  `../motivewave/docs/dynamic/findings.md`, 2026-09-14, so this doesn't
  need re-diagnosing on the next diagnostic strategy.

- **D-32** (2026-09-14) — **Q-08 closed: `BuiltInVolumeProfile` is not
  automatic — the built-in Volume Profile study does not expose POC/VAH/
  VAL to other studies.** Live check on a `@GC` chart
  (`../motivewave/docs/dynamic/findings.md`, 2026-09-14): an EMA added
  alongside the built-in Volume Profile study offers no Volume-Profile-
  derived option in its Input dropdown, and right-clicking directly on the
  Volume Profile plot gives a plot-specific context menu with no
  Create/Add Alert entry at all (unlike right-clicking empty chart space,
  which does offer a generic price alert) — so neither the Export Value
  mechanism nor the Study Alert mechanism reaches it. D-26's stated
  fallback is now the plan, not a contingency: `CustomVolumeProfile`
  journals its own POC/VAH/VAL on a cadence, compared against the chart by
  hand. No further Q-08 work needed.

- **D-33** (2026-09-14) — **Q-01 closed: ticks belonging to a bar
  reliably arrive before `onBarClose` fires for it, on this feed.** Live
  evidence (`../motivewave/docs/dynamic/findings.md`, 2026-09-14):
  `OrderingProbe.java` logged a monotonic sequence on every tick and every
  bar close on a live `@GC` 1-min chart. Across 2,738 ticks and 12 bar
  closes, zero cases of a tick arriving (by sequence) after a bar close
  whose own exchange timestamp put it at or before that close. Less
  load-bearing than it would have been pre-D-10 (the sequencer already
  records actual arrival order rather than assuming one), but confirms a
  bar-close-triggered aggregate can be trusted at the moment it fires.
  Secondary observation carried into the findings (not a decision by
  itself): `recvTime` ran ~668ms *before* `tickTime` throughout the run —
  worth resolving (clock skew vs. `tick.getTime()` semantics) before
  trusting any absolute feed-latency number D-23's dual-clock design
  produces.

- **D-34** (2026-09-14) — **Q-09 closed: Asia/London/NY sub-session
  split, informational grouping only — counters reset once per full 24h
  day, not per sub-session.** Sub-session boundaries (CT, matching
  COMEX/`@GC` convention): **Asia** 18:00–03:00, **London** 02:00–08:00
  (slight overlap with Asia's tail is normal for this convention), **NY /
  RTH** 08:20–13:30 (the existing `@GC` RTH window per
  `../motivewave/CLAUDE.md`'s prior findings). The gaps this leaves
  (08:00–08:20, 13:30–18:00) are not assigned a sub-session label — a
  tick in a gap is just "24h, no sub-session," not an error. These labels
  exist for journal/analysis grouping (e.g. "did this strategy only work
  during London") — they do **not** create separate reset boundaries.
  D-19's reversal cap, D-21's session price anchor, and the journal's
  session-file boundary all still reset exactly once per full 24h day
  (D-29's existing boundary), not at each Asia/London/NY transition.
  Rationale: three independent reset boundaries a day is meaningfully more
  moving parts (three price anchors, three journal files, three reversal
  counters) for a forward-testing rig that doesn't yet have evidence any
  of today's strategies behave differently enough by sub-session to need
  that granularity — revisit once a real strategy's results actually show
  a session-dependent pattern worth isolating.

- **D-35** (2026-09-14) — **Q-03 answered empirically; D-07's retention
  figure is closed for ticks + top-of-book DOM, but full per-order DOM
  detail reopens the question rather than closing it.** Two live captures
  on `@GC` (`../motivewave/docs/dynamic/findings.md`, 2026-09-14):
  ticks + top-of-book DOM run **~28.0 MB/hour raw, ~1.35 MB/hour gzipped**
  (~20.7x) — for a 2-3 day window, **~1.4–2.0 GB raw, ~65–97 MB gzipped**.
  **Compressed JSONL is clearly sufficient for this tier; no binary
  encoding is needed for it.** Full per-order (`DOMOrder`) detail,
  measured directly (not the old 20-update sample) at **2,747.6
  orders/update**, extrapolates to **~31.5 GB/hour raw, ~3.89 GB/hour
  gzipped** (only ~8.1x, since order IDs don't compress well) — **~1.5–2.3
  TB raw, ~187–280 GB gzipped** for the same 2-3 day window. That
  measurement used naive full-snapshot-per-update logging (the whole book
  re-logged every update, not a delta), so it's an upper bound, not a
  final number — a delta/incremental encoding is unmeasured but plausibly
  much smaller.

  **Not decided here, flagged for a follow-up decision:** whether the raw
  journal's DOM tier retains full per-order detail at all. D-12 already
  decided strategies never see raw DOM, only derived views — this finding
  raises the same question one layer down, for what the *raw journal*
  itself retains for replay (D-11). The one part of the design that
  plausibly needs order-ID-level history is D-22's forward-only feature
  class (order resting time, liquidity-pull frequency) — worth checking
  whether a narrower, shorter, or delta-encoded capture satisfies that
  specifically, rather than applying a blanket 2-3 day full-detail policy
  that costs terabytes. Three live options once that's decided: (a)
  raw journal DOM tier stays top-of-book-only, full per-order detail never
  retained past what's needed in memory for live feature computation; (b)
  a delta encoding is built and measured before committing to a retention
  window for it; (c) a much shorter full-detail window (hours, not days)
  is accepted as the cost of keeping D-22's class-4 features replayable.

- **D-36** (2026-09-15) — **Do not build a custom volume profile from
  scratch: the SDK's `com.motivewave.platform.sdk.profile.VolumeProfile`
  engine, wrapped directly, is confirmed accurate and is what
  `flow-runtime` should use.** Closes the open question `docs/dynamic/
  sdk-capability-findings.md` was written to answer, now with live
  evidence rather than desk research. Sequence of findings, full detail
  in `../motivewave/docs/dynamic/findings.md` 2026-09-14/15 `[LIVE]`
  entries:
  1. `VolumeProfile`/`VolumeRow`/`SummaryProfile`/`AggregateFilter`/
     `DataSeries.calcSwingPoints` all compile and run against our actual
     jar (E-1), with one unrelated constructor mismatch on the
     out-of-scope `TPOProfile`.
  2. E-10 found these classes have **zero usage anywhere in MotiveWave's
     own 339-file published studies repo** — no reference implementation,
     no proof of a safe usage pattern going in.
  3. Despite that, a live session-scoped `VolumeProfile` fed by ticks
     (`SdkCapabilityProbe.java`) produced POC/VAH/VAL that **closely
     matched the chart's own built-in Volume Profile study** once both
     were scoped to the same accumulation window (the one mismatch found —
     VAH off by 0.3 — traced to the built-in study's "Use Historical Bars"
     option extending its lookback, not to any flaw in the engine).
  4. Heap growth stayed modest with no exceptions across everything run
     so far (E-3, partial — see `sdk-capability-findings.md`'s live-status
     table for what's not yet a clean measurement).
  **Consequence — amends D-26**: D-26's "two competing implementations"
  framing (`CustomVolumeProfile` vs `BuiltInVolumeProfile`) is retired.
  There is one engine (the SDK's `VolumeProfile`), owned and fed in
  `flow-runtime` (per D-05/D-09, since it's an SDK type `flow-core` can't
  import), exposed to `flow-core` through a read-only view interface
  (`VolumeProfileView`, in our own vocabulary, integer ticks per D-21).
  The former "custom vs built-in" comparison work is replaced by a
  **settings-parity check** (matching `rangeTicks` and value-area % to
  whatever the strategy actually needs) — not an implementation choice.
  **Same engine covers footprint** (§2.2 of `sdk-capability-findings.md`:
  a footprint row is a bar-scoped `VolumeProfile` with `rangeTicks=1`) and
  **delta/cumulative delta** (`VolumeProfile.getTotalDelta()` etc.) — so
  this decision closes the "build vs reuse" question for those two as
  well, not just volume profile itself. **Consequence for D-22**: volume
  profile moves from readiness class 2 (warmable from historical bars) to
  class 3 (warmable only from tick history) — the SDK engine is fed by
  ticks (`onTick`), not bars, so warming it at startup means replaying
  historical ticks through it, not just loading bar history.
  **Still open, not resolved by this decision**: the liquidity heatmap
  (built-in `Order Heatmap`/`DOM Power` visual quality vs. a custom
  MBO-fed one — `sdk-capability-findings.md` §2.8 — not yet checked
  against real output) and Q-10 (raw journal DOM retention policy). Big
  trades (`AggregateFilter`) has the same zero-reference-usage caveat as
  point 2 above but real live confirmation of its repeat-emission
  behavior (E-5) — promising, not yet to the same confidence level as
  volume profile specifically.

- **D-37** (2026-09-15) — Closing out the volume-profile half of D-36's
  audit before starting on footprint. Four calls, made directly by the
  user, not from a new experiment:
  1. **Settings parity (`rangeTicks`, value-area %) accepted as close
     enough.** E-2's match against the chart is "close, not verified
     identical" (see D-36) — not pursuing exact parity further for now.
  2. **Partial-bar-at-attach: mark invalid, do not backfill.** The first
     bar-scoped (footprint) profile after a study attaches (or redeploys)
     is excluded/skipped rather than reconstructed from historical ticks —
     building starts clean from the next full bar close. Resolves the
     open item in `todo.md` §4 ("Partial-bar-at-attach handling: backfill
     or mark invalid") in favor of the simpler option.
  3. **Historical tick-replay warm-start deferred, not being built now.**
     `sdk-capability-findings.md` §6.5's open question (readiness class 3 —
     whether replaying a session's ticks through `VolumeProfile` at startup
     is fast enough) is left unanswered on purpose; volume profile starts
     forward-only from attach time until this is revisited.
  4. **E-3 (memory/throughput) gets one more pass**: a clean single-
     instance 10-minute run of `SdkCapabilityProbe`, since every prior run
     was contaminated by duplicate/zombie instances (see D-36 point 4 and
     the old `sdk_capability_probe.log`, archived
     2026-09-15 as `sdk_capability_probe.log.bak.20260915_201849` — it
     showed two instances logging concurrently, one spinning E7_SWINGS/
     E4_BAR_CLOSE lines ~100ms apart, clearly a zombie). This is in
     progress this session, not yet closed.
  **Sequencing set for what comes next**: footprint (`VolumeProfileView`
  row-level parity, E-4) is the next real build step, but no code gets
  written for it yet. First: finish the E-3 clean measurement (point 4).
  Then: a features/triggers discussion with the user — what
  `VolumeProfileView` actually needs to expose and what should trigger off
  it — **before** any implementation and **before** footprint
  experimentation starts. The user's read is that experimentation on
  volume profile proper is otherwise done.

- **D-38** (2026-09-15) — **`VolumeProfileView` event/trigger design: two
  general primitives, not five bespoke events.** Closes the
  features/triggers discussion D-37 required before any footprint or
  `VolumeProfileView` code. The user's five examples (POC/VAH/VAL
  touch/cross, LVN/HVN enter/leave) generalize to:
  - **`NamedLevel`** — a single moving price (POC, VAH, VAL today;
    extensible later to prior-session POC/VAH/VAL, VWAP, etc. without new
    plumbing). Events: `TOUCH`, `CROSS_ABOVE`, `CROSS_BELOW`.
  - **`NamedZone`** — a moving price range (LVN/HVN clusters today;
    extensible to the value area itself, etc.). Events: `ENTER`, `LEAVE`,
    `TOUCH` (boundary touch without necessarily entering).

  Since prices are integer tick offsets throughout the core (D-21), touch/
  cross comparisons are exact — no float-tolerance question.

  **Cross cause: either side moving triggers it.** POC/VAH/VAL/LVN/HVN
  recompute on their own, independent of price. A `CROSS` fires on any
  flip in relative position between price and the level, regardless of
  which one actually moved — chosen over "price-moved-only" (cleaner
  semantics but loses "the market's balance point just shifted past a
  stationary price," which is real information) and over tracking both as
  distinct event kinds (more expressive, not enough evidence yet that the
  distinction earns its complexity).

  **Two separate cadences, not one.** Zone/level identity (see below) has
  to be computed centrally, once, at a shared recompute cadence — if two
  strategies checked at different rates they could disagree about whether
  a zone still exists. That shared recompute cadence is also the natural
  hook for D-37's still-open memory-rotation fix (E-3 found the SDK engine
  unsafe to keep alive unbounded — recompute time is a candidate rotation
  point). Separately, each strategy declares its own wake/check cadence
  exactly like D-16's existing `EVERY_TICK`/`THROTTLE(millis)` pattern — a
  strategy can't see data fresher than the last shared recompute regardless
  of its own declared cadence.

  **Architectural fit**: these are new dynamic trigger types alongside
  D-16's existing `PRICE_CROSS`/`BOOK_CHANGE` (dynamically registered per
  strategy, same rationale — a strategy sleeps until relevant, not woken
  on every tick), not a separate event bus.

  **Zone identity is persistent, tracked across recomputes** — the more
  expensive of the two options considered, deliberately chosen because
  D-39's LVN/HVN ranking needs a stable ID to accumulate touch outcomes
  against; the simpler stateless-boolean alternative (just "is price
  inside any current LVN/HVN row") can't support that.

  Matching algorithm (price-range overlap between successive same-type
  clusters — LVN↔LVN, HVN↔HVN only):
  - One new cluster overlaps exactly one old cluster → same ID, bounds
    updated silently (not an event on its own).
  - **Split** (one old cluster's range now overlaps 2+ new clusters): the
    new cluster with the largest overlap keeps the old ID; the rest are
    new IDs.
  - **Merge** (2+ old clusters now overlap one new cluster): the new
    cluster keeps the ID of whichever old cluster it overlaps most; the
    others get `ZONE_DISSOLVED` — deliberately distinct from a normal
    `LEAVE` (if price was inside one of those at the time, that's
    inconclusive evidence, not a reversal or a pass-through — see D-39).
  - No overlap in either direction → plain dissolve / plain appear.
  - "Largest overlap wins" is flagged explicitly as an arbitrary tie-break,
    not a principle — open to revision, not load-bearing on anything else.

  IDs reset each session (D-29's boundary), and persist across a profile
  rotation (D-37's memory fix rotates the underlying SDK object
  periodically; that's an implementation detail the zone-identity layer
  must stay continuous through).

  **Boundary-flicker debounce**: a `LEAVE` isn't finalized until price
  moves at least one full row-width past the boundary; a fast re-entry
  within that distance continues the same open trial rather than starting
  a new `ENTER`/`LEAVE` pair. Prevents both trigger-spam (D-16's "should
  not be woken 40 times a second" concern) and noisy touch/outcome counts
  (D-39).

- **D-39** (2026-09-15) — **LVN/HVN reversal ranking: two layers, blended,
  not a single score.** The user wants existing LVNs (and by extension
  HVNs) ranked by likelihood of reversing price — explicitly **not
  skippable down to a lighter version**; this is expected to be where the
  actual trading edge comes from, so the track-record layer is in scope
  now, not deferred.

  **Layer 1 — intrinsic, available the instant a zone appears, no history
  needed:**
  - Void depth: the existing `lvn_threshold` classification ratio, used
    continuously (`1 - volume/rolling_avg`) instead of as a binary flag.
  - Void width: row-count/tick-span of the contiguous cluster.
  - Shoulder strength: volume of the flanking HVN peaks relative to the
    void — how sharp a cliff it is.
  - Position relative to POC/value area: inside vs. outside, distance
    from POC.
  - Confluence: count of independent structural levels within a few
    ticks — prior-session POC/VAH/VAL, overnight high/low, a swing point
    (`calcSwingPoints`, live-proven per E-7), a big-trade cluster
    (`AggregateFilter`, E-5), round numbers.
  - Formation delta: aggressive one-sided volume vs. quiet/thin, from
    `VolumeRow.getTotalDelta()`/imbalance flags (already exposed, no new
    SDK surface needed).
  - Recency: session-relative age since the zone first appeared.

  **Layer 2 — track record, reusing D-38's `ENTER`/`LEAVE` events
  directly, no new detection mechanism:** each `ENTER` opens a trial on
  that zone ID; the matching `LEAVE` resolves it — same-side exit =
  `REVERSED`, opposite-side exit = `PASSED_THROUGH`, zone dissolves while
  price is inside = `INCONCLUSIVE` (excluded from the bounce-rate
  calculation entirely — it's not evidence either way). D-38's boundary-
  flicker debounce applies here too, so the trial count isn't polluted by
  edge noise. Per-zone record: `touchCount`, `reversedCount`,
  `passedThroughCount`, running `bounceRate`, max penetration depth per
  touch (how far price got in before reversing — a different signal than
  reversing right at the edge).

  **Combined score — Bayesian-style blend, solves cold start:**
  `score = (priorWeight × layer1Score + touches × empiricalRate) /
  (priorWeight + touches)`. A fresh zone with zero touches ranks on
  structure alone (Layer 1 as prior); as touches accumulate, the score
  shifts toward the actual observed outcome. `priorWeight` (how many
  "virtual touches" the prior counts as) is a tuning constant, not a
  design question.

  **Scope: session-only, in-memory, resets with zone IDs each session
  (D-29's boundary) — deliberately, not by default.** Cross-session
  persistence (this price level has reversed 8 of the last 12 times this
  month) was considered and explicitly deferred, not ruled out: it needs
  a price-level proximity match across days (VP itself resets daily, so
  "the same zone" tomorrow isn't an identity match) and a persistence
  layer that doesn't exist anywhere in this project yet. Revisit once
  session-only is proven out.

  **Explicitly a heuristic rank, not a calibrated probability**, until
  checked against real outcomes — same v1-then-revise-by-evidence pattern
  as D-22/D-23 elsewhere in this project. D-40's journal is what makes the
  calibration check possible later.

- **D-40** (2026-09-15) — **Zone lifecycle events are a new record kind in
  D-15's existing decisions-tier journal, not a new format.** The user
  wants a persistent audit history of zone events — "likely causality...
  nothing decisive, just a history of what happened" — but explicitly
  ruled out raw tick events (too much, uninformative on its own) and plain
  English (unreplayable, not analyzable). D-15 already defines exactly
  this shape of tier: JSONL, change-only, human-readable, long retention.
  Zone lifecycle events fire only on actual transitions (`CREATED`,
  `ENTER`, `LEAVE`, `DISSOLVED`, `MERGED`, `SPLIT`) — rare relative to
  ticks, so it stays small by construction, not by pruning after the fact.

  Record shape, one JSON line per event: `ts`, `seq`, `zoneType`
  (LVN/HVN), `zoneId`, `eventType`, `priceRange` (integer ticks, D-21),
  `side` (`ENTER`/`LEAVE`), `outcome` (`LEAVE` only:
  `REVERSED`/`PASSED_THROUGH`/`INCONCLUSIVE`), `penetrationDepth`
  (`LEAVE` only), `touchCount`, `bounceRate` (running values at time of
  event), `layer1Score` (intrinsic composite at time of event),
  `confluence`, `relatedZoneIds` (`MERGED`/`SPLIT` predecessors/
  successors).

  **Deliberate design point**: pairing `layer1Score` with `outcome` at
  each event is what turns this from "just a history" into a labeled
  dataset — Layer 1 features alongside what actually happened, timestamped
  and replayable (ties into D-11's replay requirement, which this journal
  tier already exists to support). This is the mechanism for D-39's
  explicitly-deferred v2: checking, and eventually recalibrating or
  replacing, the hand-picked Layer 1 weights against real outcomes once
  enough sessions accumulate. Records are factual only — no verdict field,
  nothing decisive, per the user's own framing.

  **Sequencing**: this closes the features/triggers discussion D-37
  required. Next is implementation — `VolumeProfileView`/footprint
  (D-36/D-37) plus the `NamedLevel`/`NamedZone` triggers (D-38), the
  ranking engine (D-39), and the journal record kind (D-40) — not yet
  started; no code has been written against any of D-38/D-39/D-40 yet.

- **D-41** (2026-09-15) — **Walking skeleton built, per README's "Build
  order" (checked directly with the user rather than jumping straight to
  `VolumeProfileView`, since nothing in `flow-core`/`flow-runtime` existed
  yet and D-38/D-39/D-40's event/ranking work needs `MarketState`/
  readiness/triggers to plug into).** `flow-core`/`flow-runtime` now exist
  and compile; `build/build.sh` compiles `flow-core` with no
  `mwave_sdk.jar` on the classpath (D-09, verified — not just intended),
  compiles `flow-runtime` against the SDK + `flow-core`, runs
  `SafetyHookReflectionTest`, and refuses to deploy if it fails.

  **What's done, matching todo.md §2/§3 item by item**: core event types
  (`Event` sealed interface + `TickEvent`/`BarEvent`/`DomEvent`/
  `ClockEvent`/`OrderEvent`/`FillEvent`), integer tick-offset prices
  (D-21), `Intent`, `FlowStrategy`, `Trigger` (exactly README's
  `BarClose`/`EveryTick`/`Throttle`/`PriceCross`/`BookChange` set — D-38's
  `NamedLevel`/`NamedZone` triggers deliberately not added yet, per that
  decision's own note that they arrive with `VolumeProfileView`, not
  speculatively), `MutableMarketState` with a real generation guard,
  `Feature`/`ReadinessChecker` (vacuously trivial with zero features
  registered, but the mechanism is real), `Sequencer` (single synchronized
  `publish()` so seq order matches enqueue order across producer threads —
  a deliberate correctness-over-throughput call, irrelevant at this feed's
  measured rates, see E-3), `Pipeline` (SDK-free, unit-testable per
  README's own "Testing" section — implements the D-25 exception boundary
  itself), the two-tier `JournalWriter` (JSONL both tiers per D-35's
  finding that compressed JSONL is sufficient, not a binary encoding;
  raw-tier drop+gap-marker and decisions-tier loud-failure-on-overflow per
  D-15, both actually implemented not just described), `NullStrategy`,
  `OrderGateway` (reads `getPosition()` only, zero order-submission calls
  anywhere in the class), and `FlowRuntimeStudy` with all 15
  `OrderContext`-taking hooks confirmed overridden by
  `SafetyHookReflectionTest` — StudyHeader flags copied from
  `../motivewave/experiments/src/flow_diag/FlowStrategySkeleton.java`'s
  already-live-validated combination (2026-09-14 session, pos=0/cash flat
  throughout) rather than re-derived.

  **What's explicitly not done, not silently skipped**:
  - `DOMListener` not wired (`DomEvent` exists, nothing publishes it —
    needed for `BookChange` and the liquidity map later).
  - External config store is a placeholder (`StrategyConfig` wraps an
    empty map).
  - Refuse-to-arm-on-existing-position (D-24) not built — moot while
    nothing arms, but needed before that stops being true.
  - Replay harness and the replay-equivalence structural test — next
    concrete step.
  - **No live session has been run.** Everything above is compile-clean
    and deploy-clean, not live-verified. Per this project's own core habit
    (motivewave/CLAUDE.md: "everything gets checked against a real,
    running MotiveWave instance... not assumed from the SDK docs"), this
    is not "done" until a dry-run session's journal is actually read and
    makes sense.

  **Bug found and fixed before any live run**: `Pipeline`'s intent-change
  detection initially used `Intent`'s full record equality, which includes
  `seq` -- since every strategy call increments `seq`, that made *every*
  wake produce a change record regardless of whether the desired state
  actually moved, defeating D-15's change-only journal design entirely.
  Fixed to compare content excluding `seq` (`Pipeline.sameContent()`).
  Caught by re-reading the code being written, not by a live run —
  flagging it here because it is exactly the kind of thing a live run
  should be expected to also catch (an unreadable, bar-per-bar-noisy
  decisions.jsonl), and didn't need to reach that point to be found.

  **Live-verified 2026-09-15**: journal half of the walking skeleton's
  definition-of-done confirmed against a real `@GC` session — single
  clean instance, session header correct, raw stream in strict seq order,
  3 bar closes landing on exact 20s exchange-time boundaries, heartbeats
  on the correct ~10s cadence, `exchangeTimeMs`/`localTimeMs` tracked
  independently and correctly, zero DISARM/GAP_MARKER/exceptions.

- **D-42** (2026-09-16) — **Replay harness + replay-equivalence test
  built and passing against the D-41 live session.** `ReplayHarness`
  deliberately bypasses `Sequencer` — replay reads a file top to bottom
  in original seq order, so a plain loop calling `Pipeline.handle()`
  directly is simpler and more deterministic than routing through
  queue/thread machinery whose entire purpose is ordering *concurrent*
  live producers, which replay doesn't have. Two refactors made this safe
  rather than just possible: `RawEventCodec` puts encode (used live by
  `Pipeline`) and decode (used by replay) in one class instead of two that
  could quietly drift apart, and `StrategyRegistrations.buildDefault()` is
  now the single registration point both `FlowRuntimeStudy` and
  `ReplayHarness` call, so "identical strategy code" (README's own phrase
  for what replay requires) can't silently diverge between live and
  replay either. `JsonObject` (a hand-rolled flat-JSON reader, companion
  to the existing `Json` writer — still no JSON library on this offline
  build's classpath) was needed to parse the raw/decisions journals back.

  **Result**: `ReplayEquivalenceTest` against the D-41 session —
  **PASS**, 3472 events replayed, 0 gaps, and beyond just the intent-list
  check, replay's own `decisions.jsonl` came out byte-identical to the
  live one on every field checked (`generation`, `exchangeTimeMs`,
  `localTimeMs`, heartbeat cadence) — stronger evidence than the minimum
  self-check README asks for.

  **Explicitly not proven by this pass**: it was a 0-intent-changes-vs-0
  comparison (`NullStrategy` never changes), which proves the mechanism
  doesn't false-positive on a no-op strategy but not that it correctly
  reproduces a *real* intent change across live vs. replay. That stays
  open until a real strategy exists to exercise it — flagged here rather
  than left implicit, same as D-41's equivalent caveat on the live run.

- **D-43** (2026-09-16) — **D-11's "replay must be bit-identical to live"
  does not hold for any feature backed by the SDK's own engine classes,
  and D-36 stands anyway — checked directly with the user rather than
  silently picking a resolution.** Found while starting `VolumeProfileView`:
  the SDK's `sdk.profile.VolumeProfile` needs a live SDK `Instrument` to
  even construct (`new VolumeProfile(startTime, endTime, instr,
  rangeTicks)`), which only exists inside a running MotiveWave session --
  so a feature built on it structurally cannot run under
  `ReplayHarness`'s plain-JDK loop, only inside MotiveWave. The
  alternative considered was porting FLOW's own already-validated
  bucket-accumulation algorithm (SDK-free, replayable, and incidentally
  solves E-3's memory problem at the root since a plain `bucket -> volume`
  map has no per-tick retention to begin with) -- **the user chose to
  keep the SDK engine instead, accepting replay-inside-MotiveWave as a
  separate, not-yet-built future mechanism.** D-36 is therefore
  unamended.

  **Consequence, stated plainly rather than left implicit**: `ReplayHarness`
  / `ReplayEquivalenceTest` (D-42) cannot reconstruct `SdkVolumeProfileFeature`
  or any future SDK-engine-backed feature. Replay for a strategy that
  depends on one is **not** bit-identical to live under the current
  mechanism -- `ReplayHarness.replay()` now takes an explicit
  `Map<String,Feature>` from the caller rather than assuming one, so this
  is a visible, forced choice at every call site instead of a silent gap.
  `ReplayEquivalenceTest` runs with zero features and prints a caveat
  saying so. Building replay-inside-MotiveWave (a mechanism that feeds a
  recorded raw journal through the same runtime code but inside a
  MotiveWave-hosted process, so a real `Instrument` is available) is now
  the acknowledged path to closing this gap -- not scheduled, not
  designed yet, tracked in `todo.md`.

- **D-44** (2026-09-16) — **`VolumeProfileView` built and deployed
  (compile/reflection-verified, not yet live-verified).** Scope
  deliberately limited to the primitive itself, per D-36/D-37/D-43:
  session-scoped POC/VAH/VAL/zones with the E-3 rotation fix, not
  footprint (bar-scoped, separate build item) and not D-38's
  `NamedLevel`/`NamedZone` triggers or D-39's ranking (both build on this
  existing and being correct first — same validate-the-primitive-then-
  layer-on-top sequencing that worked for the walking skeleton).

  - `VolumeProfileView`/`ZoneView` (`flow-core/src/com/flow/flow/`) — no
    SDK import, extends `Feature`.
  - `Pipeline` now feeds every registered `Feature` on every event before
    trigger evaluation (`Map<String,Feature>`, required explicitly at
    construction — this is also what forced `ReplayHarness`'s signature
    change in D-43).
  - `SdkVolumeProfileFeature` (flow-runtime) — SDK `VolumeProfile` fed via
    `TickAdapter`, rotated every 150 ticks or 3 minutes (v1 defaults
    derived directly from E-3's ~1.1-1.2 MB/tick measurement, expected to
    be revised once measured under this feature specifically), merging
    each rotation's row volumes into a persistent `bucket -> [askVol,
    bidVol]` map that never holds a raw tick. POC/VAH/VAL/HVN/LVN are
    recomputed every tick from persistent-buckets-combined-with-the-
    current-live-SDK-object's-rows — same value-area-expansion and
    rolling-local-average algorithms as `FLOW/flow/features/
    volume_profile.py` (already validated accurate on real data), ported
    to Java, not reinvented. Zone identity (D-38's greedy largest-overlap
    matching) is implemented and tracks ids across recomputes, though
    nothing consumes zone-transition events yet (that's D-38's separate
    trigger-layer pass).
  - `TickAdapter`: found and worked around a **second** T-6-class
    javadoc-vs-jar mismatch — `Tick.getPrice(Enums.BarData)` exists in
    the compiled jar (confirmed via `javap`) but `Enums.BarData` is not
    resolvable from source at all against this jar (`import
    com.motivewave...Enums.BarData` itself fails to compile). Worked
    around with a `java.lang.reflect.Proxy`-based `Tick` implementation
    instead of `implements Tick` directly — the handler reads the
    `BarData` argument via `java.lang.Enum.name()`, an ordinary
    `java.lang` API, so `Enums.BarData` never needs to be named in source
    anywhere. Worth remembering as a general technique the next time this
    class of mismatch is hit, not just a one-off fix.

  **Compiles clean, safety test passes, deployed. Live-verified 2026-09-16
  (numbers only, not yet visually):** ran ~3 minutes clean on `@GC`
  (`null_strategy_1789498983282_inst2112383200`), decimal POC/VAH/VAL
  logged (`4344.9002`/`4346.5002`/`4344.5002`), zone ids confirmed stable
  across many recomputes (e.g. `hvn-15`/`hvn-27` persisted for minutes
  rather than getting reassigned each tick) — the zone-identity matching
  works, not just compiles. Numeric comparison against the built-in
  study's screenshot proved impractical (matching two tools' timestamps
  by eye), so added chart drawing instead (same purpose as E-2's, this
  time through the real pipeline): `FlowRuntimeStudy.redrawFigures()`
  draws POC/VAH/VAL lines and LVN (orange)/HVN (green) zone boxes,
  throttled to 1/sec, called from `onTick` — a MotiveWave-invoked
  callback thread, never the drain thread. This forced one more real
  design point: `SdkVolumeProfileFeature`'s state is drain-thread-only by
  design (no synchronization, per the single-writer principle), but
  MotiveWave's figure-drawing API only works called from a
  platform-invoked callback, confirmed the hard way previously — two
  genuinely different threads. Bridged with `VolumeProfileSnapshot`, an
  immutable value published via a `volatile` field after every recompute
  (textbook "publish immutable via volatile," the one exception to "no
  synchronization anywhere in this class").

  **Visual comparison confirmed 2026-09-16, with exact numbers** (with
  the redeploy applied live -- no explicit remove/re-add needed this
  time, MotiveWave picked up the rebuilt classes on its own): screenshot
  after a few minutes shows **FLOW VAH 4341.9000 matching the built-in
  study's dashed line at 4341.9 exactly**, **FLOW VAL 4339.4000 close to
  its 4339.2** (2 ticks / 0.2 points off -- same order of "close, not
  exact" parity D-37 already accepted), and **FLOW POC 4340.2000** sitting
  on a real transition in the built-in histogram. This closes the loop
  E-2 opened: the SDK engine's accuracy was already confirmed once
  standalone; this confirms it holds through the actual rotation-fixed,
  `Pipeline`-fed, journaled production path -- our own merge/recompute/
  rotation logic on top of the SDK engine, not just the engine alone.
  `VolumeProfileView` is now genuinely live-validated, not just
  compile-clean.

- **D-45** (2026-09-16) — **`NamedLevel`/`NamedZone` triggers (D-38)
  built: `Trigger.LevelCross`/`Trigger.ZoneTransition`, `LevelSource`/
  `ZoneSource`, `TriggerEvaluator` extended. Unit-verified, not yet live.**

  Kept feature-agnostic per D-38's own intent ("extensible... without new
  plumbing"): `LevelCross(featureId, levelName, kind)` and
  `ZoneTransition(featureId, zoneKind, kind)` reference a feature by id
  and a level by string name / zone by `ZoneView.Kind`, not by importing
  `VolumeProfileView` directly. `TriggerEvaluator` looks the feature up in
  `Pipeline`'s existing `Map<String,Feature>` and only acts if it
  implements the new `LevelSource`/`ZoneSource` interfaces (both in
  `com.flow.flow`, alongside `ZoneView`) — `VolumeProfileView` implements
  both via default methods (`levelValue("POC"|"VAH"|"VAL")`,
  `zonesOfKind(kind)`), so `SdkVolumeProfileFeature` needed zero changes.

  Semantics, matching D-38 exactly: `LevelCross` fires on either side
  moving (price or the level itself, since POC/VAH/VAL recompute every
  tick independently of price) via a relative-position-flip check;
  `ZoneTransition` wakes on **any** zone of the declared kind, not one
  specific id (matches the original ask — a strategy inspects
  `ZoneSource.zonesOfKind()` itself to see which one), `ENTER` fires on
  new membership (including a direct jump between adjacent zones, not
  just from "outside"), and `LEAVE` is debounced — not confirmed until
  price clears one zone-width past its last known range, so a fast
  re-entry continues the same open trial rather than firing a spurious
  `ENTER`/`LEAVE` pair.

  **Found and fixed a real pre-existing bug, not a new-code bug**:
  `Pipeline`'s trigger loop broke on the first trigger that returned
  true, so any other declared trigger simply never got evaluated that
  event — silently desyncing its internal state (which side of a level
  it last saw, which zone it was last inside). This would have corrupted
  any strategy declaring more than one stateful trigger, including the
  pre-existing `PriceCross`, not just the new ones. `TriggerEvaluatorTest`
  (33 synthetic checks, direct assertions against `TriggerEvaluator`, no
  platform needed — README's own "assert the intents you expect before
  the platform is involved" testing philosophy) caught it on the first
  run, before any live session was involved. Fixed: every declared
  trigger is now evaluated every event unconditionally. Wired into
  `build/build.sh` as a permanent gate, same standing as the safety
  reflection test.

  **Not yet live-verified**: no real strategy declares a `LevelCross`/
  `ZoneTransition` trigger yet, so this has never actually woken a
  strategy against real market data — only proven correct against
  synthetic sequences. That's the natural next check once a real
  strategy (or a throwaway diagnostic) exists to exercise it.

- **D-46** (2026-09-16) — **`LevelZoneObserverStrategy` built: first
  strategy to actually declare D-38's triggers, plus the "price trace"
  journal record and chart labels for zones.** Not yet live-verified.

  Same trading behaviour as `NullStrategy` (always `Intent.none()`,
  nothing to arm) — deliberately still a pure observer, not a signal.
  What's different: `triggers()` declares `LevelCross` for POC/VAH/VAL ×
  {TOUCH, CROSS_ABOVE, CROSS_BELOW} and `ZoneTransition` for LVN/HVN ×
  {ENTER, LEAVE, TOUCH} (16 triggers total, all against
  `VolumeProfileView.FEATURE_ID`), so Pipeline's trigger/trace machinery
  fires against real market data for the first time. `requires()` gates
  on the volume-profile feature per D-22, even though nothing here trades
  on it yet.

  **The "price trace"**: `Pipeline.handle()` now journals a compact
  `level_trace`/`zone_trace` decisions-tier record for every `LevelCross`/
  `ZoneTransition` that fires, regardless of what the strategy does with
  it — matches README's "the runtime owns trigger evaluation and journals
  why the strategy was woken." This is deliberately the **foundational**
  half of D-40's zone lifecycle journal only: `CREATED`/`MERGED`/`SPLIT`/
  `DISSOLVED` and the `layer1Score`/`outcome`/`bounceRate` pairing D-40
  described are D-39 (ranking) concepts that don't exist yet — not
  skipped, just not buildable before D-39 is. `BarClose`/`EveryTick`/
  `Throttle`/`PriceCross`/`BookChange` firing is deliberately **not**
  traced this way (far more frequent, not what "trace through the
  levels" means).

  **Chart labels**: each zone box now gets a `Label` (kind + persistent
  id, e.g. "LVN lvn-7") at its current midpoint, using the same id the
  trace journal records — a box on the chart and a line in the journal
  can be matched by eye, not just inferred by price.

  A shared `VolumeProfileView.FEATURE_ID`/`POC`/`VAH`/`VAL` constant set
  replaces what were two independently-hardcoded `"volume_profile"`
  string literals (`FlowRuntimeStudy`'s registration, this strategy's
  trigger declarations) — the kind of drift that would have been a
  silent, hard-to-diagnose failure (triggers referencing a feature id
  that's spelled differently from how it's registered).

  Full rebuild clean (both test gates pass), replay-equivalence
  regression still passes.

  **Live-verified 2026-09-16**: ran on `@GC` as `level_zone_observer`
  (`level_zone_observer_1789569440133_inst287167897`), zero exceptions/
  disarms. Of 330 decisions-tier lines, 96 were `level_trace` and 220
  `zone_trace` — the mechanism is the dominant record type, not a rare
  edge case. Sequence reads correctly: first tick has POC/VAH/VAL
  coincide and all fire `TOUCH` together (correct degenerate case with
  one traded price); as price develops, `CROSS_ABOVE` fires on VAL then
  POC then VAH in order as price works up through the value area.
  `ENTER`/`TOUCH` frequently coincide for zone events (62 ENTER, 35 LEAVE,
  175 TOUCH) — expected, since `rangeTicks=1` zones are exactly one tick
  wide, so entering one is almost always also touching its boundary. One
  case worth recording rather than treating as a bug: at one point `POC
  CROSS_BELOW` and `POC TOUCH` fired together at the same price — this is
  D-38's cause-agnostic rule doing exactly what it says: POC itself moved
  onto price's existing position, which is simultaneously "no longer
  strictly above" and "exactly touching." `D-46` is now genuinely
  live-verified, not just compile-clean and unit-tested.

- **D-47** (2026-09-16) — **POC-relative row numbering for chart labels
  and the price trace, per the user's explicit rule.** Not yet
  live-verified.

  Rule: POC = `0`. A level/zone's number is its row/bucket distance from
  POC's row (row = tick-offset ÷ `rangeTicks`), positive above, negative
  below, magnitude growing with distance — asked and confirmed: row
  count, not raw ticks (stays meaningful if `rangeTicks` is ever widened
  from its current default of 1), measured from a zone's **midpoint**
  (not either edge). VAH/VAL get numbered the same way. A zone whose
  range contains VAH or VAL is **forced** to that level's exact number
  rather than computing its own and hoping it matches — the two are
  computed from the same formula but rounding on a wide zone's midpoint
  could otherwise disagree by one.

  **Deliberately NOT the zone's internal identity.** `ZoneView.id()`
  (e.g. `lvn-7`) is what D-38's matching algorithm uses to track "is this
  the same zone across recomputes" and what D-39/D-40 will eventually
  hang a track record on — a POC-relative number can't serve that role,
  since it would change every time POC itself moves even if the zone
  hasn't. This is purely a display value, computed fresh at draw/trace
  time, never stored. Confirmed with the user before touching anything
  load-bearing.

  **Also added to the price trace** (a follow-on ask, not in the
  original rule): `level_trace`/`zone_trace` records now carry
  `relativeRow` and `priceDecimal` alongside the existing `priceTicks` —
  the tick-offset alone isn't human-readable. This forced a real
  architectural piece: `Pipeline` (`flow-core`, no SDK) had no way to
  convert ticks to decimal, and D-21 only sanctions doing that at the
  ingest and journal boundaries — the journal *is* one of those
  boundaries, so doing it in `Pipeline` is correct, it just needed a
  conduit. `Pipeline`'s constructor now takes a nullable
  `IntToDoubleFunction priceDecoder` (`FlowRuntimeStudy` passes
  `priceCodec::fromTicks`; `ReplayHarness` passes `null` — no live price
  context, trace lines fall back to tick-offset-only under replay,
  consistent with D-43's existing gap). `relativeRow` reuses the exact
  same formula as the chart label, via a new `LevelSource.relativeRow(int)`
  default method (null unless overridden) that `SdkVolumeProfileFeature`
  implements directly against its own `poc`/`rangeTicks` fields — safe to
  read there without going through the volatile snapshot, since
  `Pipeline` calls it from the same drain thread that owns the feature;
  explicitly **not** safe to call from `FlowRuntimeStudy`'s drawing code
  (different thread), which is why the chart-side computation stays
  local to `FlowRuntimeStudy` instead of reusing this same method.

  Full rebuild clean (both test gates pass), replay-equivalence
  regression still passes.

- **D-48** (2026-09-17) — **Price trace now records the level's own
  value and a zone's own low/high, not just the current market price,
  per the user's explicit request.** Not yet live-verified.

  `level_trace` gains `levelPriceTicks`/`levelPriceDecimal` — the
  level's (POC/VAH/VAL) own value at fire time, distinct from
  `priceTicks`/`priceDecimal` (the current market price). These can
  genuinely differ: D-38's cross is cause-agnostic, so a `CROSS_BELOW`
  can fire because the *level* moved onto a stationary price, not price
  moving through the level — recording both makes that visible in the
  trace instead of only inferable.

  `zone_trace` gains `zoneLowTicks`/`zoneHighTicks` (+ their decimal
  forms) — the specific zone's range, not just which kind (LVN/HVN)
  fired. Since `ZoneTransition` wakes on *any* zone of a kind (D-38, by
  design — a strategy inspects `ZoneSource.zonesOfKind()` itself to see
  which one), the trace previously had no way to say which zone was
  actually involved.

  **The real wrinkle**: getting a zone's range into the trace needed
  more than reading current state, because a `LEAVE` fires *after* price
  has already left the zone — by then, "which zone contains the current
  price" no longer answers the question for the zone being left.
  `TriggerEvaluator` already tracked this internally
  (`lastZoneRange`/`lastZoneId`) but cleared it right before returning
  `true` for a `LEAVE`. Added `lastFiredZoneRange(Trigger)`: a separate,
  fire-time snapshot captured for `ENTER`/`LEAVE`/`TOUCH` alike, read by
  `Pipeline` immediately after a true `shouldWake()` — the zone as it was
  known at the moment of the event, not whatever state has been cleared
  to since.

  Full rebuild clean (both test gates pass, including the pre-existing
  `TriggerEvaluatorTest` — the `checkZoneTransition` control-flow change
  needed to add the capture points didn't change any of its behavior),
  replay-equivalence regression still passes.

  **D-47 and D-48 both live-verified 2026-09-18**: ran `level_zone_observer`
  on `@GC`, 9,943 ticks processed, 876 `level_trace` + 2,194 `zone_trace`
  fired, zero exceptions. Confirmed correct: a `TOUCH` shows
  `levelPriceTicks`/`levelPriceDecimal` exactly matching
  `priceTicks`/`priceDecimal` (right, since touching means price equals
  the level); a 1-tick-wide zone (`rangeTicks=1`) shows
  `zoneLowTicks`==`zoneHighTicks`, as it should. Both decisions are now
  genuinely proven, not just compiled and unit-tested.

- **D-49** (2026-09-18) — **Zone age (time since a zone's id was first
  assigned) added to `ZoneView` and `zone_trace`.** Cost measured, not
  guessed, before building: added `firstSeenAtMs` to `ZoneView` (event
  time, not wall clock — set once when D-38's matching assigns a brand
  new id, never touched again while that id persists across recomputes)
  and `zoneAgeMs` to `zone_trace` (`event time - firstSeenAtMs`).
  Deliberately scoped to zones only, not `level_trace` — POC/VAH/VAL are
  recomputed values with no persistent id, so "age since first appeared"
  isn't a coherent concept for them the way it is for a zone.

  **Cost, measured against the same real ~2.6-hour session used for the
  D-48 estimate**: `zone_trace` lines grew ~259→278 bytes (~7%) with the
  new field. Scaled to that session's real firing rate (~1058
  `zone_trace`/hour), that's roughly **+20 KB/hour**, pushing the
  combined trace total to **~330 KB/hour** (up from D-48's ~310 KB/hour
  estimate). The tracking map itself (`zoneFirstSeenAtMs`, one entry per
  zone id ever created this session, never pruned) costs roughly 100
  bytes/entry; even a few hundred zone ids over a multi-hour session is
  a few tens of KB — not measured precisely (the journal doesn't carry
  zone ids, only kind/low/high/age, so exact id-count isn't directly
  recoverable from it), but clearly negligible by an order of magnitude
  regardless. No pruning added — ids reset with the session anyway
  (D-29's boundary), and even an unpruned map never gets large enough
  within one session to matter.

  Full rebuild clean (both test gates pass), replay-equivalence
  regression still passes. Not yet live-verified.

  **Amended 2026-09-18**: staying not-live-verified deliberately, by the
  user's own call, not an oversight — `zoneAgeMs` is hard to eyeball
  against a live chart (there's no visual reference for "how old is this
  zone" the way footprint has the built-in study to compare against), and
  the user trusts the measured-before-built cost accounting and the
  mechanism (set-once-on-new-id, D-38's existing identity tracking) enough
  to accept it without a manual check. Left open only in the sense that
  nobody's watched it fire against real data yet.

- **D-50** (2026-09-18) — **Major sequencing redirect, plus footprint
  built (first of the newly-ordered construct list).**

  **The redirect, stated by the user directly**: stop per-zone
  refinement (D-39's ranking, the HVN/LVN density question) and build
  the remaining core constructs first — footprint, big trades, VWAP,
  market structure, liquidity map, in that rough order (footprint
  confirmed first; big trades and VWAP added to the list after it;
  market structure and liquidity map's relative order was already set
  earlier the same day). Once all exist, wire **one simple strategy that
  uses all of them and places real orders** (Sim account) to prove the
  full pipeline end-to-end. **Only after that is working does
  optimization of individual parts begin.** This supersedes D-39's
  pause-in-place with an explicit "build breadth first" plan — D-39
  itself is not reopened, just deferred further behind more constructs
  than originally expected.

  **Footprint (`FootprintView`/`SdkFootprintFeature`)**: bar-scoped
  instead of session-scoped use of the same SDK `VolumeProfile` engine
  (D-36) — reset at every bar close rather than living for the whole
  session, which means it needs none of `VolumeProfileView`'s E-3
  rotation-fix machinery at all: a bar is short-lived by nature, so the
  underlying SDK object never lives long enough to approach the growth
  rate E-3 measured on a session-length profile. D-37's partial-bar rule
  applies directly: the bar already in progress when the feature
  attaches is discarded at its own close (never published as
  `lastClosed()`, not backfilled) — `isReady()` flips true only once a
  bar has been tracked from its own open through its own close.

  **Deliberately no imbalance flag on `FootprintRow`** — just raw
  `askVolume`/`bidVolume`/`delta` per row. The SDK's
  `isBidImbalance(per, delta, useDelta)` needs a threshold choice, and
  picking one now would be exactly the kind of per-construct judgment
  call this same redirect asked to defer. A consumer can define
  imbalance however it ends up getting decided later, off the raw
  numbers already exposed.

  Own settings-panel row (`Footprint Row Width`, ticks, default 1) —
  independent of `VolumeProfileView`'s own row-width setting, since
  footprint and session-level volume profile can legitimately want
  different granularity.

  Full rebuild clean (both test gates pass), replay-equivalence
  regression still passes. **Live-verified 2026-09-18**, twice: an
  initial live pass (mechanics — bar-close rows sane, zero exceptions)
  and, the same day, a direct visual comparison against MotiveWave's own
  built-in footprint study on the same chart — the user's read: "working
  good enough." No formal row-by-row diff done (that's E-4's stricter,
  still-open bar), but the construct is trusted for the breadth-first
  build to proceed on.

- **D-51** (2026-09-18) — **Per-construct draw flags, plus an honest
  architecture fix the user's question exposed rather than papering
  over.** Not yet live-verified.

  **The question asked**: how much separation of concerns actually
  exists between constructs, enough that a per-construct draw toggle can
  be added cleanly. **The honest answer at the time**: not much —
  `redrawFigures()` was one method that read `SdkVolumeProfileFeature`
  directly by name, with no separation a second construct's drawing
  could slot into without extending that same method. Fixed as part of
  this change, not left as a known gap: `redrawFigures()` is now a thin
  dispatcher — `clearFigures()` once, then one `if (drawFlag)
  redrawXFigures()` line per construct — and each construct's drawing
  lives in its own method (`redrawVolumeProfileFigures()`,
  `redrawFootprintFigures()`). A future construct's drawing method never
  has to know or care whether any other construct is currently shown.
  Deliberately not more than this — no `Drawable` interface, no
  registry, no plugin mechanism. The user was explicit: not asking for
  "extremely scalable," just enough that this exact nuance (and the next
  few like it) don't require a rewrite. A real interface/registry is the
  natural next step **only if** a third or fourth construct's drawing
  needs turn out to want something the flag+dispatcher pattern can't
  express — not speculatively built now.

  **`FootprintView` tightened in the same pass**: was three parallel
  accessors (`current()`/`currentTotalVolume()`/`currentTotalDelta()`,
  duplicated again for `lastClosed`) — now one `BarFootprint` record
  (`startMs`, `endMs`, `rows`, `totalVolume`, `totalDelta`) per bar. Not
  scope creep for its own sake: drawing footprint "directly over the
  candles" needs the bar's own time range to position labels correctly,
  which the old three-accessor shape didn't carry at all — the
  separation-of-concerns question and this fix are the same fix, not two
  separate ones.

  **Draw flags**: `Volume Profile > Draw on Chart` (default **false** —
  stopped per direct instruction) and `Footprint > Draw on Chart`
  (default **true**). A construct is always calculated and journaled
  regardless of its draw flag — the flag only controls
  `redrawFigures()`, nothing upstream of it. `SdkFootprintFeature` grew
  the same volatile-snapshot pattern `SdkVolumeProfileFeature` already
  has (`FootprintSnapshot`) for the same reason: drawing runs on a
  MotiveWave-invoked callback thread, not the drain thread that owns the
  feature's mutable state.

  **Footprint drawn directly over candles**: one `Label` per row, at
  (`bar's own time midpoint`, `row's decimal price`) — not "now" or
  session start, unlike the volume-profile lines, so it visually lines
  up with that bar's own candle. Text is `bid×ask`, colored green/red by
  which side dominates that row, gray if roughly equal. **Scope
  limitation, stated plainly**: only the current (forming) and last
  closed bar are drawn — `FootprintView` doesn't keep a longer history
  than that (its existing, deliberate v1 scope), so there is nothing
  further back to draw yet. Drawing footprint across many historical
  bars, the way the built-in study or the earlier VP screenshots showed,
  would need `FootprintView` extended to a rolling bar history first —
  not attempted here, flagged rather than silently limited.

  Full rebuild clean (both test gates pass), replay-equivalence
  regression still passes. **Live-verified 2026-09-18** — draw-flag
  toggle and the footprint-over-candles rendering both confirmed working
  by the user, alongside D-50's comparison-against-built-in-study check.

- **D-52** (2026-09-18) — **Footprint row merging is draw-time only,
  never stored.**

  Per the user's explicit instruction: the raw per-tick footprint rows
  stay exactly as `FootprintView` computes them — "the more granular the
  data the better" for later analysis — nothing about `FootprintRow`,
  `BarFootprint`, or `SdkFootprintFeature` changed. A new
  draw-time-only setting (`Footprint > Draw: Merge N Rows`, default 1 =
  no merging) controls how many raw rows `FlowRuntimeStudy` groups
  together *only inside `drawFootprintBar()`*, purely for a "bird's eye
  view" at runtime.

  Gridded by price, not by row count: `bucketLow = floor(priceTicks /
  (rawRangeTicks × mergeRows)) × (rawRangeTicks × mergeRows)`. Chosen
  over grouping by row index/position specifically because footprint
  rows can have gaps (no row at all where nothing traded) — a
  position-based grouping would silently shift which prices land
  together depending on which rows happen to exist; the price grid
  doesn't, a tick that never traded still correctly contributes zero to
  its bucket. Matches the user's own worked example exactly: three
  1-tick rows `0×1`, `1×3`, `4×0` → one merged `5×4` (bid sum, ask sum).

  A merged bucket (`mergeRows > 1`) draws as a genuine zone — a `Box`
  spanning its low/high price, same visual pattern as the LVN/HVN zones
  — not a single-point label, since it now legitimately covers a price
  range. `mergeRows=1` collapses to one bucket per raw row, so there's
  exactly one drawing code path regardless of the setting, not a special
  case for "no merging."

  **Explicitly out of scope, per the user's own framing**: if similar
  merging/downsampling is wanted for offline analysis/backtesting later,
  that belongs in the analysis/backtest script itself, not in
  `FootprintView` or any core data structure. Nothing here anticipates
  or builds toward that.

  Full rebuild clean (both test gates pass), replay-equivalence
  regression still passes. **Live-verified 2026-09-18** — merged-row
  boxes confirmed correct by the user alongside D-50/D-51's checks.

- **D-53** (2026-09-18) — **Big trades built directly against our own
  `TickEvent` stream, not as a wrapper around the SDK's `AggregateFilter`
  — reopens D-36's "same audit covers big trades" framing for this one
  construct specifically.** Live-verified the hard way: the first attempt
  (`AggregateFilter` fed via `TickAdapter`'s dynamic proxy, same pattern
  D-44/D-50 already had working for `VolumeProfile`) disarmed a live
  `level_zone_observer` session immediately — every event from seq 1
  hit `ClassCastException: class jdk.proxy2.$Proxy6 cannot be cast to
  class k.v`. Root cause and full trail:
  `../../motivewave/docs/dynamic/findings.md`, 2026-09-18. Diagnosed and
  fixed within the same session — no strategy runs on data this affects
  yet (walking skeleton, no real trading), but it did mean a live session
  sat silently disarmed until the fix was deployed.

  **Root cause**: `AggregateFilter.onTick(Tick)` casts its argument to an
  internal concrete class, unlike `VolumeProfile.onTick(Tick)` which
  accepts any `Tick` implementer (confirmed safe there since D-44). A
  real asymmetry between two SDK engine classes E-2/E-5 both technically
  "passed" — E-5's own live confirmation fed it the platform's *real*
  `Tick` directly from a MotiveWave callback thread, never through a
  proxy or a re-sequenced stream, so it never exercised this path.

  **Why not just feed it the real Tick from the callback thread**:
  that's the only way to avoid the cast, but doing so would mean
  `AggregateFilter`'s internal state mutates from a thread that isn't the
  drain thread — violates the single-writer invariant (README "The event
  stream") every other feature depends on, and makes big trades
  undecodable from the raw journal (D-11's replay-equivalence requires
  every feature's inputs to be reconstructable from the recorded event
  stream in original seq order; realtime callback-thread arrival order
  isn't that). Not attempted.

  **What got built instead**: `BigTradeFeature` (`flow-core/src/com/flow/
  flow/`) reimplements `AggregateFilter`'s actual job directly —
  accumulate volume by `exchOrderId` (a real per-tick field, added to
  `TickEvent` alongside `aggExchOrderId` specifically for this), and once
  the running total for an id crosses the configured `minSize`, publish a
  `BigTradeEvent`; further volume on the same id updates that event's
  size in place (T-3's documented repeat-emission behavior, reproduced
  directly rather than relied upon from the SDK). `exchOrderId == 0`
  (confirmed live sentinel, E-5) is never aggregated with another
  zero-id tick — each stands alone, counted only if its own single-tick
  volume already clears `minSize`. **Zero SDK dependency**, so this
  lives entirely in `flow-core`, not `flow-runtime` — not required by
  D-09's classpath split, just falls out naturally since there was
  nothing left needing the SDK. Deterministic function of the recorded
  `TickEvent` stream, so D-11's replay-equivalence holds by construction
  rather than by trusting an opaque engine to replay identically.

  **Fixed threshold (D-22 class 1, ready immediately)**: `minSize` is a
  configurable settings value (`Big Trade Min Size`, default 10
  contracts, matching the value the original `SdkCapabilityProbe` E-5
  experiment used), not a relative/percentile threshold — no warmup
  needed, consistent with how footprint/VP row widths are configured.

  **Logging split, not the feature itself**: `BigTradeFeature` takes an
  optional `Listener` callback (`onCreated`/`onUpdated`) rather than
  owning file I/O directly — keeps it a plain, synchronously-testable
  object per D-20, with no platform or filesystem dependency at all.
  `BigTradeFileLogger` (`flow-runtime`) implements `Listener` and owns
  the actual `logs/big_trade_feature.log` PrintWriter, same live-
  validation discipline as footprint/VP's own log files. A drawing pass
  (chart figures) is deliberately deferred, same as footprint's own
  D-50-before-D-51 sequencing — this pass is feature + log validation
  only.

  Full rebuild clean (both test gates pass) after the fix. **Live-
  verified**: redeployed, the previously-disarmed session's replacement
  came back healthy (zero `DISARM` records, heartbeat `healthy:true`,
  trigger/trace records continuing to fire normally) — confirms the
  crash itself is fixed. **Not yet confirmed**: an actual real trade
  clearing the 10-contract threshold hadn't printed in the short window
  checked immediately after redeploy, so the aggregation/dedupe logic's
  correctness against real data is still open — next live check should
  look for `CREATED`/`REPEAT` lines in `big_trade_feature.log` once a
  genuinely large print happens.

  **Amended 2026-09-18 by D-55**: order-id aggregation itself was
  subsequently live-confirmed *correct* (D-54's live check), but a
  side-by-side against the built-in study showed it was the wrong mode —
  D-55 switches to time+price window aggregation, matching what the
  built-in evidently uses. This entry's design description (order id as
  the aggregation key) is superseded; the "not a wrapper around
  AggregateFilter, here's why" reasoning stands unchanged.

- **D-54** (2026-09-18) — **Big trades drawn on chart: one CIRCLE Marker
  per trade, green for buy-aggressor / red for sell-aggressor, labeled
  with contract size.** Requested directly by the user right after D-53
  landed, plus a follow-up mid-build asking for the size label too — both
  folded into this pass rather than split into two.

  **New SDK-jar mismatch found and worked around, same class as T-6**:
  `Enums.MarkerType`/`Enums.Size`/`Enums.Position` (needed for `Marker`'s
  constructor) fail to compile as source-level type references —
  confirmed directly (`javac`: "cannot find symbol: class MarkerType,
  location: interface Enums") for both a qualified reference and a plain
  `import`, the same failure shape T-6/`TickAdapter`'s `Enums.BarData`
  finding already established for a different nested `Enums` type.
  Worked around the same way `TickAdapter` works around `Enums.BarData` —
  reflection, not a source-level reference: `MarkerAdapter`
  (`flow-runtime`) resolves `Enums$MarkerType`/`$Size`/`$Position` via
  `Class.forName`, looks up `CIRCLE`/`MEDIUM`/`CENTER` via `Enum.valueOf`,
  and calls `Marker`'s 6-arg constructor via `Constructor.newInstance`
  once, cached in static fields. Unlike `TickAdapter`'s dynamic proxy
  (needed there because `Tick` itself couldn't be implemented any other
  way), `Marker` is a perfectly ordinary nameable class — only the enum
  arguments going *into* its constructor needed reflection — so
  `MarkerAdapter.circle()` returns a genuinely typed `Marker`, and every
  other call on it (`setTextValue`, `addFigure`) is a normal method call.

  **Color convention matches footprint's existing delta-sign rule**
  (`drawFootprintBar()`): `isAskTick` (buy aggressor, "+ve") → green
  `(0,200,0)`; sell aggressor ("-ve") → red `(220,60,60)`. Text is the
  trade's contract size (`%.0f`), not a bare shape — added per the user's
  explicit follow-up request, matching the project's existing convention
  that every drawn figure carries a readable label (POC/VAH/VAL lines,
  zone boxes, footprint rows all do too).

  **Historical stamp, not "current state"**: unlike VP zones/footprint
  rows, a big trade has no ongoing state to reflect — `redrawBigTradeFigures()`
  draws one Marker per entry in `BigTradeView.recent()` (bounded at 500
  by `BigTradeFeature`, D-53) every redraw pass, the same full-clear-
  and-redraw-from-current-state pattern `redrawFigures()`'s dispatcher
  already uses for every construct, just applied to a list of point
  events instead of an ongoing computed state. New draw flag
  (`Big Trades > Draw on Chart`, default **true** — the user asked for
  this immediately, no reason to default it off) alongside the existing
  per-construct dispatcher (D-51).

  **Needed a thread-safety fix in `BigTradeFeature` to support this**:
  `recent()` previously built its `List.copyOf` live from the
  drain-thread-only `recentByKey` map on every call — safe when nothing
  outside the drain thread called it (true when D-53 landed), unsafe now
  that `redrawBigTradeFigures()` calls it from a MotiveWave callback
  thread. Fixed with the same volatile-immutable-snapshot pattern
  `VolumeProfileSnapshot`/`FootprintSnapshot` already established:
  `recentSnapshot` is republished only inside the create/repeat-update
  branches (not on every tick — most ticks aren't big trades, so this
  stays cheap), and `recent()` now just returns it.

  Full rebuild clean (both test gates pass). **Live-verified, the
  aggregation logic itself for the first time (closing D-53's one open
  item)**: `logs/big_trade_feature.log` shows real `CREATED` → `REPEAT`
  sequences against live `@GC` ticks, size genuinely growing across
  repeats for the same `exchOrderId` exactly as T-3 predicted (e.g.
  `10.0 → 11.0 → 13.0 → 25.0` for one order id, three separate repeat
  emissions). Session stayed healthy throughout (zero `DISARM`,
  `level_trace`/`zone_trace` records continuing normally) — confirms the
  redesign in D-53 both fixed the crash and produces correct output
  against real trades. **Not yet confirmed**: the drawn circles
  themselves — `redrawBigTradeFigures()` runs on the callback thread
  outside `Pipeline`, so a drawing-side exception wouldn't show up as a
  journaled `DISARM` the way a pipeline exception would; needs the user
  to actually look at the chart.

- **D-55** (2026-09-18) — **Big trades re-aggregated by time+price window,
  not exchange order id — corrects D-53's aggregation mode, not its
  decision to avoid `AggregateFilter`.** Triggered by the user's own
  side-by-side comparison against MotiveWave's built-in "Big Trades(20)"
  study on the same chart: markers landed at the correct price/time on
  both sides, but the contract-size numbers didn't match at corresponding
  locations — ours mostly showed `1`, the built-in showed varied `2`/`3`/
  `5`s.

  **Root cause, found in the SDK's own javadoc**
  (`../../motivewave/docs/static/javadoc/.../AggregateFilter.html`, not
  previously read closely): `aggByOrder` and `aggPeriod` are **mutually
  exclusive by data availability, not a user choice** — "if this
  [exchange order] id is available... it will be used to aggregate
  (ignoring the agg period)." D-53 used order-id aggregation, which
  D-53 itself already live-confirmed *correct for that mode* (real
  `CREATED`→`REPEAT` growth on a shared order id) — the bug wasn't in the
  aggregation logic, it was in choosing the wrong mode. On this feed,
  most prints have genuinely distinct exchange order ids (each fill
  against a different resting order), so order-id aggregation mostly
  degenerates to single-print "big trades." The built-in study's "(20)"
  is almost certainly `aggPeriod=20ms` — time+price window aggregation
  (a sweep through several resting orders within a short window),
  independent of order id, which is the more human-intuitive "one big
  trade" and evidently what MotiveWave's own study uses.

  **New design**: a single in-progress aggregation window (not a
  per-order-id or per-price map) — matches `AggregateFilter`'s own
  internal shape too (its field list has one `aggTick`/`tickStart` pair,
  not a map), and is correct here because trades on one instrument arrive
  strictly sequentially in time, so at most one window can be open at
  once. A tick merges into the current window iff same `priceTicks`,
  same `isAskTick` (side), and within a configurable `aggPeriodMs` of the
  *last* tick that joined it (a rolling gap, not a fixed clock window —
  chosen as the more standard "continuous burst" interpretation); a
  non-matching tick silently closes the old window (its last published
  state already stands as final) and opens a new one. T-3's
  same-order-id repeat behavior is naturally subsumed — same-id repeats
  are necessarily same price/side/short-gap, so they merge into the
  window anyway without any order-id-specific code. `exchOrderId` on the
  resulting `BigTradeEvent` is now best-effort provenance only (the
  opening tick's id), not an aggregation key.

  New setting, `Big Trades > Agg Period (ms)`, default **20** (best
  available evidence for the built-in's own default, from its title) —
  configurable per-instrument/session like every other threshold here.

  Full rebuild clean (both test gates pass), redeployed. **Live-verified
  crash-free** (replacement session healthy, zero DISARMs) — **merge
  behavior itself not yet observed live**: the short window checked
  right after redeploy had `minSize=1` (the user's own test setting from
  the drawing pass), so single ticks already clear threshold before any
  merge opportunity shows up in the log as a `WINDOW_GROW` line. Next
  live check: either wait for a natural same-price cluster, or raise
  `minSize` back up, and confirm the drawn numbers now vary the way the
  built-in study's do.

- **D-56** (2026-09-18) — **Order-id repeat tracking kept alive as its
  own separate construct (`OrderRepeatView`), not discarded when D-55
  moved big trades to time+price-window aggregation.** Direct user
  request: "keep the group by order id also possible for future. we
  might need both to identify iceberg orders and more analyses." Also
  confirmed the D-55 fix itself: a second side-by-side against the
  built-in "Big Trades(20)" study, varied numbers now roughly matching —
  user's own assessment, "matching almost exactly (90%) good enough."

  **Why a separate construct, not a flag on `BigTradeFeature`**: the two
  questions are genuinely different. "Was this a big print" (time+price
  window, D-55) and "is this order id trading repeatedly" (iceberg/
  hidden-liquidity signal) can disagree in both directions — a big
  window-aggregated print might be several different orders' first and
  only fill each, and a genuine order-id repeat might never individually
  or cumulatively be "big." Keeping them separate mirrors how VP and
  footprint stay separate constructs despite sharing the same SDK engine
  underneath.

  **No size threshold, no classification** — capture only. Any order id
  seen a second time publishes an `OrderRepeatEvent` (count starting at
  2), updated on every repeat after that; "is this actually an iceberg"
  is deferred analysis, not decided here, matching how D-39/D-40 kept
  ranking separate from raw capture. Reuses D-53's original per-order-id
  logic almost exactly (already live-proven correct, just not the right
  fit for `BigTradeView` specifically).

  **One new bound, an accepted simplification not a measured one**: the
  per-order-id running-state map (`running`, every distinct order id seen
  this session, not just repeats) is capped at 5000 entries with LRU
  eviction (`LinkedHashMap` access-order mode) — without it, this map
  would grow with total tick count over a long session, since most real
  order ids never repeat at all. Forgetting a very old, never-repeated id
  costs nothing; the only real risk is missing a genuine repeat that
  happens unusually far apart in tick count (not wall time), accepted
  without measurement for now, same honesty-over-precision stance D-49
  modeled for a different tradeoff.

  No settings row, no drawing — log-only (`logs/order_repeat_feature.log`)
  for now, same build-then-layer discipline as every other construct's
  first pass here. `recent()` is drain-thread-only (no volatile snapshot
  yet, unlike `BigTradeFeature`) since nothing outside the drain thread
  reads it — would need the same fix `BigTradeFeature` got (D-54) before
  any future drawing/cross-thread use.

  Full rebuild clean (both test gates pass), redeployed, **live-verified
  crash-free** (session healthy, zero DISARMs) — **repeat detection
  itself not yet observed live** (log empty in the short window checked
  right after redeploy; needs a real order id to actually trade twice).

- **D-57** (2026-09-18) — **VWAP built: ported from MotiveWave's own
  published `VWAP.java` source, core running-average algorithm only.**
  Fourth construct in the 2026-09-18 breadth-first order (footprint →
  big trades → **VWAP** → market structure → liquidity map).

  Cloned `MotiveWave/motivewave-studies` (GPL v3, public,
  `https://github.com/MotiveWave/motivewave-studies`, already the source
  D-36/E-10 previously inventoried by filename only) to actually read
  `ma/VWAP.java` for this pass, rather than working from the earlier
  summary alone — confirms E-10's finding exactly: genuinely
  tick-weighted (`totalPrice += tick.getPrice()*tick.getVolumeAsFloat();
  totalVolume += tick.getVolumeAsFloat(); vwap = totalPrice/
  totalVolume`), and the file is ~90% settings-panel/UI plumbing
  (path/indicator descriptors, `DataSeriesImpl`-backed per-bar storage,
  RTH/click-anchor menu, standard-deviation bands) that has no equivalent
  in this codebase and isn't needed — only the core running-average loop
  is ported, confirming "adapt, don't copy" (D-36) was the right call,
  not an assumption.

  **Zero SDK dependency** — lives in `flow-core`
  (`VWAPView`/`VWAPFeature`), same as `BigTradeFeature` (D-53): two
  running `double`s, no engine class, no rotation/memory concern at all.

  **Session-anchored to feature construction (attach time), not a
  calendar-day boundary** — matches `VolumeProfileView`/`FootprintView`'s
  own "forward-only from attach" behavior (D-37) rather than building
  session-boundary-detection machinery that doesn't exist anywhere else
  in this codebase yet (nothing currently resets anything at a calendar
  boundary — D-19's reversal counters and D-21's price anchor will
  eventually need the same mechanism). A true daily reset matching
  D-29/D-34's 24h session boundary is deferred until that general
  mechanism gets built, not invented as a one-off here.

  **Standard-deviation bands deliberately not ported** — a materially
  separate computation (per-bar OHLC typical price, rolling volume
  window, the source file's own second half) on top of the core VWAP
  value, not attempted this pass. Matches this session's own established
  pattern of deferring secondary refinements (footprint's imbalance
  flag, VP's value-area-method pluggability) until the core construct is
  proven.

  **Volatile snapshot from day one, not retrofitted** — `vwap()`/
  `totalVolume()` publish via `volatile` fields updated at the end of
  every tick, unlike `BigTradeFeature` which needed exactly this fix
  added after the fact once drawing needed cross-thread reads (D-54).
  Cheap enough to just do now given that lesson.

  No settings row (nothing to configure — no threshold, no window), no
  drawing yet — log-only (`logs/vwap_feature.log`, periodic 1s-throttled
  samples since VWAP is one continuously-updating value rather than
  discrete occurrences, unlike big trades/order repeats' event-driven
  logging) — same build-then-layer discipline as every construct's first
  pass here.

  Full rebuild clean (both test gates pass), redeployed. **Live-verified
  both halves**: crash-free (session healthy, zero `DISARM`s, sequence
  advancing normally, `level_trace` continuing to fire) and the value
  itself looks correct — sampled `vwap` tracked the same price range
  `VolumeProfileView`'s POC/VAH/VAL were reporting concurrently
  (~4419.15-4419.21), with `totalVolume` growing monotonically as
  expected (6 → 10 → 11 → 12 → 13 across consecutive 1s samples). Not
  compared numerically against the chart's own built-in VWAP line yet
  (no drawing wired to do that visual comparison) — next natural check
  once VWAP gets a chart line, same as every other construct's
  built-in-study comparison pattern.

- **D-58** (2026-09-18) — **Liquidity map built: live MBO DOM stream →
  aggregate-only in-memory book → periodic bounded-window journal
  snapshot, no raw DOM persistence at all.** Fifth and last construct in
  the 2026-09-18 breadth-first order (footprint → big trades → VWAP →
  market structure *(deferred, picked up later)* → **liquidity map**,
  brought forward ahead of market structure per direct instruction).

  **Design driven directly by the user (2026-09-18), not derived from
  the SDK docs alone**: D-35 already proved full per-order DOM detail at
  native update rate is ~31.5 GB/hour — not storable, full stop. The
  user's own proposal: maintain a live, continuously-updating aggregate
  order-book instance fed by the raw DOM stream, and let a periodic
  (~10s) snapshot of *that derived state*, bounded to a window around
  price, be the only thing that ever reaches disk. This is a fourth
  option beyond D-35's original three (top-of-book-only / delta encoding
  / shorter full-detail window) and the one actually built.

  **Explicit, accepted consequence for D-11**: replay (bit-identical to
  live) does not extend to `LiquidityMapFeature` at update granularity —
  full per-update DOM detail is never in the raw journal at all (see
  `DomEvent`'s javadoc), so there is nothing for replay to reconstruct
  it from below the periodic snapshot. Flagged to the user directly
  before building, not discovered after.

  **Three design questions asked and answered directly by the user**
  before writing code:
  1. **Aggregate rows only, not per-order** — no individual resting
     order id/size/age tracking. Explicitly **not** ruled out forever:
     the user asked for a deferred experimentation item (see todo.md)
     covering both whether that per-order data is even reliably
     available on this feed, and, if so, whether it can actually support
     intent analysis (iceberg/manipulation/hindsight-movement
     detection) — a real research question, not assumed either way.
  2. **Bounded window around price for the periodic snapshot**, not the
     full book every time — matches what a heatmap actually displays
     and keeps storage predictable regardless of how wide the live book
     gets. The *live* in-memory state still holds the full book (cheap —
     see below); only the persisted snapshot is windowed.
  3. **Same JVM, clock-triggered** — no new concurrency model. Reuses
     the existing 100ms `ClockEvent` cadence and the existing async
     `JournalWriter`, exactly like the heartbeat mechanism already does,
     just as its own independently-named cadence (currently the same
     period by coincidence, not by design, since the two serve unrelated
     purposes and may want to diverge once real storage numbers are in).

  **Mechanism**:
  - `DomEvent` (flow-core) grows the depth array its own original
    comment already anticipated: `bidRows`/`askRows` (full resting book
    per update), alongside the existing top-of-book convenience fields.
  - `RawEventCodec.encode()` for the `"dom"` record type is **left
    unchanged** — it only ever wrote top-of-book fields, so full-update
    depth structurally never reaches the raw journal, by construction,
    not by a special-cased skip anywhere in `Pipeline`. `decode()`
    reconstructs `DomEvent` with empty row lists (that detail was never
    recorded) — the accepted D-11 gap made concrete.
  - `LiquidityMapFeature` (flow-core, zero SDK dependency, same
    realization as `BigTradeFeature`/`VWAPFeature`): each `DomEvent`
    **replaces** `bidRows`/`askRows` wholesale, O(1) per update — the SDK
    hands back the full current book every time, not a delta (confirmed
    live), so unlike `VolumeProfileView` this needs **no rotation/
    memory-management machinery at all**; live state is always exactly
    the current book's size, never accumulates. Reads (`bestBidTicks`,
    `bidSizeAt`, `bidRowsWithin`) are a deliberate O(n) linear scan over
    the stored list rather than a maintained sorted structure — correct
    tradeoff given writes happen at DOM-update rate (measured 32-172/sec,
    D-35) and reads only happen at the ~10s snapshot cadence or a
    strategy's own much slower trigger cadence.
  - `Pipeline` gets a second clock-triggered periodic action alongside
    the existing heartbeat: `maybeDomSnapshot()`, same `ClockEvent`
    counter mechanism, writes a new `liquidity_snapshot` decisions-tier
    record (`Json.fieldRaw()` added to embed the row arrays — the first
    decisions-tier record needing anything beyond flat primitive
    fields). v1 defaults, explicitly flagged as revisit-from-measurement
    like `SdkVolumeProfileFeature`'s `ROTATE_EVERY_TICKS` already is:
    100 clock events (~10s) cadence, ±100 ticks window.
  - `FlowRuntimeStudy` implements `DOMListener` (same proven pattern as
    `../../motivewave/experiments/src/flow_diag/DomDetailCapture.java`
    and `SdkCapabilityProbe.java`, including that pattern's
    `removeListener` in `destroy()` — an un-removed listener is exactly
    what turns a "removed" study instance into a DOM-fed zombie, per
    that experiment's own finding). Subscribes only after the sequencer
    is already running, avoiding the null-sequencer race rather than
    just tolerating it the way `onTick()`'s defensive null check does.
    No exchange timestamp exists for a DOM update at all (confirmed from
    the actual jar — neither `DOM` nor `DOMListener.update()` carry one,
    unlike `Tick.getTime()`) — local receipt time stands in for both
    `eventTimeMs` and `receiptTimeMs`, a permanent characteristic of
    this data source, not a gap to close later.

  **Liquidity-heatmap visual check (the one item flagged open in
  today's earlier assessment) — explicitly declined by the user, not
  skipped by oversight**: "any motivewave heatmap study is not
  accurate. whatever we will build will be more accurate and i
  currently dont have bookmap subscription to test against it so we
  just gonna trust our process." Closed as *won't-do*, not left open.

  Full rebuild clean (both test gates pass), redeployed. **Live-verified
  end to end, immediately**: real MBO data flowing (~920-930 bid rows,
  ~865-870 ask rows on `@GC`, both sides updating roughly once a
  second), best bid/ask tracked correctly (spread ~4 ticks, sane), zero
  `DISARM`s, and the periodic snapshot fired on schedule — three
  `liquidity_snapshot` records in the first ~30 seconds, well-formed
  JSON, ~3.6 KB each. **Real measured storage cost, not estimated**:
  ~3.6 KB/snapshot × 360 snapshots/hour (10s cadence) ≈ **~1.3 MB/hour,
  ~31 MB/day** — comfortably in D-35's "cheap" tier, nowhere near the
  per-order-detail numbers that made this whole design necessary in the
  first place.

- **D-59** (2026-09-18) — **Liquidity map drawn on chart for a live
  visual test: one colored Box per DOM row in the draw window, deep
  blue → light blue → white → yellow → orange → red → deep red by
  resting size, both sides on the same scale.** Requested directly by
  the user right after D-58 landed, exact color sequence and "both
  sides of current price on the same scale" (not a separate color
  family per side) specified directly, not inferred.

  **Retrofitted `LiquidityMapFeature` for cross-thread safety first**:
  `bidRows`/`askRows` marked `volatile` (D-58's own javadoc had already
  flagged this as needed once drawing existed, same lesson
  `BigTradeFeature` taught, D-54) — but unlike `BigTradeFeature`'s fix,
  this one was free: `onEvent()` already just reassigns these fields to
  `DomEvent`'s own already-immutable `List`s, so marking them `volatile`
  is a plain-write-to-volatile-write change, not a new allocation on the
  DOM-update hot path. No "throttle the publish rate" mechanism needed,
  unlike what the class's own javadoc had speculated a drawing pass
  would require.

  **Historical stamp at "now," not a scrolling heatmap across time** —
  there is no per-row history to draw beyond the live state (D-58: raw
  DOM detail is never persisted, only periodic bounded snapshots), so a
  classic Bookmap-style heatmap scrolling across the chart's time axis
  is explicitly not attempted; each redraw shows a thin trailing column
  positioned at the current time, cleared and redrawn from scratch every
  cycle like every other construct's drawing here.

  **Normalization: plain linear min-max across the combined bid+ask
  window, every redraw** — simplest defensible v1 for a first visual
  test, not tuned. Known, stated limitation: one very large resting
  order in the window would compress everything else toward the cold
  end of the scale. Not addressed pre-emptively — revisit from what the
  live picture actually looks like, not a guessed fix.

  New settings: `Liquidity Map > Draw Window (ticks each side)` (default
  50, separate from `Pipeline`'s own `DOM_SNAPSHOT_WINDOW_TICKS` — one
  controls the journal snapshot, this one only the drawing, no storage
  relationship between them) and `Liquidity Map > Draw on Chart`
  (default **true** — the user asked to see this immediately, same
  reasoning D-51/D-54 already used for "just built, default on").

  Full rebuild clean (both test gates pass), redeployed, session stayed
  healthy (zero `DISARM`s) immediately after. **Live-verified** — user
  confirmed directly: "yes working fine." Follow-up request right after
  (heatmap history behind the candles, not just the live trailing
  column) recorded in `todo.md`, explicitly not picked up now.

- **D-60** (2026-09-18) — **Market structure built against unreviewed
  rule resolutions, per explicit instruction to proceed without
  waiting for review.** The user provided a complete trend/pullback/
  zone/flip system directly (three iterative drafts, distilled into
  `docs/dynamic/marketStructureRules.md`, 7 points flagged
  `⚠️ AMBIGUOUS`). No time to review it right now — explicit
  instruction: "create a temp structure rules, fill the ambiguity with
  whatever u feel good and move ahead... and complete the market
  structure part." `marketStructureRulesTemp.md` records exactly which
  guess was made for each of the 7 points (CHOCH = first bar's close;
  pullback validity anchored to the run's first candle, not sliding;
  a valid pullback's candle range keeps growing until continuation;
  "1%" = 1% of that candle's own high-low range; SBR/RBS plays CHOCH's
  bootstrap role after the first flip; the DT/DB scan window is
  `[A+ candle, flip-confirming candle]` inclusive; CHOCH is retired
  permanently once any real TJL pair forms) — **none of these are
  confirmed**, this decision and the feature built against it are
  provisional in a stronger sense than usual until reviewed.

  This construct replaces the earlier, much vaguer plan ("market
  structure builds on `DataSeries.calcSwingPoints`," E-7/E-10) — that
  plan is superseded, not merely deferred; `calcSwingPoints` is not used
  here at all.

  **Zero SDK dependency** — pure bar OHLC logic (open/high/low/close),
  so `MarketStructureFeature` lives in `flow-core`, same realization as
  `BigTradeFeature`/`VWAPFeature`/`LiquidityMapFeature` this session.
  **Bar-close-triggered only** (`BarPhase.CLOSE`), matching the source
  system's own non-repainting, close-only framing (§9) — this is the
  first construct here gated on bar closes rather than ticks or DOM
  updates.

  **Implementation shape**: a single state machine (`trend`,
  `pullbackState`, the pullback's own growing candle list, the current
  `lastTjl1`/`lastTjl2`, and `lastAPlus`/`lastSbrRbs`/`lastDtDb` from the
  most recent flip). The one piece of state the rules required beyond a
  literal reading of "zones": `tjl1AnchorBar` (which specific candle
  produced the current TJL1, not just its resulting price range) plus a
  running `barsSincePair` list accumulated from that candle forward —
  needed so a future flip's "highest/lowest point after A+" scan (§6)
  has a real window to search without replaying history. Reset every
  time a new real TJL pair forms.

  Flip-watch (§5) runs unconditionally every bar, independent of
  pullback state, exactly as specified — checked *before* pullback
  update each bar, and a bar that triggers a flip is excluded from that
  same bar's pullback processing (starts fresh next bar). The
  CHOCH-driven initial transition (before any real TJL pair has ever
  formed) is handled as a distinct, simpler branch of the same
  flip-check: trend flips, but no A+/SBR/RBS/DT/DB gets assigned (there
  is no TJL1 yet for A+ to come from) — matches §1a's own simpler
  framing rather than forcing the general flip's full role-reassignment
  onto a case the source describes differently.

  Log-only (`logs/market_structure_feature.log`), no drawing, no
  settings — one line per named event (pullback validated, TJL pair
  formed, flip), matching the discrete-event logging style already used
  for big trades/order repeats rather than VWAP's periodic-sample style,
  since these are genuinely discrete occurrences.

  Full rebuild clean (both test gates pass), redeployed, session stayed
  healthy immediately (zero `DISARM`s). **Not yet live-verified beyond
  that** — no bar had closed in the short window checked right after
  redeploy, so no pullback/TJL/flip event has fired yet to confirm the
  logic itself against real data. Next check: `market_structure_
  feature.log` after a few real 1-minute bars have closed.

- **D-61** (2026-09-18) — **Exec/risk-chain backbone built: external
  config store, risk chain (all 8 filters), bracket reporting, and
  refuse-to-arm.** First piece of "wire one simple strategy end-to-end,"
  built as its own coherent unit since the filters share state and a
  common journaling pattern — entry evaluators and the first real
  strategy are the next piece, deliberately separate.

  **`ExternalConfig`** (flow-core): flat JSON, hand-edited, runtime-read-
  only (D-08/D-18's own write discipline, same spirit as `.env` without
  being a secret — committed to the repo at `config/risk.json`, not
  gitignored). Missing keys fall back to conservative defaults rather
  than failing the session; every value in effect is journaled at
  session start (`risk_config_loaded`) with the file's own last-modified
  time for staleness (README: "traceable after the fact, not silently
  assumed current"). Units are integer ticks, not dollars — sidesteps
  needing a per-instrument $/tick multiplier as a separate config value
  for this v1; the daily-loss guard is directionally meaningful in ticks,
  revisit if a real $ threshold ever matters more than this
  simplification.

  **`RiskChain`** (flow-core): the 8 filters in README's own listed
  order (armed, session open, readiness, daily-loss, size cap, rate
  limit, churn guard, lag guard), short-circuiting on the first block —
  later filters are moot once one blocks, nothing meaningful to journal
  for them. `evaluate()` is read-only; `recordAccepted()` (called only
  when `evaluate()` allows) is what actually updates churn/rate/PnL
  state, so a blocked intent never pollutes tracking as if it had really
  happened. Every evaluation — allowed or blocked — gets a
  `risk_verdict` decisions-tier record with all verdicts reached before
  short-circuiting.

  Sub-decisions inside the chain, each a real simplification worth
  naming:
  - **"Session open" is a hard-coded ALLOW** — no session-boundary-
    detection machinery exists anywhere in this codebase yet (the same
    gap flagged independently while building VWAP, D-57, and market
    structure, D-60), and D-29 already settled on a 24h session with no
    defined "closed" window to check against yet.
  - **Daily-loss PnL tracking assumes every allowed intent is exactly
    what gets reconciled** — true today since `OrderGateway` is dry-run
    only (D-14); once real Sim fills exist this needs to sync against
    actual fill prices instead, noted as a gap rather than fixed now.
  - **Churn's reversal counter counts every accepted position change
    except the session's very first entry** — not "reversal" in the
    strict direction-flip sense, matching D-19's own "long/flat/long"
    example (2 changes, both counted) literally rather than only
    counting direct sign flips.
  - **The lag guard's `processingTimeMs` is measured with
    `System.nanoTime()` inside `Pipeline.handle()`** — a deliberate,
    narrow exception to D-11's "no wall clock below ingest": this
    specific measurement is an operational health check that can only
    cause a disarm (a safety action), never alter what price or intent
    gets computed, so it doesn't threaten replay determinism the way a
    trading-logic read of the wall clock would.

  **New `MarketState.lastPriceTicks()`** (D-21 integer ticks): a
  general-purpose "current price" accessor was missing entirely before
  this — needed for PnL marking, added via `Event.priceOf()` in
  `MutableMarketState.bump()`, the same mechanism `Pipeline`'s trace
  journaling already used for a different purpose.

  **Bracket reporting**: `OrderGateway.reconcileDryRun()` now also
  reports `wouldSetStopPriceTicks`/`wouldSetTargetPriceTicks` from the
  `Intent` — dry-run reporting only, no bracket order is ever
  constructed (matches the existing position-side discipline exactly).

  **Refuse-to-arm (D-24), finally implemented**: `OrderGateway.
  refuseToArmReason()` checks `getPosition()` and `getActiveOrders()` on
  `onActivate` — if either is non-empty, `armDenied` is set and the risk
  chain's `armed` filter blocks regardless of the `Armed` setting, until
  a fresh activation finds a clean account.

  **Sizing (Q-06)**: fixed contracts stays a strategy-level decision (a
  strategy that wants to be long requests `targetPosition =
  fixedContracts`, reading the value from config) — `RiskChain`'s size
  cap is a separate safety check (block if a target exceeds
  `maxContracts`), not the mechanism that performs scaling in the first
  place. Chosen over introducing a new "direction vs. size" split into
  `Intent` that the type doesn't currently have.

  Full rebuild clean (both test gates pass), redeployed, session stayed
  healthy (zero `DISARM`s), `risk_config_loaded` confirmed live with the
  exact values from `config/risk.json`. **Not yet exercised beyond
  that**: no strategy currently emits a non-`Intent.none()` value
  (`LevelZoneObserverStrategy` is still a pure observer), so the risk
  chain's filters and the refuse-to-arm path have not been triggered by
  a real changing intent yet — that needs the next piece (entry
  evaluators + a first real strategy) to actually exercise.

- **D-62** (2026-09-18/19) — **First real strategy built:
  `MarketStructureLvnReversalStrategy`, exercising every construct built
  this session together.** Explicit scope, stated by the user directly:
  not expected to be correct or profitable yet, the point is proving the
  whole system holds together end-to-end without breaking. Per the
  user's own design, with every remaining ambiguity resolved by best
  judgment (all documented inline in the class's own javadoc, same
  "temp, revisit later" stance as `marketStructureRulesTemp.md`):

  1. **Area of interest = `MarketStructureView.lastTjl2()`** in the
     current trend — not an arbitrary pick: TJL2 is already the
     system's own named far-side/invalidation boundary
     (`marketStructureRules.md`'s own invariant), so reusing it as the
     trade zone means "pullback zone" and "post-flip retest zone" are
     the same concept for free (`lastTjl2` always means the right thing
     whether it's a real TJL2, the CHOCH bootstrap, or a post-flip
     SBR/RBS) — no separate case needed for either.
  2. **Area-of-interest expiration is implicit, not a timer**: the
     tracked zone is just "whatever `lastTjl2` currently is." The
     moment that value changes (new pair, or a flip), the old one is
     retired automatically and in-progress tracking resets. No time/
     distance-based staleness rule added on top of that.
  3. **Biggest LVN inside the area of interest**: recomputed fresh from
     `VolumeProfileView.zones()` every wake (not tracked by persistent
     zone id across wakes — simpler, accepted for this pass).
  4. **No LVN in the area of interest → no trade**, exactly as stated.
  5. **Entry confirmation = absorption (footprint) OR aggression (big
     trades) OR sweep (liquidity map)** — any one, not all three
     required. Read as "any of these, together or alone" rather than
     requiring unanimous agreement.
  6. **Stop** = a small buffer beyond the area of interest's own far
     edge — the same edge that would flip the trend if broken, so the
     stop and the structural invalidation point are the same idea by
     construction. **Target** = a fixed 2:1 reward:risk multiple.
     Simplest defensible choices, not tuned.

  **Three new entry evaluators** (D-27, `flow-core`, reusable library
  pieces as that decision originally planned): `AbsorptionEvaluator`
  (stateless, footprint-row volume concentration at the touched price),
  `AggressionEvaluator` (stateless, a recent same-direction big trade
  near the touched price), `SweepEvaluator` (**stateful** — detecting a
  sweep means detecting *change* in resting liquidity, which a single
  point-in-time read can't do alone; a strategy owns one instance per
  area-of-interest attempt). All three are v1 proxies for genuinely
  loose trading concepts, documented as such in their own javadoc, not
  claimed to be rigorous.

  **VWAP and order-repeat tracking are read but never gate the trade** —
  folded into the intent's own reason string as non-blocking context,
  included specifically because the user asked that every construct
  built this session get exercised together, not because the strategy
  logic itself needed them.

  **A real architecture gap found and closed along the way**: nothing
  let a strategy read a feature's *value* from `MarketState` at all —
  only `generation()`/`exchangeTimeMs()`/`localTimeMs()`/
  `lastPriceTicks()` existed. `MutableMarketState`'s own very first
  version had already flagged this ("feature-specific accessors... get
  added here... as each real feature is built") but nothing needed it
  until now. Added seven typed accessors (`marketStructure()`,
  `volumeProfile()`, `footprint()`, `bigTrades()`, `liquidityMap()`,
  `vwap()`, `orderRepeats()`), each a safe cast returning null if that
  feature isn't registered or isn't the expected type — required
  `MutableMarketState` to finally hold the `features` map (passed in by
  `Pipeline`, which already had it).

  **A real correctness bug found in self-review, before this ever ran
  live, and fixed — not shipped and flagged after the fact**: the
  strategy optimistically mutates its own `phase`/position-tracking
  fields the moment it *decides* to change position, before knowing
  whether `RiskChain` will actually let that intent through. Since
  `armed=false` is the literal default (D-41), the very first real
  entry this strategy ever attempts would have been silently blocked
  while the strategy's own internal state believed it succeeded —
  managing a phantom position that was never actually reconciled, and
  eventually emitting a "flatten" intent for a position that never
  existed. Fixed structurally, not with a special case: `FlowStrategy`
  gained a new default hook, `onIntentRejected(Intent, String reason)`,
  called by `Pipeline` whenever `RiskChain` blocks an intent that would
  otherwise have gone to `IntentSink`. The strategy saves a snapshot of
  every field a position-changing decision is about to mutate, keyed by
  that intent's own `seq`, and `onIntentRejected` rolls it back exactly
  if the rejected intent matches. Every other strategy defaults to a
  no-op override, since none of them hold state a rejection could
  invalidate yet.

  Also, as part of wiring this in: `strategy.onInit()` now receives real
  values (`fixedContracts`/`maxContracts` from `ExternalConfig`, D-61)
  instead of an empty map — the first real use of that hook, closing
  part of the "`StrategyConfig` is just an empty-map placeholder" gap
  (per-`strategyId`-scoped params and external levels remain
  unaddressed, see todo.md).

  Registered as `market_structure_lvn_reversal` in
  `StrategyRegistrations` — **not** switched on by default (the
  settings panel's `Strategy Id` still defaults to
  `level_zone_observer`); the user selects it explicitly to test.

  Full rebuild clean (both test gates pass), redeployed. **Live-
  verified that the refactor didn't break anything already running**:
  the currently-active `level_zone_observer` session (unaffected by
  which strategy is selected, but very much affected by the
  `MutableMarketState`/`Pipeline` constructor changes underneath it)
  stayed healthy, zero `DISARM`s, trace records continuing normally,
  across two full rebuild-and-redeploy cycles in a row. **Not yet
  live-verified**: the new strategy's own logic — nobody has selected
  `market_structure_lvn_reversal` in the settings panel and watched it
  run yet.

- **D-63** (2026-09-19) — **Liquidity map best-bid/ask bug found live,
  partially fixed, NOT fully resolved — recorded honestly as still
  open, not claimed as done.** Found while monitoring the first real
  strategy's live session per the user's request: `bestAskTicks` sat
  frozen at exactly one value (~$22 below the real market, `4396.90` on
  `@GC`) for an entire session while `bestBidTicks` tracked price
  normally — an ask price below the current bid is never a meaningful
  "best ask" in a real book.

  **Root cause confirmed, not just patched blind**: a genuine resting
  sell order at that price, correctly flagged `isAsk()=true` (so not a
  bid/ask misclassification) — persisted across multiple study
  restarts at the identical decimal price, strongly suggesting one real
  stale/forgotten order rather than a feed artifact.

  **First fix attempt (filtering each side against the other side's own
  raw, unfiltered extreme) was tried and found fragile**: it worked when
  only one side had a stray far row, but surfaced the *same class* of
  bug on whichever side happened to have the anomaly, across different
  sessions (`bestBidTicks` was the one stuck once, `bestAskTicks`
  another time). Replaced with anchoring against the last genuinely
  **traded** price (from `TickEvent`, now tracked in
  `LiquidityMapFeature` too) — trades are authoritative in a way
  resting orders aren't. Applied in two places: `LiquidityMapFeature.
  bestBidTicks()`/`bestAskTicks()` (flow-core) and
  `FlowRuntimeStudy.update(DOM)`'s own separate, duplicate best-bid/ask
  computation (flow-runtime, previously unfixed entirely — feeds
  `DomEvent`'s top-of-book fields and the diagnostic log).

  **Live-checked after the fix — still not fully resolved**: the
  diagnostic log (`logs/liquidity_map_feature.log`) shows the fix
  working *most* of the time (sane, tight bid/ask spreads), but the
  same stale `4396.90` value still intermittently reappears, well after
  the session had real ticks flowing (ruling out "no trade seen yet" as
  the explanation for at least some occurrences). Exact remaining
  trigger condition not identified — plausibly a genuine transient gap
  in top-of-book rows in a specific DOM snapshot, but not confirmed.
  **This bug is left open, not closed** — flagged in `todo.md` to
  revisit with more targeted live debugging than periodic log-polling
  allowed this session. Does not affect the first real strategy's
  actual entry logic (D-62's `SweepEvaluator` reads `bidSizeAt`/
  `askSizeAt` at an exact price, never `bestBidTicks`/`bestAskTicks`) —
  it affects the liquidity-map heatmap's window centering (D-59) and
  the periodic journal snapshot (D-58) only, both display/historical
  concerns, not the live trading decision path.

- **D-64** (2026-09-19) — **Market structure gets historical warm-start
  and chart drawing, both per direct user request.** Confirmed first
  (the user asked directly): market structure was live-only, forward-
  only from attach, same as every other construct here — no historical
  data was ever pulled in. The user considers this construct
  specifically different from the others (it should use both historical
  and live data), so this closes that gap.

  **Historical warm-start**: on `startSession()`, before any live event
  reaches the pipeline, `warmStartMarketStructure()` pulls up to N
  closed bars (`Market Structure > Historical Warm-Start Bars` setting,
  default 100, configurable per the user's explicit ask) directly from
  `ctx.getDataSeries()` and feeds each one through
  `MarketStructureFeature.onEvent()` as a synthetic `BarEvent`
  (negative `seq`, never colliding with the real `Sequencer`'s own).
  Bypasses `Sequencer`/`Pipeline` entirely — this is a one-time
  bootstrap before the live event stream exists, not part of the
  replay-relevant record.

  **Real bug found and fixed along the way**: `DataSeries.isComplete()`
  returns `false` even for bars from many minutes in the past on this
  jar — confirmed directly (a diagnostic check against index
  `size()-10` came back `false`, with no exception reading its OHLC/
  volume/time, ruling out a data-access problem). This made the first
  version of the warm-start loop discard every single bar
  (`actualBars: 0` against `availableBars: 646`), silently producing an
  empty warm-start instead of an error. Fixed by not depending on
  `isComplete()` at all: the series' own last index is unconditionally
  treated as "current/forming" and excluded — exactly matching how the
  *live* `onBarClose(DataContext)` handler already behaves (it never
  checks `isComplete()` either, trusting its own callback timing). Also
  guarantees zero overlap with future live bar-close events. **Live-
  verified working correctly this time**: a batch of 100 real historical
  bars produced 8 genuine `PULLBACK_VALID` events, one `TJL_FORMED`, and
  one real `FLIP` (DOWN→UP), all firing synchronously and correctly
  through the exact same state machine live events use.

  **Chart drawing**: `redrawMarketStructureFigures()`, following
  `marketStructureRules.md` §8's color scheme exactly (TJL1 blue, TJL2
  orange, A+ purple, SBR/RBS gray, DT/DB yellow) — one `Box` + `Label`
  per non-null zone, drawn from session start to now (same convention
  `redrawVolumeProfileFigures()` already uses for POC/VAH/VAL, since a
  `ZoneRange` carries no anchor time of its own). SBR-vs-RBS and DT-vs-DB
  share one field each (`lastSbrRbs()`/`lastDtDb()`) — the correct label
  is inferred from the *current* trend (Down trend → the last flip was
  Up→Down → SBR/DT; Up trend → RBS/DB), so no new field was needed to
  track which flip direction produced them. New settings: `Market
  Structure > Historical Warm-Start Bars` (default 100) and `> Draw on
  Chart` (default true). Full rebuild clean, redeployed, session
  healthy. **Warm-start's own correctness is now live-confirmed; the
  drawing itself is not yet visually confirmed** — same "built, not yet
  eyeballed" gap every other construct's first drawing pass has had.

- **D-65** (2026-09-19) — **Real bug found by the user's own visual
  read of the chart, not a documented ambiguity: continuation
  confirmation gave up after the first non-confirming candle instead
  of waiting for a later one.** `marketStructureRules.md` §3 says "if
  **a later candle** closes below that low → continuation confirmed" —
  the wording itself implies confirmation can take several candles.
  D-60's original implementation checked only the very next trend-
  colored candle after a pullback validated, and if that specific one
  didn't confirm, discarded the whole attempt (`pullbackState = NONE`)
  unconditionally. Any pullback whose real confirming candle wasn't
  the immediate next one was silently thrown away — exactly why the
  user's own downtrend read (expecting a TJL1 near "LVN -5") found
  nothing there: a genuine pullback had very likely formed and been
  discarded before its actual continuation candle arrived.

  This was a misreading on inspection, not one of the 7 already-
  flagged, still-unconfirmed resolutions in `marketStructureRulesTemp.md`
  — worth being precise about the distinction: those seven are
  genuinely ambiguous in the source text; this one wasn't ambiguous,
  it was read wrong.

  **Fix**: once a pullback is `VALID`, a trend-colored candle that
  fails to confirm continuation is now simply ignored — not added to
  `pullbackBars`, doesn't reset anything, doesn't end the attempt. The
  state stays `VALID`, waiting for a later candle (of either color) to
  eventually confirm. Only a genuine confirmation resolves the
  pullback now; nothing gives up on one anymore.

  Full rebuild clean (both test gates pass), redeployed. **Live-
  verified immediately, dramatically**: the same 100-bar historical
  warm-start that previously produced only 1 `TJL_FORMED` event now
  produces 4, in the same batch — direct, measurable confirmation that
  pullbacks were genuinely being lost before, not just a theoretical
  concern.

- **D-66** (2026-09-19) — **Entry-signal arrows + real order-submission
  code, built at the user's explicit request; the two land very
  differently on the risk spectrum.**

  Arrows: `MarkerAdapter` gained an `arrow()` factory (`Enums.MarkerType.ARROW`,
  same reflection pattern as `circle()` — `Enums.MarkerType`/`Size`/`Position`
  don't resolve as source-level types against this jar, confirmed again).
  `FlowRuntimeStudy.onIntentChanged` now also calls `recordEntrySignal()`,
  appending to a bounded (200) list drawn by `redrawEntrySignalFigures()`
  behind a new `FLOW_DRAW_ENTRY_SIGNALS` setting. Only fires for intents
  the risk chain already **allowed** — with `armed=false` (default) the
  list stays empty, matching "order signals," not "everything considered."

  Real order submission: `OrderAdapter` (new file) extends the same
  reflection pattern to `Enums.OrderAction`/`TIF` (also fail to resolve
  at source level, confirmed the same way), giving `OrderGateway` two new
  methods — `submitRealEntry()` (market entry via the SDK's direct
  `buy(int)`/`sell(int)`, no reflection needed for those two) and
  `submitRealBracket()` (stop + target via `createStopOrder`/
  `createLimitOrder` + `submitOrders`, meant to be called only from
  `onOrderFilled` once a real fill is confirmed, never speculatively
  ahead of one).

  **Deliberately not wired to anything.** Neither method has a caller
  anywhere in this codebase — `onIntentChanged` still only calls
  `reconcileDryRun()`. Reasoning: `CLAUDE.md`'s hard rule requires exact
  account/instrument/side/quantity/order-type stated and one last
  explicit confirmation *immediately before* submitting, which a
  per-tick automatic strategy loop cannot satisfy once wired into its
  normal path — "arm it and let it trade" is a structurally different
  request than "place this one order now," and the rule is written
  around the latter. The first real call site gets added deliberately,
  in the same turn as that specific confirmation, not as a side effect
  of arming. Q-02(b) (`OrderContext` write-call thread affinity) is
  still genuinely untested — these methods compile clean and pass the
  safety-hook reflection test but have never been exercised against the
  live SDK.

- **D-67** (2026-09-19) — **First live one-shot real-order test: entry
  worked, bracket-attach didn't. Root cause suspected, fix applied,
  needs retest next session before this is trusted.**

  **What happened, in order.** With the user's explicit in-the-moment
  confirmation (Sim account, `@GC`/`GCZ6`, SELL 1, market entry, 10-tick
  SL/TP), a one-shot test-trade path was wired into `onActivate`
  (`FIRE_TEST_TRADE_KEY`, off by default, guarded so it can never fire
  twice per Study instance). On activation it correctly submitted a real
  SELL 1 market order via `OrderContext.sell(int)`. The order filled on
  the account/broker side — **confirmed directly from the MotiveWave
  Account view**: `simulated` account, `GCZ6`/COMEX, filled 1 @ 4416.1,
  Total P/L moved to -150.00. So `submitRealEntry()`'s actual submission
  mechanism works: this is a real fill on a real (Sim) account, not a
  journal artifact.

  **What didn't happen: `onOrderFilled` was never called.** The bracket
  (stop/target) is submitted from `onOrderFilled`, gated behind a
  `pendingTestBracket` flag set right after `submitRealEntry()`. Checked
  the session's `decisions.jsonl` directly: no `ORDER_FILLED` line
  anywhere, despite the session staying alive and healthy (continuous
  heartbeats) for 3+ minutes after the confirmed fill — ruling out a
  crash or a stuck/dead instance. Cross-checked an unrelated older
  session's log and found one genuine `ORDER_FILLED` entry there,
  confirming the hook *can* fire correctly on this Study class in
  general (that one is presumed to be from an order placed manually
  through MotiveWave's own trade panel, not this codebase). So the gap
  is specific to how this particular order was submitted, not the hook
  mechanism itself. **Consequence at the time**: the position sat open
  and completely unprotected (no stop, no target — confirmed from the
  Orders panel showing Active (0)) until the user closed it manually.

  **Working theory (unconfirmed — SDK is closed-source, no way to check
  directly).** `OrderContext.buy(int)`/`sell(int)` return `void` — no
  `Order` reference is ever handed back to the strategy. Every other
  order-creation method on `OrderContext` (`createMarketOrder`,
  `createStopOrder`, `createLimitOrder`) returns an `Order`, meant to be
  passed to `submitOrders(Order...)`. Hypothesis: `buy`/`sell` are
  "quick action" convenience methods (plausibly intended for
  discretionary/manual panel use) that don't register the resulting
  order in whatever internal table the platform uses to attribute a
  later fill callback back to a specific `Study` instance — without an
  `Order` object, there's no handle for that bookkeeping. This is
  inference from one data point, not a confirmed platform finding;
  flagging the uncertainty explicitly rather than overclaiming it.

  **Fix applied** (untested — market was closed for the rest of this
  session): `OrderAdapter` gained `marketOrder(ctx, isBuy, qty)`
  (`createMarketOrder(Enums.OrderAction, int)` via the same reflection
  pattern already used for the stop/limit orders). `submitRealEntry()`
  now does `ctx.submitOrders(OrderAdapter.marketOrder(...))` instead of
  `ctx.buy(int)`/`sell(int)` — same tracked-order family as the bracket,
  for every order type now.

  **State left for next session**: naked position closed manually by
  the user, confirmed flat. The one-shot test-trade checkbox
  (`FIRE_TEST_TRADE_KEY`) is still checked in settings and
  `testTradeFiredEver` is a per-instance latch — a fresh Study reload
  next session will have that latch reset, so **the test will fire
  again automatically the moment the study reactivates**, unless the
  checkbox is unchecked first. Deliberate: this is what lets the retest
  happen without another round of code changes, but means walking up to
  next session's chart with that box still checked is itself the
  "immediately before submitting" confirmation being exercised again,
  same as the first time — not a standing authorization to leave it be
  indefinitely. Strip the whole test-trade path out once this is
  confirmed working (per the user's own instruction), not before.

- **D-68** (2026-09-20) — **`MarketStructureFeatureTest` built: first
  synthetic-sequence regression test for `MarketStructureFeature` (D-60),
  weekend/no-live-market work per the user's own framing ("continue with
  all the parked items in todo") while the user reviews
  `marketStructureRules.md` separately.** Same discipline as
  `TriggerEvaluatorTest` (D-45) — plain `main()`, no JUnit, direct
  synthetic bar sequences, no MotiveWave involved
  (`flow-core/src/com/flow/flow/MarketStructureFeatureTest.java`). Wired
  into `build/build.sh` as a third gate (after the trigger test, before
  compiling `flow-runtime`) — a future change that breaks the state
  machine now fails the build, not just a live session.

  **What it proves, and what it doesn't**: 30 checks across 5 scenarios
  (CHOCH bootstrap, pullback validation anchored to the run's first
  candle per resolution #2, the 1%-of-own-range zone math per resolution
  #4 hand-verified against the real code and confirmed exact, the D-65
  "don't give up on a non-confirming candle" bugfix, the first
  flip-with-a-real-pair exercising resolutions #5 and #6 together, and
  the CHOCH-driven bootstrap flip) all pass against the **current**
  implementation. This is a regression baseline proving the code does
  what `marketStructureRulesTemp.md`'s guesses say it should do — it is
  **not** independent confirmation that those guesses are correct.
  `marketStructureRules.md`'s pending review is still the open item; when
  it lands, any contradicted resolution means this test's expected values
  change too, not just the feature.

  **Real finding surfaced while writing it, not yet acted on — flagged to
  the user rather than fixed unilaterally, since it touches the state
  machine under active review**: `performFlip()`'s CHOCH-driven branch
  (`!hasRealPair`, i.e. the very first flip, before any real TJL pair has
  ever formed) returns without ever calling `listener.onFlip(...)`. Since
  `MarketStructureFileLogger` only logs discrete events through that
  listener interface, today's behavior is that **the first flip is
  invisible in `market_structure_feature.log`** — the trend genuinely
  changes (confirmed by this test) and chart drawing still reflects it
  correctly (`redrawMarketStructureFigures()` polls `trend()` directly,
  not through the listener), but there is no journaled record explaining
  why. Test `testChochDrivenFirstFlip_NoRealPair` locks in this as
  *current, observed* behavior rather than silently relying on it —
  deliberately named to make clear it's flagged, not endorsed.

  **Second, incidental finding while hand-deriving test numbers**: the
  flip-watch (`checkFlip`) runs on *every* bar against whatever
  `lastTjl2` currently is, which is the CHOCH bootstrap point zone until
  a real TJL pair forms. If that CHOCH close sits inside the price range
  a later pullback trades through, two ordinary pullback-formation closes
  below it trigger a premature CHOCH-driven flip before the pullback ever
  reaches VALID — not a bug (CHOCH really is supposed to be a live
  invalidation boundary from bar one), but a real, previously
  undocumented sensitivity: how far price has drifted from the session's
  very first candle governs whether early pullbacks can even complete
  before getting reinterpreted as a trend flip. Worth keeping in mind
  when reviewing `marketStructureRules.md`'s own framing of CHOCH's role,
  not itself a defect in the test or the feature.

- **D-69** (2026-09-20) — **Session-boundary-detection machinery built:
  `SessionBoundary` (flow-core), 17:00 America/Chicago rollover, wired
  into `RiskChain` (D-19's reversal cap, D-30's "daily"-loss PnL) and
  `VWAPFeature` (D-57's own deferred true daily reset).** Closes the gap
  flagged independently while building VWAP (D-57) and market structure
  (D-60), and answers Q: "what exact clock time is the 24h session
  boundary" — decided directly with the user (17:00 CT, matching CME
  Globex's real electronic-session rollover for `@GC` and D-34's existing
  Asia/London/NY windows already being in that zone), not derived from
  any prior doc.

  **Deliberately narrow scope**: `SessionBoundary.sessionIdFor(epochMillis)`
  (pure, `ZoneId`-based, handles DST on its own) plus a small stateful
  `Tracker` (`advance()`, drain-thread-only, first observation never
  itself a transition so a feature attaching mid-session doesn't see a
  spurious reset). Wired into exactly two places, both self-contained
  counters safe to zero out: `RiskChain.reversalsThisSession`/
  `realizedPnlTicks` (checked lazily at the top of `evaluate()`, since
  that's only ever called on an intent change anyway — no need to check
  every event) and `VWAPFeature`'s two running totals (checked on every
  event including `ClockEvent`, so a quiet book still resets on schedule,
  same reason D-23 injects synthetic clock events at all). **Deliberately
  NOT wired**: D-21's `PriceCodec` tick anchor (flow-runtime) and the
  journal's per-activation session file — re-anchoring ticks mid-run
  would shift the coordinate system every tick-keyed feature already has
  live state in (zone ranges, VP buckets, TJL zones), a materially bigger
  and riskier change than zeroing a counter; the journal file boundary is
  already handled at a coarser grain by MotiveWave's own study lifecycle.
  `RiskChain.lastPosition`/`entryPriceTicks` are also deliberately left
  untouched by the reset — they track the actual live position, which
  must survive a calendar rollover unchanged (flatten-at-session-end
  isn't built yet, so a position can genuinely still be open when the
  boundary crosses).

  Verified with three new synthetic test files (`SessionBoundaryTest`,
  10 checks on the pure rollover math; `SessionResetWiringTest`, 17
  checks exercising the actual `RiskChain`/`VWAPFeature` reset behavior
  end to end), both wired into `build/build.sh` as permanent gates. Full
  rebuild clean (5 test gates total now), replay-equivalence regression
  still passes.

  **Real finding surfaced while building the RiskChain test, flagged to
  the user, not fixed unilaterally (safety-relevant logic)**:
  `RiskChain.checkDailyLoss()` runs unconditionally on every `evaluate()`
  call, including an exit — there is no "this intent reduces risk"
  exemption. An exit whose own mark-to-market loss (at the price it's
  evaluated against) alone breaches the daily-loss limit is **blocked
  exactly like an entry would be**, which means a position can get stuck
  open past the point where flattening it would be the correct move — the
  opposite of what a "kill switch" should do. One provable consequence:
  because of this, a **flat** position's `realizedPnlTicks` can never
  legitimately end up past the limit through the chain's own gate at all
  (the exit that would cross the line gets blocked before it can realize
  it) — confirmed by construction while writing the test, not just
  argued. The obvious fix is an explicit exemption for
  risk-reducing/flattening intents from `checkDailyLoss` (and arguably
  `checkSizeCap`), but this touches the daily-loss kill switch's actual
  behavior, so it's recorded here rather than changed without sign-off.

- **D-70** (2026-09-20) — **Book imbalance built: `LiquidityMapView.
  imbalanceAtLevels(int n)`, closing README's original "imbalance at N
  levels" item (line 175's own phrase) that had never gotten built.**
  Added directly to `LiquidityMapView`/`LiquidityMapFeature` rather than
  as a separate feature/view — it's a pure derived read over data
  `LiquidityMapFeature` already stores (D-58's bid/ask rows), not new
  state to track. Sums resting size over the N rows closest to the
  market on each side (row COUNT, i.e. "levels" — deliberately not a
  tick-distance window, unlike the existing `bidRowsWithin`/
  `askRowsWithin`), returns `(bidSum-askSum)/(bidSum+askSum)` in
  `[-1, 1]` (positive = bid-heavy). A side with fewer than n rows uses
  every row it has rather than padding with zeros, so a thin book reads
  as genuinely thin. Null only when there's no book data on either side
  at all (not ready yet) — distinct from a genuine `0.0` reading, same
  convention `bestBidTicks()`/`bestAskTicks()` already use.

  Verified with `LiquidityMapFeatureTest` (5 checks: no-data-yet,
  nearest-N-both-sides, thinner-side-uses-what-it-has, one-side-empty,
  n=0 degenerate case), wired into `build/build.sh` as a sixth gate. Full
  rebuild clean, replay-equivalence regression still passes. **Not wired
  into the one existing real strategy** — nothing currently needs it,
  matching README's own "don't over-abstract ahead of need"; it's just
  available on `MarketState` for whichever strategy wants it next, same
  as every other derived DOM view.

- **D-71** (2026-09-20) — **`marketStructureRules.md`'s review is in.**
  The user answered all 7 originally-flagged ambiguities directly
  (`market_structure_review.md`), folded into that document's own text
  now (each section marked `REVIEWED 2026-09-20`). This is a doc update,
  not a code change — recorded here because of what it implies, not
  because anything got rebuilt yet.

  **5 of the 7 answers contradict `marketStructureRulesTemp.md`'s
  guesses and what `MarketStructureFeature.java` (D-60) actually runs
  today** — concrete, specific disagreements, not philosophical ones:
  1. **CHOCH anchor** (point 3) — should be set at *initialization time*
     (candle open if init lands exactly at a candle start, else the
     first live price) — the code currently waits for the first bar's
     **close** instead (the old guess).
  2. **Pullback validation past 2 candles** (point 1) — should be a
     *sliding* consecutive-pair check (`current vs. immediately
     preceding`) — the code currently checks every candle against the
     run's *first* candle only (the old guess).
  3. **The "1%" base** (point 4) — should be the candle's own **body
     height** (`|close-open|`) — the code currently uses the full
     **high-low range** (the old guess), which produces different (and
     currently probably wider) TJL/DT/DB zones than intended.
  4. **Flip-watch's reference after a flip** (point 5) — should become
     **DT/DB**, replacing `lastTJL2` in that role — the code currently
     leaves `lastTJL2` equal to the old SBR/RBS value and never involves
     DT/DB in flip-watch at all (the old guess, which
     `MarketStructureFeatureTest`'s own "Resolution #5" checks currently
     lock in as correct — they now test the *wrong* behavior).
  5. **DT/DB search window start** (point 6) — should start at the
     **SBR/RBS candle's own open time** — the code currently starts from
     the **A+ candle** (`tjl1AnchorBar`) instead (the old guess).

  Points 2 (pullback keeps growing) and 7 (CHOCH permanently retired)
  matched the guesses exactly — no change needed there.

  **2 new ambiguities surfaced by the review's own answers, not
  pre-existing** (both written up in full in `marketStructureRules.md`'s
  closing section, §1a and §6):
  - Point 3's "at initialization time" CHOCH anchor collides with D-64's
    historical warm-start, which feeds bars through the same state
    machine *before* any live event — does CHOCH use whatever's fed
    first (warm-start included) or specifically the first live price?
  - Point 5/6's chained-flip mechanism (`CHOCH1 → DT1 → CHOCH2 → DB1`)
    only ever shows DT/DB updating across the chain — does A+/SBR-RBS
    stay frozen at the first flip's assignment for the whole chain, or
    get reassigned somehow at each link too?

  **Not yet acted on**: `MarketStructureFeature.java`,
  `MarketStructureFeatureTest` (D-68), and `MarketStructureFileLogger`'s
  behavior all need to change to match points 1/4/5/6 above — this is a
  real implementation rework, not a doc-only fix, and hasn't been
  started. `marketStructureRulesTemp.md` is marked superseded (banner
  added) rather than deleted, kept as the historical record of what was
  actually built against. Sequencing and the 2 new ambiguities are the
  user's call, not decided here.

- **D-72** (2026-09-20) — **The 2 new ambiguities D-71 raised are now
  answered too** (`market_structure_new_review.md`), folded into
  `marketStructureRules.md` (§1a, §6, closing section). Both resolved
  cleanly, but one of the two answers introduces a real gap beyond what
  D-71 already flagged.

  1. **CHOCH-vs-warm-start (point 8)**: resolved — **historical
     warm-start bars count as "the first candle."** If N historical bars
     are fed through `MarketStructureFeature` before any live event
     (D-64), the CHOCH anchor is the first fed bar's **open**, not
     skipped and not overridden once live data begins. This doesn't
     reopen point 3's separate open-vs-first-live-price branch for
     whenever no warm-start bar exists at all — that still stands as
     originally answered.
  2. **Chained-flip A+/SBR-RBS fate (point 9)**: resolved, and more
     precisely than either of D-71's two guessed readings — neither
     "frozen forever" nor "reassigned every link." **A+/SBR-RBS are
     tradeable for exactly the chain's first flip, then permanently drop
     out for good.** From the chain's second flip onward, the tradeable
     set is always exactly `{DT, DB}`: whichever of the two was just
     crossed gets replaced by a freshly computed one of the same kind
     (using D-71's already-established window-chaining rule), the other
     carries over untouched, direction alternating with the newly
     confirmed trend.

  **New gap surfaced by point 9's own answer, not resolved by it**: point
  9 introduces "tradeable levels" as a first-class concept —
  `MarketStructureView`'s interface has no notion of it at all today
  (only raw A+/SBR-RBS/DT-DB zone values, no "which of these should a
  strategy currently treat as tradeable" signal), so this is a real API
  gap beyond D-71's 5 already-flagged value-computation fixes, not just
  another arithmetic correction. It also leaves one thing unanswered:
  once a real new TJL1/TJL2 pair finally ends a chain, does the existing
  `{DT,DB}` tradeable pair drop out immediately (replaced outright) or
  stay tradeable alongside the fresh pair until the next flip? (The
  flip-watch *reference* itself already reverts to the fresh `lastTJL2`
  automatically per point 5's own wording — only the tradeable-set
  question is open.) Full detail in `marketStructureRules.md`'s closing
  section, point 10.

  **Still not acted on**: no code has changed. The rework this decision
  and D-71 together describe (5 behavioral fixes plus a new
  tradeable-levels concept) remains fully queued, sequencing is the
  user's call.

- **D-73** (2026-09-20) — **`marketStructureRules.md`'s last open point
  (10) is answered, directly in chat: the market-structure rules review
  is now fully closed, all 10 points (the original 7 plus 3 that
  surfaced along the way).** Resolution, stronger than either reading
  D-72 offered: the moment a real new TJL1/TJL2 pair forms (at
  continuation-confirmed, not waiting for the next flip), **every
  previously-tradeable DT/DB level becomes non-tradeable immediately**,
  fully replaced by the fresh pair, and flip-watch's reference switches
  back to the fresh `lastTJL2` at that same moment — from then on, only
  the latest TJL1/TJL2 pair is tradeable, exactly like normal
  (non-chained) operation. No residual ambiguity.

  **Where this leaves things**: `marketStructureRules.md` now has zero
  open ⚠️ items — every section is marked REVIEWED/RESOLVED. The full
  rework list stands exactly as D-71/D-72 already described: 5 concrete
  behavioral fixes to `MarketStructureFeature.java` (CHOCH anchor timing
  and value, sliding-pair pullback validation, body-height 1% base,
  DT/DB as the post-flip reference, DT/DB window anchored to the
  SBR/RBS candle) plus 1 new interface concept (`MarketStructureView`
  needs a "which levels are currently tradeable" notion it doesn't have
  today, including this decision's own immediate-replacement-on-new-pair
  rule). `MarketStructureFeatureTest` (D-68) needs the same rework, not
  just the feature — it currently locks in the wrong guesses as
  passing tests. **Still not started** — no code has changed under any
  of D-71/D-72/D-73; this decision only closes the rules question, the
  implementation work is queued, sequencing is the user's call.

- **D-74** (2026-09-20) — **Market structure rework complete:
  `MarketStructureFeature`/`MarketStructureView` now implement the fully
  reviewed rules (D-71/D-72/D-73), not `marketStructureRulesTemp.md`'s
  superseded guesses.** All 5 concrete behavioral fixes plus the new
  tradeable-levels concept are built and passing.

  **The 5 fixes**:
  1. CHOCH anchor = the first-processed bar's **open**, not close (works
     for warm-start bars too, point 8 — same code path, no special-casing).
  2. Pullback validation past 2 candles is a **sliding consecutive-pair**
     check (current vs. immediately preceding), not anchored to the
     run's first candle.
  3. The "1%" zone offset is **body height** (`|close-open|`), not the
     high-low range — `rangeOffsetTicks` renamed `bodyOffsetTicks` and
     recomputed accordingly, applied identically to TJL1/TJL2 and DT/DB.
  4. On a flip, the fresh **DT/DB (not SBR/RBS) becomes flip-watch's
     reference** — `lastTjl2` is reassigned to the freshly computed
     DT/DB at every flip, not left pointing at the old TJL2 value.
  5. The DT/DB search window starts at the **SBR/RBS candle's own open**
     (the TJL2-producing bar), not the A+ candle — tracked via a new
     `windowAnchorBar`/`barsSinceAnchor` pair that replaces the old
     `tjl1AnchorBar` entirely (A+'s own candle is no longer load-bearing
     for anything). Chains correctly across multiple pair-less flips:
     each flip's window starts from wherever the *previous* flip's
     DT/DB was computed, not always from the original SBR/RBS candle —
     verified with a live-diverging test case (see below).

  **The new concept**: `MarketStructureView.TradeableLevel` (`TJL2`,
  `A_PLUS`, `SBR_RBS`, `DT`, `DB` — TJL1 deliberately excluded, it's
  never itself the tradeable/invalidation boundary) and
  `tradeableLevels(): Set<TradeableLevel>`, implementing the full
  lifecycle points 9/10 describe: empty while only CHOCH is active;
  `{TJL2}` in normal operation; `{A_PLUS, SBR_RBS, DT-or-DB}` for
  exactly the chain's first flip; `{DT, DB}` from the second flip
  onward, refreshing whichever side was just crossed (`lastDt()`/
  `lastDb()` are now two separate fields, not one shared `lastDtDb()`
  slot — required, since both must persist simultaneously mid-chain);
  reset to `{TJL2}` (and `lastAPlus`/`lastSbrRbs`/`lastDt`/`lastDb` all
  nulled) the instant a fresh real TJL pair forms, at any point, not
  waiting for the next flip.

  **Downstream consumers updated to match**: `MarketStructureFileLogger`
  (flow-runtime) now logs `tradeableLevels` on every `TJL_FORMED`/`FLIP`
  line. `FlowRuntimeStudy.redrawMarketStructureFigures()` draws `lastDt()`/
  `lastDb()` as separate boxes (no more trend-inferred relabeling of one
  shared slot) and dims non-tradeable zones rather than hiding them, so a
  chart-watcher can see what a level *was* without the drawing implying
  it's currently actionable. `MarketStructureLvnReversalStrategy` gained
  a guard so its area-of-interest read (`ms.lastTjl2()`) only fires when
  `tradeableLevels()` actually contains `TJL2`, `DT`, or `DB` — fixes the
  exact CHOCH foot-gun point 3 warned about (the strategy could
  previously have treated the non-tradeable CHOCH bootstrap zone as a
  real area of interest). Not expanded to also trade off `A_PLUS`/
  `SBR_RBS` — that stays a single-zone area-of-interest concept per its
  own D-62 design; using the other tradeable levels is separate,
  not-yet-scoped strategy work.

  **Verification**: `MarketStructureFeatureTest` (D-68) rewritten
  entirely — 53 checks, including one continuous scenario that forms a
  real TJL pair, chains through 3 flips (CHOCH1→CHOCH2→CHOCH3), and ends
  the chain with a fresh real pair, exercising points 5/6/9/10 together
  against hand-derived numbers. One check is deliberately adversarial:
  the CHOCH2 DT/DB-window test uses a candle (the original SBR/RBS bar)
  whose own low is numerically LOWER than the correct chained anchor's
  low — if the window had wrongly stayed anchored to the original
  SBR/RBS candle forever (the pre-rework behavior), the test's expected
  DB value would be provably different from what a correctly-chained
  window produces, so this isn't just checking numbers match, it
  actively distinguishes the two possible implementations. All 53 pass
  against the real code, on the first run, no debugging needed. Full
  rebuild clean (6 `flow-core` test gates + the safety reflection test),
  replay-equivalence regression still passes, deployed. **Not yet
  live-verified** — market's closed, consistent with everything else
  built this weekend.

- **D-75** (2026-09-20) — **Raw-log auto-deletion built: `LogRetention`
  (flow-core), 48h window, per the user's own stated policy** (triaged
  from a `temp.txt` scratch note into `todo.md` §4b earlier this
  session). Deletes `raw.jsonl` from any session directory whose
  embedded start time is older than 48 hours — `decisions.jsonl` and the
  directory itself are **never** touched, since that tier is already
  exactly what the user called "the analysed data" (price trace,
  liquidity snapshots, etc. all live there per D-15's own design, not in
  `raw.jsonl`). Closes the *duration* half of D-07/D-35's long-open
  "2-3 days, TBD" figure with a firm number; Q-10 (whether per-order DOM
  detail gets captured in the raw tier at all) is unaffected, still open.

  Zero SDK dependency — pure file I/O, so it lives in flow-core (not
  buried as a private `FlowRuntimeStudy` helper) and is testable the same
  way everything else this session was: `LogRetentionTest` runs against a
  real temp directory (not mocked), 13 checks covering the directory-name
  parsing (including a strategyId containing underscores, which could
  otherwise be mis-parsed, and a negative `instanceId`, since
  `System.identityHashCode` can return one) and the actual prune
  behavior (an old directory's `raw.jsonl` is deleted while its
  `decisions.jsonl` survives; a recent directory is untouched; a
  non-matching directory name is skipped entirely rather than
  misinterpreted; an old directory with no `raw.jsonl` at all doesn't
  crash). Wired into `build/build.sh` as a seventh gate.

  Called once per activation from `FlowRuntimeStudy.startSession()`
  (same "check once at session start" pattern risk config loading
  already uses), journaled as a `log_retention_pruned` decisions-tier
  record so a session's own startup is traceable. Full rebuild clean,
  replay-equivalence regression still passes, deployed. **Not yet
  live-verified** — the code path only runs on an actual MotiveWave
  activation, which hasn't happened since this was built (market's
  closed).

- **D-76** (2026-09-20) — **Offline journal comparison/summary tooling
  built, per README's own plan** ("Comparison across strategies is done
  offline, from the journals... can be Python"), §5 of `todo.md`.
  `analysis/journal_summary.py` (new top-level directory, first Python in
  this repo — matches README's own "the constraint is the *runtime* is
  all-Java, not every script").

  Two modes: single-session summary (session header, per-record-type
  counts, a health check for `DISARM`/`GAP_MARKER`, and a chronological
  list of every non-heartbeat "notable" record — rendered compactly for
  known types like `intent_changed`/`risk_verdict`/`level_trace`/
  `zone_trace`, falling back to raw JSON for anything new so it never
  silently drops an unrecognized record type) and two-session comparison
  (per-type count diff, `intent_changed` sequence equality check).
  Deliberately scoped to `decisions.jsonl` only — not `raw.jsonl`
  (tick-level, not meant for human reading, D-15's own split) and not
  the diagnostic `*_feature.log` files (a separate, throwaway thing from
  the real two-tier journal this tool is about).

  Verified directly against real recorded sessions under `logs/` (not
  synthetic — this tool's whole job is reading real journal output), both
  modes, output confirmed sensible by inspection. No test harness of its
  own (matches this project's existing stance that analysis tooling is
  outside the Java-only runtime's testing discipline) — its own output
  was manually checked against the same sessions' known content instead.

- **D-77** (2026-09-20) — **Backtest data source: MotiveWave's own
  `DataSeries`, ~24.2 days of `@GC` 1-minute OHLC, not TradingView.**
  Platform question answered by a throwaway experiment in
  `../motivewave/experiments/` (`HistoricalDepthProbe.java`, full
  progression in `../motivewave/docs/dynamic/findings.md`, 2026-09-20):
  MotiveWave's `DataSeries` reached exactly 25,000 1-minute `@GC` bars
  (2026-08-25T15:34Z through 2026-09-18T21:00Z) after raising the
  client-side "Max Linear Bars" chart setting and manually scrolling the
  chart back — the series stopped precisely at that configured number,
  not an independent, lower data-provider wall, so more may be reachable
  by raising the setting further. Not pursued further this session — the
  user chose to proceed with the ~24.2 days already reached rather than
  push for more.

  Per the user's own stated preference ("if the data is provided i want
  to keep everything in motivewave only"), this closes the data-source
  question in MotiveWave's favor — the TradingView fallback that was on
  the table if MotiveWave's own data proved insufficient is not needed.
  `HistoricalOhlcExporter.java` (same experiments directory) dumps the
  currently-loaded `DataSeries` to a plain CSV
  (`timestampMs,open,high,low,close,volume`) in `FLOW_V2/analysis/data/`
  — deliberately written into FLOW_V2's own tree rather than the
  experiments repo's `logs/`, since the CSV is a dataset FLOW_V2's
  backtest consumes, not a finding about the platform itself.

  **Revisit later**: ~24.2 days is enough to exercise the backtest
  engine and get a first read, but is genuinely thin for a swing/trend
  system that needs many pullback-flip cycles to say anything
  statistically meaningful — worth pushing "Max Linear Bars" higher (or
  actually falling back to TradingView) once the harness itself is
  proven out and more history is actually wanted.

- **D-78** (2026-09-20) — **Order-flow execution rules distilled for a
  future review, prep work only — not picked up yet, per the user's own
  explicit instruction.** Same precedent as `marketStructureRules.md`,
  but distilled from the already-written code itself (no external spec
  exists for this one) rather than external Pine Script drafts: an
  agent read `AbsorptionEvaluator`/`AggressionEvaluator`/`SweepEvaluator`
  and `MarketStructureLvnReversalStrategy` in full, plus D-27/D-62, and
  wrote `docs/dynamic/orderFlowExecutionRules.md` — same structure as
  the market-structure precedent (numbered sections, ⚠️ AMBIGUOUS
  markers, a closing "Open questions, collected" section), touching no
  other file.

  **15 points flagged**, covering: LVN "biggest" selection (width vs.
  volume, no persistent identity across wakes), the shared
  `TOUCH_TOLERANCE_TICKS` constant reused across three different
  proximity checks, absorption's concentration multiple and single-bar-
  only scope, aggression's recency window (and the underlying "big
  trade" threshold, flagged as out-of-scope/relevant rather than
  distilled since its own source wasn't in the reading list), sweep's
  drop-ratio threshold and single-prior-sample comparison, the
  ANY-one-of-three (absorption OR aggression OR sweep) combination rule,
  the stop buffer size, the 2:1 reward:risk ratio (D-62's own javadoc
  already self-flags this one — "revisit once this actually gets tested
  for real"), and the `EveryTick` wake cadence versus D-27's stated
  proximity-trigger architectural preference.

  No code changed, no fixes proposed — pure distillation, same as
  `marketStructureRules.md`'s own pre-review form. Waiting on the user
  to actually pick up this review, same status as the todo item that
  requested it.

- **D-79** (2026-09-20) — **Market structure backtest engine built and
  run end-to-end against real MotiveWave-exported `@GC` data — first
  real result obtained, not yet trusted as a signal, just a working
  harness.** `MarketStructureBacktest` (flow-core, zero SDK dependency,
  `com.flow.backtest`): reads a plain CSV
  (`timestampMs,open,high,low,close,volume`), feeds each bar through the
  real, reworked `MarketStructureFeature` (D-74), and simulates the
  proposed pure-market-structure entry rule (trade direction = current
  `trend()`, enter on any bar whose range touches a zone in
  `tradeableLevels()`, stop `stopBufferTicks` beyond that zone's far
  edge, target at a fixed reward:risk multiple — defaults 2 ticks / 2:1,
  matching `MarketStructureLvnReversalStrategy`'s own convention). One
  position at a time; a stop/target hit and a fresh signal on the exact
  same bar deliberately does **not** re-enter same-bar (an unrealistic
  whipsaw artifact of checking exit-then-entry against one bar's OHLC
  range, not a real trading decision — found and fixed while writing the
  synthetic test below, before ever running on real data).

  Verified with `MarketStructureBacktestTest` (13 checks: exact R-multiple
  math on a target-hit win and a stop-hit loss, hand-derived against the
  same bar-construction technique `MarketStructureFeatureTest` already
  uses; the no-same-bar-re-entry guard explicitly exercised; a CSV
  round-trip). Wired into `build/build.sh` as an eighth gate. One real
  bug caught by the test before real data was ever touched: an early
  version of the test's own setup bars had the continuation bar dip back
  into the freshly-formed TJL2 zone, triggering entry a bar earlier than
  intended — not an engine bug, but a reminder that the continuation bar
  itself can legitimately touch the zone it just helped create.

  **First real run**, `analysis/data/GC_1m_1789910262328.csv` (D-77's
  MotiveWave export, 25,000 bars / ~24.2 days): 734 valid pullbacks, 501
  TJL pairs formed, 245 flips, 1,206 trades (545W/631L/30 flat),
  totalR=189.00, avgR=+0.16/trade, maxDrawdownR=36.00. **Read with real
  caveats, not as a signal to trust**: ~1 trade every 20 bars is a very
  loose entry filter (expected — this is deliberately the unconfirmed,
  no-order-flow pass), no transaction costs/slippage modeled, and 24.2
  days is thin for a swing system to say anything statistically
  meaningful. Full trade list in
  `analysis/data/backtest_run_1_output.txt`. This is a first working
  end-to-end pass, not a conclusion about the strategy's edge.

  **Not yet decided**: whether `analysis/data/` (the raw CSV + run
  output, ~1.1 MB) should be gitignored or committed — flagged, not
  decided, since it's real exported market data rather than source.

- **D-80** (2026-09-20) — **Backtest engine extended (configurable stop
  rule + bar aggregation), three exploratory runs done, explicitly
  "hit and trial" per the user's own framing — not a conclusion about
  the strategy, a first read on parameter sensitivity.**

  `MarketStructureBacktest.StopRule` added (`FIXED_BUFFER_TICKS`, the
  original D-79 convention, kept — and `ZONE_SIZE_MULTIPLE`, stop
  distance from entry = a multiple of the touched zone's own width, not
  a fixed tick buffer). `aggregate(bars, intervalMs)` added — buckets
  already-loaded bars by each bar's own start-time floor-divided to the
  interval boundary (not by row count, so a gap in the source data can't
  silently misalign grouping), used to build 5-minute bars directly from
  the already-exported 1-minute CSV (D-77) rather than re-exporting from
  MotiveWave for every timeframe tried. Both verified with 2 new
  synthetic checks in `MarketStructureBacktestTest` (16 total now) before
  touching real data again — exact stop/target arithmetic for the new
  rule, and an aggregation test with deliberately mismatched bucket
  boundaries (a single trailing 1-minute bar forming its own partial 5m
  bucket) to catch a row-count-based bug the timestamp-bucketing approach
  specifically avoids.

  **Three runs, same `GC_1m_1789910262328.csv` source, saved in full
  under `analysis/data/`:**

  | Run | Timeframe | Stop | R:R | Trades | Win% | totalR | avgR | maxDD(R) |
  |---|---|---|---|---|---|---|---|---|
  | 1 (D-79) | 1m | 2 ticks fixed buffer | 2:1 | 1206 | ~45.2% | 189.00 | +0.16 | 36.00 |
  | 2 | 5m | 1.5× zone size | 2:1 | 218 | ~22.5% | -65.00 | -0.30 | 70.00 |
  | 3 | 5m | 1.5× zone size | 1:1 | 234 | ~27.8% | -98.00 | -0.42 | 100.00 |
  | 4 | 1m | 1.0× zone size | 1:1 | 2016 | ~34.3% | -470.00 | -0.23 | 477.00 |

  | 4 | 1m | 1.0× zone size | 1:1 | 2016 | ~34.3% | -470.00 | -0.23 | 477.00 |
  | 5 | 1m | $3 fixed distance | 1:1 | 1013 | ~50.3% | 7.00 | +0.01 | 38.00 |

  **Runs 4 and 5 added after the "last hit and trial" note above** — the
  user kept going a couple more rounds. Run 4: 1-minute bars, stop =
  exactly 1.0× zone size, 1:1 R:R — negative (win rate ~34.3% against a
  50% breakeven at 1:1). Run 5: a third, new stop convention added,
  `StopRule.FIXED_PRICE_DISTANCE` (a flat $-distance from entry,
  independent of the touched zone's own size entirely — verified with
  its own synthetic check first, same discipline as the other two stop
  rules) — 1-minute bars, $3 fixed SL and TP (1:1 by construction),
  landed essentially at breakeven (win rate ~50.3%, totalR +7.00 over
  1013 trades — noise-level, not a real edge either direction).

  Notable across all five: **every zone-size-based stop variant tried
  (runs 2/3/4) came out clearly negative; the original fixed-2-tick-
  buffer stop (run 1) was the only clearly positive one; the flat
  $-distance stop (run 5) landed at breakeven.** Not enough runs to call
  this a real pattern (stop convention vs. timeframe vs. R:R are still
  not disentangled — no run here holds timeframe and R:R fixed while
  only swapping the stop convention), but worth noting as the one
  consistent thread across all the trials done so far.

  **Reading with real caveats, not as a conclusion**: run 2→3 changed
  only R:R (both 5m/zone-size) — win rate rose (closer target, easier to
  hit) but expectancy still worsened, consistent with the underlying
  signal not being strong enough to clear breakeven at either ratio
  (33.3% needed at 2:1, 50% at 1:1; actual 22.5%/27.8%). Run 1→2 changed
  BOTH timeframe and stop rule at once, so it doesn't isolate which one
  flipped the sign — not disentangled, and not asked to be. All three
  runs share the same underlying entry filter (touch any
  `tradeableLevels()` zone, no order-flow confirmation at all) — this is
  consistent with that filter alone not being a real edge, which is
  exactly the gap `orderFlowExecutionRules.md`'s eventual confirmation
  logic (absorption/aggression/sweep) exists to close, not a finding
  about the market-structure rules themselves being wrong.

  **Explicitly the last hit-and-trial pass for now** — the user's own
  framing, returning to other work next.

## Open questions (not yet decisions)

Platform questions get answered by a throwaway study in
`../motivewave/experiments/`, landing in that repo's `findings.md`; system
questions are decided directly, no experiment needed. Either way the
outcome becomes an entry above.

- **Q-02, stage (b) only — `OrderContext` write-call thread affinity.**
  Stage (a) is closed, see D-31: reads are safely retainable and callable
  off-thread. Stage (b), now optional rather than blocking (D-17 has a
  safe default regardless): submit a far-from-market limit order from a
  retained reference, confirm, cancel, as its own deliberate session under
  Sim Trade Only. Order placement — explicit in-the-moment confirmation
  per `CLAUDE.md` required. Only worth running if inline-flush latency
  ever actually matters enough to want the answer.

- **Q-10 — Raw journal DOM tier: how much per-order detail, retained for
  how long?** Spun off from Q-03 by D-35, which measured the empirical
  cost (full per-order detail: ~31.5 GB/hr raw, ~3.89 GB/hr gzipped) but
  didn't decide the policy. Three live options: (a) top-of-book only in
  the raw journal, no per-order history retained past live memory; (b)
  build and measure a delta/incremental DOM encoding before committing to
  a window; (c) accept a much shorter full-detail retention window (hours,
  not days). Hinges on whether D-22's forward-only feature class (order
  resting time, liquidity-pull frequency) actually needs replay-grade
  order-ID history, or can warm up live-only each session. System
  question — no experiment needed, needs a decision.

- **Q-07 — Simulated-account fill fidelity.** How does MotiveWave's
  simulator fill — last price, bid/ask, queue-aware? Platform question.
  Determines how much of the sim-stage PnL curve is signal and how much is
  the simulator being generous.


*Closed by being routed around*: the old open question on settings-UI
conditional param visibility — see D-18. The old open question on whether
bar-close-only decisions suffice — see D-16.
