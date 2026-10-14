# Gianluca Bruni backtest — progress

Plan: `gianlucaBruniBacktest_plan_v1.md`. Machine state: `reports/gianluca_backtest/state.json`.

## RESUME HERE (new chat on the remote desktop)
User said "continue" → follow plan §10.4, with this override:

**No cron, no supervisor (user, 2026-10-12).** The VM has no `crontab`; the user chose to drive compute phases from
the chat instead. So in a compute phase the chat itself:
1. checks `reports/gianluca_backtest/runner.lock.json` / `pgrep -f gianluca_backtest/runner.py`; if not running,
   starts it detached at low priority, one plain command:
   `bash analysis/gianluca_backtest/start_runner.sh` (detached, nice +10, log in runner_logs/runner.log)
   (the runner writes its own lock file; mkdir `runner_logs/` first);
2. tells the user one line (phase, rough hours) and ends the turn — no polling;
3. on the next "continue": `--status`-style check (state.json phase, `wc -l runs/*.jsonl`, `tail -3` of the runner
   log). If the runner died without advancing the phase, read the traceback, fix, restart (count attempts; 4 in a
   row → stuck). If the phase advanced, carry on.
The user says "continue" when they check back; there is no Telegram notice for this.

Current position: see state.json and the latest phase note below.

## Checklist
- [x] P0_SETUP (2026-10-12)
- [x] P1A_BUILD_CORE
- [x] P1B_BUILD_SIGNALS
- [x] P1C_BUILD_SIZING_RUNNER
- [x] P2_VALIDATE
- [x] P3_SEARCH_FULL_A (compute)
- [x] P3_SEARCH_FULL_B (compute)
- [x] P4_REVIEW_GOLD
- [x] P5_SEARCH_FULL_C (compute)
- [x] P5_SEARCH_FULL_D (compute)
- [x] P6_PICK_BEST
- [x] P7_SPLIT (compute)
- [x] P8_REPORT

## P0 (2026-10-12)
- CSV: 2,193,481 rows + header, 1594591200000 (2020-07-12 22:00 UTC) → 1790974740000. Footprint 20723 (10,348
  lines), 20724 (12,788). Both strategy files present.
- Python 3.14.4, no numpy. 4 cores → workers ≤ 2. Disk 82 GB free. RAM 7 GB (4 GB available).
- Branch: `VM-orderflow-rules-review`, not `VM-gianluca-backtest` (§10.5 step 1 not done). Carrying on; no commits.
- `ops/test_gb_backtest_supervisor.py`: 7 tests OK.

## P1A (in progress, 2026-10-12)
- Written in `analysis/gianluca_backtest/`: `timeutil.py`, `data.py` (CSV → int-tick arrays, sessions, pickle
  cache), `bars.py`, `vprofile.py` (named so to avoid shadowing stdlib `profile`), `indicators.py` (vol SMA/EMA,
  Wilder ATR, ATR pct, BOCPD, swings), `features.py` (per-TF bundle + cache; `python3 features.py [tfs]` builds).
  Modules import each other flat (the package dir must be on sys.path; scripts in it get that automatically).
- `tests/test_core.py`: 14 tests OK (DST 2021/2025, sessions, short-session drop, bars + partial bar, gaps, profile
  spread, VA, POC tie, MA, ATR, ATR pct, swings, BOCPD change detection).
- Caches built (`python3 analysis/gianluca_backtest/features.py 15 5 10 30`): base parse 10.6 s, completed VAs
  1.9 s, tf15 47 s, tf5 138 s, tf10 69 s, tf30 26 s (BOCPD dominates). 1,608 sessions, 0 short sessions, 0 rows in
  the 17:00 ET break, 7 gaps > 30 min inside sessions. `sanity.py <tf>` prints a check: 0 malformed VAs; 15m FRESH
  73% / MATURE 22% / OLD 5%.
- **P1A done.**

## P1B (done 2026-10-12)
- `signals.py` (+ `tests/test_signals.py`, 14 tests OK: target, stop-first, gap stop, flatten, engulf margin, VAH
  margin, invalid candles, MS-low cancel (trade/close), ceiling + min R:R, re-entry only when flat, 08:30 news,
  16:45 cutoff, short mirror). Shorts = longs on the negated series.
- Smoke (`smoke.py`): baseline 15m 0.14 s/run, 425 trades, win 29.6%, ΣR −86, 1-lot −$17.6k. 5m variant 1.7 s/run.

## P1C (done 2026-10-12)
- `sizing.py`, `metrics.py`, `search.py` (space, eval, stages A/B/D + H1, coord descent), `runner.py`, `report.py`.
  Tests: `bash analysis/gianluca_backtest/tests/run_all.sh` — core 14, signals 14, sizing 8, runner 1 (DRY stage of
  20 configs, killed after ≥ 3 runs, resumed to exactly 20, state advanced, lock removed) — all OK.

## P2 (done 2026-10-12)
- **Profile check** (`p2_profile_check.py`, VA 70%): footprint 20724 covers the first 11.8 h of the 2026-09-29
  session with the same volume as the 1m data (46,197 vs 47,288); 1m approximation over that span vs exact:
  POC +4, VAH −8, VAL −6 ticks. Footprint 20723 is incomplete (28% of the 1m volume over its span) → not usable.
  The footprint only covers part of each session, so a whole-session comparison isn't possible.
- **Baseline** (all §4 baselines, full data): 425 signals, 26 taken, win 29.6%, exp −0.203 R/signal, Kelly net
  −$1,666 (−1.7%), max DD 2.0%, flat 1-lot −$17,595, PF 0.61, Sharpe −0.43, max consecutive losses 17. Skips:
  Kelly ≤ 0 on 359, score 38, streak 2. Learned buckets: ATR EXTREME, FRESH, NY.
- **Hand check** of 10 random baseline trades (`p2_baseline.py trades`): all correct — VAH/VAL break, engulf,
  volume, entry ±1 tick, stop at bar extreme, 2R target / MS-high cap (#303 capped, R:R 1.6), stop fill 1 tick
  through, target needs 1 tick through. #66 and #320 close inside the prior VA; both are re-entries (correct per
  GB-Q17 baseline). No bugs found.
- **Timing** (20 random A configs): 5m 0.31 s, 10m 0.19, 15m 0.14, 30m 0.08 s/run (developing-trend / 5m configs up
  to ~1.7 s). `config.json` workers = 2. Estimate: stage A ~5–10 min, B ~20–40 min wall clock. No numpy needed.

## P3 (started 2026-10-12 21:33)
- Runner started via `start_runner.sh`; it now chains compute phases by itself (A → B), then stops at
  P4_REVIEW_GOLD. Stage A resumed once (148/3300 kept) after that change. Don't `pkill -f runner.py` from the Bash
  tool: the pattern matches the tool's own shell. Use the pid from `runner.lock.json` instead.

## P3 results (A + B done 2026-10-12 21:40)
- A: 3,300 runs, only 13 eligible (≥ 150 taken). Kelly sizes most signals to 0 (rolling f* ≤ 0), so the floor
  removes almost every config with a volume condition: **all 1,286 eligible A+B configs have `vol_cond=none`** —
  an artefact of the floor on taken trades; say so in the results doc.
- B: 3,023 runs, 1,286 eligible, 587 eligible & profitable.
- Best (a7746e9444f2760e): 30m, t2, vol none, 3R, N5, EMA, trend none, ceiling off, min_rr 0.5, re-entry VAH on,
  hold, stop_buf 1, MS-break close, **long only**. 744 signals / 326 taken, Kelly +$355k (+355%), Sharpe 1.24,
  MDD 22%, flat 1-lot +$72k, but exp only +0.04 R/signal: the rolling Kelly sits out 2021–23 (−0.12 to −0.26 R)
  and sizes up 2024–25 (+0.33/+0.41 R).
- Parameter medians (eligible): long 66% profitable vs short 29% (gold 1800 → 4200 bull market); 30m, target
  2.5–3R, ceiling off, thr a0.2/t10, setups one, va 80 help.

## P4 (done 2026-10-13)
Top-config buckets: ATR LOW −0.16 R (n235) vs MEDIUM +0.37; FRESH +0.10 vs MATURE/OLD −0.12; ASIA +0.11, LONDON
+0.04, NY −0.04; stop_buf 3 > 1 > 0 (median). Five gold variants (new params, baseline = previous behaviour):
1. `sess_filter` all | no_ny | asia_only — NY triggers are the weakest bucket.
2. `atr_filter` all | no_low — LOW-ATR triggers lose.
3. `fresh_filter` all | fresh_only — only FRESH regimes are positive (score only sizes; this removes the signal).
4. `stop_atr` 0 | 0.25 | 0.5 × ATR extra stop distance — wider stops kept winning in B.
5. `ma_filter` off | 200 — trade only with the TF close above (long) / below (short) SMA(200) of TF closes: a
   principled regime filter in place of "long only", which is just the 2020–26 bull market.
Filters apply to first trades and re-entries. Stage C (`search.stage_c`): each variant value one at a time on the 20
best A+B configs + the full 72-combo variant grid on the 5 best = 460 runs. Variants are also in stage D and the
H1 search (B sampling + D), not in the already-run stage B. Tests 40 OK; baseline and best config reproduce exactly.

## P5 (C + D done 2026-10-13 06:55)
- C 460 runs, D coordinate descent 159 steps (150 new runs). Best moved from +$355k to **+$650k**:
  `ce0620b353815e2a` (30m, thr a0.3, 3R, N5, long, ceiling off, hold, setups many, `atr_filter=no_low`).

## P6 (done 2026-10-13)
- A\* = `ce0620b353815e2a`: 547 signals / 455 taken, exp +0.280 R, Kelly +$649,904, MDD 15.1%, flat 1-lot +$120k,
  Sharpe 1.51. $576k of it from 2024–25. 10 chunks: 9 positive in R, chunk 4 −19 R.
- Ranks 1–6 of the top 20 are the **same trade list** (params that don't change any trade).
- Monte Carlo as written in `report.monte_carlo` (shuffle % returns, compound) is **invariant to order** (the product
  doesn't change), so it can't measure anything. Replaced by `monte_carlo_resize`: shuffle the strategy-level trade
  order and re-run his sizing (Kelly/streak depend on order). 10k shuffles: 0% below $100k; final p5/50/95
  $368k/$526k/$788k; MDD p5/50/95 15.5/21.5/30.8%.
- Hand check of 10 A\* trades: all correct.
- Results doc §3–4 written.
- For P7: the H1 search's top 20 (H1_B seeds) and the H2 top 50 are now **distinct by result** (signals, taken,
  net $), not by hash, so the 50 aren't padded with copies of one trade list (`search.ranked(by_result=True)`).
  Full-data stages A–D are unchanged (already run).

## P7 (done 2026-10-13 07:05) and P8 (done 2026-10-13)
- A_on_halves 2, H1_A 3,300, H1_B 3,030, H1_D 109, H2_top50 50 runs. H1 = sessions 1–803 (to 2023-08-22).
- A\* H1 +0.10 R / H2 +0.45 R. B\* (H1 best, short only) +0.60 R → −0.01 R. Top 50 median +0.49 R → −0.05 R,
  Sharpe 1.43 → −0.62, 3/50 Kelly-profitable on H2. Degradation > 100% → not solid.
- `gianlucaBruniBacktest_results_v1.md` written in full (§1–9). Total configs tested: 13,424.
- **Backtest DONE.** Nothing committed (user decides). Scripts used for the phases are kept in
  `analysis/gianluca_backtest/` (`p2_*`, `p4_review.py`, `p6_*`, `p8_halves.py`, `sanity.py`, `smoke.py`).

## Decisions
- H1/H2 split: sessions [1, n//2) / [n//2, n) by count (session 0 has no previous VA and never trades).
- Trade floor counts **taken** trades (sized > 0), not signals.
- Warm-up (< 30 outcomes) risks a flat 0.25% but still applies score 0 / min_score / streak skips.
- "Loss" for streaks, Kelly and max consecutive losses = R ≤ 0; win = R > 0.
- Sharpe: P&L booked on the exit's session; every eval session is a day (zero-trade days included).
- Stage D = coordinate descent over every parameter, trying **all** other values of one parameter as a batch and
  moving to the best if it beats the current; passes repeat until no move; cap 300 runs.
- Stage B samples (seed = 1000 + A rank) every non-A parameter independently and uniformly.
- Runner pool: `forkserver` (Python 3.14 default on Linux), each worker loads the cache once.
- Trade CSVs are not written by the runner; `report.save_trades_csv` regenerates them deterministically for A\*,
  B\*, the top 20 and the H2 50 when P6/P8 need them.
- News: FOMC list left out (no dated list I can vouch for) → `news` ∈ {off, 0830}. Say so in the results doc.
- The touch bar may itself be the trigger (MS low/high fixed first, then the trigger checks run on the same bar).
- MS-high "setup ends" check runs after the trigger check on the same bar, and only on bars after the touch.
- BOS uses only the most recent known swing high; once broken, no BOS until a newer swing high is known.
- `ms_break=close` uses the TF bar close (GB-Q12 says "15m close"; TF varies).
- Entry cutoff: trigger bar must close strictly before 16:45 ET. Target rounded to the nearest tick.
- Score session bucket uses the trigger bar's close time minus 1 min (a bar closing 02:00 is ASIA).
- `thr` encoded `t<ticks>` or `a<k×ATR>`.
- Prices stored as int ticks (×10) everywhere; avoids float level errors in the profile.
- Bars at 17:00–17:59 ET belong to no session and are dropped (counted in `base.stats`).
- VA expansion tie (up pair = down pair) → add the upper pair.
- BOCPD run lengths ≥ 300 merged into r = 300. Freshness buckets per §5.4 (FRESH ≤ 30, MATURE ≤ 60, OLD > 60);
  §4 GB-Q20's "age > 100 = OLD" conflicts — §5.4 (the exact spec) wins.
- Vol MA / ATR before their window fills: running mean over the bars so far (only affects the very first bars).
