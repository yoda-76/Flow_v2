# VWAP Pullback with ADX Gate (Matteo Conti, strategy 2 of 3)

Research log and findings for one strategy. **Append to this file as research continues** (new entries at the bottom of
section 4, then fold the conclusion into section 5 and update the status line). Nothing here is a trading decision; entries are
evidence, tagged `[BACKTEST]`, `[PROP-SIM]`, `[DATA]` or `[CODE]` (see [README](README.md)).

- **Status:** base fixed 2026-11-02, edge unconfirmed; **left out of the chosen portfolio** (S1 + S3 is better, B-06). Last updated 2026-11-02.
- **Instrument tested:** NQ 1-minute bars, 2019-09-01 to 2026-10-14. Sized as 1 NQ and as MNQ.
- **One-line verdict so far:** as described it has no edge after costs; trailing both exits gives a small, unstable edge. Nothing we
  tried comes near his claims, because the 5-bar-high target on 1-minute bars is only a few points.
- **Sister strategies:** [vault_break.md](vault_break.md), [overnight_bias_orb.md](overnight_bias_orb.md).

## 1. Summary of where we are

| Question | Answer so far | Confidence |
|---|---|---|
| Does it make money as described? | No. 1 NQ: 1,967 trades, 76.1% win, PF 1.00, −$63, DD $17,025. Gross of costs only $11/trade. | High. |
| Does it match his claims (payoff ~0.7, avg trade above strategy 1's, fewer trades than strategy 1)? | No variant does. Median target 4–10 pts in every variant; payoff 0.26–0.45. | High. |
| Fixed base (trailing TP and SL, variant 7E)? | 2,035 trades, 75.2% win, PF 1.19, +$30,924, DD $6,299; 3 losing years of 8. | Medium (small edge, unstable year to year). |
| Why does it fail? | Target (5-bar high) median 5.75 pts vs stop (20-bar low) 26.75 pts → payoff 0.31, break-even win ~76%; costs then remove the rest. | High. |

## 2. The strategy as ported

Source: interview transcript, distilled to `docs/video_transcripts/strategies/distilled/MatteoConti_2_VwapPullbackADX.txt` (user's answers
plus four settings taken from his first video: VWAP anchor 08:30, exit 14:55, guard rails, last entry 14:30). Port:
`analysis/gb_nq_backtest/vwap_adx.py`. The first video is `docs/video_transcripts/strategies/raw/Matteo_Conti_1 timeestamps`.

**Fixed base (user, 2026-11-02):**
- 1m bars, long only. OR = 08:30–09:00 CT high. VWAP anchored 08:30 CT. Wilder ADX(14), continuous.
- Gates: close above OR high (arms, for the day) → bar low ≤ VWAP (same bar allowed) → signal bar closes above VWAP with ADX > 20
  and ADX ≤ previous bar's ADX (re-check mode). Fill at next bar's open.
- Skip if the next open is at/above the TP or at/below the SL.
- TP = highest high of the last 5 bars, SL = lowest low of the last 20 bars, both including the signal bar. **Both trailing
  (variant 7E): recomputed every bar after the entry bar from the window ending at the previous bar.** Fixed-at-entry is the
  script default.
- Max 4 trades/day, one at a time, stop for the day after 2 losses in a row; re-entry needs a fresh VWAP touch. No entries after
  14:30 CT; time exit 14:55 CT.

## 3. How it is tested

Same as [vault_break.md](vault_break.md) section 3 (1m exits, stop first, $4.60 RT on 1 NQ / $1 on MNQ, 1 tick slippage on market
fills, split at 2023-01-01). ADX warms up on the 40 days before the window.

## 4. Research log (chronological)

### B-01 `[BACKTEST]` 2026-11-02, base as described (fixed exits), MNQ and NQ
MNQ: 1,955 trades, 69.6% win, −$0.55/trade, PF 0.96, −$1,080, DD $2,356; in-sample PF 0.87, out-of-sample PF 1.02.
1 NQ: 1,967 trades, 76.1% win, PF 1.00, −$63, DD $17,025. Median TP 5.75 pts, SL 26.75 pts. Costs = 8.8% of the average MNQ winner.
TP-skip fired on 2.1% of signals (his claim: < 5%). NQ by year swings from −$12,588 (2020) to +$11,990 (2024).
First variants (MNQ): latch PF 0.91, midnight VWAP 0.88, 1 trade/day 1.01, exit 15:55 0.96, ADX(10) 0.94, ADX(20) 0.97.
Output: `reports/vwap_adx/`.

### B-02 `[CODE]` 2026-11-02, variant flags added to `vwap_adx.py`
`--or-end`, negative `--vwap-anchor`, `--vwap-basis`, `--arm`, `--touch-after-arm`, `--arm-expiry`, `--touch`, `--adx-cmp/-tol/-lag/
-reset`, `--touch-window`, `--first-fill`, `--tp-len/--sl-len/--exclude-signal-bar`, `--trail`, `--gap-tp/--gap-sl`, `--min-tp-pts`,
`--tp-through`, `--intrabar`. Defaults reproduce B-01 byte for byte.

### B-03 `[BACKTEST]` 2026-11-02, one-factor sweep, 56 variants, 1 NQ
Table: `reports/s2_sweep/sweep.txt`. "PASTE defaults" = midnight VWAP, exit 15:55, 1 trade/day.

| Variant | Trades | Win % | PF | Payoff | Avg $ | Net $ | Max DD $ | Median TP pts |
|---|---|---|---|---|---|---|---|---|
| base | 1,967 | 76.1 | 1.00 | 0.31 | 0 | −63 | 17,025 | 5.75 |
| gross | 1,979 | 78.0 | 1.09 | 0.31 | 11 | 21,460 | 14,430 | 6.00 |
| PASTE defaults | 753 | 74.1 | 0.98 | 0.34 | −2 | −1,789 | 18,092 | 5.50 |
| **7E trail TP + SL** | 2,035 | 75.2 | **1.19** | 0.39 | 15 | 30,924 | **6,299** | 5.75 |
| 7C trail TP | 2,031 | 75.5 | 1.15 | 0.37 | 12 | 25,277 | 8,539 | 5.75 |
| 3D arm expiry 60 | 1,357 | 76.4 | 1.13 | 0.35 | 15 | 20,223 | 13,388 | 5.00 |
| 4E ADX th 25 | 1,536 | 77.4 | 1.09 | 0.32 | 12 | 18,814 | 15,824 | 6.50 |
| 2A VWAP 00:00 | 1,738 | 74.9 | 0.92 | 0.31 | −13 | −21,710 | 26,674 | 5.75 |

Trailing exits are the only family that clearly helps; midnight and 17:00 VWAP clearly hurt. Larger targets (tp 8, min TP 4–6) raise the
payoff to 0.40–0.45 but not the profit.

### B-04 `[BACKTEST]` 2026-11-02, fixed base (7E) per year, 1 NQ

| Year | Trades | Win % | Net $ | PF |
|---|---|---|---|---|
| 2019 (Sep–Dec) | 26 | 57.7 | −290 | 0.83 |
| 2020 | 305 | 73.1 | −4,403 | 0.79 |
| 2021 | 287 | 77.0 | 5,710 | 1.44 |
| 2022 | 263 | 71.1 | 470 | 1.01 |
| 2023 | 314 | 72.3 | −2,614 | 0.90 |
| 2024 | 333 | 79.9 | 13,078 | 1.68 |
| 2025 | 301 | 80.1 | 13,085 | 1.59 |
| 2026 (to Oct 14) | 206 | 72.8 | 5,887 | 1.23 |

Output: `reports/s2_sweep/7E_trail_both`.

### B-05 `[PROP-SIM]` 2026-11-02, all three strategies on ONE LucidPro account, 5 MNQ per trade each
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

### B-06 `[PROP-SIM]` 2026-11-02, S1 + S3 vs S1 + S2 + S3, 1 MNQ each, one LucidPro 25K account
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

## 5. Consolidated findings

1. As described, no edge after costs on NQ or MNQ.
2. The geometry rules out his claims: a 5-bar-high target on 1m bars is a few points, so payoff stays near 0.3.
3. Trailing both levels (fixed base) gives PF 1.19 and the smallest drawdown of the three strategies, but 3 of 8 years lose and most of
   the profit is 2024–2025. Treat as unconfirmed.
4. His first-video settings (08:30 VWAP) clearly beat the "midnight" reading.
5. **Not part of the portfolio:** at 1 MNQ it adds ~$1.9k over 7 years to S1 + S3, lowers the 25K pass rate (68.0% vs 73.1%),
   creates the only month beyond the 25K allowance and correlates 0.49 with S3 month to month (B-06).

## 6. Open questions and planned work

- Is "1 minute" wrong? Re-run on 2m / 3m / 5m bars (not done).
- Correlation with strategies 1 and 3; does it add anything to the portfolio despite the thin edge?
- Roll-gap correction; MNQ sizing and prop-challenge replay.

## 7. Caveats that apply to everything above

- His claims for this strategy are vague (no $ figure); the "7 RR" is read as 0.7.
- Trailing exits were not what he described; the fixed base is our choice, picked as the best of 56 variants.
- Unadjusted contract rolls; sparse 2019 data.

## 8. How to reproduce (run from `FLOW_V2/`)

```
# fixed base, 1 NQ
python analysis/gb_nq_backtest/vwap_adx.py --trail both --mult 20 --comm 4.60 --out reports/vwap_adx/base_7e
# as described (script defaults), MNQ
python analysis/gb_nq_backtest/vwap_adx.py --out reports/vwap_adx/base
```

## 9. File index

- Rules: `docs/video_transcripts/strategies/distilled/MatteoConti_2_VwapPullbackADX.txt`
- Script: `analysis/gb_nq_backtest/vwap_adx.py`
- Earlier write-up (superseded by this file): `docs/dynamic/matteoConti_vwapAdx_backtest_v1.md`
- Outputs: `reports/vwap_adx/`, `reports/s2_sweep/`
