# To-do

Every task still to be done for FLOW_V2, in rough order. Companion to
`decisions.md` (closed answers) and `findings.md` (evidence) — this file is
the queue, those two are the record. When a task closes, tick it, add the
date, and link the decision/finding it produced; don't delete it.

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
- [~] **E-3** Memory/throughput — heap growth modest, guard never
  tripped, but no clean single-instance full-session measurement yet
  (duplicate-instance contamination in the runs so far).
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

- [ ] Build scripts: compile `flow-core` **without** `mwave_sdk.jar`,
  compile `flow-runtime` with SDK + core, deploy both class trees to
  `%USERPROFILE%\MotiveWave Extensions\dev\` (D-09)
- [ ] Core types: immutable sequenced event types, integer tick-offset
  prices (D-21), `Intent` as desired state (D-13), `FlowStrategy`,
  `MarketState` with generation guard + `freeze()` (D-12), registry
- [ ] Sequencer: MPSC queue, single-writer drain loop (D-10)
- [ ] `ClockEvent` injection at 100ms, event time only below ingest
  (D-11, D-23)
- [ ] SDK→core adapters in `flow-runtime`: `onTick`, `DOMListener`, bar
  hooks, order/fill hooks
- [ ] Journal: writer thread, bounded queues, decisions tier (JSONL,
  change-only, heartbeat) + raw tier, gap markers on raw drop, loud failure
  on decisions-queue overflow, session header record (D-15). Raw encoding
  depends on Q-03.
- [ ] External config store: file loader for `strategyId`-scoped params,
  risk defaults, session config, external levels; journaled with staleness
  (D-08, D-18)
- [ ] Runtime `@StudyHeader` Study: settings panel with only `strategyId`,
  `armed`, `mode`; `armed = false` default
- [ ] `OrderGateway` — sole `OrderContext` holder, dry-run only for now
  (D-14); flush point placement depends on Q-02 (D-17)
- [ ] Exception boundary: disarm, journal with seq, keep ingesting (D-25)
- [ ] Refuse-to-arm on existing position / resting orders (D-24)
- [ ] Readiness framework: per-feature readiness, arming blocked with
  journaled reason (D-22)
- [ ] Null feature + null strategy (`Intent.none()`)
- [ ] Replay harness: feed recorded raw journal back through identical code
- [ ] Run one full dry-run session, confirm journal + replay

## 3. Structural tests `[BUILD]` — from day one

- [ ] Reflection test in `flow-runtime`: every `OrderContext`-taking method
  on `Study` is overridden by the runtime class (D-14)
- [ ] Replay-equivalence test: record → replay → identical intent sequence
  (D-11)
- [ ] Event-sequence test DSL in `flow-core` (D-20)

## 4. Core features and execution `[BUILD]`

- [ ] Delta (already proven in the `../motivewave` skeleton study; also
  free from the SDK's `VolumeProfile.getTotalDelta()` per D-36)
- [ ] Market structure / swings with bar-history warmup, built on
  `DataSeries.calcSwingPoints` (confirmed real usage pattern, E-10) —
  factor in E-7's observed swing revision behavior before treating a
  swing as final
- [ ] `VolumeProfileView` (flow-core interface) wrapping the SDK's
  `sdk.profile.VolumeProfile` (flow-runtime) per D-36 — settings-parity
  to the chart, not a custom implementation. Same engine, bar-scoped,
  covers footprint (`FootprintView`)
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
- [ ] Partial-bar-at-attach handling: backfill or mark invalid
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
