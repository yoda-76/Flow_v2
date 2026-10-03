# Backtest engine — closing the gap (plan, not yet built)

Written 2026-10-02, after an audit of what actually exists (`todo.md` and `decisions.md` D-06/D-77/D-79/D-80 told
the story before code was read; this re-verifies against the real files, per this project's own rule: never report
a claim you haven't just checked). **Scope resolved by the user 2026-10-03** — see "Scope (resolved)" below; this
replaces the original ⚠️ AMBIGUOUS scope question and the D-43 blocker that used to sit at the top of this doc.

## Honest current state (verified 2026-10-02, not re-derived from memory)

Two separate, unconnected things exist, wearing similar-sounding names. Neither is "the backtest engine, needs a
few fixes" — read both sections before assuming either is close to done.

**1. `ReplayHarness` / `ReplayEquivalenceTest` / `RecordingReplayTest`** (`flow-core/src/com/flow/core/`) —
real, built, live-verified. Reads a single session's `raw.jsonl` top to bottom, feeds it through the real
`Pipeline` + strategy + features, and asserts the resulting `Intent` sequence is bit-identical to what the live run
produced. **Not part of this plan, and not being discarded or deprioritized** — it's a separate, ongoing piece of
infrastructure with its own real job: replaying/monitoring whatever strategy is already running in forward test on
the cloud VM, to watch its behavior and inform order-flow execution analysis (the user's own framing, 2026-10-03).
This doc's engine answers a different, earlier question — is a candidate strategy's signal logic worth forward-
testing at all — on 1-minute OHLC, before order flow or `ReplayHarness` ever enter the picture for that strategy.

**2. `MarketStructureBacktest`** (`flow-core/src/com/flow/backtest/`) — real, built, 16 synthetic tests, wired
into `build/build.sh` as the eighth gate. The only thing that actually produces trades/PnL today. Reads a plain OHLC
CSV (`timestampMs,open,high,low,close,volume`), feeds it through the real `MarketStructureFeature`, simulates a
single hardcoded entry/stop/target rule against bar highs/lows, and produces wins/losses/totalR/avgR/maxDrawdownR.
5 exploratory runs done (D-79/D-80), explicitly "hit-and-trial, not a conclusion," then paused. **This is the tool
this plan generalizes** — see "Proposed architecture" below.

**Bottom line (still true)**: `D-06` ("no backtesting, forward-test only") was carved around once, narrowly, for
market-structure-only logic. The work below extends that same carve-out to *any* bar-close strategy, by design —
see the decision to record at the end of this doc.

## Scope (resolved 2026-10-03 by the user — supersedes the original ⚠️ AMBIGUOUS block and the D-43 blocker)

- **Order-flow execution is explicitly OUT of scope for backtesting, parked, not abandoned.** We cannot get
  tick/DOM-level historical data from MotiveWave, so there is no honest way to backtest footprint/absorption/
  liquidity-map-confirmed entries. The "Replay-inside-MotiveWave" (D-43) design problem that used to block this plan
  is **parked** — a separate, later item, not part of this engine. Order flow stays what it already was: the
  **execution layer** used once a strategy is promoted to forward testing (Sim), never part of backtesting.
- **This engine tests strategies on 1-minute OHLC bars only.** No tick data, no DOM, no footprint/VWAP/volume
  profile/big-trades/liquidity-map features — those don't exist in this mode at all.
- **Not dependent on the VM's own recordings.** Historical data comes directly from MotiveWave's own chart (the
  same technique D-77 already proved: lower the chart's "Max Linear Bars" setting, scroll back, export) — **final
  result, 2026-10-03: ~6.2 years, 2020-07-12 → 2026-10-02, 2,193,481 bars** (`analysis/data/GC_1m_latest.csv`),
  far beyond the 24.2-day CSV D-77 originally exported — the number grew through several re-exports as the user
  kept scrolling further back (see §7 below and the checklist for the full trail). This replaces "wait for the
  VM to accumulate raw.jsonl history" entirely for this purpose. **MotiveWave on the dev machine froze while
  scrolling for even more** (reached April 2020 before hanging) — left alone rather than force-restarted; see
  `todo.md`'s "loose end" note for what to check if it recovers.
- **Must be generic across many strategies, not just market structure.** The user plans to test "a lot of different
  strategies" through this tool — the engine is the pluggable, reusable part; each strategy's bar-close logic is a
  plug-in, the same philosophy the live system already uses for `FlowStrategy` (README: "new strategy touches one
  box").
- **Risk chain: yes, build it — logic that doesn't need tick data.** Daily loss limit, size cap, max reversals,
  rate limit, min dwell — all evaluable from bar timestamps and simulated fills alone, no order-flow dependency.
  (Live-only guards — lag/queue-depth — don't apply to a backtest and are excluded.)
- **Reports: needed.** Trade list, win rate, PnL/R, drawdown, what the risk chain blocked and why.
- **Purpose, stated by the user directly — keep this in view, it's what "minimal scope" means here**: *"the main
  focus is not the replay on the chart, the main focus is how effective the strategies [are] before applying the
  execution engine... this is just the first checkpoint in onboarding a new strategy... ultimately we can only
  forward-test a limited number of strategies with our current resources."* This is a **screening filter** — does a
  strategy's setup/signal logic show any edge at all on years of historical bars — not a replacement for forward testing, not
  a visual/replay tool, not an order-flow-execution model. Don't let the build grow past that.
- **Explicitly parked for later** (the user's words: *"park it, we'll come back to it, that's a different thing"**):
  D-43 / Replay-inside-MotiveWave / any order-flow backtest. Do not fold this into the current build.

## Proposed architecture — generalize `MarketStructureBacktest`, don't rebuild it from zero

`MarketStructureBacktest` already has the two hardest pieces working and tested: reading/aggregating an OHLC CSV,
and simulating stop/target fills against bar highs/lows (including the subtle "no same-bar re-entry" rule, and the
real bug it already caught — a continuation bar re-triggering entry a bar early). The gap is that the entry logic
is hardcoded to one rule instead of being a plug-in, and there's no risk chain, no multi-strategy comparison, and
no reusable report.

1. **`BacktestStrategy` interface** (new, `flow-core/src/com/flow/backtest/`) — the pluggable seam. Minimal and
   bar-close-only, mirroring `FlowStrategy`'s shape without anything tick/order-flow-dependent:
   ```java
   public interface BacktestStrategy {
     String id();
     Signal onBar(BacktestBarState state);  // called once per closed bar; state exposes OHLC history +
                                              // whatever bar-level features (e.g. MarketStructureView) the
                                              // strategy's own constructor wires up — same self-contained-feature
                                              // pattern MarketStructureFeature already uses.
   }
   ```
   `Signal` = direction + stop + target (or none), same shape `MarketStructureBacktest`'s current hardcoded rule
   already produces — extract it rather than inventing a new one.
2. **Extract today's hardcoded market-structure entry rule into the first concrete `BacktestStrategy`
   implementation.** This is a refactor, not new logic — the exact same rule, now behind the new interface. Use it
   as the regression check for step 3 (below): the generalized engine run with this strategy must reproduce run 1's
   exact numbers (1,206 trades, totalR=189.00, avgR=+0.16, maxDrawdownR=36.00, D-79) before trusting the refactor.
3. **`BacktestEngine`** (new, generalizes what `run()` already does) — takes bars + a `BacktestStrategy` + a risk
   config, drives the same bar-by-bar loop (manage open position against this bar's range, feed the bar to the
   strategy, check for an entry), but entry/exit decisions come from the strategy plug-in instead of inline code.
4. **Risk chain, ported** (new) — a cut-down, bar-clock version of the live risk chain's non-tick-dependent filters:
   daily loss limit, max contracts/size cap, max reversals per session, rate limit per minute, min dwell. Reuse
   `config/risk.json`'s key names/shapes where they line up, so the same mental model applies; evaluated against
   bar timestamps, not wall-clock or live account state. Every denial recorded on the `Report`, same spirit as the
   live risk chain journaling every verdict.
5. **Report generation** — reuse `analysis/daily_report.py`'s shape (trades, win rate, PnL/R, drawdown, what was
   blocked and why) so a backtest run and a live day read the same way. Likely needs a small Java-side writer
   (CSV/JSON of trades + risk-chain denial counts) that the existing Python report tooling can read, rather than
   building a second report renderer from scratch.
6. **Multi-strategy / parameter-sweep comparison** — formalizes what D-80 did by hand: given N (strategy, param set)
   combinations, run each through steps 3–5, emit one comparison table. This is central now, not an afterthought —
   "testing a lot of different strategies" is the explicit goal.
7. **Data, final**: the user's **~6.2-year 1-minute `@GC` CSV** (2020-07-12 → 2026-10-02, 2,193,481 bars,
   exported via the redeployed `HistoricalOhlcExporter`, D-77's tool — `../motivewave/experiments/src/flow_diag/
   HistoricalOhlcExporter.java`, now also writing a stable `GC_1m_latest.csv` alongside the dated snapshot so
   downstream tooling has one fixed path) is the primary input, replacing the original 24.2-day D-77 export. OHLCV,
   not OHLC — volume was already correct in the exporter, verified (see data-quality check below). Store under
   `analysis/data/` per existing convention; `analysis/data/`'s gitignore status is still flagged, not decided
   (D-79) — **recommend**: gitignore the raw CSV (large, ~100MB, regeneratable from MotiveWave any time) but
   commit small run-output summaries/reports, matching how `data/`/`reports/` are already split elsewhere in
   this repo. Flag this recommendation to the user rather than deciding it silently.
   Got here through several re-exports as the user scrolled further back each time (1y → 2.8y → 3.8y → 4.8y →
   6.2y), each one re-validated the same way (shape + roll check, below) before trusting it. **MotiveWave froze
   on the dev machine while scrolling for still more** (reached April 2020 before hanging) — left as-is per the
   user's call rather than force-restarted; see `todo.md`'s loose-end note. **Re-export requires reactivating the
   study** (remove + re-add, or restart MotiveWave) after scrolling further — the exporter is a one-shot-per-
   activation design, and scrolling alone does not feed more bars into an already-active study's `DataSeries`
   (unverified whether the platform even does that at all; the safe, known-working path is reactivation either
   way) — confirmed empirically across all the re-exports above, every one needed a fresh reactivation.

### Data-quality check — contract rolls (checked at each re-export through 2026-10-03, not fully conclusive)

A 6.2-year `@GC` series crosses many contract rolls (roughly bi-monthly on COMEX gold). Checked for roll-splice
artifacts by scanning all 2,193,481 bars for large 1-minute price moves: no new red flags appeared at any
re-export stage as the range grew. **No evidence of a stitching problem** — the large moves cluster cleanly into two
explainable buckets: (a) daily/weekend session-boundary gaps (e.g. 61-min and ~49-hour gaps at the 16:00 CT halt/
weekend close), and (b) a disproportionate number landing at `12:29`/`13:29` UTC specifically — the minute before
the 8:30am ET scheduled US economic release (CPI, jobs, etc.) — across scattered, unrelated dates, which is a news
signature, not a roll signature (a roll artifact would cluster on ~monthly calendar dates, not one time-of-day
across random days). **Not fully proven**: the CSV has no contract-month column, so a small roll-day gap (gold's
calendar spread is often only a few dollars, well under the 0.4% threshold used here) could be hiding inside
ordinary volatility undetected. Accepted as low-risk for this engine's stated purpose (a cheap screening filter,
not a precision PnL estimate) — revisit only if a strategy's results look suspiciously tied to specific roll-ish
dates.

### Phasing (don't attempt all of this in one pass — matches the user's "keep it minimal, keep moving" instruction)

**Steps 1–6 all done, 2026-10-03** (see the checklist and `decisions.md` D-122 for the full detail):

1. ~~`BacktestStrategy` interface + extract the existing market-structure rule behind it. Regression-check against
   D-79's run 1 numbers before anything else is trusted.~~ Done — exact match, every trade identical.
2. ~~Generalized `BacktestEngine` loop (strategy plug-in instead of inline logic) — same bar-by-bar mechanics as today.~~ Done.
3. ~~Ported risk chain (daily loss limit, size cap, reversals, rate limit, dwell) wired into the engine, denials
   recorded on the report.~~ Done, mutation-tested.
4. ~~Report writer reusing `daily_report.py`'s shape.~~ Done — plus CSV output and daily/weekly/monthly/yearly
   PnL tables (added to scope 2026-10-03, see the checklist).
5. ~~Multi-strategy / parameter-sweep comparison tooling.~~ Done.
6. ~~Run the (currently reviewed-or-not) market-structure strategy over the full historical CSV once the roll
   question above is checked.~~ **Done, 2026-10-03, D-122** — run on a ~5-year slice (user's choice, not the
   full 6.2 years), default params and a risk-adjusted `rr`. Explicitly **predates** the order-flow rules review
   (still in progress as of this writing, confirmed by the user to be taking longer than expected) — re-run once
   it lands.
7. Onboard additional strategies as they're written, one `BacktestStrategy` implementation each — no engine changes
   needed per the whole point of step 1's plug-in seam. **Not yet done** — only `market_structure_backtest` is
   registered so far.

**Explicitly not phased in here**: anything order-flow, anything requiring `raw.jsonl`/the VM's own recordings,
D-43/Replay-inside-MotiveWave. Revisit only when the user says so.

## What this is not

Not a reversal of D-06's stance that order-flow/tick-level execution can't be honestly backtested — that stance is
unchanged and, per the user's 2026-10-03 scope call, now explicitly permanent for this engine's lifetime, not just
"until D-43 is solved." This tool answers a narrower, different question: does a strategy's **setup/signal** logic
(expressed in pure price action) show any edge on years of historical bars, as a cheap first filter before spending one of a
limited number of forward-test slots on it. Order-flow execution quality is only knowable once a strategy reaches
Sim/live forward testing, same as today.

**Decision to record once steps 1–4 are built and the first real run completes**: amend D-06 to note that the
bar-close-only carve-out (originally market-structure-specific, D-74/D-79) is now a **general-purpose backtest
engine** open to any `BacktestStrategy` implementation, with order-flow execution staying explicitly and
permanently out of scope — dated, with the regression-check and first real multi-strategy run's results attached.

## Checklist — what this now actually requires (revised 2026-10-03 after scope resolution)

**1. Data**
- [x] *(2026-10-03, final)* Export the `@GC` 1-minute CSV — grew across several re-exports to **~6.2 years**
  (2020-07-12 → 2026-10-02, 2,193,481 bars), far more than planned; OHLCV confirmed, 0 malformed rows, only
  3/2,193,481 zero-volume bars. Exporter now also writes a stable `GC_1m_latest.csv` path. More history may still
  come later — MotiveWave froze on the dev machine mid-scroll (reached April 2020) and was left as-is; see
  `todo.md`'s loose-end note for what to check if it recovers.
- [x] *(2026-10-03)* Contract-roll check — no stitching artifacts found; residual small-roll-gap risk accepted as
  low for a screening tool, not fully provable without a contract-month column. Full write-up above.
- [ ] Decide `analysis/data/`'s gitignore status for this file (D-79, still flagged) — recommendation above, not yet
  decided by the user.

**2. Engine — done 2026-10-03**
- [x] `BacktestStrategy` interface (`flow-core/src/com/flow/backtest/BacktestStrategy.java`).
- [x] Extracted the existing market-structure rule behind it (`MarketStructureBacktestStrategy.java`);
  regression-checked against D-79's run 1 numbers — **exact match**, every individual trade byte-identical, not
  just the summary stats (`BacktestEngineTest`).
- [x] Generalized `BacktestEngine` (`BacktestEngine.java`) — strategy plug-in, same bar-loop mechanics as the old
  tool (kill switch checked before the ordinary stop/target geometry, no same-bar re-entry).
- [x] Ported risk chain (daily loss limit — with the forced-exit kill switch, size cap, max reversals, rate
  limit, min dwell) — `SessionBoundary.Tracker` reused directly for the 17:00 CT rollover, not reimplemented.
  One real bug found and fixed while building this: a redundant boolean flag was short-circuiting denial
  counting; removed, the existing `realizedTicksToday` check already covers the same case.
- [x] `BacktestRunner.java` — CLI entry point + strategy registry (one new line per strategy — see
  `docs/backtest-strategy-guide.md`), writes a self-contained `.jsonl` per run (header + trades + summary).
- [x] Report writer reusing `daily_report.py`'s shape: `analysis/backtest_report.py`.
- [x] Multi-strategy / parameter-sweep comparison tooling: `analysis/backtest_compare.py` — reads N already-run
  `.jsonl` files and emits one table, **never re-runs the engine** (the user's own instruction, 2026-10-03: build
  the report once, let comparison reuse it).
- [x] `docs/backtest-strategy-guide.md` — how to turn a strategy idea into a registered `BacktestStrategy`, end
  to end, with the existing one as a worked template.

**3. Trust-building — done for everything built so far**
- [x] Unit-tested every new piece against synthetic bar sequences before real data (`BacktestEngineTest`,
  `BacktestRunnerTest`, `test_backtest_report.py`, `test_backtest_compare.py` — 26 + 8 + 9 + 6 checks).
- [x] Mutation-tested `BacktestEngine.java` (2026-10-03, 6 mutations against a scratch-compiled copy, this
  project's own standing practice): **2 survived on the first pass, both real missing-boundary-case tests, not
  equivalent mutants** — (1) the daily-loss kill switch's `<=` breach check had no test where the loss landed
  *exactly* on the limit (only overshoot was tested); (2) min-dwell's `<` check had no test where elapsed time
  equals `minDwellMs` *exactly* (only under/over were tested). Both fixed by adding the missing boundary test,
  confirmed to fail against the mutated code and pass against the real code. The other 4 mutations (kill-switch
  exemption from the reversal counter, rate-limit window off-by-one, max-reversals boundary, the stop-wins-on-tie
  convention) were caught on the first try — the stop-wins-on-tie one decisively, by the D-79 regression check
  (238 trades changed).
- [x] The run-1 regression check is in and passing (see above) — the key correctness gate for the refactor.
- [x] Every `backtest_report.py`/`backtest_compare.py` output states bar count, date range and trade count next
  to the numbers.

**4. Sequencing**
- [x] First real run (2026-10-03, D-122) is explicitly noted as predating the order-flow rules review —
  re-run once `orderFlowExecutionRules.md` lands in code.

**5. Record-keeping — done 2026-10-03**
- [x] `decisions.md` D-122 amends D-06 with the full first-run write-up (default vs. risk-adjusted-optimized
  `rr`, the full sweep, and the caveats).

**Parked, not part of this checklist**: D-43 / Replay-inside-MotiveWave / any order-flow backtest — revisit only
when the user raises it again.
