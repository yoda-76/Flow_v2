# Adding a strategy to the backtest engine

Written 2026-10-03, alongside the engine itself (`docs/dynamic/backtestEnginePlan.md` has the full design and
scope decisions — read that first if you haven't). This doc is the how-to: turn a strategy idea into one new
Java class that plugs into `BacktestEngine` with no engine changes, the same "a new strategy touches one box"
rule the live `FlowStrategy` interface follows (README "Adding a strategy").

**Scope reminder, because it shapes everything below**: this engine tests **pure price-action / bar-close signal
logic on 1-minute OHLC bars only** — no tick data, no DOM, no footprint/VWAP/volume-profile/big-trades/liquidity-
map, no order-flow execution. If your strategy idea needs any of that to decide when to enter, it can't be
backtested here (order-flow backtesting is explicitly parked, see `backtestEnginePlan.md`) — it goes straight to
forward testing (Sim) instead, where the real order-flow features exist.

## 1. The interface you implement

```java
package com.flow.backtest;

public interface BacktestStrategy {
  String id();
  void onBar(Bar bar, long seq);      // update your own internal state -- called every bar, flat or in a position
  boolean isReady();                  // your own warmup gate, if any
  Signal entrySignal(Bar bar);        // called only while flat; null = no entry this bar
}
```

`Bar` is `(timestampMs, open, high, low, close, volume)`. `Signal` is `(direction, stopPrice, targetPrice)` —
**you compute the stop/target yourself**, fully sized, in decimal price units. The engine applies it exactly as
given; there is no shared stop-sizing mechanism to plug into (deliberately — see §4).

## 2. What the engine does for you, and in what order

Per bar, `BacktestEngine.run()`:
1. If a position is open: checks the daily-loss kill switch first (forces an exit at this bar's close if the
   day's running loss breaches the limit), then checks your Signal's stop/target against this bar's high/low
   (stop wins if both are hit in the same bar — the standard conservative convention, since intrabar path order
   is unknowable from OHLC alone). No same-bar re-entry after either kind of exit.
2. Calls your `onBar(bar, seq)` — **always**, whether flat or in a position.
3. If flat, didn't just close this same bar, and you report `isReady()`: checks the risk chain (daily loss,
   max reversals, rate limit, min dwell — `config/risk.json`'s same field names/defaults, `backtestEnginePlan.md`
   has the exact porting rules); if nothing blocks it, calls your `entrySignal(bar)` and opens whatever it returns.

You never see the risk chain directly — a denied entry just means `entrySignal` was called but nothing happened;
the denial is counted on the `Report`, not reported back to your strategy.

## 3. A worked example — the existing strategy, read as a template

`MarketStructureBacktestStrategy.java` is the first (and so far only) implementation — read it alongside this
section rather than as a black box:

```java
public final class MarketStructureBacktestStrategy implements BacktestStrategy {
  private final MarketStructureFeature ms;   // your own state, built in the constructor

  @Override public void onBar(Bar bar, long seq) {
    ms.onEvent(new BarEvent(seq, bar.timestampMs(), bar.timestampMs(), BarPhase.CLOSE,
        toTicks(bar.open()), toTicks(bar.high()), toTicks(bar.low()), toTicks(bar.close()), bar.volume()));
  }

  @Override public boolean isReady() { return ms.isReady(); }

  @Override public Signal entrySignal(Bar bar) {
    ZoneRange touched = /* ... your own entry condition, reading your own state ... */;
    if (touched == null) return null;
    // compute direction + stop + target however your idea says to, return a Signal
  }
}
```

The pattern: your constructor builds whatever indicator/feature objects your idea needs (they can be anything —
a reused `flow-core` feature like `MarketStructureFeature`, a plain moving-average you write yourself, nothing
at all for something trivial); `onBar` feeds them; `entrySignal` reads them and decides.

## 4. Why there's no shared "stop sizing" abstraction

`MarketStructureBacktestStrategy` happens to support three interchangeable stop-sizing conventions
(`MarketStructureBacktest.StopRule` — fixed tick buffer, zone-size multiple, fixed price distance), but that's
**internal to that one strategy**, not a facility `BacktestStrategy` provides generically. README's own rule:
don't extract a shared mechanism until a second real strategy actually needs the same thing. If your strategy
wants multiple stop conventions too, copy the pattern (an enum + a param), don't try to reuse `StopRule` itself
unless you're genuinely doing the same thing it does.

## 5. Register it so `BacktestRunner` can run it from the command line

One line in `BacktestRunner.REGISTRY`:

```java
private static final Map<String, StrategyFactory> REGISTRY = Map.of(
    "market_structure_backtest", (params, tickSize) -> new MarketStructureBacktestStrategy(
        tickSize, parseDouble(params, "rr", 2.0),
        MarketStructureBacktest.StopRule.valueOf(params.getOrDefault("stopRule", "FIXED_BUFFER_TICKS")),
        parseDouble(params, "stopParam", 2.0)),
    "your_new_strategy_id", (params, tickSize) -> new YourNewStrategy(tickSize, /* parse your own --key=value params out of the map */)
);
```

`params` is whatever `--key=value` flags were passed on the command line, already split into a `Map<String,String>`
— parse out whatever your strategy needs with sensible defaults, the same way the existing entry does.

## 6. Test it before touching real data

Same discipline as everywhere else in this repo: a synthetic event-sequence test (craft a few `Bar`s, assert the
`Signal`/trade you expect), **before** running it against the real CSV. `MarketStructureBacktestTest` /
`BacktestEngineTest` are the templates — plain `main()`-driven checks (`check`/`checkDouble` helpers), no JUnit,
wired into `build/build.sh` as one more `"$JAVA" -cp build/classes/core com.flow.backtest.YourStrategyTest` line.

## 7. Run it and get a report

```
"$JAVA" -cp build/classes/core com.flow.backtest.BacktestRunner \
    analysis/data/GC_1m_latest.csv 0.1 your_new_strategy_id --yourParam=value --out=analysis/data/backtest_runs/your_run.jsonl

python analysis/backtest_report.py analysis/data/backtest_runs/your_run.jsonl
```

To compare it against other runs (other strategies, or the same strategy with different params) without
re-running anything:

```
python analysis/backtest_compare.py --glob "analysis/data/backtest_runs/*.jsonl"
```

Each `.jsonl` file is self-contained (header + trades + summary, `BacktestRunner`'s own javadoc has the exact
record shapes) — keep them around under `analysis/data/backtest_runs/` and the comparison tool reads however many
you point it at, no re-run needed, matching the point of building it this way (the user's own instruction,
2026-10-03: build reports once, let comparison tooling reuse them).

## 8. What this can tell you, and what it can't

A good backtest result here means: this strategy's pure price-action setup/signal logic shows an edge on bar
data, across a long enough history to mean something. It does **not** mean the strategy will work once order-flow
execution, real fills, slippage, and the full risk chain are in play — that's what forward testing (Sim) is for.
Treat this as the first checkpoint in onboarding a new strategy idea (the user's own framing, 2026-10-03): a cheap
filter for "is this even worth a forward-test slot," given only a limited number of strategies can be
forward-tested at once.
