# Overnight-Bias Opening-Range Breakout (Matteo Conti, strategy 3 of 3)

Research log and findings for one strategy. **Append to this file as research continues** (new entries at the bottom of
section 4, then fold the conclusion into section 5 and update the status line). Nothing here is a trading decision; entries are
evidence, tagged `[BACKTEST]`, `[PROP-SIM]`, `[DATA]` or `[CODE]` (see [README](README.md)).

- **Status:** base fixed 2026-11-02 (all defaults); edge tested (B-08); in the chosen pair S1 + S3 for prop trading (B-07); funded phase simulated (B-09); robustness round R-1 passed (B-11); first-payout cost and time (B-12); 2 MNQ funded and $500 first payout (B-13, B-14); pass vs payout timeline (B-15); 2 MNQ eval / 1 MNQ funded (B-16). Last updated 2026-11-04.
- **Instrument tested:** NQ 1-minute bars (15m bars built from them), 2019-09-01 to 2026-10-14. Sized as 1 NQ.
- **One-line verdict so far:** the strongest of the three. A real edge (t 4.5, every year positive, longs and shorts both work) whose
  source is the overnight-bias direction (random sides make $41/trade vs $377). It reproduces his quoted figures out of sample. Fragile by
  design: the profit sits in the top ~10% of trades. Weaker on MES. Paired with strategy 1 at 1 MNQ each for a LucidPro 25K account.
- **Sister strategies:** [vault_break.md](vault_break.md), [vwap_pullback_adx.md](vwap_pullback_adx.md).

## 1. Summary of where we are

| Question | Answer so far | Confidence |
|---|---|---|
| Does it make money? | Yes. 1 NQ: 693 trades, 53.7% win, PF 1.56, payoff 1.35, +$261,117, DD $16,802; every year positive. | High (arithmetic). |
| Does it match his claims? | Yes, since 2023: 52.5% / payoff 1.39 (his "1.4 with a 53%"); short 50.0% / 1.60 (his "50% … 1.6"). His figures are payoff ratios. | Medium-high. |
| Real edge or overfit? | Real: t 4.5; all 25 nearby settings positive; walk-forward +$181k vs fixed +$231k; random side → $41/trade, random days → ≈ $0. MES weaker (PF 1.22, t 1.8). R-1: beats 10,000/10,000 random fakes; all 1,000 noise runs profitable; top 5% of trades carry 82% of the net. | High (B-08, B-11). |
| Where is the edge? | The overnight-bias direction; entering at the breakout also matters (random later entries make $158 vs $377). | High. |
| Weak point | Positive skew: dropping the top 10% of trades turns it negative; ~100 trades/year. | High. |
| Prop trading? | With S1 at 1 MNQ each on LucidPro 25K: 73.1% pass in the backtest, **~67% (median 35 days) under execution noise**, 59% with a 25% profit cut. First $1,000 payout: median fees $151, median ~7.4 months (B-11, B-12). | Medium (third-party rules). |
## 2. The strategy as ported

Source: interview transcript, distilled to `docs/video_transcripts/strategies/distilled/MatteoConti_3_OvernightBiasORB.txt` (v2: each doubt
has a default and coded alternatives 1A–13B). Port: `analysis/gb_nq_backtest/overnight_orb.py`.

**Fixed base = all defaults (user, 2026-11-02):**
- 15m bars, long and short, America/Chicago.
- Overnight range (ONR) = highs/lows of the 15m bars from 00:00 to 08:30 CT.
- Bias from the 08:30 bar's open: top third (pos ≥ 2/3, incl. above the ONR) → long only; bottom third (≤ 1/3) → short only;
  middle → no trade.
- OR = the 08:30–08:45 bar. Entry: any 15m bar closing beyond the OR in the bias direction with Wilder ADX(14) > 20 (continuous on
  15m), fills from 09:00 to 14:15 CT at the next open. One trade per day.
- SL = fill ∓ 0.30 × volatility (SMA(15) of daily true range, as strategy 1), TP = 3 × the SL distance; rounded to 0.25. Time exit
  at the 14:30 CT open.

## 3. How it is tested

- Default exit check on 15m bars, stop first; 1m resolution gives identical results (TP and SL never share a bar at these distances).
- Costs: 1 NQ $4.60 round trip, 1 tick slippage on market fills, none on the TP limit.
- Split at 2023-01-01. Median stop $1,710 (≈ 85 pts) all history, $1,845 since 2023.

## 4. Research log (chronological)

### B-01 `[BACKTEST]` 2026-11-02, base (all defaults), 1 NQ

| | Since 2023 | All (2019 →) | His claim |
|---|---|---|---|
| Trades | 373 (215 L / 158 S) | 693 | "200 trades" (unclear) |
| Win % | 52.5 | 53.7 | 53 |
| Payoff, all sides | 1.39 (PF 1.54) | 1.35 (PF 1.56) | "1.4" |
| Short side | 50.0% win, payoff 1.60 | 51.3%, 1.56 | "50% win rate with a 1.6" |
| Long win % | 54.4 | 55.5 | ≈ 56 (derived) |
| Net | $154,169 | $261,117 | ≈ $200,000 |
| Time exits | — | 63% | "mostly hit" |

Per year: 2019 +$3,632 (29 trades), 2020 +$26,814, 2021 +$18,529, 2022 +$57,974, 2023 +$26,552, 2024 +$63,688, 2025 +$17,684,
2026 to Oct 14 +$46,245. Output: `reports/orb/base`.

### B-02 `[BACKTEST]` 2026-11-02, one-factor sweep, 27 variants, 1 NQ
Table: `reports/orb/sweep.txt`.

| Variant | Trades | Win % | PF | Payoff | Net $ | Max DD $ |
|---|---|---|---|---|---|---|
| base | 693 | 53.7 | 1.56 | 1.35 | 261,117 | 16,802 |
| 1B ONR from 23:00 | 694 | 53.2 | 1.53 | 1.34 | 247,273 | 16,802 |
| 1C ONR from 17:00 | 707 | 52.6 | 1.45 | 1.30 | 221,903 | 20,693 |
| 2B ONR on closes | 773 | 52.3 | 1.42 | 1.30 | 228,979 | 18,759 |
| 3C bias from OR close | 918 | 50.5 | 1.35 | 1.32 | 233,107 | 29,667 |
| 5B ATR(14) volatility | 691 | 52.7 | 1.43 | 1.28 | 206,106 | 19,685 |
| 6B first fill 09:30 | 663 | 53.7 | 1.48 | 1.28 | 204,440 | 15,787 |
| 7B fresh cross | 629 | 53.4 | 1.58 | 1.37 | 245,987 | 16,169 |
| 7C first break only | 606 | 54.1 | 1.60 | 1.36 | 248,022 | 16,169 |
| 7D intrabar stop entry | 764 | 52.7 | 1.47 | 1.32 | 259,226 | 20,460 |
| 8C ADX(10) | 731 | 53.1 | 1.51 | 1.33 | 257,827 | — |
| 8C ADX(20) | 604 | 53.5 | 1.56 | 1.36 | 222,642 | — |
| 10C stop from the OR boundary | 693 | 57.1 | 1.58 | 1.18 | 281,342 | 21,699 |

Within noise of base: 3B, 4B, 4C, 4D, 5C, 5E, 9B, 10B, 11B, 11C, 12B, 13B. Not run: 8B (an 08:30 ADX reset never warms up before the
14:30 exit), 8D (platform ADX). The 8C rows were rerun separately after the first sweep wrote both lengths to one folder.

### B-03 `[PROP-SIM]` 2026-11-02, all three strategies on ONE LucidPro account, 5 MNQ per trade each
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

### B-04 `[PROP-SIM]` 2026-11-02, S1 + S3 on one LucidPro account, size 5 → 1 MNQ per strategy
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

### B-05 `[BACKTEST]` 2026-11-02, month-by-month P&L, S1 + S3 at 1 MNQ each (the 25K / 73.1% / 35-day setting)
Fixed bases, 1 MNQ per strategy, $1 round trip, 1 tick slippage; Sep 2019 → 2026-10-14. Table: `reports/prop3/q1/monthly/monthly_pnl.csv`.
- 86 months, **63 positive / 23 negative**, average +$505, median +$446, best +$2,547 (2022-05), worst −$767 (2022-02).
- **No month lost more than $1,000** (the 25K loss allowance); longest run of losing months: 2. Worst single day −$561.
- Every calendar year positive: 2020 +$5,922, 2021 +$3,656, 2022 +$7,597, 2023 +$4,126, 2024 +$9,102, 2025 +$6,212,
  2026 to Oct 14 +$6,510. 2023 is the weakest full year (5 losing months).
- Monthly P&L correlation S1 vs S3: **0.19** (his claim: < 0.25).

### B-06 `[PROP-SIM]` 2026-11-02, Matteo Conti S1 + S3 plus a third strategy: S2 vs Daily Anchored VWAP, one LucidPro 25K account
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

### B-07 `[PROP-SIM]` 2026-11-02, S1 + S3 vs S1 + S2 + S3, 1 MNQ each, one LucidPro 25K account
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

### B-08 `[BACKTEST]` 2026-11-02, real edge or overfit? (base, 1 NQ, $4.60 RT)
Tools: `analysis/gb_nq_backtest/random_entry.py`, batch in `reports/edge/` (`grid_summary.txt`), statistics from the trade list.

| Test | Result | Reads as |
|---|---|---|
| Mean trade vs 0 | $377, t = 4.5, bootstrap 95% [$217, $542]; < 2023 t 3.2, ≥ 2023 t 3.2; monthly t 5.4 (66 of 86 months positive) | real |
| Year by year | positive every year | real |
| Long vs short | long $306 (t 3.0), short $468 (t 3.3) | not bull-market beta |
| Dependence on big winners | drop top 1% → $302 (t 3.8); top 5% → $78 (t 1.1); top 10% → −$112 | **fragile by design** (3R target, 63% time exits) |
| Parameter neighbourhood (stop 0.20–0.40 × vol, target 2–4 R, 25 points; ADX 15/25) | all positive, PF 1.31–1.56; base = top of the grid (1.56), neighbours 1.46–1.54; ADX 15/25 PF 1.51/1.50 | plateau; base is his setting, slightly lucky |
| Walk-forward over the grid | 2021–2026 +$181k vs fixed base +$231k vs grid average +$199k; every year positive | real; expect less than the base |
| Random side, same entry minute (500 runs) | $41/trade vs $377, p = 0.002 | **the overnight bias is the edge** |
| Random minute and side, random days | −$5/trade, p = 0.002 | signal ≫ random |
| Random entry later the same day | $158/trade, p = 0.002 | entering at the breakout matters |
| Slippage 2 / 3 ticks | PF 1.54 / 1.53 | cost-robust |
| Contract rolls | excluding roll Monday + 21 days: $395, t 4.0 | not a roll artifact |
| Other market: MES 2021–2026 (same rules, vol-based levels) | PF 1.22, t 1.8, 5 of 6 years positive | weaker; partial confirmation |

### B-09 `[PROP-SIM]` 2026-11-02, funded phase: S1 + S3, 1 MNQ each, LucidPro 25K eval → funded → payouts
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

### B-10 `[BACKTEST]` 2026-11-02, downtrend regimes (base, 1 NQ, $4.60 RT)
`analysis/gb_nq_backtest/regime_split.py` — regime known before each trade's day opened (no look-ahead).

| Regime | Trades | Win % | Avg $ | PF | Net $ | Long net $ | Short net $ |
|---|---|---|---|---|---|---|---|
| All | 693 | 53.7 | 377 | 1.56 | 261,117 | 119,686 | 141,431 |
| NQ above its 100-day average | 480 | 51.9 | 321 | 1.50 | 154,182 | 60,742 | 93,440 |
| **NQ below its 100-day average** | 182 | 59.3 | **570** | **1.70** | 103,758 | 55,880 | 47,878 |
| NQ up over the prior 20 days | 443 | 54.2 | 325 | 1.56 | 144,152 | 92,422 | 51,730 |
| **NQ down over the prior 20 days** | 250 | 52.8 | **468** | 1.56 | 116,965 | 27,264 | **89,701 (PF 2.14)** |
| **NQ > 10% below its 1-year high** | 151 | 55.0 | 367 | 1.42 | 55,360 | 19,785 | 35,575 |

| Named sell-off (NQ move) | Trades | Net $ | PF | Long / short $ |
|---|---|---|---|---|
| 2020 Covid crash (−27.6%) | 7 | +318 | 1.07 | −669 / +987 |
| 2022 bear market (−32.4%) | 109 | +57,974 | 1.70 | +8,518 / +49,456 |
| 2023 Aug–Oct pullback (−10.5%) | 21 | +11,668 | 2.01 | −1,617 / +13,286 |
| 2024 Jul–Aug pullback (−14.6%) | 8 | +19,618 | 6.11 | −998 / +20,617 |
| 2025 tariff sell-off (−19.4%) | 21 | +23,423 | 2.05 | +5,437 / +17,986 |

- **As strong or stronger in downtrends** — the short side carries it (after a 20-day decline, shorts: PF 2.14, +$89.7k).
  Every named sell-off was positive.
- This is why S1 + S3 pair well: S1 weakens in downtrends, S3 does not.

### B-11 `[BACKTEST]` `[PROP-SIM]` 2026-11-03, robustness round R-1 (with S1) — full entry: [vault_break.md B-15](vault_break.md)
S3-specific: random side 0 / 10,000 beat it (z 4.4); random days and side 0 / 10,000 (z 6.6); random later minute 0 / 10,000;
Bonferroni-adjusted p 0.005 (52 tries). Noise: 1,000 / 1,000 runs profitable, median keeps 94% of net. **Most outlier-dependent part
of the pair:** dropping the top 5% of trades keeps 18% of the net (S1: 67%). Pair under noise: 67% eval pass (median), all four
pass-line criteria met.

### B-12 `[PROP-SIM]` 2026-11-03, cost and time to the first $1,000 payout (with S1) — full entry: [vault_break.md B-16](vault_break.md)
Median fees $151 (mean ~$140), median ~226 calendar days under noise; fee target met, 6-month target failed.

### B-13 `[PROP-SIM]` 2026-11-03, first payout with 2 MNQ each in the funded phase (with S1) — full entry: [vault_break.md B-17](vault_break.md)
Worse than 1 MNQ: under noise 41% vs 31% paid within 6 months, but 70% vs 89% within 12 months; funded breaches ×4; 15% of
journeys spend > $900 or never get paid (1 MNQ: 0%).

### B-14 `[PROP-SIM]` 2026-11-03, a $500 first payout instead of $1,000 (with S1) — full entry: [vault_break.md B-18](vault_break.md)
~1 month sooner (median 196 vs 226 days under noise), same fees (~$140 mean, $151 median); 44% paid within 6 months.

### B-15 `[PROP-SIM]` 2026-11-04, "pass in ~35 days" vs "paid in ~200 days" (with S1) — full entry: [vault_break.md B-19](vault_break.md)
The eval passes in ~35 *trading* days (~7 weeks); the first payout comes ~196–226 *calendar* days after buying the first eval.
The gap is the funded climb from $25,000 to $26,600–$27,100 at ~$500/month, plus a new eval on the ~1 in 2 journeys that lose a
funded account.

### B-16 `[PROP-SIM]` 2026-11-04, 2 MNQ each in the eval, 1 MNQ once funded (with S1) — full entry: [vault_break.md B-20](vault_break.md)
Median time to the first payout under noise: 175 days ($1,000) / 148 days ($500), vs 227 / 196 with a 1 MNQ eval; mean fees
~$190 vs ~$141 (about one reset per journey). Best plan tested so far for the first payout.

## 5. Consolidated findings

1. **Real edge on NQ:** every year positive, in and out of sample, longs and shorts both positive, cost- and roll-robust (B-01, B-08).
2. **It reproduces his statistics** with the default reading of every doubt; "1.4" and "1.6" are payoff ratios (B-01).
3. **Not overfit in the usual sense:** all 25 nearby settings and both ADX thresholds positive; walk-forward clearly positive. His setting is
   the top of the grid, so expect somewhat less than the base (B-02, B-08).
4. **The overnight bias is the edge** (random side → $41/trade, p 0.002) (B-08).
5. **Fragile by design:** the profit lives in a minority of large winners, so long flat spells are normal and short windows are noisy.
6. **Weaker outside NQ:** MES PF 1.22, t 1.8 (B-08).
7. **Prop fit:** paired with S1 at 1 MNQ each — 73.1% eval pass rate on LucidPro 25K, ~$1,800/yr paid out in the funded phase (B-03 – B-09).
8. **Robust, but 73% is optimistic:** ~67% eval pass under execution noise; first $1,000 payout ~$150 fees, ~7 months (B-11, B-12).

## 6. Open questions and planned work

- Fees are now modelled for the first payout (B-16). Open: does 2 MNQ each in the funded phase shorten the ~7 months without many more
  funded breaches? Unfinished R-1 pieces: DLL-ON noise runs, MES prop check. Truly unseen data: NQ before 2019 (needs Databento).
- Funded-phase sizing (e.g. 2 MNQ each once a buffer exists); larger payout requests if Lucid allows them.
- Roll-adjusted data; confirm Lucid's real rules.

## 7. Caveats that apply to everything above

- His figures are quoted loosely on camera; "200 trades" is not explained.
- Unadjusted contract rolls; sparse 2019 data.

## 8. How to reproduce (run from `FLOW_V2/`)

```
# fixed base = script defaults (1 NQ, $4.60 RT)
python analysis/gb_nq_backtest/overnight_orb.py --out reports/orb/base
```
Variant flags and the code-to-flag mapping (1A–13B) are listed in the script's docstring.

## 9. File index

- Rules: `docs/video_transcripts/strategies/distilled/MatteoConti_3_OvernightBiasORB.txt`
- Script: `analysis/gb_nq_backtest/overnight_orb.py`
- Earlier write-up (superseded by this file): `docs/dynamic/matteoConti_overnightOrb_backtest_v1.md`
- Outputs: `reports/orb/`
