# Gianluca Bruni strategy — curiosity backtest on gold: plan v1

**Written:** 2026-10-13 on the dev machine, for a Claude Code chat on the VM (compute runs keep going without it, §10).
**What it is:** a one-off, curiosity backtest of the Gianluca Bruni strategy on @GC 1-minute OHLCV, using our best
guesses for every open question. The parameters are then fitted to gold as hard as we can ("overfit"), followed by a
simple first-half / second-half test.
**What it is not:** it is not a FLOW decision. It has nothing to do with FLOW's rules, `config/risk.json`, the 1-contract
cap or the runtime. It does not answer the review questions; the user is still reviewing the video themselves.
**Do not touch:** `docs/dynamic/gianlucaBruniStrategy.md` and `docs/dynamic/gianlucaBruniStrategy_toBeReviewed_v1.md`
(read them, never edit them). All new material goes into the new files listed in §8.

Contents:
1. Hard rules
2. Inputs
3. The user's answers and how they are read
4. Best guesses for every GB-Q
5. Exact rule spec
6. Search plan
7. In-sample / out-of-sample procedure
8. Files
9. Phases
10. Unattended operation
11. Estimates
12. Known weaknesses

---

## 1. Hard rules (apply to every session and every script)

- **No orders, no MotiveWave, no FLOW runtime.** This work only reads a CSV and writes files. Never open, arm, add or
  restart anything in MotiveWave. Never touch `config/`, `flow-core/`, `flow-runtime/` or `build/`. Never run
  `build.sh`. The live Sim runtime on the VM must not notice this work happened.
- **Never read `.env`.**
- **CPU:** the VM also runs MotiveWave live. Every process from this work runs at **BELOW_NORMAL** priority, and the
  runner uses at most `max(1, cores // 2)` worker processes. If `ops/watchdog.py` raises a lag or feed alert while a
  compute phase runs, set `workers` to 1 in `reports/gianluca_backtest/config.json`.
- **Token economy (user rule, 2026-10-13): use as few tokens as possible.**
  - Keep tool output small: tail/grep/counts, never whole files or big tables.
  - Read only the plan sections the phase needs.
  - Write to the progress file rarely and briefly: at phase ends and at real decisions, a few lines each, not after
    every step.
  - No long chat summaries.
  - Don't poll running compute; end the turn (§10.4).
  - The record must still be complete enough for a new chat to resume.
- **No git commit or push.** The user decides later; the VM rule is a `VM-` branch, see P0. Work stays as files on
  disk.
- Never edit the two strategy files named above.
- No new pip packages unless P0 finds Python lacks them. The standard library is enough (§5.13). numpy may be installed
  with `pip install --user numpy` if P2 shows pure Python is too slow (§11). Nothing else.

## 2. Inputs

| What | Where on the VM | How it gets there |
|---|---|---|
| @GC 1m OHLCV, 2020-07-12 → 2026-10-02, 2,193,481 rows, columns `timestampMs,open,high,low,close,volume` (UTC ms, assumed to be bar **open** time: the first row is 22:00 UTC Sunday = 18:00 EDT, the Globex open) | `analysis/data/GC_1m_latest.csv` | git: force-added despite the `analysis/data/` ignore rule (about 97 MB) |
| Two days of real per-price volume (FLOW footprint, 1-second rows with `rows:[{p,a,b}]`) for sessions 20723 and 20724 (2026-09-27/28), inside the CSV's range | `analysis/data/gb_vp_check/20723.jsonl`, `20724.jsonl` | git: force-added copies of `data/footprint/GC/2072{3,4}.jsonl` (about 3 MB) |
| The strategy (read only) | `docs/dynamic/gianlucaBruniStrategy.md`, `..._toBeReviewed_v1.md` | git. The transcripts under `docs/video_transcripts/` are gitignored and not on the VM; the strategy file is enough. |
| This plan, the supervisor | `docs/dynamic/gianlucaBruniBacktest_plan_v1.md`, `ops/gb_backtest_supervisor.py`, `ops/test_gb_backtest_supervisor.py`, `ops/register_gb_backtest_task.ps1` | git |

Instrument constants: GC tick = 0.1, $10 per tick, $100 per point. Sizing is in **0.1-contract steps** (MGC = 1/10 GC).

## 3. The user's answers (2026-10-13) and how they are read

| Topic | The user said | How it is applied |
|---|---|---|
| What the search maximises | "Net $ PnL, with a trade floor" | Maximise **net $ PnL of the Kelly-sized equity curve** (§5.11), start equity $100,000. Configs with fewer than **150 trades** (full data) or **75** (one half) are ineligible. Tie-break: higher Sharpe. |
| Costs | "$4.60 commission and 1 tick slippage" | **$4.60 per round trip per contract**, scaled by size. **1 tick ($10/contract) slippage on every market fill:** entry, stop exits, session-end flatten and MS-high exits. Target exits are limit orders at the target price with no slippage, but only fill when price trades **1 tick through** the target. Both readings ("per round trip", "per market fill") are this plan's interpretation; flag them in the results doc. |
| Sizing | "use the contract sizing that he recommended; this backtest has nothing to do with FLOW rules" | His Kelly table by score (§5.11), on % of equity, compounding, in 0.1-contract steps. A flat 1-contract result is reported **alongside** it for reference only; it is never the objective. |
| "Find the medium" | "Median of top-N on H2" | Take the top 50 first-half configs, run each on the second half, and report the **median** second-half result, plus the single best first-half config's second-half result (§7). |
| Ambiguities | "u decide best possible guesses" | §4. Each guess is either fixed or swept; swept ones get gold-fitted in §6. |

## 4. Best guesses for every GB-Q

"Fixed" means one value is used throughout. "Swept" means it is a search parameter, and the first value listed is the
baseline (his reading).

| Q | Topic | Guess | Swept values (baseline first) |
|---|---|---|---|
| GB-Q1 | Threshold unit | Points on NQ, so his 6 NQ points ≈ 24 NQ ticks. For gold this only sets the scale of the sweep. | — |
| GB-Q2 | Threshold for GC | Same threshold for the VAH-break and engulf margins | Fixed ticks: 0, 2, 4, 6, 10, 15, 20, 30. ATR mode: 0.1, 0.2, 0.3 × ATR(14) of the timeframe. 11 values in total. |
| GB-Q3 | Session | Full Globex day, 18:00 → 17:00 ET (§5.1) | fixed |
| GB-Q4 | Value-area % | 70% | 70, 60, 80 |
| GB-Q5 | Volume MA | SMA(20) | SMA, EMA |
| GB-Q6 | Reducing volume | One bar: vol < previous bar's vol | fixed |
| GB-Q7 | Pullback / break of structure | Fractal swings, N bars each side, confirmed N bars later (no look-ahead). BOS = a close above the last confirmed swing high. | N = 3, 2, 5 |
| GB-Q8 | MS low span | Lowest low of the whole pullback leg that contains the valid bearish candle (§5.6) | fixed |
| GB-Q9 | Valid candle | Strict: body > longest wick | fixed |
| GB-Q10 | MS high | Highest high between the last BOS bar and the first pullback touch, i.e. the peak before the pullback (§5.6) | fixed |
| GB-Q11 | Trend filter test | VAH and VAL both higher (overlap allowed) | (a) both higher; (c) POC higher; none |
| GB-Q27 | Trend filter timing | The last two **completed** sessions | completed; developing-at-trigger |
| GB-Q12 | MS low broken | Any trade below it (low < MS low) | trade-through; 15m close below |
| GB-Q28 | Previous VA at rollover | The setup ends at session end. A new session starts fresh against its own previous VA. | fixed |
| GB-Q29 | Setups per session | One | one; many (re-arm after a fresh rise above VAH and a new touch) |
| GB-Q13 | News | 08:30 ET window: skip a trigger whose bar overlaps 08:15–08:45 ET on any weekday (NFP, CPI and most US data print at 08:30). Add FOMC 14:00 ET ± 15 min only if a dated FOMC list can be written with confidence; otherwise leave it out and say so. | off; 08:30 window; 08:30 + FOMC |
| GB-Q14 | Stop | Exactly at the trigger bar's low (high for shorts) | buffer 0, 1, 3 ticks |
| GB-Q15 | Minimum R:R under ceiling | 1R floor | 1.0, 0, 0.5 |
| GB-Q16 | Re-entry while open | Only when flat | fixed |
| GB-Q17 | Re-entry VAH margin | Engulf + volume only | off; on (re-entry must also close ≥ threshold above prev VAH) |
| GB-Q30 | Overnight | Flatten at the last 1m bar of the session | flatten; hold |
| GB-Q31 | Above VAH first | Yes: high > prev VAH earlier in this session, before the touch | fixed |
| GB-Q32 | Pullback depth | Wick touch: low ≤ prev VAH | fixed |
| GB-Q18 | Score volume point | Reducing volume only | fixed |
| GB-Q19 | When regime is read | At the trigger bar | fixed |
| GB-Q20 | BOCPD | Our own: Adams–MacKay, Normal–Gamma model on log(high/low) of each bar, hazard 1/50, run length truncated at 300, regime age = MAP run length. Age > 100 = OLD. | fixed |
| GB-Q21 | NY hours / "best session" | Buckets by trigger time (ET): ASIA 18:00–02:00, LONDON 02:00–08:00, NY 08:00–17:00. The "best" bucket is learned in-sample (§5.10). | fixed |
| GB-Q22 | Score 1 and 0 | 1/5 = ⅛ Kelly, 0/5 = skip | fixed (min score is swept, §6) |
| GB-Q23 | Kelly vs 1-contract cap | **Not applicable**: the user said to use his sizing, not FLOW's cap (§3) | — |
| GB-Q24 | Autocorrelation 0–10 | strength = clip(10 × (P(W\|W) − P(W)) / 0.16, 0, 10) over the last 100 outcomes. This is calibrated so his 52.5% vs ~40% ≈ 7.9; it is a guess. | fixed |
| GB-Q25 | Streak skip | Skip the trade right after the first loss that ends a ≥ 2-win streak, only when strength ≥ 3. Q2 point: the trade right after the first win that ends a ≥ 2-loss streak, only when strength ≥ 3. | streak rules on; off |
| GB-Q33 | Kelly window | The last 100 strategy-level outcomes, causal (§5.11) | fixed |
| GB-Q26 | 1m profile approximation | Accepted for curiosity; measured in P2 (§5.3) and reported, never a blocker | fixed |
| GB-Q34 | IS/OOS split | Superseded by the user's halves procedure (§7) | — |

**Gold additions** (not his rules, swept so "adjust the rules for gold" has room):
- Timeframe: 15, 5, 10, 30 minutes.
- Direction: both, long only, short only.
- Target R: 2, 1, 1.5, 2.5, 3.
- MS-high ceiling: on, off.
- Volume condition: either (his), reducing only, low-effort only, both, none.
- Min score to trade: 0, 1, 2, 3.
- Kelly risk cap per trade: 2%, 1%.
- P4 may add at most **5** more variants (§9).

## 5. Exact rule spec

Shorts are the exact mirror of longs: VAL instead of VAH, lows instead of highs, VAs shifted down, pullback up into the
previous VA, close below prev VAL by the threshold, engulf = close < previous bar's low − threshold, stop at the trigger
bar's high. Only longs are written below.

### 5.1 Time and sessions
- Convert UTC ms to US Eastern by hand; the VM's Python may lack tzdata, so do not use `zoneinfo`. EDT (UTC−4) runs
  from the 2nd Sunday of March 02:00 local to the 1st Sunday of November 02:00 local, otherwise EST (UTC−5). Unit-test
  both 2021 and 2025 transitions.
- **Session** = 18:00 ET → 17:00 ET next day; session date = the ET date of its close. A 1m bar belongs to the session
  that contains its **open** time.
- Sessions with fewer than 300 one-minute bars (holidays, data gaps) are **not sessions**: no trades, and they are
  skipped when looking up "previous session". Log how many there are.
- Check for gaps longer than 30 minutes inside a session and report the count; don't fix them.

### 5.2 Bars
- Build TF-minute bars (TF ∈ 5, 10, 15, 30) inside each session, aligned to the session open (18:00, 18:15, ...).
  - O = first open, H = max, L = min, C = last close, V = sum.
  - A bar's time is its close time.
  - A partial last bar at 17:00 is kept.
- Bars never span two sessions.

### 5.3 Volume profile from 1m bars (approximation, GB-Q26)
- **Spreading:** each 1m bar's volume is spread evenly over every 0.1 price level from its low to its high, inclusive.
  A bar with high = low puts all its volume on that one level.
- **POC** = the level with the most volume (ties: the one closest to the session's volume-weighted mean price).
- **Value area (VA%):** TradingView style.
  1. Start at the POC.
  2. Compare the total of the next 2 levels above with the next 2 below.
  3. Add the larger pair.
  4. Repeat until the VA holds ≥ VA% of the session's volume.
  - VAH and VAL are the top and bottom levels of the VA.
- **Completed-session VA:** one per session, cached per VA%.
- **Developing VA:** recomputed at each TF bar close from that session's profile so far, cached per (TF, VA%). It is
  only needed for the developing trend-filter variant; compute it lazily if it's too slow.
- **P2 check:**
  - Build the exact per-price profile for sessions 20723/20724 from the footprint files: sum `a + b` per price, using
    only rows with timestamps inside the session.
  - Compare its POC, VAH and VAL with the 1m approximation for the same sessions.
  - Report the differences in ticks. The result is information only, not a gate.

### 5.4 Indicators (all causal: a value at bar i uses bars ≤ i only)
- **Volume MA:** SMA(20) or EMA(20, α = 2/21) of TF-bar volume, crossing session boundaries.
- **ATR(14):** Wilder's, on TF bars. **ATR percentile** = % of the last 200 ATR values (including the current one) that
  are ≤ the current one, on 0–100. Buckets: LOW 0–25, MEDIUM >25–50, HIGH >50–75, EXTREME >75.
- **BOCPD** as in GB-Q20.
  - Normal–Gamma priors: μ0 = the mean of the first 500 bars, κ0 = 1, α0 = 1, β0 = the variance of the first 500 bars.
  - Freshness buckets: FRESH 0–30, MATURE 31–60, OLD > 60.
  - Precompute once per TF and cache.
- **Swings** (fractal N):
  - Bar k is a swing high if its high is greater than the highs of the N bars on each side; swing lows mirror this.
  - A swing is only **known** at bar k + N.
  - Swings may cross session boundaries.

### 5.5 Building blocks (from the strategy file §3)
- **Valid bar:** |C − O| > max(H − max(O, C), min(O, C) − L). Bearish: C < O.
- **Engulf (long):** C ≥ previous bar's H + thr.
- **VAH break (long):** C ≥ prevVAH + thr.
- **Volume condition:**
  - reducing = V < previous bar's V;
  - low-effort = V < volMA at the previous bar (the MA must not include the trigger bar itself);
  - the variant decides which of these must hold (§4).
- `thr` = the threshold in price (ticks × 0.1, or k × ATR at the trigger bar).

### 5.6 MS low and MS high
- **BOS:** a TF close above the most recent known swing high that hasn't already been broken. The pullback leg for that
  BOS = the bars from the broken swing high's bar to the BOS bar.
- **MS low** (fixed when the pullback first touches prev VAH, see §5.7):
  1. Take the most recent BOS before the touch.
  2. Walk back from that BOS bar through its pullback leg to find the closest valid bearish bar.
  3. If there is one, MS low = the lowest low of that leg.
  4. If there is none, go to the previous BOS's leg, at most 3 legs back.
  5. If all 3 fail, there is no MS low and no setup.
- **MS high** = the highest high from the last BOS bar to the touch bar, inclusive.

### 5.7 Setup state machine (one per direction, per session)
States: IDLE → ABOVE → ACTIVE → (TRADED) → ENDED.
1. **IDLE → ABOVE:** the trend filter passes for this session (completed-sessions variant: checked at the session
   open), and some TF bar in this session has H > prevVAH. With the developing variant, the filter is instead checked
   at the trigger.
2. **ABOVE → ACTIVE:** a later bar has L ≤ prevVAH (the touch). At that bar, fix MS low and MS high (§5.6). With no MS
   low, go back to IDLE (the setups-many variant may re-arm; setups-one ends the session).
3. **ACTIVE → cancelled:** a bar's L < MS low (or, in the close variant, C < MS low) → ENDED.
4. **First trade (in ACTIVE):** on a bar close where all of the following hold:
   - VAH break, engulf and the volume condition (§5.5);
   - the trend filter passes;
   - the news filter allows it;
   - MS low is intact (the trigger bar itself must not break it);
   - the bar closes before 16:45 ET (no entry in the last 15 minutes of the session);
   - the R:R is acceptable (§5.8).
   Then enter, and the state becomes TRADED.
5. **Re-entries (TRADED, only when flat):** any later bar with engulf + volume (+ VAH margin in that variant), the same
   other checks, and R:R acceptable.
6. **ENDED:** when any of these happen:
   - price reaches the MS high (any bar with H ≥ MS high, including while in a trade);
   - MS low is broken;
   - the session ends.
   With setups-one, nothing more happens this session. With setups-many, return to IDLE (needs a fresh rise above prev
   VAH and a new touch).
7. If a first-trade trigger is rejected only by the R:R floor, the setup stays ACTIVE.

### 5.8 Entry, stop, target, exits
- **Entry** = trigger bar's C + 1 tick (slippage).
- **Stop** = trigger bar's L − buffer.
- **Risk** = entry − stop. Skip the trigger if risk < 2 ticks.
- **Target** = entry + R_target × risk.
- **Ceiling on:**
  - if MS high ≤ entry, there is no trade and the setup ends;
  - if the target is above MS high, target = MS high;
  - if (target − entry) / risk < min_rr, skip the trigger.
- **Exit simulation on the 1m bars after the trigger bar's close:**
  - stop if L ≤ stop: fill at stop − 1 tick, or at O − 1 tick if O < stop (a gap);
  - target if H ≥ target + 1 tick: fill at target;
  - if both happen in the same 1m bar, assume the **stop** happened first;
  - with the ceiling off, a separate MS-high touch ends the *setup* but does not close the trade.
- **Session end (flatten variant):** exit at the last 1m bar's close of the session − 1 tick.
- **Hold variant:** the trade continues into the next session and is still managed by its stop and target. The setup
  still ends at session end.
- **Recorded per trade:** entry/exit time and price, R multiple = (exit − entry) / risk before costs, $ per contract
  after costs, score inputs, and exit reason.

### 5.9 Signal generation vs sizing
Signal generation (§5.1–5.8) depends only on rule parameters and gives a **strategy-level trade list**: one position at
a time, every trigger traded at 1 unit. Sizing (§5.10–5.11) is an overlay on that list, so sizing parameters never
need a fresh signal pass. A sized-to-zero trade still counts as an outcome, because its shadow result feeds the streak
and Kelly statistics, exactly as he paper-tracks skipped trades.

### 5.10 Meta-label score 0–5 (strategy file §7)
- **Q1:** the ATR bucket at the trigger = the in-sample best bucket (highest mean R in that config's in-sample trade
  list; ignore buckets with fewer than 20 trades).
- **Q2:** the streak point (GB-Q25).
- **Q3:** the freshness bucket = the in-sample best bucket.
- **Q4:** reducing volume is true on the trigger bar.
- **Q5:** the session bucket = the in-sample best bucket.
- "In-sample" = whatever period the search is fitting: the full data in §6, H1 in §7. The learned best buckets are then
  **frozen** for any out-of-sample run.

### 5.11 Sizing (his model) and equity
- **Kelly f\*** = W − (1 − W) / R, where W and R (avg win R / avg loss R) come from the **previous 100 strategy-level
  outcomes** only.
  - Warm-up: with fewer than 30 previous outcomes, risk 0.25%.
  - f\* ≤ 0 → risk 0 (skip; still shadow-tracked).
- **Risk % by score:** 5 → ½f\*, 4 → ¼f\*, 3 → ⅕f\*, 2 → ⅙f\*, 1 → ⅛f\*, 0 → 0. Scores below `min_score` → 0. The
  streak skip → 0. Cap at `kelly_cap` (2% or 1% of equity).
- **Contracts** = floor((equity × risk%) / (risk_points × $100 + $4.60 + $20) / 0.1) × 0.1. Below 0.1 → skip
  (counted as "too small").
- **Equity** compounds trade by trade, starting at $100,000. Net $ PnL = final equity − 100,000.
- **Flat 1-contract** $ PnL is reported next to it for reference.

### 5.12 Metrics (every run)
For each run, record:
- trades taken (sized > 0) and signals;
- win rate and expectancy in R per strategy-level signal;
- net $ (Kelly), % return, max drawdown % (Kelly equity), and net $ (flat 1 contract);
- profit factor;
- Sharpe: daily returns of the Kelly equity, √252, with zero-trade days included;
- max consecutive losses, average trades per month, % of trades per exit reason, autocorrelation strength at the end.

### 5.13 Implementation notes
- **Code:** `analysis/gianluca_backtest/`, a package of plain modules.
  - Suggested split: `timeutil, data, bars, profile, indicators, signals, sizing, metrics, search, runner, report`.
  - Tests in `analysis/gianluca_backtest/tests/`, using synthetic data, standard-library `unittest`.
- **Standard library + `array` for speed.**
  - Load the CSV once and cache the parsed arrays plus all precomputed features (bars, profiles, ATR pct, BOCPD,
    swings) with `pickle` in `reports/gianluca_backtest/cache/`. The cache is keyed by input file size + mtime + code
    version.
  - One run then needs only the signal loop and the 1m exit walk.
- **Parallelism:** `multiprocessing.Pool(workers)`. Each worker loads the cache once.
- **Determinism:** the seed for every random sample is written to the results, and config hash = sha1 of the sorted
  params JSON.

## 6. Search plan (overfit on the full data)

Stages; each row is one compute phase or part of one:

| Stage | What | Runs |
|---|---|---|
| A — coarse | Full grid: TF (4) × threshold (11) × volume condition (5) × target R (5) × fractal N (3). Other parameters at baseline. | 3,300 |
| B — wide | For each of the 20 best distinct A configs, 150 seeded random samples of the other parameters (VA%, MA, trend test, trend timing, ceiling, min_rr, re-entry VAH margin, session end, stop buffer, news, MS-low-break, setups/session, direction, min score, streak rules, kelly cap), plus a one-at-a-time sweep of each of those around the single best A config | ~3,100 |
| C — gold variants | P4 (Claude) reads A+B and may add ≤ 5 new rule variants as new parameters, with the baseline value = the current behaviour; sweep them on the 20 best so far | ≤ 600 |
| D — polish | Coordinate descent from the best config: try every neighbouring value of every parameter, keep improvements, repeat until none (cap 300 runs) | ≤ 300 |

- **Eligibility:** ≥ 150 trades. Report the best **A\***, plus the top 20 table.
- Before relying on A\*, also check:
  - its trades by hand: P6 prints 10 trades with every number behind them;
  - its 10-chunk stability (§9.4 of the strategy file);
  - a Monte Carlo of 10,000 shuffles of its R sequence, giving the % of shuffles with negative final equity and the
    5/50/95% percentiles of max drawdown.

## 7. In-sample / out-of-sample (the user's procedure)

- **Halves:** split the eligible sessions 50/50 by count. H1 = the first half, H2 = the second; the boundary is about
  2023-08.
  - Indicators run continuously across the boundary, since they are causal.
  - Equity restarts at $100,000 in each half.
  - The rolling Kelly/streak history in H2 may use H1 outcomes, since those are in the past.
  - The learned score buckets stay frozen from whichever period was fitted.

1. **A\* on H1 and on H2** (2 runs), with A\*'s buckets learned on the full data. This shows how much the full-data
   overfit relies on each half.
2. **Overfit on H1 only:**
   - Re-run stages A, B and D automatically on H1, with the same parameter space plus P4's variants and the same rules
     for picking the top 20.
   - No Claude judgment here, so H2 can't leak in through choices.
   - Eligibility ≥ 75 trades. The best is **B\***.
3. **On H2:**
   - Run B\* and the top 50 distinct H1 configs on H2, with buckets learned on H1 and frozen.
   - Report the **median** H2 net $, expectancy, Sharpe and max drawdown of the 50, B\*'s own H2 result, and the share of
     the 50 that are profitable on H2.
4. **Degradation:**
   - Compare H2 with H1 for B\* and for the median, on expectancy and Sharpe.
   - Classify by his thresholds (strategy file §9.3): ~20–25% normal, ≥ 30% warning, ≥ 50% not solid.
5. **Report the number of configs tested** (overfitting / PBO note), and say plainly that the parameter *space* was
   designed after seeing the full data, so the H1→H2 test is not a clean blind test.

## 8. Files

| File | Tracked? | What |
|---|---|---|
| `docs/dynamic/gianlucaBruniBacktest_plan_v1.md` | yes | This plan. Do not change the rules in it during the run; record deviations in the progress file. |
| `docs/dynamic/gianlucaBruniBacktest_progress.md` | yes | Live log: phase checklist, every decision made without the user and why, timings, problems. Created in P0. |
| `docs/dynamic/gianlucaBruniBacktest_results_v1.md` | yes | Final report (P8), layout in §9 P8 |
| `analysis/gianluca_backtest/**` | yes | Code + tests |
| `reports/gianluca_backtest/state.json` | no (gitignored) | Machine state, §10.2 |
| `reports/gianluca_backtest/config.json` | no | `{"workers": N}` written in P2 |
| `reports/gianluca_backtest/cache/` | no | Pickled features |
| `reports/gianluca_backtest/runs/<stage>.jsonl` | no | One line per run: hash, params, seed, metrics. Append-only; a resumed runner skips hashes already present. |
| `reports/gianluca_backtest/trades/<hash>.csv` | no | Trade lists for A\*, B\*, the top 20 and the H2 50 |
| `reports/gianluca_backtest/supervisor.log`, `runner_logs/` | no | Supervisor and runner output |

## 9. Phases

Each phase has a kind (claude or compute) and done-criteria. Claude phases run back to back in one VM chat (§10.4);
compute phases run without Claude. Keep the progress file's checklist current enough that a cut-off chat can resume.

| Phase | Kind | Work | Done when (then set state to the next phase) |
|---|---|---|---|
| **P0_SETUP** | claude | Check every input in §2 exists (CSV row count, footprint files). Record python version, core count, free disk and RAM in the progress file. Create the progress file from §9's phase list as a checklist, and `reports/gianluca_backtest/state.json` at P0_SETUP. Record the current git branch (the user switched to `VM-gianluca-backtest` in §10.5; if not, note it and carry on). Run `python ops/test_gb_backtest_supervisor.py`. | All checks recorded; a missing input → write it in the progress file, set `kind: "stuck"` with a note |
| **P1A_BUILD_CORE** | claude | timeutil, data loading + cache, sessions, bars, profile + VA, indicators (MA, ATR pct, BOCPD, swings), with tests | Tests pass; one cache build timed and recorded |
| **P1B_BUILD_SIGNALS** | claude | Building blocks, MS low/high, state machine, entry/exit on 1m, costs, trade list, with tests (hand-made bar sequences for: valid/invalid candle, engulf margin, VAH break margin, MS-low cancel, ceiling cap + min R:R, re-entry only when flat, stop-first rule, gap stop, session flatten, short mirror) | Tests pass |
| **P1C_BUILD_SIZING_RUNNER** | claude | Score, Kelly overlay, equity, metrics; `search.py` stages A/B/D + H1/H2 logic exactly as §6–7; `runner.py --resume` that reads state.json, runs the current compute phase resumably, writes runs/*.jsonl, and **sets state.json to the next phase when complete** (§10.2 order); report helpers | Tests pass; `runner.py --resume` on a 20-config dry stage works and resumes after being killed mid-way |
| **P2_VALIDATE** | claude | (1) §5.3 profile check vs footprint. (2) Baseline config (all §4 baselines) on the full data: metrics in the progress file. (3) Print 10 baseline trades with every input (prev VA, MS low/high, bar OHLCV, volume MA, thr, entry/stop/target, exit path) and check each by hand against the rules; fix bugs. (4) Time 20 runs → set `workers` and estimate stage hours. If one run takes > 5 s, profile and speed up (or install numpy). | Baseline + profile check + hand check recorded; config.json written |
| **P3_SEARCH_FULL_A** | compute | Stage A | runner advances → P3_SEARCH_FULL_B |
| **P3_SEARCH_FULL_B** | compute | Stage B | runner advances → P4_REVIEW_GOLD |
| **P4_REVIEW_GOLD** | claude | Read A+B: which parameters matter, per-bucket stats (regime, freshness, hour, weekday, direction, exit reason) for the top configs. Design ≤ 5 gold-specific variants (e.g. an hour filter, a different stop anchor, a VAL-based variant), each a new parameter whose baseline value = the current behaviour; implement + test them; define stage C in `search.py`. | Variants written in the progress file with reasons; state → P5_SEARCH_FULL_C |
| **P5_SEARCH_FULL_C** | compute | Stage C | → P5_SEARCH_FULL_D |
| **P5_SEARCH_FULL_D** | compute | Stage D | → P6_PICK_BEST |
| **P6_PICK_BEST** | claude | Choose A\* by §3's objective; hand-check 10 of its trades; chunk test + Monte Carlo; write the full-data section of the results doc | Section written; state → P7_SPLIT |
| **P7_SPLIT** | compute | §7 steps 1–3, all automatic (runner sub-steps: `A_on_halves`, `H1_A`, `H1_B`, `H1_D`, `H2_top50`) | → P8_REPORT |
| **P8_REPORT** | claude | Write `gianlucaBruniBacktest_results_v1.md`: (1) summary in plain words; (2) what was tested and costs; (3) baseline result (his rules, our guesses); (4) full-data overfit A\* — params, metrics, equity curve as a table by year, chunk test, Monte Carlo, top 20; (5) which rules got changed for gold and the evidence; (6) halves: A\* on H1/H2, B\*, median of 50 on H2, degradation verdict; (7) comparison with his claimed stats; (8) caveats (§12), number of configs tested; (9) which GB-Q guesses mattered most (parameter sensitivity) — useful for the user's review. | Doc written; state → `{"phase":"DONE","kind":"done"}` |

## 10. Running it on the VM

### 10.1 Two kinds of work
- **Compute phases** (grid runs, most of the wall-clock time) are **plain Python**. `ops/gb_backtest_supervisor.py`
  starts them and restarts them if they crash. They use no Claude, so a usage limit can't stop them.
- **Claude phases** (P0–P2, P4, P6, P8) run in an **interactive Claude Code chat on the VM**. Nothing restarts Claude
  automatically. When a chat stops on a usage limit, **the user types `continue` in the same chat** once the limit
  resets. A brand-new chat picks up the same way (§10.4).

### 10.2 Machinery
- **`ops/gb_backtest_supervisor.py --loop`**, started by the scheduled task `FLOW-GB-Backtest`
  (`ops/register_gb_backtest_task.ps1`: at logon + re-tried every 30 min, single instance, no time limit). Every 10 min:
  - **compute phase:** if the runner isn't running, start `python analysis/gianluca_backtest/runner.py --resume` at
    BELOW_NORMAL priority, with output in `reports/gianluca_backtest/runner_logs/`. A runner exit that doesn't advance
    the phase counts as an attempt; 4 in a row → **STUCK**.
  - **claude phase:** start nothing; send one notice: "phase X needs Claude — say continue".
  - **done / stuck:** send one notice.
  - It never starts or stops Claude.
- **Notifications:** one line appended to `<FLOW_HOME>/logs/alerts.log` (`[INFO] GB backtest: …`). `ops/watchdog.py`
  already forwards new lines there to Telegram.
- **`state.json`:** `{"phase": "...", "kind": "claude"|"compute"|"done"|"stuck", "attempts": n, "note": "..."}`.
  - P0 creates it.
  - **Only the work itself advances `phase`:** the Claude chat when a Claude phase's done-criteria hold, the runner
    when a compute phase is complete. Write atomically (`state.json.tmp`, then `os.replace`) and keep the other keys.
  - Order and kinds:
    P0_SETUP → P1A_BUILD_CORE → P1B_BUILD_SIGNALS → P1C_BUILD_SIZING_RUNNER → P2_VALIDATE → P3_SEARCH_FULL_A (compute)
    → P3_SEARCH_FULL_B (compute) → P4_REVIEW_GOLD → P5_SEARCH_FULL_C (compute) → P5_SEARCH_FULL_D (compute) →
    P6_PICK_BEST → P7_SPLIT (compute) → P8_REPORT → DONE (kind "done").
  - When advancing, set `kind` for the new phase and reset `attempts` to 0.

### 10.3 Runner contract (built in P1C)
- `runner.py --resume`:
  1. Read state.json.
  2. Run the current compute phase's configs that aren't yet in `runs/<stage>.jsonl`, appending and flushing one line
     per finished run.
  3. When all are done, advance state.json.
  4. Exit 0.
- Killing it at any moment loses at most the runs in flight. On an exception: print the traceback, exit non-zero, and
  the supervisor retries.
- Stage B/C/D configs are generated deterministically from earlier results + the seed, and written to
  `runs/<stage>.configs.json` first. A restart reuses that file.

### 10.4 Resume protocol (the user says "continue", or a new chat starts)
1. Read this section, then `docs/dynamic/gianlucaBruniBacktest_progress.md`, then `reports/gianluca_backtest/state.json`
   (if it doesn't exist yet, start at P0). Read other parts of this plan only as the current phase needs them.
2. Check files on disk before trusting the progress file, since a chat can be cut off mid-step.
3. **Claude phase:** do it. When its done-criteria (§9) are met, advance state.json and go straight on to the next
   Claude phase in the same chat.
4. **Compute phase:**
   - check the supervisor is running (`python ops/gb_backtest_supervisor.py --status`; if no runner lock appears within
     a minute, run `--once`);
   - give the user one line ("P3 running, ~N h; say continue when Telegram says it needs Claude");
   - **end the turn. Do not poll or sleep**, because polling burns tokens.
5. If blocked by something only the user can fix, put it at the top of the progress file under **BLOCKED**, set `kind`
   to `"stuck"` with a `note`, and tell the user.
6. Ask the user only when something can't be decided from this plan; otherwise decide and log the decision.
7. Never weaken a hard rule (§1) to get unblocked.

### 10.5 What the user does on the VM (once)
1. Pull the branch with these files (the data is in it, see §2), then `git checkout -b VM-gianluca-backtest`.
2. Run `python ops\test_gb_backtest_supervisor.py`; all tests should pass.
3. `powershell -ExecutionPolicy Bypass -File ops\register_gb_backtest_task.ps1`, then `Start-ScheduledTask
   FLOW-GB-Backtest`.
4. Open Claude Code in the repo and say: **"Run the Gianluca backtest: follow
   docs/dynamic/gianlucaBruniBacktest_plan_v1.md §10.4."** After that, `continue` is enough.

### 10.6 Checking on it
- Telegram notices.
- On the VM: `python ops\gb_backtest_supervisor.py --status`, the progress file, and `runs/*.jsonl` line counts.

### 10.7 Stopping / fixing
- **Stop:** `Unregister-ScheduledTask FLOW-GB-Backtest`, end the `python …gb_backtest_supervisor.py` process, and end
  the process in `reports/gianluca_backtest/runner.lock.json`.
- **After STUCK:** fix the cause (state.json `note`, the progress file's BLOCKED section), then run
  `python ops\gb_backtest_supervisor.py --reset-stuck`.

## 11. Estimates

| Item | Runs | Time |
|---|---|---|
| Full-data search (A 3,300 + B ~3,100 + C ≤ 600 + D ≤ 300) | ~7,300 | |
| A\* on halves | 2 | |
| H1 search (A + B + D on half the data) | ~6,700 | Each run about half the full-data time |
| H2 runs | ~51 | |
| **Total** | **~14,000 backtest runs** | |
| Run cost | | Pure Python: roughly 0.5–3 s per full-data run (5m TF slowest) once features are cached; measured in P2 |
| Compute | | About 4–10 CPU-hours. With 2 BELOW_NORMAL workers, about **2–6 hours** of wall clock. |
| Claude phases | | P0–P2 is the big one (build + check): about 2–4 hours of session time, likely spread over 1–2 usage windows. P4, P6 and P8 take about 30–60 min each. |
| **Wall clock end to end** | | Likely **10–20 hours**, up to ~1.5 days if usage limits bite during the build phases. |

## 12. Known weaknesses (say these in the results doc)
- **The volume profile is approximated from 1m bars** (GB-Q26). His value areas come from TradingView's SVP HD. P2
  measures the error on only 2 sessions.
- **Many rule details are our guesses** (§4). A good or bad result is a result for *these* guesses, not proof about his
  method.
- **NQ vs gold:** his stats are on NQ. Nothing here says the strategy works or fails on NQ.
- **Overfitting:** ~14,000 configs. The H1→H2 test is the only honest number, and it is weakened because the parameter
  space and the gold variants were designed after seeing the full data.
- **The fill model is simple.** Bar-close market entries, 1m exit resolution with stop-first ties, and no queue model
  for target limits beyond the 1-tick-through rule.
- **Compounding Kelly PnL is path-dependent.** Net $ is dominated by late trades at large equity. Read expectancy and
  Sharpe next to it.
- **News filter:** a time-window proxy, not a real calendar.
