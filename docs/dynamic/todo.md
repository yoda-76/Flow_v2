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

**Q-02(a) is done and closed — see D-31.** `ContextRetentionProbe`
activated cleanly and ran; its log shows a stable
`System.identityHashCode` across the platform's own threads and this
probe's background poller thread, `pos=0`/`cash` flat throughout. No
Q-02(b) needed to unblock D-17 (it has a safe default already); stage (b)
is now optional, only relevant if inline-flush latency ever matters
enough to chase.

**Getting there took two wrong turns worth knowing about before touching
any future diagnostic `Strategy` class** — full trail in
`../motivewave/docs/dynamic/findings.md`, 2026-09-14 `[LIVE]` entries.
Short version: `autoEntry=false` + `manualEntry=false` together produce a
dead-end "Please Choose Long or Short" dialog on Activate with no actual
chooser in it — looks like a Position Type problem (it isn't) and doesn't
respond to `supportsPositionType`. **Standing config for any diagnostic
strategy with no real entry logic: `autoEntry=true, manualEntry=false,
supportsPositionType=false` (default), plus
`supportsEnterOnActivate=false, supportsCloseOnDeactivate=false`
explicit.** `autoEntry=true` only *permits* the class's own code to
auto-enter — with no `buy`/`sell` call anywhere in the class, it has
nothing to act on; confirmed by two full live sessions with position/cash
flat throughout.

Q-01 and Q-08 are also done now — see D-33 and D-32.

**Still pending, unblocked, not yet run:**
- `TickDomLogger` (Q-03) — already running (started before this handoff
  was written; baseline byte counts noted), let it reach a full hour then
  measure `logs/ticks.log` + `logs/dom.log` raw vs. gzipped size.

Q-08 is done — see D-32.

Both repos' changes from this session are committed locally (not pushed).

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
- [x] (2026-09-14) **Q-08** `[EXP]` Is the built-in volume profile readable
  from another study? **Done → D-32: no.** Confirmed live — an EMA added
  alongside the built-in Volume Profile study shows no Volume-Profile-
  derived option in its Input dropdown, and right-clicking the Volume
  Profile plot gives a plot-specific menu with no Create/Add Alert entry.
  `BuiltInVolumeProfile` is not automatic; D-26's journal-and-compare-by-
  hand fallback is the plan.

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
