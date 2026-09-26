# Code review — checkpoint 2026-09-27

A full read of FLOW_V2 at commit `f657b7d` (HEAD, deployed on the dev machine as `c3ef87f`, whose code differs only in
`analysis/status.py`). **Findings only — nothing here has been changed in code.** Per `docs/working-agreements.md`
§3 each item waits for the user's go before it is fixed; the parked user decisions listed in `todo.md` §4 stay
parked and are not re-raised here except where a finding touches them.

## How to read this

**Categories** (as asked):

| Code | Category | Meaning |
|---|---|---|
| **A** | Code | The code does not do what it (or its own comment / the docs) says it does. |
| **B** | Business-logic implementation | The trading rule is sound, but the way it is wired makes live behaviour diverge from the intent. |
| **C** | Fault in the logic itself | The rule or design is itself flawed or incomplete, independent of how it is coded. |
| **D** | Documentation / UI text | Wrong or stale text — in code comments, docs, or the MotiveWave UI. |
| **E** | Something else | Robustness, performance, environment and operations. |
| **F** | Already known and parked | Listed for completeness only; not new. |

**Severity:** **High** = can place, keep or close a position against intent, or silently stop the safety chain
working. **Medium** = wrong trading behaviour, misleading analysis, or a failure a 24/7 run will hit. **Low** = worth
fixing, little practical effect today (1 contract, Sim only).

**Evidence tags:** **[CODE]** read in the source, with the path; **[MEASURED]** checked against real recorded
data; **[UNVERIFIED]** plausible from the code, needs a live observation.

**Laptop Phase 2** (the `lvn_fade_test` Sim session) — rows marked **⚑** will be visible or matter there.

---

## Summary

| ID | Sev | Cat | One line | ⚑ |
|---|---|---|---|---|
| A1 | High | Code | The daily-loss kill switch closes and cancels on the real (Sim) account even in `DRY_RUN` mode when *Armed* is checked | |
| A2 | High | Code | "Exchange time" mixes three clocks and jumps backwards (74 times in 5 min, by up to 2.3 s, **measured**) — every time-based risk rule reads it | ⚑ |
| B1 | High | Biz impl | A blocked exit intent leaves the risk chain believing a position is still open; the phantom P&L can fire the kill switch on a flat account | ⚑ |
| B2 | High | Biz impl | A blocked intent is never retried (identical follow-ups are dropped), and `lvn_fade_test` has no rejection handler at all → phantom strategy positions | ⚑ |
| B6 | High | Biz impl | Bracket legs are `DAY` orders and a leg that expires, is cancelled or is rejected is not noticed — the position can sit unprotected | ⚑ |
| A3 | Med | Code | `SessionBoundary.Tracker` treats a *backwards* session change as a new session — with A2, daily resets can fire repeatedly near 17:00 CT | |
| A4 | Med | Code | The rate limit counts intents that change nothing ("holding", "none") — about 4 slots per trade against a limit of 6 per minute | ⚑ |
| A5 | Med | Code | The nightly report and status line do not flag the newest alert lines (partial-fill flatten, ignored override file, rejected bracket leg) | ⚑ |
| A6 | Med | Code | Re-activating the study clears a kill-switch / mismatch disarm for the same day | |
| B3 | Med | Biz impl | When the runtime skips an intent (order in flight, resting orders, flip, "noop"), neither the strategy nor the risk chain is told | ⚑ |
| B4 | Med | Biz impl | The kill switch does not reset the risk chain's position or the strategy (the session flatten does); it can re-fire | |
| B5 | Med | Biz impl | "Max reversals per session" counts **every** position change: 20 ≈ 10 round trips, then all entries are blocked for the day | ⚑ |
| C1 | Med | Logic | No single source of position truth: risk chain, strategy and order tracker each keep their own belief; real fills reach only the tracker | ⚑ |
| C2 | Med | Logic | The lag guard only blocks entries — a slow machine stops trading without any alert | ⚑ |
| D1 | Med | Docs/UI | MotiveWave shows the study as "walking skeleton, **dry-run only** … armed has no effect" — false, and safety-relevant | ⚑ |
| E1 | Med | Other | The event queue is bounded by count (100 000) but each order-book event carries the whole book — a stalled drain can use gigabytes | |
| E2 | Med | Other | The data store keeps a file open for every (construct, day) forever and prunes files that are still open | |
| E4 | Med | Other | This PC's clock is ~2.27 s behind the feed's exchange timestamps (**measured**) | ⚑ |
| A7 | Low | Code | Risk-chain P&L ignores quantity (only the sign of the position) | |
| A8 | Low | Code | Journal close does not wait for its writer thread; the drain thread is not joined on destroy | |
| A9 | Low | Code | Bracket prices are not snapped to the tick grid (a float cast happens to do it today) | |
| A10 | Low | Code | Partial-fill slices would show up as "orphan fills" in the nightly report, with the wrong quantity | |
| B7 | Low | Biz impl | The daily-loss figure is computed from signal prices, not fills (slippage and fees ignored) | |
| B8 | Low | Biz impl | Stop and target are fixed from the signal price, not the fill price | ⚑ |
| C3 | Low | Logic | The only real-account guard is a per-installation checkbox; ideas for a cheap second layer | |
| C4 | Low | Logic | Daily-loss limit is in ticks, not money, and not per contract | |
| D2 | Low | Docs | Several safety-critical comments and two `configuration.md` rows are stale | |
| D3 | Low | Docs | `findings.md` still says nothing has been built | |
| E3 | Low | Other | Chart redraw runs on the tick callback thread and only when ticks arrive | |
| E5 | Low | Other | Behaviour across a contract roll of the continuous `@GC` chart is unverified | |


## Fix status (updated 2026-09-27)

Fixed off-market the same day, at the user's instruction ("leave whatever needs my decision, complete everything
else, don't break the existing code"). **None of it is deployed or seen live yet** — it is built, unit-tested and
mutation-checked, and the full `build.sh` (every gate, both replay tests) passes. Details: `decisions.md` D-110.

| ID | Status | What changed |
|---|---|---|
| A1 | **Fixed** | Kill switch's account side moved to `LiveOrderTracker.onKillSwitch`; sends orders only in `SIM_LIVE`+Armed, never a close to a flat account. |
| A2 | **Fixed** | `MutableMarketState.riskClockMs()` — one monotonic clock (highest ingest receipt time) for every risk rule; DOM events no longer move `exchangeTimeMs`. Real recording: 0 backward steps (was 74). |
| A3 | **Fixed** | `SessionBoundary.Tracker` only advances to a *later* session. |
| A4 | **Fixed** | Rate limit counts and limits only real position changes. |
| A5 | **Fixed** | Report/status flag the D-99, D-106, rejected-order, `LIVE_LEG_LOST`, `ARM_STILL_DENIED` and `DOM_BACKLOG_SKIPPED` lines. |
| A6 | **Fixed (not unit-testable)** | A safety disarm survives re-activation (`ARM_STILL_DENIED`); only re-adding the study clears it. Compiled; lives in the MotiveWave-bound class. |
| A7 | **Fixed** | Risk-chain P&L multiplies by the signed position. |
| A8 | **Fixed** | Sequencer and journal join their threads on shutdown. |
| A9 | **Fixed** | Bracket prices go through `OrderGateway.roundToTick` (the instrument's own rounding). |
| A10 | **Fixed** | Report merges partial-fill slices of the same entry order. |
| B1 | **Fixed** | A flat intent is never blocked by any filter. |
| B2 | **Fixed** | A repeated blocked intent is re-evaluated at most once a second (silent while still blocked); the strategy is told on every repeat; `lvn_fade_test` rolls a blocked entry back. |
| B3 | **Partly fixed** | Runtime refusals (order in flight, resting orders, flip) now come back as a block. The "account already holds the target" (`noop`) case is left for C1. |
| B4 | **Fixed** | Kill switch books the loss, tells the risk chain and the strategy, latches until the 17:00 CT reset. |
| B5 | **Awaiting the user's decision** | What "max reversals" should mean. Docs now describe what it does. |
| B6 | **Awaiting the user's decision** | `GTC` legs; re-submit vs flatten on a lost leg. (The report already has an ALERT slot for `LIVE_LEG_LOST`.) |
| B7 | Open — part of C1 | |
| B8 | **Awaiting the user's decision** | Offset the bracket from the fill price? |
| C1 | **Awaiting the user's decision** | Account as the single position truth (design). |
| C2 | **Fixed** | Lag blocks aggregate into one WARN in the report. |
| C3 | **Awaiting the user's decision** | Sim cash-balance guard. |
| C4 | Partly by A7 | Still ticks, not money. |
| D1 | **Fixed** | Study label/desc: "FLOW Runtime (Armed + SIM_LIVE places real orders)"; the log-line prefix changes with it. |
| D2 | **Fixed** | Stale comments in `OrderGateway`, `RiskChain`, `FlowRuntimeStudy`, and the `configuration.md` rows. |
| D3 | **Fixed** | `findings.md` points to `decisions.md` / this file. |
| E1 | **Fixed** | At most 64 order-book snapshots queued; extras skipped and counted (`DOM_BACKLOG_SKIPPED`); ticks never skipped. |
| E2 | **Fixed** | Past days' data files closed at the day change; one undeletable file no longer aborts pruning. |
| E3 | Open — needs a live measurement | |
| E4 | **User action** | Check Windows time sync (now matters more: the risk clock is this machine's clock). |
| E5 | Open — needs a contract roll | |

---

## A — Code

### A1 · High · The kill switch acts on the account in `DRY_RUN` mode
**Where:** `flow-runtime/.../FlowRuntimeStudy.java` — the `killSwitch` lambda in `startSession()` (≈ line 441);
compare the `sessionFlatten` lambda right below it (≈ line 463). **[CODE]**

**What:** the risk chain treats the study as armed whenever the *Armed* checkbox is ticked, whatever the Mode.
With Mode `DRY_RUN` + Armed, allowed intents are recorded by `RiskChain.recordAccepted()` as if they were real
positions, so dry-run "trades" accumulate P&L. When that reaches −200 ticks, `Pipeline` calls the kill switch, which
calls `gateway.cancelAllAndClose()` — a real `closeAtMarket()` plus `cancelOrders()` on the account. The session
flatten is gated by `isLiveModeRequested()` (Mode `SIM_LIVE` **and** Armed); the kill switch is not.

**Consequence:** the documented promise "`DRY_RUN` never touches an order" (`configuration.md`, D-92) is broken for
this combination. On the Sim account it would close whatever position exists there — including one placed by hand —
and cancel every order on the account.

**Suggested fix:** gate the kill switch's account actions exactly like the session flatten (journal the breach
and disarm in any mode; only send orders when `SIM_LIVE`). Add a test with Mode `DRY_RUN` + Armed.

### A2 · High · One "exchange time" built from three different clocks ⚑
**Where:** `flow-core/.../MutableMarketState.bump()` updates `exchangeTimeMs` from **every non-clock event**.
Tick events carry the feed's exchange timestamp (`FlowRuntimeStudy.onTick` → `tick.getTime()`); order-book events
carry the local clock (`update(DOM)` → `System.currentTimeMillis()`, documented as unavoidable); bar events carry
the bar's end time. **[CODE] + [MEASURED]**

**Measured** on the committed recording `flow-core/fixtures/recording_gc_20260924_5min/raw.jsonl`: every tick's
exchange time is **2 227–2 277 ms ahead** of its local receipt time (median 2 271 ms); replaying
`MutableMarketState`'s rule over the 1 750 non-clock events gives **74 backward jumps in 5 minutes, up to 2 277 ms**.

**Everything below reads that value:**
- `RiskChain.evaluate()` — the churn dwell (5 s minimum), the rate-limit window, the "no new entries" window and the
  17:00 CT daily-P&L reset;
- `Pipeline`'s kill-switch check (daily-loss reset);
- `LvnFadeTestStrategy`'s 2-minute warm-up;
- `intent_changed.eventTimeMs` in the journal, which the nightly report orders by.

Meanwhile the session **flatten** window is judged on the `ClockEvent`'s local time (`Pipeline.checkSessionFlatten`
uses `e.eventTimeMs()`), so the entry block and the flatten use different clocks ~2.3 s apart.

**Consequence:** a dwell can look up to 2.3 s longer or shorter than it was, the rate window's deque is pruned
assuming monotonic time (it is not), and the edges of the trading window disagree by ~2.3 s. Each effect is small;
together they make every time-based risk rule approximate and non-reproducible between live and replay.

**Suggested fix:** keep the three clocks in separate fields (`exchangeTimeMs` from ticks only; a local clock from
clock/DOM events); run **all** risk timing (dwell, rate, windows, daily reset) on one monotonic local clock; keep the
exchange time for display and analysis. See also E4 (the PC clock itself).

### A3 · Medium · Session tracker counts a backwards step as a new session
**Where:** `flow-core/.../SessionBoundary.Tracker.advance()` returns `true` whenever the session id *differs* from the
last one, including when it goes **down**. **[CODE]**

**What:** with A2, events around 17:00 CT arrive with times on both sides of the boundary (ticks ~2.3 s ahead,
clock/DOM events behind). The id flips new → old → new, and each flip is reported as a rollover: VWAP resets several
times, and `RiskChain` zeroes `realizedPnlTicks`/`reversalsThisSession` more than once. Harmless while the market is
shut 16:00–17:00, but the reopen's first seconds of VWAP are lost, and the logic is wrong in principle.

**Suggested fix:** only advance on a strictly greater session id (ignore older ids); fix A2 first.

### A4 · Medium · The rate limit counts intents that change nothing ⚑
**Where:** `RiskChain.recordAccepted()` appends to `recentChangeTimestamps` on every accepted intent, even when
`targetPosition == lastPosition`; `checkRateLimit()` counts that list. **[CODE]**

**What:** `Pipeline` evaluates any intent whose *content* changed (the reason text counts). One `lvn_fade_test`
round trip produces four such intents — entry, "holding" (same position, new reason), "stop_hit"/"target_hit",
then "none" (same position again) — so each trade uses **4 of the 6 slots per minute**. The rate limit is also
evaluated before the churn check, so it can block the *exit* intent (see B1).

**Consequence:** the limit is effectively ~1.5 trades a minute, not "6 position changes a minute" as
`configuration.md` says, and it feeds the desync in B1.

**Suggested fix:** record a timestamp only when the position actually changes (and consider exempting flat
intents from the rate limit, as the session window already does).

### A5 · Medium · The report and status line miss the newest alert lines ⚑
**Where:** `analysis/daily_report.py` — `ATTENTION` table. **[CODE]**

**Not flagged today:**
- `LIVE_ENTRY_PARTIAL_FLATTENED`, `LIVE_ENTRY_PARTIAL_FILL`, `LIVE_BRACKET_SIZE_FROM_FILL` (D-99);
- `RISK_LOCAL_CONFIG_IGNORED` (D-106 — a malformed override silently means "no pruning");
- `ORDER_REJECTED` and `ORDER_CANCELLED` for a **bracket leg** (the D-86 / B6 case — the one situation where a
  position loses its protection);
- `REFUSE_TO_ARM` is listed; `DENIED` arming states are shown in the arming section only.

**Consequence:** the nightly review's "Needs attention" and `status.py`'s alert count can read zero on a day where
a bracket leg vanished or an override was ignored.

**Suggested fix:** add them with severities (a leg rejected/cancelled that is not our own sibling cleanup = ALERT;
the partial-fill lines = WARN; override ignored = ALERT), with tests.

### A6 · Medium · Re-activating the study undoes a safety disarm
**Where:** `FlowRuntimeStudy.onActivate()` sets `armDenied = false` whenever the account is flat with no orders.
**[CODE]** The field's own comment says it is "set once in onActivate, never cleared for the life of this instance".

**What:** the kill switch (daily-loss) and the double-fill correction disarm by setting `armDenied = true` and
flattening. If the strategy is then deactivated and re-activated in MotiveWave's control box — **[UNVERIFIED]**
whether MotiveWave reuses the instance or creates a new one — the account is flat, so `onActivate` clears the
disarm and the same day continues trading. `killSwitchTripped` in `Pipeline` stays latched, so the kill switch would
not fire again for the same breach.

**Suggested fix:** keep a separate "disarmed by a safety rule this session" flag that only a new trading session
(17:00 CT) or a fresh study clears; journal the reason on re-activation.

### A7 · Low · Risk-chain P&L ignores quantity
**Where:** `RiskChain.recordAccepted()`, `checkDailyLoss()`, `onFlattened()` multiply by `Integer.signum(position)`.
**[CODE]** Correct at 1 contract; with `maxContracts` > 1 the daily loss is under-counted by the position size.
**Fix:** multiply by the signed position, not its sign.

### A8 · Low · Shutdown does not wait for its threads
**Where:** `JournalWriter.flushAndClose()` interrupts the writer thread but does not `join()` it before draining
and closing (unlike `ConstructDataStore`, which joins for 2 s); `FlowRuntimeStudy.destroy()` stops the sequencer
without joining the drain thread and then closes the journal. **[CODE]**
**Consequence:** a few records at the end of a session can be lost or written after close (PrintWriter silently
ignores them). **Fix:** join both threads with a short timeout, in order: clock → sequencer → journal.

### A9 · Low · Bracket prices are not snapped to the tick grid
**Where:** `LiveOrderTracker.onOrderFilled()` sends `(float) codec.fromTicks(ticks)`; `PriceCodec`'s anchor carries
float noise (e.g. 4322.900098). **[CODE]** The float cast happens to round the noise away at gold's price level, which
is why Sim accepted it (D-81). A real exchange rejects off-tick prices. **Fix:** round through
`instrument.round(...)`, as `OrderGateway.describeFill` already does for reporting.

### A10 · Low · The report would mis-read partial fills
**Where:** `daily_report.build_trades()` — the second slice of a part-filled entry arrives as another
`role: entry` record while a trade is open and falls into the "orphan fill" branch; the trade's quantity is the
first slice's. **[CODE]** Unreachable at 1 contract. **Fix:** merge consecutive entry slices of the same order id.

---

## B — Business-logic implementation

### B1 · High · A blocked exit leaves a phantom position in the risk chain ⚑
**Where:** `RiskChain.evaluate()` (only the session-window filter exempts flat intents; *armed*, *readiness*,
*daily_loss*, *rate_limit* and *churn* all block them) + `Pipeline.handle()` (`lastIntent` is updated before the
risk chain runs, so an identical intent is never evaluated again). **[CODE]**

**Scenario (likely with `lvn_fade_test`):** entry at T; the 5-tick stop is hit at T+3 s (normal on gold). The
strategy emits "stop_hit" (flat) → **blocked by churn** (minimum dwell 5 s). Live, nothing is wrong on the account —
the real bracket closes the position. But `RiskChain` still holds `lastPosition = ±1` with the old entry price. The
strategy's next intent ("none", flat) is evaluated once — if that is also inside 5 s it is blocked too — and every
later "none" is identical, so never evaluated again. The risk chain now **marks a position that does not exist
against every price**, until the strategy's next entry, which it then books against the **old** entry price.

**Consequences:**
- the daily-loss **kill switch can fire on a flat account** (it flattens, cancels everything and disarms for the day);
- conversely a real loss can be under-counted;
- the rate limit (A4) and "armed" (when disarmed mid-trade) produce the same state.

**Suggested fix:** never block a flat intent in the risk chain (reducing risk is always allowed — the same reason
the session window already exempts it); and re-evaluate a blocked intent when it is repeated instead of dropping it
(see B2). The durable fix is C1.

### B2 · High · Blocked intents are never retried; `lvn_fade_test` never learns of a block ⚑
**Where:** `Pipeline.handle()` — `lastIntent = intent` happens *before* the risk chain, "so an identical
still-blocked intent doesn't re-evaluate every event"; `LvnFadeTestStrategy` does not override
`onIntentRejected`. `MarketStructureLvnReversalStrategy` does, and rolls its state back. **[CODE]**

**What:**
- `lvn_fade_test` sets `phase = IN_POSITION` the moment it decides to enter. If the entry is blocked (not armed,
  window, readiness, rate, churn, reversals, lag), it still believes it is in a trade and manages a virtual
  stop/target, skipping every other signal meanwhile. **In `DRY_RUN` every entry is blocked by "armed"**, so a
  dry-run journal of this strategy shows far fewer signals than it would really take.
- The rollback in `MarketStructureLvnReversalStrategy` is defeated by the dedup: after a rollback the strategy
  re-emits the *same* intent, which `Pipeline` drops as unchanged, so the strategy moves on believing it acted
  while the risk chain never saw it.

**Suggested fix:** give `lvn_fade_test` the same rollback as the other strategy; in `Pipeline`, remember the last
*accepted* intent separately from the last *seen* one and re-evaluate a repeated intent that was blocked (the
per-event cost is the reason it was avoided — throttle the retry, e.g. once a second).

### B3 · Medium · Intents the runtime skips are invisible to the strategy and the risk chain ⚑
**Where:** `LiveOrderTracker.reconcileLive()` returns `reconcile_live_skipped` / `reconcile_live_noop` for an entry
while an order is in flight, while resting orders exist, for a flip, or when the account already holds the target
size. Nothing is fed back. **[CODE]**

**Scenario:** the strategy's own stop check fires before the real stop order has filled (price touched the stop
level; the fill is a moment behind). The strategy goes flat and immediately takes a new signal in the same
direction. `reconcileLive` sees the account still long 1 = target → "noop". The strategy and the risk chain now
manage a **new** trade with new stop/target; the account holds the **old** trade with the old bracket, which closes
it a moment later. The strategy then holds a phantom position until its virtual stop/target.

**Suggested fix:** treat any skip as a rejection (call `onIntentRejected` and don't `recordAccepted`) — or, better,
C1.

### B4 · Medium · The kill switch leaves stale state behind
**Where:** the kill-switch lambda in `FlowRuntimeStudy.startSession()` disarms, resets the order tracker and sends
`cancelAllAndClose`, but — unlike the session flatten (`Pipeline.checkSessionFlatten`) — does **not** call
`RiskChain.onFlattened()` or `FlowStrategy.onFlattened()`. **[CODE]**

**Consequence:** the risk chain keeps marking the closed position; if price comes back the "breach" clears,
`killSwitchTripped` resets, and a later drop fires the kill switch again — another `closeAtMarket` on what is now a
flat account (behaviour on a flat account is unconfirmed, which is why the session flatten deliberately avoids it).

**Suggested fix:** after the kill switch, call both `onFlattened()` hooks and latch the kill switch until the next
trading session.

### B5 · Medium · "Max reversals" counts every position change ⚑
**Where:** `RiskChain.recordAccepted()` increments `reversalsThisSession` on **every** change of target position
after the first (flat→long, long→flat, …). `configuration.md` describes `maxReversalsPerSession` as "max direction
changes". **[CODE]**

**Consequence:** with the default 20, a strategy gets the first entry free and then 19 more changes — about **10
round trips per trading day** — after which every entry is blocked with "max reversals per session (20) reached"
until 17:00 CT. **In the laptop's Phase 2 this will look like `lvn_fade_test` stopping mid-session.** (It resets at
17:00 CT, or with A3's clock flips.)

**Suggested fix:** decide what the limit means (true reversals long↔short, or trades per day), rename or recount
accordingly, and update `configuration.md`. Until then, raise it in `risk.local.json` for a stress-test session if
more trades are wanted.

### B6 · High · Bracket legs are `DAY` orders and a lost leg goes unnoticed ⚑
**Where:** `OrderAdapter.stopOrder/limitOrder` use `TIF DAY`; `LiveOrderTracker.onOrderCancelled/onOrderRejected`
act only while an **entry** is in flight — a cancelled/rejected/expired **leg** is logged and nothing else.
**[CODE] + [UNVERIFIED]**

**What:** a position's protection is two independent resting orders (there is no OCO). If either leg is rejected
(e.g. a stop already through the market — D-86), cancelled from the MotiveWave UI, or **expires** as a `DAY` order,
the tracker still holds its reference and the position stays open with one or no protective leg, silently. When the
Simulated account expires `DAY` orders (exchange session end? local midnight = 13:30 CT on an IST machine?) is
**unknown** — if it is local midnight, every trade open at 00:00 IST would lose its bracket mid-session.

**Suggested fix:** use `GTC` for bracket legs (the session flatten already bounds their life); on any
cancel/reject of a tracked leg that we did not cancel ourselves, re-submit it or flatten, and journal an ALERT
(A5). Observe once live when a Sim `DAY` order expires.

### B7 · Low · Daily loss is estimated from signal prices
**Where:** `RiskChain` books entry/exit at the tick price when the intent is accepted — its own javadoc notes it
"needs to sync against actual fill prices" (and still says "OrderGateway is dry-run only", see D2). Slippage and
fees are not counted, so the 200-tick limit is approximate. Part of C1.

### B8 · Low · Stop and target come from the signal price, not the fill ⚑
**Where:** `LvnFadeTestStrategy.search()` fixes stop/target at signal price ± 5 ticks; the bracket is placed at those
absolute prices after the market entry fills. One or two ticks of slippage turns a 5:5 bracket into 4:6 or 3:7.
Acceptable for a plumbing test; worth knowing when reading its P&L. **Fix (if it matters):** offset the bracket from
`getAvgFillPrice()`.

---

## C — Faults in the logic itself

### C1 · Medium · There is no single source of truth for the position ⚑
Three components each keep their own belief — `RiskChain` (`lastPosition`, `entryPriceTicks`), each strategy's
`phase`/`positionDirection`, and `LiveOrderTracker` (the only one that asks the account). Real fills reach only the
tracker; `FlowStrategy.onFill()` has **no caller anywhere** (the tracker's own javadoc says so). B1–B4 and B7 are all
symptoms of this. The daily-loss kill switch — a safety control — is computed from the risk chain's belief, not the
account.

**Suggested direction (a design decision for the user):** make the account the truth. On every fill/cancel callback
publish a fill event through the sequencer; `RiskChain` and the strategy update from it (the strategy via `onFill`),
and the daily loss uses fill prices. Intents stay "desired state"; the risk chain gates *changes* to the real
position. This is a bigger change than the individual fixes above, and it would make them mostly unnecessary.

### C2 · Medium · The lag guard stops trading silently ⚑
`RiskChain.checkLag()` blocks entries while the event queue is ≥ 1 000 deep or an event took ≥ 2 s — correct as a
guard, but it produces only an ordinary `risk_verdict` line. On a slow machine (the laptop) the strategy can stop
entering for long stretches with nothing in the status line or the report's "Needs attention". **Suggested:** count
lag blocks as a WARN in the report and status line, and journal queue depth on heartbeats.

### C3 · Low · The real-account guard is one UI checkbox (known, D-107)
The SDK gives no way to read which account is active, so no code guard is possible (D-107). Two cheap second layers
that *are* possible, for the user to consider: (1) refuse to arm unless the `ACTIVATE` cash balance is within a
configured "this is my Sim balance" range (the dev machine's Sim shows `98910.0`; a real account is unlikely to match);
(2) refuse to arm unless `risk.local.json`/`risk.json` names the machine's expected Sim balance explicitly. Neither is
proof; both would catch the common mistake.

### C4 · Low · Loss limit in ticks, not money, not per contract
`dailyLossLimitTicks` is a tick count for the whole position's direction (A7), not a dollar amount — fine as a v1 at
1 contract of one instrument (D-30), misleading the moment size or instrument changes.

---

## D — Documentation and UI text

### D1 · Medium · The study tells the user it cannot trade ⚑
**Where:** `FlowRuntimeStudy`'s `@StudyHeader`: label **"FLOW Runtime (walking skeleton, dry-run only)"** and desc
**"Hosts FlowStrategy plug-ins. No order-submission code exists yet; armed has no effect."** Visible in MotiveWave's
study list and prefixed to every log line (`FLOW Runtime (walking skeleton, dry-run only): FLOW_RUNTIME: ACTIVATE …`).
The class javadoc says the same ("registers just NullStrategy, dry-run only … no order-submission code exists in this
class tree at all"). **All false since D-66/D-82** — the study places real Sim orders when armed in `SIM_LIVE`.
A user (or a Claude on another machine) reading the UI could conclude that ticking *Armed* is harmless.

**Fix:** change the label/desc to state plainly that Armed + `SIM_LIVE` places orders on the selected account.
(Changing the label changes the log-line prefix; nothing parses it.)

### D2 · Low · Stale comments in safety-critical classes, and two config rows
- `OrderGateway`: the block "Real order submission … deliberately NOT called from anywhere … no caller exists yet";
  `reconcileDryRun`'s "nothing routes an automatic intent to them"; `closeAtMarket`'s "no caller … the still-unbuilt
  session-end auto-flatten".
- `RiskChain` class javadoc: "OrderGateway is dry-run only (D-14)"; "flatten-at-session-end … isn't itself built yet".
- `FlowRuntimeStudy`: `armDenied` "never cleared" (see A6); the "D-61 … not gitignored" comment sits above
  `DATA_ROOT` instead of `RISK_CONFIG_PATH`.
- `docs/configuration.md`: `rateLimitPerMinute` "max position *changes*" (A4) and `maxReversalsPerSession` "max
  direction changes" (B5) describe what the code does not do.

### D3 · Low · `findings.md` is a placeholder
`docs/dynamic/findings.md` still says "Nothing has been built or tested in FLOW_V2 itself yet". All evidence lives in
`decisions.md`. Either retire the file or point it at `decisions.md`.

---

## E — Something else (robustness, performance, environment)

### E1 · Medium · Event queue bounded by count, not memory
**Where:** `Sequencer` — `LinkedBlockingQueue<>(100_000)`; each `DomEvent` holds the full book as two lists
(~600–700 rows per side on `@GC`, D-35). **[CODE] + [UNVERIFIED]** If the drain thread stalls (a slow redraw, a GC
pause, a slow disk under the journal), tens of thousands of full-book events queue up before `publish()` blocks —
potentially gigabytes — and when it does block, it blocks MotiveWave's own tick/DOM callback threads.
**Suggested:** coalesce DOM events (keep only the latest book while one is still queued), or bound the queue by a
much smaller DOM count.

### E2 · Medium · The data store never closes past days' files
**Where:** `ConstructDataStore.writerFor()` keeps a `PrintWriter` per (construct, session id) in `open` until
shutdown; `pruneNow()` deletes old days' files — which may still be open — and one failing delete throws out of the
loop and aborts the rest of that prune. **[CODE]** On a 24/7 run: file handles grow by ~6 a day; on Windows a delete
of an open file may fail (aborting pruning); on Linux the space is not freed until the handle closes.
**Suggested:** close a session's writers when the session changes; make prune skip-and-continue per file.

### E3 · Low · Chart drawing on the tick thread
`maybeRedraw()` runs from `onTick` (at most once a second) and redraws every enabled construct synchronously on
MotiveWave's tick callback thread — including one box per book row for the heatmap. It also means nothing redraws
while no trades print, so the heatmap goes stale in quiet periods. Measure before changing.

### E4 · Medium · This PC's clock is ~2.27 s behind the feed ⚑
**[MEASURED]** Every tick in the committed recording has an exchange timestamp 2 227–2 277 ms later than the local
receipt time — a constant offset, so it is clock skew (or a fixed feed offset), not latency (which would make
receipt *later*). Beyond A2, it shifts the 1-second recorded candles (labelled by the local clock) against the fill
times the trade viewer lines them up with. **Check Windows time sync on both machines** (runbook §13.1 row B8
already asks for it; this is the evidence it matters).

### E5 · Low · Contract roll unverified
The chart is the continuous `@GC`; position, orders and `getPosition()` are those of whatever contract the chart
currently maps to (`GCZ6` today). What happens to an open position or resting leg across a roll, and whether
`getPosition()` then reads 0, is untested. The weekend flatten makes it unlikely to matter.

---

## F — Already known and parked (not re-flagged)

- D-86: both bracket legs rejected — no software fallback (B6 widens this to *any* lost leg).
- `plumbingEdgeCases.md` §7 (close-then-cancel ordering), §10 (concurrent fill callbacks for two legs),
  §11 (`onActivate` sees a live position immediately after a restart).
- D-99: a partial fill that never completes and never cancels — no timeout.
- D-93: no feed-silence watchdog, holidays not modelled (user decision).
- No alert channel (`todo.md` §4).
- D-107: no code-level Sim-account check is possible (C3 offers partial mitigations).

---

## What looked right (checked, no finding)

- Every `OrderContext`-taking hook is overridden and the reflection test enforces it; no hook places an order.
- The one-shot test-trade path is gone; activation places nothing.
- `refuseToArmReason()` blocks arming with an existing position or resting orders.
- The session-end/weekend flatten: `TradingWindow` is correct for Mon–Fri halts and the weekend (DST handled by the
  zone), retries every 5 s, never sends a close to a flat account, never disarms, and is gated to `SIM_LIVE`.
- `LiveOrderTracker`: the in-flight guard, sibling-leg cancellation, the self-cancel exemption, the double-fill check
  and the D-99 partial-fill rule do what their tests say.
- Journal backpressure: decisions overflow disarms; raw overflow gap-marks.
- The data recorder never disarms trading on failure; big-trade re-emission and tick-grid snapping are sound.
- Log retention defaults to keep-everything and fails safe.

## Scope and method

Read in full: `RiskChain`, `Pipeline`, `TradingWindow`, `Sequencer`, `JournalWriter`, `MutableMarketState`,
`FlowStrategy`, `Intent`, `ExternalConfig`, `LogRetention`, `RollingFileWriter`, `FeatureLogs`, `DataRecorder`,
`ConstructDataStore` (write and prune paths), `VWAPFeature`, `BigTradeFeature`, `VolumeProfileMath`,
`LvnFadeTestStrategy`, `LiveOrderTracker`, `OrderGateway`, `OrderAdapter`, `PriceCodec`, `FlowRuntimeStudy` (all
but the drawing methods), `status.py`, and `daily_report.py`'s trade/alert logic. Skimmed: `MarketStructureFeature`,
`MarketStructureLvnReversalStrategy` (callbacks only), `LiquidityMapFeature`, `SdkVolumeProfileFeature` (reviewed in
D-104), `TriggerEvaluator`. **Not reviewed:** the drawing code, `TickAdapter`, `MarkerAdapter`,
`SdkFootprintFeature`, `trade_view.py`, `journal_summary.py`, the backtest package, and the test suites themselves
(their assertions were not audited). One measurement was made (A2/E4, on the committed recording); nothing else was
run. No code was changed.
