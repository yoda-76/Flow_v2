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
