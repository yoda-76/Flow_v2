# Matteo Conti "Vault Break" — MNQ backtest v1 (2026-11-01)

Rules: `docs/video_transcripts/strategies/distilled/MatteoConti_1_VaultBreak.txt` (v2, all doubts resolved by the user).
Script: `analysis/gb_nq_backtest/vault_break.py` (stdlib only, read-only over the CSV). Full output: `reports/vault_break/<run>/`.

## Setup
- Data: `analysis/data/NQ_1m_latest.csv`, NQ 1m bars, 2019-09-01 → 2026-10-14. Timestamps checked to be bar OPEN times
  (08:30 CT volume spike sits on the 13:30 UTC minute in summer).
- Traded as MNQ ($2/pt), 1 contract, $1 round-trip commission, 1 tick slippage on market fills (entry, stop, time
  exit), none on the TP limit. TP fills on touch.
- Exits resolved on 1m bars; stop first if TP and SL are both inside one bar.
- In-sample < 2023-01-01, out-of-sample ≥ 2023-01-01 (his split).
- 1,838 days in window: 67 skipped (holiday / early close), 40 skipped for missing data (sparse 2019–2020).

## Results (1 MNQ, after costs)

| Run | Trades | Win % | Avg $/trade | Raw $/trade per 1 NQ, no costs | PF | Net $ | Max DD $ |
|---|---|---|---|---|---|---|---|
| Base (1m exits) — ALL | 1,488 | 66.3 | 8.59 | 103 | 1.23 | 12,780 | 1,808 |
| Base — in-sample | 614 | 65.8 | 9.25 | 110 | 1.27 | 5,682 | 969 |
| Base — out-of-sample | 874 | 66.7 | 8.12 | 98 | 1.20 | 7,099 | 1,808 |
| 30m exits (SL-first on 30m bars) — ALL | 1,488 | 64.9 | 5.33 | 71 | 1.13 | 7,930 | 1,894 |
| TR = plain high − low — ALL | 1,524 | 66.2 | 8.33 | 101 | 1.22 | 12,690 | 1,808 |
| No costs at all — ALL | 1,492 | 66.6 | 10.31 | 103 | 1.28 | 15,387 | 1,690 |
| 1 NQ ($20/pt, $4.60 RT) — ALL | 1,488 | 66.3 | 91.29 | 103 | 1.24 | 135,835 | 17,632 |
| 1 NQ — out-of-sample | 874 | 66.7 | 86.62 | 98 | 1.22 | 75,705 | 17,632 |
| 5 MNQ (his live size) — ALL | 1,488 | 66.3 | 42.94 | 103 | 1.23 | 63,900 | 9,040 |

Per year (base): every year positive except 2019 (22 trades, −$59). Weakest full year 2024 (+$344, DD $1,420).

## Reading
- Close to his claims: win rate 66–67% vs his ~67%, raw average ~$103 per NQ vs his ~$112. Out-of-sample holds up
  (PF 1.20 vs 1.27 in-sample).
- Thin edge after costs: ~$8.60 per MNQ trade; costs take ~17% of gross.
- The stop-first-on-30m-bars rule costs about a third of the edge — 1m exit resolution matters.
- TR mode (with/without the overnight gap) barely matters.

## Not done yet
- Roll gaps: the CSV is not roll-adjusted, so a roll day inflates that day's TR for 15 days. Not corrected.
- Not tested: SMA15(ATR14) volatility, one-bar gap before re-entry, K sensitivity, prop-challenge replay.

## v2 — one-factor sweep (2026-11-02), 1 NQ, $4.60 RT
Variant codes S1-x from the user's list (2026-11-02). Full table: `reports/s1_sweep/sweep.txt`.
His target (2022 → 2026): avg trade $112–121 per NQ, win ≈ 67%, payoff ≈ 0.6.

| Variant | Trades | Win % | PF | Avg $ | Net $ | Max DD $ | 2022+ avg $ | 2022+ win % |
|---|---|---|---|---|---|---|---|---|
| base | 1,488 | 66.3 | 1.24 | 91 | 135,835 | 17,632 | 80 | 66.8 |
| gross (no costs) | 1,492 | 66.6 | 1.28 | 103 | 153,870 | 16,895 | 90 | 67.0 |
| **2C open = 17:00 Globex** | 1,517 | 67.6 | **1.34** | **122** | **185,527** | **11,084** | **123** | **68.8** |
| 1C EMA vol | 1,490 | 67.0 | 1.27 | 101 | 150,661 | 16,498 | 94 | 67.7 |
| 1G k = 0.35 | 1,346 | 67.2 | 1.31 | 111 | 149,793 | 11,210 | 112 | 68.0 |
| 3C VWAP 08:30 | 1,446 | 66.5 | 1.28 | 103 | 149,068 | 18,624 | 92 | 67.0 |
| 9 exits on 30m bars | 1,488 | 64.9 | 1.15 | 59 | 87,335 | 18,411 | 42 | 65.1 |

- **2C (session open = 17:00 CT Globex open) is the only variant that lands in his range on both average and win
  rate for 2022+, and it is better on every measure** (PF, net, DD, 0 losing years). But he said "midnight" explicitly.
- Worse: 2D (08:30 open), 4B (first signal 10:30), 5C/5D (fresh-cross re-entry), 6B cap 1–2, 8D (exit 13:30).
- No effect (identical or within noise): 3D, 3E, 3B, 7B, 7C, 8C, 9B, 2B, 1D, 1E, cap 4–5.
- k plateau: 0.20–0.40 all positive; 0.35–0.40 slightly better than 0.30 (fewer trades).
- Exit resolution matters: 30m bars with stop-first halves the 2022+ average; 30m with the bar-direction rule ≈ 1m.
