# Matteo Conti "VWAP Pullback + ADX Gate" — NQ/MNQ backtest v1 (2026-11-01)

Rules: `docs/video_transcripts/strategies/distilled/MatteoConti_2_VwapPullbackADX.txt` (v3, all doubts resolved by the user).
Script: `analysis/gb_nq_backtest/vwap_adx.py` (stdlib only, read-only over the CSV). Full output: `reports/vwap_adx/<run>/`.

## Setup (base)
- Data: `analysis/data/NQ_1m_latest.csv`, 1m bars, 2019-09-01 → 2026-10-14. Timestamps = bar open time (verified).
- OR 08:30–09:00 CT; VWAP anchored 08:30 CT; Wilder ADX(14) continuous on 1m; ADX > 20 and not rising.
- Re-check mode (entry bar must close above VWAP); TP = 5-bar high, SL = 20-bar low, both incl. signal bar, fixed.
- Skip if next open >= TP or <= SL. Max 4 trades/day, one at a time, stop after 2 losses in a row, no entries after
  14:30 CT, exit 14:55 CT. Stop first if TP and SL are both inside one 1m bar.
- MNQ: $2/pt, $1 round trip, 1 tick slippage on market fills. NQ: $20/pt, $4.60 round trip.
- 1,222 armed days, 791 days with trades. TP-skip fired on 2.1% of signals (his claim: "< 5%").

## Results

| Run | Trades | Win % | Avg $/trade | Raw $/trade per 1 NQ, no costs | PF | Net $ | Max DD $ |
|---|---|---|---|---|---|---|---|
| Base, 1 MNQ — ALL | 1,955 | 69.6 | −0.55 | 10.6 | 0.96 | −1,080 | 2,356 |
| Base, 1 MNQ — in-sample (<2023) | 855 | 66.9 | −1.62 | 0.0 | 0.87 | −1,385 | 2,146 |
| Base, 1 MNQ — out-of-sample | 1,100 | 71.7 | 0.28 | 18.8 | 1.02 | — | — |
| Base, 1 NQ — ALL | 1,967 | 76.1 | −0.03 | 10.7 | 1.00 | −63 | 17,025 |
| No costs, 1 MNQ — ALL | 1,979 | 78.0 | 1.08 | 10.8 | 1.09 | 2,146 | 1,443 |
| Latch mode | 2,242 | 68.6 | −1.25 | 3.7 | 0.91 | −2,793 | 3,175 |
| VWAP anchored 00:00 CT | 1,725 | 68.5 | −1.77 | −1.6 | 0.88 | −3,057 | 3,397 |
| 1 trade/day | 791 | 69.5 | 0.11 | 17.2 | 1.01 | 85 | 1,593 |
| Exit 15:55 CT | 1,955 | 69.7 | −0.54 | 10.7 | 0.96 | −1,048 | 2,371 |
| ADX(10) | 2,140 | 69.8 | −0.76 | 8.5 | 0.94 | −1,635 | 2,767 |
| ADX(20) | 1,539 | 67.6 | −0.36 | 12.6 | 0.97 | −548 | 2,260 |

(Variants are 1 MNQ, ALL. NQ win % is higher than MNQ because costs are a smaller share: on MNQ a 1–2 point TP
can turn into a net loss.)

NQ per year (1 contract): 2019 −5, 2020 −12,588, 2021 +3,328, 2022 +217, 2023 −3,891, 2024 +11,990,
2025 −8,430, 2026 +9,315.

## Reading
- No edge as coded. Gross of costs it makes ~$11 per NQ per trade; costs ($1.61/MNQ trade = 8.8% of the avg
  winner) take it to breakeven or below. Out-of-sample is slightly positive (PF 1.02 MNQ, 1.06 NQ), in-sample negative.
- The geometry is much worse than he describes: median TP 5.75 pts vs median SL 26.75 pts → reward:risk ≈ 0.21, not
  the ~0.7 he implied. Breakeven win rate at 0.21 ≈ 82%; we get 78% gross.
- No variant fixes it. The midnight VWAP is clearly worse; 1 trade/day and ADX(20) are marginally better. Exit time
  barely matters (only 5 time exits).
- Year-to-year swings are large (NQ: −12.6k to +12.0k) for an average near zero.

## Not done yet
- Roll gaps not corrected (CSV not roll-adjusted).
- Untested: 10:00 CT start, re-arming variants, ADX threshold other than 20, TP/SL definitions other than 5/20 bars.
- His video-1 strategy (15m "drift VWAP pullback", fixed 80-pt stop / 40-pt target) is a different strategy and
  was not tested.

## v2 — one-factor sweep (2026-11-02), 1 NQ, $4.60 RT
Variant codes S2-x from the user's list (2026-11-02). Full table: `reports/s2_sweep/sweep.txt`.
His target: fewer trades than strategy 1 (< ~1,490), avg trade > strategy 1's, win ≈ 67%, payoff ≈ 0.7.
"PASTE defaults" = the list's own defaults (VWAP 00:00, exit 15:55, 1 trade/day) — differ from the user's base.

| Variant | Trades | Win % | PF | Payoff | Avg $ | Net $ | Max DD $ | Median TP pts |
|---|---|---|---|---|---|---|---|---|
| base | 1,967 | 76.1 | 1.00 | 0.31 | 0 | −63 | 17,025 | 5.75 |
| gross (no costs) | 1,979 | 78.0 | 1.09 | 0.31 | 11 | 21,460 | 14,430 | 6.00 |
| PASTE defaults | 753 | 74.1 | 0.98 | 0.34 | −2 | −1,789 | 18,092 | 5.50 |
| 7E trail TP + SL | 2,035 | 75.2 | 1.19 | 0.39 | 15 | 30,924 | 6,299 | 5.75 |
| 7C trail TP | 2,031 | 75.5 | 1.15 | 0.37 | 12 | 25,277 | 8,539 | 5.75 |
| 3D arm expiry 60 | 1,357 | 76.4 | 1.13 | 0.35 | 15 | 20,223 | 13,388 | 5.00 |
| 4E ADX th 25 | 1,536 | 77.4 | 1.09 | 0.32 | 12 | 18,814 | 15,824 | 6.50 |
| 2A VWAP 00:00 | 1,738 | 74.9 | 0.92 | 0.31 | −13 | −21,710 | 26,674 | 5.75 |

- **No variant comes near his claims.** Best is 7E (both levels trailing): PF 1.19, $15/trade, payoff 0.39 —
  vs his ~0.7 payoff and > $90/trade. The median target stays 4–10 points in every variant, so a payoff of 0.7 with
  $100+/trade is not reachable with a 5-bar-high target on 1-minute bars.
- Likely explanations: a different bar size than "1 minute", different exits than described, or a wrong claim.
- Trailing exits (7C/7D/7E) are the only family that clearly helps; the midnight / 17:00 VWAP clearly hurts.
- Identical to base (float ties never happen): 3F, 4C.
