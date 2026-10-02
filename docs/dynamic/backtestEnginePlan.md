# Backtest engine — closing the gap (plan, not yet built)

Written 2026-10-02, after an audit of what actually exists (`todo.md` and `decisions.md` D-06/D-77/D-79/D-80 told
the story before code was read; this re-verifies against the real files, per this project's own rule: never report
a claim you haven't just checked).

## Honest current state (verified 2026-10-02, not re-derived from memory)

Two separate, unconnected things exist, wearing similar-sounding names. Neither is "the backtest engine, needs a
few fixes" — read both sections before assuming either is close to done.

**1. `ReplayHarness` / `ReplayEquivalenceTest` / `RecordingReplayTest`** (`flow-core/src/com/flow/core/`) —
real, built, live-verified. Reads a single session's `raw.jsonl` top to bottom, feeds it through the real
`Pipeline` + strategy + features, and asserts the resulting `Intent` sequence is bit-identical to what the live run
produced (content, not sequence numbers). **This has no PnL concept at all.** It is a determinism/regression check
— "does replaying this recording reproduce the same decisions" — not a trading-performance tool. Verified for
footprint/VWAP; market-structure warm-start and liquidity depth are explicitly **not** replay-exact (D-89); anything
using `SdkVolumeProfileFeature` can't be replayed under plain JDK at all (D-43). Wired into `build/build.sh` as a
gate.

**2. `MarketStructureBacktest`** (`flow-core/src/com/flow/backtest/`) — real, built, 16 synthetic tests, wired
into `build/build.sh` as the eighth gate. This is the only thing that actually produces trades and PnL (`Report`
with wins/losses/totalR/avgR/maxDrawdownR). But:
- It reads a **plain OHLC CSV** (`timestampMs,open,high,low,close,volume`), not `raw.jsonl` and not
  `data/<construct>/*.jsonl` — a third, independent data path, fed from a one-off MotiveWave bar export
  (`analysis/data/GC_1m_1789910262328.csv`, 25,000 1-minute bars, ~24.2 days, 2026-08-25 to 2026-09-18, D-77).
- It is a **deliberate narrow carve-out from D-06** ("no backtesting, forward-test only"), scoped to pure
  bar-close market-structure logic (trend/pullback/TJL/flip) with a hand-written entry/stop/target rule —
  explicitly **excluding** the order-flow-confirmed entries (`MarketStructureLvnReversalStrategy`, absorption,
  aggression, sweep-and-reclaim) that are this system's actual tick/DOM-level trading edge. D-06's own rationale
  (tick-level entries don't survive bar-granularity replay honestly) still applies to everything this tool skips.
- 5 exploratory runs done (D-79/D-80), explicitly "hit-and-trial, not a conclusion" per the user's own framing at
  the time, then paused to go do other work. No run isolates one variable; no comparison tooling beyond a
  hand-typed markdown table.

**Bottom line**: there is no engine today that backtests the real order-flow strategy, none that consumes this
system's own recorded `data/`/`raw.jsonl`, and no report/compare/parameter-sweep tooling beyond one manual table.
`D-06` ("no backtesting for order flow") has not been reversed — only carved around for the bar-only case.

## Data actually available (checked on disk 2026-10-02 — don't assume, re-check before building)

- **Our own `raw.jsonl` session recordings** span roughly 2026-09-14 to today, but it's a **patchwork of short
  dev/test sessions** (`null_strategy`, `level_zone_observer`, `market_structure_lvn_reversal`, `lvn_fade_test` —
  dozens of short-lived instances, mostly DRY_RUN plumbing tests), not continuous trading days. This is the *only*
  source with tick/DOM detail (footprint, delta, absorption, liquidity map) — real continuous-day history only
  starts accumulating **now that the VM runs 24/7**.
- **`data/<construct>/*.jsonl`** keeps a rolling **7 trading days** only (D-88) and is a derived/sampled view (1 s
  cadence for most constructs), not the event-level replay-exact source `ReplayHarness` needs — not a viable
  primary backtest input, useful at most for a quick sanity cross-check.
- **MotiveWave's own historical export** is **OHLC bars only** (the 24-day CSV above) — no tick or DOM history, so
  it structurally cannot feed footprint/delta/absorption/liquidity-map features. Historical DOM reconstruction may
  also be permission-gated on the current Rithmic tier regardless (README's "What's confirmed already").

⚠️ **AMBIGUOUS — this changes the scope of what gets built, confirm before the cloud Claude starts coding**: "test
over however much gold data we can get from MotiveWave" could mean —
  - **(a)** Build the real order-flow backtest against **our own `raw.jsonl` archive only** (the only tick/DOM
    source that exists), accepting that today it's patchy and will only become a genuine continuous-day archive
    going forward from the VM's 24/7 run. This is the only option that actually exercises the trading edge.
  - **(b)** Extend `MarketStructureBacktest`-style bar-only backtesting with a longer MotiveWave CSV export (more
    history, still bar-only, still not the real edge) — useful for market-structure-only validation, not for
    anything order-flow.
  - **(c)** Both, clearly labeled as two different tools answering two different questions (recommended default
    if no answer is given, since (a) and (b) don't conflict and the project already half-built (b)).
  - **Not (d)**: there is no way to get years of tick/DOM history from MotiveWave itself for a market that's been
    live-recorded here only since mid-September — don't let the cloud Claude assume otherwise.

This doc proceeds assuming **(c)**, phased so (a) — the real engine — comes first since it's the actual ask; say so
explicitly if the scope should be narrowed or widened before work starts.

## Proposed architecture — reuse `ReplayHarness`, don't rebuild it

`ReplayHarness` already does the hardest part correctly (bit-identical event replay through the real pipeline). The
gap is everything *downstream* of the `Intent` stream it already produces, plus running it over more than one
session.

1. **Fill/PnL simulator** (new, smallest useful piece — build and unit-test this first). Consumes the `Intent`
   sequence `ReplayHarness` emits and simulates fills the way the Sim account/`OrderGateway` would: apply the
   strategy's stop/target distances from a simulated fill price, and — important — run the **same risk chain**
   (`armed`/session/readiness/daily-loss/size-cap/rate-limit/churn/lag) so a backtest's denials match what live
   would actually have denied, rather than crediting the strategy with trades the real system would have blocked.
   Unit-test against synthetic `Intent` sequences with known expected trades/PnL before touching any real recording
   — same discipline as every other core piece in this repo.
2. **Multi-session driver** (new). Loops the harness + simulator across every `raw.jsonl` under `logs/` matching a
   date range and/or strategy id, concatenating results — today's tests call `ReplayHarness` once per session;
   nothing drives it across many.
3. **Report generation** (new, but don't invent a new format). Reuse `analysis/daily_report.py`'s report shape
   (trades, win rate, PnL, R-multiples, drawdown, what was blocked and why) so a backtest run and a real live day
   are visually comparable side by side.
4. **Run comparison / parameter-sweep tooling** (new). Formalizes what D-80 did by hand into a script: given N
   parameter sets, run each through steps 1–3, emit one comparison table — matches this project's own working
   agreement for experiments (`working-agreements.md` §5: headline numbers + one table, full output to a file, one
   growing `decisions.md` entry per sweep).
5. **`MarketStructureBacktest` stays as-is**, kept and documented as *"the bar-only backtest"* — a separate,
   legitimate tool for scope (b) above. Don't fold it into the new engine; just make sure the two are never referred
   to interchangeably again (that confusion is exactly what produced the "most of it is done" assumption this plan
   corrects).

### Phasing (don't attempt all of this in one pass)

1. Fill/PnL simulator over one session's replayed `Intent` stream — unit-tested on synthetic sequences first.
2. Multi-session driver + report reusing `daily_report.py`'s format.
3. First real run: the actual order-flow strategy currently live on the VM, over whatever `raw.jsonl` history
   exists — **report honestly that this is patchy/short-session data today**, not a continuous historical backtest;
   don't overstate what a handful of scattered dev-test sessions prove.
4. Parameter-sweep tooling, once there's more than one run worth comparing.
5. Only if the ⚠️ above resolves to (b) or (c): extend `MarketStructureBacktest` with a longer MotiveWave bar
   export, kept clearly separate from and not conflated with steps 1–4.

## What this is not

Not a reversal of D-06's stance that MotiveWave's own *optimizer* fills are optimistic and bar-granularity replay
doesn't survive for tick-level entries — that critique is about replaying at **bar** granularity. This plan's
engine replays at **event level**, through the same deterministic, bit-identical path `ReplayHarness` already
proved out for live — which is precisely why README calls event-level replay "the only honest path to
backtesting order-flow logic later." Worth stating plainly so the cloud Claude doesn't second-guess the premise.

**Decision to record once step 1–3 are built and the first real run completes**: D-06 gets an explicit amendment
(not a silent reversal) noting the order-flow backtest now exists for the scope actually built, dated, with the
first run's honest results attached — same pattern as D-79/D-80 for the bar-only carve-out.
