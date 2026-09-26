# Plumbing robustness — distilled edge cases for review

Source: **not** an external spec — the actual current code (`Pipeline.java`,
`RiskChain.java`, `Sequencer.java`, `JournalWriter.java`, `SessionBoundary.java`,
`OrderGateway.java`, `FlowRuntimeStudy.java`), read specifically to find real
edge cases before any strategy-optimization work starts (the user's own
framing: "i want the system to be robust before i start to optimise
strategy"). Same convention as `marketStructureRules.md`/
`orderFlowExecutionRules.md`: **⚠️ AMBIGUOUS** marks a genuine gap, race, or
undecided behavior this pass actually found in the code — phrased as "here
is what happens today, confirm whether that's acceptable or decide what
should happen instead" — never a guessed fix applied unilaterally. A few
points below are the code's own existing self-flagged caveats, cited as
such rather than re-discovered; most are new, found by tracing specific
event sequences (double fills, disarm-while-breached, restart timing)
through the real call paths rather than reading the prose docs.

Each point ends with a **testable via** tag: `flow-core unit test` (SDK-free,
can be written now), `needs fake OrderContext` (flow-runtime, no such
harness exists yet — building one is its own scope decision, see §13),
`Sim-live checklist` (only observable by actually running a Sim session),
or `cross-repo platform question` (belongs in `../motivewave/experiments`,
noted here only because it directly gates a FLOW_V2 safety mechanism).

---

## 1. `ReplayEquivalenceTest` is not wired into `build/build.sh`

**Closed 2026-09-22** — wired into `build.sh` against a new committed
fixture, `flow-core/fixtures/replay_fixture_null_strategy/` (13 synthetic
events run once through a real `Pipeline`+`JournalWriter`+`NullStrategy`,
not hand-written JSON). Turned out to be less trivial than "one missing
line": `ReplayEquivalenceTest` takes a session directory as an argument,
and — checked while building the fixture — **every currently-registered
strategy is degenerate for this purpose**. `NullStrategy`/
`LevelZoneObserverStrategy` never change their intent by design;
`market_structure_lvn_reversal` and `lvn_fade_test` both require
`VolumeProfileView`, which only has an SDK-backed implementation and so
can never be reconstructed under replay's plain-JDK harness (D-43). The
committed fixture is therefore an honest 0-vs-0 comparison — it proves
the record→replay→compare machinery runs end to end (encode/decode
round-trips, dual-tier `JournalWriter` output, the harness doesn't throw)
but not real intent-change equivalence, exactly as `ReplayEquivalenceTest`
already prints at every run. See the fixture's own `README.md` for the
full explanation. Not an ambiguity — a build-script bug. `build.sh` runs eight gates
(`TriggerEvaluatorTest`, `MarketStructureFeatureTest`, `SessionBoundaryTest`,
`SessionResetWiringTest`, `LiquidityMapFeatureTest`, `LogRetentionTest`,
`MarketStructureBacktestTest`, `SafetyHookReflectionTest`); `Replay
EquivalenceTest.java` exists in `flow-core/src/com/flow/core/` and is
referenced repeatedly in `decisions.md`/`todo.md` as one of the two
structural tests that "run from the start," but no line in `build.sh`
ever invokes it. Every rebuild since has been passing "all gates" without
this one ever running.

**Testable via**: `flow-core unit test` — just add the missing line to
`build.sh`, no review needed first.

---

## 2. The daily-loss kill switch goes silent the instant `Pipeline` disarms for *any* reason

**Resolved 2026-09-22 — fixed.** User's decision: "yes, move it ahead of
the health check" — the original D-85 "no matter what" framing was meant
literally. `Pipeline.handle()`'s kill-switch block now runs *before* the
`if (!healthy.get()) return;` line, so a disarm for an unrelated reason
(a feature exception, a decisions-queue overflow) can no longer silence
it. `PipelineExceptionBoundaryTest.testKillSwitch_StillFiresAfterPipeline
DisarmsForAnUnrelatedException()` rewritten to assert the fix (it
previously locked in the opposite, now-wrong behavior as a found-but-
undecided baseline).

`Pipeline.handle()`'s order of operations (`Pipeline.java` lines 132–177):

1. bump state, write the raw record
2. on a `ClockEvent`, maybe heartbeat / maybe DOM-snapshot
3. if `journal.decisionsOverflowed()`, flip `healthy` false and journal `DISARM`
4. **`if (!healthy.get()) return;`**
5. *(only reached if healthy)* the daily-loss kill-switch check
   (`riskChain.dailyLossBreached(...)`, calling `killSwitch.accept(...)` on a
   breach)

The kill switch's own comment (`Pipeline.java` line 85–92, and `RiskChain
.dailyLossBreached()`'s javadoc) frames it as checked "on every event,
independent of the strategy's own triggers or intents" — but it is *not*
independent of `Pipeline`'s own health flag. The moment `healthy` flips
false — from a feature/strategy exception (`onPipelineException`,
line 336) **or** a decisions-queue overflow (line 146) — every subsequent
event returns at line 154 before ever reaching the kill-switch check.
A position that breaches the daily-loss limit *after* an unrelated
feature throws once is now protected by nothing at all: not the kill
switch (never reached again), and not the strategy (already disarmed).

⚠️ **AMBIGUOUS — should the kill switch stay live after `Pipeline`
disarms for an unrelated reason?** Today it silently does not. Confirm
whether "no matter what, close everything the instant the daily loss
limit is hit" (the user's own D-85 framing) was meant to survive a
`Pipeline`-health disarm too — if so, the kill-switch check needs to move
ahead of the `healthy` early-return (it doesn't touch strategy/feature
state, so nothing about the disarm reasoning actually requires gating it
behind `healthy`).

**Testable via**: `flow-core unit test` — feed a sequence that (a) throws
from a stub feature to trip `onPipelineException`, then (b) breaches the
daily-loss limit, and assert on whether `killSwitch` fires. Whatever the
answer to the ambiguity above is, this exact sequence should become a
permanent regression case either way.

**Test written 2026-09-22, locking in current behavior** —
`PipelineExceptionBoundaryTest.testKillSwitch_GoesSilentAfterPipeline
DisarmsForAnUnrelatedException()`, wired into `build.sh`, with a positive
control (`testKillSwitch_FiresNormally_PositiveControl()`) proving the
exact same breach *does* trip the kill switch when nothing has disarmed
Pipeline first — so the "silent" result in the disarmed case is a real
finding, not a broken harness. The ambiguity itself (should this change)
is untouched by writing the test; nothing here has been fixed.

---

## 3. Journal backpressure paths are fully unexercised

**Closed 2026-09-22** — `JournalBackpressureTest.java`, wired into
`build.sh`. Confirms the exact 10,000/50,000 capacity boundaries, a
single contiguous `GAP_MARKER` for one drop episode, and — the harder
case — that two separate drop episodes with a successful write in
between produce two *distinct* `GAP_MARKER` records rather than one
merged range (this one needs the real writer thread; see the test's own
comment for why the margins used are safe, not tuned to a bare minimum).

`JournalWriter`'s two policies (raw: drop + `GAP_MARKER`; decisions: fail
loud via `decisionsOverflowed()`) are exactly as specified in README, but
nothing forces either queue (`rawQueue` capacity 50,000, `decisionsQueue`
capacity 10,000) to actually fill. `LogRetentionTest` covers rotation/
deletion, a different concern entirely.

One real subtlety worth locking into a test rather than assuming: a
decisions-queue overflow is detected **up to one event late** for the
trigger-driven decision writes (`intentChangeLine`/`RiskChain.resultLine`/
`traceLine`) — those all happen *after* `Pipeline.java` line 154's health
check on the same event, so an overflow caused by one of them isn't
caught until `journal.decisionsOverflowed()` is polled on the *next*
event (line 146). An overflow from the heartbeat/DOM-snapshot writes
(lines 142–143), which run *before* the check, is caught same-event. Not
a bug — the runtime still disarms promptly either way — but worth an
explicit test rather than an assumption, since "how late" matters for
how many decision records could theoretically be lost between the
trigger and the disarm.

Also worth a dedicated test: two separate raw-tier drops with a
successful write in between should produce **two** distinct
`GAP_MARKER` records, not one merged range — `rawDropped()`/
`flushPendingGapIfAny()` (`JournalWriter.java` lines 69–91) appear to
already do this correctly (`pendingGapStart` reset to `null` on flush),
but it's never been exercised.

**Testable via**: `flow-core unit test` for both (fill the queues via a
slow/blocked consumer double, or a tiny test-only queue capacity).

---

## 4. `RiskChain` has zero dedicated tests

**Tests written 2026-09-22** — `RiskChainTest.java`, wired into
`build.sh`: one case per filter, the two churn sub-checks, the two lag
sub-checks, the short-circuit-ordering behavior, `dailyLossBreached()`
against `checkDailyLoss()`, and the rollover-interleaving trace below —
39 checks total, all passing against the current code. These lock in
*current* behavior as a regression baseline; the two ⚠️ points below are
about whether that behavior is the *right* one long-term, which the
tests don't answer and aren't trying to. One real, if minor, side-finding
while writing the churn test: `checkChurn`'s reversal-cap check
(`reversalsThisSession >= maxReversals`) doesn't special-case "this is the
very first entry" the way `recordAccepted()`'s own counting does — a
`maxReversalsPerSession` of `0` blocks even the first-ever entry, not just
a later reversal. Noted in the test's own comment, not treated as a bug.

Already flagged directly to the user; restated here as its own numbered
item since it's the largest single gap. Beyond "write one test per
filter," two things worth deciding *before* writing tests so the tests
assert the right thing rather than just the current behavior verbatim:

**Resolved 2026-09-22 — keep as-is.** User's decision: short-circuit
stays; not changing to evaluate-and-journal-all-blockers. No code change.

⚠️ ~~AMBIGUOUS — filter-order short-circuiting means only the first
blocking filter's verdict is ever journaled for a given intent.~~
`evaluate()` (`RiskChain.java` lines 96–133) returns at the first `block`,
per the class's own documented design ("later filters are moot once one
blocks, so there's nothing meaningful to journal for them"). This is a
real, deliberate design choice, not an oversight — but confirm it's still
wanted now that the daily-loss check and the size cap sit *ahead of*
churn/rate/lag in that fixed order: an intent that would fail size-cap
**and** would also have failed churn never reveals the second fact
anywhere, which could matter when debugging why a strategy's entries
keep getting suppressed for what looks like the wrong reason.

⚠️ **AMBIGUOUS — session-rollover-at-the-boundary ordering between the
two cadences.** `dailyLossBreached()` (every event) and `evaluate()`
(intent-change only) share one `SessionBoundary.Tracker` instance and
both call `maybeRolloverSession()`. Traced through by hand: on the exact
event that crosses 17:00 CT, `dailyLossBreached()` always runs first
(`Pipeline.java`'s ordering) and consumes the transition (`Tracker
.advance()` is one-shot-true), so `evaluate()`'s own rollover check on
the same event is always a no-op by the time it runs. This appears to be
correct and safe as traced, not a bug — but it's non-obvious enough
(two independent call sites sharing one stateful tracker, correctness
depending on call order that lives in a third file) that it deserves a
permanent test locking in this exact interleaving, not just a written-down
trace.

**Testable via**: `flow-core unit test` for all of the above — none of
this needs the SDK.

---

## 5. `Sequencer`'s producer-side backpressure has no test either

**Closed 2026-09-22** — `SequencerTest.java`, wired into `build.sh`.
Confirms `queueDepth()` genuinely grows under a slow handler (not just in
theory) and composes that real, growing depth with `RiskChain.checkLag()`
directly, confirming a realistic threshold trips well under the 100,000
real ceiling.

`Sequencer.publish()` (`Sequencer.java` line 50) blocks the calling
(producer) thread on a full queue rather than dropping — deliberate, per
the class javadoc, because dropping a market event would silently corrupt
every feature's incremental state. At 100,000 capacity this is a
purely theoretical concern today, but it means a sufficiently slow drain
thread would eventually **block MotiveWave's own tick/bar/DOM callback
threads**, not just fall behind — worth at least one test confirming the
lag guard (`RiskChain.checkLag()`, gated on `queueDepth`) actually trips
and disarms *before* the queue gets anywhere near this ceiling, since
that guard is the only thing standing between "falling behind" and
"blocking the platform's own callback thread."

**Testable via**: `flow-core unit test` (a slow/blocked `Pipeline.handle`
stand-in feeding `Sequencer` directly, asserting `queueDepth()` growth
trips `checkLag` well under 100,000).

---

## 6. `Pipeline`'s exception boundary is built but untested

**Closed 2026-09-22** — `PipelineExceptionBoundaryTest.java`, wired into
`build.sh`, using a real `Sequencer` (not a bare `try/catch` around a
direct `Pipeline.handle()` call) so a regression in `Sequencer`'s own
wrapping would be caught too, not just `Pipeline.onPipelineException()`
in isolation. All four implied guarantees confirmed: the drain thread
survives, raw ingestion resumes for later events, the strategy is never
invoked again, and the `DISARM` record carries the throwable's message.

**One new, smaller finding surfaced while writing this test, not
previously flagged**: raw ingestion resuming afterward does **not**
include the triggering event itself. `Pipeline.handle()` calls
`journal.writeRaw()` *after* the feature loop, so the one event whose own
feature call throws is never raw-journaled at all, even though
`marketState.bump()` already ran for it moments earlier in the same call.
Not a correctness bug on its own (nothing acts on that event's absence),
but worth knowing: a post-incident raw-journal review will have a
one-event hole exactly at the moment something went wrong, which is
precisely the event an investigator would most want present.

`Sequencer.drainLoop()` wraps `handler.accept(e)` in try/catch and calls
`Pipeline.onPipelineException` on any throwable (`Sequencer.java` lines
93–104); `Pipeline` sets `healthy=false` and journals a `DISARM` line
(`Pipeline.java` lines 335–344). No test throws from a stub feature or
strategy and asserts: (a) the drain thread survives, (b) raw ingestion
continues past the exception, (c) the strategy is never invoked again
this session, (d) the `DISARM` line's `reason` field actually contains
the throwable. All four are implied by the code and the README, none are
locked in.

**Testable via**: `flow-core unit test`.

---

## 7. `cancelAllAndClose()`'s two-step ordering has no confirmed atomicity

**Mostly resolved 2026-09-22, via `../motivewave` Javadoc research (not
yet live-confirmed).** `OrderContext.closeAtMarket()`'s own Javadoc states
explicitly: *"This method will wait until the market order(s) have been
filled."* — a documented, synchronous/blocking guarantee. `cancelOrders()`
has no such note. So `cancelAllAndClose()`'s `closeAtMarket()` →
`cancelOrders()` sequence cannot race: the close order is already filled
and gone by the time `cancelOrders()` (which only touches still-open
orders) ever runs. Full detail in `../motivewave/docs/dynamic/findings.md`
(2026-09-22). **[DOC]-tier only** — per this project's own "verify
against the real thing before trusting docs" discipline, this is a strong
lead, not a live-verified fact, for a safety-critical mechanism that has
never actually fired live yet (D-85). Worth a live confirmation whenever
D-85 gets its first real trigger, not before.

`OrderGateway.cancelAllAndClose()` (`OrderGateway.java` lines 228–238)
calls `ctx.closeAtMarket()` then, immediately after with no wait or
confirmation, `ctx.cancelOrders()` (blanket, no-arg). If `closeAtMarket()`
itself works by submitting a new market order that briefly appears in
`getActiveOrders()` before it's acknowledged/filled, the very next line's
blanket `cancelOrders()` could race — and possibly cancel — the close
order the kill switch itself just placed. Whether `closeAtMarket()` is
synchronous from the SDK's perspective (blocks until the order is at
least submitted/acknowledged, not just queued) isn't confirmed anywhere
in this codebase or `../motivewave`'s findings.

⚠️ **AMBIGUOUS / cross-repo platform question** — does `OrderContext
.closeAtMarket()` return only after its own order is safely past the
window where a subsequent `cancelOrders()` call could touch it? This is
exactly the kind of platform-timing fact `../motivewave/experiments`
exists to pin down (same category as D-67's `onOrderFilled` surprise),
not something to assume either way given the kill switch is the one
sanctioned blanket-sweep path and has never actually fired in a real
session yet.

**Testable via**: `cross-repo platform question` first (a throwaway
`../motivewave` experiment placing then immediately blanket-cancelling);
`Sim-live checklist` second, once D-85 gets its first real trigger.

---

## 8. A genuine double-fill of both bracket legs is currently undetectable

**Fixed 2026-09-23 — see `decisions.md` D-87.** `onOrderFilled()` now
checks `gw.currentPosition()` after handling the sibling leg either way;
anything other than flat triggers `armDenied` + `cancelAllAndClose()`.
Brief note carried into D-87 rather than repeated here: this assumes a
bracket always closes the whole position — revisit if that ever changes.

The single most important finding in this pass. `FlowRuntimeStudy
.onOrderFilled()`'s sibling-cancellation branch (lines 1427–1448):

```java
Order sibling = wasStop ? target : stop;
restingStopOrder = null;
restingTargetOrder = null;
...
String line = gw.cancelIfActive(sibling, ..., "sibling leg after " + ... + " filled");
```

`OrderGateway.cancelIfActive()`'s own javadoc (`OrderGateway.java` lines
198–205) states plainly: *"Returns null (nothing journaled) if the order
was already resolved (**filled/cancelled**) by the time this runs, which
is the normal case when the OTHER leg is the one that triggered the
cleanup."* Filled and cancelled are treated identically — both just mean
"nothing to do." But they are not the same outcome: if the sibling leg
had **also genuinely filled** on the exchange (a fast market or thin
liquidity gapping through both the stop and the target before either
cancel lands — not a contrived scenario, brackets exist precisely because
this can happen) rather than being cleanly cancelled by the broker/OCO,
the account is now flat-then-reversed — e.g. a long position's stop
(sell) *and* target (sell) both executing leaves the account net **short**
by the position size, not flat. Nothing in this code path can tell the
two cases apart, nothing journals which one actually happened, and
nothing corrects it:

- `restingStopOrder`/`restingTargetOrder` are already nulled by the first
  fill's own callback, so if the sibling's fill callback arrives *after*
  that (a real double-fill), `filledId` matches neither stashed field —
  it falls through to the generic `"not one of ours to track"` branch
  (lines 1432–1434) and produces no record beyond the bare `ORDER_FILLED`
  log line.
- `FlowStrategy.onFill()` has no caller anywhere (see §12) — the strategy
  never learns about real fills at all, so it can't notice its belief
  about being flat is now wrong either.
- The daily-loss kill switch only watches PnL against `entryPriceTicks`/
  `lastPosition` as `RiskChain` itself believes them to be — a real
  position the runtime doesn't know changed isn't something the kill
  switch is watching for at all.

⚠️ **AMBIGUOUS — how should a genuine double-fill (both bracket legs
executing) ever be noticed and corrected?** Not decided. A real fix needs
its own design pass — candidates worth considering when this comes up:
comparing `gw.currentPosition()` against the expected flat/target state
after any fill-related event and flagging a mismatch explicitly, or
having `cancelIfActive` (or a new sibling method) distinguish "already
cancelled" from "already filled" if the SDK exposes that distinction at
all (unconfirmed — see the reused list of unknowns in §9/§10). Flagged
only; this document does not propose applying a fix.

**Testable via**: `needs fake OrderContext` for the state-machine half
(feed two `onOrderFilled` calls for stop then target and assert the
current silent-drop behavior, as a regression baseline to compare any
future fix against) — but **actually observing which SDK signal, if any,
distinguishes a double-fill from a clean OCO-cancel** is a `cross-repo
platform question` first.

---

## 9. The bracket is sized off the stashed intent, not the confirmed fill quantity

**Partially informed 2026-09-22, via `../motivewave` Javadoc research —
underlying question still open.** `Order` explicitly models partial
fills (`getFilled()`/`getFilledAsFloat()`, distinct from `isFilled()`),
and there is no separate "onOrderPartiallyFilled" hook among `Study`'s
full `on*` method inventory — only `onOrderFilled`/`onOrderCancelled`/
`onOrderRejected`/`onOrderModified`. Whether `onOrderFilled` fires once
per partial fill or only on full resolution is **not documented anywhere**
and still needs a live order likely to partially fill to observe
directly. Regardless of that answer, though: the fix direction is now
better-grounded than a guess — `order.getFilled()` is a real, SDK-exposed
way to read the actual filled quantity, so `onOrderFilled`'s bracket-sizing
branch reading that (or `gw.currentPosition()`) instead of the stashed
intent value is correct either way. Full detail in
`../motivewave/docs/dynamic/findings.md` (2026-09-22).

`onOrderFilled()`'s case 1 (lines 1399–1424) sizes the bracket from
`pendingBracketTargetPosition` — the intent's *originally requested*
target position, stashed before submission — never re-checked against
`gw.currentPosition()` after the fill actually lands. If the entry order
is only partially filled (whether the SDK calls `onOrderFilled` once per
partial fill, or the account genuinely only got part of the requested
size), the bracket submitted immediately after would be sized for the
full originally-intended quantity, not the smaller quantity actually
held. Low blast radius **today** only because `config/risk.json` caps
`maxContracts` at 1 — a 1-lot order is generally all-or-nothing — but the
gap is in the code regardless of today's config, and would become a real
risk the moment size ever exceeds 1 contract.

⚠️ **AMBIGUOUS — does the SDK call `onOrderFilled` per-partial-fill, or
only on full resolution?** Unconfirmed — a `cross-repo platform question`
this document can't answer from FLOW_V2's own code. Whatever the answer,
`onOrderFilled`'s bracket-sizing branch should probably read
`gw.currentPosition()` at that moment rather than trust the stashed
intent value, but that's a fix to flag, not apply here.

**Testable via**: `cross-repo platform question` first; `needs fake
OrderContext` second, once the platform behavior is known.

---

## 10. Whether `onOrderFilled`/etc. can be invoked concurrently for two legs is unconfirmed

**Checked 2026-09-22, still fully open.** No documentation anywhere in
`../motivewave/docs/static/` (Javadoc or the SDK Programming Guide)
describes callback threading for `Study`'s order hooks at all. The one
existing thread-identity finding (`onActivate` on `TaskQueue - 16`,
`onBarClose` on `Quote Consumer 1 ...`, 2026-09-12) shows different hook
*types* dispatch on different named threads, which is suggestive context
but says nothing about two *order-fill* callbacks specifically. This
genuinely needs a live experiment, not more reading — and decompiling the
client-side SDK jar likely wouldn't help either, since order/account
event dispatch plausibly originates from the broker connection's own
network layer, not client code visible in this jar.

`onOrderFilled`'s own comment (`FlowRuntimeStudy.java` line 1389) already
flags this partially: *"Runs on whatever thread MotiveWave calls this
hook on."* All the mutable state it touches (`restingStopOrder`,
`restingTargetOrder`, `liveOrderInFlight`, `selfCancelledOrderIds`) is
`volatile`/a concurrent set — safe for *visibility*, not for the
*compound* read-null-then-act sequence in §8's sibling-cancellation
branch, which is not atomic. If MotiveWave can genuinely deliver two
`onOrderFilled` calls for the two legs of one bracket concurrently on two
different threads (rather than serializing all order callbacks for one
account/instrument), there's a real, un-locked race on top of §8's
detection gap, not just a detection gap alone.

⚠️ **AMBIGUOUS / cross-repo platform question** — does MotiveWave
serialize `OrderContext`-hook callback delivery per account/instrument,
or can two fire concurrently on different threads? Not established
anywhere in this codebase or `../motivewave`'s findings so far.

**Testable via**: `cross-repo platform question` first.

---

## 11. `refuseToArmReason()`'s timing at `onActivate` is trusted, not confirmed

**Checked 2026-09-22, still fully open.** Same search as §10 above, no
useful documentation found. Genuinely needs a live experiment: hold a
resting order or open position, restart/reconnect the platform, and
observe whether `onActivate`'s `ctx.getPosition()`/`ctx.getActiveOrders()`
reliably already reflect it.

`FlowRuntimeStudy.onActivate()` calls `gateway.refuseToArmReason()`
synchronously, immediately on receiving the `OrderContext`
(`FlowRuntimeStudy.java` lines 1302–1315), reading `ctx.getPosition()`/
`ctx.getActiveOrders()` and refusing to arm if either is non-empty — the
one thing standing between a restart and silently adopting a live
position. Whether the platform guarantees these two calls already reflect
the account's true state at the exact moment `onActivate` fires (versus,
say, right after MotiveWave itself starts up or reconnects, where an
account sync could still be in flight) is not confirmed against SDK
source or behavior anywhere in this codebase.

⚠️ **AMBIGUOUS / cross-repo platform question** — is `onActivate`'s
`OrderContext` guaranteed already synced with the real account state, or
could a fresh reconnect race this check into wrongly reporting "clear to
arm" a moment before the platform's own account-sync catches up? Given
this check is the one thing preventing a restart from adopting an unknown
live position, this is worth deliberately confirming rather than assuming,
the same way D-67's `onOrderFilled` gap was confirmed rather than assumed
away.

**Testable via**: `cross-repo platform question`.

---

## 12. The strategy never learns about real fills — already self-flagged, restated for completeness

`reconcileLive()`'s own javadoc (`FlowRuntimeStudy.java` lines 527–532)
already states this plainly: *"a real fill is never fed back to the
strategy (`FlowStrategy.onFill()` has no caller anywhere yet)."* Not a
new finding — restated here because §8's double-fill gap and this one
compound each other: even in the (much more common) single-clean-fill
case, the strategy's own internal position belief and the account's real
position are two independently-maintained facts that are never cross-
checked against each other by anything at runtime. `reconcileLive()`
mitigates this today by always reading `gw.currentPosition()` fresh
rather than trusting the strategy's belief — but only for the strategy's
*next* intent, not as an active check that the two haven't already
diverged.

**Testable via**: not independently testable as a "gap" — this is a
scope note. Worth folding into whatever design pass §8 eventually gets.

---

## 13. `flow-runtime` has zero automated tests of any kind

**Resolved 2026-09-22 — build it.** User's decision: "build it but keep
it strictly sim only not on real account. dont need consent to do the
tests." Read as: build a fake/stub `OrderContext` test double for
`flow-runtime`, purely a unit-test fixture under a plain JVM — it never
holds a real `OrderContext` or touches any actual account, Sim or real,
so CLAUDE.md's per-order-confirmation hard rule (which governs a *real*
`OrderContext` placing a *real* order) doesn't apply to it at all; running
these tests needs no fresh confirmation each time, same as any other unit
test. Not yet built — this closes the scope question, not the work
itself. Once it exists, it's the prerequisite for turning §8/§9/§10's
gaps into real regression tests rather than only Sim-live checklist items.

**Built 2026-09-26 (`decisions.md` D-91):** `FakeBroker` (proxy-based,
passive, fails loudly on unmodelled methods), `OrderGatewayTest`, and — after
the user approved extracting the `onOrder*`/`reconcileLive` state machine out
of `FlowRuntimeStudy` (which can't be instantiated outside MotiveWave) into
`LiveOrderTracker` — `LiveOrderTrackerTest`, which now covers §8's D-87
double-fill correction as a regression test. §9 (partial fills) and §10
(concurrent callbacks) are still not covered: the fake fills whole orders
only and delivers callbacks one at a time.

Every SDK-bound behavior above — `refuseToArmReason()`, `submitRealEntry`/
`submitRealBracket`, `cancelIfActive`/`cancelAllAndClose`, the whole
`onOrderFilled`/`onOrderCancelled`/`onOrderRejected` state machine — is
exercised today only by live Sim sessions, never by an automated test.
`SafetyHookReflectionTest` is the sole test in `flow-runtime`, and it only
checks that every `OrderContext`-taking hook is overridden — it never
calls into any of them.

⚠️ **AMBIGUOUS — is building a fake/stub `OrderContext` test double worth
the effort, or should everything in `flow-runtime` stay Sim-live-only?**
A real scope decision, not obviously "yes, always mock the SDK": the SDK
interface is large (`OrderContext`, `Order`, `DOM`, `Tick`, etc.), a fake
would need to plausibly model fill/reject/cancel callback timing to be
worth anything for §8–§10 above, and getting the fake's behavior wrong
could produce tests that pass against a fiction while the real platform
does something else — the exact failure mode D-67's live-only confirmation
was designed around. Confirm whether this is worth building before any
`flow-runtime` test code gets written on the assumption that it is.

**Testable via**: the meta-question itself needs an answer first — see
above.

---

## Open questions, collected

1. ~~§2 — kill switch goes dark once `Pipeline` disarms for any reason~~
   **Resolved 2026-09-22 — fixed.** Moved ahead of the `healthy` check.
2. ~~§4 — `RiskChain.evaluate()`'s short-circuit on the first blocking
   filter~~ **Resolved 2026-09-22 — kept as-is.** No code change.
3. **§4 — the two rollover cadences' shared, order-dependent
   `SessionBoundary.Tracker`** traces through as correct by hand and is
   now locked in by `RiskChainTest`'s interleaving test — nothing further
   to decide here.
4. ~~§7 — `cancelAllAndClose()`'s `closeAtMarket()` → `cancelOrders()`
   ordering~~ **Mostly resolved 2026-09-22 — [DOC]-tier.** `closeAtMarket()`
   is documented as blocking until filled; not yet live-confirmed.
5. ~~§8 — a genuine double-fill of both bracket legs is currently
   undetectable and uncorrected~~ **Fixed 2026-09-23 — see `decisions.md`
   D-87.** Position checked after sibling handling; a mismatch disarms +
   flattens. Not yet live-tested (needs a genuine double-fill to happen).
6. **§9 — bracket sizing trusts the stashed intent's target position, not
   the confirmed fill quantity.** The underlying SDK question (does
   `onOrderFilled` fire per-partial-fill) is still open, but 2026-09-22
   research found `Order.getFilled()` — a real, documented way to read
   the actual filled quantity regardless of that answer. The fix
   direction is now well-grounded; whether/when to apply it is still open.
7. ~~§10 — whether order-hook callbacks can be delivered concurrently for
   two legs~~ **Checked 2026-09-22 — no documentation exists either way.**
   Still fully open, genuinely needs a live experiment.
8. ~~§11 — whether `onActivate`'s `OrderContext` is guaranteed
   already-synced~~ **Checked 2026-09-22 — same as §10, no documentation
   exists.** Still fully open, genuinely needs a live experiment.
9. ~~§13 — whether a fake/stub `OrderContext` test harness is worth
   building at all~~ **Resolved 2026-09-22 — yes, build it**, strictly as
   a Sim-only test double (never a real account), no per-run confirmation
   needed since it never holds a real `OrderContext`. Not yet built.

**Status as of 2026-09-22 (second pass, live review)**: §§1, 2, 3, 4, 5, 6
are closed with code/tests; §13's scope question is decided (build it,
not yet built); §7 is resolved at [DOC]-tier via `../motivewave` Javadoc
research (`closeAtMarket()`'s documented blocking behavior), pending a
live confirmation whenever D-85 gets its first real trigger; §9 has a
well-grounded fix direction (`Order.getFilled()`) even though its
underlying SDK question is still open.

**§10/§11 live experiment attempted 2026-09-23, blocked before it could
run — a real finding of its own.** With the user's account/instrument/
quantity confirmed (Simulated, GC, 1 lot), a throwaway diagnostic
`Study` (`OrderCallbackThreadProbe.java`, `../motivewave/experiments/`)
was designed to log thread identity across `onActivate`/order callbacks
and fire one BUY 1 lot + SELL bracket test trade. Writing the file itself
was **refused by Claude Code's own auto-mode safety classifier**
("Real-World Transactions") the moment the code contained a real
order-submission call — independent of the account being Simulated, and
independent of `CLAUDE.md`'s own rule already having been satisfied
(exact account/instrument/side/quantity/order-type stated and confirmed).
This is a harness-level guardrail, not a project-methodology one. Offered
three ways past it (a permission-rule change, the user writing the
order-placing lines directly, or deferring) — **user chose to defer**.
§10/§11 remain fully open, unattempted; whoever picks this back up next
should expect the same block and plan for one of those three paths rather
than assuming a stated, confirmed order is sufficient on its own.

§8 (the double-fill gap) remains a pure FLOW_V2 design question, requires
no order placement to fix, and is next. Sequencing what's left is the
user's call, same as every other distillation document's closing note.
