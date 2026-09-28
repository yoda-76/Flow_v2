# Log analysis — 2026-09-28 (last night's open + tonight's long run)

Written 2026-09-28 ~20:40 IST from the journals (`logs/lvn_fade_test_*`), the recorded `data/`, MotiveWave's own log
(`%APPDATA%\MotiveWave\output\output (Sep-28 194554).txt`, and the earlier `022437` one) and the daily report.
**Analysis only — nothing was fixed.** The fix list is in `todo.md` ("FIX LIST 2026-09-28"). Last night's findings
(L-1…L-18) are in [`liveTest-2026-09-28.md`](liveTest-2026-09-28.md) and are **not repeated here**; this page adds what
tonight's ~40-minute run showed (findings **N-1…N-9**) and puts all seven sessions side by side.

## Sessions (all `lvn_fade_test`, `@GC`, 20-second bars, `SIM_LIVE`, armed, Simulated account)

| Session | When (IST) | Fills | Round trips | Win rate | Net | Notes |
|---|---|--:|--:|--:|--:|---|
| S1 `…1355399648` | 03:23 | 0 | 0 | – | – | 1-min chart, removed after 2 min |
| S2 `…742583690` | 03:25–03:45 | 22 | 11 | 27 % | −52 ticks (−$520) | max-reversals test (limit 20) |
| S3 `…859760374` | 03:46–03:55 | 17 | 8 (+1 open at removal) | 25 % | −39 ticks (−$390) | removed short → close dialog |
| S4 `…577018947` | 03:55–03:58 | 0 | 0 | – | – | flat removal |
| S5 `…1587484618` | 03:58–04:16 | 13 | 6 (+ L-1 kill/short) | 33 % | −19 ticks (−$190) | kill-switch test (limit 10) |
| S6 `…160254228` | 19:46–19:49 | 0 | 0 | – | – | started, removed after 3 min (before this run) |
| **S7 `…1949816597`** | **19:51–20:25** | **138** | **69** | **42 %** | **−165 ticks (−$1,650)** | limits `dailyLossLimitTicks` 90000, `maxReversalsPerSession` 100000 |

Daily report for the trading day (both nights): **94 closed trades, 36 win / 58 loss, −$2,750 gross**, one phantom
"still open" (L-3), three ALERTs from last night's kill switch. Account cash on Sim: 98,910 (03:23) → **96,090**
(20:25). Every `cash Δ` matches the trade prices to the dollar — the fill/cash journaling is exact, in all sessions.

## Tonight's run (S7) in numbers

- 69 round trips in ~34 min (~2 a minute): 40 stops, 29 targets, **0 unprotected moments, 0 orders on any account
  but Simulated** (every order line reads `simulated GCZ6.COMEX.RITHMIC`), position flat at every end-of-trade.
- Avg win **+3.7 ticks**, avg loss **−6.8 ticks**; longest losing streak 7; hold time median 3.2 s, max 25 s. It lost in
  every 5-minute bucket but the last: −45, −12, −10, −43, −26, −36, +7 ticks.
- Guards: **536 intents denied** — rate limit 472 (`6 changes in the last 60s, limit 6`), churn/dwell 64. No `lag`,
  `daily_loss`, `size_cap` or `armed` denials. No `KILL_SWITCH`, no disarm. So the raised limits were never the
  constraint; **the 6-per-minute rate limit is what shapes the trading** (≈ 3 round trips a minute at most).
- Heartbeats: 203, longest gap 10.0 s (the interval), 0 over 12 s. No `DOM_BACKLOG_SKIPPED`. MotiveWave's log has
  no `SEVERE` other than the known `OrderImpl::target order not found!` (L-8, one per fill/bracket), plus one new
  `ConcurrentModificationException` (N-4).
- Recorded data after the run (`data/*/GC/20723.jsonl`, ~1 h 25 min of session): liquidity map 21 MB, big trades 786 KB,
  footprint 392 KB, VWAP 259 KB, market structure 61 KB, bars 19 KB. Journal of S7: `decisions.jsonl` 1.7 MB,
  **`raw.jsonl` 19 MB** in 34 min (~33 MB/h). `logs/` is now 438 MB and `logRetentionHours` is 0 (keep everything).

## New findings

### N-1 · **High (for results, not safety)** · Brackets are anchored on the signal, entries fill ~1.6 ticks worse → the bracket is 7/3, not 5/5
**Seen (S7, all 69 trades; S2/S3/S5 the same):** the strategy asks for stop/target 5 ticks either side of the signal
price (`intent_changed`: stop −289 / target −279 around −284). The market entry fills later and worse, and the bracket
is placed from the *signal*, so measured **from the fill** the stop is 5–9 ticks (mode 7) and the target 1–5 ticks
(mode 3): stop + target = 10 in every trade. Mean adverse entry slippage ≈ **1.6 ticks** (7×29 + 6×19 + 8×8 + 5×8 + 9×4 +
1) / 69 = 6.64 ticks stop distance). Result: avg win 3.7 vs avg loss 6.8, so break-even needs a win rate of ~66 %;
observed 42 % (S2 27 %, S3 25 %, S5 33 %). Expectancy ≈ −2.4 ticks a trade (−$24) on Sim, where there are **no fees**.
This is code review **B8** / **L-6**, now measured across 94 trades. Note `lvn_fade_test` is a plumbing stress test, not
an edge — the point is that *even a perfectly random signal* would lose ~1.6 ticks a trade to this. **Needs the
user's decision** (offset the bracket from the fill; limit-or-marketable-limit entry; or widen). It also means the
strategy's own virtual exit (`target_hit`/`stop_hit`, L-5) and the real fills disagree by design.

### N-2 · **Med** · The pipeline keeps deciding after `DEACTIVATE`, and its state survives — a stale intent could fire on re-activation
**Seen (S7, journal tail):** `DEACTIVATE pos=0` at 20:25:06; then, before `DESTROY` at 20:25:11, a new signal —
`intent_changed targetPosition 1 stop −400 target −390` — went through the risk chain as **allowed** (armed still `true`),
followed by `reconcile_skipped "OrderGateway not yet constructed"` (L-10's misleading text) and a `holding` intent.
Nothing was sent, correctly. **But** last night's A6 test showed MotiveWave **reuses the study instance** across
deactivate → activate: the strategy, risk chain and sequencer keep their state while the gateway is torn down and
rebuilt. So after a re-activation the strategy may believe it is long (its own target 1) while the account is flat, and
the very first reconcile would send a **market entry on a stale signal**. **Unverified** — it needs a deliberate
deactivate/activate test on a quiet chart. Belongs with C1 (no single source of position truth). Same pattern in S3.

### N-3 · **Med** · Log volume: `raw.jsonl` ≈ 33 MB/hour and retention is "keep everything"
**Seen:** 19 MB for S7's 34 min; `logs/` 438 MB after eight days of mostly-off use. At ~0.8 GB a day this is fine for a
disk-rich dev machine and **not fine for a 24 h+ cloud run**. `logRetentionHours` (D-106) exists but defaults to 0 and
its wiring has been seen starting up but never *pruning* a real multi-day set. Set a value for the cloud run and watch
it prune once.

### N-4 · **Low** · `ConcurrentModificationException` inside MotiveWave's chart renderer (once)
**Seen:** 20:10:26.79 in MotiveWave's log, thrown by `com.motivewave.platform.ui.draw.graph…VerticalAxis` on the
JavaFX thread, right after `LIVE_BRACKET_SUBMITTED` / two `ORDER_MODIFIED` callbacks (the platform redrawing order
lines). Stack has no FLOW class in it; trading was unaffected (the bracket that was being submitted filled normally).
Recorded so it isn't rediscovered — worth one look only if it recurs, and to check that our chart-drawing code
(`Strategy signals — chart drawing`) never touches chart objects off the UI thread.

### N-5 · **Low** · Clock offset between this PC and the feed is not constant
**Seen (heartbeat `exchangeTimeMs − localTimeMs`):** tonight **+0.8 … +1.9 s** (feed ahead of the PC; the local clock is
behind), last night **+0.2 s**. Order-fill journal lines therefore carry a local `t` up to ~1.8 s before `lastFillTimeMs`
(L-18). This is E4 (*check Windows time sync*) recurring — the risk clock is the PC's clock. Do a manual sync
(`w32tm /resync`) on both machines before a long run.

### N-6 · **Low** · The order journal cannot measure entry latency or slippage
**Seen:** `real_order_submitted` has **no timestamp and no signal price** (fields: orderType, instrument, side, qty,
positionBefore, cashBalance, reason). N-1 had to be measured *indirectly* (bracket distance from the fill). Add
`t` and the signal/intent price so submit→fill time and slippage can be read straight from the journal.

### N-7 · **Low** · The analysis tools keep showing last night's alerts and the phantom open trade
**Seen:** `status.py` now says `STOPPED ALERTS 4 … position: flat (1 open trade)`; `daily_report.py` lists the four
kill-switch/`untracked` alerts and "1 trade STILL OPEN". Both come from L-1 / L-3 (a manual close the journal never
saw) and will stay in every report for the trading day. Needs L-3 fixed (route non-study fills), or a way to mark an
alert reviewed. Until then read the numbers with that in mind.

### N-8 · **Low** · Rithmic reported `permission denied` for the order book of `GCZ6` at workspace load
**Seen:** 19:46:23, twice (`get_order_book error : 13 … permission denied symbol: GCZ6`), **before** our study started.
Our recorder still got depth (`liquidity_snapshot` rows on every second, `SUBSCRIBED_DOM symbol=@GC`), so this looks like
MotiveWave's own chart DOM request lacking an entitlement, not our subscription. Recorded because the liquidity-map
inputs depend on depth actually arriving (runbook §13.1 B4) — spot-check `data/liquidity_map/GC/*.jsonl` has non-empty
`bidRows`/`askRows` after each session (they did tonight).

### N-9 · **Info** · What is verified by tonight's long run, on top of last night's list
Sustained ~2 round trips a minute for 34 min with no growth in lag, exact cash reconciliation on 69 trades, brackets on
the tick grid, sibling leg cancelled by the platform every time, no unprotected position, every heartbeat healthy,
config layering (`risk.local.json` overrides and `risk_config_loaded` values) correct, memory not measured this time.
**Not** verified by it: the kill switch (limit 90000 was never near), session-end flatten, day roll, partial fills.

## Loose ends to remember
- `config/risk.local.json` on this machine is now `{ "maxReversalsPerSession": 100000, "dailyLossLimitTicks": 90000 }`.
  It is **git-ignored: the other machine does not have it** — without it the defaults apply (200 ticks, 20 reversals,
  i.e. ~10 round trips per session) unless the user creates the file there.
- Big-trade Min Size is still **1** (test value, last night's note).
- MotiveWave is closed and the study removed; `status.py` reads `STOPPED`.

## Addendum, 21:53 IST — Rithmic disconnect with a position open (a real test case)

Session `lvn_fade_test_1790610724729_inst370945978`, evidence: MotiveWave log 21:53:17–21:56:20, the journal, `raw.jsonl`.

**What happened.** 21:53:17 long 1 filled at 4162.8, bracket stop 4161.7 / **target 4162.7 (1 tick BELOW the fill — the signal-anchored bracket, F-7/N-1, produced a target already through the market)**. At **21:53:21** all four Rithmic connections
(market data, trading, PnL, history) went "Broken" at the same instant; MotiveWave popped "Service Disconnected: RN". Last tick 21:53:22.7. Reconnected **21:56:04** ("no quotes posted for 163907 ms").

**What the system did.** Nothing wrong, nothing noticed: heartbeats stayed `healthy:true` (exchange time frozen at 21:53:22.7, local clock advancing — by design, D-93), no alert, no
disarm. On reconnect the resting target (SELL LMT 4162.7) **filled at 4171.0**: +82 ticks, **+$820** (cash 94,750 → 95,570) — the Sim engine matches only on a tick, so it filled at the first post-outage price. A real exchange would have executed a marketable
limit at once (about breakeven). Then the sibling-cancel/benign path, the account watch (`explainedByFill`) and the new entry all behaved normally. The strategy re-entered within 3 s on the 82-tick-gapped price.

**Findings**
- **N-10a · High (for a real account)** A dead feed with a position open is invisible: no ALERT, entries continue on resume. With the trading connection also down, neither the kill switch nor a flatten could have sent an order for 2 m 43 s. On Sim nothing protected the position either (the local engine can't fill a stop without ticks) — a real account's resting stop would live at the exchange, this one did not.
- **N-10b · Med** Sim fill artifact: a limit filled 8.3 points through its price. Every P&L figure that includes it (tonight's dev-machine total, +$820) is wrong for strategy evaluation. The report should flag fills far from their limit/stop price.
- **N-10c · Med** The 164 s of ticks were **not** replayed into the pipeline (`raw.jsonl`: 165 s hole, price jumps 67 → 149 ticks; MotiveWave backfilled 7,296 ticks for its own chart). The footprint/VWAP/volume-profile/big-trade/market-structure recordings and features have a hole and a discontinuity; nothing marks it.
- **N-10d · Med** First-tick-after-outage trading: entries resumed on the very first reconnect ticks with the features stale.
- **N-10e · Info** Cause of the disconnect is not in our logs (all four connections dropped in the same 50 ms, then reconnected by themselves) — network or the Rithmic/Lucid server, not the study.

## Addendum, 22:xx IST — latency and slippage, quantified (S7: 69 entries, S8: 82 entries; both dev machine, Sim)

Method: for each entry, the signal = the raw tick whose `seq` fired the intent (`raw.jsonl`: receipt time, last price, bid, ask); the fill = the `order_fill` record. Script: scratchpad `lat.py` (not in the repo).

| | S7 | S8 |
|---|---|---|
| signal tick received → entry fill callback (local clock) | median 0 ms, max 40 ms | median 0 ms, max 60 ms |
| signal event time → fill time (exchange stamps) | median 0 s | median 0 s |
| entry fill vs the **last-trade price** at the signal (ticks, + = worse) | mean **+1.64**, median 2, p90 3, range −4…+4 | mean **+1.94**, median 2, p90 3, range 0…+6 |
| entry fill vs the **touch** (buy vs ask / sell vs bid) | 54 % exactly at the touch, 35 % one tick better, 3 % one worse | 62 % at the touch, 32 % one better |
| quoted spread at the signal | median 2 ticks (mean 2.2) | median 2 ticks (mean 2.3) |
| correlation of latency with slippage | 0.07 | −0.02 |

**Reading:** on Sim there is **no latency at all** (the fill callback arrives within ~50 ms of the tick that fired the signal, and the fill is stamped with that same tick's time). The ~1.6–1.9 ticks are **not delay, they are the spread**: the signal fires on a last-trade price that sits on one side of a ~2-tick-wide book, and the market order buys the ask / sells the bid. Real-account latency (tens to hundreds of ms plus queue/impact) will come **on top** of that and cannot be measured on Sim. The journal cannot measure it later either until `real_order_submitted` carries a timestamp and the signal price/touch (F-11).
Exit side (S7, 40 stops): 34 filled at the trigger, 6 within 1–2 ticks worse (mean ≈ 0.25 tick); limit targets fill at the limit or up to 1 tick better.
