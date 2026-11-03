# Vault Break (Matteo Conti, strategy 1 of 3)

Research log and findings for one strategy. **Append to this file as research continues** (new entries at the bottom of
section 4, then fold the conclusion into section 5 and update the status line). Nothing here is a trading decision; entries are
evidence, tagged `[BACKTEST]`, `[PROP-SIM]`, `[DATA]` or `[CODE]` (see [README](README.md)).

- **Status:** base fixed 2026-11-02; edge tested (B-12); in the chosen pair S1 + S3 for prop trading (B-11); funded phase simulated (B-13); robustness round R-1 passed (B-15); first-payout cost and time (B-16). Last updated 2026-11-03.
- **Instrument tested:** NQ 1-minute bars (`analysis/data/NQ_1m_latest.csv`), 2019-09-01 to 2026-10-14. Sized as 1 NQ
  ($20/pt) and as MNQ ($2/pt).
- **One-line verdict so far:** a real, modest, broad-based edge (t 5.2, every year positive, every nearby setting positive, beats
  random entries by ~$100/trade) that holds out of sample. Weaker on MES. It matches his reported numbers only with the session open moved
  to the 17:00 CT Globex open, which is not the rule he stated. Paired with strategy 3 at 1 MNQ each for a LucidPro 25K account.
- **Sister strategies:** [vwap_pullback_adx.md](vwap_pullback_adx.md), [overnight_bias_orb.md](overnight_bias_orb.md).

## 1. Summary of where we are

| Question | Answer so far | Confidence |
|---|---|---|
| Does it make money as he described it (midnight open)? | Yes. 1 NQ: 1,488 trades, 66.3% win, PF 1.24, +$135,835, DD $17,632; $91/trade, t 3.7. | High. |
| Fixed base (2C, 17:00 open)? | 1,517 trades, 67.6% win, PF 1.34, +$185,527, DD $11,084, $122/trade, every year positive. | High (arithmetic). |
| Does it match his claims (2022 → 2026: $112–121/trade, ~67% win)? | Only with 2C: $123, 68.8%. | Medium: 1 of 34 variants. |
| Real edge or overfit? | Real: t 5.2; all 45 nearby settings positive; walk-forward +$130k vs fixed +$151k; random long entries make $18/trade vs $122; cost- and roll-robust. MES weaker (PF 1.14, t 1.8). R-1: beats 10,000/10,000 random-direction and random-day fakes; all 1,000 noise runs profitable. | High (B-12, B-15). |
| Where is the edge? | Mostly in which days it trades (noise-break days); entering later the same day still makes $84/trade. | Medium. |
| Prop trading? | With S3 at 1 MNQ each on LucidPro 25K: 73.1% pass in the backtest, **~67% (median 35 days) under execution noise**, 59% with a 25% profit cut. First $1,000 payout: median fees $151, median ~7.4 months (B-15, B-16). | Medium (third-party rules). |
## 2. The strategy as ported

Source: interview transcript, distilled to `docs/video_transcripts/strategies/distilled/MatteoConti_1_VaultBreak.txt` (all doubts G1–G16
and H1–H3 resolved by the user, 2026-11-01). Port: `analysis/gb_nq_backtest/vault_break.py`.

**Fixed base (user, 2026-11-02):**
- 30m bars, long only, America/Chicago. Session for the open, VWAP and ATR = 00:00–16:00 CT.
- **Session open = open of the first 1m bar at/after 17:00 CT of the previous date (Globex open, variant 2C).** He said midnight;
  midnight is kept as the script default for reproducibility.
- Volatility = SMA(15) of daily true range (prior 16:00 close as prevClose), today excluded; holiday sessions count if they traded.
- Noise-up = session open + 0.30 × volatility, fixed for the day. VWAP anchored at 00:00 CT, typical price.
- Signal: 30m close > noise-up AND > VWAP, signal bar closing 10:00–14:00 CT. Fill at the next bar's open, any price.
- TP +40 pts (limit), SL −75 pts (stop-market), time exit 14:30 CT. Up to 3 entries/day, one at a time; the exit bar may re-signal.
- Holidays / early closes (no bar after 14:30 CT): no trading.

## 3. How it is tested

- Exits resolved on 1-minute bars; stop first if TP and SL fall inside the same minute.
- Costs: 1 NQ $4.60 round trip, 1 tick slippage on entry, stop and time exit; none on the TP limit. MNQ runs: $1.00 round trip.
- In-sample / out-of-sample split at 2023-01-01 (his split). His claimed stats cover 2022 → 2026, so 2022+ is also reported.
- Timestamps in the CSV are bar OPEN times (checked, B-01).

## 4. Research log (chronological)

### B-01 `[DATA]` 2026-11-01, CSV timestamp convention
Median 1m volume Jun–Jul 2024: 13:29 UTC 882, **13:30 UTC 5,079**, 13:31 UTC 4,021. The 08:30 CT open spike sits on 13:30, so
`timestampMs` is the bar's open time.

### B-02 `[BACKTEST]` 2026-11-01, MNQ, his rules as stated (midnight open)
1 MNQ, $1 RT. All: 1,488 trades, 66.3% win, $8.59/trade, PF 1.23, +$12,780, DD $1,808. In-sample PF 1.27, out-of-sample PF 1.20.
Raw cost-free average $103 per NQ vs his $112. Exits: TP 819, SL 326, time 342.
Variants: 30m exits PF 1.13 (+$7,930); plain H−L range PF 1.22; no costs PF 1.28; 5 MNQ +$63,900, DD $9,040.
Output: `reports/vault_break/{base_1m,exit_30m,tr_hl,no_costs,5mnq}`.

### B-03 `[BACKTEST]` 2026-11-02, 1 NQ, his rules as stated
1,488 trades, 66.3% win, $91.29/trade, PF 1.24, +$135,835, DD $17,632. Since 2023: 874 trades, +$75,705. Only 2019 (22 trades) negative.
Output: `reports/vault_break/nq_1c`.

### B-04 `[CODE]` 2026-11-02, variant flags added to `vault_break.py`
`--vol-ma`, `--vol-session`, `--open-ref`, `--vwap-anchor/-basis/-prev`, `--first/last-signal-close`, `--reentry`, `--max-gap-pts`,
`--fill-at`, `--time-exit-min/-at`, `--intrabar`. Defaults reproduce B-03 byte for byte (checked against the earlier `trades.csv`).

### B-05 `[BACKTEST]` 2026-11-02, one-factor sweep, 34 variants, 1 NQ
Table: `reports/s1_sweep/sweep.txt`. Highlights (all history; "2022+" = trades from 2022-01-01):

| Variant | Trades | Win % | PF | Avg $ | Net $ | Max DD $ | 2022+ avg $ | 2022+ win % |
|---|---|---|---|---|---|---|---|---|
| base (midnight) | 1,488 | 66.3 | 1.24 | 91 | 135,835 | 17,632 | 80 | 66.8 |
| gross | 1,492 | 66.6 | 1.28 | 103 | 153,870 | 16,895 | 90 | 67.0 |
| **2C open 17:00** | 1,517 | 67.6 | **1.34** | **122** | **185,527** | **11,084** | **123** | **68.8** |
| 1C EMA vol | 1,490 | 67.0 | 1.27 | 101 | 150,661 | 16,498 | 94 | 67.7 |
| 1G k 0.35 | 1,346 | 67.2 | 1.31 | 111 | 149,793 | 11,210 | 112 | 68.0 |
| 3C VWAP 08:30 | 1,446 | 66.5 | 1.28 | 103 | 149,068 | 18,624 | 92 | 67.0 |
| 2D open 08:30 | 1,386 | 65.6 | 1.19 | 73 | 100,659 | 14,714 | 67 | 66.2 |
| 4B first signal 10:30 | 1,386 | 65.0 | 1.18 | 68 | 94,264 | 14,776 | 57 | 65.6 |
| 6B cap 1 | 747 | 66.9 | 1.20 | 82 | 61,144 | 21,447 | 32 | 65.6 |
| 30m exits, stop first | 1,488 | 64.9 | 1.15 | 59 | 87,335 | 18,411 | 42 | 65.1 |

No effect: VWAP close basis, VWAP of the previous bar, 17:00 VWAP, gap filter, fill at signal close, time-exit at open,
target-first, Globex ATR sessions, plain H−L. k from 0.20 to 0.40 is positive throughout (a plateau, not a spike).

### B-06 `[BACKTEST]` 2026-11-02, fixed base (2C) per year, 1 NQ

| Year | Trades | Win % | Net $ | PF |
|---|---|---|---|---|
| 2019 (Sep–Dec) | 38 | 55.3 | 205 | 1.03 |
| 2020 | 219 | 67.1 | 34,043 | 1.51 |
| 2021 | 190 | 63.7 | 19,581 | 1.33 |
| 2022 | 199 | 70.4 | 19,660 | 1.24 |
| 2023 | 220 | 65.5 | 16,393 | 1.19 |
| 2024 | 215 | 69.8 | 29,076 | 1.37 |
| 2025 | 224 | 71.4 | 46,170 | 1.64 |
| 2026 (to Oct 14) | 212 | 67.0 | 20,400 | 1.23 |

Output: `reports/s1_sweep/2C_open17_00`.

### B-07 `[PROP-SIM]` 2026-11-02, all three strategies on ONE LucidPro account, 5 MNQ per trade each
Fixed bases, 5 MNQ ($2/pt) per trade per strategy, $1 round trip per contract, 1 tick slippage; Sep 2019 → Oct 2026. Rules from
`lucid_replay.py` (third-party LucidPro: EOD-trailing max loss, breach on the combined worst open P&L minute by minute across all open
positions, pass on closed balance; no daily loss limit, no time limit, no fees or payouts modelled). Max concurrent contracts 15
(cap 20 on 25K).

Rolling start = an independent evaluation begun on every session day; pass/fail % exclude attempts still open at the data end.
Days = trading (session) days to pass. Script `analysis/gb_nq_backtest/prop_multi.py`; full output `reports/prop3/sim_all/summary.txt`.

| Plan | Set | Net $ | Max DD $ | Worst day $ | Pass % | Fail % | Median days to pass | Chain passes / fails |
|---|---|---|---|---|---|---|---|---|
| 25K | S1 | 88,668 | 5,615 | −2,272 | 49.5 | 50.5 | 8 | 116 / 116 |
| 25K | S2 | 9,708 | 4,850 | −1,282 | 37.4 | 62.6 | 39 | 15 / 26 |
| 25K | S3 | 128,665 | 8,588 | −2,912 | 39.7 | 60.3 | 5 | 117 / 188 |
| 25K | S1+S3 | 217,332 | 8,205 | −2,805 | 39.9 | 60.1 | 3 | 203 / 301 |
| 25K | S1+S2+S3 | 227,040 | 9,065 | −3,420 | 39.3 | 60.7 | 3 | 218 / 324 |
| 50K | S1 | | | | 54.2 | 45.8 | 26 | 39 / 33 |
| 50K | S3 | | | | 42.3 | 57.7 | 16 | 51 / 63 |
| 50K | S1+S2+S3 | | | | 39.4 | 60.6 | 7 | 91 / 119 |
| 100K | S1 | | | | 52.4 | 47.6 | 65 | 16 / 12 |
| 100K | S3 | | | | 54.6 | 45.4 | 58 | 20 / 20 |
| 100K | S1+S2+S3 | | | | 48.9 | 51.1 | 23 | 39 / 42 |

- **Running all three does not raise the pass rate** (25K: 39% combined vs 50% for S1 alone, 40% for S3 alone); it makes passing
  **faster** (median 3 trading days vs 5–8). His claims: ~40% pass alone, higher combined, ~5 days to pass.
- Reason: at 5 MNQ each, three strategies triple the risk against the same fixed loss allowance; the diversification does not make up
  for it. S1 alone on 50K (54%) and S3 alone on 100K (55%) are the best rates found.
- S2 alone is poor (37% / 39 days on 25K) and adds almost nothing to the combined set.

### B-08 `[PROP-SIM]` 2026-11-02, S1 + S3 on one LucidPro account, size 5 → 1 MNQ per strategy
Same setup as the previous entry; set S1+S3 only. Rolling start (an evaluation begun on every session day). "No limit" = run until pass or
breach; "21 days" = attempt expires after 21 trading days. Output `reports/prop3/q<size>/sim` and `.../sim21`.

| MNQ each | Net $ | Max DD $ | Worst day $ | 25K pass % (median days) | 50K pass % (days) | 100K pass % (days) | 25K pass % in 21 days | 50K in 21 days | 100K in 21 days |
|---|---|---|---|---|---|---|---|---|---|
| 5 | 217,332 | 8,205 | −2,805 | 39.9 (3) | 42.8 (9) | 45.9 (23) | 39.9 | 38.7 | 20.4 |
| 4 | 173,866 | 6,564 | −2,244 | 42.8 (4) | 47.4 (13) | 51.1 (38) | 41.6 | 37.4 | 11.0 |
| 3 | 130,400 | 4,923 | −1,683 | 45.0 (5) | 53.7 (20) | 67.0 (62) | 40.6 | 29.1 | 4.7 |
| 2 | 86,933 | 3,282 | −1,122 | 52.6 (10) | 67.7 (44) | 96.4 (111) | 43.7 | 11.3 | 0.1 |
| 1 | 43,466 | 1,641 | −561 | 73.1 (35) | 100.0 (114) | 100.0 (237) | 19.1 | 0.1 | 0.0 |

- Smaller size → higher eventual pass rate but far slower: 1 MNQ passes 50K every time, after ~114 trading days (~5.5 months).
- With a 21-trading-day limit the best is **25K at 2 MNQ each: 43.7% pass, 37.5% breach, 18.8% expire** (median 9 days to pass);
  5 MNQ on 25K is about the same pass rate (39.9%) but nearly every failure is a breach.
- On 50K/100K the target is too far for small size inside 21 days; time limits (if the firm has one) dominate.
- Evaluation fees and monthly subscription costs are not modelled; they decide which row is cheapest per pass.

### B-09 `[BACKTEST]` 2026-11-02, month-by-month P&L, S1 + S3 at 1 MNQ each (the 25K / 73.1% / 35-day setting)
Fixed bases, 1 MNQ per strategy, $1 round trip, 1 tick slippage; Sep 2019 → 2026-10-14. Table: `reports/prop3/q1/monthly/monthly_pnl.csv`.
- 86 months, **63 positive / 23 negative**, average +$505, median +$446, best +$2,547 (2022-05), worst −$767 (2022-02).
- **No month lost more than $1,000** (the 25K loss allowance); longest run of losing months: 2. Worst single day −$561.
- Every calendar year positive: 2020 +$5,922, 2021 +$3,656, 2022 +$7,597, 2023 +$4,126, 2024 +$9,102, 2025 +$6,212,
  2026 to Oct 14 +$6,510. 2023 is the weakest full year (5 losing months).
- Monthly P&L correlation S1 vs S3: **0.19** (his claim: < 0.25).

### B-10 `[PROP-SIM]` 2026-11-02, Matteo Conti S1 + S3 plus a third strategy: S2 vs Daily Anchored VWAP, one LucidPro 25K account
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

### B-11 `[PROP-SIM]` 2026-11-02, S1 + S3 vs S1 + S2 + S3, 1 MNQ each, one LucidPro 25K account
Full history Sep 2019 → Oct 2026, fixed bases, rolling start, no time limit. Output `reports/prop3/q1/cmp123`, `cmp123_2023`.

| | S1 + S3 | S1 + S2 + S3 |
|---|---|---|
| Net $ (7 years) | 43,466 | 45,408 |
| Max DD $ / worst day $ | 1,641 / −561 | 1,813 / −684 |
| Losing months / worst month $ | 23 of 86 / −766 | 22 of 86 / **−1,250** |
| Monthly avg ÷ st.dev | 0.73 | 0.68 |
| 25K pass %, all history (median days) | **73.1 (35)** | 68.0 (30) |
| 25K pass %, since 2023 (median days) | 68.7 (30) | 70.1 (27) |
| 50K / 100K pass % | 100 / 100 | 100 / 100 |

Monthly correlation: S1–S2 0.16, **S2–S3 0.49**, S1–S3 0.19. S2 alone at 1 MNQ by year: −43, −590, +416, −95, −466, +1,078, +1,187, +455
(45 winning / 40 losing months).
- **S1 + S3 is the better pair over the full history:** higher pass rate, smaller drawdown, better risk-adjusted months. S2 adds only
  ~$1.9k in 7 years, has the only month beyond the 25K allowance, and moves with S3.
- S1 + S2 + S3 is slightly better since 2023 only because of S2's strong 2024–2025; S2's profit is concentrated in those two years.

### B-12 `[BACKTEST]` 2026-11-02, real edge or overfit? (fixed base 2C, 1 NQ, $4.60 RT)
Tools: `analysis/gb_nq_backtest/random_entry.py` (Monte Carlo benchmark), grid/slippage/MES batch in `reports/edge/`
(`grid_summary.txt`), statistics computed from the trade list.

| Test | Result | Reads as |
|---|---|---|
| Mean trade vs 0 | $122, t = 5.2, bootstrap 95% [$76, $167]; < 2023 t 3.3, ≥ 2023 t 4.0; monthly t 5.3 | real |
| Year by year | positive every year (2019: 38 trades ≈ 0); 2022 bear year +$19.7k | real, not bull-only |
| Dependence on big winners | drop top 1% → $115 (t 4.8); top 5% → $87 (t 3.6); top 10% → $48 (t 1.9) | broad-based |
| Parameter neighbourhood (k 0.20–0.40 × TP 30–50 × SL 60–90, 45 points) | all 45 positive, PF 1.20–1.39, t 3.4–5.6; base 1.34 mid-plateau; higher k slightly better | not a spike |
| Walk-forward (each year use the grid point best on prior years) | 2021–2026 +$130k vs fixed base +$151k vs grid average +$132k; every year positive | not dependent on the pick |
| Random long entries, random days, same exits (500 runs) | $18/trade (= NQ drift), p = 0.002 | signal adds ~$100/trade |
| Random entry later the same day (after the signal) | $84/trade, p = 0.012; < 2023 no difference (p 0.45), ≥ 2023 signal better (p 0.002) | most of the edge is in picking the day; the entry moment adds a little |
| Slippage 2 / 3 ticks | PF 1.32 / 1.31, $116 / $112 per trade | cost-robust |
| Contract rolls | excluding roll Monday + 21 days: $134, t 4.9 | not a roll artifact |
| Other market: MES 2021–2026 (TP 11 / SL 20 = NQ points × ES/NQ price ratio) | PF 1.14, t 1.8, 4 of 6 years positive | weaker; partial confirmation |
| As he stated (midnight open) | $91, t 3.7, PF 1.24 | real without our 2C choice |

Earlier "random entries beat the strategy" result (`rand_s1_time`, $205 vs $122) is **invalid**: it drew random times on the strategy's own
days, which were selected by a breakout that happens later that day (look-ahead). Replaced by the two unbiased versions above.
Open: TP/SL are fixed points while NQ rose from ~8,200 to ~27,900 (2019 → 2026) — the rule means a different thing over time.

### B-13 `[PROP-SIM]` 2026-11-02, funded phase: S1 + S3, 1 MNQ each, LucidPro 25K eval → funded → payouts
`prop_multi.py --lifecycle` (merged minute path as in the evaluation runs; payout constants imported from `prop_sim.py`). One account at a
time; after a pass the funded account starts the next session day; after a breach a new eval starts the next session day. No fees.
Lucid mode: $1,000 per request when balance − 1,000 ≥ $26,100, cycle profit ≥ $250, largest day ≤ 40% of the cycle; 90/10 split.
Doubling mode: the user's schedule 100, 200, 400, 800, 1,600, then +500 (paid when profit ≥ 2 × the next payout).
Output: `reports/prop3/funded_q1_lucid`, `reports/prop3/funded_q1_doubling`.

| Full period 2019-09 → 2026-10 | Lucid rules | Doubling schedule |
|---|---|---|
| Evals bought | 2 (both passed; 1 funded breach, 2020-07-17) | 1 |
| Payouts | 24 × $1,000 = $24,000 → **$21,600** after split | 13, $33,900 → **$30,510** after split |
| Days from first eval to first payout | 264 | 194 |
| Account left at the end | $41,554 (unwithdrawn surplus) | $33,164 |

| Rolling 365-day windows (n = 74, every 30 days) | Lucid | Doubling |
|---|---|---|
| Evals bought, median (range) | 2 (1–4) | 3 (1–5) |
| Net payouts per year: median / mean | $1,800 / $1,849 | $1,530 / $1,941 |
| 10th / 90th percentile | $900 / $3,600 | $927 / $4,221 |
| Worst / best window | $0 / $3,600 | $630 / $7,020 |
| Windows with ≥ 1 payout | 71 / 74 | 74 / 74 |
| Median days to first payout | 221 | 86 |

- The fixed $1,000 request and the 40% consistency rule cap withdrawals at ~$1,000 per cycle; profit beyond that piles up in the account
  ($41.5k at the end). Larger requests after the first payouts (if Lucid allows them) are not modelled.
- Breach risk is low at this size: one funded breach in 7 years (just after the first payout, when the buffer was thinnest).
- **Why the first payout took 264 days in the full-period run:** the first eval started on the first day of data (2019-09-25) and
  needed 189 calendar days to pass (Nov 2019 – Feb 2020 was flat: running total $277 → $780 → $406 → $343 → $607 → $381 → $1,171, pass
  on 2020-04-01). The funded part then took 74 days (2020-04-02 → 2020-06-15), and 74 again on the second funded account. The first
  payout needs **+$2,100** in the funded account, not +$1,000: the balance must stay ≥ $26,100 after withdrawing $1,000, i.e. reach $27,100.
  At ~$505 a month that is ~4 months on average. The 35-day eval figure is a median over all start dates; this start was an unlucky one.

### B-14 `[BACKTEST]` 2026-11-02, downtrend regimes (fixed base 2C, 1 NQ, $4.60 RT)
`analysis/gb_nq_backtest/regime_split.py` — each trade is classed by the regime known before its day opened (no look-ahead).

| Regime | Trades | Win % | Avg $ | PF | t | Net $ |
|---|---|---|---|---|---|---|
| All | 1,517 | 67.6 | 122 | 1.34 | 5.2 | 185,527 |
| NQ above its 100-day average | 1,030 | 67.6 | 134 | 1.39 | 4.7 | 137,552 |
| **NQ below its 100-day average** | 441 | 68.7 | 102 | 1.25 | 2.2 | 44,916 |
| NQ up over the prior 20 days | 912 | 68.9 | 156 | 1.50 | 5.4 | 141,805 |
| **NQ down over the prior 20 days** | 605 | 65.6 | 72 | 1.17 | 1.8 | 43,722 |
| **NQ > 10% below its 1-year high** | 335 | 69.9 | 105 | 1.25 | 1.9 | 35,019 |

| Named sell-off (NQ move) | Trades | Net $ | PF |
|---|---|---|---|
| 2020 Covid crash (−27.6%) | 25 | −3,730 | 0.75 |
| 2022 bear market (−32.4%) | 199 | +19,660 | 1.24 |
| 2023 Aug–Oct pullback (−10.5%) | 54 | −4,668 | 0.83 |
| 2024 Jul–Aug pullback (−14.6%) | 20 | +2,078 | 1.23 |
| 2025 tariff sell-off (−19.4%) | 34 | +2,809 | 1.18 |

- **Still profitable in downtrends, but roughly half as strong**: ~$72–105/trade vs ~$134–156 in uptrends, and not statistically
  clear on its own (t 1.8–2.2). It is long-only, so a downtrend removes much of its tail wind.
- **Fast crashes hurt** (2020 crash, 2023 pullback lost); a slow grinding bear (2022) was still positive.

### B-15 `[BACKTEST]` `[PROP-SIM]` 2026-11-03, robustness round R-1: S1 + S3 against the "73% / 35 days" claim
Tests from `docs/video_transcripts/strategies/raw/how_to_backtest.txt`, plus prop-level stress of B-11. Driver
`analysis/gb_nq_backtest/robustness.py`; noise / shift options added to `vault_break.py` and `overnight_orb.py` (off by default:
output byte-identical to before, checked); `prop_multi.py --rolling-csv` added. Full tables: `reports/robust_r1/R1_summary.md`.
Pass line fixed **before** the run (user, 2026-11-03): median pass rate under noise ≥ 50%; ≥ 50% with a 25% profit haircut;
random-direction and random-timing p < 0.01 after counting tries; no start year below 40%.
Lucid rules checked against `docs/Lucid Pro 25K - Complete Rules Report.md` (from Lucid's site, 2026-11-02): target, $1,000 EOD
trailing MLL and the $25,100 lock match the simulator; the optional $600 DLL was missing and is now run as a second variant.

| Test (S1 / S3) | Result |
|---|---|
| Random direction, same entry minute (10,000; 1 NQ) | 0 / 10,000 beat the real $122 (null mean −$44, z 7.3) / 0 beat $377 (null $40, z 4.4) |
| Random minute on random days (10,000) | 0 beat (null $19, z 4.5) / 0 beat (null −$7, z 6.6) |
| Random later minute, same day (10,000) | 83 beat (p 0.008) / 0 beat — S1's entry moment adds little, as in B-12 |
| Count the tries (S1 79, S3 52 variants) | Bonferroni on the permutation p: 0.008 / 0.005 (< 0.01, narrowly; with every variant of the 3 video strategies, 193, it would be 0.02 / 0.02) |
| Power | p < 0.05 for good only from trade 207 / 130; S1's first 100 trades look like luck (p 0.40) |
| Noise: entry 0–1 min late + 0–2 extra ticks on every fill (1,000 runs, 1 MNQ) | all runs profitable; median keeps 86% / 94% of the base net; worst run $12,984 of $17,734 / $22,318 of $25,733 |
| Entry shifted +1…+5 min | net $13.6k–16.0k of $17.7k / $19.8k–24.4k of $25.7k — gradual, no cliff (earlier entries score higher but use unknown information) |
| Drop best trades (1 NQ): top 10 / top 1% / top 5% | 96% / 93% / 67% of net kept; S3: 68% / 76% / **18%** (excess kurtosis 2.0) |

Prop sim, S1 + S3, 1 MNQ each, LucidPro 25K, a start on every session day (no DLL; the $600 DLL changed nothing in any finished case):

| Case | Pass % | Median days | Chained pass/fail (95% CI) |
|---|---|---|---|
| Base | 73.1 | 35 | 37/13 (60–84%) |
| **Noise, 200 runs: median (5th pct / worst)** | **67.0** (63.3 / 60.1) | 35 | |
| Profit haircut 25% / 50% | 58.7 / 45.8 | 36 / 38 | 30/17 / 23/24 |
| Top 10 trades of each removed | 60.1 | 35 | 33/16 |
| Entry +1 … +5 min | 61.1–69.2 | 32–40 | |

Pass % by start year, base: 2020 75, 2021 87, 2022 67, 2023 64, 2024 77, 2025 57, 2026 79. Under noise the median by year is 42–80 (2019: 100);
**2021 falls from 87 to 42** (worst run 33) — that year depended on exact fills.

**Verdict against the pass line: all four criteria met.** The edge is real; the realistic eval figure is ~60–67% pass in ~35–40
trading days, not 73%. With half the backtest edge the pass rate falls below 50%.
Not finished (run stopped twice for low memory, not resumed): DLL-ON noise runs (11 of 200) and the MES prop check.

### B-16 `[PROP-SIM]` 2026-11-03, cost and time to the FIRST $1,000 payout (S1 + S3, 1 MNQ each, LucidPro 25K)
`analysis/gb_nq_backtest/first_payout.py` (+ `first_payout_report.py`); tables in `reports/first_payout/R2_summary.md`.
Journey (user, 2026-11-03; one account at a time, no copy trading): eval $75.60 (coupon, DLL ON) → eval fail: reset $75 → pass: funded
→ funded breach before the payout: new eval $75.60 → **first payout** ($1,000 requested, $900 received after the 90/10 split; needs
balance ≥ $27,100, i.e. +$2,100 funded, ≥ $250 cycle profit, no day > 40% of it). DLL $600 throughout (LucidScale not modelled).
A journey from every session day with ≥ 365 days of data after it (1,564 starts). Check: the journey from the first data day takes
264 calendar days, as in B-13.
Pass line fixed before the run (proposed by Claude, user said go): under noise the median journey costs ≤ $300 in fees and takes
≤ 6 months; fewer than 10% of journeys spend more than $900 in fees.

| Case | Fees median / mean / p90 / max | Days to payout median / p90 / max | ≤ 6 months | ≤ 12 months | Fees > $900 |
|---|---|---|---|---|---|
| Base | $151 / $129 / $226 / $227 | 221 / 328 / 469 | 33% | 97% | 0% |
| **Noise, 200 runs: median (worst 5%)** | $151 / $141 ($151) / $227 / $302 | **226 (244)** / 371 / 466 | 31% (26%) | 89% (83%) | 0% |
| Profit haircut 25% | $151 / $188 / $376 / $453 | 306 / 483 / 610 | 15% | 65% | 0% |
| Profit haircut 50% | $678 / $688 / $1,206 / $1,282 | 754 / 1,560 / 1,764 | 9% | 22% | 39% (135 journeys never paid) |
| Top 10 trades of each removed | $151 / $150 / $301 / $378 | 249 / 377 / 653 | 25% | 88% | 0% |

- Per journey (base): 0.26 resets and 0.45 funded breaches on average. Independent back-to-back journeys: 11, mean fees $137
  [95% CI $104–171], mean 231 days [187–275].
- By start year, median days: 2019 273, 2020 200, 2021 293, 2022 196, 2023 252, 2024 117, 2025 242.
- **Verdict:** fees met (median $151, never above $900 unless the edge halves); **time failed** (median ~7.4 months, only ~31%
  within 6 months). Expectancy of the first payout ≈ **$900 − ~$140 = ~$760**, but it takes ~7 months. The eval is the short part
  (~35 trading days); the time goes into making +$2,100 in the funded account at ~$500/month, and about one journey in two loses a
  funded account on the way.

## 5. Consolidated findings

1. **Real edge, modest per trade:** ~$90–120 per NQ against a 75-point ($1,500) stop. Positive in every full year including the 2022 bear
   year, in and out of sample, at 1–3 ticks of slippage, away from contract rolls (B-03, B-05, B-06, B-12).
2. **Not overfit in the usual sense:** every one of 45 nearby settings is positive (PF 1.20–1.39); choosing the setting walk-forward gives
   about the same result as the fixed base; random entries do far worse (B-12). The 2C open is a fitted choice, but the rule as he stated it
   (midnight) also has a clear edge (t 3.7).
3. **The edge is mostly day selection** (the noise-barrier break); the exact entry bar adds a little, mainly since 2023 (B-12).
4. **Weaker outside NQ:** MES PF 1.14 (t 1.8), with a guessed point scaling (B-12).
5. **Exit modelling matters:** checking exits on 30m bars with stop-first halves the average (B-05).
6. **Prop fit:** paired with S3 at 1 MNQ each, 73.1% of LucidPro 25K evals pass (median 35 trading days) and the funded account pays
   ~$1,800/yr under Lucid's payout rules, with one funded breach in 7 years (B-07 – B-13). Adding S2 or the anchored VWAP strategy makes it
   worse (B-10, B-11).
7. **Robust, but 73% is optimistic:** under realistic execution noise the pass rate is ~67% (60–84% range from 50 independent
   evals); the first $1,000 payout costs ~$150 in fees and takes ~7 months, mostly in the funded phase (B-15, B-16).

## 6. Open questions and planned work

- Fees are now modelled for the first payout (B-16). Open: does 2 MNQ each in the funded phase shorten the ~7 months without many more
  funded breaches? Unfinished R-1 pieces: DLL-ON noise runs, MES prop check. Truly unseen data: NQ before 2019 (needs Databento).
- Funded-phase sizing: e.g. 2 MNQ each once a buffer exists; larger payout requests if Lucid allows them.
- Fixed-point TP/SL while NQ rose ~3.4× (2019 → 2026): test a price- or volatility-scaled version.
- Roll-adjusted data; confirm Lucid's real rules.

## 7. Caveats that apply to everything above

- His numbers are quoted loosely on camera and unverified; several transcript passages are garbled.
- With 34 variants tried, matching his range with one of them is weak evidence by itself.
- 2019 data is sparse; 2019 results carry little weight.
- Unadjusted contract rolls (see section 6).

## 8. How to reproduce (run from `FLOW_V2/`)

```
# fixed base, 1 NQ
python analysis/gb_nq_backtest/vault_break.py --open-ref globex1700 --mult 20 --comm 4.60 --out reports/vault_break/base_2c
# his rules as stated (script defaults), MNQ
python analysis/gb_nq_backtest/vault_break.py --out reports/vault_break/base_1m
```
Variant flags are listed in the script's docstring.

## 9. File index

- Rules: `docs/video_transcripts/strategies/distilled/MatteoConti_1_VaultBreak.txt`
- Raw transcript: `docs/video_transcripts/strategies/raw/Matteo_Conti_2 timeestamps.md`
- Script: `analysis/gb_nq_backtest/vault_break.py`
- Earlier write-up (superseded by this file): `docs/dynamic/matteoConti_vaultBreak_backtest_v1.md`
- Outputs: `reports/vault_break/`, `reports/s1_sweep/`
