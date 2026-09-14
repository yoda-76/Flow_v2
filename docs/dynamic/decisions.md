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

## Open questions (not yet decisions)

Platform questions get answered by a throwaway study in
`../motivewave/experiments/`, landing in that repo's `findings.md`; system
questions are decided directly, no experiment needed. Either way the
outcome becomes an entry above.

- **Q-01 — Bar/tick ordering.** Do ticks belonging to a bar reliably
  arrive before `onBarClose` fires for it, or can a bar close with its
  last ticks still in flight? Platform question. Less load-bearing than it
  was, since D-10's sequencer records the ordering that actually occurred
  rather than assuming one, but still determines whether a bar-aligned
  aggregate can be trusted at the moment the bar-close trigger fires.

- **Q-02 — `OrderContext` retention and thread affinity.** Can a context
  captured in one callback be retained and used later, and can it be used
  from a thread other than the one that supplied it? Platform question,
  and the input to D-17's flush point. Test in two stages: **(a)** retain
  the context from `onActivate`, call **read-only** methods on it from a
  later callback and from a timer thread, logging results plus
  `System.identityHashCode(ctx)` each time one is received — zero risk,
  and a stable identity hash across callbacks is strong evidence it is a
  long-lived handle. **(b)** only if (a) is inconclusive, and only as its
  own deliberate session under Sim Trade Only: submit a far-from-market
  limit order from a retained reference, confirm, cancel. Stage (b) is
  order placement and falls under the hard rule in `CLAUDE.md` — explicit
  in-the-moment confirmation required.

- **Q-03 — Raw data volume and the retention window.** Capture **one hour
  of live `@GC`** and record event counts (trades, DOM updates,
  `DOMOrder` entries per update) alongside bytes, both uncompressed and
  gzipped. The compression ratio decides whether a binary encoding is
  worth writing or whether compressed JSONL reaches a 3-day window on its
  own, and the absolute number closes D-07's TBD retention figure.

- **Q-07 — Simulated-account fill fidelity.** How does MotiveWave's
  simulator fill — last price, bid/ask, queue-aware? Platform question.
  Determines how much of the sim-stage PnL curve is signal and how much is
  the simulator being generous.

- **Q-08 — Is MotiveWave's built-in volume profile readable from our own
  study?** Platform question, input to D-26. Can a deployed study obtain a
  handle to another study instance on the same chart, and do the built-in
  profile's POC/VAH/VAL land in an addressable `DataSeries`, or are they
  internal to the renderer with no programmatic surface? Decides whether
  `BuiltInVolumeProfile` is automatic or whether custom-versus-built-in
  comparison falls back to reading the chart by hand.

- **Q-09 — Session separations within the 24h day.** D-29 settled 24h
  (not RTH-only) with flatten-at-end as the default, but not where the
  sub-session boundaries fall inside that day (e.g. Asia/London/NY splits)
  or whether per-session counters — D-19's reversal cap, D-21's price
  anchor, the journal's session-file boundary — reset at each sub-session
  or once daily. System question; user to specify the boundaries.

*Closed by being routed around*: the old open question on settings-UI
conditional param visibility — see D-18. The old open question on whether
bar-close-only decisions suffice — see D-16.
