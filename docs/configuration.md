# Configuration guide — how to change every setting FLOW_V2 uses

Written 2026-09-26. This is a how-to, not a decision record: *why* a value is
what it is lives in `docs/dynamic/decisions.md`; this file says **where each
setting lives, what it does, when it takes effect, and how to change it.**
If you add a setting anywhere, add it here in the same commit.

There are four places configuration lives:

| Where | What it holds | Who edits | Takes effect |
|---|---|---|---|
| [`config/risk.json`](#1-configrisk-json) | risk limits, session-end flatten, data-recording cadence | you, by hand | next time the study is **activated** |
| [MotiveWave study settings](#2-motivewave-study-settings-flow-runtime) | strategy id, armed/mode, big-trade threshold, draw flags, warm-start | you, in the MotiveWave GUI | mostly at activation; a few live |
| [Constants in code](#3-constants-in-code) | paths, retention, cadences | code change + rebuild + deploy | after deploy + re-activation |
| `.env` | (nothing in FLOW_V2 uses it today) | **only you, never Claude** | — |

The runtime never writes `config/risk.json` or the study settings; it only
reads them and **journals the values actually in force at the start of every
session** (see [Verifying](#verifying-a-change-took-effect)).

---

## 1. `config/risk.json`

A flat JSON object of **plain integers** (no quotes, no decimals, no comments).
Loaded once, when the study is activated (`FlowRuntimeStudy.startSession`).

- **Missing key** → its default (in the table). **Missing file** → every
  default, and the log shows `RISK_CONFIG_MISSING`.
- **Unknown or misspelled key** → **silently ignored** (only the keys below
  are read). Check the journal line after any edit.
- **A non-integer value** (e.g. `"5"` in quotes, `5.5`) is a parse error at
  load — don't.
- Values are in **ticks / counts / ms / minutes, never dollars** (D-30). For
  `@GC` one tick = 0.1 point.

### Risk limits

| Key | Default | Meaning |
|---|---|---|
| `fixedContracts` | 1 | Contracts per trade (D-30). Passed to the strategy. |
| `maxContracts` | = `fixedContracts` | Size cap in the risk chain: an intent whose target exceeds it is blocked. |
| `dailyLossLimitTicks` | 200 | Realized + open loss (ticks) at which the **kill switch** fires: flatten, cancel everything, disarm for the rest of the session. Reset at the 17:00 CT session rollover. |
| `rateLimitPerMinute` | 6 | Max position *changes* in any rolling 60 s. |
| `minDwellMs` | 5000 | Minimum time in a position before another change (churn guard). |
| `maxReversalsPerSession` | 20 | Max direction changes per 17:00-CT-to-17:00-CT session. |
| `lagQueueDepthThreshold` | 1000 | If the event queue backs up this deep, new entries are blocked (lag guard). |
| `lagProcessingMsThreshold` | 2000 | Same, if one event takes this long to process. |

### Session-end flatten (D-92)

Times are America/Chicago. The market pauses daily 16:00–17:00 CT and closes
Friday 16:00 CT → Sunday 17:00 CT.

| Key | Default | Meaning |
|---|---|---|
| `flattenLeadMinutes` | 5 | Minutes before the 16:00 CT halt at which any open position is flattened → **15:55 CT**. On Fridays the flatten window lasts all weekend. **0 (or negative) switches session-end flatten *and* the entry block off entirely.** Clamped to 600. |
| `noEntryLeadMinutes` | 15 | Minutes before the halt at which **new entries stop** → **15:45 CT**. Never less than `flattenLeadMinutes` (raised to it if so). Clamped to 600. |

The flatten only sends orders when the study is **Mode `SIM_LIVE` and Armed**
and did not refuse to arm at activation; in `DRY_RUN` it never touches an
order. Holidays and early closes are **not modelled** (a decision — D-93).

> **Testing the flatten during a watched session.** The real 15:55 CT is
> ~02:25 IST. To make the window start earlier, raise the lead: e.g.
> `"flattenLeadMinutes": 400, "noEntryLeadMinutes": 405` → entries stop at
> 09:15 CT, flatten from 09:20 CT, normal reopen at 17:00 CT. **Restore 5 / 15
> afterwards.** The entry block applies to *every* armed intent in that
> window, so expect the strategy to be blocked until 17:00 CT.

### Data recording (D-88)

| Key | Default | Meaning |
|---|---|---|
| `dataIntervalSeconds` | 1 | Write interval for footprint candles, VWAP and big trades under `data/`. Raise if storage or load demands it. |
| `liquidityIntervalSeconds` | 1 | Liquidity-map snapshot interval, tunable separately (the heaviest: ≈ 4–5 KB/s at 1 s ≈ 3 GB per 7 trading days). |
| `dataKeepTradingDays` | 7 | Rolling window of trading days kept under `data/`; the oldest is deleted when a new one starts. |

### How to change it

1. Edit `config/risk.json` (any editor; keep it valid JSON).
2. **Deactivate/remove the FLOW Runtime study and add it again** (or restart
   MotiveWave). The file is read only at activation; a running study keeps the
   values it started with.
3. Check the journal (see [Verifying](#verifying-a-change-took-effect)).

Only change limits deliberately: `maxContracts` and `dailyLossLimitTicks` are
what stands in for per-order human confirmation under `CLAUDE.md`'s
session-scoped Sim-trading exception. Raising them needs the same explicit,
stated-out-loud decision as arming.

---

## 2. MotiveWave study settings (FLOW Runtime)

Where: on a chart, **FLOW menu → FLOW Runtime** (adds the study) → its
**Runtime** tab. Settings are stored *per study instance* by MotiveWave: an
already-added study keeps its saved value even if the code's default changes,
so to pick up a new default, change the value in the study or remove and
re-add it.

### Strategy and trading

| Setting (key) | Default | Read | Meaning |
|---|---|---|---|
| Strategy Id (`FLOW_STRATEGY_ID`) | `level_zone_observer` | at activation | Which strategy runs. Registered ids: `null_strategy`, `level_zone_observer`, `lvn_fade_test`, `market_structure_lvn_reversal`. |
| Mode (`FLOW_MODE`) | `DRY_RUN` | live | `DRY_RUN` = journal what it *would* do, place nothing. `SIM_LIVE` = may place real orders **on the Simulated account** when also Armed. |
| Armed (`FLOW_ARMED`) | unchecked | live | Master switch. **Unchecked = nothing can be ordered, ever.** Checked with `SIM_LIVE` = automatic Sim orders, bounded by `risk.json`. |

**Safety rules (`CLAUDE.md`)**: only the Simulated account, ever — a real
account is strictly forbidden. "Sim Trade Only" must stay enabled in
MotiveWave. During the 2026-09-24 sprint Sim arming is pre-authorized; after
it ends, the bounds must be stated out loud and confirmed before checking
Armed, every session. If the `ACTIVATE` log line ever shows an account that is
not the Simulated one, stop.

### Constructs (calculated and recorded regardless of the draw flags)

| Setting (key) | Default | Read | Meaning |
|---|---|---|---|
| Volume Profile → Row Width (`FLOW_VP_RANGE_TICKS`) | 1 | activation + draw | Ticks per VP row. |
| Volume Profile → Draw (`FLOW_DRAW_VP`) | off | live | Draw the profile on the chart. |
| Footprint → Row Width (`FLOW_FP_RANGE_TICKS`) | 1 | activation + draw | Ticks per footprint row. **Stored** granularity. |
| Footprint → Merge N Rows (`FLOW_FP_MERGE_ROWS`) | 1 | live | Draw-time grouping only; never changes what is computed or stored. |
| Footprint → Draw (`FLOW_DRAW_FP`) | on | live | Draw footprint bars. |
| Big Trades → Min Size (`FLOW_BT_MIN_SIZE`) | **1** | activation | Contracts for a trade to count as big. **1 is a temporary test value (D-90) — restore to 10 after the capture test.** At 1 nearly every tick is a "big trade". |
| Big Trades → Agg Period ms (`FLOW_BT_AGG_PERIOD_MS`) | 20 | activation | Same-price, same-side trades within this window merge into one. |
| Big Trades → Draw (`FLOW_DRAW_BT`) | on | live | |
| Liquidity Map → Draw Window ticks (`FLOW_LM_DRAW_WINDOW_TICKS`) | 50 | live | Rows drawn each side of price. Cosmetic; no storage effect. |
| Liquidity Map → Draw (`FLOW_DRAW_LM`) | on | live | |
| Market Structure → Warm-Start Bars (`FLOW_MS_WARMSTART_BARS`) | 100 | activation | Historical bars replayed into market structure when the study starts (0 = none). |
| Market Structure → Draw (`FLOW_DRAW_MS`) | on | live | |
| Entry Signals → Draw Arrows (`FLOW_DRAW_ENTRY_SIGNALS`) | on | live | Arrows at entries the risk chain allowed (empty until armed). |

"activation" = read once when the study starts; changing it later has no
effect until the study is removed and re-added. "live" = re-read while running.

---

## 3. Constants in code

Changing these means editing source, running `build/build.sh`, and
re-activating the study. **`build.sh` deploys by wiping `MotiveWave
Extensions/dev` — never run it while a session is running** (check that the
newest `logs/*/decisions.jsonl` was not modified in the last minute).

| Setting | Value | Where |
|---|---|---|
| Log directory | `C:/yadvendra/trading/FLOW_V2/logs` | `FlowRuntimeStudy.LOG_ROOT` |
| Recorded-data directory | `C:/yadvendra/trading/FLOW_V2/data` | `FlowRuntimeStudy.DATA_ROOT` |
| Risk config path | `C:/yadvendra/trading/FLOW_V2/config/risk.json` | `FlowRuntimeStudy.RISK_CONFIG_PATH` |
| Raw-log retention | 48 h (`decisions.jsonl` is kept) | `LogRetention.DEFAULT_RETENTION_MS` |
| Session rollover / reopen | 17:00 America/Chicago | `SessionBoundary.BOUNDARY` |
| Daily halt (trading-window close) | 16:00 America/Chicago | `TradingWindow.CLOSE` |
| Flatten retry cadence | 5 s | `Pipeline.FLATTEN_RETRY_MS` |
| Chart redraw throttle | 1 s | `FlowRuntimeStudy.REDRAW_INTERVAL_MS` |
| DOM snapshot in the journal | every ~10 s, ±100 ticks | `Pipeline.DOM_SNAPSHOT_*` |
| Raw journal flush | every 50 records | `JournalWriter.RAW_FLUSH_EVERY` |

The three hard-coded absolute paths are a known cloud-readiness item
(`docs/dynamic/todo.md`, Phase 4): they will become configuration before the
system moves to another machine.

**Per-strategy parameters are constants inside each strategy** — there is no
external per-strategy config yet (`StrategyConfig` only carries
`fixedContracts`/`maxContracts` from `risk.json`). To tune one, edit the
strategy class in `flow-core/src/com/flow/strategies/`:

- `lvn_fade_test`: `SL_TP_TICKS` = 5, `WARMUP_MS` = 2 min.
- `market_structure_lvn_reversal`: `TOUCH_TOLERANCE_TICKS` = 2,
  `STOP_BUFFER_TICKS` = 2, `REWARD_RISK_MULTIPLE` = 2.0,
  `AGGRESSION_RECENCY_MS` = 60 s.

---

## Verifying a change took effect

Every activation writes these into the session's `logs/<strategy>_<ms>_inst<id>/decisions.jsonl`:

- `risk_config_loaded` — every `risk.json` value in force, plus
  `fileLastModifiedMs` (compare with `sessionStartMs` to spot a stale file).
- `session_header` — strategy id, symbol, tick size.
- `DATA_RECORDER_ON` (log line) — data root and the three data settings.
- The MotiveWave log's `ACTIVATE pos=… cash=…` line — **confirm the account is
  the Simulated one**.

If a value you changed isn't in `risk_config_loaded`, the study was not
re-activated after the edit, or the key is misspelled (unknown keys are
ignored without a warning).
