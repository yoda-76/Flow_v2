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

## 1. Resolve open questions

Platform questions — experiments in `../motivewave`, then a decision here:

- [ ] **Q-01** `[EXP]` Bar/tick ordering — do a bar's ticks reliably arrive
  before `onBarClose` fires for it? Log sequence of `onTick` vs bar-close
  callbacks around many bar boundaries on live `@GC`.
- [ ] **Q-02 (a)** `[EXP]` `OrderContext` retention — retain from
  `onActivate`, call **read-only** methods from a later callback and from a
  timer thread, log results + `System.identityHashCode(ctx)`. Zero-risk.
  Feeds D-17's flush point.
- [ ] **Q-02 (b)** `[EXP]` Only if (a) is inconclusive. Far-from-market
  limit order from a retained reference, confirm, cancel — Sim Trade Only,
  its own session, **explicit in-the-moment confirmation per `CLAUDE.md`**.
- [ ] **Q-03** `[EXP]` Raw data volume — capture one hour of live `@GC`:
  trade count, DOM update count, `DOMOrder` entries per update, bytes raw
  and gzipped. Closes D-07's retention figure and decides binary vs
  compressed JSONL for the raw journal tier.
- [ ] **Q-07** `[EXP]` Sim fill fidelity — how does the Simulated account
  fill (last, bid/ask, queue-aware)? Order placement is involved, so same
  confirmation rule as Q-02 (b).
- [ ] **Q-08** `[EXP]` Is the built-in volume profile readable from another
  study (handle to study instance, POC/VAH/VAL in an addressable
  `DataSeries`)? Decides whether `BuiltInVolumeProfile` is automatic or a
  by-hand chart comparison (D-26).

System questions — decided here:

- [ ] **Q-04** `[DECIDE]` Instruments and timeframes — first contract(s),
  bar interval, can a strategy declare its required timeframe?
- [ ] **Q-05** `[DECIDE]` Session model — RTH vs 24h, flatten at session
  end by default, session-file boundary, session price anchor (D-21).
- [ ] **Q-06** `[DECIDE]` Sizing and risk defaults — fixed contracts vs
  risk-per-trade off stop distance; daily-loss kill switch value; realized
  vs realized + open.

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

- [ ] Delta (already proven in the `../motivewave` skeleton study)
- [ ] Market structure / swings with bar-history warmup
- [ ] `VolumeProfileProvider` + `CustomVolumeProfile` with pluggable
  value-area algorithm; `BuiltInVolumeProfile` per Q-08 outcome (D-26)
- [ ] Session / prior-session levels, ATR, overnight high/low, VWAP
  (method journaled)
- [ ] Liquidity map and book imbalance (derived DOM views only — raw DOM
  never crosses `MarketState`)
- [ ] Footprint, big trades (fixed vs relative threshold declared, D-22)
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

- [ ] `CLAUDE.md` and `findings.md` still say "seven open questions" —
  there are eight (Q-01…Q-08)
- [ ] `CLAUDE.md` directory structure still shows `app/`; README layout is
  `flow-core/` + `flow-runtime/` + `build/`
- [ ] `README.md` / `decisions.md` "Open questions" intro still says system
  questions get experiments here — align with experiments-in-motivewave-only
