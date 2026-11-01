# Daily Anchored VWAP Signal Strategy (`DailyAnchoredVwapStrategy`)

Research log and findings for one strategy. **Append to this file as research continues** (new entries at the bottom of
section 4, then fold the conclusion into section 5 and update the status line). Nothing here is a trading decision; entries are
evidence, tagged `[BACKTEST]` (a run of our Python port on recorded data), `[PROP-SIM]` (a prop-firm account simulation on backtest
trades), `[DATA]` (a check of the input data) or `[CODE]` (a change to the tooling).

- **Status:** research in progress. Last updated 2026-10-31.
- **Instrument tested:** MES (micro E-mini S&P 500) 1-minute bars, 2021-08-18 to 2026-10-30. Also MNQ (NQ prices, $2/pt) for cross-checks.
- **One-line verdict so far:** profitable on MES 2023 to 2026 with the platform settings, but the result depends on those exact
  settings, on having no stop, and on the 2023-2026 market. It lost in 2022 and no setting chosen on one year reliably worked the
  next. See section 5.

## 1. Summary of where we are

| Question | Answer so far | Confidence |
|---|---|---|
| Does it make money on MES with the platform settings (3m, EMA 11, rel ATR 1.1, no stop, 3 contracts)? | Yes, 2023-01-01 to 2026-10-30: 416 trades, +$30,173 on $25k, PF 1.83, max closed-trade drawdown $2,552. | High that the arithmetic is right. |
| Is that an edge we could have found in advance? | Weak evidence. Those settings are the best of 360 in hindsight. Re-optimising each year gave +$13.5k (rolling) and +$7.6k (anchored) out of sample, t about 1.2. | Low to medium. |
| Is it a cost or fill artifact? | No. Survives fills at the next bar open and 3 ticks of slippage each way (about +$23k). | High. |
| Is it the uptrend/downtrend regime? | Not the 100-day trend. Excluding 2022, downtrend days were the best bucket. 2022 itself lost on both sides. | Medium. |
| What predicts better trades? | Late-day entries (14:00 to 15:59), gap-up days, Wednesday (and not Friday), the trade after a same-day loss, longer holds. | Medium in-sample (consistent across years, small samples). **As filters they do not help out of sample** (B-13, B-14). |
| Can it pass and live on a Lucid prop account? | Only barely, and only with small size. 3 contracts breach often. See B-06 and B-08. | Medium (rules are third-party). |

## 2. The strategy as ported

Source: `DailyAnchoredVwapStrategy` (rq_sdk, Rust). Port: `analysis/gb_nq_backtest/anchored_vwap.py`. Signals are generated on bar close.

**Source defaults:** typical price on; session 09:30 to 16:00 ET; EMA 20; 1 contract; ATR 14; ATR stop on at 2.0x; relative-volatility filter
on (baseline ATR 50, maximum relative ATR 1.20); 2 additional attempts after the first loss of the day.

**Platform settings used for the headline result** (from the platform run config the user supplied, 2026-10-31):
MES, 3-minute bars, EMA length 11, **3 contracts**, ATR period 14, **ATR stop off**, baseline ATR 50, **maximum relative ATR 1.1**,
2 additional attempts, $10k capital, $0.50 per contract, 100x leverage. In our runs: $25k account, $1 round trip per contract.

**Logic (one `if / else-if` ladder per bar, so one event per bar):**
- VWAP resets at the ET calendar date change (midnight ET), not at the session open, and accumulates every bar including overnight.
- Signals only inside 09:30 to 16:00 ET. A regime variable steps through `-1/-2/-3` (bearish sequence) or `1/2/3` (bullish sequence)
  using EMA, VWAP and bar close. Markers: a **green dot enters long**, a **red dot enters short**; a **green triangle closes a long**, a
  **red triangle closes a short**. Entry needs: allowed (attempt rules), volatility allowed (ATR/baseline ATR <= limit), flat.
- Exit is only a triangle signal or the ATR stop (if on). **There is no session flatten and no time exit**, so a position can stay open
  overnight or over a weekend (longest hold seen 187 hours). Triangles only fire inside 09:30 to 16:00 ET.
- Daily attempts: after a first win no more trades that day; after a first loss, up to 2 more closed trades.

**Quirks kept on purpose** (as written in the source): early returns (no EMA, no volume, ATR not ready) happen before the previous close
and VWAP are updated; the VWAP anchor is midnight ET; marker colour names are the reverse of the usual long/short convention.

## 3. How it is tested

- **Data:** 1-minute CSVs from MotiveWave exports in `analysis/data/` (`MES_1m_latest.csv`, `NQ_1m_latest.csv`, `GC_1m_latest.csv`). **The files
  are not back-adjusted for contract rolls** (see B-02).
- **Bars:** built from 1m, aligned to the UTC epoch (equals ET). The source never states a timeframe; the platform run uses 3m.
- **Fills:** market orders at the signal bar close plus or minus 1 tick (adverse). An ATR stop fills at the stop or the 1m open if it
  gapped through. `--fill next_open` fills at the open of the next 1m bar instead (more realistic).
- **Costs:** $1.00 round trip per contract (the user's $0.50 per side), 1 tick of slippage each way, MES $5 per point, tick 0.25.
- **Profit factor (PF):** early runs (B-01, B-03, B-04) report R-based PF from the engine; later runs report dollar PF. They differ slightly
  (for example 2.15 R-based vs 2.05 dollar-based for the same trades).
- **Not modelled:** margin, taxes, evaluation fees (set per run), payout processing charges, the Lucid daily loss limit (sources conflict).

## 4. Research log (chronological)

### B-01 `[BACKTEST]` 2026-10-31, MNQ, source defaults, last year
Window 2025-10-14 to 2026-10-14, 5m bars, $25k, 1 contract. 135 trades, win 39.3%, PF 1.36 (R-based), **+$2,459 (+9.8%)**, max drawdown
$2,627. Exits: 86 triangle (PF 5.85), 49 ATR stops (all losers). 12 trades held over 10 hours.
Timeframe sensitivity (same settings): 1m -$969, 3m -$2,480, 5m +$2,459, 15m +$1,386. The sign flips with the timeframe.
Output: `reports/avwap_mnq_5m`, `_1m`, `_3m`, `_15m`.

### B-02 `[DATA]` 2026-10-31, MES export validation and contract rolls
First export (2024-06-04 to 2026-10-30, 852,758 bars) and a re-export (2021-08-18 to 2026-10-30, 1,840,140 bars) both pass: sorted, no
duplicate timestamps, no OHLC violations, all prices on the 0.25 grid, no zero-volume bars, gaps are only the daily break, weekends and
holidays. One-minute returns correlate 0.92 with NQ at the same timestamp (clock is aligned).
**Rolls are not adjusted.** Every quarterly roll shows a jump of +51 to +74 points ($255 to $370 per MES) on the **Monday of expiry week**
at the daily break: 2024-06-17, 2024-09-16, 2024-12-16, 2025-03-17, 2025-06-16, 2025-09-15, 2025-12-15, 2026-03-16, 2026-06-15, 2026-09-14.
A first detection pass picked weekend gaps for 2025-06 and 2026-03 (real weekend moves were larger than the roll); fixed by restricting to
the daily 1-hour break. The NQ and gold files almost certainly have the same issue; earlier NQ and gold results were not roll-filtered.

### B-03 `[BACKTEST]` 2026-10-31, MES, source defaults, last year
Window 2025-10-30 to 2026-10-30, 5m bars, 1 contract, $25k. 141 trades, win 30.5%, PF 0.98, **-$329 (-1.3%)**. Timeframe sensitivity: 1m -$916,
3m -$110, 15m +$840. Weaker than MNQ over a similar year (B-01).

### B-04 `[BACKTEST]` 2026-10-31, MES, platform settings, 2025-10-14 to 2026-10-14
3m, EMA 11, **3 contracts**, no ATR stop, max relative ATR 1.1, $10k. 112 trades, win 58.9%, PF 2.15 (R-based), **+$11,612 (+116.1% of $10k)**,
every exit a triangle. Max drawdown $2,247 closed-trade, $2,406 marking open trades; worst closed trade -$2,174 (21.7% of $10k); worst
open loss -$2,396. Median hold 0.8 hours, 90th percentile 69 hours, longest 187 hours; 45 trades held over 10 hours.
By quarter: -2.8R, +48.0R, +14.7R, +23.8R. Output: `reports/avwap_mes_user`.

### B-05 `[BACKTEST]` 2026-10-31, remove roll-day trades (user request)
`roll_filter.py` drops trades entered on a roll day. For B-04: 1 trade removed (+$241), result **+$11,371 (+113.7%)**, PF 2.03; no trade was open
across a roll jump. The rolls happen after the 16:00 close and the relative-ATR filter blocks entries while the gap inflates ATR, so the
effect is small. For 2023-01-01 onward: 4 roll-day entries (+$337) removed and 1 trade open across a roll (+$64) kept (see B-07).

### B-06 `[PROP-SIM]` 2026-10-31, Lucid 25K and 100K lifecycle on the B-04 trades
Rules from third-party sites (see section 7 caveat): 25K: target $1,250, max loss limit $1,000 end-of-day trailing (locks at $25,100 once
the balance reaches $26,100), 20 micros max, 40% consistency and a $26,100 payout buffer in the funded phase, 90/10 split. 100K: target $6,000,
limit $3,000, buffer $103,100, first payout cap $2,500. Evaluation fee $100 (user figure, 25K); a new evaluation is bought only after a
lost evaluation or a breached funded account. Breach is tested on the worst open loss inside each trade.
- 25K, last year, 3 contracts: 7 evaluations bought (3 passed, 4 failed), 2 funded breaches, 1 payout of $1,000 ($900 net); cash +$200.
  The worst open loss in one trade (about $2,400) exceeds the $1,000 allowance, so accounts are lost to a single trade.
- 25K, last year, 1 contract: 1 evaluation, passed 2026-04-08 (6 months), no breach, no payout; cash -$100.
- 25K, last year, 2 contracts: 6 evaluations, 4 passed, 3 funded breaches, no payout; cash -$600.
- 25K, whole MES file (2024-06 to 2026-10), 1 / 2 / 3 contracts: net +$1,700 / +$2,200 / +$1,900 after $100 fees.
- 100K, 3 contracts, last year: 1 evaluation, passed 2026-05-18, no breach; no payout at $2,500 requests, 1 payout with $1,000 requests.
  Whole file: 1 evaluation passed 2025-04-16, 2 payouts ($5,000 gross).

### B-07 `[BACKTEST]` 2026-10-31, MES, platform settings, 2023-01-01 to 2026-10-30 (after the re-export)
$25k, 3 contracts. **416 trades, win 56.2%, PF 1.83 (dollar), +$30,173 (+120.7%)**, max closed-trade drawdown $2,552 (10.2%), worst day
-$2,174, best day +$2,956. By year: 2023 +$8,162 (PF 2.14), 2024 +$2,146 (1.18), 2025 +$6,520 (1.75), 2026 +$13,346 (2.53). Longs +$18,584
(PF 2.15), shorts +$11,590 (1.57). With roll-day entries removed: 412 trades, +$29,836 (+119.3%), max drawdown $2,650. Output: `reports/avwap_mes_2023`.

### B-08 `[PROP-SIM]` 2026-10-31, user-defined "doubling" payout schedule on the B-07 trades
Schedule (not a Lucid rule): payouts 100, 200, 400, 800, 1,600, then +$500 each (2,100, 2,600, ...). Doubling continues while the previous
payout is below $1,000. A payout is taken when the funded account's profit (balance minus start, net of earlier payouts) reaches twice the
next payout, so half is paid out and half stays. Verified against the user's table with synthetic trades (all six rows match).
Result, Lucid 25K rules for evaluation and loss limit, 3 contracts, 2023-01-01 to now, $100 per evaluation: **24 evaluations bought (12 passed, 12 failed),
12 funded accounts created and 11 breached, 26 payouts totalling $13,200 ($11,880 after 90/10), net +$9,480**. Payouts by year: 2023 $5,200,
2024 $700, 2025 $1,800, 2026 $5,500. The current funded account is open at $28,652.
Reference on the same trades with Lucid's published payout rules ($1,000 requests, balance must stay above $26,100, 40% consistency): 2
evaluations, 0 funded breaches, 6 payouts ($6,000 gross, $5,400 net), net +$5,200, and a funded balance still open at $48,132. The two are not
like-for-like (unwithdrawn profit). The $100 and $200 payouts are not allowed by Lucid's published rules (minimum request $500, buffer).

### B-09 `[BACKTEST]` 2026-10-31, is the edge real? (`edge_check.py`, `anchored_vwap.py --fill/--slip-ticks`)
- **Fills and costs:** next-1m-open fills +$31,719; slippage 2 ticks +$27,602 (close fill) / +$28,150 (next open); 3 ticks +$22,937 / +$22,978. Not a cost artifact.
- **Significance (2023 to 2026, in-sample):** per-trade t = 3.33; bootstrap over 378 trading days gives total P&L 5th to 95th percentile +$15.7k to +$44.8k.
- **Concentration:** the best 10 trades are 54% of the profit; without the best 20 trades the total is +$3,222. 7 of 416 trades lost more than $1,000.
- **Holding time:** 181 trades held over 10 hours made +$25,006 (PF 1.80); 202 trades under an hour made +$1,366 (PF 1.27).
- **Stop:** with the 2x ATR stop on, 2023 to 2025 is PF 0.61 (-$9,825). The profit depends on having no stop.
- **Never-seen period (2021-09 to 2022-12, same settings):** PF 0.75, -$6,383, 149 trades. 2023 to 2025: PF 1.72 (+$18,190); the last year PF 2.07.
- **Neighbouring settings:** EMA 9/11/15/20 x relative ATR 1.0/1.1/1.2/1.5: the platform pick is the best cell for 2023-2025; neighbours are
  worse; in 2021-22 almost every cell loses. EMA 15 loses in the last year.
- **Timeframe:** 3m works; 1m about breakeven (PF 0.97 in 2023-25); 5m PF 1.19; 15m PF 1.08 and 0.83 last year.
- **Other instrument:** the same rules on MNQ: 2023-26 PF 1.13 (+$13,094); 2019-22 PF 0.89 (-$8,922). Much weaker than MES on nearly the same market.
- **Flip test** (same entry/exit times, opposite direction) lost -$38.9k. Not valid proof: the exit times depend on the original direction.

### B-10 `[BACKTEST]` 2026-10-31, optimise on 2022, test on 2023 onward (`optimize_wf.py`)
Grid of 360: timeframe {3,5,15} x EMA {5,9,11,15,20,30} x max relative ATR {1.0,1.1,1.2,1.5,off} x stop {off,2x,3x,5x ATR}. Score = net / max(closed-trade
max drawdown, $500), at least 40 trades, fixed before looking. Chosen on 2022: **15m, EMA 15, no volatility filter, 2x stop**: 2022 +$23,327 (PF 1.72) but 2023 to now
**-$3,905 (PF 0.97)** with drawdown $24,877. Rank correlation between the 2022 score and test net: -0.07. The top 20 settings from 2022 averaged -$6,468 on the test.
Regime flip: in 2022 slow, loose, stop-protected settings won; from 2023 only fast 3m settings with a tight filter and no stop worked. Output: `reports/optimize_wf.csv`.

### B-11 `[BACKTEST]` 2026-10-31, rolling and anchored walk-forward (`walk_forward.py`)
Same grid and score. Each setting run once over the whole file; each fold picks on its train window only.
- **Rolling (1-year train):** 2023 chose 15m EMA15 2x stop: +$5,018 (PF 1.21); 2024 chose 3m EMA11 rel 1.2 no stop: +$351 (1.02); 2025 chose 5m EMA5 no filter no stop: +$3,822 (1.12);
  2026 (to October) chose 15m EMA9 rel 1.0 3x stop: +$4,266 (2.01, 37 trades). **Total +$13,457, PF 1.17, 711 trades, closed-trade max drawdown $8,273.**
- **Anchored (train on everything before):** total **+$7,572, PF 1.12**, 636 trades; 2025 lost -$1,257 (23 trades).
- For the same four years: the average of all 360 settings -$3,445; the platform settings +$30,173 (selected in hindsight); the best setting each year in hindsight +$45,326.
- Better than random: the chosen settings beat 99% (rolling) and 92% (anchored) of random one-setting-per-year draws. Per-trade t = 1.22 and 0.79, not significant on their own.
- Persistence of profitable settings from one year to the next: 67%, 41%, 24%, 81% against base rates of 52%, 36%, 22%, 60%.
Output: `reports/walk_forward.log`, cached trades `reports/walk_forward_trades.pkl`.

### B-12 `[BACKTEST]` 2026-10-31, factor analysis of the platform-settings trades (`factor_analysis.py`)
519 trades, 2022-01-15 to 2026-10-30.
- **Regime:** above the 100-day average PF 1.67 (+$22,348, 392 trades) vs below PF 1.03 (+$834, 127). But all of the weakness is 2022 (81 downtrend trades, PF 0.60,
  -$8,144). From 2023 on, downtrend trades were PF 3.24 (+$8,978, 46 trades) vs uptrend PF 1.65. So "uptrend good, downtrend bad" is not the driver.
- **Consistent in every year (5 of 5):** entries 14:00 to 15:59 (2023+: PF 2.33, +$135.9 per trade, vs PF 1.47 before 14:00); gap up over 0.3% (2023+: PF 2.53 vs 1.62);
  Wednesday best (PF 2.67), Friday worst in all 5 years (PF 0.70, -$4,028); the trade taken after a same-day loss (PF 3.21, 59 trades). "Yesterday up" better in 4 of 5 years (PF 1.95 vs 1.08).
- **Not reliable:** 20-day trend, long vs short (both positive), 20-day volatility, month (about 40 trades per month).
- **Trade shape:** trades held under an hour are about breakeven; the profit is in holds of an hour or longer (2023+: PF 1.92 vs 1.27). Weekend holds: 50 trades, -$5,100, inconsistent by year.
- **Why entries cluster late:** the relative-ATR filter blocks entries while volatility is elevated after the open; only 3 entries in the 10:00 hour vs 141 at 12:00; the first half hour (09:30 to 09:59, 63 trades) was the worst bucket (PF 0.48).
- **2022 failure:** lost on both sides (longs PF 0.63, shorts 0.76), mostly in entries before 14:00 (PF 0.55) and holds over 10 hours (50% win, avg -$212).
Output: `reports/factor_analysis.log`.

### B-13 `[BACKTEST]` 2026-10-31, do the candidate filters survive walk-forward? Screening version (`filter_wf.py`)
Filters from B-12 applied to the cached trades of all 360 settings (trades that fail the filter are dropped after the fact), then the same rolling and anchored
walk-forward (best setting on the train window by the same score, at least 25 trades; trade it on the next year). **Caveat:** the filters were found in B-12 on 2022-2026
data, so the filter choice itself is not out of sample, and dropping trades after the fact is an approximation.

| Filter | Rolling OOS net / PF / trades / max DD | Anchored OOS net / PF / trades |
|---|---|---|
| none (baseline) | +$13,457 / 1.17 / 711 / $8,273 | +$7,572 / 1.12 / 636 |
| no Friday entries | +$5,522 / 1.10 / 436 / $11,828 | -$14,114 / 0.82 / 491 |
| entries 14:00 or later only | +$7,915 / 1.58 / 125 / $3,404 | -$5,225 / 0.71 / 113 |
| gap up > 0.3% only | +$4,320 / 1.25 / 164 / $4,964 | -$4,688 / 0.75 / 144 |
| no Friday + 14:00+ | +$6,787 / 1.34 / 114 / $5,341 | -$917 / 0.93 / 112 |
| 14:00+ and gap up | no eligible setting (under 25 trades in the train year) | +$748 / 1.21 / 22 |
| all three | no eligible setting | +$520 / 1.26 / 13 |
| no entries before 10:00 | +$6,012 / 1.13 / 398 / $10,243 | +$3,452 / 1.05 / 544 |

No filter beats the baseline on net in either walk-forward, and rolling and anchored disagree (for example "14:00+ only" is PF 1.58 rolling but 0.71 anchored). The only consistent benefit
is a smaller drawdown from fewer trades (14:00+ rolling: $3,404 vs $8,273). For context, the fixed platform settings (selected in hindsight) with the filters, 2023 onward:
none PF 1.83 (+$30,173), no Friday PF 2.09 (+$30,790), 14:00+ PF 2.42 (+$19,466), gap up PF 2.53 (+$12,417), no Friday + 14:00+ PF 3.55 (+$22,601).
The in-sample PF gain disappears when the settings are chosen walk-forward. Output: `reports/filter_wf.log`.

### B-14 `[BACKTEST]` 2026-10-31, engine-level confirmation of the best candidate: entries from 14:00 on (`walk_forward.py --filter late`)
The filter built into the engine (signals before 14:00 ET are skipped, later state unchanged), full 360-setting grid, same rolling and anchored walk-forward.
- **Rolling:** 2023 +$2,553 (15m EMA9, 2x stop), 2024 -$3,760, 2025 -$5,786, 2026 -$1,182. **Total -$8,176, PF 0.83**, 241 trades, max drawdown $14,349.
- **Anchored:** 2023 +$2,553, 2024 -$1,703, 2025 +$142, 2026 -$704. **Total +$289, PF 1.01**, 210 trades.
- Against the unfiltered baseline of B-11 (+$13,457 rolling / +$7,572 anchored) it is worse. The settings picked on the train years looked excellent in-sample (PF 1.9 to 4.8 on 40 to 70 trades)
  and failed the next year; the choice ranked 136, 355, 347 and 260 of 360 in the test years (rolling). Train-score rank correlations were -0.24, +0.20, -0.11, +0.17.
- Output: `reports/walk_forward_late.log`, trades cache `reports/walk_forward_trades_late.pkl`.
- Not engine-tested yet: "no Friday" and "no Friday + 14:00+" (flags `--filter nofri` and `latenofri` exist).

### B-15 `[CODE]` 2026-10-31, tooling added during this research
`anchored_vwap.py`: `--start`, `--end`, `--fill next_open`, `--slip-ticks`, `--ema`, `--contracts`, `--atr-mult`, `--no-atr-stop`, `--max-rel-atr`, and optional entry gates
`entry_min_hhmm` / `skip_friday` (used by `walk_forward.py --filter`). `prop_sim.py`: `--plan 25k|50k|100k`, `--request`, `--cycle-goal`, `--payout-mode lucid|doubling`, `--no-payout`.
`lucid_replay.py`: 25K, 50K, 100K plan rules. `roll_filter.py`: roll detection and `--write-filtered`. The default (unfiltered, 1-tick, close-fill) results were re-checked after each change
(+$30,173 on the 2023 to now run).

### B-16 `[BACKTEST]` 2026-11-01, month-by-month P&L since 2023 (original settings, 3 contracts)
46 months: 32 positive, 14 negative, median +$497, best +$3,494 (2026-04), worst -$1,796 (2025-12). By year: 2023 +$8,162 (4 losing months), 2024 +$2,146 (7), 2025 +$6,520 (2), 2026 YTD +$13,346 (1). Longest losing streak 3 months, worst month-end drawdown $1,951. Source: `reports/avwap_mes_2023/trades.csv`, grouped by ET exit month.

### B-17 `[BACKTEST]` 2026-11-01, can we say "pause the strategy for a month"? (`pause_rules.py`)
Question: which conditions predict a bad month. Data: MES 3m EMA 11 rel 1.1 no stop, 3 contracts, 2022 -> 2026 YTD (525 trades, +$23,482, 2022 alone -$6,691).
- Pause rules built on the strategy's own results (N losses in a row, last-N-trades loss, shadow drawdown, month-to-date stop), 50+ variants: **none beat "never pause" on 2023->now** (baseline +$30,173). Most cut profit by a third to a half, because winners cluster right after losers. Typical: 2 losses -> pause 14d gives +$17,245; shadow DD >= $1,000 -> pause 30d gives +$1,798.
- Only rule that was roughly neutral: stop trading for the rest of the month once month-to-date <= -$1,000: 2022->now +$27,331 (vs +$23,482), 2023->now +$29,844 (vs +$30,173). The gain is almost entirely June 2022 (one -$4,886 trade on 2022-06-09 plus -$1,976 and -$1,237 later that month). It is a tail cap, not a predictor.
- Market state of the previous month (realised vol of 1h returns, trend efficiency, chop, drift) vs this month's P&L, 58 months: Spearman rho +0.01, +0.01, +0.02, -0.10. Previous month's own P&L: rho -0.06. Nothing predicts the next month. Same month: higher volatility months are somewhat better (rho +0.27), which is no use in advance.
- Worst 10 trades (-$18.5k of -$58.6k total losses) are single long holds without a stop: 5 of them in 2022 (-$4,886, -$1,976, -$1,683, -$1,488, -$1,237). In 2023->now the worst single trade is -$2,174.
Conclusion: no usable "pause" signal found. Bad months look like normal variance (a handful of long holds going the wrong way) rather than an identifiable regime; consistent with B-10 to B-14. Output: console only; script `analysis/gb_nq_backtest/pause_rules.py`.

### B-18 `[BACKTEST]` 2026-11-01, how far do winners dip before they win? (max adverse excursion, MAE)
Data: `reports/avwap_mes_2023/trades_noroll.csv` (412 trades, 3 contracts), worst 1m high/low against the entry price while the trade was open, in dollars, before commission.
| | Winners (231) | Losers (181) |
|---|---|---|
| average dip | $174 | $390 |
| median | $56 | $191 |
| 75th / 90th percentile | $210 / $450 | $570 / $1,020 |
| worst | $2,396 | $2,336 |
| average result / hold | +$287 / 17.0 h | -$202 / 23.8 h |
Winners that dipped >= $500 first: 19 of 231 (their profit $8,860 of $66,327); >= $1,000: 4 (profit $2,680); >= $1,500: 2 ($1,415). A loser's median dip ($191) is close to a winner's 75th percentile ($210), so tight stops cut winners and losers alike; a stop near $1,000 would only have touched 4 winners. This motivated B-19. Computed ad hoc (not saved as a script).

### B-19 `[BACKTEST]` 2026-11-01, fixed-dollar stops of $60 / $180 / $250 / $500 / $1,000 (`stop_test.py`)
Setting: MES 3m, EMA 11, rel ATR 1.1, 3 contracts, stop = dollars for the whole position set at entry ($15/pt, so 4 / 12 / 16.7 / 33 / 67 pts), full engine (attempts logic reacts to stop-outs), 1 tick slippage on the stop fill, $1 round trip per contract, roll-day entries removed. New option `--stop-usd` in `anchored_vwap.py`.
| stop | 2022: net / PF / maxDD | 2023->now: trades / net / PF / maxDD / win% / stopped |
|---|---|---|
| none | -7,254 / 0.67 / 10,325 | 412 / +29,836 / 1.82 / 2,650 / 56% / 0 |
| $60 | -1,879 / 0.79 / 3,816 | 660 / -9,750 / 0.67 / 10,040 / 29% / 370 |
| $180 | -4,948 / 0.60 / 6,238 | 498 / -422 / 0.99 / 5,286 / 40% / 187 |
| $250 | -4,846 / 0.64 / 6,426 | 479 / -1,548 / 0.97 / 6,680 / 43% / 160 |
| $500 | -6,574 / 0.64 / 8,783 | 440 / +17,909 / 1.38 / 3,226 / 52% / 75 |
| $1,000 | -6,269 / 0.69 / 9,151 | 420 / +21,679 / 1.48 / 4,534 / 55% / 27 |
Findings: every stop lowers 2023->now profit and none lowers its max drawdown (closed-trade); the loosest ($1,000) still gives up $8.2k and raises max drawdown from $2,650 to $4,534 (stopped trades get re-entered and stopped again, and the stopped trades would have recovered, as B-18 suggested). Stops of $250 or less turn the strategy into a loser (PF 0.67-0.99). 2022 stays negative at every level; a stop reduces 2022's drawdown only by turning it into many small losses. Consistent with B-03/B-04: the edge needs the long no-stop holds. Output: `reports/stop_test/`.

### B-20 `[PROP-SIM]` 2026-11-02, Matteo Conti S1 + S3 plus a third strategy: S2 vs Daily Anchored VWAP, one LucidPro 25K account
Window 2021-09-01 → Oct 2026 (MES data starts Aug 2021). S1 + S3 at 1 MNQ each (fixed bases); S2 = VWAP pullback + ADX, trailing exits,
1 MNQ; AV = Daily Anchored VWAP, MES, platform settings (3m, EMA 11, no stop, max rel ATR 1.1), $1 RT. Rolling start, no time limit.
`prop_multi.py` extended for a second instrument and for overnight holds (a trade open at an attempt's start is excluded from that
attempt). Output: `reports/prop3/replace_s2`, `reports/prop3/av_c1..c3`.

| Set | Trades | Net $ | Max DD $ | Worst day $ | Worst trade $ | Longest hold | 25K pass % | Median days to pass |
|---|---|---|---|---|---|---|---|---|
| S1 + S3 | 1,651 | 33,812 | 1,641 | −561 | −582 | 5.5 h | 68.6 | 29.5 |
| S1 + S2 + S3 | 3,141 | 36,237 | 1,813 | −684 | −582 | 5.5 h | 68.9 | 27 |
| S1 + S3 + AV 1 MES | 2,224 | 41,949 | 3,816 | −1,867 | −1,628 | 285 h | 64.6 | 21 |
| S1 + S3 + AV 2 MES | 2,224 | 50,086 | 6,512 | −3,508 | −3,257 | 285 h | 53.8 | 15 |
| S1 + S3 + AV 3 MES | 2,224 | 58,223 | 9,370 | −5,148 | −4,886 | 285 h | 45.4 | 11 |
| AV alone, 1 MES | 573 | 8,137 | 3,415 | −1,641 | −1,628 | 285 h | 63.5 | 100 |

- Replacing S2 with AV adds profit (+$8.1k vs +$2.4k over S1 + S3) and speeds up passing (21 vs 27 days) but **lowers the pass rate**
  (64.6% vs 68.9%) and more than doubles the drawdown: AV has no stop, so one trade at 1 MES can lose −$1,628, more than the whole
  25K allowance. Bigger AV size makes it worse.
- S2 at 1 MNQ barely changes S1 + S3 (pass 68.9% vs 68.6%, 27 vs 29.5 days).
- AV's 188 overnight-or-longer holds may also break a prop firm's overnight rules (not modelled).

## 5. Consolidated findings

1. **Real but fragile.** In-sample 2023 to 2026 it is solidly profitable and survives realistic fills and 3 ticks of slippage. Out of sample it is only marginally profitable (+$7.6k to +$13.5k over four years with yearly re-optimisation, about t 1 per trade) and it lost in 2022.
2. **The platform settings are a hindsight winner.** They are the top of 360 settings for 2023 onward (+$30k). A fair expectation is closer to the walk-forward figures than to +$30k.
3. **Setting-dependent.** Only the 3-minute timeframe works; the volatility limit and EMA length move results a lot; no stable best setting exists year to year.
4. **No stop is part of the edge.** Switching the ATR stop on makes 2023-2025 negative. The strategy therefore carries tail risk: single trades of -$1,000 to -$2,200 at 3 contracts, holds of up to 187 hours.
5. **Profit is concentrated** in a minority of trades (top 10 are 54%) and in longer holds (over 10 hours made 83% of the 2023+ profit).
6. **The uptrend story is not supported.** The weak year was 2022; downtrend days after 2022 were the best bucket.
7. **The candidate filters do not survive walk-forward (B-13, B-14).** Late-day entries, gap-up days and "no Friday" were consistent across years in-sample (B-12) and lift the PF of the hindsight-selected settings, but with settings chosen walk-forward none beats the unfiltered baseline, rolling and anchored disagree, and the engine-level "14:00+" test is worse than no filter (-$8,176 rolling, +$289 anchored vs +$13,457 / +$7,572). They mainly cut the trade count and the drawdown. Treat them as descriptions of the 2023-2026 sample, not as tradable rules.
8. **Prop-firm fit is poor at 3 contracts** on a $25k Lucid account because a single trade can exceed the $1,000 loss limit; fewer contracts survive but pay out slowly.
9. **MES vs MNQ:** MES 2023-26 PF 1.83, MNQ PF 1.13 with the same rules on a near-identical market. The MES result is partly instrument/period specific.

## 6. Open questions and planned work

- [x] Walk-forward test of the candidate filters (B-13 screening, B-14 engine-level for "14:00+"): no robust improvement.
- [ ] Engine-level walk-forward for "no Friday" and "no Friday + 14:00+" (`walk_forward.py --filter nofri|latenofri`); screening suggests the same answer.
- [ ] Filters chosen strictly from train-window data in each fold (the B-13 filters were found on all years, so their choice is not out of sample).
- [ ] Repeat the rolling walk-forward on MNQ as an independent check.
- [ ] Wide catastrophic stop (for example 6 to 8 ATR) to cap the tail while keeping the no-stop behaviour (only the 2x stop was tested).
- [ ] Understand why 2022 failed (trend vs range days; which part of the logic breaks) before adding any regime filter.
- [ ] Validate the port against a trade list from the user's platform for the same settings and period (we have no such list yet).
- [ ] Back-adjust or roll-filter the NQ and gold files so earlier results on those are comparable.
- [ ] Re-check the Lucid rules against Lucid's own pages before relying on any prop-firm number (all rules here are third-party).

## 7. Caveats that apply to everything above

- All results come from a single price path (MES 2021-2026). Dates shift pass/breach outcomes a lot.
- Lucid rule numbers (targets, loss limits, buffers, payouts, 90/10 split) come from third-party review sites that disagree on some points (daily loss limit, minimum days, consistency). Evaluation fee ($100) is the user's figure; fees for other plan sizes are unknown.
- The port is not validated against the user's platform for this strategy. Fill timing, the midnight-ET VWAP reset and the 3-minute bar boundaries are assumptions that match the source code as written.
- The data is NQ/ES-family futures with unadjusted rolls; roll-day entries can be removed but the indicator contamination around a roll (ATR spike for about 40 minutes after the break) is not corrected.

## 8. How to reproduce (run from `FLOW_V2/`)

```
# headline backtest: platform settings, 2023-01-01 to the end of the data
python analysis/gb_nq_backtest/anchored_vwap.py --csv analysis/data/MES_1m_latest.csv --start 2023-01-01 --tf 3 --ema 11 --contracts 3 \
    --no-atr-stop --max-rel-atr 1.1 --mult 5 --tick 0.25 --equity 25000 --comm 1.0 --out reports/avwap_mes_2023
# drop trades entered on contract-roll days
python analysis/gb_nq_backtest/roll_filter.py --csv analysis/data/MES_1m_latest.csv --trades reports/avwap_mes_2023/trades.csv \
    --equity 25000 --write-filtered reports/avwap_mes_2023/trades_noroll.csv
# Lucid 25K evaluation -> funded -> payouts ($100/eval); add --payout-mode doubling for the user schedule, --plan 100k for the 100K plan
python analysis/gb_nq_backtest/prop_sim.py --trades reports/avwap_mes_2023/trades_noroll.csv --csv analysis/data/MES_1m_latest.csv \
    --mult 5 --days 1500 --fee 100
# diagnostics, optimisation and walk-forward
python analysis/gb_nq_backtest/edge_check.py
python analysis/gb_nq_backtest/optimize_wf.py --workers 4
python analysis/gb_nq_backtest/walk_forward.py --workers 5
python analysis/gb_nq_backtest/factor_analysis.py
# filters: screening on the cached trades, and engine-level (late / nofri / latenofri)
python analysis/gb_nq_backtest/filter_wf.py
python analysis/gb_nq_backtest/walk_forward.py --workers 5 --filter late
```

Other options on `anchored_vwap.py`: `--fill next_open`, `--slip-ticks N`, `--ema`, `--contracts`, `--atr-mult`, `--no-atr-stop`, `--max-rel-atr`, `--start`, `--end`.

## 9. File index

| What | Where |
|---|---|
| Port of the strategy | `analysis/gb_nq_backtest/anchored_vwap.py` |
| Roll detection and roll-day trade filter | `analysis/gb_nq_backtest/roll_filter.py` |
| Lucid evaluation replay (25K/50K/100K rules) | `analysis/gb_nq_backtest/lucid_replay.py` |
| Evaluation -> funded -> payout lifecycle (Lucid and doubling schedule) | `analysis/gb_nq_backtest/prop_sim.py` |
| Edge diagnostics | `analysis/gb_nq_backtest/edge_check.py` |
| Single optimise/test split | `analysis/gb_nq_backtest/optimize_wf.py` |
| Rolling and anchored walk-forward | `analysis/gb_nq_backtest/walk_forward.py` |
| Factor analysis | `analysis/gb_nq_backtest/factor_analysis.py` |
| Filter walk-forward (screening) | `analysis/gb_nq_backtest/filter_wf.py`, output `reports/filter_wf.log` |
| Filter walk-forward (engine-level) | `analysis/gb_nq_backtest/walk_forward.py --filter ...`, output `reports/walk_forward_late.log` |
| Run outputs | `reports/avwap_*`, `reports/optimize_wf.*`, `reports/walk_forward.*`, `reports/factor_analysis.log`, `reports/edge_check.log` |
| Data | `analysis/data/MES_1m_latest.csv`, `NQ_1m_latest.csv`, `GC_1m_latest.csv` |
