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

## 0. Session handoff (2026-09-14) — read this first

**Do not activate any strategy currently deployed in
`../motivewave/experiments/` until a fresh redeploy has happened.** The
version of `ContextRetentionProbe.java` deployed to
`%USERPROFILE%\MotiveWave Extensions\dev` right now is the **unfixed**
one — it's missing `supportsEnterOnActivate=false` /
`supportsCloseOnDeactivate=false`, which is why activating it prompted for
a Long/Short direction choice (these two StudyHeader flags default to
`true` and place/close a position as a platform-level side effect of
Activate/Deactivate, independent of the class's own code — see
`../motivewave/docs/dynamic/findings.md`, 2026-09-14 `[LIVE]` entry). The
source is fixed on disk (both `ContextRetentionProbe.java` and
`FlowStrategySkeleton.java`), compiles clean, but **has not been
redeployed** — redeployment needs your explicit go-ahead each time per
`../motivewave/CLAUDE.md`'s hard rule, and wasn't done because you were
stepping away without being able to supervise/confirm it live.

**Next session, in order:**

1. Re-run `../motivewave/experiments/redeploy.sh` (needs your OK again,
   writes outside the project tree) to push the fixed classes.
2. Restart MotiveWave or remove/re-add the study so it picks up the fixed
   class.
3. Re-confirm out loud which account is selected and that Sim Trade Only
   is on (a past confirmation doesn't carry forward) before activating.
4. Activate `ContextRetentionProbe` — it should no longer prompt for
   Long/Short. Let it run a few minutes (polls every 5s), then it can be
   deactivated.
5. Also still pending, unblocked by the above: `OrderingProbe` (Q-01) on a
   live `@GC` chart through several bar closes, `TickDomLogger` (Q-03) for
   a full hour, and the Q-08 GUI check (Volume Profile already added per
   this session — next step: add a second study, e.g. a Simple Moving
   Average, and check its Input dropdown / right-click "Create Alert" on
   the Volume Profile plot for an addressable POC/VAH/VAL value).
6. Once logs exist, read them back and write the Q-01/Q-02(a)/Q-03/Q-08
   findings + decisions in this repo.

Nothing has been committed in `../motivewave` yet this session (new/fixed
Java files, the findings.md entries) — do that alongside FLOW_V2 if asked.

## 1. Resolve open questions

Platform questions — experiments in `../motivewave`, then a decision here:

- [~] (2026-09-14) **Q-01** `[EXP]` Bar/tick ordering — do a bar's ticks
  reliably arrive before `onBarClose` fires for it? Log sequence of
  `onTick` vs bar-close callbacks around many bar boundaries on live `@GC`.
  `OrderingProbe.java` written, compiled, deployed to
  `../motivewave/experiments/`. Waiting on: attach to a live `@GC` chart
  through several bar closes, then read `logs/ordering_probe.log`.
- [~] (2026-09-14) **Q-02 (a)** `[EXP]` `OrderContext` retention — retain
  from `onActivate`, call **read-only** methods from a later callback and
  from a timer thread, log results + `System.identityHashCode(ctx)`.
  Zero-risk. Feeds D-17's flush point. `ContextRetentionProbe.java`
  written, compiled — **found live that the first deploy was unsafe**
  (`supportsEnterOnActivate`/`supportsCloseOnDeactivate` default `true`,
  prompted for Long/Short before activation; see session handoff above
  and `../motivewave/docs/dynamic/findings.md` 2026-09-14 `[LIVE]`), fixed
  on disk, **not yet redeployed**. Waiting on: redeploy (needs fresh OK),
  reconfirm account/Sim Trade Only out loud, activate (should no longer
  prompt for direction), run a few minutes, read
  `logs/context_retention_probe.log`.
- [ ] **Q-02 (b)** `[EXP]` Only if (a) is inconclusive. Far-from-market
  limit order from a retained reference, confirm, cancel — Sim Trade Only,
  its own session, **explicit in-the-moment confirmation per `CLAUDE.md`**.
- [~] (2026-09-14) **Q-03** `[EXP]` Raw data volume — capture one hour of
  live `@GC`: trade count, DOM update count, `DOMOrder` entries per
  update, bytes raw and gzipped. Closes D-07's retention figure and
  decides binary vs compressed JSONL for the raw journal tier. No new code
  needed — existing `TickDomLogger.java` already logs everything required.
  Waiting on: run it for a full hour on live `@GC`, then measure
  `logs/ticks.log` + `logs/dom.log` raw and gzipped size.
- [ ] **Q-07** `[EXP]` Sim fill fidelity — how does the Simulated account
  fill (last, bid/ask, queue-aware)? Order placement is involved, so same
  confirmation rule as Q-02 (b).
- [~] (2026-09-14) **Q-08** `[EXP]` Is the built-in volume profile readable
  from another study (handle to study instance, POC/VAH/VAL in an
  addressable `DataSeries`)? Decides whether `BuiltInVolumeProfile` is
  automatic or a by-hand chart comparison (D-26). Narrowed via SDK docs
  (`../motivewave/docs/dynamic/findings.md`, 2026-09-14): no API for a
  study to get another study's handle; the mechanism is Export Values,
  GUI-wired per connection. Whether the *built-in* study exports POC/VAH/
  VAL isn't documented. Waiting on: no code needed — place the built-in
  Volume Profile study on a chart, then check the Add-Study dialog's
  "Study" input options for a new study.

System questions — decided here:

- [x] (2026-09-14) **Q-04** `[DECIDE]` Instruments and timeframes — `@GC`
  first, 1-minute bars by default, strategy may declare required bar
  interval(s) → D-28
- [~] (2026-09-14) **Q-05** `[DECIDE]` Session model — 24h (not RTH-only),
  flatten at session end by default, decided → D-29. Sub-session boundaries
  within the 24h day still open, split off as **Q-09** below (user to
  specify)
- [x] (2026-09-14) **Q-06** `[DECIDE]` Sizing and risk defaults — fixed
  contracts to start, daily-loss kill switch on realized + open PnL, exact
  value left as a config value rather than a decision → D-30
- [ ] **Q-09** `[DECIDE]` Session separations within the 24h day — where
  sub-session boundaries fall (e.g. Asia/London/NY), whether per-session
  counters (reversal cap, price anchor, journal file boundary) reset per
  sub-session or once daily. User to specify.

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

- [x] (2026-09-14) `CLAUDE.md` and `findings.md` still say "seven open
  questions" — there are eight (Q-01…Q-08)
- [x] (2026-09-14) `CLAUDE.md` directory structure still showed `app/`;
  now matches README's `flow-core/` + `flow-runtime/` + `build/` layout
- [x] (2026-09-14) `README.md` / `decisions.md` "Open questions" intro
  said system questions get experiments here — now states platform
  questions (Q-01/02/03/07/08) get a `../motivewave` experiment and system
  questions (Q-04/05/06) are decided directly, no experiment needed
