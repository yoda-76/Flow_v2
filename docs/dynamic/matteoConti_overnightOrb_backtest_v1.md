# Matteo Conti "Overnight-Bias ORB" — NQ backtest v1 (2026-11-02)

Rules: `docs/video_transcripts/strategies/distilled/MatteoConti_3_OvernightBiasORB.txt` (v2: defaults + coded variants).
Script: `analysis/gb_nq_backtest/overnight_orb.py` (stdlib only, read-only over the CSV). Sweep table:
`reports/orb/sweep.txt`; each run in `reports/orb/<code>/`.

## Setup (base = all defaults)
- Data: `analysis/data/NQ_1m_latest.csv`, 2019-09-01 → 2026-10-14; 15m bars built from 1m (CT quarter hours).
- 1 NQ ($20/pt), $4.60 round trip, 1 tick slippage on market fills, SL/TP rounded to 0.25. Stop first if TP and SL
  share a bar (never matters here: 1m and 15m exits give the same result).
- Median stop $1,710 (≈ 85 pts) all, $1,845 since 2023.

## Base vs his claims

| | Ours, 2023 → Oct 2026 | Ours, all (2019 →) | His claim |
|---|---|---|---|
| Trades | 373 (215 L / 158 S) | 693 | "200 trades" (unclear what) |
| Win % all | 52.5 | 53.7 | 53 |
| "1.4" overall | payoff 1.39 (PF 1.54) | payoff 1.35 (PF 1.56) | 1.4 |
| Short side | 50.0% win, payoff 1.60 | 51.3%, payoff 1.56 | "50% win rate with a 1.6" |
| Long win % | 54.4 | 55.5 | ≈56 (derived) |
| Net | $154,169 | $261,117 | ≈$200,000 "this specific period" |
| Time exits | — | 63% | "mostly hit" |
| Max DD | — | $16,802 | — |

→ His "1.4" and "1.6" are **payoff ratios** (avg win / avg loss): the short side matches exactly (50.0% / 1.60).
Every calendar year is positive (2019 +3.6k … 2024 +63.7k, 2025 +17.7k, 2026 YTD +46.2k).

## One-factor sweep (all periods, 1 NQ)
Full table in `reports/orb/sweep.txt`. Highlights:
- Barely matter (within noise): 3B, 4B, 4C, 4D, 5C, 5E, 9B, 10B, 12B, 13B, 1B.
- Worse: 1C (17:00 ONR, $222k), 2B (ONR on closes, $229k), 3C (OR close, PF 1.35, DD $29.7k), 5B (ATR14 vol, $206k),
  6B (first fill 09:30, $204k), ADX(20) ($223k).
- Fewer trades, similar or slightly better per-trade: 7B fresh cross (PF 1.58), 7C first break only (PF 1.60,
  0.227R). 7D intrabar stop entry: more trades, lower PF (1.47).
- 10C (stop from the OR boundary) has the highest net ($281k) but a lower payoff (1.18) and larger DD ($21.7k).
- ADX(10): $258k, similar to base.

## Reading
- Unlike strategy 2, this one reproduces his figures closely out of sample with the default reading of every
  doubt, and the result is robust to most single-factor changes.
- The edge sits in a minority of big winners: removing the 53 largest winners turns every run negative (base −$18k).
  Matches his "outliers contributed 88,000" remark in spirit (his outlier definition is unknown).
- Low frequency (~100 trades/year) → for a 21-day challenge, often too few trades on its own (his own point).

## Not done yet
- Roll gaps not corrected (CSV not roll-adjusted; affects the ONR/vol on roll days).
- Combined variants (G3 of the rules file), MNQ sizing / prop-challenge replay, correlation with strategy 1.
