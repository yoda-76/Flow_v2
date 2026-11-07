# Gianluca Bruni strategy — NQ parameter search + in/out-of-sample (v1, 2026-10-14)

Range 2020-04-01 → 2026-03-31 (1,549 sessions, 1m OHLCV, `analysis/data/NQ_1m_latest.csv`). Code: `analysis/gb_nq_backtest/gb_search.py`
(`precompute` ~3 min once, then `search` ~18 min, 8 workers). Output in `reports/gb_nq/` (`search_results.md` = every table,
`runs.jsonl` = all 15,521 runs, `trades_{A,B}star_full.csv`). Rules/costs as in `gianlucaBruniBacktest_nq_v1.md`; the 26 settings
searched are every guess I made plus the gold-plan sweeps (tf, thresholds, VA%, MA type, volume rule, R:R, ceiling, fractal, trend
filter and its timing, setups per session, MS-low/MS-high definitions, MS-break, stop buffer, news, session-end, re-entry margin,
entry cutoff, direction, ATR/age/hour filters). Objective: total net R (floor 100 trades full / 50 half). Search: one-at-a-time
from baseline, 6,000 random configs, coordinate descent from the best 5 — on the full window, then again on H1 only.
H1 = 2020-04-01..2023-03-30, H2 = 2023-03-31..2026-03-31 (sessions split 50/50).

## Headline
| | n | win | exp R | total R | PF | maxDD | Sharpe |
|---|---|---|---|---|---|---|---|
| His numbers, full range | 174 | 37.9% | −0.050 | −8.7 | 0.92 | 23R | −0.07 |
| His numbers: H1 / H2 | 99 / 75 | 40% / 35% | +0.023 / −0.146 | +2.3 / −10.9 | 1.04 / 0.77 | | +0.31 / −0.47 |
| **A\*** best fit on the full range | 1,380 | 29.4% | +0.155 | **+214** | 1.21 | 36R | +1.22 |
| A\* on H1 / H2 (both inside its own fit) | 679 / 701 | | +0.085 / +0.223 | +58 / +157 | | | +0.60 / +1.81 |
| **B\*** best fit on H1 only → H1 | 845 | 29.9% | +0.169 | +142 | 1.23 | 33R | +1.63 |
| **B\* → H2 (out of sample)** | 874 | 24.5% | **−0.050** | **−43.9** | 0.94 | 59R | **−0.28** |
| Top-50 H1 configs → H2, median | 496 | | **−0.055** | **−28** | | | −0.52 |

**Out of sample the edge does not survive:** fitted on H1, the best config makes +0.169R/trade, and on H2 it loses −0.050R; of the 50
best H1 configs only **1 of 50** is profitable on H2 (median in-sample +0.292R → median out-of-sample −0.055R).

## A\* settings (best fit on all the data)
`tf=15, thr_vah=0, thr_eng=15, va=0.7, vma=ema, volume rule=none, reduc_n=2, rr=3, min_rr=1, ceiling=off, fractal=5, trend filter=none,
setups=many, ms_break=trade, ms_low=valid_to_bos, ms_high=lookback, above_VAH_first=no, stop_buf=2 ticks, news=off, session_end=hold,
re-entry margin=off, cutoff=0, direction=both, ATR filter=none, age<=60, hours=all.`
It has abandoned most of his rules (no volume condition, no trend filter, no ceiling, no VAH margin): effectively a generic
"close 15 points above the previous high" breakout with 3R target. B\* is similar. Treat both as overfit, not as the strategy.

## What each guess does (one change from his numbers, full range; baseline −8.7R)
Helps most: setups per session **many** +18R; engulf margin **4 pts** +16.5R (10 pts +11R); age ≤30 +13R; volume rule **both** +11.5R /
**low-effort only** +8.8R; trend filter **developing VA** +11R / none +5.5R; MS-low = valid candle's own low +8.6R / valid→BOS +7.3R;
price need not be above VAH first +8.2R; rr 3 +6.9R; ATR>25 +7R; short only +6.6R.
Hurts most: volume rule **none** −18R; **EMA** instead of SMA −18R; re-entry also needs VAH margin −14.5R; VA 60% −14R;
tf 5/10/30 −3/−8/−9R; trend = POC higher −10R; fractal 5 −7R; ceiling off −3R.
Across the 6,000 random configs (n≥100) averages are negative for almost every value; only large engulf margins (15–20 pts) average positive
(+6R, 50–110 configs). Best single random config: +83.5R. Descent found much higher (A\*) — a sign of overfitting, not of an edge.

## Reading it
1. With his stated numbers NQ shows no edge in 2020–2026 (H1 ≈ 0, H2 negative).
2. Searching 15k configurations finds many that look excellent in-sample (Sharpe 1.2–1.6), and they fail out of sample — the standard
   outcome of fitting noise; the fitted settings are not his strategy anymore.
3. The only choices that help consistently across many views: allow more than one setup per session, a larger engulf margin than 6
   (4–10 pts), require the volume condition (both/low-effort), and fresh regimes — each worth testing alone, none proven.
4. Not tested: the true tick/volume profile (1m approximation), sizing/score (the search maximises R, not Kelly equity), NY-hours
   variants beyond 3 buckets, any time-of-day exits. Costs: $4.60 + 1 tick (market fills); no cost sensitivity run.
