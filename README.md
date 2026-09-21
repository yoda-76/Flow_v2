# FLOW_V2

Turning discretionary order-flow trading judgment — market structure, basic
price action, volume profile, footprint, DOM liquidity, big trades — into an
automated **forward-testing** system running inside MotiveWave, on US futures
via Rithmic/CQG.

Same methodology as [FLOW](../FLOW) (NSE, Python, backtest-first) and the
same platform as [motivewave](../motivewave) (the scoping project that proved
this SDK can do what we need). This repo is the third thing: **the actual
system**.

The one-line goal: *test many strategies quickly, without editing the core
for any of them.*

Architecture below is the current settled shape, recorded decision by
decision in `docs/dynamic/decisions.md`. It is the current best answer, not
final — but it is specific enough to build against, which the first draft of
this file was not.

## What this is, and what it explicitly is not

**Is:** a single deployed MotiveWave Strategy — "the runtime" — that owns
data ingestion, feature computation, execution and journaling, and hosts
interchangeable strategy plug-ins. You pick which strategy runs from a
dropdown in the study settings, arm it, and it forward-tests against live
data on the Simulated account, recording everything it saw and everything it
did.

**Is not (for now):**

- **No backtesting.** Forward testing is the whole point. MotiveWave's own
  docs flag their optimizer's fills as optimistic, and the tick-level order
  flow this system is built on doesn't survive bar-granularity replay
  anyway. Backtest may come later, from our own recorded data — see
  *Replay* below.
- **No computed options/Greeks/GEX.** We don't calculate these ourselves —
  that's FLOW's domain, on a different market. GEX *levels* (or any other
  external observation — key levels, a bias for the day, whatever a
  strategy wants) can still enter the system as **manual pluggable input**:
  see *External inputs and config* below.
- **No multi-instrument portfolio logic.** One instrument per runtime
  instance (one chart), at first.
- **No ML.** Rules, expressed explicitly, so a losing forward test tells you
  *which rule* was wrong.

## Relationship to the two sibling repos

| Repo | Role | Still active? |
|---|---|---|
| `../FLOW` | NSE research platform (Python/Nautilus). Source of the *methodology*, not of code. | Yes, separately |
| `../motivewave` | The experiments lab: SDK reference docs (`docs/static/`), throwaway diagnostic studies, the portable JDK, and the findings record about *the platform itself*. | Yes — keeps being the lab |
| `FLOW_V2` (here) | The real system. Its `docs/dynamic/` records decisions and findings about *the system we build*, not about the SDK. | Yes — primary |

Rule of thumb for where a thing goes: **"is this true about MotiveWave, or
true about our system?"** A question like *does `onTick` fire after
`onBarClose` for ticks belonging to the closed bar?* is a platform question —
it gets answered by a throwaway study in `../motivewave/experiments/` and
lands in that repo's `findings.md`. A question like *should stop distance be
ATR-based or structure-based by default?* is ours, and lives here.

The SDK docs, Javadoc and sample project are **not copied** into this repo.
They stay at `../motivewave/docs/static/`, single source, never edited.

## The layer model

FLOW's static docs put one architectural constraint above everything else:
data flows one way through named layers, and each layer is inspectable on its
own. That carries over here, re-cut for a live feed:

```
FEED          MotiveWave SDK: Tick, DOM, DataSeries (OHLC bars), clock
  ↓
INGEST        normalize into our own immutable, sequenced event types
  ↓           (the last layer allowed to read the wall clock)
SEQUENCER     one totally-ordered event stream, single-writer drain
  ↓
FEATURES      price action (candles, swings, structure, levels)
              order flow (delta, footprint, profile, liquidity map, big trades)
  ↓
MARKET STATE  read-through view, generation-guarded, handed to the strategy
  ↓
TRIGGERS      when does the strategy get woken, and why
  ↓
STRATEGY      plug-in: state → Intent            ← the only part that varies
  ↓
RISK CHAIN    ordered filters, each verdict journaled
  ↓
EXECUTION     desired state → reconciled orders, sizing, brackets, guards
  ↓
JOURNAL       every input, decision, order and fill, to disk, always
```

Everything left of `STRATEGY` is core and is written once. Everything right
of it is core and is written once. A new strategy touches **one box**.

## The event stream

Everything the runtime knows arrives as an event on one totally-ordered
stream. Three producers feed it: the study callbacks (`onTick`, bar hooks,
order and fill hooks), the `DOMListener` — which runs on its own thread, not
the study callback thread — and the runtime's own clock.

Every callback does exactly one thing: wrap its payload in an immutable
event stamped with a monotonic sequence number, the platform event time and
the local receipt time, then push it onto an MPSC queue. The pipeline drains
that queue and runs on a **single thread**, so exactly one thread ever
mutates feature state.

This is what makes the feature layer race-free without locks, and it is also
precisely the artifact a replay needs. Determinism and thread safety come
out of the same structure.

Decision cadence and order placement are deliberately separated (see
*Deciding and acting* below), so the pipeline can run at event level
regardless of how the `OrderContext` retention question resolves.

### Time is an event

Nothing below `INGEST` may read the wall clock. Time comes off the event:
"now" is the timestamp of the most recent event drained, whatever kind it
was.

Market events alone are not enough for that — a quiet book with no prints
would freeze time for the feature and strategy layers, and a rule like
*flat by 15:59* would never fire. So the runtime, which sits above ingest
where the clock is allowed, injects a synthetic `ClockEvent` into the same
queue on a fixed cadence (100ms). It gets a sequence number and is recorded
in the raw journal like anything else.

That is what keeps replay honest: clock events are *in the recorded stream*
and are fed back in order, rather than regenerated from the replay machine's
clock. Feed the same file in twice, get the same intents.

Clock events do not wake a strategy unless it declared a time-based trigger.
`MarketState` exposes both notions of time separately — exchange event time
from the last market event, local time from the last clock event — and the
gap between them is the feed-latency measurement, journaled.

### Replay

The journal records every input event the runtime saw, not just its
decisions, so a replay harness feeds recorded events back through the
identical feature and strategy code.

This is not a deferred nice-to-have. With decisions made at event level, the
decision log alone can no longer explain *why* something fired — replay is
the primary debugging tool, and the only honest path to backtesting
order-flow logic later.

Which makes one property load-bearing: **replay must be bit-identical to
live.** No wall clock below ingest, no dependence on hash-ordered iteration,
thread scheduling, or object identity hashes anywhere in the feature or
strategy path. A self-check goes in early and stays: record a session,
replay it, assert the intent sequence is identical.

## MarketState

A read-only view handed to the strategy at each decision point. Not a
per-decision immutable copy — at the rates this feed delivers (~50,988
`DOMOrder` entries across 20 DOM updates on `@GC`, per
`../motivewave/docs/dynamic/findings.md`) an immutable object graph per
decision is not affordable.

Instead: features own mutable, incrementally updated internal state;
`MarketState` is an interface reading through to them, allocation-free to
hand over. It carries a generation counter bumped at each decision point,
and any accessor called on a stale generation throws — which preserves what
immutability was actually protecting against (a strategy stashing the
reference and reading it three bars later) without the allocation.
`MarketState.freeze()` returns a genuinely immutable copy, used only by the
journal and by tests.

The raw DOM never crosses this boundary. Strategies see derived views:
bucketed liquidity map, imbalance at N levels, resting size at a price,
large-order presence.

### Prices are integers

Prices are integer tick offsets from a session anchor throughout the core,
converted to and from decimal only at the ingest and journal boundaries.
Tick size and anchor come from instrument config.

Rationale: every feature does price-keyed array indexing, level equality
comparison and profile bucketing on every event. Integers make all three
exact and fast; doubles make level equality a tolerance question forever and
force boxed or hashed lookups in the hot path.

## Features

Features are incremental: each consumes events and is **correct after every
event**, not just at bar boundaries. No feature defers work to a bar close.
The hot path allocates nothing per event — primitive arrays, price-keyed by
tick offset, no boxing, no string-keyed lookups.

Features register in a declared order and never call each other laterally;
dependencies are constructor-injected, so a cycle is a compile error.
Strategies declare which features they need, and unused ones don't run.

### Readiness, not warmup

Features differ in what they need before their readings mean anything, and
the difference is per-feature rather than one global warmup period. Each
feature declares whether it is warmable and what history it needs; the
runtime **refuses to arm until every feature the active strategy uses
reports ready**, journaling which one is holding it up.

Four classes:

- **Ready immediately.** Everything DOM-derived (liquidity map, book
  imbalance) — a full book snapshot arrives on subscription. Per-bar delta
  and footprint for bars that begin after attach.
- **Warmable from historical bars.** Market structure and swings, session
  and prior-session levels, ATR, overnight high/low, VWAP, volume profile.
  These do not need tick-level history; bar data is enough.
- **Warmable only from tick history.** Anything needing per-trade
  aggressor detail before attach, e.g. session cumulative delta.
- **Forward-only, cannot be warmed.** Anything wanting DOM *history* —
  order resting time, book-change rates, liquidity-pull frequency. Historical
  order-book reconstruction is permission-gated on our Rithmic tier
  (`../motivewave/docs/dynamic/findings.md`), so these are ready only after
  N minutes of live running.

Two things that are easy to miss and belong in the readiness check:

- **Relative thresholds are a warmup.** "Big trade = 50 contracts" needs
  nothing. "Big trade = 95th percentile of the last 30 minutes of trade
  sizes" needs a filled window and a not-ready state. Same for delta
  z-scores, normalized book imbalance, adaptive absorption thresholds. Each
  feature declares whether its threshold is fixed or relative.
- **The partial bar at attach** is incomplete for every bar-scoped feature.
  Either backfill the elapsed part from tick history or mark that bar
  invalid and have triggers skip it.

Note on VWAP: **adapted from MotiveWave's own published `VWAP.java` source**
(`docs/dynamic/sdk-capability-findings.md` §2.7, `MotiveWave/motivewave-
studies` on GitHub), not built from scratch — and that source is genuinely
tick-weighted (`totalPrice += tick.getPrice() * tick.getVolumeAsFloat()`,
fed via `instr.forEachTick(...)`), not a bar-level approximation as
originally assumed here. The file itself can't be compiled as-is (it casts
to an internal `platform.ui.*` class not in our public SDK jar), so the
algorithm gets ported into `flow-runtime`, not copied — see D-36 and
`sdk-capability-findings.md` for the full trail. Matching the chart's VWAP
line exactly is therefore the expected outcome, not a known gap.

### Volume profile: reuse the SDK's engine, don't build one

**Settled 2026-09-15 (D-36), superseding the two-implementation plan below
this paragraph as historical record** — the SDK's own
`com.motivewave.platform.sdk.profile.VolumeProfile` engine, fed by our own
ticks and wrapped behind a read-only view, is confirmed live to produce
output that matches the chart's built-in Volume Profile study closely once
both are scoped to the same accumulation window. No custom volume-profile
implementation gets built. The same engine covers footprint (a bar-scoped
`VolumeProfile` with `rangeTicks=1`) and delta/cumulative delta
(`getTotalDelta()` etc.), so this closes "build vs. reuse" for those two as
well. Full trail: `docs/dynamic/sdk-capability-findings.md` and
`../motivewave/docs/dynamic/findings.md`, 2026-09-14/15 `[LIVE]` entries.

One caveat this reuse doesn't remove: E-6 found `com.motivewave.platform.
common.Enums.VAMethod` doesn't exist anywhere in our jar, so only
`getValueArea(double)` — one fixed value-area algorithm — is actually
callable. FLOW's own volume profile found POC holding up well while
VAH/VAL/HVN/LVN showed real inaccuracy on the same data; that's a live
question worth re-checking against *this* engine specifically (it's not
FLOW's implementation, and E-2's live parity check already looks
promising), not assumed to reproduce or assumed fixed.

<details>
<summary>Historical: the original two-implementation plan (superseded by
D-36, kept for context)</summary>

Volume profile was originally planned behind a provider interface with two
implementations, both running at once:

```
VolumeProfileProvider          // interface, flow-core
  ├─ CustomVolumeProfile       // flow-core, built from our own tick stream
  └─ BuiltInVolumeProfile      // flow-runtime, reads MotiveWave's study
```

The idea was that both would write POC/VAH/VAL to the journal on a fixed
cadence, with custom-vs-built-in becoming an offline diff of two journal
columns. This assumed "reading the built-in study's own output" was a real
option; D-32/Q-08 later confirmed it structurally isn't (no SDK API reaches
another study's internal state), and separately, E-10 found there was
never a need to try: the SDK exposes the same *engine* MotiveWave's own
studies would use, directly, to any code that instantiates it — which is
what D-36 uses instead of either half of this plan.

</details>

## The strategy contract

This is the part worth getting right, because it's what makes "plug in any
strategy" true rather than aspirational.

### Strategies emit intent. They do not place orders.

A strategy receives a read-only `MarketState` and returns an `Intent` — a
**desired end state**, not a command:

```java
Intent { targetPosition, stopPrice, targetPrice, reason, strategyId, seq }
```

`exec/` diffs that against the actual position and live orders and emits the
minimal set of order actions. Re-submitting an identical intent is a no-op.
Desired state rather than command, because a command model goes ambiguous
the moment a fill is partial, a stop is already resting, or the same intent
repeats on consecutive events — all normal at event level. It is also what
makes a restart mid-position recoverable: desired state is re-derivable,
command history is not.

```java
public interface FlowStrategy {
  String id();                                   // "orb_delta_reversal"
  Set<Feature> requires();                       // gates arming on readiness
  Set<Trigger> triggers();                       // when to wake this strategy
  void onInit(StrategyConfig cfg);
  Intent onEvent(MarketState state);
  default void onFill(FillEvent fill) {}
}
```

Three reasons for intent-only, in order of how much they matter:

1. **Safety.** Only `OrderGateway` ever holds an `OrderContext`. A strategy
   plug-in *cannot* place an order even by accident, because it is never
   handed the ability to. Given that this project's predecessor already
   placed a real, filled order from a study containing zero order calls (see
   `../motivewave/docs/dynamic/findings.md`, 2026-09-11), that structural
   guarantee is worth more than any convention.
2. **Comparability.** Ten strategies sharing one execution path means a
   difference in results is a difference in *signal*, not in someone's
   bespoke stop handling.
3. **Testability.** An `Intent` returned from a synthetic event sequence is
   assertable in a plain unit test, with no platform running.

An escape hatch for genuinely complex ideas (scaling in/out, bracket
ladders) can be added later as an explicit opt-in interface — but not before
a real strategy actually needs it.

### Decisions are event-level

Bar-close-only decisions quantise entry timing to the bar and will not work
for the way this methodology is actually traded. The core is built for
event-level from day one — that's what forces always-consistent features and
the allocation-free hot path, and those are the expensive-to-retrofit half.

Cadence is declared per strategy, not fixed globally:

```java
Set<Trigger> triggers();
// BAR_CLOSE | EVERY_TICK | THROTTLE(millis)
// PRICE_CROSS(level) | BOOK_CHANGE(minSize)   — registered dynamically
```

Dynamic registration is what keeps event level affordable. A flat strategy
watching one level should not be woken forty times a second: it registers
interest in the zone, sleeps until price is near it, and swaps to
`EVERY_TICK` once the zone is in play. The runtime owns trigger evaluation
and journals **why** the strategy was woken, which is half of debugging a
missed entry.

Phasing: the first two or three strategies declare `BAR_CLOSE` while the
core settles, keeping early journals small and readable. Moving a later
strategy to event level is one line, not a migration.

### The shape most strategies will take

Nearly every strategy here is expected to be: *an area of interest* — from
market structure, GEX levels, or another concept — *plus an order-flow
entry* inside it.

Two consequences. The reusable library is the **entry evaluator**, not the
setup logic: absorption, imbalance stacking, sweep-and-reclaim live in
`flow/` and get composed by every strategy. And proximity triggers are the
primary wake-up mechanism, which is exactly what dynamic registration above
is for.

A `ZoneEntryStrategy` base class encoding the two-phase shape (zone logic at
a slow cadence, entry logic at event level) is the obvious eventual
abstraction — deliberately not written until two real strategies have shown
the same shape, per the same rule FLOW's `flow/backtest/common/` follows.

### Hard rule: a strategy must not import `com.motivewave.*`

This is FLOW's "no Nautilus import in `signal.py`" rule, transplanted — and
here it is enforced by the compiler, not by review. `flow-core`, which holds
every strategy, is compiled with **no `mwave_sdk.jar` on the classpath**. A
strategy that reaches into the SDK fails the build.

If a strategy can't be written without the SDK, the core is missing a
feature — add it to the core, don't leak the platform into the strategy.

### Adding a strategy

1. New class in `strategies/`, implementing `FlowStrategy`.
2. One line in the registry.
3. Unit-test it against synthetic event sequences — assert the intents you
   expect, *before* the platform is involved.
4. Recompile, redeploy, pick it from the dropdown, run in dry-run.

No new `@StudyHeader`, no new deployment target, no core edits, no touching
another strategy. If step 1 forces you to modify anything in `core/`,
`flow/`, `price/` or `exec/`, that's a signal the core abstraction is wrong
— fix it there properly, once, for all strategies.

## External inputs and config

Not everything a strategy should react to comes from the live feed. GEX
levels (computed elsewhere, e.g. by FLOW, or read off a vendor site), key
support/resistance, a discretionary daily bias — these change at most once a
day, sometimes intraday, and are edited by hand.

The same hand-edited store also holds **strategy parameters**. MotiveWave's
settings panel carries only `strategyId`, `armed` and `mode`; everything
else — per-strategy params, risk defaults, session config — is read from
file. That sidesteps the question of whether one settings panel can
conditionally show only the active strategy's params, keeps one config
mechanism instead of two, makes params journalable as data rather than
scraped from a UI, and turns a param sweep into a file edit rather than a
recompile-and-redeploy. Cost accepted: the GUI's type-checked widgets and
validation are lost.

Properties of the store:

- Plain files (JSON/CSV), session-scoped, loaded at session start and
  re-readable on demand.
- Exposed to strategies as just another read-only field on `MarketState`
  (e.g. `state.externalLevels()`), alongside price and order-flow features —
  a strategy doesn't know or care whether a number came from the live feed
  or a file someone edited that morning.
- Edited only by the user, the same way `.env` is — the runtime reads this
  store, never writes it.
- Staleness is visible: the journal records what external input and what
  param set was active at every decision point, so a decision made on a
  three-day-old GEX level is traceable after the fact, not silently assumed
  current.

## Execution, risk and safety

### Deciding and acting are separate

The pipeline always produces intents at event level and puts them on an
intent queue. A separate **flush** step drains that queue through the risk
chain into `OrderGateway`.

Where the flush runs is the only thing that varies with the unresolved
`OrderContext` retention question (Q-02). If a retained context is valid and
callable off-thread, flush inline for full event-level latency. If it is
retainable but not thread-safe, or not retainable at all, flush at the top of
the next platform callback. Strategies, features and intent types are
identical either way, so the experiment is off the critical path.

The gap between intent sequence number and execution sequence number is
journaled on every trade, so the cost of a lagging flush is measured rather
than argued about.

### The risk chain

Ordered filters between intent and gateway, each recording its verdict so a
suppressed trade is visible rather than invisible: armed, session open,
readiness, daily-loss limit, size cap, rate limit, churn guard, lag guard.

Two of those exist specifically because decisions are event-level, and both
are core rather than per-strategy — comparability is lost the moment each
strategy implements its own throttling:

- **Churn.** Minimum dwell time in a position and a max-reversals-per-session
  cap. Reconciliation already makes a repeated identical intent a no-op, but
  it does not stop long/flat/long across three consecutive ticks.
- **Lag.** The runtime watches drain-queue depth and per-event processing
  time. If lag crosses a threshold it **disarms and journals the reason**
  while continuing to compute, so the session stays diagnosable. Disarm
  rather than drop, because dropping events corrupts feature state silently,
  and the failure mode without the guard is a strategy trading on a book
  that is seconds stale.

### Restart with a live position

If the runtime starts — or the study reloads after a redeploy — and finds an
open position or resting orders on the account, it **refuses to arm** and
journals what it found. It does not adopt them and does not flatten them.
The position is cleared manually by the user before arming.

Rationale: adopting a position whose reasoning no longer exists in memory is
how a small loss becomes a large one, and auto-flattening is an order placed
as a side effect of startup, which the hard rule forbids.

### Errors

A feature or strategy that throws is caught at the pipeline boundary: the
runtime disarms, journals the exception with the event sequence number that
caused it, and continues ingesting so the session stays diagnosable. Nothing
propagates into a MotiveWave callback, where it risks the platform
deactivating the study or flooding the output log.

### Safety — non-negotiable

Carried over from `../motivewave/CLAUDE.md`, and tightened by the structure
above.

- **"Sim Trade Only" stays enabled** in MotiveWave settings. It disables
  order placement on every account but the Simulated one, platform-wide,
  regardless of what any code or button does.
- **Every inherited order-capable hook is explicitly overridden** in the
  runtime class — `onEnterNow` above all. Omitting a hook inherits
  MotiveWave's default, and MotiveWave's default for `onEnterNow` places a
  market order. This is enforced by a reflection test that enumerates every
  `OrderContext`-taking method on `Study` from the jar and asserts the
  runtime overrides each one — so it stays true the day MotiveWave ships a
  new hook.
- **`OrderGateway` is the only class holding an `OrderContext`**,
  package-private, instantiated by the runtime, never handed elsewhere.
- **Dry-run is the default mode.** The runtime ships with `armed = false`:
  intents are computed and journaled, no order is ever submitted. Arming is
  a deliberate per-session act.
- **Confirm the selected account, out loud, at every activation.** Not once
  — every time. The GUI's account selection can change between sessions.
- **Never place, modify or cancel an order as a side effect of other work.**

## Journal

Two tiers, because one JSONL stream will not hold at MBO rates and the raw
tier is the only part replay needs.

**Decisions tier** — JSONL, human-readable, long retention. Intents that
differ from the previous intent, risk verdicts, trigger reasons, orders,
fills, readiness transitions, active external inputs and their staleness,
plus a periodic heartbeat snapshot. Change-only, because at event level a
record per decision is no longer readable by a human.

**Raw tier** — the sequenced event stream in a compact encoding, rotated per
session, short rolling retention (2–3 days, exact figure set from
measurement — see Q-03). This is replay fuel and regression-fixture source.

Both are written by a dedicated writer thread off bounded queues; the
pipeline thread never does I/O. Backpressure is decided rather than
discovered: if the raw queue fills, **drop and write a gap marker recording
the lost sequence range** — a replay that silently skipped events is worse
than one that refuses to run. If the decisions queue fills, that's a bug and
it fails loudly.

Every session file opens with a header record: strategy id, git commit, full
param set, instrument and session config. Cross-strategy comparison is
meaningless without knowing which build produced a run.

Comparison across strategies is done **offline, from the journals** — one
run directory per session per strategy. Analysis tooling there can be
Python; the constraint is that the *runtime* is all-Java, not that every
script ever written is.

## Forward-test workflow

Three modes, in order, each a gate on the next:

1. **Dry run** — strategy computes intents against live data; nothing is
   submitted. Read the journal: did it fire where you'd have fired manually?
   This catches most "the idea is wrong" and all "the feature is buggy"
   failures for free.
2. **Sim** — same thing, orders actually submitted to the Simulated account.
   Now fills, slippage, partial fills and rejections become real and the
   journal starts producing a PnL curve.
3. **Live** — out of scope until a strategy has earned it, and gated by an
   explicit decision recorded in `docs/dynamic/decisions.md`.

## Testing

`flow-core` compiles and runs under a plain JDK with no platform present,
which is what makes the following possible at all.

Tests are **event sequences**, not single states — at event level a sequence
is the unit of behaviour:

```java
seq().trade(4391.2, 3, ASK)
     .bookPull(4391.5, 120)
     .trade(4391.4, 8, ASK)
     .expectIntent(LONG, 1);
```

Interesting slices cut from a raw session log (30 seconds around a real
setup) are committed as fixtures and asserted against permanently. That's
the regression suite, and it only exists if the raw log format is stable
early.

Two tests are structural rather than behavioural and run from the start: the
replay-equivalence check (record, replay, assert identical intent sequence)
and the order-hook override check in `flow-runtime`.

## Repo layout

```
FLOW_V2/
├── README.md              this file
├── CLAUDE.md              working rules (inherits FLOW's and motivewave's)
├── docs/dynamic/          decisions.md, findings.md — about this system
├── flow-core/             compiled WITHOUT mwave_sdk.jar on the classpath
│   └── src/com/flow/
│       ├── core/          event types, MarketState, Intent, FlowStrategy, registry
│       ├── price/         candles, swings, structure, levels
│       ├── flow/          view interfaces only (VolumeProfileView, FootprintView,
│       │                  BigTradeEvent, ...) + entry evaluators built on them
│       │                  (absorption, imbalance, sweep) — no SDK import (D-36)
│       ├── external/      hand-edited input + param store
│       ├── exec/          reconciliation, sizing, brackets, risk chain
│       ├── journal/       record types and writers
│       └── strategies/    one package per strategy plug-in
│   └── test/              event-sequence DSL, recorded fixtures
├── flow-runtime/          compiled WITH mwave_sdk.jar and flow-core
│   └── src/com/flow/rt/   the @StudyHeader Study, SDK→core adapters, OrderGateway,
│                          clock, and the SDK-engine wrappers D-36 settled on:
│                          delta/footprint/profile (wraps sdk.profile.VolumeProfile),
│                          big trades (wraps AggregateFilter), VWAP (ported from
│                          MotiveWave's published source), liquidity map (ours,
│                          built from the live MBO DOM stream)
├── build/                 compile + redeploy scripts (portable JDK 26)
└── logs/
```

The two-unit split is the point, not a convention: it is what makes "a
strategy cannot import the SDK" a compile error instead of a rule someone
has to remember. Deploy concatenates both class trees into
`%USERPROFILE%\MotiveWave Extensions\dev\`.

Internal package boundaries are still tentative and expected to shift once a
second and third strategy test them, the same way FLOW's
`flow/backtest/common/` only took its current shape after a second strategy
actually needed it.

## Build order

The walking skeleton first, before any real feature: event types, sequencer,
clock events, journal writer, a null feature, a strategy that always returns
`Intent.none()`, gateway in dry-run. Run it for a full session and confirm
the journal reconstructs what happened and that replay reproduces it
exactly.

Then: delta (already proven working in the skeleton study), then market
structure and volume profile with their readiness gates, then one real
strategy, then the second — which is what actually tests whether the
boundaries hold.

## What's confirmed already (inherited, not re-derived)

From `../motivewave/docs/dynamic/findings.md`, all `[LIVE]` on our actual
Rithmic feed:

- Tick data carries price, volume, bid/ask, **aggressor side**, and real
  exchange order IDs.
- The DOM gives **true Market-by-Order** depth — individual resting orders
  across the full book (~600–700 price rows per side on `@GC`), not a
  top-of-book approximation. A real liquidity map is buildable.
- The strategy lifecycle fires correctly, and bar OHLC + live aggressor
  delta + account state were all read together in one working run.
- Historical DOM reconstruction may be permission-gated on our Rithmic
  tier. Irrelevant to forward testing; relevant to replay and to
  forward-only features.

## Open questions

Recorded here rather than guessed, per the methodology. Platform questions
become a throwaway experiment in `../motivewave/experiments/`; system
questions are decided directly, no experiment needed. Either way the
outcome lands as a decision in `docs/dynamic/decisions.md`, which is the
authoritative list — this section is kept in sync with it but
`decisions.md` wins if they ever drift.

- **Q-02, stage (b) only — `OrderContext` write-call thread affinity.**
  Stage (a) is closed (`decisions.md` D-31): reads are safely retainable
  and callable off-thread. Stage (b), now optional rather than blocking
  (the flush point already has a safe default): submit a far-from-market
  limit order from a retained reference, confirm, cancel, as its own
  deliberate session under Sim Trade Only. Order placement — explicit
  in-the-moment confirmation per `CLAUDE.md` required.
- **Q-10 — Raw journal DOM tier: how much per-order detail, retained for
  how long?** Spun off from Q-03 (`decisions.md` D-35), which measured
  the empirical cost (full per-order DOM detail: ~31.5 GB/hr raw, ~3.89
  GB/hr gzipped — vs. ~28 MB/hr / ~1.35 MB/hr for top-of-book-only) but
  didn't decide the policy. Hinges on whether D-22's forward-only feature
  class (order resting time, liquidity-pull frequency) needs replay-grade
  order-ID history or can warm up live-only each session.
- **Q-07 — Simulated-account fill fidelity.** How does MotiveWave's
  simulator fill — last price, bid/ask, queue-aware? Platform question.
  Determines how much of the sim-stage PnL curve is signal and how much is
  the simulator being generous.

Closed since last sync: Q-11 (automatic order placement vs. the per-order
confirmation rule) — resolved by `decisions.md` D-82, `CLAUDE.md`'s new
session-scoped-arm exception.

## Notes

Personal research project, built alongside full-time work as a developer at
a brokerage. Not affiliated with my employer, uses no proprietary data or
code. Not investment advice.
