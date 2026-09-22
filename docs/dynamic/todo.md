# To-do

Every task still to be done for FLOW_V2, in rough order. Companion to
`decisions.md` (closed answers) and `findings.md` (evidence) — this file is
the queue, those two are the record. When a task closes, tick it, add the
date, and link the decision/finding it produced; don't delete it.

## Where we left off (2026-09-20, updated — read this first, assume no memory of the conversation that produced it)

**Most recent (2026-09-20)**: today's plan (per direct instruction):
market structure rework first → then whatever can be done off-market for
(1) forward-testing workflow and (2) historical retention → then, if
tokens remain, start the plain-OHLC market structure backtest (§5, added
to todo — explicitly not reversing D-06's "no order-flow backtesting"
stance). Status:

1. **Market structure rework — done (D-74).** All 5 behavioral fixes
   plus the new `tradeableLevels()` concept built, `MarketStructureFeatureTest`
   (D-68) rewritten entirely (53 checks, all passing against the real
   code on the first run), full rebuild + replay regression clean,
   deployed. `MarketStructureFileLogger`/`redrawMarketStructureFigures()`/
   `MarketStructureLvnReversalStrategy` all updated to match (`lastDt()`/
   `lastDb()` replace the old shared `lastDtDb()`; the strategy now
   gates its area-of-interest read on `tradeableLevels()` so it can't
   trade the non-tradeable CHOCH placeholder).
2. **Forward-testing workflow, off-market half — done.** Offline journal
   comparison tooling (D-76, `analysis/journal_summary.py`) — the only
   item in that section not gated on a live/Sim session actually
   running. Everything else there (dry-run review, Sim sessions, a
   second strategy, committing fixtures) needs either live market data
   or a strategy design the user hasn't specified — left for when the
   market's open.
3. **Historical retention, off-market half — done.** Raw-log 48h
   auto-deletion (D-75, `LogRetention`) — the one concrete, unambiguous
   requirement in §4b. The rest of that section (price trace 5-day
   storage, VP daily/session snapshots, footprint week storage, big
   trades time series, VWAP storage) each need a format/location design
   pass first, and liquidity map's format is explicitly "to be
   discussed" per the user's own words — not guessed at rather than
   built wrong.

4. **Backtest — first real run done (D-79).** Data source resolved via
   a throwaway MotiveWave experiment (D-77: `HistoricalDepthProbe`/
   `HistoricalOhlcExporter` in `../motivewave/experiments/`) — found
   MotiveWave's own `DataSeries` caps at the client-side "Max Linear
   Bars" setting (25,000 bars / ~24.2 days of `@GC` 1-min, not an
   independent data-provider wall), exported to
   `analysis/data/GC_1m_1789910262328.csv`, MotiveWave-only per the
   user's stated preference. `MarketStructureBacktest` (flow-core,
   `com.flow.backtest`) built, tested (13 checks, real bug caught before
   touching real data), wired into `build/build.sh` as an eighth gate,
   and actually run against that CSV: 1,206 trades, totalR=189.00,
   avgR=+0.16/trade — explicitly not a signal to trust yet (loose entry
   filter by design, no costs modeled, thin sample). **Also**: at the
   user's own request, an agent distilled the order-flow execution
   rules into `docs/dynamic/orderFlowExecutionRules.md` for a *future*
   review (D-78, 15 points flagged) — prep only, the user explicitly is
   not picking up that review yet.

   **Learned the hard way, worth remembering**: `FLOW_V2` and
   `../motivewave/experiments` deploy into the SAME `MotiveWave
   Extensions/dev` directory and each wipes it before deploying its own
   classes — running either side's build again silently removes
   whatever the other side had deployed. Caught mid-session running
   FLOW_V2's `build.sh` for an unrelated compile-check, which briefly
   removed the deployed OHLC exporter study (recovered fine — MotiveWave
   had the class cached from the same running session).

Everything built today is compile-clean, all 8 `flow-core` test gates +
the safety reflection test pass, replay-equivalence regression holds,
deployed (FLOW_V2's runtime, as of the last `build.sh` run) —
**nothing except the backtest's own offline run has been live-verified**,
market's closed throughout.

**Just before that (2026-09-20)**: point 10, the last open rules
question, is answered directly (D-73) — `marketStructureRules.md`'s
review fully closed, all 10 points.

**Before that (2026-09-20)**: the 2 new ambiguities D-71 raised got
answered too (`market_structure_new_review.md` → D-72). Both resolved
cleanly: warm-start bars count for CHOCH's anchor (use the first fed
bar's open); and the chained-flip A+/SBR-RBS question resolved more
precisely than either guess D-71 offered — they're tradeable for
exactly the chain's first flip, then drop out for good, with the
tradeable set becoming `{DT, DB}` from the second flip onward,
refreshing one side at a time.

**Just before that (2026-09-20)**: the user's `marketStructureRules.md`
review landed (`market_structure_review.md`) — see D-71 and the
"⚠️ RESOLVED 2026-09-20" note further down this file. **Headline: 5 of
the 7 original ambiguities were guessed wrong**, and
`MarketStructureFeature`/`MarketStructureFeatureTest` (D-60/D-68)
currently implement/test those wrong guesses — a real rework is needed,
not yet started, sequencing is the user's call.

**Just before that (2026-09-20, weekend/no-live-market session)**: while
the user was still reviewing `marketStructureRules.md`, worked through
parked, live-market-independent todo items, per direct instruction
("continue with all the parked items in todo, keep asking questions if
ambiguity").

1. Built `MarketStructureFeatureTest` (D-68) — first synthetic-sequence
   regression test for `MarketStructureFeature` (D-60), 30 checks, all
   passing against the current implementation. **Two things surfaced,
   flagged to the user, not yet acted on**: (1) the CHOCH-driven first
   flip (before any real TJL pair exists) never calls `listener.onFlip`,
   silently missing from `market_structure_feature.log` even though the
   trend genuinely changes and the chart still draws it correctly; (2)
   the flip-watch runs against the CHOCH bootstrap point on every bar
   until a real pair forms, so how far price has drifted from the
   session's first candle governs whether an early pullback can complete
   before being reinterpreted as a flip.
2. Built the session-boundary-detection machinery (D-69) — asked the
   user for the exact rollover time (nothing had ever pinned this down),
   got 17:00 CT, built `SessionBoundary` + wired it into `RiskChain` and
   `VWAPFeature`. **A real RiskChain gap surfaced while testing it,
   flagged not fixed**: `checkDailyLoss` can block an exit exactly like
   an entry, so a position can get stuck open past the point where
   flattening would be correct — the opposite of what a kill switch
   should do.

Full detail on both in `decisions.md` D-68/D-69. Both are compile-clean,
all 5 test gates + the replay-equivalence regression pass, deployed —
**neither has been live-verified**, consistent with everything else
built this session (market's closed). Continuing with other parked
items next.

**Before this (2026-09-19)**: real order-submission code was built
(D-66) and live-tested with the user's explicit in-the-moment
confirmation (D-67) — a one-shot SELL 1 `GCZ6` market test order, 10-tick
SL/TP. **Entry worked** (confirmed filled on the Sim account from the
MotiveWave Account view directly). **Bracket attach didn't** — it's
gated behind `onOrderFilled`, which never got called for that order,
leaving the position naked until the user closed it manually. Suspected
cause: entry was submitted via `ctx.sell(int)` (returns `void`, no
`Order` handle), unlike every other order-creation method on
`OrderContext`; fixed to go through `createMarketOrder()` +
`submitOrders()` instead, same tracked family as the stop/target orders.
**Untested — market was closed for the rest of the session.** Full
detail, including the exact reasoning and what's still just a theory
(not confirmed against SDK source), in `decisions.md` D-67.

**Before the next trading session, needs to happen in this order:**
1. Confirm the naked position from the last test is still flat (user
   confirmed it was closed manually as of 2026-09-19 — re-verify at
   session start regardless, don't assume it's still true).
2. Decide whether to leave `FIRE_TEST_TRADE_KEY` checked (it will fire
   automatically on the next activation — that's deliberate, see D-67)
   or uncheck it first and re-check it deliberately right before
   activating, matching CLAUDE.md's "immediately before submitting"
   framing more literally.
3. **What needs retesting, specifically**: does `onOrderFilled` fire now
   for a `createMarketOrder()`-submitted entry? If yes — does the
   bracket (`submitRealBracket`, stop via `createStopOrder` + target via
   `createLimitOrder`) submit correctly, and do both legs show up
   correctly in MotiveWave's Orders/Positions panels (right side, right
   prices, OCO behavior when one leg fills)? If `onOrderFilled` *still*
   doesn't fire even for a tracked order, the theory in D-67 is wrong
   and this needs a different diagnosis — don't assume the fix worked
   just because it compiles.
4. Once retest passes end-to-end (entry + bracket both confirmed
   correct): per the user's own instructions, (a) strip the whole
   one-shot test-trade path out of `FlowRuntimeStudy`/`OrderGateway`
   (it's throwaway plumbing, not a feature) and (b) update `CLAUDE.md`
   to lower the per-order confirmation requirement specifically when Sim
   Trade Only is confirmed enabled, so the automated strategy can run
   unattended on Sim without a human confirming each entry — **not
   before both (entry AND bracket) are confirmed working**, since a
   policy change permitting unattended automatic trading shouldn't rest
   on a test that only proved half the flow.

Market structure got its historical warm-start + chart drawing (D-64),
and the user's own visual read of the resulting chart caught a real bug
(D-65) — continuation confirmation was giving up after the first
non-confirming candle instead of waiting for a later one, silently
discarding pullbacks whose real confirming candle wasn't the immediate
next bar. Fixed; live-verified the same 100-bar warm-start now produces
4 TJL formations instead of 1. Full detail in `decisions.md` D-64/D-65.
Liquidity map's best-bid/ask bug (D-63) is still open, not resolved —
see below.

## Older (2026-09-18)

**Most recent**: footprint (D-50) got a second, stronger live confirmation
today — compared directly against MotiveWave's built-in footprint study,
user's read: "working good enough." D-49 (zone age) stays deliberately
not-live-verified — the user's own call, not an oversight: hard to eyeball
against a live chart, trusted on the measured-cost accounting instead.

**Then big trades (D-53) got built, and broke a live session, then got
fixed** — full detail in `decisions.md` D-53 and `../../motivewave/docs/
dynamic/findings.md` 2026-09-18. Short version: the first attempt wrapped
the SDK's `AggregateFilter` the same way `VolumeProfile`/footprint wrap
their SDK engine (D-36's original plan) — this crashed immediately with a
`ClassCastException` the instant it saw a live tick, because
`AggregateFilter.onTick(Tick)` (unlike `VolumeProfile.onTick(Tick)`)
casts its argument to an internal concrete class, not just the `Tick`
interface. This **disarmed the live `level_zone_observer` session running
at the time** for its entire duration — caught, diagnosed, and fixed
within the same working session, not left for the user to discover.

**Fix**: big trades are now built directly against `flow-core`'s own
`TickEvent` stream (`BigTradeFeature`), not wrapping any SDK class at
all — `TickEvent` gained real `exchOrderId`/`aggExchOrderId` fields (were
previously always stubbed to 0 by `TickAdapter`, since nothing needed
them before this), and `BigTradeFeature` reimplements `AggregateFilter`'s
aggregate-by-order-id-then-threshold-then-repeat-with-growing-size logic
(T-3) directly against that stream. Zero SDK dependency, so it lives in
`flow-core`, not `flow-runtime`. Rebuilt clean, redeployed, **live-
verified that the crash is fixed** (the replacement session came back
healthy, zero DISARMs, trace records continuing normally) — **not yet
verified that the aggregation logic itself is correct against a real
trade**, since no print crossing the 10-contract default threshold had
happened in the short window checked right after redeploy. Next live
check: watch `logs/big_trade_feature.log` for `CREATED`/`REPEAT` lines
once a genuinely large print happens on `@GC`.

**Then drawing got added to big trades (D-54)**, same session, per two
direct requests (circles first, then a follow-up asking for the size
label too). One green/red CIRCLE `Marker` per trade
(green=buy-aggressor/"+ve", red=sell-aggressor/"-ve", matching
footprint's existing delta-sign color convention), labeled with contract
size. Hit a second SDK-jar mismatch along the way, same class as T-6:
`Enums.MarkerType`/`Size`/`Position` aren't resolvable as source-level
types (confirmed directly, same failure shape as `TickAdapter`'s
`Enums.BarData` finding) — worked around with a small reflection helper
(`MarkerAdapter`, flow-runtime) that builds the enum constants and calls
`Marker`'s constructor via `Constructor.newInstance`, then returns a
genuinely typed `Marker` for everything after that. Also fixed a
thread-safety gap this exposed: `BigTradeFeature.recent()` was building
its list live from drain-thread-only state — now a volatile snapshot,
same pattern VP/footprint already use.

**This pass also closed D-53's one remaining open item**: watched
`logs/big_trade_feature.log` fill with real `CREATED` → `REPEAT`
sequences against live `@GC` ticks, size genuinely growing per repeat
for the same order id (e.g. `10.0 → 11.0 → 13.0 → 25.0`) — confirms the
T-3 aggregation logic is correct against real data, not just
crash-free. Session stayed healthy throughout.

**User looked, and caught a real bug (D-55)**: circles landed at the
right price/time, but the contract-size numbers didn't match the
built-in "Big Trades(20)" study at corresponding spots — ours mostly
showed `1`, built-in showed varied `2`/`3`/`5`s. Root cause, found in
the SDK's own javadoc (not read closely before this): `AggregateFilter`'s
`aggByOrder` and `aggPeriod` are mutually exclusive **by data
availability, not choice** — order id wins whenever it's available,
which is always true on our Rithmic feed, so D-53's order-id-based
design was live-confirmed correct *for that mode* but was the wrong
mode: most real prints have distinct order ids per fill, so order-id
aggregation mostly degenerates to single-print trades. The built-in's
"(20)" is almost certainly `aggPeriod=20ms` — time+price window
aggregation, independent of order id, the more intuitive "sweep" notion
of a big trade.

**Rebuilt to match**: `BigTradeFeature` now tracks a single in-progress
time+price window (same price, same side, gap since the last matching
tick ≤ a new `Agg Period (ms)` setting, default 20 — best guess at the
built-in's own default). T-3's repeat behavior falls out of this for
free (same-id repeats are necessarily same price/side/short-gap).
`exchOrderId` on `BigTradeEvent` is now provenance only, not the
aggregation key. Full rebuild clean, redeployed, crash-free live —
**merge behavior itself (a `WINDOW_GROW` log line, or drawn numbers
actually varying like the built-in's) not yet observed**, since the
short post-redeploy window checked still had `minSize=1` set from the
prior drawing-verification pass, which lets every single tick clear
threshold before a merge would even become visible.

**User confirmed the D-55 fix**: second side-by-side against the
built-in study, numbers now vary and roughly track each other —
"matching almost exactly (90%) good enough." Also asked to keep the
original order-id aggregation (D-53's design) alive for future use:
"we might need both to identify iceberg orders and more analyses."

**Built D-56 in response**: `OrderRepeatView`/`OrderRepeatFeature`
(flow-core), a separate construct (not folded back into `BigTradeView`)
tracking any exchange order id seen trading more than once this
session — no size threshold, capture only, no iceberg classification
attempted. Logs to `logs/order_repeat_feature.log`. No settings/drawing
yet. One accepted-not-measured bound: the per-order-id tracking map is
capped at 5000 entries with LRU eviction (most order ids never repeat
at all, so without a cap this grows with tick count, not with anything
useful). Full rebuild clean, redeployed, session healthy — repeat
detection itself not yet observed live (needs a real order id to
actually trade twice in the checked window).

**Confirmed construct order, unchanged**: footprint (done) → **big
trades (done — aggregation mode confirmed ~90% match against built-in;
order-id repeat tracking split out as its own construct, D-56, for
future iceberg work)** → VWAP → market structure → liquidity map.

**Built, deployed, and live-verified against real `@GC` ticks**: walking
skeleton (D-41), replay harness (D-42), `VolumeProfileView` (D-44, drawn
POC/VAH/VAL matched the built-in study), D-38's `NamedLevel`/`NamedZone`
triggers (D-45), `LevelZoneObserverStrategy` + the price trace (D-46),
POC-relative row numbering on labels/trace (D-47), and the trace's
level/zone-identity fields — `levelPriceTicks`, `zoneLowTicks`/
`zoneHighTicks` + decimals (D-48). All of D-41 through D-48 are now
confirmed working against real ticks, not just compiled.

**Built and deployed, NOT yet live-verified**: D-49 — zone age
(`ZoneView.firstSeenAtMs`, `zone_trace`'s new `zoneAgeMs` field). Cost
was measured before building (not guessed): roughly +20 KB/hour on top
of D-48's ~310 KB/hour trace estimate, negligible memory for the
tracking map itself. D-50 — footprint (`FootprintView`/
`SdkFootprintFeature`), bar-scoped use of the same SDK engine, no
rotation-fix needed (bars are naturally short-lived), D-37's
partial-bar-discard rule applied, logs to
`FLOW_V2/logs/footprint_feature.log` for validation. D-51 — per-construct
draw flags (`DRAW_VP_KEY` default false, `DRAW_FP_KEY` default true) and
a `redrawFigures()` dispatcher split into `redrawVolumeProfileFigures()`/
`redrawFootprintFigures()`, plus `FootprintView` tightened to a single
`BarFootprint` record (drawing needs the bar's own time range, the
earlier 3-parallel-accessor design didn't carry that). D-52 —
footprint row merging for drawing only (`FP_MERGE_ROWS_KEY` setting,
default 1): raw stored footprint data is never touched, only the
drawn boxes/labels merge N adjacent price rows by grid bucket
(`Math.floorDiv`-based, not row-index/count-based, so gaps in the raw
rows don't skew the merge — verified against the user's own worked
example `0×1, 1×3, 4×0 → 5×4`). D-49 through D-52 all pass full rebuild
(`build/build.sh`, both test gates) + replay regression
(`build/replay_check.sh`) clean. **D-50/D-51/D-52 (footprint,
draw-flag dispatcher, row merging) are now live-verified** — user
tested 2026-09-18, confirmed working. **D-49 (zone age) is still
NOT live-verified** — built and regression-clean only.

**New todo raised after live-testing (2026-09-18)**: footprint drawing
should show delta *magnitude* via color (currently just sign —
green/red/gray flat colors), not yet designed. See section 4's
footprint items below.

**Open observation, action deferred, not a bug report**: HVN/LVN
classification may be too dense — a screenshot comparison suggested it,
but wasn't apples-to-apples (ours was `rangeTicks=1`, the manual
comparison was a 4-tick VP), so the real next step is rerunning the
comparison at matched granularity before concluding anything. See the
todo item below for the full writeup.

**D-39 (LVN/HVN reversal ranking) remains deliberately paused** — the
user's call: locking in ranking-model decisions (Layer 1 weights,
confluence scope, the Bayesian prior-weight constant) gets harder to
reverse once anything depends on them, better to wait.

**Historical retention — design captured, NOT started.** 2026-09-18 the
user laid out a full retention plan (price trace last 5 days, VP
daily+session snapshots, footprint last week at a configurable bar
interval, big trades time series, VWAP stored accurately, market
structure NOT stored, liquidity map format undecided) and then
explicitly said not to build it yet — write it to `todo.md` only, build
incrementally later. Full requirements are in section **4b** below.
Don't start any of it without a fresh explicit go, same as any other
`[BUILD]` item.

**Major redirect, 2026-09-18 — read this before doing anything else.**
The user does not want to resume D-39 (or the HVN/LVN density check)
next. Explicit instruction: build the remaining core constructs first —
**footprint, liquidity map, market structure** — before any more
per-zone refinement (age was the last one allowed in; "let's not get
involved in finding the strength of zones just yet"). Once those three
exist, wire **one simple strategy that uses all of them and actually
places orders** (Sim account, per every existing hard rule — confirm
account/Sim-Trade-Only before any activation, same as always), proving
the full pipeline end-to-end. **Only after that full plumbing is
working does optimization of individual parts begin** — D-39's ranking,
the HVN/LVN density question, and anything else per-construct all wait
until after this. This is a deliberate breadth-before-depth choice, not
a random topic change — don't quietly revert to depth-first work (e.g.
"let's just polish volume profile a bit more first") without asking.

**Confirmed construct order (2026-09-18)**: footprint (done, D-50, not
yet live-verified) → big trades → VWAP → market structure → liquidity
map. Big trades wraps `AggregateFilter` (D-36/E-5, real order-id
repeat-emission already confirmed live). VWAP is ported (not copied)
from MotiveWave's own published `VWAP.java` source — doesn't compile
as-is against our jar. Market structure builds on
`DataSeries.calcSwingPoints` (E-10, though E-7 found real swing
instability at low strength worth factoring in). Liquidity map is the
biggest and most novel — `DOMListener` isn't even wired into the walking
skeleton yet, so it starts from less existing groundwork than the rest.

**VWAP built (D-57)**: ported the core running-average algorithm from
MotiveWave's own published `VWAP.java` (cloned the real
`motivewave-studies` repo this time to read it directly, not just the
earlier summary) — confirms E-10's finding exactly, tick-weighted,
~90% of that file is settings/UI plumbing not needed here. Zero SDK
dependency (`VWAPFeature`, flow-core, same as `BigTradeFeature`), two
running doubles, no rotation concern. Session-anchored to attach time
(not a calendar-day boundary — that mechanism doesn't exist anywhere in
this codebase yet). Std-dev bands deliberately not ported (separate
computation, deferred). Volatile snapshot from day one this time, not
retrofitted like big trades needed. Log-only, no drawing/settings yet.
Full rebuild clean, redeployed, **live-verified**: crash-free, and the
sampled value itself tracked the same price range VP's POC/VAH/VAL were
reporting concurrently, `totalVolume` growing monotonically as expected.
Not yet compared against the chart's own built-in VWAP line (needs
drawing first).

**VWAP draw test pending** — no chart line wired for it yet (log-only
per D-57), so it hasn't been visually compared against the built-in
VWAP study the way every other construct has been. Do this whenever
VWAP drawing actually gets built — not urgent, just not forgotten.

**Order changed, 2026-09-18**: market structure is explicitly deferred
("we will pick market structure later on") — **not** the next
construct despite the order recorded just above. Before any more
construct work: commit and push current progress (both this repo and
`../motivewave`, since its `findings.md` picked up new entries this
session too). Then **liquidity map** is next, ahead of market
structure — check whether `../motivewave/experiments/` already has
enough live evidence (DOM/MBO capture, heatmap visual check) to build
from, or whether more experiments are needed first, before writing any
code.

**Commit + push done, 2026-09-18** — both `FLOW_V2` (D-53–D-57's work)
and `../motivewave` (the `AggregateFilter` cast finding) are pushed to
their `origin` remotes.

**Liquidity map experiment coverage, checked 2026-09-18 — verdict:
enough to start building, one cheap open item worth closing first.**
What already exists, all `[LIVE]` unless noted:

- **True MBO confirmed, not just top-of-book MBP** — the 2026-09-11
  finding (`../../motivewave/docs/dynamic/findings.md`): 50,988 real,
  distinct `DOMOrder` entries across 20 DOM updates on `@GC`, ~595-720
  price rows per side (the whole resting book).
- **Update cadence and data volume already measured**, twice, from two
  different capture strategies — `TickDomLogger.java` (top-of-book,
  long sample): ~171,600 DOM updates/hr. `DomDetailCapture.java` (full
  per-order detail, 6,000-update bounded sample): 32.76 updates/sec,
  2,747.6 `DOMOrder` entries/update. This is what D-35's retention
  numbers (Q-10) are built from — not a separate unknown.
- **A working `DOMListener` reference implementation already exists**
  and runs live: `DomDetailCapture.java implements DOMListener`,
  `update(DOM dom)` — directly reusable as the pattern for finally
  wiring `DomEvent` into `FLOW_V2`'s own pipeline (§2 above,
  "`DOMListener` not wired" is implementation work at this point, not
  an open experiment).
- `sdk-capability-findings.md` §2.8 already reasoned through *why*
  build-custom over built-in: the built-in `Order Heatmap`/`DOM Power`
  studies are 1-second/bar-close aggregate snapshots accumulated from
  whenever a DOM panel was opened — not Bookmap-grade, no per-order
  resolution, no sub-second timeline. Doc-level reasoning, not yet
  visually confirmed.

**One real gap, cheap to close, not a blocker**: the **liquidity
heatmap visual check** (§1b above) — actually adding the built-in
`Order Heatmap`/`DOM Power` studies to a chart and looking — has been
flagged open since 2026-09-16 and never done. Worth doing before or
alongside starting the build, same self-honesty check that changed the
plan for volume profile (D-36) — cheap enough that skipping it would be
skipping validation for no real reason, even though the doc-level case
for custom-build is already fairly strong on its own.

**Not a blocker either**: E-9 (`getDOMHistory()`'s actual shape/
retention on our feed, whether it's permission-gated) — only matters
for the "warm start on attach" nice-to-have (§2.8's point (a)), not the
core live construction. Every other construct here (VP, footprint,
VWAP) already accepted "forward-only from attach, no historical
warm-start" (D-37's pattern) — the same default applies to liquidity
map without needing E-9 answered first.

**User decided: skip the visual check, build directly** — "any
motivewave heatmap study is not accurate. whatever we will build will
be more accurate and i currently dont have bookmap subscription to
test against it so we just gonna trust our process." Then gave the
actual storage design directly (see D-58): live aggregate book,
never-persist-raw-DOM, periodic bounded-window snapshot instead. Three
follow-up design questions asked and answered (aggregate-only rows,
bounded snapshot window, same-JVM clock-triggered mechanism) before any
code — **liquidity map is now built, deployed, and live-verified** (D-58)
— full detail in `decisions.md`. All five constructs from the
2026-09-18 breadth-first redirect are now done except market structure,
which stays explicitly deferred per the user's own call.

**Liquidity map drawn on chart for a live visual test (D-59)**, same
session, right after D-58 landed — user asked directly. One colored
`Box` per DOM row in a draw window (separate setting from the journal
snapshot's own window, no storage relationship), deep blue → light
blue → white → yellow → orange → red → deep red by resting size, both
sides of price on the *same* scale (not a separate color family per
side) — exact gradient and "same scale both sides" spec given directly
by the user, not inferred. Positioned as a thin trailing column at
"now," not a scrolling heatmap across time — there's no per-row history
to draw (D-58: raw DOM detail is never persisted, only periodic bounded
snapshots). Plain linear min-max normalization across the combined
bid+ask window each redraw — simplest v1, known limitation stated
directly: one very large resting order would compress everything else
toward the cold end; not pre-emptively fixed, revisit from what the
live picture actually looks like. Required retrofitting
`LiquidityMapFeature.bidRows`/`askRows` to `volatile` for cross-thread
drawing reads (D-58's own javadoc had already flagged this as coming) —
free this time, since `onEvent()` already just reassigns to
`DomEvent`'s own immutable Lists, no new allocation needed. Full
rebuild clean, redeployed, session stayed healthy. **Not yet visually
confirmed** — same as every other construct's drawing, this runs
outside `Pipeline` entirely, so a rendering bug wouldn't show up in any
journal. Needs the user to look at the chart.

**Heatmap confirmed live (D-59)**: "yes working fine." Follow-up request
— heatmap history rendered behind the candles across time, not just the
live trailing column — recorded above and in §4, explicitly deferred,
not picked up now.

**Market structure is next, and its actual definition just changed
significantly** (2026-09-18): the earlier plan ("builds on
`DataSeries.calcSwingPoints`," E-7/E-10) is superseded by a much more
specific system the user provided directly — a trend-state machine with
pullback validation, TJL1/TJL2 zone formation, trend-flip detection, and
post-flip role reassignment (A+/SBR/RBS/DT/DB). Per the user's explicit
instruction, **no code gets written yet**: the full rule system was
distilled into `docs/dynamic/marketStructureRules.md` first, with 7
`⚠️ AMBIGUOUS` points flagged inline (CHOCH's exact price basis, how the
2-candle pullback check behaves past a 3rd counter-trend candle, whether
a pullback's candle range keeps growing until continuation, what "1%"
is measured against, what flip-watch keys off after the first flip
before a real TJL pair exists, the exact window for "highest/lowest
point after A+," and whether CHOCH is ever revisited after the first
real TJL pair forms). **Review deferred by the user** ("currently i
dont have time to review... fill the ambiguity with whatever u feel
good and move ahead... and complete the market structure part") — build
proceeded without waiting, per explicit instruction.

**⚠️→✅ RESOLVED 2026-09-20, AND FIXED — this was exactly the gap the
note below used to warn about, and it has now been closed end to end.**
The user reviewed `marketStructureRules.md` across three rounds
(`market_structure_review.md`, `market_structure_new_review.md`, and a
direct chat answer → D-71/D-72/D-73), all 10 points resolved, zero open
⚠️ items left in that document. **`MarketStructureFeature` (D-60) has
now been reworked to match (D-74)** — all 5 corrected behaviors (CHOCH
anchor at init-time/open-based, sliding-pair pullback validation,
body-height 1% base, DT/DB as the post-flip reference, DT/DB window
anchored to the SBR/RBS candle, chaining correctly across multiple
pair-less flips) plus the new `tradeableLevels()` concept are built and
passing. `MarketStructureFeatureTest` (D-68) is rewritten entirely — 53
checks against the real reviewed rules, not the old guesses. Full
detail in `marketStructureRules.md`'s closing section and
`decisions.md` D-71 through D-74. **Not yet live-verified** — market's
closed as of this writing.

**Market structure built (D-60)**: `MarketStructureFeature` (flow-core,
zero SDK dependency — pure bar OHLC logic), bar-close-triggered only.
Full state machine per the rules: trend, pullback tracking (anchored,
growing), TJL1/TJL2 formation on confirmed continuation, flip-watch
(independent of pullback state, checked every bar), and post-flip
A+/SBR/RBS/DT/DB role reassignment, including the simpler CHOCH-only
initial-transition case (no A+ exists yet, so no role reassignment
happens on that specific flip). Log-only
(`logs/market_structure_feature.log`), no drawing, no settings. Full
rebuild clean, redeployed, session stayed healthy. **Not yet
live-verified beyond "doesn't crash"** — no bar had closed in the short
window checked right after redeploy, so no pullback/TJL/flip event has
fired yet against real data.

**Market structure rules review — done 2026-09-20**, see the resolved
note above and D-71. No longer deferred.

**Went straight at the exec/risk-chain backbone (2026-09-18)**, per
direct instruction, "one by one": external config store → exec
reconciliation/brackets → risk chain → sizing → refuse-to-arm, in that
order, all built as D-61 (see `decisions.md` for full detail, and the
individual checklist items above in §2/§4 for each piece). `RiskChain`
(flow-core, all 8 filters from README's own list), `ExternalConfig`
(flow-core, `config/risk.json`), `MarketState.lastPriceTicks()` added
(a real gap — no general "current price" accessor existed before this,
needed for PnL marking). Full rebuild clean, redeployed, session
healthy, config load confirmed live with exact values. **Not yet
exercised beyond that** — no strategy currently emits anything but
`Intent.none()`, so none of the risk chain's filters or the
refuse-to-arm path have fired against a real changing intent yet.

**Entry evaluators + first real strategy built (D-62, 2026-09-19)** —
`MarketStructureLvnReversalStrategy`, exercising every construct from
this session together (market structure for the area of interest,
volume profile for the LVN, footprint/big trades/liquidity map for
entry confirmation, VWAP/order-repeats as non-gating context). Full
detail in `decisions.md`. **Found and fixed a real bug in self-review
before it ever ran live**: the strategy had no way to learn a
`RiskChain`-blocked intent never took effect, and would have silently
desynced from reality the first time `armed=false` (the default)
blocked a real entry — fixed with a new `FlowStrategy.
onIntentRejected()` hook, not a special case. Registered but not
switched on by default. Rebuilt clean, redeployed, confirmed the
already-running session survived the underlying `MutableMarketState`/
`Pipeline` changes across two redeploys. Per CLAUDE.md's hard rule,
this stays dry-run only — Sim-account order placement needs its own
explicit, in-the-moment confirmation later, not bundled into this
build step.

**Next concrete step**: select `market_structure_lvn_reversal` in the
`Strategy Id` setting and watch it actually run — its own logic hasn't
been live-verified at all yet, only that building it didn't break
anything.

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
  Related but separate: §4b now has a stated **48h raw-log
  auto-deletion** window (2026-09-20 scratch note) — that's the
  *duration* half of D-07's own still-open "2-3 days, TBD" figure, not
  an answer to Q-10's *content* question (whether per-order detail gets
  captured at all in the first place).

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
- [-] (2026-09-18) **Liquidity heatmap visual check** — dropped, not
  done, by explicit user call rather than left open by oversight: "any
  motivewave heatmap study is not accurate. whatever we will build will
  be more accurate and i currently dont have bookmap subscription to
  test against it so we just gonna trust our process." Custom build
  (D-58) went ahead without this comparison.

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
- [x] (2026-09-18) External config store, risk-defaults half → D-61:
  `ExternalConfig` (flow-core), flat JSON at `config/risk.json`,
  hand-edited/runtime-read-only, missing keys fall back to conservative
  defaults, loaded+journaled (`risk_config_loaded`) at session start
  with staleness (file mtime). **Live-verified**: exact config values
  confirmed in the journal. Still not done: per-`strategyId`-scoped
  params and external levels (GEX, key levels) — `StrategyConfig`
  itself is still an empty-map placeholder; only the risk/sizing half of
  D-08/D-18's original scope exists so far, built first since the risk
  chain needed it immediately.
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
- [x] (2026-09-18) Refuse-to-arm on existing position / resting orders
  (D-24) → D-61: `OrderGateway.refuseToArmReason()`, checked on
  `onActivate`. Built and deployed; not yet live-exercised (needs the
  user to actually activate the strategy in MotiveWave — hasn't
  happened this session).
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
  for trigger logic specifically and already caught one real bug.
  `MarketStructureFeatureTest` (D-68, 2026-09-20) is the same direct,
  non-DSL pattern applied to the state machine — 30 checks, caught 2 real
  findings (CHOCH-driven first flip never calls `onFlip`; flip-watch
  sensitivity to CHOCH's position relative to later price action), both
  flagged not fixed. The general-purpose DSL README describes
  (`seq().trade(...).expectIntent(...)`) for strategy-level fixtures is
  still not built — every direct synthetic test so far has covered a
  specific construct instead, and each one has paid for itself (this is
  now three-for-three: real bugs found before any live run, matching
  D-45's original one).

## 3a. Plumbing robustness — wider edge-case search (2026-09-22)

Raised by the user directly ("test plumbing with all the edge cases we
can find... i want the system to be robust before i start to optimise
strategy"), separate from and prior to any strategy-tuning work. A first
read through the actual plumbing code (not just what README/decisions.md
*say* it does) already surfaced two concrete gaps below — the rest is the
plan for finding/closing the remainder, following the same
distill-then-decide shape as the market-structure and order-flow rule
reviews (`[[review-then-rework-workflow]]`-equivalent for code coverage
rather than hand-specified rules): survey → distill candidate edge cases
into their own doc → flag genuinely undecided behavior `⚠️ AMBIGUOUS`
→ wait for the user → only then write tests.

- [x] (2026-09-22) **`ReplayEquivalenceTest` exists but is not wired into
  `build/build.sh`'s gate list.** → Fixed, wired in against a new
  committed fixture, `flow-core/fixtures/replay_fixture_null_strategy/`
  (13 synthetic events run once through a real `Pipeline`+`JournalWriter`+
  `NullStrategy`, not hand-written JSON — see that directory's own
  `README.md`). Turned out less trivial than "one missing line" while
  building it: `ReplayEquivalenceTest` takes a session directory as an
  argument, and **every currently-registered strategy is degenerate for
  this purpose** — `NullStrategy`/`LevelZoneObserverStrategy` never
  change their intent by design, and `market_structure_lvn_reversal`/
  `lvn_fade_test` both require `VolumeProfileView`, which only has an
  SDK-backed implementation and can never be reconstructed under replay's
  plain-JDK harness (D-43). The fixture is an honest 0-vs-0 comparison —
  proves the record→replay→compare machinery runs end to end, not real
  intent-change equivalence, exactly as the test's own printed NOTE
  already says. Full detail in `docs/dynamic/plumbingEdgeCases.md` §1.
  **This directly affects the D-84 fixture item below.**
- [x] (2026-09-22) **`RiskChain` has zero dedicated tests.** → Fixed,
  `RiskChainTest.java`, 39 checks, wired into `build.sh`: one case per
  filter, both churn sub-checks, both lag sub-checks, the short-circuit
  ordering, `dailyLossBreached()` against `checkDailyLoss()`, and the
  rollover-interleaving trace from `plumbingEdgeCases.md` §4, all passing
  against current code. These lock in *current* behavior as a regression
  baseline; whether the short-circuit-ordering and shared-rollover-tracker
  design is the *right* one long-term is still open (§4's own ⚠️ points,
  untouched by writing the tests). Side-finding while writing the churn
  test, noted not fixed: `checkChurn`'s reversal-cap check doesn't
  special-case "this is the very first entry" — `maxReversalsPerSession:
  0` blocks even the first-ever entry, not just a later reversal.
- [x] (2026-09-22) **`Pipeline`'s exception boundary has no dedicated
  test.** → Fixed, `PipelineExceptionBoundaryTest.java`, wired into
  `build.sh`, using a real `Sequencer` (not a bare `try/catch` around a
  direct `handle()` call) so a regression in `Sequencer`'s own wrapping
  would be caught too. Confirms the drain thread survives, raw ingestion
  resumes for later events, the strategy is never invoked again, and the
  `DISARM` record carries the throwable. **New, smaller finding along the
  way**: the *triggering* event itself is never raw-journaled (`writeRaw`
  runs after the feature loop, which is where the throw happens) — a
  post-incident raw-journal review will have a one-event hole exactly at
  the moment something went wrong. Not fixed, noted in
  `plumbingEdgeCases.md` §6. The same test file also closes the §2 kill-
  switch-goes-dark item below (it's really one Pipeline-health question,
  tested together).
- [x] (2026-09-22) **The D-85 kill switch going silent once `Pipeline`
  disarms for any reason (`plumbingEdgeCases.md` §2) has a regression
  test now**, `PipelineExceptionBoundaryTest.testKillSwitch_GoesSilent
  AfterPipelineDisarmsForAnUnrelatedException()`, with a positive control
  proving the exact same breach *does* trip the kill switch when nothing
  disarmed Pipeline first. Locks in current (silent) behavior as a
  baseline; whether it should change is still open, untouched by writing
  the test.
- [x] (2026-09-22) **Journal backpressure paths are unverified.** →
  Fixed, `JournalBackpressureTest.java`, wired into `build.sh`: the exact
  10,000/50,000 capacity boundaries, a single contiguous `GAP_MARKER` for
  one drop episode, and two separate episodes (with a successful write in
  between) producing two distinct `GAP_MARKER` records rather than one
  merged range.
- [ ] **Everything SDK-bound in `flow-runtime` is untested**: restart-
  with-resting-orders-or-open-position refuse-to-arm, `OrderGateway`
  bracket submission/cancellation edge cases (one leg rejected, both legs
  gone with position still open — see the D-86 revisit item above), and
  the D-85 kill switch's `cancelAllAndClose()` racing an in-flight fill.
  **Scope decided 2026-09-22 (`plumbingEdgeCases.md` §13): build a
  fake/stub `OrderContext` test harness**, strictly a unit-test double
  under a plain JVM (never a real account, Sim or otherwise) — the
  user's own words: "build it but keep it strictly sim only not on real
  account. dont need consent to do the tests." Since it never holds a
  real `OrderContext`, CLAUDE.md's per-order-confirmation rule doesn't
  apply to running these tests at all. **Not yet built** — this closed
  the scope question, not the work; the harness itself, and the §8/§9/§10
  regression tests it enables, are next.
- [ ] **Turn the two D-84 near-miss raw session logs already sitting in
  `logs/` into a committed regression fixture** proving D-86's
  bracket-only-close fix actually holds against the exact event sequence
  that caused the original double-close race. **Reassessed 2026-09-22,
  NOT closable the way originally scoped**: identified the exact two
  session dirs (`lvn_fade_test_1790007162838_inst1154906642` for the
  original bug, `lvn_fade_test_1790009860788_inst1194290049`'s second
  trade for the follow-up incident — both confirmed against `decisions.md`
  D-84's own account), but D-86's actual fix lives entirely in
  `FlowRuntimeStudy`/`OrderGateway` (flow-runtime, SDK-bound), which
  `ReplayHarness` never touches at all (it only drives `Pipeline`+
  strategy+features under a plain JDK) — so replaying these logs could
  only ever prove `LvnFadeTestStrategy`'s own intent sequence reproduces,
  never that `reconcileLive()` correctly no-ops on a stop/target hit. And
  `LvnFadeTestStrategy` requires `VolumeProfileView` (see the
  `ReplayEquivalenceTest` item above) — the same D-43 gap, so even that
  narrower claim can't be made from these particular logs. **Now that
  the fake-`OrderContext` harness is queued (above)**, this item's most
  natural path is folding it into that harness's own test scenarios once
  it exists, rather than a `ReplayHarness`-based fixture at all — revisit
  then rather than deciding the format now.
- [~] (2026-09-22, review started same day) **Distillation done →
  `docs/dynamic/plumbingEdgeCases.md`, review now in progress.** Same
  convention/status as `orderFlowExecutionRules.md` (D-78) at first, then
  the user started the live review pass the same day rather than bringing
  answers back separately. Three of nine ⚠️ points resolved so far: (1)
  the kill-switch-goes-dark finding → **fixed**, moved ahead of
  `Pipeline`'s `healthy` check; (2) `RiskChain`'s short-circuit-ordering
  question → **kept as-is**, no code change; (3) the fake-`OrderContext`-
  harness scope question → **build it**, strictly Sim-only, no per-run
  consent needed since it's a pure test double. Still open: §7
  (`cancelAllAndClose` atomicity), §8 (the double-fill gap — still the
  most consequential open point), §9 (fill-quantity trust), §10
  (concurrent order-hook callbacks), §11 (`onActivate` sync timing) — §7/
  §10/§11 need `../motivewave` experiments, §8/§9 need their own design
  pass. Full detail and status per point in the doc itself.

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
  levels after a few minutes live on `@GC`. Session-scoped only —
  footprint (bar-scoped `FootprintView`, same engine) is now built
  separately, see below (D-50).
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
- [x] (2026-09-16) POC-relative row numbering (D-47): chart labels and
  `level_trace`/`zone_trace` (`relativeRow`, `priceDecimal` fields) both
  use it — POC=0, +/- rows scaled by distance, zones overlapping VAH/VAL
  forced to that level's exact number. **Live-verified 2026-09-18.**
- [x] (2026-09-17) Price trace carries level/zone identity, not just
  current price (D-48): `level_trace` gains `levelPriceTicks`/
  `levelPriceDecimal` (the level's own value, can differ from current
  price under D-38's cause-agnostic cross); `zone_trace` gains
  `zoneLowTicks`/`zoneHighTicks` + decimals (which specific zone fired,
  not just its kind). Needed `TriggerEvaluator.lastFiredZoneRange()` — a
  fire-time snapshot, since a `LEAVE`'s zone range is already cleared
  from the normal tracking state by the time `Pipeline` would otherwise
  read it. **Live-verified 2026-09-18**: 9,943 ticks, 876 `level_trace` +
  2,194 `zone_trace`, zero exceptions, new fields confirmed correct.
- [~] (2026-09-18) Zone age (D-49): `ZoneView.firstSeenAtMs` +
  `zone_trace`'s `zoneAgeMs`. Cost measured before building: ~+20 KB/hour
  of trace bytes, negligible tracking-map memory. Full rebuild + replay
  regression clean. **Not yet live-verified.**
- [x] (2026-09-18, live-verified 2026-09-18) Footprint (D-50):
  `FootprintView`/`SdkFootprintFeature` — bar-scoped use of the same SDK
  `VolumeProfile` engine (D-36), reset every bar close instead of
  session-scoped, so none of `VolumeProfileView`'s E-3 rotation-fix
  machinery is needed (a bar is naturally short-lived). D-37's
  partial-bar rule applied directly: the bar in progress at attach is
  discarded, `isReady()` true only once a full bar has closed.
  `FootprintRow` deliberately has no imbalance flag — raw
  `askVolume`/`bidVolume`/`delta` only, imbalance-threshold choice
  deferred per the same 2026-09-18 redirect that deferred D-39. Own
  settings row (`Footprint Row Width`, default 1 tick), independent of
  `VolumeProfileView`'s. User confirmed working live.
- [x] (2026-09-18, live-verified 2026-09-18) Per-construct draw flags +
  drawing separation (D-51): `redrawFigures()` is now a dispatcher
  (`clearFigures()` once, then one `if (drawFlag) redrawXFigures()` per
  construct) instead of one method hardcoding `SdkVolumeProfileFeature`
  by name — the gap the user's own question exposed, fixed rather than
  left. `FootprintView` tightened from three parallel accessors to one
  `BarFootprint` record (needed the bar's own time range anyway, to draw
  footprint positioned at its own candle). Volume Profile drawing now
  **off** by default, Footprint **on** — footprint drawn directly over
  candles (current + last closed bar only, per `FootprintView`'s
  existing scope — no longer history to draw yet). User confirmed
  working live.
- [x] (2026-09-18, live-verified 2026-09-18) Footprint row merging for
  drawing (D-52): `FP_MERGE_ROWS_KEY` setting (default 1), merges N
  adjacent price rows into one drawn box/label by price-grid bucket
  (`Math.floorDiv`-based, handles gaps in raw rows correctly — not
  row-index/count-based). Raw stored footprint data untouched, this is
  display-time only. User confirmed working live.
- [ ] **Beautify footprint drawing with delta-describing colors**
  (raised 2026-09-18, after live-testing D-50/D-51/D-52 and confirming
  the mechanics work). Currently `drawFootprintBar()` only picks one of
  three flat colors by delta *sign* (green/red/gray) — no sense of
  *magnitude*. Wants something closer to a proper footprint chart's
  visual language, e.g. color intensity/gradient scaled by delta size
  or by bid/ask imbalance ratio, not just up/down/flat. Purely a
  drawing-layer change (`FlowRuntimeStudy.drawFootprintBar()`) — raw
  `FootprintRow`/`BarFootprint` data unaffected, consistent with D-52's
  same draw-only boundary. Design not started: exact color scale,
  whether it's linear or something else, and whether it needs its own
  settings (e.g. a max-delta-for-full-saturation value) are all open.
- [x] (2026-09-18) Big trades (D-53/D-54/D-55, order-repeat split out as
  D-56): **not a wrapper around `AggregateFilter`** — that plan (D-36/
  E-5) broke live the moment it was tried (`AggregateFilter.onTick`
  casts to an internal concrete class, disarmed a live session — see
  `../../motivewave/docs/dynamic/findings.md` 2026-09-18). Rebuilt as
  `BigTradeFeature` (flow-core, no SDK dependency) directly against
  `TickEvent`'s new `exchOrderId`/`aggExchOrderId` fields. First
  aggregation attempt used order id (T-3-style, live-confirmed correct
  for that mode) but a side-by-side against the built-in "Big
  Trades(20)" study showed it was the wrong mode — switched to time+price
  window aggregation (D-55, `Agg Period (ms)` setting, default 20,
  matching the built-in's inferred default) after finding
  `AggregateFilter`'s own javadoc says order id and `aggPeriod` are
  mutually exclusive by data availability, not choice. **User-confirmed
  live: "matching almost exactly (90%) good enough."** Drawing (D-54):
  one green/red CIRCLE `Marker` per trade + contract-size label, via a
  `MarkerAdapter` reflection helper (`Enums.MarkerType`/`Size`/`Position`
  hit the same T-6-class source-resolution mismatch `TickAdapter` already
  found for `Enums.BarData`). Order-id aggregation kept alive separately
  as `OrderRepeatView`/`OrderRepeatFeature` (D-56, log-only, no drawing)
  per explicit user request, for future iceberg/hidden-liquidity
  analysis. `BigTradeFeature.recent()` uses a volatile snapshot for
  cross-thread drawing-path reads; `OrderRepeatFeature.recent()` doesn't
  yet (nothing cross-thread reads it).
- [x] (2026-09-18) VWAP (D-57): ported the core running-average
  algorithm from MotiveWave's own published `VWAP.java` (D-36,
  genuinely tick-weighted) — confirmed by cloning the real repo and
  reading the file directly this time, not the earlier summary alone.
  Zero SDK dependency, so it's `VWAPFeature` in `flow-core`, not
  `flow-runtime` (unlike the original plan's assumption it would need
  porting into `flow-runtime` — turned out not to need the SDK at all).
  Session-anchored to attach time, not a calendar boundary (that
  mechanism doesn't exist yet anywhere in this codebase). Std-dev bands
  not ported (separate computation, deferred). Live-verified: crash-free,
  sampled values tracked VP's concurrent price range correctly,
  `totalVolume` growing monotonically. Not yet drawn/compared against
  the chart's built-in VWAP line.
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
- [ ] Session / prior-session levels, ATR, overnight high/low (VWAP
  itself is done, see D-57 above — this item is the rest of the bundle)
- [x] (2026-09-20) **Session-boundary-detection machinery** → D-69:
  `SessionBoundary` (flow-core), 17:00 CT rollover (decided directly with
  the user this session — no exact time existed anywhere before this).
  Wired into two of the four things flagged here as eventually needing
  it: VWAP's daily reset (now real, not deferred) and D-19's
  reversal-cap counter (plus D-30's daily-loss PnL, not originally
  listed here but the same "per session" concept). **Deliberately NOT
  wired**: D-21's session price anchor (`PriceCodec`) — re-anchoring
  ticks mid-run would desync every already-live tick-keyed feature's
  state, a materially bigger change than resetting a counter, flagged as
  a real design question if it's ever wanted, not silently done. The
  24h boundary itself (D-29/D-34) was already decided *where* it falls;
  this just makes it detectable in running code. Full detail + a real
  RiskChain finding (daily-loss can block an exit, not just an entry —
  flagged to the user, not fixed) in `decisions.md` D-69.
- [x] (2026-09-18) Liquidity map (D-58): `DOMListener` finally wired
  into `FlowRuntimeStudy` (`DomEvent` grows `bidRows`/`askRows`, its own
  original comment's anticipated evolution). `LiquidityMapFeature`
  (flow-core, zero SDK dependency): each DOM update replaces the live
  aggregate book wholesale (O(1), no rotation needed — unlike
  `VolumeProfileView` this never accumulates). Per the user's own design
  (2026-09-18): raw DOM updates are **never** persisted at all
  (`RawEventCodec` stays top-of-book-only by construction) — only a
  periodic (~10s), bounded-window (±100 ticks) snapshot of the live
  state reaches the journal, a new `Pipeline`-owned clock-triggered
  mechanism (`liquidity_snapshot` records). Accepted consequence:
  replay (D-11) can't reconstruct this construct at update granularity,
  only at snapshot granularity — flagged to the user before building.
  Aggregate rows only, no per-order tracking (see the deferred
  experimentation item below). Liquidity-heatmap visual check explicitly
  declined (see §1b). **Live-verified immediately**: real MBO data
  flowing (~920-930 bid rows / ~865-870 ask rows on `@GC`), zero
  DISARMs, periodic snapshots firing on schedule, **measured** (not
  estimated) storage cost ~3.6 KB/snapshot → ~1.3 MB/hour, ~31 MB/day.
- [x] (2026-09-18) Liquidity map drawing (D-59): heatmap test draw per
  direct request — one `Box` per DOM row in a draw window, deep blue →
  light blue → white → yellow → orange → red → deep red by resting
  size, both sides on the same scale. Thin trailing column at "now,"
  not scrolling across time (no per-row history exists to draw). Plain
  linear min-max normalization, known outlier-compression limitation
  stated, not fixed. Retrofitted `LiquidityMapFeature`'s row fields to
  `volatile` for this (free, no new allocation). Full rebuild clean,
  redeployed, session healthy. **User-confirmed live: "yes working
  fine."**
- [ ] **Heatmap history on chart, as a background layer below the
  candles** (raised by the user right after confirming D-59's live
  column works, 2026-09-18) — explicitly **not being picked up now**.
  Current drawing (D-59) only shows a single trailing column at "now";
  the user wants the heatmap's history rendered across the chart's time
  axis too, sitting visually behind the candles (a real scrolling
  Bookmap-style heatmap, not just the live edge). This needs actual
  historical per-time liquidity data to draw from, which today only
  exists as the periodic `liquidity_snapshot` records (D-58, ~10s
  cadence, bounded window) — the resolution and retention of those
  snapshots would directly determine how detailed/how far back this
  history view could go. Not started, not designed — revisit deliberately
  later, same as every other `[DESIGN ONLY]`/deferred item here.
- [ ] **Deferred experimentation item, per-order DOM detail (raised by
  the user alongside declining per-order tracking in D-58)**: two
  linked questions, not yet started. (1) Do we even reliably get real
  order ids/size/age from individual resting orders on this feed, at
  useful fidelity — `DOMOrder.getExchangeOrderId()`/`getQuantity()`
  exist in the SDK and MBO itself is confirmed real (2026-09-11
  finding), but resting-order-level reliability over time hasn't been
  specifically checked. (2) If that data is available and reliable, can
  it actually support identifying institutional intent — icebergs,
  manipulation, hindsight market-mover analysis, etc. — or does it turn
  out too noisy/ambiguous to be useful. A real research question, not
  assumed either way; not started.
- [x] (2026-09-20) Book imbalance → D-70: `LiquidityMapView.
  imbalanceAtLevels(int n)`, nearest-N-rows-per-side, `[-1,1]`, already
  reachable via `MarketState.liquidityMap()` — no strategy consumes it
  yet, available when one wants it.
- [x] (2026-09-18) Big trades: see the D-53 entry above (§4's own big
  trades item) — built as `BigTradeFeature` directly against `TickEvent`,
  not `AggregateFilter`, after the wrapped-engine plan broke live. Fixed
  threshold declared (D-22 class 1). Footprint: see the
  `VolumeProfileView` line above, same engine
- [x] (2026-09-15) Partial-bar-at-attach handling → **D-37: mark invalid,
  skip it, build starts from the next full bar close, no backfill.**
- [x] (2026-09-15) Triggers: `BAR_CLOSE`, `EVERY_TICK`, `THROTTLE`,
  dynamic `PRICE_CROSS` / `BOOK_CHANGE` (D-16) → `Trigger.java`, built as
  part of the walking skeleton (D-41). Stale unchecked item, corrected
  2026-09-18 on noticing it during a status review — wake reason
  journaling exists too, via `TriggerEvaluator`/`Pipeline`'s trace lines
  (D-45).
- [x] (2026-09-15) `exec/` reconciliation: intent diff → minimal order
  actions (D-13) → `OrderGateway.reconcileDryRun()`, position diff
  already existed; extended 2026-09-18 (D-61) to also report the
  bracket (stop/target) that would accompany a real entry.
- [x] (2026-09-18) Risk chain: armed, session open, readiness,
  daily-loss, size cap, rate limit, churn guard, lag guard — each
  verdict journaled (D-13, D-19, D-61) → `RiskChain` (flow-core), wired
  into `Pipeline.handle()`. "Session open" is a hard-coded ALLOW for now
  (no session-boundary-detection machinery exists, same gap noted for
  VWAP/market structure). Live-verified config load; **the chain's own
  filters haven't fired yet** — needs a strategy that actually changes
  its intent, which doesn't exist until the next item below.
- [x] (2026-09-18) Sizing and brackets per Q-06 → D-61: fixed contracts
  stays a strategy-level decision (reads `fixedContracts` from
  `ExternalConfig`), `RiskChain`'s size cap is a separate safety check
  against `maxContracts`. Brackets: see the `exec/` reconciliation item
  above.
- [x] (2026-09-21) **Fix `RiskChain.checkDailyLoss()` blocking an exit,
  not just an entry** (found 2026-09-20 while building
  `SessionResetWiringTest`, D-69) → **closed, but not via the "likely
  fix" this item originally guessed at.** D-85 didn't exempt
  risk-reducing intents from `checkDailyLoss()`/`checkSizeCap()` —
  `evaluate()`'s per-intent gating is unchanged, so a breached exit
  *intent* can still be blocked exactly as before. Instead, a new
  `RiskChain.dailyLossBreached(Context)` is checked on **every event**,
  independent of intents entirely, and a breach forcibly runs
  `OrderGateway.cancelAllAndClose()` — bypassing the intent path rather
  than un-blocking it. Full detail in `decisions.md` D-85.
- [ ] Intent-seq vs execution-seq gap journaled per trade (D-17) — still
  not done; moot until a real strategy's intents actually reach
  `OrderGateway` with any latency worth measuring.
- [x] (2026-09-19) Entry evaluators in `flow/` (D-27) → D-62:
  `AbsorptionEvaluator` (footprint, stateless), `AggressionEvaluator`
  (big trades, stateless), `SweepEvaluator` (liquidity map, **stateful**
  — detecting a sweep means detecting change, needs a memory of the
  prior reading). "Imbalance stacking" specifically not built — the
  user's actual first-strategy design used absorption/aggression/sweep
  instead; imbalance stacking stays unbuilt until something needs it.
- [~] **Order-flow execution rules review — distillation done (D-78),
  review itself explicitly NOT picked up yet.** Same process as
  `marketStructureRules.md`: `docs/dynamic/orderFlowExecutionRules.md`
  now exists, distilled from the actual code (no external spec exists
  for this one, unlike market structure's Pine Script drafts) —
  `AbsorptionEvaluator`/`AggressionEvaluator`/`SweepEvaluator`, how
  `MarketStructureLvnReversalStrategy` combines them ("any one, not all
  three required"), and the stop/target rules, all originally built by
  best judgment per D-62's own javadoc. 15 points flagged ⚠️ AMBIGUOUS,
  collected in that document's closing section — full list in
  `decisions.md` D-78. **Do not start the actual review or any code
  rework unprompted** — wait for the user to bring their own answers,
  the same way `marketStructureRules.md`'s review arrived as its own
  explicit files/messages, not inferred from context.

## Strategy signals — chart drawing `[DONE]`

- [x] **Draw the strategy's own order signals on the chart** (raised by
  the user, 2026-09-19; built 2026-09-19): up arrow (green, "B") at the
  entry price for a long entry, down arrow (red, "S") for a short entry.
  `MarkerAdapter` (D-59's reflection helper) gained an `arrow()` factory
  (`Enums.MarkerType.ARROW`, orientation via `Position.BOTTOM`/`TOP` —
  unconfirmed visually yet which way it actually renders, flag to fix if
  upside down). `FlowRuntimeStudy.onIntentChanged` now also calls
  `recordEntrySignal()`, appending to a bounded (200) synchronized list
  read back by the new `redrawEntrySignalFigures()`, dispatched from
  `redrawFigures()` behind a new `FLOW_DRAW_ENTRY_SIGNALS` setting
  (default on). **Important semantic**: `onIntentChanged` (the
  `IntentSink`) is only ever called by `Pipeline` for intents the risk
  chain already ALLOWED — with `armed=false` (the default) every intent
  is blocked before it gets here, so this list stays empty and no
  arrows draw until the session is armed. This matches the "order
  signals" framing of the original request (signals that became real
  accept-track intents), not "everything the strategy considered,
  blocked or not." Compiled, safety reflection test still passes,
  redeployed. Not yet visually confirmed on a live chart (needs an
  armed session to produce any arrows at all — see the armed/order-
  placement discussion, 2026-09-19).

## Open bug, not resolved `[BUG]`

- [ ] **Liquidity map bestBid/bestAsk still intermittently wrong**
  (D-63, found + partially fixed 2026-09-19, monitoring the first real
  strategy live). A genuine stale resting order ~$22 below market
  (`4396.90` on `@GC`) was making `bestAskTicks` freeze at that value
  for whole sessions. Fixed by anchoring best-bid/ask to the last
  genuinely traded price instead of the raw DOM extreme — this measurably
  improved things (mostly sane values now) but **the same stale value
  still intermittently reappears**, confirmed still happening well
  after real ticks were flowing (so "no trade seen yet" doesn't fully
  explain it). Root cause of the remaining intermittent cases not
  found — need more targeted live debugging (e.g. logging the full raw
  row count/extremes at the exact moment it reverts, not just periodic
  1s samples) than this session's log-polling allowed. Does **not**
  affect the first strategy's actual entry logic (`SweepEvaluator`
  reads exact-price liquidity, never best-bid/ask) — affects the
  heatmap's window centering (D-59) and the periodic snapshot (D-58)
  only.

## 4c. Market structure: historical warm-start + chart drawing

Raised by the user directly, 2026-09-19, while checking on `MarketStructureFeature` (D-60) live.

- [x] (2026-09-19) **Historical warm-start on activation** → D-64:
  pulls up to N closed bars (`Historical Warm-Start Bars` setting,
  default 100, configurable) from `ctx.getDataSeries()` at session
  start, feeds them through the same state machine as synthetic
  `BarEvent`s before any live event does. Found and fixed a real bug
  along the way: `DataSeries.isComplete()` returns `false` even for
  bars from many minutes ago on this jar, which silently discarded
  every warm-start bar in the first version (`0` fed against `646`
  available) — fixed by not depending on it at all, matching how the
  live `onBarClose` handler already works (position-based, not
  completeness-based). **Live-verified**: 100 real historical bars
  produced 8 genuine `PULLBACK_VALID` events, a `TJL_FORMED`, and a
  real `FLIP`, all correct.
- [x] (2026-09-19) **Draw market structure on the chart** → D-64:
  `redrawMarketStructureFigures()`, TJL1/TJL2/A+/SBR-RBS/DT-DB as
  Box+Label per `marketStructureRules.md` §8's color scheme. Full
  rebuild clean, redeployed, session healthy. **Not yet visually
  confirmed** — needs the user to look at the chart.

Both explicitly **not being picked up right now** — recorded per the
user's own instruction ("ADD THIS TO THE TODO") for later.

## 4b. Historical retention `[DESIGN ONLY, NOT STARTED]`

Laid out by the user 2026-09-18, deliberately **not implemented yet** —
their own instruction: record it so the plan doesn't get lost, build it
incrementally later if that's better for quality, don't build it all at
once now. Treat every item below as a real requirement to eventually
satisfy, not a suggestion — but none of it starts without an explicit
go, same as every other `[BUILD]` section in this file. Several items
below need their own design pass first (file format, exact storage
location, rotation mechanics) before any code — this section is the
requirements, not a plan.

- [x] (2026-09-20) **Raw log auto-deletion: 48 hours after creation** → D-75:
  `com.flow.journal.LogRetention` (flow-core), wired into
  `FlowRuntimeStudy.startSession()`. **Not yet live-verified** — runs
  only on an actual MotiveWave activation, market's closed. Original
  note (triaged
  2026-09-20 from a `temp.txt` scratch note, note now cleared). Raw
  session logs get automatically deleted 48h after creation; only the
  *analysed* outputs survive — the user's own list matches this
  section's existing per-construct bullets below almost exactly (price
  trace through VP, big trades, liquidity map snapshots, footprint
  chart data, VP histogram) — so this doesn't add new *kept* items, it
  adds the missing **deletion mechanism** itself. Connects to two
  already-open items rather than replacing them: D-07/D-35 left the raw
  tier's retention window at "2-3 days, exact figure TBD" — 48h is a
  firm, specific number that fits inside that range and is worth
  formally closing that TBD against, not a new decision on its own. The
  system-requirements snapshot (§7 below) separately noted retention is
  "still manual, not automatic — no code anywhere actually deletes old
  session directories" as of 2026-09-20; this is the concrete number
  that mechanism should use once built. Not yet designed or built —
  same "requirements, not a plan" status as the rest of this section.
- [ ] **Price trace**: last 5 trading days retained (not the current
  session-only scope `level_trace`/`zone_trace` already have).
- [ ] **Volume profile — two snapshot cadences, not one**:
  - Daily: one histogram + zones/levels snapshot per trading day.
  - Session-wise: 3-4 snapshots per day at session boundaries — Asia,
    London, New York, London+NY overlap. **Connects to an existing
    decision, not a fresh concept**: D-34 already defined these exact
    sub-session boundaries (Asia/London/NY), but explicitly as
    *informational labeling only* — "they do NOT create separate reset
    boundaries." This is a genuinely new use of that same boundary
    concept (triggering a snapshot capture), not just labeling anymore —
    worth resolving explicitly as its own decision when this is
    designed, not assumed to fall out of D-34 automatically.
- [ ] **Footprint**: last week retained, one row-set per bar with
  per-price-level ask/bid/delta — same raw granularity as live,
  D-52 applies (any merging stays a display/analysis-time concern, never
  stored). **Bar interval must be a configurable setting** (clarified by
  the user 2026-09-18: could be 30s, 1min, 5min — not hardcoded to 1
  minute).
- [ ] **Big trades**: a time-ordered series, retained (window not yet
  specified — assume "last week" matching footprint unless told
  otherwise when this is designed).
- [ ] **Liquidity map**: storage approach explicitly **undecided** — the
  user's own words: "to be discussed." Do not guess a format; ask when
  this is actually designed.
- [ ] **Market structure**: explicitly **NOT stored** — cheap to
  regenerate from retained historical 1-minute data whenever needed, so
  storing it separately would be redundant.
- [ ] **VWAP**: **will be stored**, computed accurately from live ticks
  (tick-weighted, same genuinely-accurate method D-36 already found in
  MotiveWave's own published source) — specifically *not* reconstructed
  later from 1-minute bar data, which the user flagged introduces "a
  very small inaccuracy" versus the real tick-weighted calculation. VWAP
  itself isn't built yet either (still queued after big trades per the
  D-50 construct order) — this is a requirement on however it eventually
  gets built, not a separate feature.

## 5. Strategies and forward testing

- [x] (2026-09-19) First real strategy → D-62:
  `MarketStructureLvnReversalStrategy`, `EveryTick` trigger (not
  `BAR_CLOSE` — deliberate, this build's point is stress-testing the
  live pipeline at its most demanding cadence). **Not unit-tested on
  synthetic sequences** — built and reasoned through directly per the
  user's explicit framing ("i dont need to test the strategy itself, i
  want to see if the system can hold everything together"); the
  event-sequence DSL (§3, still not built either) would be the natural
  way to do that properly later. A real bug was found and fixed in
  self-review before this ever ran live (see D-62's own writeup) — the
  strategy had no way to learn a `RiskChain`-blocked intent never
  actually took effect, and would have silently desynced from reality
  the first time `armed=false` (the default) blocked a real entry.
- [ ] Dry-run sessions → review journal against manual judgment. Next
  concrete step: select `market_structure_lvn_reversal` in the
  `Strategy Id` setting (currently still defaults to
  `level_zone_observer`) and watch `decisions.jsonl` for real
  `intent_changed`/`risk_verdict`/`reconcile_dry_run` records — this
  strategy's own logic has not been live-verified at all yet, only
  that building it didn't break anything already running.
- [ ] Make the `Strategy Id` setting a dropdown of registered strategies
  (`StrategyRegistrations.buildDefault()`'s own keys) instead of a free-
  text `StringDescriptor` — right now a typo'd id just silently fails to
  match anything registered, and the valid choices are only discoverable
  by reading `StrategyRegistrations.java` directly. Raised 2026-09-21,
  **explicitly not to be implemented yet** — the user doesn't want a
  redeploy/study-reload right now while a live Sim session is running
  armed.
- [ ] **Revisit: no software fallback if the bracket fails to close a
  position.** D-86 (2026-09-21) made the real bracket the sole mechanism
  that ever closes a position — deliberate, fixes two live near-misses
  from having a redundant software-side close racing it (D-84). But the
  user flagged the accepted tradeoff itself as worth revisiting, not
  settled: if both bracket legs somehow get rejected/cancelled by the
  broker with nothing left resting, the position now stays open with
  nothing but the daily-loss kill switch (D-85) or a human to end it.
  Needs a real design pass (e.g. detecting "both legs gone, position
  still non-flat" as its own explicit condition and re-submitting a
  fresh bracket, rather than either racing a second closer or doing
  nothing) — not decided yet, flagged only.
- [ ] Sim sessions (arming is a per-session act; confirm account out loud
  at every activation)
- [ ] Second real strategy — tests whether the layer boundaries hold;
  revisit package layout and `ZoneEntryStrategy` only after this (D-27)
- [x] (2026-09-20) Offline journal comparison tooling (Python OK) → D-76:
  `analysis/journal_summary.py` — single-session summary + two-session
  comparison, verified against real recorded sessions under `logs/`.
- [ ] Commit first recorded fixtures from real sessions (D-20)
- [~] **Backtest the finalized market structure rules — plain OHLC only,
  no order-flow execution modeling.** **Engine working end to end,
  4 exploratory runs done (2026-09-20, D-79/D-80) — explicitly
  hit-and-trial, not a conclusion, per the user's own framing; paused
  here to return to other work.**

  Extended (D-80) beyond the first run: `StopRule.ZONE_SIZE_MULTIPLE`
  (stop = a multiple of the touched zone's own width, not a fixed tick
  buffer) alongside the original `FIXED_BUFFER_TICKS`, and
  `aggregate(bars, intervalMs)` to build coarser bars (5-minute tried)
  directly from the already-exported 1-minute CSV rather than
  re-exporting from MotiveWave per timeframe. Both verified
  synthetically first (16 checks total in `MarketStructureBacktestTest`).

  | Run | Timeframe | Stop | R:R | Trades | Win% | avgR | maxDD(R) |
  |---|---|---|---|---|---|---|---|
  | 1 | 1m | 2 ticks fixed | 2:1 | 1206 | ~45.2% | +0.16 | 36.00 |
  | 2 | 5m | 1.5× zone size | 2:1 | 218 | ~22.5% | -0.30 | 70.00 |
  | 3 | 5m | 1.5× zone size | 1:1 | 234 | ~27.8% | -0.42 | 100.00 |
  | 4 | 1m | 1.0× zone size | 1:1 | 2016 | ~34.3% | -0.23 | 477.00 |
  | 5 | 1m | $3 fixed distance | 1:1 | 1013 | ~50.3% | +0.01 | 38.00 |

  Added a third stop convention for run 5, `StopRule.FIXED_PRICE_DISTANCE`
  (flat $-distance from entry, independent of the touched zone's own
  size — its own synthetic check added first). Run 1's fixed-tick-buffer
  stop is the only clearly positive result; the zone-size-based runs
  (2/3/4) were clearly negative; the flat-$-distance run (5) landed at
  breakeven. Not enough runs to call it a real pattern (no run isolates
  stop-convention as the only changed variable), but the one consistent
  thread so far.

  All three share the same entry filter (touch any tradeable zone, zero
  order-flow confirmation) — consistent with that filter alone not being
  a real edge on its own, which is exactly what
  `orderFlowExecutionRules.md`'s confirmation logic exists to add, not a
  finding against the market-structure rules themselves. Full output
  per run under `analysis/data/backtest_run_{1,2,3}*.txt`. Full detail
  and caveats in `decisions.md` D-79/D-80.

  Data source resolved (D-77): `HistoricalDepthProbe.java` (throwaway
  experiment, `../motivewave/experiments/`) found MotiveWave's own
  `DataSeries` caps at exactly the client-side "Max Linear Bars" chart
  setting (25,000 in this account) after raising that setting and
  manually scrolling the chart back — not an independent data-provider
  wall, so more may be reachable by raising it further (not pursued).
  `HistoricalOhlcExporter.java` (same experiments dir) dumped the loaded
  25,000-bar `@GC` 1-minute series (~24.2 days,
  2026-08-25T15:34Z–2026-09-18T21:00Z) to
  `analysis/data/GC_1m_1789910262328.csv`. MotiveWave-only, per the
  user's stated preference — the TradingView fallback wasn't needed.

  Entry rule as proposed (trade direction = current trend, enter on any
  bar touching a `tradeableLevels()` zone, stop 2 ticks beyond its far
  edge, target 2:1 R:R) is now actually implemented and run, not just
  proposed — no objection raised, proceeded on "let's jump on the
  backtest."

  `MarketStructureBacktest` (flow-core, `com.flow.backtest`, zero SDK)
  built, verified with `MarketStructureBacktestTest` (13 checks,
  including a real bug caught before real data was touched — a
  continuation bar dipping back into the zone it just formed, triggering
  entry a bar earlier than a test intended), wired into `build/build.sh`
  as an eighth gate.

  **First real run**: 734 valid pullbacks, 501 TJL pairs, 245 flips,
  1,206 trades (545W/631L/30 flat), totalR=189.00, avgR=+0.16/trade,
  maxDrawdownR=36.00 — full trade list in
  `analysis/data/backtest_run_1_output.txt`. Explicitly **not** a signal
  to trust yet: ~1 trade/20 bars is a loose entry filter by design (no
  order-flow confirmation at all, per this item's own framing), no
  costs/slippage modeled, and 24.2 days is thin for a swing system.
  Whether `analysis/data/`'s exported CSV + run output should be
  gitignored or committed is flagged, not decided (D-79).

  **Cross-repo note carried over from setting this up**: both this repo
  and `../motivewave/experiments` deploy into the same
  `MotiveWave Extensions/dev` directory and each wipes it before
  copying its own classes in — running FLOW_V2's `build/build.sh` again
  (e.g. to build a new feature) will silently remove the deployed
  experiment studies (`HistoricalDepthProbe`/`HistoricalOhlcExporter`)
  and vice versa. Caught this the hard way mid-session (see
  `decisions.md` D-77's trail) — worth remembering before either side
  runs its own build/deploy again while the other's study is still
  needed live.

  Original framing (raised by the user 2026-09-20,
  sequenced explicitly *after* the market-structure rework, the
  off-market forward-testing/historical-retention work, and only "if we
  have any tokens left" — not started, no design yet). **A deliberate,
  narrow carve-out from D-06's "no backtesting, forward-test only"
  stance, not a reversal of it**: D-06's rationale was that
  order-flow/tick-level entries don't survive bar-granularity replay
  honestly (MotiveWave's own optimizer fills are flagged optimistic, and
  this system's actual edge is tick/DOM-level). Pure market-structure
  logic (trend/pullback/TJL/flip, §1-§7 of `marketStructureRules.md`)
  is different — it's bar-close-only by construction (§9's
  non-repainting principle) and needs nothing tick-level to evaluate, so
  a plain-OHLC backtest of *just the structure logic* (not the eventual
  order-flow-confirmed entries `MarketStructureLvnReversalStrategy`
  layers on top) doesn't inherit D-06's original objection. Scope, once
  picked up: validate the reworked state machine (trend calls,
  TJL/A+/SBR/DT/DB formation, tradeable-level lifecycle) against
  historical OHLC directly, without needing footprint/big-trades/
  liquidity-map confirmation or live/Sim order placement at all. Needs
  its own design pass (data source — MotiveWave's own historical bars vs.
  an external OHLC feed; what "backtest" even means here given
  `ReplayHarness`/`ReplayEquivalenceTest` already exist for a different
  purpose — replay-equivalence, not signal backtesting) before any code.

## 6. Doc housekeeping

- [x] (2026-09-14) `CLAUDE.md` and `findings.md` still say "seven open
  questions" — there are eight (Q-01…Q-08)
- [x] (2026-09-14) `CLAUDE.md` directory structure still showed `app/`;
  now matches README's `flow-core/` + `flow-runtime/` + `build/` layout
- [x] (2026-09-14) `README.md` / `decisions.md` "Open questions" intro
  said system questions get experiments here — now states platform
  questions (Q-01/02/03/07/08) get a `../motivewave` experiment and system
  questions (Q-04/05/06) are decided directly, no experiment needed
- [ ] **"Problem Ledger" session-end format — proposed, not adopted**
  (triaged 2026-09-20 from a `temp.txt` scratch note, note now cleared).
  The user's idea: end each session with a short, structured note —
  `Solved` / `Decided` / `Disproved` / `Learned` / `Open` / `Next` —
  tracking actual intellectual progress rather than lines of code or
  commits ("Code is an output. Problems solved are progress."). This
  file's own "Where we left off" handoffs already informally cover
  similar ground but not in this exact structured shape. This is a
  workflow/process idea, not a FLOW_V2 code task — it may fit better as
  a `CLAUDE.md` convention than a `todo.md` item; recorded here per
  direct instruction rather than moved unprompted. Not yet adopted
  either way — needs an explicit decision on whether/where to use it.

## 7. System requirements snapshot `[MEASURE]` — assembled 2026-09-20, CPU still open

Raised by the user 2026-09-18 (scratch note in `temp.txt`, triaged into
here and the note cleared): "maintain a system requirement snapshot of
the current system — how much RAM, storage, and CPU will the system need
to run comfortably." Assembled this session (weekend, no live market) by
pulling together numbers already measured elsewhere plus everything
built since 2026-09-18 (D-60/61/62 market structure, risk chain, first
strategy; D-68/69/70 this session) — no new experiments needed except
CPU, exactly as this section originally predicted.

**RAM — bounded, no single holistic heap measurement, but every piece is
individually sized:**
- `SdkVolumeProfileFeature` (session-scoped VP): E-3 found ~1.1-1.2 MB/
  tick *unbounded* before the fix; the rotation mechanism (D-44, every
  150 ticks/3 min) bounds the PEAK just before a rotation to roughly
  150 × ~1.1-1.2 MB ≈ **~165-180 MB worst-case**, dropping back down
  every rotation as ticks merge into the small persistent bucket map.
  This is the dominant, and only large, RAM consumer in the system.
- Footprint (D-50): same engine, but bar-scoped and reset every bar
  close — peak is bounded by one bar's own tick count (typically far
  fewer than VP's 150-tick rotation window at 1-min bars), not
  independently measured but structurally much smaller than VP's peak.
- Big trades / order repeats (D-53–D-56): explicitly capped (500-entry
  recent windows, 5000-entry order-id LRU map) — low single-digit MB at
  most.
- Liquidity map (D-58): replaced wholesale each DOM update, never
  accumulates — bounded by book depth (~600-700 rows/side on `@GC`),
  well under 1 MB.
- Market structure (D-60), risk chain (D-61), session-boundary tracking
  (D-69) — all a handful of primitive fields and small lists (a handful
  of `Bar`/`ZoneRange` records, a 60s-window deque). Negligible
  individually. **One theoretical, not practical, gap noted while
  writing this**: `MarketStructureFeature.barsSincePair` only clears on
  a new TJL pair forming, not on a fixed cadence — an extremely long
  trend with no pullback ever validating would let it grow unbounded.
  Each `Bar` is 4 ints, so even thousands of accumulated bars costs
  under a few hundred KB — not a real RAM risk in practice, but flagged
  as the one construct without an explicit bound, unlike everything
  else in this list.
- **Net**: peak system RAM is dominated by VP's rotation cycle
  (~165-180 MB worst-case, just before a rotation), everything else
  combined adds low single-digit MB. No JVM/platform baseline included
  (MotiveWave's own footprint is out of scope for this snapshot).

**Storage — the "DOMListener isn't wired" premise this section
originally noted (2026-09-18) is now stale; D-58 wired it the same day.
Today's raw journal genuinely does carry top-of-book DOM, not just
ticks/bars:**
- Raw tier: D-35's ticks + top-of-book DOM measurement (~28.0 MB/hr raw,
  ~1.35 MB/hr gzip) now applies directly to what's actually running —
  `RawEventCodec` only ever encodes `DomEvent`'s top-of-book fields
  (D-58's own javadoc), matching that measurement's scope exactly. Full
  per-order DOM detail (~31.5 GB/hr raw) is NOT what gets written —
  that number only ever applied to the option D-58 explicitly declined
  (per-order tracking). For a full 24h session: **~670 MB/day raw,
  ~32 MB/day gzip**.
- Decisions tier: price trace (`level_trace`/`zone_trace`, D-48/D-49)
  ~310-330 KB/hour; liquidity snapshots (D-58) ~3.6 KB/snapshot × ~360/
  day (10s cadence) ≈ ~1.3 MB/hour, ~31 MB/day; heartbeat/risk_verdict/
  intent_changed records are sparse and cheap by comparison. **Net for a
  full 24h session: roughly ~40-50 MB/day.**
- Retention is still **manual**, not automatic — D-07/D-35 settled on a
  2-3 day rolling window *in principle*, but no code anywhere actually
  deletes old session directories under `logs/`; every activation's
  directory just accumulates until someone removes it by hand. Not a
  new decision, just restated plainly here since a requirements snapshot
  should say this out loud rather than imply retention is enforced.
- **New, previously-unflagged item found while assembling this**: the
  diagnostic-only file loggers (`market_structure_feature.log`,
  `big_trade_feature.log`, `order_repeat_feature.log`,
  `vwap_feature.log`, `volume_profile_feature.log`,
  `footprint_feature.log`, `liquidity_map_feature.log`) are opened in
  **append mode with no rotation or pruning at all** — unlike the real
  two-tier journal (which at least has a stated, if unenforced,
  retention policy), these throwaway validation logs just grow across
  every session forever at whatever their fixed path is. Low severity
  (they're explicitly diagnostic, not the system of record) but worth an
  occasional manual cleanup rather than assuming they're bounded.
- Q-10 (full per-order DOM retention policy, if ever wanted) and §4b's
  full historical-retention plan remain open/design-only — their
  eventual cost still isn't in this snapshot, unchanged from before.

**CPU — still genuinely unmeasured, unchanged from 2026-09-18, and this
weekend's market-closed constraint is exactly why**: E-3's "~1.2µs/tick
steady-state" is per-event processing latency, not a utilization/core
figure, and a meaningful profile needs real tick rates flowing through
the live pipeline — an idle/no-market profiling pass wouldn't represent
anything. Needs a live session once the market's open again.
