# Gianluca Bruni strategy — NQ backtest with HIS numbers (v1, 2026-10-14)

One run, no fitting. Code: `analysis/gb_nq_backtest/gb_backtest.py` (stdlib only). Data: `analysis/data/NQ_1m_latest.csv`
(2,423,665 1m bars, 2019-08-23 → 2026-10-14; 1,846 sessions, 71 invalid <300 bars). Output: `reports/gb_nq/{summary.txt,trades.csv}`.
Re-run: `python analysis/gb_nq_backtest/gb_backtest.py analysis/data/NQ_1m_latest.csv --out reports/gb_nq` (~30 s).
Rules = `gianlucaBruniBacktest_plan_v1.md` §5 on NQ ($20/pt, tick 0.25). Strategy / to-be-reviewed files untouched.

## His numbers used (video)
15m; threshold **6 points** (VAH break and engulf margin); 2R target capped at MS high, no minimum R:R; stop = trigger candle
low; volume SMA(20), reducing OR low-effort; ATR 14 / percentile 200; BOCPD hazard 2%; best buckets = HIGH volatility (ATR pct
51–75), FRESH (age ≤30), New York; Kelly ½/¼/⅕/⅙ for score 5/4/3/2. Costs: $4.60 round trip + 1 tick on market fills (entry,
stop, session flatten); limit exits need 1 tick through.

## Guesses where the video is silent (plan §4 baselines — NOT his numbers)
VA 70%; fractal N=3 for swings/BOS; trend filter = last two completed sessions' VAH and VAL both higher (shorts mirrored);
one setup per session; no entry in the last 15 min; news = skip bars overlapping 08:15–08:45 ET on weekdays; MS-low break =
trade-through; flatten at session end; score-1 size 1/8 Kelly, score-0 skip; BOS leg must be within the previous 2 sessions;
autocorrelation strength formula = plan's. Position sizing done in R-space (no contract rounding).

## Result
212 trades (128 long / 84 short) in ~6.8 years vs his 727. Win 39.6%, expectancy **−0.029R net** (−0.018R gross), PF 0.95,
total −6.2R, maxDD 23R. Fixed 0.5% risk: −3.5%, Sharpe ≈ 0. Kelly-table sizing: −4.2%, Sharpe −0.40.
- First trades −0.165R (n=122); re-entries +0.155R (n=90). Long −0.060R, short +0.019R.
- By year: 2020 −0.02, 2021 −0.16, 2022 +0.07, 2023 −0.33, 2024 −0.04, 2025 −0.24, 2026 +0.27 (R/trade).
- His "best" buckets do not stand out on NQ: HIGH −0.04R, FRESH −0.00R, NY +0.04R (n=39); EXTREME volatility is best (+0.26R,
  n=27). Score 4 +0.42R (n=7); score 5 never occurs. No trade was streak-skipped (autocorrelation too weak to arm the rule).
- Halves (no refit): −0.006R then −0.052R.
- Checked: 8 random trades re-derived independently from the raw 1m file (entry, stop, exit bar and reason all match).

## Reading it
With his stated numbers and these guesses there is no edge on NQ in this data. Not proof against his method: the trade count
is 3.4× lower than his, so one or more of the guessed rules is stricter than what he does (candidates: one setup per session,
trend filter on completed sessions, BOS-leg search, the 08:30 news filter). Next step if wanted: loosen those one at a time
and see which restores his trade frequency, then judge expectancy — that is a fit to his count, not a blind test.
