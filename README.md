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
  *Record now, replay later* below.
- **No computed options/Greeks/GEX.** We don't calculate these ourselves —
  that's FLOW's domain, on a different market. GEX *levels* (or any other
  external observation — key levels, a bias for the day, whatever a
  strategy wants) can still enter the system as **manual pluggable input**:
  see *External/manual inputs* below.
- **No multi-instrument portfolio logic.** One instrument per runtime
  instance (one chart), at first.
- **No ML.** Rules, expressed explicitly, so a losing forward test tells you
  *which rule* was wrong.

## External/manual inputs

Not everything a strategy should react to comes from the live feed. GEX
levels (computed elsewhere, e.g. by FLOW, or read off a vendor site), key
support/resistance, a discretionary daily bias — these change at most once
a day, sometimes intraday, and are edited by hand.

These are a **first-class, pluggable input type**, not a hack:

- Held in a small daily/session-scoped store (a plain file — JSON/CSV —
  loaded at session start and re-readable on demand; format TBD when the
  first strategy actually needs one).
- Exposed to strategies as just another read-only field on `MarketState`
  (e.g. `state.externalLevels()`), alongside price/order-flow features —
  a strategy doesn't know or care whether a number came from the live feed
  or a file someone edited that morning.
- Edited only by the user, the same way `.env` is — the runtime reads this
  store, never writes it.
- Staleness is visible: the journal records what external input was active
  at every decision point, so a decision made on a three-day-old GEX level
  is traceable after the fact, not silently assumed current.

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

## The fixed constraint everything is built around

FLOW's static docs put one architectural constraint above everything else:
data flows one way through named layers, and each layer is inspectable on its
own. That carries over here, re-cut for a live feed:

```
FEED          MotiveWave SDK: Tick, DOM, DataSeries (OHLC bars)
  ↓
INGEST        normalize into our own immutable event types
  ↓
FEATURES      price action (candles, swings, structure, levels)
              order flow (delta, footprint, profile, liquidity map, big trades)
  ↓
MARKET STATE  one immutable read-only snapshot per decision point
  ↓
STRATEGY      plug-in: snapshot → Intent            ← the only part that varies
  ↓
EXECUTION     intent → orders, sizing, brackets, session guards
  ↓
JOURNAL       every input, decision, order and fill, to disk, always
```

Everything left of `STRATEGY` is core and is written once. Everything right
of it is core and is written once. A new strategy touches **one box**.

## The strategy contract

This is the part worth getting right, because it's what makes "plug in any
strategy" true rather than aspirational.

### Strategies emit intent. They do not place orders.

A strategy receives a read-only `MarketState` and returns an `Intent`:
*flat, long, or short, this size, with this stop and this target, because of
this reason.* The core translates intent into actual orders, applies risk
limits and position sizing, manages brackets, flattens at session end, and
writes the journal.

Sketch (names provisional until the first two strategies exist):

```java
public interface FlowStrategy {
  String id();                                   // "orb_delta_reversal"
  List<Param> params();                          // surfaced in the settings UI
  void onInit(StrategyConfig cfg);
  Intent onBarClose(MarketState state);          // primary decision point
  default Intent onTick(MarketState state) { return Intent.none(); }
  default void onFill(FillEvent fill) {}
}
```

Three reasons for intent-only, in order of how much they matter:

1. **Safety.** Only the runtime class ever holds an `OrderContext`. A
   strategy plug-in *cannot* place an order even by accident, because it is
   never handed the ability to. Given that this project's predecessor
   already placed a real, filled order from a study containing zero order
   calls (see `../motivewave/docs/dynamic/findings.md`, 2026-09-11), that
   structural guarantee is worth more than any convention.
2. **Comparability.** Ten strategies sharing one execution path means a
   difference in results is a difference in *signal*, not in someone's
   bespoke stop handling.
3. **Testability.** An `Intent` returned from a synthetic `MarketState` is
   assertable in a plain unit test, with no platform running.

An escape hatch for genuinely complex ideas (scaling in/out, bracket
ladders) can be added later as an explicit opt-in interface — but not before
a real strategy actually needs it.

### Hard rule: a strategy must not import `com.motivewave.*`

This is FLOW's "no Nautilus import in `signal.py`" rule, transplanted. A
strategy plug-in depends only on our own core types. If a strategy can't be
written without reaching into the SDK, the core is missing a feature — add
it to the core, don't leak the platform into the strategy. This is what
keeps strategies unit-testable, mutually consistent, and portable if the
platform ever changes underneath us.

### Adding a strategy

1. New class in `strategies/`, implementing `FlowStrategy`.
2. One line in the registry.
3. Unit-test it against hand-built `MarketState` snapshots — assert the
   intent you expect, *before* the platform is involved.
4. Recompile, redeploy, pick it from the dropdown, run in dry-run.

No new `@StudyHeader`, no new deployment target, no core edits, no touching
another strategy. If step 1 forces you to modify anything in `core/`,
`flow/`, `price/` or `exec/`, that's a signal the core abstraction is wrong
— fix it there properly, once, for all strategies.

## Safety — non-negotiable

Carried over from `../motivewave/CLAUDE.md`, and tightened by the structure
above.

- **"Sim Trade Only" stays enabled** in MotiveWave settings. It disables
  order placement on every account but the Simulated one, platform-wide,
  regardless of what any code or button does.
- **Every inherited order-capable hook is explicitly overridden** in the
  runtime class — `onEnterNow` above all. Omitting a hook inherits
  MotiveWave's default, and MotiveWave's default for `onEnterNow` places a
  market order.
- **Dry-run is the default mode.** The runtime ships with `armed = false`:
  intents are computed and journaled, no order is ever submitted. Arming is
  a deliberate per-session act.
- **Confirm the selected account, out loud, at every activation.** Not once
  — every time. The GUI's account selection can change between sessions.
- **Never place, modify or cancel an order as a side effect of other work.**

## Forward-test workflow

Three modes, in order, each a gate on the next:

1. **Dry run** — strategy computes intents against live data; nothing is
   submitted. Read the journal: did it fire where you'd have fired
   manually? This catches most "the idea is wrong" and all "the feature is
   buggy" failures for free.
2. **Sim** — same thing, orders actually submitted to the Simulated
   account. Now fills, slippage, partial fills and rejections become real
   and the journal starts producing a PnL curve.
3. **Live** — out of scope until a strategy has earned it, and gated by an
   explicit decision recorded in `docs/dynamic/decisions.md`.

Comparison across strategies is done **offline, from the journals** — one
run directory per session per strategy, structured JSONL. Analysis tooling
there can be Python; the constraint is that the *runtime* is all-Java, not
that every script ever written is.

### Record now, replay later

The journal records every input event the runtime saw, not just its
decisions. That makes a replay harness — feed recorded ticks and DOM back
through the identical feature and strategy code — a straight-line future
project, and it's the only honest path to backtesting order-flow logic. It
is deliberately not being built now, but the journal format is designed so
it stays possible.

Raw tick/DOM-level journal data is **retained for a short rolling window
only (last 2–3 days — exact number to be set once real data size is
known)**, not indefinitely. Order-flow data at MBO granularity is large
enough that unbounded retention is a real disk-space decision, not a free
default. Higher-level records (intents, orders, fills, bar-level summaries)
are cheap and can be kept longer; the retention window applies specifically
to the raw event stream a future replay would need.

## Repo layout (planned, tentative)

```
FLOW_V2/
├── README.md              this file
├── CLAUDE.md              working rules (inherits FLOW's and motivewave's)
├── docs/dynamic/          decisions.md, findings.md — about this system
├── app/src/com/flow/
│   ├── runtime/           the single @StudyHeader host Strategy; settings, lifecycle
│   ├── core/              event types, MarketState, Intent, FlowStrategy, registry
│   ├── price/             candles, swings, structure, levels
│   ├── flow/              delta, footprint, volume profile, liquidity map, big trades
│   ├── external/          manual/pluggable input store (GEX levels, daily bias, etc.)
│   ├── exec/              intent → orders, sizing, brackets, session/risk guards
│   ├── journal/           JSONL writers for inputs, snapshots, intents, orders, fills
│   └── strategies/        one package per strategy plug-in
├── app/build/             compile + redeploy scripts (portable JDK 26)
└── logs/
```

This layout is a starting sketch, not a commitment — expect it to shift once
real code exists and the boundaries get tested against an actual second and
third strategy, the same way FLOW's `flow/backtest/common/` only took its
current shape after a second strategy actually needed it.

Nothing here is built yet. `../motivewave` proved the pieces exist — live
tick data with aggressor side, true Market-by-Order DOM depth, working
strategy lifecycle, readable account state. This repo turns that into a
system.

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
  tier. Irrelevant to forward testing; relevant to replay later.

## Open questions, to settle empirically before they're designed around

Recorded here rather than guessed, per the methodology. Each becomes an
experiment (platform questions in `../motivewave/experiments/`, ours here)
and then a decision.

1. **Bar/tick ordering.** Do ticks belonging to a bar reliably arrive before
   `onBarClose` fires for it, or can a bar close with its last ticks still
   in flight? Determines whether the decision point can trust bar-aligned
   flow aggregates.
2. **Settings UI limits.** Can one settings panel show only the selected
   strategy's parameters (conditional visibility/enablement), or do all
   strategies' params have to coexist visibly? Shapes how strategy params
   are declared.
3. **Instruments and timeframes.** Which contracts do we forward-test first
   (`@GC` is what's been used so far), on what bar interval, and does a
   strategy get to declare the timeframe it requires?
4. **Session model.** RTH only or 24h? Flatten at session end by default?
5. **Sizing and risk defaults.** Fixed contracts, or risk-per-trade sized
   off the stop distance? What's the daily-loss kill switch?
6. **Intrabar decisions.** Is bar-close-only enough for the first
   strategies, or does the order-flow half need tick-level entry timing
   from day one?
7. **Simulated-account fidelity.** How does MotiveWave's simulator fill —
   last price, bid/ask, queue-aware? Determines how much to trust sim PnL.

## Notes

Personal research project, built alongside full-time work as a developer at
a brokerage. Not affiliated with my employer, uses no proprietary data or
code. Not investment advice.
