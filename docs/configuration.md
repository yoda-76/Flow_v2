# Configuration guide — how to change every setting FLOW_V2 uses

Written 2026-09-26. This is a how-to, not a decision record: *why* a value is
what it is lives in `docs/dynamic/decisions.md`; this file says **where each
setting lives, what it does, when it takes effect, and how to change it.**
If you add a setting anywhere, add it here in the same commit.

There are four places configuration lives:

| Where | What it holds | Who edits | Takes effect |
|---|---|---|---|
| [`config/risk.json`](#1-configrisk-json) (+ optional git-ignored `config/risk.local.json` for one machine, D-106) | risk limits, session-end flatten, data-recording cadence, log retention | you, by hand | next time the study is **activated** |
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
| `dailyLossLimitTicks` | 200 | Realized + open loss (ticks) at which the **kill switch** fires: flatten, cancel everything, disarm. In `DRY_RUN` it still disarms and journals, but sends no order — there is nothing to flatten. Once it fires, re-arming is refused (`ARM_STILL_DENIED`) for the rest of this study *instance* — deactivating/reactivating does **not** clear it (code review A6); only removing and re-adding the study does (see the safety-rules note below). The realized-P&L figure the kill switch checks resets at the 17:00 CT session rollover, same as `maxReversalsPerSession`, but that rollover does **not** by itself clear a kill-switch disarm already in force. P&L scales with the actual position size (code review A7), not just its sign. |
| `rateLimitPerMinute` | 6 | Max **real position changes** in any rolling 60 s — a strategy re-asserting the same target (e.g. repeating "holding") never counts against this and is never blocked by it (code review A4). An intent to go flat is never blocked by this or any other filter (B1). |
| `minDwellMs` | 5000 | Minimum time in a position before another change (churn guard). |
| `maxReversalsPerSession` | 20 | Every change of *target* position after the session's first counts as one reversal — entries **and** exits both count, so 20 ≈ 10 round trips, not 20 round trips. (Code review B5: what this limit *should* mean — round trips vs. individual position changes — is awaiting the user's decision.) |
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

> **All of this is judged on this machine's own clock**, not an exchange
> feed's time: the entry-block window, the flatten window and the daily
> P&L/reversal reset all read the local clock (code review A2). Keep
> Windows time sync turned on.

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
| `dataKeepTradingDays` | 7 | Files are `data/<construct>/<symbol>/<sessionId>.jsonl` (the symbol folder since findings F-1, e.g. `GC` for `@GC`; recordings made before 2026-09-27 are flat `data/<construct>/<sessionId>.jsonl`, still read by the tools). Rolling window of trading days kept under `data/`; the oldest is deleted when a new one starts. About 0.45 GB per trading day (mostly the liquidity map) — disk per retention in `docs/runbook.md` §11a. |

### Log retention (D-106)

| Key | Default | Meaning |
|---|---|---|
| `logRetentionHours` | **0 = keep everything** | How long **old diagnostic logs** are kept, in hours. It covers (1) the raw tick journals of *past* sessions (`logs/<session>/raw.jsonl`, D-75) and (2) the per-feature logs (`logs/*_feature*.log`). It **never** touches `decisions.jsonl` (kept forever, D-15), the current session's files, or `data/` (its own window, `dataKeepTradingDays`). A negative value counts as 0. |

- **Dev machine: leave it at 0** (keep the logs for research; delete by hand if the disk ever complains).
  **Cloud machine: set 48** in `config/risk.local.json` (below).
- Pruning runs **at activation** (raw journals + feature logs) and **whenever a feature log rolls to a new
  trading day** (feature logs). Files are judged by their last-modified time, so anything written in the last N
  hours stays.
- **The per-feature logs now roll daily.** They used to be one file per feature that grew forever
  (`vwap_feature.log`); they are now `vwap_feature_<yyyy-MM-dd>.log`, one per **trading day** (17:00 CT → 17:00 CT,
  named by the day it ends in — the same day the report uses). Old single-file logs are left alone unless
  retention is set, then they age out like any other. Log lines and the `# feature start` header are unchanged; the
  header appears only in the first file of an activation.
- **Before D-106 raw journals were deleted after a fixed 48 h on this machine too.** With the default 0 they are
  now kept — that is the intended dev-machine behaviour, but expect `logs/` to grow (~3 MB/hour of `raw.jsonl` on
  a quiet market; more when busy).

### Per-machine overrides: `config/risk.local.json` (D-106)

`config/risk.json` is tracked in git and identical everywhere. To make **one machine** differ (the cloud machine
pruning at 48 h, say) without editing that file and fighting git on every pull, create an **optional, git-ignored**
`config/risk.local.json` next to it, in the same flat-integer format, containing **only the keys that differ**:

```json
{ "logRetentionHours": 48 }
```

- Any known key in it **wins** over `risk.json`; every other key keeps its `risk.json` value. Works for every key
  in this section, not only retention.
- Read at activation, like `risk.json`. No file = nothing overridden (the normal case on the dev machine).
- **It is journaled**: `risk_config_loaded` records `localOverrideKeys` (which keys came from it),
  `localOverrideLastModifiedMs`, and `localOverrideError`; the MotiveWave log shows `RISK_LOCAL_CONFIG … overrides=[…]`.
- **A broken file is not silent**: if it cannot be read (not valid JSON, a quoted or fractional number) it is
  ignored **as a whole** — no half-applied keys — the study still starts on `risk.json` alone, and the MotiveWave log
  says `RISK_LOCAL_CONFIG_IGNORED … error=…` (`localOverrideError` in the journal). For retention that means "keep
  everything", so the failure mode is a full disk, never lost logs. **Check the log line after creating the file.**
- A misspelt key is ignored without a warning, exactly like in `risk.json` (only the keys in this document are
  read); the `overrides=[…]` line shows what was actually picked up.

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

**Instrument:** only gold (`@GC` or a `GC` contract, tick 0.1) may be traded, for now (user, 2026-09-27). On any
other chart the study logs `REFUSE_TO_ARM instrument … is not allowed` and never arms or sends an order, whatever
*Armed* and *Mode* say; it still records into that instrument's own data folder. Journaled as
`instrumentAllowed` in `session_header` and in the heartbeat/arming `runtime` detail. Not a setting — widening
it is a code change (`InstrumentPolicy.ALLOWED_ROOTS`), with the strategies and limits re-checked.

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
not the Simulated one, stop. A kill-switch or order-anomaly disarm (code
review A6) survives deactivating and reactivating the study — the log shows
`ARM_STILL_DENIED` on every reactivation attempt after one; remove and
re-add the study (a fresh instance) to arm again.

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
| Project root (`FLOW_HOME`) | `C:/yadvendra/trading/FLOW_V2` unless overridden — see below | `com.flow.core.FlowHome` |
| Log directory | `<FLOW_HOME>/logs` | `FlowHome.logs()` |
| Recorded-data directory | `<FLOW_HOME>/data` | `FlowHome.data()` |
| Risk config path | `<FLOW_HOME>/config/risk.json` (+ optional `risk.local.json` beside it) | `FlowHome.riskConfig()` / `riskLocalConfig()` |
| Raw-log / feature-log retention | now the `logRetentionHours` config key (default 0 = keep everything; `decisions.jsonl` is always kept) | `ExternalConfig.logRetentionHours()` (D-106; was a fixed 48 h, `LogRetention.DEFAULT_RETENTION_MS`, which is no longer used by the runtime) |
| Session rollover / reopen | 17:00 America/Chicago | `SessionBoundary.BOUNDARY` |
| Daily halt (trading-window close) | 16:00 America/Chicago | `TradingWindow.CLOSE` |
| Flatten retry cadence | 5 s | `Pipeline.FLATTEN_RETRY_MS` |
| Chart redraw throttle | 1 s | `FlowRuntimeStudy.REDRAW_INTERVAL_MS` |
| DOM snapshot in the journal | every ~10 s, ±100 ticks | `Pipeline.DOM_SNAPSHOT_*` |
| Raw journal flush | every 50 records | `JournalWriter.RAW_FLUSH_EVERY` |

### Where FLOW_V2 lives on the machine (`FLOW_HOME`, D-98)

Every runtime path — `logs/`, `data/`, `config/risk.json`, and the per-feature
`logs/*_feature.log` files — hangs off one root. First non-blank wins:

1. JVM system property `flow.home` (`-Dflow.home=D:/flow` on MotiveWave's JVM)
2. environment variable `FLOW_HOME`
3. the built-in default `C:/yadvendra/trading/FLOW_V2` — so **nothing changes
   unless you set one of the first two**.

The value is made absolute and is read **once, when MotiveWave loads the
class**: changing it needs a **MotiveWave restart** (re-activating the study is
not enough), and an environment variable must be set before MotiveWave is
launched. Every activation logs `FLOW_HOME root=<path> source=<where it came
from>` to the MotiveWave log — check it after changing anything. `logs/` is
created if missing; `config/risk.json` is **not** (a missing file means every
default, and the log shows `RISK_CONFIG_MISSING`), so copy `config/` across
when setting up a new home.

**The `analysis/*.py` tools read the same variable (D-101).** With `FLOW_HOME`
set, `daily_report.py`, `status.py` and `trade_view.py` default to
`$FLOW_HOME/logs`, `$FLOW_HOME/data` and write pages to `$FLOW_HOME/reports`, so
they can be run from any directory. With it **unset** they behave as before —
relative to the directory you run them from (run them from the repo root) — there
is deliberately no built-in absolute default on the Python side. `--logs`,
`--data` and `--out` still override. Unlike the runtime, the tools read the
variable on every run, so no restart is involved.

### Where the build finds the JDK, the SDK jar and MotiveWave (D-100)

`build/build.sh` and `build/replay_check.sh` read three environment variables
(resolved in `build/env.sh`); with none set they use exactly the paths that used
to be written in the scripts, so nothing changes on this machine.

| Variable | Default | Meaning |
|---|---|---|
| `FLOW_JDK_BIN` | `../motivewave/tools/jdk-26.0.2.1+1/bin` (then `javac`/`java` on `PATH` if that folder doesn't exist) | Folder holding `javac`/`java` (`.exe` on Windows). **If you set it, it is used or it is an error** — never a silent fall-back to another JDK. |
| `MWAVE_SDK_JAR` | `C:/Program Files (x86)/MotiveWave/lib/mwave_sdk.jar` | The SDK jar `flow-runtime` compiles against. |
| `MOTIVEWAVE_EXT_DIR` | `/c/Users/MSI/MotiveWave Extensions` | MotiveWave's extensions folder; `build.sh` deploys to `<this>/dev`. |

The classpath separator (`;` on Windows shells, `:` elsewhere) is picked
automatically. A missing JDK / jar / folder stops the script at the start with a
message naming the variable to set, not halfway through.

> **Running the whole build without touching MotiveWave:** point the deploy at a
> scratch folder — `MOTIVEWAVE_EXT_DIR=$(mktemp -d) bash build/build.sh`. Every
> gate runs and the deploy step wipes only that folder. (This is how D-100 was
> verified.) The default, with the variable unset, still wipes MotiveWave's `dev`
> folder — never run that while a session is running.

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
