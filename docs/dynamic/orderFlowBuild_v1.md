# Order-flow execution model — build notes v1

**Built:** 2026-10-11 on branch `orderflow-rules-review`, from `orderFlowRules_gapAnswers_v1.md` (user-approved A1–A7).
**State:** ready for a Sim run on the VM. Full `build/build.sh` suite passes (deploy tested into a scratch folder only); replayed on the
recorded sessions of 2026-09-26..29 with 0 errors. **Not yet run live in MotiveWave**: the first VM run is the live check.

## What exists

| File (flow-core unless noted) | What it does |
|---|---|
| `orderflow/OfConfig.java` + **`config/orderflow.json`** | Every tunable number (≈ 70), defaults from gapAnswers; the file overrides any of them. The full set is journaled at session start. |
| `orderflow/OrderFlowEngine.java` (feature `orderflow_engine`) | Built from trades only, so it replays exactly from raw journals. Builds: 500-trade bars with footprint and reads; aggressor orders (`aggExchOrderId`) → BIG (≥ 30) / SMALL (10–29) / BURST, with price-response outcome; profiles; VWAP (17:00 CT); micro structure; 1-second price history. |
| `orderflow/OfBar.java` | Footprint rows (6 ticks), diagonal imbalance 300 %, stacked ≥ 3, finished/unfinished auction (top row bid = 0 / bottom row ask = 0), close location, candle dominance, volume cluster. |
| `orderflow/SessionClock.java` | OPEN / ASIA (09:00 Tokyo) / LONDON (08:00 London) / NY (09:00–13:00 New York) / LATE; trading day from 17:00 Chicago; DST-safe. |
| `orderflow/Profile.java`, `ProfileBook.java` | Session / day / week profiles plus completed previous sessions and day; POC, 70 % VA, HVN, LVN with rank score, shape (D/P/b/double), naked-POC flag, maturity rule (30 min + 2,000 lots). |
| `orderflow/Reads.java` | Reads grouped ABS / EXH / FLIP / IMB (plus supporting reads) so correlated evidence isn't double-counted. |
| `orderflow/Models.java` | M1–M8 exactly as gapAnswers §5, with the shared −2..+2 / NO_SIGNAL / NO_DATA meaning; a model error becomes NO_SIGNAL. |
| `orderflow/ExecutionPlanner.java` | Aggregation (Go = a +2 and no −2), stop (priority model, 5-tick buffer, wall shift, max), nearest target ≥ 2R, POC/HVN blocker, chasing, momentum, P-shape, 3-per-zone / 6-per-session limits. Every block reason is recorded. |
| `orderflow/LiquidityTracker.java` | Heatmap from the live liquidity map, once per second: walls, sweep-reversal / breakthrough / gap / spoof events, nearest wall. Live only. |
| `strategies/OrderFlowExecutionStrategy.java` (id **`orderflow_execution`**) | Ideas from market structure (trend + lastTjl2/DT/DB); evaluation at every closed bar; entry/hold/exit intents using the existing proven pattern. Writes **`logs/orderflow_eval*.log`**: init with config, idea open/close, every evaluation (all model scores and reads, levels, plan), entries, exits, rejections. |
| `orderflow/OrderFlowReplay.java` | Research replay: raw journal → full strategy → eval log + gross trade summary. |
| `orderflow/OrderFlowEngineTest.java` | 33 synthetic checks, wired into `build.sh` (mutation-checked: swapping the finished-auction side fails it). |
| Changed: `core/MarketState`, `MutableMarketState` (`orderFlow()` accessor), `strategies/StrategyRegistrations`, `flow-runtime/.../FlowRuntimeStudy` (registers the engine, passes the config path), `core/DataRecorder` (liquidity window ±250, A7), `config/risk.json` (`dataKeepTradingDays` 30), `docs/configuration.md`, `build/build.sh`. | |

**Run it live (user does this):** in the study settings, set *Strategy Id* = `orderflow_execution`. Readiness waits for
5 closed 500-trade bars. Order behaviour and risk chain are unchanged (Sim only, dry-run until armed).

**Replay a recording:**
`java -cp build/classes/core com.flow.orderflow.OrderFlowReplay out.jsonl logs/<session>/raw.jsonl`
(add `-Dorderflow.config=config/orderflow.json` to test changed values).

## Robustness (added before the first VM run)

- Any exception inside a feature or the strategy would disarm the whole pipeline for the session
  (`Pipeline.onPipelineException`). So `OrderFlowEngine.onEvent` and `OrderFlowExecutionStrategy.onEvent` never throw.
- On an engine error: count it, record it, write it to the eval log as `"type":"error"`, and **block new entries**
  (`engine_error`, fail closed). An open position keeps being managed.
- On a strategy error: log it and answer with the safe intent. That means **hold** while in a position (never an
  accidental flat), and none while flat.
- `build.sh` replays the committed 5-minute GC fixture through the full strategy and fails on any error.
- All 14 recorded sessions on the dev machine replay with 0 engine / 0 strategy errors.

## Execution bars: 1 minute (changed 2026-10-12, VM)

The user asked for the order-flow execution bars to use the same 1-minute timeframe as the market-structure chart.
New key `barSeconds` in `OfConfig`: `0` (code default) keeps the 500-trade bars (`barTrades`); `> 0` builds
clock-aligned time bars of that many seconds. `config/orderflow.json` now sets `"barSeconds": 60`.

- A bar closes on the clock 200 ms after its minute ends, or earlier if a trade from a later minute arrives. A late
  print from a minute already closed goes into the next bar. Minutes with no trades make no bar.
- Every setting counted in bars is now counted in minutes: readiness 5 bars = 5 minutes; `avgWindowBars` 20,
  `readValidBars` 3, `exhaustionBars` 3, `momentumBars` 3, `trappedSmallBars` 5, `retestMaxBars` 10. These, and
  `maxStopTicks` 80 (set from 500-trade bar ranges, build note B2), were not re-tuned for 1-minute bars.
- Replay of one real session (VM, 2026-10-08/09, 132,689 trades): 1-minute bars = 1,649 bars, 6 entries
  (1 win, 5 losses, −24 ticks gross); 500-trade bars = 265 bars, 1 entry (−6 ticks). 0 errors either way.
  One session, not a result.
- Tested in `OrderFlowEngineTest` (7 time-bar checks).

## Telegram alerts (added 2026-10-12)

`ops/watchdog.py` (scheduled every minute on the VM) now also tails `logs/orderflow_eval_<day>.log` and forwards
three kinds of line:
- **`ORDERFLOW_ERROR`** (WARN): engine or strategy errors. At most 5 a minute, then one summary line. The strategy itself
  logs a repeating engine error only at counts 1, 2, 3, 10, 100, ...
- **`ORDERFLOW_ENTRY`** (INFO): side, model, entry, stop, target, R:R, idea.
- **`ORDERFLOW_EXIT`** (INFO): stop/target, price, P&L in ticks, model, idea.

Prices are real prices: the session's `price_anchor` in `decisions.jsonl` converts the logged tick offsets. The first
run doesn't forward history. Turn it off with `--no-forward-orderflow`. Telegram is only used if
`TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` are set on the VM (env or `.env`), as for every other alert; otherwise the
lines only go to `logs/alerts.log`. Tested in `ops/test_watchdog.py` (4 new cases, mutation-checked).

## Backup to Google Drive (VM nightly)

`ops/stage_by_date.py` (nightly mode) now also stages:
- every session's **`raw.jsonl`**, the input of `OrderFlowReplay`;
- the rolled system logs `logs/<base>_YYYY-MM-DD.log`, including **`orderflow_eval_*.log`**;
- the live logs, under `_current/` (overwritten nightly).

Liquidity-map files stay in the weekly `liquidity` mode, as before. Tested on the dev machine: 170 files, hard-linked.
Pull on the dev machine with `ops/sync_from_gdrive.ps1` (rclone is at
`C:\Users\MSI\Downloads\rclone-v1.75.1-windows-amd64\`, not on PATH).

## Running it on the VM (user)

1. **Retention first:** in `config/risk.local.json` on the VM, remove `logRetentionHours` and `dataKeepTradingDays`
   (or set them to 0 and 30). Otherwise raw journals and the eval log are deleted after 48 h.
2. `git fetch` and `git checkout orderflow-rules-review`, then `bash build/build.sh` (redeploy per `docs/runbook-ec2.md`).
3. Study settings: *Strategy Id* = `orderflow_execution`. Start **unarmed** (dry run) and check:
   - `logs/orderflow_eval_*.log` gets an `init` line, then `idea_open` / `eval` lines once 5 bars have closed;
   - `alerts.log` shows no `DISARM`.
   Then arm on the **Sim account** as usual.
4. **Leave it running across days.** The engine starts cold at activation: profiles, completed-session levels and the
   prior NY POC (M8) only exist after the first session boundaries. A restart loses that state.
5. What to watch: `"type":"error"` lines in the eval log; `entry` / `exit` lines; RiskChain rejections in
   `decisions.jsonl`.

## Deviations from the answers file (found by replaying real data) — review these

| # | Change | Why |
|---|---|---|
| **B1** | LVN = on a 3-row moving average, the minimum between two neighbouring HVNs ≤ **0.7 ×** the smaller HVN (width ≥ 2 rows). HVN = highest smoothed row within ± 2 rows and ≥ **1.0 ×** the mean. The answers' "≤ 0.35 × mean row volume" is replaced. | Real intraday Gold profiles are smooth; the mean-based rule found **zero** LVNs on a 13-hour session. 0.5 still found almost none; 0.7 gives a few per session, which is closer to how the course picks them by eye. |
| **B2** | Trigger bars must touch the location (M1, M2, M3, M8). Chasing = entry more than **1 average bar range** beyond the location (was 10 ticks). Max stop **80** ticks (was 60). | 500-trade GC bars have a median range of **47 ticks** (p25 35, p75 61; median duration ≈ 4.6 min). The first replay had M1 firing with the close 100+ ticks past the LVN (a bug), and a 10-tick chase limit rejected nearly every bar-close entry. |
| **B3** | The POC blocker uses the POC of **every** profile (including completed sessions); the HVN blocker stays with the active session and day. | Both replay trades had a completed session's naked POC between entry and target, which the course says to skip. |

## Replay results (dev-machine recordings, gross, no costs)

10 sessions, 91,564 trades, 179 bars. With the final code: 19 full setups (+2): M1 8, M5 10, M4 1. All are blocked:
POC/HVN blocker in 11, "no target at 2R" in 10, stop too wide in 3, chasing in 2 (several have more than one reason).
**No entries.** That is the
course's "most places are no trade" applied strictly. Three days of data is not a basis for tuning. The VM's
recordings (Drive sync) are the next research input.

## First live Sim trades (2026-10-13, VM) — findings, no code changed

Instance `inst1160401828`, armed `SIM_LIVE` from 2026-10-12 15:57 UTC, 1-minute execution bars. 2026-10-12 (Columbus
Day, range 4183.5–4196.6) gave no market-structure event and no idea in 4 hours. On 2026-10-13 up to 15:31 UTC it
opened 38 ideas, logged 975 evaluations and made 4 entries. Every fill was on account `simulated`, the heartbeats were
all healthy, and there were 0 strategy errors. Cash went from 77,400 to 77,720 (+$320), which matches the fills.

| # | Fill (UTC) | Idea (market structure) | Entry fill | Stop / target (label) | R:R | Result |
|---|---|---|---|---|---|---|
| 1 | 09:49 | I25: TJL_FORMED 09:31, trend DOWN, TJL2 4217.7–4217.9 | 4207.3 | 4208.7 / 4203.3 (`session:ASIA:HVN`) | 2.6 | stop, −14 ticks |
| 2 | 12:34 | I32: FLIP to DOWN 12:34, DT 4212.9–4214.6 | 4206.4 | 4209.4 / 4197.3 (`session:OPEN:POC`) | 2.78 | stop, fill 4209.6 (2 ticks of slippage), −32 |
| 3 | 12:50 | I32 | 4206.2 | 4207.3 / 4203.8 (`VWAP`) | 2.5 | stop, −11 |
| 4 | 12:51 | I32 | 4206.1 | 4208.0 / 4197.3 (`session:OPEN:POC`) | 4.94 | target, +89 |

All four were shorts and all were model **M5**, with a +2 score ("retest held") and no model at −2.

**The logs explain every stage of these trades.** Each one was checked by hand against the code:
- **Idea:** `idea_open` in the eval log. Its zone matches the market-structure event at the same minute.
- **Evaluation:** an `eval` line with all eight models (score, reads, stopRef, location, note) and the plan.
- **Stop:** the M5 `stopRef` plus `stopBufferTicks` 5.
- **Target:** the nearest candidate at least 2 × the risk away. The full candidate list is logged.
- **Risk:** a `risk_verdict` in `decisions.jsonl` covering all eight filters.
- **Execution:** `real_order_submitted`, `order_fill` and `entry_execution` (slippage against the signal and the
  touch, submit-to-fill time), then the bracket in `LIVE_BRACKET_SUBMITTED`.
- **Position management:** a fixed bracket only, with no trailing stop and no move to breakeven. The exit is the bracket
  leg's `order_fill`, followed by the other leg's cancel and `POSITION_CLOSED`.
- **Per-idea cap:** idea I32 used all 3 entries allowed per zone (`maxEntriesPerZonePerSession`) and then closed on
  "zone or trend changed".

**Findings to decide on (not fixed; the user asked to let it run unchanged):**
1. **M5 does not check location or the stall** (`Models.java` `m5`). Gap answers G0.5 and the M5 row require
   "any V1 location" (LVN, VAH/VAL, POC, VWAP or the idea's zone) and "a 2-bar stall (no new extreme)". The code needs
   only the flip, the micro break of structure and the retest, and M5's "location" is its own break-of-structure level.
   The result:
   - Entries were 6–10 points away from the idea zone: trade 1 was 105 ticks below its zone, trades 2–4 were 67 ticks
     below theirs.
   - Trade 1 was not within the 10 ticks of `locationMaxDistanceTicks` of any V1 level (the nearest was VAL, 15 ticks
     away).
   - M5 is also noisy: it scored +2 and then −2 on the very next bar after trades 1 and 2.
2. **Fast re-entry on the same idea.** Trade 4 entered 21 s after trade 3 stopped out. The rules allow this (3 per zone,
   `minDwellMs` 5 s), but it lets a losing setup repeat within a minute.
3. **The strategy and the account diverge on a target touch.** On trade 4 the strategy logged `target_hit` at 13:05:22
   when price touched 4197.3. The Sim limit filled only when price traded through it, 48 s later at 4197.2. For those
   48 s the strategy treated itself as flat while the account was short 1. Nothing happened, because the idea closed,
   but a new go in that window would have met a live position. The same gap applies to stops. The runtime's
   "bracket-only" skip prevented a double exit.
4. **The planned entry is not the fill.** The plan's entry and R:R use the bar close. Fills differed by 1–2 ticks
   (trade 1: plan 113, fill 114). This is visible in the logs and was harmless here, but the R:R gate is checked
   against the plan, not the fill.
5. **The idea's origin is not in `idea_open`.** It does not say which level created the idea (TJL2, DT or DB), the
   trend, or the market-structure event. Today it was matched by timestamp to `market_structure_feature_<day>.log`.
6. **The daily loss guard is effectively off** for this test (`config/risk.json`: `dailyLossLimitTicks` 90000,
   `maxReversalsPerSession` 5000). Only `maxEntriesPerSession` 6 bounds a bad day.
7. **Cosmetic:** at every study start, `FEED_STALE` / `data_gap` report an epoch-sized duration ("no market data for
   1791819715s"), because there is no previous price yet. It fires one misleading Telegram alert per restart.

**Backup gaps (checked on Drive, 2026-10-13):**
- `--min-age 1h` skips any file written in the last hour. The running instance's `decisions.jsonl` / `raw.jsonl` get a
  heartbeat every 10 s, so **they are never uploaded while the study runs**. They go up only on the first nightly run at
  least an hour after the study is removed.
- Files last written around the 22:00 UTC reopen (for example the 20737 `bars` / `footprint` / `vwap` files) slip one
  night.
- Never uploaded:
  - `data/liquidity_map/` (≈ 0.7–1.6 GB a day). Its `--mode liquidity` exists but has no timer, and nothing is on Drive.
  - `logs/watchdog_metrics.csv` and `watchdog_state.json`.
  - MotiveWave's own `~/.motivewave/output/` logs.
  - The systemd journals of the notifier and the watchdog.
- **Disk use from the open** (2026-10-12 22:00 UTC) **to 15:45 UTC** was ≈ 0.96 GB: `liquidity_map` 675 MB, the
  running `raw.jsonl` 231 MB, `decisions.jsonl` 26 MB, everything else ≈ 30 MB.

## Not built yet (follow-ups)

1. Intrabar shadow signals (G0.3): logging only, still to add.
2. Hypothetical outcome per evaluation (MFE/MAE at 5/15/60 min, would-be 2R result): an offline script over the eval
   log + raw journal.
3. `analysis/` viewer overlay of ideas, scores, levels and walls (G31).
4. Platform checks (gapAnswers §13): imbalance direction and finished-auction side against MotiveWave's footprint on
   one recorded bar; depth levels on the VM; trade-correction events.
5. NTP / clock-offset check in the runbook (G27): raw ticks show exchange time ≈ 1.9 s ahead of local receipt.
6. `orderFlowRules_reviewed_v3.md` consolidation and a `decisions.md` entry, after the user's review.
7. Rebuild pass 2 (`distillation_2_v2`) from the raw sweep (G36c).

## ⚠ Attention

- **VM retention override.** `docs/runbook-ec2.md` tells the VM to set `config/risk.local.json` to
  `{ "logRetentionHours": 48, "dataKeepTradingDays": 7 }`. If the VM has that file, it still **deletes raw journals
  after 48 h** and keeps only 7 days of `data/`. That contradicts A1 (keep raw journals) and A7 (30 days). Per
  CLAUDE.md the dev side doesn't touch the VM's config: change it on the VM (remove the two keys, or set 0 / 30).
