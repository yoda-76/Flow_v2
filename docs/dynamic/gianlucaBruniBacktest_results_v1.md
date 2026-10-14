# Gianluca Bruni strategy — curiosity backtest on gold: results v1

**Written:** 2026-10-13 on the VM. Plan: `gianlucaBruniBacktest_plan_v1.md`. Decisions and deviations:
`gianlucaBruniBacktest_progress.md`. Code: `analysis/gianluca_backtest/`.
This is a curiosity backtest, **not a FLOW decision**.

## 1. Summary
- **His rules, read our way, lose on gold.** Baseline: −0.20 R per signal over 6 years, −$17.6k at 1 contract.
  His own Kelly sizing sees the losses and stops betting, so it ends at −$1.7k.
- **Fitting the rules to the whole of gold's data looks great and means little.** The best of 6,933 configs makes
  +$650k on $100k (Sharpe 1.51, +0.28 R per signal). But it is **long only on a market that went from $1,800 to
  $4,200**. It drops his volume condition, trend filter and MS-high ceiling, and **$576k of the $650k comes from
  2024–25**. On the first half of the data (2020–23) it earns only +0.10 R and loses $9k at 1 contract.
- **The honest test fails.** The search was re-run on the first half only, then its top 50 were tested on the second
  half:
  - median expectancy fell from +0.49 R to **−0.05 R**;
  - Sharpe fell from 1.43 to **−0.62**;
  - only **3 of 50** made money with his sizing;
  - the single best first-half config went from +0.60 R to −0.01 R.
- **Verdict:** degradation is over 100%, far past his own "≥ 50% = not solid" line. Each fit learned the trend of the
  period it saw: **short** for 2020–23, **long** for 2024–26. Neither found a lasting edge in the setup itself.
- No version of his volume condition improved results on gold (§9).

## 2. What was tested, and costs
- **Data:** @GC continuous 1m OHLCV, 2020-07-12 → 2026-10-02, 2,193,481 bars, 1,608 Globex sessions (18:00–17:00 ET),
  7 intra-session gaps > 30 min, no short sessions.
- **Volume profile:** built from 1m bars (each bar's volume spread evenly over its range), TradingView-style value
  area. Checked against real footprint data (FLOW's recorder, every trade by price) on the two sessions where the
  footprint is complete and overlaps the CSV. In both, the footprint and 1m volume agree within 2%.
  - 2026-09-29, 11.8 h: POC +4, VAH −8, VAL −6 ticks, against a VA about 180 ticks wide.
  - 2026-10-02, 8.8 h: POC −8, VAH −2, VAL −4 ticks, against a VA 682 ticks wide.

  A third footprint file was incomplete and couldn't be used. Errors are a few ticks: far smaller than the thresholds
  the search settled on (0.2–0.3 × ATR ≈ 25–35 ticks in 2026), but large enough to flip an individual touch or a 0–2
  tick threshold. Evidence is thin: 2 partial sessions, both at 2026 prices.
- **Rules:** his strategy as written in `gianlucaBruniStrategy.md`, with every open question (GB-Q) answered by the
  plan's best guess (plan §4) and then swept. Shorts are the exact mirror of longs.
- **Costs (our reading of the user's "$4.60 commission and 1 tick slippage"):**
  - **$4.60 per round trip per contract**;
  - 1 tick slippage on every market fill (entry, stop, session-end flatten);
  - targets are limit orders that fill only after price trades 1 tick through.
  - Both readings ("per round trip", "per market fill") are this backtest's interpretation.
- **Sizing:** his Kelly-by-score table on % of equity, compounding from $100,000, in 0.1-contract steps:
  - Kelly f\* from the previous 100 outcomes;
  - score 5 → ½ f\*, down to score 1 → ⅛ f\*, score 0 → skip;
  - risk capped at 2% (or 1%);
  - streak skip rule.

  The flat 1-contract result is shown alongside for reference.
- **Objective:** maximise Kelly net $ with at least 150 taken trades (75 on a half); ties broken by Sharpe.
- **Configs tested: 13,424 in total**:
  - 6,933 on the full data (grid A 3,300, random B 3,023, gold variants C 460, coordinate descent D 150);
  - 6,439 on the first half (A 3,300, B 3,030, D 109);
  - 50 on the second half;
  - A\* on each half (2).
- **News filter:** an 08:30 ET window only. FOMC days were left out because no dated list could be written with
  confidence.

## 3. Baseline: his rules, our best guesses
@GC 1m, 2020-07-13 → 2026-10-02 (1,608 sessions), 15m bars, threshold 0, either volume condition, 2R, fractal N 3,
VA 70%, both trend tests, ceiling on with 1R floor, one setup per session, flatten at session end, both directions,
his Kelly-by-score sizing.

| Signals | Taken | Win % | Exp R / signal | Kelly net $ | Max DD % | Flat 1-lot $ | PF | Sharpe |
|---|---|---|---|---|---|---|---|---|
| 425 | 26 | 29.6 | −0.203 | −1,666 | 2.0 | −17,595 | 0.61 | −0.43 |

His rules, read our way, lose on gold. His rolling Kelly sees the losses and sizes 359 of the 425 signals to zero,
so the Kelly equity barely moves.

## 4. Full-data overfit: A\*
6,933 configs tested on the full data (A 3,300, B 3,023, C 460, D 150); 1,829 eligible (≥ 150 taken trades).

**A\*** = `ce0620b353815e2a`. Differences from the baseline: 30m bars, threshold 0.3 × ATR(14), **no volume
condition**, 3R target, fractal N 5, EMA volume MA, **no trend filter**, **MS-high ceiling off** (min R:R 0.5,
unused with the ceiling off), re-entries also need the VAH margin, **hold overnight**, MS low broken only by a close,
setups re-arm within a session, **long only**, and the P4 gold variant **skip LOW-ATR triggers** (ATR percentile ≤ 25).
Sizing parameters stay at baseline: min score 0, streak rules on, Kelly cap 2%. Learned score buckets: ATR MEDIUM,
FRESH, ASIA.

| Signals | Taken | Win % | Exp R / signal | Kelly net $ | Return | Max DD % | Flat 1-lot $ | PF | Sharpe | Max consec. losses | Trades / month |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 547 | 455 | 32.4 | +0.280 | +649,904 | +650% | 15.1 | +120,174 | 1.58 | 1.51 | 13 | 5.9 |

Exits: 67.6% stop, 32.4% target (holding means no session-end exits). Sized to zero: 49 by Kelly ≤ 0, 27 by score 0,
9 by the streak rule, 7 too small.

**Equity by year** (Kelly, compounding from $100,000):

| Year | Signals | Taken | Σ R | P&L $ | Equity at year end |
|---|---|---|---|---|---|
| 2020 (from Jul) | 49 | 42 | +14.4 | +10,712 | 110,712 |
| 2021 | 76 | 74 | +6.7 | −4,601 | 106,110 |
| 2022 | 96 | 69 | +2.3 | −711 | 105,399 |
| 2023 | 74 | 32 | +4.5 | −706 | 104,693 |
| 2024 | 99 | 94 | +59.3 | +111,177 | 215,870 |
| 2025 | 98 | 91 | +61.3 | +464,830 | 680,700 |
| 2026 (to Oct 2) | 55 | 53 | +4.8 | +69,205 | 749,904 |

**$576k of the $650k comes from 2024–25**, the steepest part of gold's run from about $1,800 to $4,200. 2021–23 earn
+13.5 R in total but lose a little money: the rolling Kelly sizes down after losses and up after wins, so the order
of trades matters.

**10-chunk stability** (signals in time order, 10 equal parts; n, win %, Σ R, P&L $):
(54, 29.6, +9.3, +3,476) · (55, 30.9, +12.2, +4,984) · (55, 30.9, +12.0, +6,162) · (54, 16.7, **−19.0**, −9,222) ·
(55, 30.9, +12.1, +118) · (55, 34.5, +20.0, +10,867) · (54, 35.2, +20.8, +31,070) · (55, 41.8, +36.6, +162,328) ·
(55, 45.5, +44.6, +370,918) · (55, 27.3, +4.8, +69,205).
9 of 10 chunks are positive in R. One deep loser (chunk 4, about 2022). The $ total is dominated by chunks 8–9.

**Monte Carlo** (10,000 shuffles of the strategy-level trade order, his sizing re-run on each, since rolling Kelly
and the streak rules depend on order): 0.0% of shuffles end below $100k. Final equity p5 / p50 / p95 = $368k / $526k /
$788k. Max drawdown p5 / p50 / p95 = 15.5% / 21.5% / 30.8%. The actual order (+$650k, 15.1% DD) is luckier than the
median shuffle on drawdown.

**Hand check:** 10 random taken A\* trades checked against every input (`p6_pick.py trades`): triggers, VAH margin on
re-entries, entry / stop / 3R target, fills 1 tick through, overnight holds and contract sizes are all correct.

**Top 20** (full data; columns: taken, Kelly net $, Sharpe, max DD %, exp R). All 20 share A\*'s core: 30m, threshold
0.3 × ATR, 3R, long only, no volume condition, no trend filter, ceiling off, hold, no-LOW-ATR filter. **Ranks 1–6 are
the same trade list** (their differences, such as EMA vs SMA or min R:R with the ceiling off, don't change any
trade), so the top 20 holds only about 10 genuinely different results.

| # | Hash | Taken | Net $ | Sharpe | DD % | Exp R | Differs from A\* by |
|---|---|---|---|---|---|---|---|
| 1–6 | ce0620b3 … | 455 | 649,904 | 1.51 | 15.1 | 0.280 | (no effective difference) |
| 7–8 | 934bf4c9 … | 461 | 633,979 | 1.50 | 16.8 | 0.280 | streak rules off |
| 9 | ce75c364 | 393 | 633,619 | 1.48 | 17.6 | 0.282 | 08:30 news filter |
| 10 | 454f444c | 463 | 626,871 | 1.48 | 16.5 | 0.274 | re-entry VAH margin off |
| 11 | d95cb817 | 395 | 618,344 | 1.47 | 17.5 | 0.282 | news + streak off |
| 12–16 | d4323f34 … | 469 | 606,078 | 1.45 | 16.5 | 0.274 | re-entry margin off + streak off |
| 17 | 6929e6f1 | 446 | 598,689 | 1.49 | 15.2 | 0.271 | stop buffer 1 tick |
| 18 | 51375ab1 | 388 | 591,147 | 1.54 | 13.0 | 0.260 | fractal N 3 |
| 19 | 322cf265 | 452 | 576,927 | 1.44 | 18.8 | 0.284 | stop buffer 3 + streak off |
| 20 | 6ad2e715 | 452 | 570,357 | 1.46 | 17.0 | 0.271 | stop buffer 1 + streak off |

**Reading A\* honestly:**
- It is long only, on an instrument that more than doubled over the test.
- It drops his volume condition, his trend filter and his MS-high ceiling.
- Its edge per signal (+0.28 R) is real across most of the 10 chunks, but its $ result is mostly the 2024–25 bull
  leg compounded by Kelly.
- The halves test (§6, P7) shows how much of this survives out of sample.

## 5. Rules changed for gold, and the evidence
P4 read stages A+B and added five gold variants. Each is a new parameter whose default reproduces his rules.
Evidence came from the then-best config's trades.

| Variant | Values | Why | Survived the search? |
|---|---|---|---|
| Skip LOW-ATR triggers | all / no_low | LOW ATR −0.16 R (n 235) vs MEDIUM +0.37 R | **Yes**: in A\* and all of the top 20 |
| Session filter | all / no NY / Asia only | NY −0.04 R vs Asia +0.11 R | Not in A\*; `no_ny` in the H1 best (B\*) |
| Fresh regimes only | all / fresh_only | FRESH +0.10 R vs MATURE/OLD −0.12 R | No |
| Wider stop | +0 / 0.25 / 0.5 × ATR | wider stop buffers kept winning in B | Not in A\*; 0.5 × ATR in B\* |
| 200-bar SMA trend filter | off / on | a principled stand-in for "long only" | No: the search preferred plain long only (full data) or short only (H1) |

The search's other big changes to his rules, from the sweeps of §4:
- 30m bars instead of 15m;
- a 3R target and the **MS-high ceiling off** (a 2R target capped at the MS high is his rule);
- a larger threshold (0.2–0.3 × ATR instead of 0 ticks);
- no volume condition;
- no trend filter;
- holding overnight;
- one direction only.

The last two are where the "fit" really lives (§6).

## 6. Halves: A\* on H1/H2, B\*, median of the top 50 on H2
H1 = sessions 1–803 (2020-07 → 2023-08), H2 = sessions 804–1607 (2023-08 → 2026-10). Equity restarts at $100,000 in
each half. Score buckets are frozen from the fitted period.

**A\* (fitted on the full data) on each half:**

| Half | Signals | Taken | Win % | Exp R | Kelly net $ | Max DD % | Flat 1-lot $ | PF | Sharpe |
|---|---|---|---|---|---|---|---|---|---|
| H1 | 268 | 210 | 28.0 | +0.103 | +5,517 | 14.3 | −9,223 | 1.05 | 0.21 |
| H2 | 279 | 245 | 36.6 | +0.450 | +607,379 | 13.2 | +129,397 | 1.64 | 2.20 |

A\*'s result is almost entirely H2. On H1 it is roughly break-even.

**Overfit on H1 only** (stages A, B, D re-run automatically on H1, with no Claude judgment): 6,439 runs, 1,023
eligible (≥ 75 taken). The best is **B\*** = `482ab011c89bfedd`: 30m, threshold 0.2 × ATR, 3R, ceiling off, hold,
**short only**, VA 60%, POC trend test at the trigger, stop buffer 3 ticks + 0.5 × ATR, no NY triggers, 08:30 news
filter, min score 2, streak rules off.

| B\* | Signals | Taken | Win % | Exp R | Kelly net $ | Max DD % | Flat 1-lot $ | PF | Sharpe |
|---|---|---|---|---|---|---|---|---|---|
| H1 (fitted) | 109 | 92 | 40.4 | +0.600 | +187,621 | 9.3 | +29,409 | 2.41 | 1.90 |
| H2 (unseen) | 120 | 83 | 25.0 | −0.010 | −16,367 | 24.0 | +53,338 | 0.78 | −0.39 |

**Top 50 distinct H1 configs on H2** (distinct by result: identical trade lists counted once):

| Median | H1 | H2 |
|---|---|---|
| Kelly net $ | +106,126 | **−19,198** |
| Exp R / signal | +0.494 | **−0.048** |
| Sharpe | 1.43 | **−0.62** |
| Max DD % | 10.8 | 23.5 |
| Taken trades | 92 | 69 |

- Profitable on H2 with his sizing: **3 / 50**.
- Positive expectancy on H2: 8 / 50.
- 47 of the 50 are short only; the 3 that trade both ways have a median H2 expectancy of +0.17 R.
- **Flat 1-lot $ on H2 is positive for 48 / 50** even though expectancy is negative. In H2, gold doubled, so the
  stops measured in dollars were wider. The few winners (3R in dollars) outweighed many small R-losses. This is a
  property of the price level, not of the setup.

**Degradation H1 → H2** (his §9.3 scale: ~20–25% normal, ≥ 30% warning, ≥ 50% not solid):

| | Expectancy | Sharpe | Verdict |
|---|---|---|---|
| B\* | −102% | −121% | **not solid** |
| Median of the top 50 | −110% | −143% | **not solid** |

**Caution:** the parameter *space* (including the five P4 variants) was designed after seeing the full data. So
H1 → H2 is not a clean blind test, and it still failed.

## 7. Comparison with his claimed stats
He claims (NQ, about 2 years, flat 0.5% risk; strategy file §10): 727 trades, +0.20 R expectancy, Sharpe 2.20, max
drawdown −13.1%, 41 / 10,000 Monte Carlo shuffles losing money.

| | His NQ claim | Our baseline on GC | A\* (full-data overfit) | Top 50 from H1, on H2 (median) |
|---|---|---|---|---|
| Expectancy | +0.20 R | −0.20 R | +0.28 R | −0.05 R |
| Sharpe | 2.20 | −0.43 | 1.51 | −0.62 |
| Max DD | 13.1% | 2.0% (barely trades) | 15.1% | 23.5% |
| Trades / day | ~1.4 | ~0.26 signals | ~0.34 signals | ~0.09 taken |

- Only the full-data overfit reaches his numbers, and it doesn't hold up out of sample.
- On gold, read our way, the setup fires far less often than his ~1.4 trades a day. That points to differences in
  our reading of his rules (GB-Q) as much as to the instrument.

## 8. Caveats
- **Our rules are our reading.** Every GB-Q guess (plan §4) could differ from what he trades, and the volume profile
  is a 1m approximation (a few ticks off where we could check it).
- **One instrument, one trend.** Gold rose from about $1,800 to $4,200 during the test, and both halves have a
  dominant direction. Any direction-only config is a bet on that period's trend.
- **Many configs were tested (13,424).** The full-data A\* was picked from 6,933, so its numbers are optimistic by
  construction (backtest overfitting / PBO).
- **The search space came after seeing the data**, including the P4 variants. The H1 → H2 test is not a clean blind
  test.
- **The trade floor counts taken trades, so it favours configs Kelly sizes up.** With his volume condition on, Kelly
  sized so many signals to zero that no such config reached 150 taken trades. Every eligible full-data config
  therefore has the volume condition off.
- **Costs are an interpretation:** $4.60 per round trip, 1 tick on market fills, targets need 1 tick through.
- **Sizing** grows to 10–15 GC contracts by 2025–26 on a $700k account. No liquidity or margin limits are modelled.
- **Not modelled:** FOMC days, roll days (continuous contract as given), and partial fills.
- The Monte Carlo shuffles the trade order and re-runs his sizing. It says nothing about whether the trades
  themselves repeat.

## 9. Which GB-Q guesses mattered most
These are medians over eligible A+B configs (Kelly net $, and % of configs profitable). Large gaps mean the
parameter changes results a lot. The volume condition comes from stage A by expectancy, since the trade floor
removed every config that used it.

| Question | Values (median net $, % profitable) | Matters? |
|---|---|---|
| GB-Q1/Q2 threshold | 0 ticks −$8.7k, 29% · 10 ticks +$5.8k, 60% · 0.2 × ATR +$19.8k, 69% | **A lot**: a margin well above 0 helps |
| Target R (gold addition) | 1R −$5.2k, 36% · 2.5R +$9.5k, 63% · 3R +$12.2k, 63% | **A lot** |
| MS-high ceiling (his §3.7) | on −$7.1k, 29% · off +$2.7k, 55% | **A lot**: his ceiling hurts on gold |
| Direction (gold addition) | long +$9.6k, 66% · both −$3.5k, 43% · short −$5.5k, 29% | **A lot**, but it's the trend (§6) |
| Timeframe | 30m −$0.1k, 50% · 15m −$5.3k, 36% · 10m −$11.5k, 22% | Yes |
| GB-Q29 setups per session | one +$1.9k, 54% · many −$5.0k, 39% | Yes (A\* uses many) |
| GB-Q4 value-area % | 80% +$2.8k, 55% · 70% −$0.6k, 49% · 60% −$7.7k, 33% | Yes |
| GB-Q7 fractal N | 2 +$2.1k, 54% · 3 −$0.7k, 48% · 5 −$4.4k, 40% | Some |
| GB-Q27 trend timing | completed +$0.4k, 51% · developing −$3.5k, 41% | Some |
| GB-Q14 stop buffer | 0 −$4.3k, 41% · 3 ticks −$0.1k, 50% | Some |
| Min score | 0 −$3.1k, 42% · 2 +$2.8k, 54% | Some |
| GB-Q11 trend test | both −$3.0k · POC −$1.1k · none −$2.9k | Little |
| GB-Q30 overnight | flatten −$2.8k · hold −$1.3k | Little |
| GB-Q17 re-entry VAH margin | off −$2.7k · on −$1.5k | Little |
| GB-Q5 volume MA | SMA −$3.2k · EMA −$1.4k | Little |
| GB-Q15 min R:R | 1.0 / 0.5 / 0: all about −$2.1k | None |
| GB-Q12 MS-low break | trade −$1.7k · close −$2.7k | None |
| GB-Q13 news | off −$2.2k · 08:30 −$2.0k | None |
| GB-Q25 streak rules | on −$2.7k · off −$1.7k | Little |
| Volume condition (GB-Q6/Q18) | stage A median exp R: either −0.15, reducing −0.11, low-effort −0.15, both −0.13, none −0.12 | **None**: no version helps |

**For the user's review of the video**, the guesses that change results most are:
- the threshold unit and size (GB-Q1/Q2);
- whether the 2R target is really capped at the MS high (his §3.7);
- the target multiple.

The volume condition, the news window, the MS-low-break reading and the minimum R:R barely matter on gold.
