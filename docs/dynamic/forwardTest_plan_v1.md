# Forward test plan v1 — Matteo Conti S1/S2/S3 + Daily Anchored VWAP, live on separate Sim accounts (2026-11-02)

Goal (user, 2026-11-02): run strategy 1 (Vault Break), 2 (VWAP pullback + ADX), 3 (Overnight-bias ORB) and the Daily Anchored VWAP
strategy **live, each on its own MotiveWave Simulated account**, and forward-test them for a while. Rules: the research logs in
`docs/dynamic/strategies/` and the distilled rules in `docs/video_transcripts/strategies/distilled/`. Status: **plan, nothing built yet.**

## 1. What exists vs what is missing (survey of the code, 2026-11-02; key claims re-checked)

| Need | Today | Gap |
|---|---|---|
| Several Sim accounts | MotiveWave supports multiple Simulated accounts; "Sim Trade Only" covers all of them [DOC, motivewave-docs.md "You can have multiple simulated accounts in one workspace"]. One study per chart, each with its own pipeline, risk chain, order tracker and journal. | `AccountPolicy` accepts only the id `simulated` (D-115); any other id disarms and locks the study. The ids of extra Sim accounts are unknown until probed. |
| Instruments | `InstrumentPolicy`: root `GC`, tick 0.1 only. | MNQ (S1–S3) and MES (AV) refused → no orders at all. |
| Bars / indicators | Strategy gets `MarketState` only; one `BarEvent(CLOSE)` per chart bar; chart history read only for market-structure warm-start. | No bar-history accessor, no EMA/ADX/ATR, no daily true range, VWAP only tick-weighted from the 17:00 CT session (no 00:00 / 08:30 anchor). |
| Strategy parameters | Constants in each strategy; `StrategyConfig` carries only contract counts. | Per-strategy parameter files. |
| Entries / brackets | Market entry, then a stop + limit bracket (independent orders, cancel-sibling on fill), only if BOTH levels are given. | Stop-only / no-bracket entries (AV has no stop). |
| **Exits** | Only the bracket legs, the daily-loss kill switch and the 16:00 session flatten. A strategy's own close intent is **logged but not executed**; flips refused; no order modify. | Time exits (14:30 / 14:55 CT), signal exits (AV), trailing TP/SL (S2), close-then-reverse (AV). |
| Session flatten | Global: 15:55 CT daily + weekends, from `risk.json`, same for every study. | Per-study: AV holds overnight/weekends. |
| Daily loss | Ticks, account-cash based, global value. | Per-strategy values (an S1 stop alone is 300 ticks). With separate accounts the cash is not shared — good. |
| Trade caps | Rate limit / reversals only. | 3/day (S1), 4/day + 2-loss stop (S2), 1/day (S3) — inside each strategy. |
| File isolation | Session journals per instance. | S1, S2, S3 all on MNQ share `data/<construct>/MNQ/…` and the per-feature daily logs → double recording / interleaving. |
| Parity testing | `ReplayHarness` (tick journals), separate `BacktestEngine` (not the live strategy interface). | A bar-replay driver that runs the **live Java strategy** over the 1m CSV and compares its trades with the Python backtests. |

## 2. Build phases

1. **Core features (SDK-free, low risk):** bar-history feature fed by chart warm-start + live bar closes; indicators (EMA, Wilder ADX,
   ATR, daily true range from intraday bars, VWAP with configurable anchor); per-strategy parameter files `config/strategies/<id>.json`.
2. **Strategy ports** (`FlowStrategy`, no SDK imports): S1, S2, S3, AV, each with its own trade caps and time rules.
3. **Parity harness:** run each Java strategy over `analysis/data/*_1m_latest.csv` (resampled to its timeframe) through the real
   pipeline in dry-run; compare entries/exits/P&L with the Python trade lists. **Acceptance before any live run:** trades match.
4. **Execution changes (safety-critical, reviewed line by line, FakeBroker tests):**
   a. execute a strategy close intent: cancel bracket legs by reference → close at market → verify flat (enables time/signal exits);
   b. entry with stop only or no bracket (AV);
   c. trailing levels for S2 (order modify, or cancel-and-replace) — see question 3;
   d. reverse = close, verify flat, then open (AV);
   e. per-study overrides: session flatten (AV off), daily-loss ticks, size cap;
   f. `InstrumentPolicy`: allow MNQ and MES (tick 0.25), Sim only;
   g. `AccountPolicy`: an allowlist of Sim account ids from config (default stays `simulated`).
5. **Isolation:** per-instance data/log paths (or a single recorder per symbol).
6. **Deploy + live checks:** read-only probe of each Sim account's id; user creates the accounts, adds the studies, arms each one.
7. **Forward-test monitoring:** daily script comparing live fills with the Python backtest run on the same days (slippage, missed
   trades, rule mismatches).

## 2a. Design decisions (Claude, 2026-11-02 — safety-critical parts, for review)

- **All four studies run on 1-minute charts (24-hour session), and each strategy builds its own 3m / 15m / 30m bars** from the 1m
  bars (CT clock buckets, exactly like the Python backtests). This gives identical inputs live and in the parity harness, and lets
  VWAP / daily range be computed from 1m bars as in the backtests. Charts must load ~3–4 weeks of 1m history (15-session daily range).
- **Strategy exits (time exit, signal exit):** a close intent (target 0) while the tracker's own position is open runs the same
  sweep the session flatten already uses live (`decide()` → cancel legs + close at market, or cancel only if already flat; then the
  1.5 / 3 / 5 s flat verification). This is the 2026-09-21 "no software close" rule reversed, so it is done deliberately: the race
  that rule avoided (a strategy close and a bracket leg both closing) is handled by the verification + correction path that the
  kill switch and session flatten already rely on. When the positions disagree (HOLD), the tracker returns a refusal so the
  pipeline retries every second.
- **Trailing (S2):** same position, new stop/target ticks → modify the resting legs in place (`setAdjStopPrice` /
  `setAdjLimitPrice` + `submitOrders(existing)`; SDK javadoc: "otherwise the existing order will be modified"). A new level that is
  already on the wrong side of the market (stop at/above the bid for a long, target at/below it) becomes a strategy exit instead of a
  marketable modify (matches the backtest's gap exit). Never tried live → Sim probe before relying on it. Level-only changes pass
  the rate-limit and churn checks (they only count position changes) — checked in `RiskChain`.
- **No-bracket entries (AV):** already supported (an intent without stop/target opens without legs; journaled `LIVE_FILL_NO_BRACKET`).
  AV never flips directly (its rules exit first, enter only when flat), so flips stay refused.
- **Per-strategy risk:** a third config layer `config/risk.<strategyId>.json` after `risk.json` and `risk.local.json`
  (flatten lead, daily loss ticks, size cap). AV gets `flattenLeadMinutes: 0` (no session flatten — note this also removes the
  no-new-entries window for that study only).
- **Instruments:** `InstrumentPolicy` allows roots GC (tick 0.1), MNQ and MES (tick 0.25). Micros only; NQ/ES stay refused.
- **Accounts:** `AccountPolicy` accepts an allowlist of Sim account ids from config (default `simulated` only). At activation the
  study builds — but never submits — one order object and journals its `getAccountId()` (`account_probe`); an id not on the list
  refuses to arm before any order exists. The gateway already checks the id on every order before sending.
- **Same-symbol studies:** S1, S2, S3 all trade MNQ. A study setting `FLOW_RECORD_DATA` (default on) lets two of them skip the
  shared `data/` recording, so it is written once.
- **Operational caveat (AV):** a position that exists when a study is (re)activated is never touched by that study (D-24). If the
  AV study is restarted while holding, its position must be handled by hand.

## 2b. Progress (2026-11-02, not committed yet)

| Phase | State | Evidence |
|---|---|---|
| 1. Bar history + indicators | **done** | `flow-core/src/com/flow/bars/**`; `BarsAndIndicatorsTest` — 237,292 checks against Python-generated golden values (ADX, VWAP 08:30 / 00:00 / prev-day 17:00, daily-range SMA15) on 6 weeks of NQ 1m bars; mutation-checked. Runtime: `FLOW_BAR_HISTORY_BARS` warm-start + `bar_history_warm_start` journal record; `tickSize` and `strategyConfigPath` passed to strategies. |
| 4. Execution changes | **done (except FLOW_RECORD_DATA)** | Strategy exit via the flatten sweep; trailing legs modified in place, crossed level → exit; account probe at activation (`ACCOUNT_PROBE`, refuse to arm on an unlisted account); `simAccountIds`; `risk.<strategyId>.json` layer; `InstrumentPolicy` GC/MNQ/MES. New `LiveOrderTrackerTest` cases + policy tests; the old "close is bracket-only" test updated deliberately; two mutations (no position check in the exit; no crossed-level guard) both caught. Full `build.sh` gate passes (deployed only to a temp dir). |
| 2. Strategy ports | next | |
| 3. Parity harness | next | |
| 5. Same-symbol isolation (`FLOW_RECORD_DATA`) | next | |
| 6. Deploy + live checks | after 2–5 | Live-unverified so far: order modify on Sim; whether an unsubmitted probe order shows in MotiveWave's UI; each extra Sim account's id. |

## 3. Decisions (user, 2026-11-02)
- **Machine:** the forward test runs on the **EC2 VM**, but is tested on **this dev machine first**.
- **Size:** **1 micro each** — S1, S2, S3: 1 MNQ; Anchored VWAP: 1 MES.
- **S2 exits:** **build trailing now** (order modify or cancel-and-replace for the TP/SL legs).
- **Anchored VWAP:** **as designed — holds overnight and over weekends**, exits only on its own signal; its study has no session flatten.
