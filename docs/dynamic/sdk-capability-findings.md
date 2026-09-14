# MotiveWave SDK capability audit — what we reuse vs. what we build

Status: **originally desk research (2026-09-14), now partially live-verified
(2026-09-15).** Everything in §2 was originally sourced from the official SDK
Javadoc (generated 2026-07-02), the SDK Programming Guide, the MotiveWave user
docs, and the MotiveWave user forum. Tags follow the convention already used
in `../motivewave/docs/dynamic/findings.md`:

- `[DOC]` — stated in official documentation or Javadoc
- `[FORUM]` — stated by users or MotiveWave staff on the forum
- `[LIVE]` — confirmed by us against a running MotiveWave with our feed

**Live verification status (2026-09-15)** — full detail in
`../motivewave/docs/dynamic/findings.md` 2026-09-14/15 `[LIVE]` entries and
this repo's `decisions.md` D-31 through D-36:

| Experiment | Status | Headline result |
|---|---|---|
| E-1 (signature sweep) | **Done** | All of §2 resolves against our real jar except `TPOProfile`'s constructor (Javadoc mismatch, T-6, out of scope anyway). Ran live against a real instrument, zero exceptions. |
| E-2 (VolumeProfile parity) | **Done** | **Confirmed close match against the chart** once both are scoped to the same window — root cause of the one mismatch found (VAH off by 0.3) was the built-in study's "Use Historical Bars" option, not the engine. See D-36. |
| E-3 (memory/throughput) | Partial | Heap growth stayed modest across the runs so far (guard never tripped), but duplicate-instance contamination (see below) muddies precision — no clean full-session measurement yet. |
| E-4 (footprint/imbalance) | Partial | Bar-scoped profile rows and imbalance flags captured live at two threshold settings; not yet formally diffed row-by-row against the chart's footprint. |
| E-5 (AggregateFilter/big trades) | Partial | `aggByOrder=true` repeat-emission confirmed live with real non-zero order IDs (T-3 real). One wrinkle: some emissions repeat on `exchOrderId=0`, which looks like a "no ID" sentinel rather than a genuine match — filter those before trusting a repeat count. |
| E-6 (VAMethod accessibility) | **Done** | Confirmed dead from `jar tf` alone: `com.motivewave.platform.common` doesn't exist anywhere in our jar. Only `getValueArea(double)` is usable. |
| E-7 (swing stability) | Partial | Observed live: swings revise substantially at low strength, occasionally stabilize at higher strength — real instability, not yet formally characterized into a rule. |
| E-8 (secondary timeframe) | **Done** | Confirmed `NULL` live — `ctx.getDataSeries(BarSize.minute(5))` returns null on our jar, matching T-4's forum report. |
| E-9 (DOM history) | Partial | Logged every heartbeat (snapshot count/depth) throughout, not yet analyzed for cadence/retention specifics. |
| E-10 (studies source bundle) | **Done** | Found a better source (`github.com/MotiveWave/motivewave-studies`, git-clonable) than the forum's Drive link. VWAP confirmed tick-weighted but uses an internal class not in our public jar. No footprint/big-trades/heatmap source exists. **`sdk.profile.*` and `AggregateFilter` have zero usage anywhere in MotiveWave's own published studies** — E-2/E-5's live confirmation is the only evidence either is trustworthy. `calcSwingPoints` does have real usage (4 studies). |
| E-11 (raw capture / Q-03) | **Done** | See D-35 — closed for ticks/top-of-book, spun off Q-10 for full per-order DOM retention policy. |
| E-12 (OrderContext retention / Q-02a) | **Done** | See D-31 — read-only retention confirmed safe off-thread. |

**Headline conclusion (2026-09-15, see D-36): do not build a custom volume
profile from scratch.** The SDK's `sdk.profile.VolumeProfile` engine,
wrapped directly, produces output that matches the chart once scoping is
apples-to-apples — confirmed live, not assumed from docs. This is the answer
to this document's original §1 question for volume profile and (by the same
engine, per §2.2) footprint. Still genuinely open: the liquidity heatmap
(§2.8 — built-in `Order Heatmap`/`DOM Power` visual quality not yet checked
against what a custom MBO-fed heatmap would need to beat) and Q-10 (raw
journal DOM tier retention policy, spun off from E-11).

**Recurring theme worth carrying forward**: every debugging session this
audit produced (figure-drawing threading, the `autoEntry`/`manualEntry`
dead end, zombie background threads) was resolved by **finding and matching
a real, confirmed-working example** — either in MotiveWave's own published
source or by direct live testing — never by reasoning further from Javadoc
alone. Javadoc here is a starting hypothesis, not a source of truth; treat
every signature and every "should work" as unverified until it's actually
run.

Intended reader: whoever (human or agent) is implementing FLOW_V2. Read this
alongside `README.md` and `docs/dynamic/decisions.md`; where this document
and those disagree, this document is newer, and the disagreements are listed
explicitly at the end.

---

## 1. The question this answers

The user's own analysis toolkit, as actually traded today:

| Tool | Where it comes from today |
|---|---|
| Volume profile | MotiveWave built-in study |
| Footprint | MotiveWave built-in study |
| Big trades | MotiveWave built-in study |
| VWAP | MotiveWave built-in study |
| Liquidity heatmap | **Bookmap** — no API access, licence is not ours |
| Market structure | User's own method |
| GEX levels | External, computed elsewhere, plugged in by hand |

The user's hypothesis was: *everything except market structure and the
heatmap can come out of the MotiveWave SDK out of the box.*

**That hypothesis is correct, and is an underestimate in one place
(footprint) and an overestimate in one place (VWAP).** Details below.

---

## 2. What the SDK gives us

### 2.1 Volume profile — available in full `[DOC]`

`com.motivewave.platform.sdk.profile.VolumeProfile`, a public class extending
`Profile` and implementing `TickOperation`.

```java
VolumeProfile(long startTime, long endTime, Instrument instr, int rangeTicks)
void   onTick(Tick tick)              // feed it; it accumulates incrementally
```

Available output:

```java
VolumeRow getPOC()
float     getPOCMidpoint()
void      updatePOC()
int[]     getValueArea(double per)     // e.g. 0.70
float     getVAHigh(int[] va)
float     getVALow(int[] va)
int[]     getHVNs(int sensitivity)
int[]     getLVNs(int sensitivity)
List<VolumeRow> getRows()
VolumeRow find(float price)
double    getTotalVolume()  getTotalDelta()  getTotalBidVolume()  getTotalAskVolume()
double    getDeltaPer()  getDeltaChange()  getVolumePerSecond()
double    getMaxVolume()  getMinVolume()  getMaxDelta()  getMinDelta()
double    getMaxRowDelta()  getMinRowDelta()  getMaxBidVolume()  getMaxAskVolume()
float     getHighPrice()  getLowPrice()  getOpenPrice()  getLastPrice()  getMidPrice()
VolumeProfile getPrev()  getNext()        // profiles can be chained (prior session, etc.)
boolean   isComplete()  void setComplete(boolean)
```

Trade filtering is built in:

```java
void  setFilterTrades(boolean b)
void  setMinTradeSize(float s)
```

`SummaryProfile extends VolumeProfile` also exists, presumably for
composite/summary profiles. Unexplored.

**Conclusion: write no volume profile code.** Instantiate, feed, read.

### 2.2 Footprint — available, under a different name `[DOC]`

This was underrated in the original plan. A footprint bar is a per-bar
volume profile split by aggressor side, and that is exactly what `VolumeRow`
is:

```java
double getVolume()  getAskVolume()  getBidVolume()  getDelta()
List<Tick> getAskTrades()   List<Tick> getBidTrades()
float  getStartPrice()  getEndPrice()  getRowPrice()
boolean contains(float price)
boolean isPOC()  isUAHigh()  isUALow()
boolean isBidImbalance(double per, int delta, boolean useDelta)
boolean isAskImbalance(double per, int delta, boolean useDelta)
void   addFrom(VolumeRow row)          // merge rows (aggregate profiles)
```

So:

- **A footprint = one `VolumeProfile` per bar**, with `rangeTicks` set to the
  footprint row size (1 for tick-by-tick footprint).
- **Diagonal/bid-ask imbalance detection is a native method call**, not
  something we implement. `isBidImbalance`/`isAskImbalance` take a percentage
  threshold, a delta threshold, and a flag choosing between them.
- Unfinished-auction / single-print style logic is partly there too via
  `isUAHigh()`/`isUALow()` (UA almost certainly = Unfinished Auction — verify).

**Conclusion: write no footprint aggregation and no imbalance detection.**
Write only the bar-boundary lifecycle (open a profile, close it, keep the
last N) and whatever higher-level pattern logic sits on top (stacked
imbalances, absorption).

### 2.3 Big trades — available, and stronger than a naive size filter `[DOC]`

`com.motivewave.platform.sdk.common.AggregateFilter`, implements
`TickOperation`:

```java
AggregateFilter(boolean aggByOrder,   // aggregate using the aggressor order id
                long aggPeriod,       // ms window for aggregation, 0 = none
                float minSize,        // 0 = no minimum
                float maxSize,        // 0 = no maximum
                TickOperation op)     // called for ticks meeting the criteria
void onTick(Tick tick)
void reset()
AggregateFilter withOp(TickOperation op)
void setOperation(TickOperation op)
```

The Javadoc on the class states that it was adjusted (Aug 2020) to account
for ticks that include the exchange order id, that **if this id is available
it is used to aggregate, ignoring the aggregation period**, and that this is
**currently Rithmic live data only**.

Rithmic is our feed. This matters: it means a 300-lot aggressive sweep that
executes as forty separate fills against forty resting orders is aggregated
back into **one logical trade by aggressor order id**, which is the actual
definition of a "big trade" that matters for order flow — as opposed to
"one print happened to be large."

It also composes: `AggregateFilter` is itself a `TickOperation`, so it can be
chained in front of a `VolumeProfile` to build a profile of big trades only.

**Conclusion: write no big-trade detection.** Configure an `AggregateFilter`.

### 2.4 Delta and cumulative delta — free with the profile `[DOC]`

Covered by `VolumeProfile.getTotalDelta()`, `getDeltaPer()`,
`getDeltaChange()`, `getMaxRowDelta()`, `getMinRowDelta()` and
`VolumeRow.getDelta()`. Session cumulative delta is a session-scoped
`VolumeProfile`; per-bar delta is a bar-scoped one.

Note the skeleton study in `../motivewave` already computes live aggressor
delta correctly by hand — that code can be dropped in favour of this, or
kept as the parity check against it.

### 2.5 TPO — available if wanted `[DOC]`

`TPOProfile`, `TPORow`, `TPOCell` exist in the same package.
`TPORow` exposes `getVolume()`, `getAskVolume()`, `getBidVolume()`,
`getDelta()`, `getCells()`, `isPOC()`. Not currently in scope, but noted so
nobody rebuilds it later.

### 2.6 Market structure — swings free, interpretation ours `[DOC]`

`DataSeries` provides:

```java
List<SwingPoint> calcSwingPoints(int strength)
List<SwingPoint> calcSwingPoints(int strength, int lookback)
List<SwingPoint> calcSwingPoints(boolean b, int strength)         // variants exist
```

`SwingPoint` gives the bar index, start time, value (high for tops, low for
bottoms), `isTop()`/`isBottom()`, `getStrength()`, and left/right bar counts.
`DataSeries` also provides `atr(...)` in several overloads, highest/lowest
over a range, and moving averages.

**Conclusion:** use `calcSwingPoints` as the swing primitive rather than
writing pivot detection, then implement the user's own structure
interpretation (BOS/CHoCH/ranges/whatever the method is) on top. Using their
swing definition also means our structure agrees with what's drawn on the
chart, which matters when the user is eyeballing a trade the system took.

### 2.7 VWAP — **no SDK class**, but source is published `[DOC]` `[FORUM]`

There is no VWAP type anywhere in the SDK class index. However:

- The SDK Programming Guide states that all studies and strategies built into
  MotiveWave were programmed using the SDK and that **the source code for
  these is freely available** and may be used as examples or starting points.
- A forum thread asking specifically for the VWAP source confirms it **is**
  in the published SDK resources, filed under the "MA" folder.
- The pinned SDK resources forum thread links a bundle described as source
  code for **more than 275 publicly available studies and strategies** within
  MotiveWave (Google Drive link on that thread), alongside
  `MotiveWave_Studies.zip` (the Eclipse sample project) and `sdk_api_doc.zip`
  from `motivewave.com/sdk.htm`.

**Conclusion: do not write VWAP from scratch.** Obtain the studies source
bundle, lift their VWAP implementation (including the standard-deviation band
logic), adapt it to our feature interface. This also guarantees our VWAP
matches the chart line the user actually trades off.

Caveat already recorded in `decisions.md` D-22: VWAP computed from bars is an
approximation (typical price × volume per bar) versus true tick-weighted
VWAP. Check which the MotiveWave implementation does before assuming.

**Also worth checking in that same source bundle** before writing anything:
one 2023 forum thread notes the "Big Trades" study source was *missing* from
the published set at that time, so coverage is not guaranteed to be
complete — but the 275-study bundle is more recent (thread activity into late
2025) and may now include it.

### 2.8 Liquidity heatmap — ours to build, with a free warm start `[DOC]`

Bookmap's API is not available to us (licence not ours). MotiveWave does
retain DOM history:

```java
// com.motivewave.platform.sdk.common.Instrument
List<DOMSnapshot> getDOMHistory()        // history since the DOM was last opened
DOMSnapshot       getLatestDOMHistory()  // most recent entry
```

`DOMSnapshot` is documented as containing historical DOM information at a
specific time, with `getBidPrices()`/`getBidSizes()`/`getAskPrices()`/
`getAskSizes()` arrays.

The user-facing docs add context:

- The Volume and Order Flow guide states all depth-of-market data is **live
  only, no historical values are available from the exchange**, and that
  MotiveWave accumulates historical DOM data **at 1-second intervals in
  memory** the first time a DOM panel or a study using DOM data is opened.
- The built-in **Order Heatmap** study displays historical DOM sizes
  **captured at the end of the bar**, shaded by relative size.
- The built-in **DOM Power** study displays historical bid/ask sizes, with
  the same note that historical DOM is built in memory and recording begins
  when the study or a DOM panel is opened.

So MotiveWave's own heatmap is aggregate row sizes at 1-second (or
bar-close) granularity, accumulated forward from when something opened the
DOM. That is **not** Bookmap-grade: no per-order resolution, no sub-second
timeline.

**Conclusion:** build the liquidity map ourselves from the live MBO DOM
stream as originally planned — we already confirmed `[LIVE]` in
`../motivewave/docs/dynamic/findings.md` that we get true Market-by-Order
depth with individual resting orders and ~600–700 price rows per side on
`@GC`. Use `getDOMHistory()` as (a) a warm start when the runtime attaches
after a DOM panel has been open, and (b) a cross-check against our own
reconstruction. Do not treat it as the source of truth.

### 2.9 GEX — external, unchanged

No SDK involvement. Stays as designed in `decisions.md` D-08/D-18: a
hand-edited file store, loaded at session start, surfaced on `MarketState`
like any other feature.

---

## 3. What we do NOT do: read another study's output

Worth stating explicitly so nobody tries it.

There is **no supported API to reach into another study instance on the chart
and read its computed values**. A forum thread on using proprietary studies
in a strategy concludes this, and the only workaround anyone has found is
routing a value through an `InputDescriptor` and pulling it out of the
`DataSeries` by exported key — a technique its own author describes as
imperfect.

Separately, there is a `[FORUM]` report of real data corruption in
multi-instrument `DataSeries` access: a strategy running simultaneously on ES
and CL where the ES instance received CL's series, producing wrong entry
prices. And a 2026 report that
`DataContext.getDataSeries(BarSize)` returns **null** for a secondary bar size
on 7.0.28 even when that size is backfilled elsewhere in the workspace.

We don't need any of it: the engine classes in §2 are instantiated and fed by
us directly, which is strictly better than observing someone else's instance.

---

## 4. Traps — read before writing code

These are the things that will waste time if discovered late.

**T-1. `VolumeRow.getAskTrades()`/`getBidTrades()` return `List<Tick>`.**
That implies the row retains every tick that hit it. At `@GC` MBO rates, a
session-length `VolumeProfile` holding every tick per price row is a serious
memory problem, and it also conflicts with D-16's allocation-free hot path.
Determine whether these lists are populated unconditionally or only under
some setting. If unconditional, do not keep long-lived profiles alive, or do
not use `VolumeProfile` for session-scope work.

**T-2. `getValueArea(double, VAMethod)` takes a type outside the SDK
namespace.** The signature is
`getValueArea(double per, com.motivewave.platform.common.Enums.VAMethod method)`
— note `platform.common`, **not** `platform.sdk.common`. That package is not
part of the documented SDK surface and may not be resolvable from our code at
all. Assume only `getValueArea(double)` is usable until proven otherwise.
This matters because value-area method was flagged (in FLOW) as the known
source of VAH/VAL disagreement.

**T-3. `AggregateFilter` can emit the same tick more than once.** The class
doc says that when the exchange order id is available, aggregation by that id
"may cause the same tick to be emitted multiple times with an updated size."
Our event stream must treat these as **updates to an existing logical trade,
not as new trades**, or big-trade counts and any delta derived from them will
double-count. This has a direct consequence for the sequenced event stream in
D-10: a `BigTrade` event needs an identity (the order id) and an
update-or-insert semantic, not append-only.

**T-4. Secondary-timeframe `DataSeries` may be unusable.** See §3. If market
structure needs a higher timeframe than the chart, verify early; the fallback
is aggregating bars ourselves from the primary series or from ticks.

**T-5. DOM history only exists once something opened the DOM.** Both the DOM
Power and Order Heatmap docs say recording begins when the study or a DOM
panel is opened. So `getDOMHistory()` gives nothing on a cold start and is
not a substitute for the forward-only readiness class in D-22.

**T-6. Javadoc ≠ our jar.** The Javadoc read for this audit is the public one
generated 2026-07-02. The SDK jar we compile against is whatever version we
have locally. Every signature in this document must be checked against our
actual `mwave_sdk.jar` before being relied on.

---

## 5. Experiments to run — **all out-of-the-box SDK features must be
verified before we build on them**

This is the core instruction that comes out of this audit. **None of section
2 is `[LIVE]`.** Every SDK capability we intend to lean on gets a throwaway
diagnostic study in `../motivewave/experiments/`, run against our real feed
on `@GC`, with the result written into
`../motivewave/docs/dynamic/findings.md` and tagged `[LIVE]`. Only then does
FLOW_V2 code depend on it.

Rationale: the entire value of "reuse instead of rebuild" evaporates if we
build the architecture around a class that turns out to behave differently at
MBO rates, or to be unavailable in our jar version, or to need a `DataSeries`
we can't get. Cheap to check now, expensive to discover in week six. The
existing hard rule from `../motivewave/CLAUDE.md` applies throughout: these
are **read-only** diagnostics, they place no orders, and Sim Trade Only stays
on.

Run them in this order — each one either unblocks or redirects real design
work.

**E-1. Signature and availability sweep.** Compile a study against our actual
`mwave_sdk.jar` that imports and instantiates every type in §2:
`VolumeProfile`, `SummaryProfile`, `VolumeRow`, `TPOProfile`,
`AggregateFilter`, `SwingPoint`, `DOMSnapshot`. Confirm every method
signature quoted above actually exists with that shape. Closes T-6 for all of
them at once, and it's a compile, not a run.

**E-2. VolumeProfile parity against the chart.** Instantiate a session-scoped
`VolumeProfile` with `rangeTicks` and value-area percentage matched to the
Volume Profile study the user actually has on their chart. Feed it live ticks
for a session. Journal POC / VAH / VAL / HVN / LVN on a cadence. Diff against
what's drawn. Report where they diverge and under which settings. This
replaces the old "custom vs built-in comparison" framing — there is one
engine, so this is a **configuration parity check**, not a bake-off.

**E-3. VolumeProfile memory and throughput at MBO rates.** Run a
session-scoped profile plus one bar-scoped profile per bar for a full RTH
session on `@GC`. Measure heap growth and per-tick processing time. Answers
T-1 directly. If `getAskTrades()`/`getBidTrades()` retain everything, this is
where we find out and decide whether `VolumeProfile` is viable for
session-scope work or only for bar-scope.

**E-4. Footprint and imbalance behaviour.** Bar-scoped `VolumeProfile` with
`rangeTicks = 1`. Verify `getAskVolume()`/`getBidVolume()` split matches the
chart's footprint bar. Verify `isBidImbalance`/`isAskImbalance` fire on the
same rows the chart marks, and work out what `per`, `delta` and `useDelta`
actually mean by varying them. Determine what `isUAHigh()`/`isUALow()` mean.

**E-5. AggregateFilter with the Rithmic order id.** Confirm `aggByOrder=true`
actually aggregates by exchange order id on our feed. Log every emission with
its order id and size, and **specifically confirm T-3**: does the same
logical trade get emitted repeatedly with a growing size? Capture a real
sweep and show the aggregation. This determines the shape of the `BigTrade`
event type in `flow-core`, so it blocks event-type design.

**E-6. Value area method accessibility.** Try to reference
`com.motivewave.platform.common.Enums.VAMethod` from a study compiled against
our jar. Compiles or doesn't. Closes T-2.

**E-7. Swing point behaviour.** Call `calcSwingPoints` at several strengths on
a live chart, draw the results, compare to what the user considers a swing.
Determine whether swings are stable once formed or can be revised as new bars
arrive (this matters a lot for structure logic and for replay determinism
under D-11).

**E-8. Secondary timeframe availability.** Attempt
`ctx.getDataSeries(BarSize.getBarSize(n))` for a higher timeframe and see
whether it returns null on our version. Closes T-4.

**E-9. DOM history shape and cadence.** Call `getDOMHistory()` after a DOM
panel has been open for several minutes. Measure: how many snapshots, at what
interval, how deep, and how far back does it retain. Compare one snapshot
against our own live DOM reconstruction at the same timestamp. Closes T-5 and
tells us whether warm-starting the liquidity map from it is worth the code.

**E-10. Studies source bundle inventory.** Obtain the 275-study source bundle
from the pinned forum thread. Inventory what's actually in it, specifically:
VWAP (confirmed present under "MA"), Volume Imprint / footprint, Big Trades,
Order Heatmap, DOM Power, TPO. For each one present, that's a reference
implementation showing how MotiveWave themselves drive these engine classes —
which is worth more than the Javadoc for questions like "how do they scope a
profile to a session" or "what settings do they expose and why."

**E-11. One hour of raw `@GC` capture** (already queued as Q-03 in
`decisions.md`, restated here because it belongs in the same session as
E-3/E-5). Record event counts — trades, DOM updates, `DOMOrder` entries per
update — alongside bytes, uncompressed and gzipped, to fix the raw journal
encoding and retention window.

**E-12. `OrderContext` retention and thread affinity** (already Q-02).
Read-only stage only for now: retain the context from `onActivate`, call read
methods from a later callback and from a timer thread, log results plus
`System.identityHashCode(ctx)`. Zero risk. The order-placement stage stays
gated behind explicit in-the-moment confirmation.

---

## 6. What this changes in the FLOW_V2 architecture

Assuming the experiments confirm §2, these are the consequences. Do not
implement these until the relevant experiment is green.

**6.1 `flow-runtime` gets much bigger than the README implies.**
`VolumeProfile`, `VolumeRow`, `AggregateFilter` and `SwingPoint` are all SDK
types under `com.motivewave.platform.sdk.*`. D-05 and D-09 forbid strategies
(and therefore `flow-core`) from importing them. So every one of these
engines is **owned, instantiated and fed in `flow-runtime`**, and `flow-core`
sees only our own read-only interfaces over them:

```
flow-core      VolumeProfileView, FootprintView, BigTradeEvent, SwingView, ...
                 (pure interfaces + value types, no SDK import, compiles on plain JDK)
flow-runtime   SdkVolumeProfileFeature      wraps com...profile.VolumeProfile
               SdkFootprintFeature          wraps a per-bar VolumeProfile
               SdkBigTradeFeature           wraps AggregateFilter
               SdkSwingFeature              wraps DataSeries.calcSwingPoints
               LiquidityMapFeature          ours, built from the live DOM
               VwapFeature                  adapted from MotiveWave's source
```

**The real design work is therefore the interface set, not the feature math.**
Getting `VolumeProfileView` right — what a strategy is allowed to ask, in our
vocabulary, in integer ticks per D-21 — is the thing to spend care on.

**6.2 These engines are already the right shape.** `VolumeProfile`,
`VolumeRow` and `AggregateFilter` all implement `TickOperation` with
`onTick(Tick)`. That is exactly the incremental, event-fed, always-consistent
shape D-16 requires. They slot into the sequencer's drain loop directly: the
ingest adapter converts an SDK `Tick` into our event **and** forwards it to
the SDK engines, in that order, on the single pipeline thread.

**6.3 D-26 is wrong as written and needs rewriting.** It describes two
competing implementations — `CustomVolumeProfile` (ours) versus
`BuiltInVolumeProfile` (reading MotiveWave's study) — to be compared. There
is only one engine, and reading another study is not possible (§3). D-26
becomes: *we use the SDK's `VolumeProfile`, wrapped behind a core-side view;
the open question is settings parity with the chart, checked by E-2.*

**6.4 Q-08 is wrong as written and becomes E-2.** "Can we read the built-in
volume profile study's values" is answered: no, and we don't need to.

**6.5 D-22's readiness classes need one adjustment.** Volume profile was
listed as warmable from historical bars. If we use the SDK `VolumeProfile`,
it is fed by **ticks**, so warming it means replaying historical ticks
through it, which moves it from readiness class 2 to class 3 (warmable only
from tick history). Confirm in E-3 whether replaying a session's ticks
through it at startup is fast enough to be practical.

**6.6 The build list shrinks to four things.** Market structure
interpretation on top of `calcSwingPoints`; the liquidity map from the live
MBO DOM; the external GEX/config store; VWAP adapted from MotiveWave's
published source. Everything else in the order-flow half is configuration and
wrapping.

---

## 7. Summary table

| Capability | Source | Confidence | Our work |
|---|---|---|---|
| Volume profile (POC/VA/HVN/LVN) | `sdk.profile.VolumeProfile` | `[DOC]` | wrap + configure |
| Footprint (per-bar bid/ask split) | `VolumeProfile` per bar + `VolumeRow` | `[DOC]` | bar lifecycle only |
| Footprint imbalance | `VolumeRow.isBid/AskImbalance` | `[DOC]` | none |
| Big trades | `sdk.common.AggregateFilter` | `[DOC]` | configure + dedupe (T-3) |
| Delta / cumulative delta | `VolumeProfile` / `VolumeRow` | `[DOC]` | none |
| TPO | `sdk.profile.TPOProfile` | `[DOC]` | out of scope |
| Swing points | `DataSeries.calcSwingPoints` | `[DOC]` | structure logic on top |
| ATR, MAs, highest/lowest | `DataSeries` | `[DOC]` | none |
| VWAP | MotiveWave published study source | `[FORUM]` | adapt their code |
| Liquidity heatmap | live MBO DOM (ours) + `getDOMHistory()` warm start | `[LIVE]` feed / `[DOC]` history | build |
| Market structure | ours | — | build |
| GEX levels | external file store | — | build (trivial) |
| Reading another study's output | **not possible** | `[FORUM]` | don't attempt |

---

## 8. Immediate next actions

1. Run **E-1** (compile-time signature sweep). Half a day, unblocks
   everything, needs no market open.
2. Run **E-10** (get the studies source bundle and inventory it). No market
   needed either, and it may answer several other experiments by example.
3. Schedule one live `@GC` session covering **E-2, E-3, E-4, E-5, E-9, E-11**
   together — they all want the same thing: a running diagnostic study on a
   live session — plus **E-12**, which is read-only and free to include.
4. Write every result into `../motivewave/docs/dynamic/findings.md` as
   `[LIVE]`, then amend `README.md` and `docs/dynamic/decisions.md` per §6.
5. Only then start the walking skeleton.
