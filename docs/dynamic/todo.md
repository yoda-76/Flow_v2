# To-do

Every task still to be done for FLOW_V2, in rough order. Companion to
`decisions.md` (closed answers) and `findings.md` (evidence) — this file is
the queue, those two are the record. When a task closes, tick it, add the
date, and link the decision/finding it produced; don't delete it.

## Where we left off (2026-09-17, updated — read this first, assume no memory of the conversation that produced it)

**Built, deployed, and live-verified against real `@GC` ticks**: walking
skeleton (D-41: sequencer, two-tier journal, `NullStrategy`, all 15
`OrderContext` safety hooks reflection-tested), replay harness (D-42:
`ReplayHarness`/`ReplayEquivalenceTest`, PASS on the D-41 session),
`VolumeProfileView` (D-44: SDK engine wrapped with the E-3 rotation fix,
drawn POC/VAH/VAL matched the built-in study — VAH exact, VAL within 2
ticks), D-38's `NamedLevel`/`NamedZone` triggers (D-45: `LevelCross`/
`ZoneTransition`, found and fixed a real pre-existing bug in `Pipeline`'s
trigger loop along the way), and `LevelZoneObserverStrategy` + the price
trace (D-46: 96 `level_trace` + 220 `zone_trace` records fired correctly,
zero exceptions).

**Built and deployed, NOT yet live-verified**: D-47 — POC-relative row
numbering (POC=`0`, `+`/`-` rows scaled by distance, a zone overlapping
VAH/VAL forced to that level's exact number) on chart labels and in the
price trace (`relativeRow`/`priceDecimal` fields added to
`level_trace`/`zone_trace`). D-48 — the trace now also carries the
level's own value (`levelPriceTicks`/`levelPriceDecimal`, distinct from
current price) on `level_trace`, and a zone's own low/high
(`zoneLowTicks`/`zoneHighTicks` + decimals) on `zone_trace`, per the
user's explicit request. Both built same day, full rebuild and replay
regression clean for both — needs the study removed and re-added once to
confirm either live, never watched running against real ticks since.

**Open observation, action deferred, not a bug report**: HVN/LVN
classification may be too dense (see the todo item a few lines below
this one for the full writeup) — but the screenshot comparison that
surfaced it wasn't apples-to-apples (ours was `rangeTicks=1`, the manual
comparison was a 4-tick VP), so the real next step there is rerunning the
comparison at matched granularity before concluding anything, not tuning
blind.

**D-39 (LVN/HVN reversal ranking) was about to start and was deliberately
NOT started.** The user's own call, stated directly: further building
from this point means making ranking-model decisions (Layer 1 factor
weights, whether/how to scope "confluence" given its dependency features
don't exist yet, the Bayesian blend's prior-weight constant) that get
harder to reverse once anything downstream depends on them, and D-39 is
exactly that kind of decision — better to stop at a clean, validated
boundary than lock in a ranking model on guesses. This was a deliberate
pause, not a blocker or an unfinished task.

**When resuming, in order of what's cheapest to close first:**
1. Live-verify D-47/D-48 together (remove/re-add the study once, confirm
   the chart labels and all the new trace fields — `relativeRow`,
   `priceDecimal`, `levelPriceTicks`/`levelPriceDecimal`,
   `zoneLowTicks`/`zoneHighTicks` + decimals — look right).
2. Rerun the HVN/LVN density comparison with our `rangeTicks` set to 4
   (matching the manual comparison), before deciding whether the
   classifier actually needs tuning.
3. Resume D-39 — two open sub-decisions were on the table when this
   session stopped, neither answered yet: (a) build Layer 1 now without
   a working confluence factor (returns neutral until swing
   points/big-trades/session-levels exist), or pause D-39 to build those
   dependencies first; (b) pick a default Bayesian prior-weight constant
   now (e.g. ~3 "virtual touches") vs. the user specifying one. Don't
   assume either answer — ask again if picking this back up.

**Where the work happens:**

- **Experiments run in `../motivewave` only.** Anything that needs a
  throwaway study, a live capture or a probe against the platform is built
  in `../motivewave/experiments/`, and its result lands in
  [[../../motivewave/docs/dynamic/findings.md]]. This repo only records the
  decision that follows, linking back.
- **This repo is for the main code.** `flow-core/`, `flow-runtime/`,
  `build/` — and building starts on explicit instruction only (see
  `CLAUDE.md`).
- Tags: `[EXP]` experiment in `../motivewave` · `[DECIDE]` system decision
  made here, no experiment needed · `[BUILD]` code in this repo.

Status: `[ ]` open · `[~]` in progress · `[x]` done (dated) · `[-]` dropped
(dated, with reason)

## 0. Session handoff (2026-09-15) — read this first

**All eight original open questions plus Q-09 are closed.** Q-01→D-33,
Q-02(a)→D-31, Q-04→D-28, Q-05→D-29, Q-06→D-30, Q-08→D-32, Q-09→D-34,
Q-03→D-35 (spun off **Q-10**, still open — see below). Q-02(b) and Q-07
remain, both order-placement, deferred/optional.

**Big one: `docs/dynamic/sdk-capability-findings.md` (SDK capability
audit) has been substantially live-verified — see D-36.** Headline:
**do not build a custom volume profile from scratch.** The SDK's own
`sdk.profile.VolumeProfile` engine, wrapped in `flow-runtime` and fed by
our own ticks, was confirmed live to match the chart's built-in Volume
Profile study closely once both are scoped to the same window (the one
mismatch found traced to the built-in study's "Use Historical Bars"
option, not the engine). Same engine covers footprint and delta too.
D-26 is amended accordingly (its "two competing implementations" plan is
retired); D-22 is amended (volume profile and VWAP move from readiness
class 2 to class 3, tick-fed not bar-fed); VWAP's approximation caveat is
also retired — the real MotiveWave source is genuinely tick-weighted.
**Read `sdk-capability-findings.md`'s live-verification table before
touching any of E-1 through E-12** — it has per-experiment status and
points at the full trail in `../motivewave/docs/dynamic/findings.md`.

**Still genuinely open from that audit:** the liquidity heatmap (built-in
`Order Heatmap`/`DOM Power` visual quality vs. a custom MBO-fed one —
not yet checked against real output) and **Q-10** — raw journal DOM tier
retention policy. Full per-order DOM detail costs ~31.5 GB/hr raw (~3.89
GB/hr gzipped) vs. ~28 MB/hr (~1.35 MB/hr gzipped) for top-of-book-only —
roughly 1,100-2,900x larger, measured via naive full-snapshot-per-update
logging (an upper bound, not final — a delta encoding is unmeasured).
See D-35's three options before deciding.

**Standing config for any future diagnostic `Strategy` with no real entry
logic** (learned the hard way, full trail in
`../motivewave/docs/dynamic/findings.md` 2026-09-14 `[LIVE]` entries):
`autoEntry=true, manualEntry=false, supportsPositionType=false`
(default), plus `supportsEnterOnActivate=false,
supportsCloseOnDeactivate=false` explicit. `autoEntry=false` +
`manualEntry=false` together produce a dead-end "Please Choose Long or
Short" dialog on Activate with no actual chooser in it. Also: figures
(`addFigure`) must be drawn from a real MotiveWave callback thread
(`onTick`/`onBarClose`), never a spawned timer thread — fails silently,
no exception, if you get this wrong; and `destroy()` must stop any
background thread/listener a diagnostic study starts, or a "removed"
instance keeps running as a zombie.

A working live example of most of this — session-scoped `VolumeProfile`,
bar-scoped footprint, `AggregateFilter` big trades, `calcSwingPoints`,
DOM history, chart-drawn POC/VAH/VAL lines, and a clustered translucent
LVN box, all redrawn once per second — is
`../motivewave/experiments/src/flow_diag/SdkCapabilityProbe.java`.

Both repos' changes are committed; push still pending as of this entry.

## 1. Resolve open questions

Platform questions — experiments in `../motivewave`, then a decision here:

- [x] (2026-09-14) **Q-01** `[EXP]` Bar/tick ordering — **done → D-33:
  yes**, ticks reliably arrive before `onBarClose` fires. Zero violations
  across 2,738 ticks / 12 bar closes on live `@GC`
  (`logs/ordering_probe.log`). Secondary observation carried into
  findings for D-23 later: `recvTime` ran ~668ms before `tickTime`
  throughout — cause (clock skew vs. `tick.getTime()` semantics) not yet
  isolated.
- [x] (2026-09-14) **Q-02 (a)** `[EXP]` `OrderContext` retention — retain
  from `onActivate`, call **read-only** methods from a later callback and
  from a timer thread, log results + `System.identityHashCode(ctx)`.
  Zero-risk. Feeds D-17's flush point. **Done → D-31**: stable identity
  hash across platform threads and this probe's own poller thread,
  `pos=0`/`cash` flat throughout, confirmed live in
  `logs/context_retention_probe.log`.
- [ ] **Q-02 (b)** `[EXP]` Optional now, not blocking (D-31 gave D-17 a
  safe default already). Far-from-market limit order from a retained
  reference, confirm, cancel — Sim Trade Only, its own session, **explicit
  in-the-moment confirmation per `CLAUDE.md`**. Only worth running if
  inline-flush latency ever matters enough to chase.
- [x] (2026-09-14) **Q-03** `[EXP]` Raw data volume — **done → D-35.**
  Ticks + top-of-book DOM: ~28.0 MB/hr raw, ~1.35 MB/hr gzip (~54 min live
  `@GC`, `TickDomLogger`). Full per-order DOM detail (`DomDetailCapture`,
  6,000-update self-bounded capture): 2,747.6 `DOMOrder`/update,
  extrapolates to ~31.5 GB/hr raw, ~3.89 GB/hr gzip. Compressed JSONL
  closes the question for ticks+top-of-book; full per-order detail reopens
  it as **Q-10** (raw journal DOM tier policy, below) rather than closing
  it outright.
- [ ] **Q-07** `[EXP]` Sim fill fidelity — how does the Simulated account
  fill (last, bid/ask, queue-aware)? Order placement is involved, so same
  confirmation rule as Q-02 (b).
- [x] (2026-09-14) **Q-08** `[EXP]` Is the built-in volume profile readable
  from another study? **Done → D-32: no.** Confirmed live — an EMA added
  alongside the built-in Volume Profile study shows no Volume-Profile-
  derived option in its Input dropdown, and right-clicking the Volume
  Profile plot gives a plot-specific menu with no Create/Add Alert entry.
  `BuiltInVolumeProfile` is not automatic; D-26's journal-and-compare-by-
  hand fallback is the plan. (Superseded in practice by D-36: we don't
  need to read the built-in study's output at all — the SDK's own
  `VolumeProfile` engine, fed by our own ticks, is what gets used.)

System questions — decided here:

- [x] (2026-09-14) **Q-04** `[DECIDE]` Instruments and timeframes — `@GC`
  first, 1-minute bars by default, strategy may declare required bar
  interval(s) → D-28
- [x] (2026-09-14) **Q-05** `[DECIDE]` Session model — 24h (not RTH-only),
  flatten at session end by default → D-29. Sub-session split off as
  Q-09, now also closed.
- [x] (2026-09-14) **Q-06** `[DECIDE]` Sizing and risk defaults — fixed
  contracts to start, daily-loss kill switch on realized + open PnL, exact
  value left as a config value rather than a decision → D-30
- [x] (2026-09-14) **Q-09** `[DECIDE]` Session separations within the 24h
  day — Asia (18:00–03:00 CT) / London (02:00–08:00 CT) / NY-RTH
  (08:20–13:30 CT), informational grouping only. Counters (reversal cap,
  price anchor, journal file boundary) reset once per full 24h day, not
  per sub-session → D-34.
- [ ] **Q-10** `[DECIDE]` Raw journal DOM tier — how much per-order detail,
  retained for how long? Spun off from Q-03 by D-35: full detail costs
  ~31.5 GB/hr raw / ~3.89 GB/hr gzip vs. ~28 MB/hr / ~1.35 MB/hr for
  top-of-book-only. Three options in D-35 (top-of-book-only in the raw
  journal / build+measure a delta encoding / accept a short full-detail
  window). Hinges on whether D-22's forward-only features (order resting
  time, liquidity-pull frequency) need replay-grade order-ID history.

## 1b. SDK capability audit `[EXP]` — see `docs/dynamic/
sdk-capability-findings.md` for full detail per item; live results in
`../motivewave/docs/dynamic/findings.md`

Separate from the Q-numbered questions above; this is "which SDK engine
classes can we reuse instead of building our own" (E-11/E-12 are the same
work as Q-03/Q-02(a) above, listed here too since they're part of this
audit's numbering).

- [x] (2026-09-14) **E-1** Signature sweep — all of §2 resolves against
  our real jar except `TPOProfile`'s constructor (out of scope). Ran live
  against a real instrument, zero exceptions.
- [x] (2026-09-15) **E-2** VolumeProfile parity — **confirmed close match
  → D-36**, the headline result of this whole audit.
- [x] (2026-09-15) **E-3** Memory/throughput — **clean single-instance
  11-minute run done, result is a real concern, not "modest."** T-1 guard
  (300MB) tripped in ~220s / 317 ticks, ~1.1-1.2 MB retained per tick,
  extrapolating to ~35-40 GB for a full RTH session unbounded. See
  `../motivewave/docs/dynamic/findings.md` 2026-09-15. Processing speed is
  fine (~1.2µs/tick steady-state). **Not yet a decision** — this is input
  to the features/triggers discussion (D-37) on how `VolumeProfileView`
  bounds a session-scoped profile (periodic reset, aggregate-only reads,
  etc.), which still needs to happen before any `VolumeProfileView` code.
- [~] **E-4** Footprint/imbalance — bar-scoped rows + imbalance flags
  captured live at two threshold settings, not yet formally diffed
  against the chart's footprint row-by-row.
- [~] **E-5** AggregateFilter/big trades — repeat-emission by real order
  ID confirmed live (T-3 real); `exchOrderId=0` sentinel wrinkle needs
  filtering out before trusting a repeat count.
- [x] (2026-09-14) **E-6** VAMethod accessibility — confirmed dead from
  `jar tf` alone, no code needed. Only `getValueArea(double)` is usable.
- [~] **E-7** Swing stability — real instability observed live (heavy
  revision at low strength, occasional stability at higher strength),
  not yet turned into a formal rule for structure logic to rely on.
- [x] (2026-09-14) **E-8** Secondary timeframe — confirmed `NULL` live,
  matches the forum report (T-4).
- [~] **E-9** DOM history shape/cadence — logged every heartbeat
  throughout, not yet analyzed for cadence/retention specifics.
- [x] (2026-09-14) **E-10** Studies source bundle — inventoried
  `MotiveWave/motivewave-studies` (339 files). VWAP confirmed
  tick-weighted; no footprint/big-trades/heatmap source exists;
  `sdk.profile.*`/`AggregateFilter` have zero usage anywhere in it
  (raises the stakes on E-2/E-5's live confirmation); `calcSwingPoints`
  does have real usage (4 studies).
- [x] (2026-09-14) **E-11** = Q-03, done → D-35.
- [x] (2026-09-14) **E-12** = Q-02(a), done → D-31.
- [ ] **Liquidity heatmap visual check** (the user's own idea, not
  originally in the E-numbered list) — add the built-in `Order Heatmap`
  and/or `DOM Power` study, judge whether its quality is good enough to
  skip building a custom MBO-fed heatmap. Not yet done — the two studies
  were added to a chart early in this effort but no follow-up comparison
  happened.

## 2. Walking skeleton `[BUILD]` — waiting on explicit go

Per README "Build order". No real feature yet; goal is a full session whose
journal reconstructs what happened and whose replay reproduces it exactly.

- [x] (2026-09-15) Build scripts: compile `flow-core` **without**
  `mwave_sdk.jar`, compile `flow-runtime` with SDK + core, deploy both
  class trees to `%USERPROFILE%\MotiveWave Extensions\dev\` (D-09) →
  `build/build.sh`. Compiles clean, deployed. See D-41.
- [x] (2026-09-15) Core types: immutable sequenced event types
  (`Event`/`TickEvent`/`BarEvent`/`DomEvent`/`ClockEvent`/`OrderEvent`/
  `FillEvent`), integer tick-offset prices (D-21), `Intent` as desired
  state (D-13), `FlowStrategy`, `MarketState`/`MutableMarketState` with
  generation guard + `freeze()` (D-12), `StrategyRegistry` →
  `flow-core/src/com/flow/core/`.
- [x] (2026-09-15) Sequencer: single-writer drain loop, `publish()`
  synchronized so seq order matches enqueue order across producer threads
  (D-10) → `Sequencer.java`. Blocks rather than drops on backpressure —
  deliberately different from the journal's raw tier (see below), since
  dropping a market event here would corrupt feature state silently.
- [x] (2026-09-15) `ClockEvent` injection at 100ms, event time only below
  ingest (D-11, D-23) → `FlowRuntimeStudy.startSession()`'s
  `clockExecutor`.
- [~] (2026-09-15) SDK→core adapters in `flow-runtime`: `onTick` and
  `onBarClose(DataContext)` done. `DOMListener` **not wired** (`DomEvent`
  type exists, nothing publishes it yet — needed for `BookChange` trigger
  and the liquidity map later). Order/fill hooks **deliberately not
  wired** — no order-submission code exists anywhere in this tree yet, so
  there is nothing to produce a real `OrderEvent`/`FillEvent` from.
- [x] (2026-09-15) Journal: writer thread, bounded queues, decisions tier
  (JSONL, change-only, heartbeat every 100 clock events ≈10s) + raw tier
  (JSONL per D-35's "compressed JSONL is clearly sufficient" finding, not
  a binary encoding), gap markers on raw drop, loud failure (logged +
  flagged, checked every event) on decisions-queue overflow, session
  header record (D-15) → `flow-core/src/com/flow/journal/`.
- [ ] External config store: file loader for `strategyId`-scoped params,
  risk defaults, session config, external levels; journaled with staleness
  (D-08, D-18). **Not done** — `StrategyConfig` exists as an empty-map
  placeholder only.
- [x] (2026-09-15) Runtime `@StudyHeader` Study: settings panel with only
  `strategyId`, `armed`, `mode`; `armed = false` default →
  `FlowRuntimeStudy.java`. `armed` currently has no effect either way,
  since no order-submission code exists to gate.
- [x] (2026-09-15) `OrderGateway` — sole `OrderContext` holder,
  package-private, dry-run only (D-14) → `flow-runtime/.../OrderGateway.java`.
  `reconcileDryRun()` only reads `getPosition()` and journals what it
  would do; contains no call to any order-placement method at all. Flush
  point placement (Q-02) still open, moot until submission code exists.
- [x] (2026-09-15) Exception boundary: disarm, journal with seq, keep
  ingesting (D-25) → `Pipeline` implements `Sequencer.ExceptionHandler`.
- [ ] Refuse-to-arm on existing position / resting orders (D-24). **Not
  done** — moot for now since nothing arms, but needed before this stops
  being true.
- [x] (2026-09-15) Readiness framework: per-feature readiness, arming
  blocked with journaled reason (D-22) → `Feature`/`ReadinessChecker`.
  Vacuously trivial right now — zero features exist, `NullStrategy`
  requires none — but the mechanism is real and compiles against it.
- [x] (2026-09-15) Null feature + null strategy (`Intent.none()`) →
  `NullStrategy`. No literal `NullFeature` class — a strategy requiring
  zero features already satisfies `ReadinessChecker` vacuously, so one
  would be dead code.
- [x] (2026-09-16) Replay harness: feed recorded raw journal back through
  identical code → `ReplayHarness.java`. Deliberately bypasses
  `Sequencer` entirely — replay is inherently sequential (read the file
  top to bottom in original seq order), so a plain loop calling
  `Pipeline.handle()` directly is simpler and more deterministic than
  routing through queue/thread machinery that exists only to order
  concurrent live producers. `RawEventCodec` (encode used live by
  `Pipeline`, decode used by replay) keeps the two directions from
  drifting apart — one class, not two independently maintained ones.
  `StrategyRegistrations.buildDefault()` is now the single registration
  point both `FlowRuntimeStudy` and `ReplayHarness` call, so "identical
  strategy code" can't silently drift between live and replay either.
- [x] (2026-09-16) Run one full dry-run session, confirm journal +
  replay. **Live-verified, both halves**: journal half per the
  2026-09-15 entry (superseded by this one). Replay half: ran
  `ReplayEquivalenceTest` against the recorded live session — **PASS**,
  3472 events replayed, 0 gaps, replayed decisions.jsonl byte-identical
  to the live one for every field checked (generation, exchangeTimeMs,
  localTimeMs, heartbeat cadence). **Still not exercised**: the
  intent-changed → `OrderGateway.reconcileDryRun` path and its replay
  equivalence — this was a 0-intent-changes-vs-0 degenerate pass
  (`NullStrategy` never changes), which proves the mechanism doesn't
  false-positive but not that it correctly reproduces a *real* change.
  Stays open until a real strategy exists to exercise it.

## 3. Structural tests `[BUILD]` — from day one

- [x] (2026-09-15) Reflection test in `flow-runtime`: every
  `OrderContext`-taking method
  on `Study` is overridden by the runtime class (D-14)
- [x] (2026-09-16) Replay-equivalence test: record → replay → identical
  intent sequence (D-11) → `ReplayEquivalenceTest.java`, no SDK needed
  (runs under a plain JDK per README "Testing"). **PASS** against the
  2026-09-15 live session — see the §2 entry above for the important
  caveat (degenerate 0-vs-0 comparison, real-change equivalence still
  unexercised).
- [~] Event-sequence test DSL in `flow-core` (D-20). `TriggerEvaluatorTest`
  (D-45) is a direct, non-DSL synthetic test covering the same territory
  for trigger logic specifically and already caught one real bug — the
  general-purpose DSL README describes (`seq().trade(...).expectIntent(...)`)
  for strategy-level fixtures is still not built.

## 4. Core features and execution `[BUILD]`

- [ ] Delta (already proven in the `../motivewave` skeleton study; also
  free from the SDK's `VolumeProfile.getTotalDelta()` per D-36)
- [ ] Market structure / swings with bar-history warmup, built on
  `DataSeries.calcSwingPoints` (confirmed real usage pattern, E-10) —
  factor in E-7's observed swing revision behavior before treating a
  swing as final
- [x] (2026-09-15) Features/triggers discussion required by D-37 — **done,
  see D-38/D-39/D-40.** No code written yet against any of them.
- [x] (2026-09-16) `VolumeProfileView` (flow-core interface) wrapping the
  SDK's `sdk.profile.VolumeProfile` (flow-runtime) per D-36/D-43 → see
  D-44. **Built and live-verified**: E-3 rotation fix running clean (150
  ticks / 3 min), zone ids confirmed stable across recomputes, and drawn
  POC/VAH/VAL lines visually converged with the built-in study's own
  levels after a few minutes live on `@GC`. Session-scoped only;
  footprint (bar-scoped `FootprintView`, same engine) is a separate
  not-yet-started item.
- [ ] Replay-inside-MotiveWave (D-43): a mechanism to feed a recorded raw
  journal through the same runtime code but inside a MotiveWave-hosted
  process, so a real `Instrument` exists and SDK-engine-backed features
  (`SdkVolumeProfileFeature` today) can be replayed at all. Not designed
  yet. Until this exists, `ReplayHarness`/`ReplayEquivalenceTest` cannot
  verify replay-equivalence for any strategy depending on volume profile.
- [x] (2026-09-16) `NamedLevel`/`NamedZone` triggers (D-38): `TOUCH`/
  `CROSS_ABOVE`/`CROSS_BELOW` for POC/VAH/VAL; `ENTER`/`LEAVE`/`TOUCH` for
  LVN/HVN clusters, as new dynamic trigger types alongside D-16's
  `PRICE_CROSS`/`BOOK_CHANGE` → see D-45. `Trigger.LevelCross`/
  `Trigger.ZoneTransition` (feature-agnostic: featureId + name/kind, not
  hardcoded to volume profile), `LevelSource`/`ZoneSource` interfaces
  (`VolumeProfileView` implements both via default methods),
  `TriggerEvaluator` extended, boundary-flicker debounce on `LEAVE`
  implemented. Unit-verified via `TriggerEvaluatorTest` (33 synthetic
  checks, no platform needed) — **not yet live-verified**, since no real
  strategy declares one of these triggers yet. Found and fixed a real
  pre-existing bug along the way: `Pipeline`'s trigger loop broke on the
  first trigger that fired, silently desyncing any other declared
  trigger's internal state for that event (e.g. a strategy watching both
  `ENTER` and `LEAVE` on the same zone kind).
- [x] (2026-09-16) `LevelZoneObserverStrategy` (D-46): first strategy to
  declare D-38's triggers (16 total: `LevelCross` × POC/VAH/VAL,
  `ZoneTransition` × LVN/HVN), still `Intent.none()` always — a pure
  observer proving the trigger/trace mechanism, not a signal yet.
  **Live-verified**: ran on `@GC`, 96 `level_trace` + 220 `zone_trace`
  records fired correctly, zero exceptions.
- [x] (2026-09-16) Zone/level "price trace" — foundational half of D-40's
  journal only (see D-46): `Pipeline` now journals a `level_trace`/
  `zone_trace` decisions-tier record for every `LevelCross`/
  `ZoneTransition` firing, regardless of strategy outcome. `CREATED`/
  `MERGED`/`SPLIT`/`DISSOLVED` and the `layer1Score`/`outcome`/
  `bounceRate` pairing (D-39-dependent) are the remaining, not-yet-built
  half — see the next item.
- [~] (2026-09-16) POC-relative row numbering (D-47): chart labels and
  `level_trace`/`zone_trace` (`relativeRow`, `priceDecimal` fields) both
  use it — POC=0, +/- rows scaled by distance, zones overlapping VAH/VAL
  forced to that level's exact number. Full rebuild + replay regression
  clean. **Not yet live-verified** — needs the study re-added.
- [~] (2026-09-17) Price trace carries level/zone identity, not just
  current price (D-48): `level_trace` gains `levelPriceTicks`/
  `levelPriceDecimal` (the level's own value, can differ from current
  price under D-38's cause-agnostic cross); `zone_trace` gains
  `zoneLowTicks`/`zoneHighTicks` + decimals (which specific zone fired,
  not just its kind). Needed `TriggerEvaluator.lastFiredZoneRange()` — a
  fire-time snapshot, since a `LEAVE`'s zone range is already cleared
  from the normal tracking state by the time `Pipeline` would otherwise
  read it. Full rebuild + replay regression clean. **Not yet
  live-verified**.
- [ ] **HVN/LVN classification may be too dense — needs a fair
  same-granularity comparison before concluding anything, then tuning if
  still warranted.** Visual observation, 2026-09-16 (screenshots
  `Screenshot 2026-09-16 203513.png` = ours, `.../203835.png` = the
  user's manual read, both `C:\yadvendra\New folder\`, not committed to
  the repo): on a consolidation/low-volume chunk of `@GC`, our LVN/HVN
  bands covered almost the entire visible price range with barely any
  gaps, while the user's own manual read of the same chunk picked out
  only a handful of distinct, separated zones. **Confound found
  2026-09-16, not yet controlled for**: the two weren't at the same
  granularity — ours was `rangeTicks=1` (finer rows, mechanically more of
  them to classify), the manual read was against a 4-tick VP (coarser).
  The density gap could be mostly or entirely explained by that alone.
  **Next step before any tuning**: rerun the same comparison with our
  `rangeTicks` setting also set to 4, same chunk, then judge whether a
  real over-classification problem remains. Current classifier is
  `hvn_threshold=1.5`/`lvn_threshold=0.5` against a `window=5` rolling
  local average (`SdkVolumeProfileFeature`, ported from
  `FLOW/flow/features/volume_profile.py`) — if the gap persists at equal
  granularity, plausible causes worth checking: thresholds too
  permissive, window too small/local (contrasting each row only against
  its 5 nearest neighbors rather than a wider or session-level baseline),
  or the local-contrast approach itself needing a minimum-separation/
  clustering step so adjacent marginal rows don't each independently
  qualify. Matters beyond cosmetics: D-39's Layer 1 "void depth" score
  and the zone-identity/trigger system (D-38) both operate per-zone, so
  an over-dense classification means more, noisier, less meaningful
  zones everywhere downstream. Not started — observation only, no
  decision or approach chosen yet.
- [ ] LVN/HVN reversal ranking (D-39): Layer 1 intrinsic composite (void
  depth/width, shoulder strength, POC/VA position, confluence, formation
  delta, recency) blended with Layer 2 track record (touch/outcome
  counting off `ENTER`/`LEAVE`, session-scoped in-memory) via the
  Bayesian-style prior/empirical blend.
- [ ] Zone lifecycle journal record kind (D-40), remaining half: pair
  `layer1Score` (D-39) with `outcome` on each trace record — the raw
  `zone_trace`/`level_trace` records exist (above) but carry no ranking
  fields yet since D-39 doesn't exist.
- [ ] Session / prior-session levels, ATR, overnight high/low, VWAP
  (adapted from MotiveWave's published source per D-36, tick-weighted,
  method journaled)
- [ ] Liquidity map, heatmap, and book imbalance (derived DOM views only
  — raw DOM never crosses `MarketState`): still ours to build from the
  live MBO DOM stream (`sdk-capability-findings.md` §2.8) unless the
  built-in visual check (§1b above) says the built-in `Order Heatmap`/
  `DOM Power` is good enough — not yet done
- [ ] Big trades: `BigTradeEvent` (flow-core) wrapping `AggregateFilter`
  (flow-runtime, `aggByOrder=true`) per D-36/E-5 — dedupe on repeat
  emission by real order ID (T-3, not the `exchOrderId=0` sentinel);
  fixed vs relative threshold declared, D-22. Footprint: see the
  `VolumeProfileView` line above, same engine
- [x] (2026-09-15) Partial-bar-at-attach handling → **D-37: mark invalid,
  skip it, build starts from the next full bar close, no backfill.**
- [ ] Triggers: `BAR_CLOSE`, `EVERY_TICK`, `THROTTLE`, dynamic
  `PRICE_CROSS` / `BOOK_CHANGE`, wake reason journaled (D-16)
- [ ] `exec/` reconciliation: intent diff → minimal order actions (D-13)
- [ ] Risk chain: armed, session open, readiness, daily-loss, size cap,
  rate limit, churn guard, lag guard — each verdict journaled (D-13, D-19).
  Values depend on Q-05, Q-06.
- [ ] Sizing and brackets per Q-06
- [ ] Intent-seq vs execution-seq gap journaled per trade (D-17)
- [ ] Entry evaluators in `flow/`: absorption, imbalance stacking,
  sweep-and-reclaim (D-27)

## 5. Strategies and forward testing

- [ ] First real strategy, `BAR_CLOSE` trigger, unit-tested on synthetic
  sequences before the platform is involved
- [ ] Dry-run sessions → review journal against manual judgment
- [ ] Sim sessions (arming is a per-session act; confirm account out loud
  at every activation)
- [ ] Second real strategy — tests whether the layer boundaries hold;
  revisit package layout and `ZoneEntryStrategy` only after this (D-27)
- [ ] Offline journal comparison tooling (Python OK)
- [ ] Commit first recorded fixtures from real sessions (D-20)

## 6. Doc housekeeping

- [x] (2026-09-14) `CLAUDE.md` and `findings.md` still say "seven open
  questions" — there are eight (Q-01…Q-08)
- [x] (2026-09-14) `CLAUDE.md` directory structure still showed `app/`;
  now matches README's `flow-core/` + `flow-runtime/` + `build/` layout
- [x] (2026-09-14) `README.md` / `decisions.md` "Open questions" intro
  said system questions get experiments here — now states platform
  questions (Q-01/02/03/07/08) get a `../motivewave` experiment and system
  questions (Q-04/05/06) are decided directly, no experiment needed
