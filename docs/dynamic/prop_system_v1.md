# Prop system v1 — S1 + S3 together on a Lucid account

Everything known (as of 2026-11-04) about deploying **S1 (Vault Break)** and **S3 (Overnight-bias ORB)** together on one Lucid prop
account. This is evidence, not a trading decision. Sources are the research logs
[vault_break.md](strategies/vault_break.md) (entries B-xx) and [overnight_bias_orb.md](strategies/overnight_bias_orb.md), the
Lucid rules reports in `docs/`, and the grid run planned in [prop_grid_plan_v1.md](prop_grid_plan_v1.md).

The grid numbers (§1, §7) come from the **full version** of the grid, run on 2026-11-04 on a second machine from the handoff
folder (`reports/lucid_grid_full/`, git-ignored). That run's own write-up and run log are kept beside it as
`prop_system_full_v1.md` and `simulation_run_report.md`. The 8-hour run on this machine stopped at 19 of 34 stage-1 cases
(the low-memory reaper) and was not resumed. The full run supersedes it, and the partial results agree with it to within a few
$/month.

## 1. Bottom line

**Recommended: LucidPro 50K, 6 MNQ per strategy in the eval and 2 MNQ once funded, always requesting the plan's maximum payout.**
Medians over 200 noise runs, 1,564 start days each:

| | Pro 50K 6/2 "max" |
|---|---|
| First payout | $2,000 requested ($1,800 received) |
| Days from buying the first eval to the first payout | 163 (worst 5% of runs: 172) |
| Paid within 6 / 12 months | 60% / 99% |
| Fees to the first payout, mean | $365 (eval $120.40, resets $120) |
| Net per month to the first payout | $253 |
| Never broke even by the data end | 0.0% |
| Start years paid within 12 months (base) | **100% in every year, 2019–2025** |
| Profit cut 25% / 50% (base) | $200 / $119 per month; 93% / 67% paid ≤ 12 months; 0% never break even |

**Why this one:**
- **It makes about twice what 25K makes per month** ($253 against $123 for Pro 25K 3/1) for about $160 more in fees.
- **It is as reliable as the safest combo.** Pro 25K 3/1 and Pro 50K 6/2 are the only candidates that paid 100% of starts within
  12 months in every start year.
- **It holds up under stress better than its neighbours.** Pro 50K 4/3 makes the same $253 per month, but pays only 89% within
  12 months, and with profits cut by half 15% of its starts never break even (6/2: 0%).
- **The higher-ranked combos are weaker where it matters:**
  - **Flex 150K 10/7 ($396 / month)** and **Flex 100K 7/5 ($323)** collapse for starts in 2025, the most recent year: only
    43% and 0% were paid within 12 months. That is also why about 14% of their starts never break even (§7.4). Flex pays at
    most 50% of account profit, so a $3,000 payout needs $6,000 of profit first.
  - **Pro 150K 10/7 ($340)** costs $912 in fees, and 10% of starts never break even. *(Pro 150K at 10/5 or 14/5 is steadier:
    see the safety screen below.)*
  - **Direct 150K at 5 MNQ ($296)** is steady in every year with 0% never breaking even, but costs $787 upfront and takes ~6
    months.
- **Always request the maximum.** With $500 requests the fees eat the payout: every 100K and 150K "$500" combo nets about $0
  or less per month.

**If the smallest outlay matters most:** Pro 25K 3/1 "max": $123 per month, $207 in fees, 162 days, 100% paid within 12 months
in every year and every stress case except the 50% profit cut (77%).

**Safety screen over all 782 combos** (added 2026-11-07).
- **Screen:** "max" requests with:
  - ≥ 95% paid within 12 months;
  - 0% never breaking even (noise medians);
  - at most 5% never breaking even with profits cut by 50%;
  - ≥ 88% paid within 12 months with profits cut by 25%.
- **Result:** only 8 combos pass, all of them Pro. No Flex or Direct combo passes.
- The table shows noise medians over the 50 stage-1 runs. Only 50K 6/2 and the 25K combos also have full depth (200 runs).

| Combo | First payout | Months to it | Paid ≤ 6 / 12 mo | Fees | Net / month | Net / month at −50% profit |
|---|---|---|---|---|---|---|
| Pro 150K 14/5 | $3,000 | **4.8** | 67% / 99% | $941 | **$329** | $73 |
| Pro 150K 13/5 | $3,000 | 5.0 | 65% / 98% | $913 | $322 | $88 |
| Pro 150K 12/5 | $3,000 | 5.1 | 61% / 97% | $907 | $310 | $95 |
| **Pro 100K 9/3** (middle ground) | $2,500 | 5.6 | 57% / 99% | $662 | $270 | $107 |
| Pro 100K 8/3 | $2,500 | 5.7 | 54% / 98% | $622 | $267 | $97 |
| **Pro 50K 6/2** (recommended) | $2,000 | 5.3 | 60% / 100% | **$367** | $255 | **$119** |
| Pro 25K 3/1 | $1,000 | 5.3 | 62% / 100% | $207 | $124 | $61 |
| Pro 25K 2/1 | $1,000 | 5.7 | 55% / 99% | $190 | $121 | $46 |

- **Speed barely differs among the safe combos** (4.8–5.7 months). The real choice is payout size against money at risk.
- **What each payout returns on its fees** (received ÷ fees):
  - 50K 6/2: about 4.9×;
  - 100K 9/3: about 3.4×;
  - 150K 14/5: about 2.9×.
- **Pro 150K 10/5** narrowly misses the screen (−50% profit: 10% never break even), but otherwise makes $331 / month in 5.3
  months for $802 in fees, and paid 100% of starts within 12 months in every start year (base).
- **Next:** run Pro 100K 9/3 and Pro 150K 14/5 and 10/5 on noise runs 51–200 so they are as deeply tested as 50K 6/2.

**How sure:** the edge passed R-1 (§3), the grid reproduces the earlier Pro 25K results journey by journey, and Pro 50K 6/2 stays
profitable with half the backtest profit. It is still a backtest on 7 years of NQ, so the times and fees are estimates and the
choice is the user's.

## 2. The system

| | S1 — Vault Break | S3 — Overnight-bias ORB |
|---|---|---|
| Rule set | Fixed base "2C": session open at the 17:00 CT Globex open | Script defaults |
| Code | `analysis/gb_nq_backtest/vault_break.py --open-ref globex1700` | `analysis/gb_nq_backtest/overnight_orb.py` |
| Market / data | NQ 1-minute bars, 2019-09 → 2026-10-14 | same |
| Style | intraday, long only, holds up to ~5.5 h | intraday ORB |

- **Size unit:** "1 unit" = 1 MNQ in each strategy ($2/point; $1 round trip and 1 tick of slippage are included in every trade).
  Both strategies always trade the same size. "e/f" = size per strategy in the eval / once funded.
- **At 1 MNQ each** (B-09):
  - about **$505/month** combined;
  - 63 of 86 months positive; no month worse than −$767; worst day −$561;
  - every calendar year positive.
- **They move independently:** the monthly correlation between S1 and S3 is **0.19**.
- **S2 (VWAP pullback + ADX) is left out** (B-10, B-11): at 1 MNQ it adds only ~$1.9k in 7 years, most of it in 2024–25. It
  moves with S3 (correlation 0.49) and has the only month beyond the 25K loss allowance. Adding it lowers the pass rate
  (73% → 68%).

## 3. Is the edge real?

**Each strategy alone** (B-12 for S1; overnight_bias_orb.md B-08 for S3):
- **S1** makes $122 per NQ trade (t 5.2), positive every year.
  - All 45 nearby settings are positive.
  - Choosing the setting walk-forward does as well as the fixed one.
  - Random entries make only $18 a trade.
  - It holds at 2–3 ticks of slippage and away from contract rolls.
  - It is weaker on MES (PF 1.14).
- **S3** passes the same battery of tests.

**Robustness round R-1** (B-15; tests from the "how to backtest" video):

| Test | S1 | S3 |
|---|---|---|
| Random direction, same entry minute (10,000 runs) | 0 beat the real result (z 7.3) | 0 beat (z 4.4) |
| Random minute on random days (10,000) | 0 beat (z 4.5) | 0 beat (z 6.6) |
| After counting every variant tried (Bonferroni) | p 0.008 | p 0.005 |
| Noise: entry 0–1 min late + 0–2 extra ticks on every fill (1,000 runs) | all profitable; median keeps 86% | all profitable; median keeps 94% |
| Entry shifted +1 … +5 min | gradual decline, no cliff | same |
| Without the best 5% of trades | keeps 67% | keeps **18%** (fat tails) |

**Verdict:** the edge is real, and every criterion fixed before the run was met. S3 leans on its best trades. With half the
backtest edge, the eval pass rate falls below 50%.

**Downtrends** (B-14): S1 still makes money but about half as much. Fast crashes lost (2020, the 2023 pullback); the slow 2022
bear market was positive.

## 4. Passing the eval (LucidPro 25K)

**Rolling starts** (an eval begun on every session day; B-08, B-11, B-15):

| Size per strategy | Pass % | Median trading days to pass |
|---|---|---|
| 1 MNQ | 73.1 (base); **~67 under noise** (worst run 60) | 35 |
| 2 MNQ | 52.6 | 10 |
| 3 MNQ | 45.0 | 5 |
| 5 MNQ | 39.9 | 3 |

- **Smaller size passes more often but more slowly.** Running more strategies speeds up the pass but does not raise the pass rate
  (B-07).
- **By start year** (1 MNQ, base): 57–87%. Under noise, 2021 falls from 87% to 42%; that year depended on exact fills.
- **Lucid rules check:** the target, the $1,000 EOD trailing MLL and the $25,100 lock in the simulator match Lucid's site. The
  optional $600 DLL changed nothing in the finished cases.

## 5. Funded phase (B-13, no fees)
- **At 1 MNQ each, under Lucid's payout rules,** a funded account pays about **$1,800 a year** (median of rolling 365-day
  windows; 10th–90th percentile $900–$3,600). There was one funded breach in 7 years.
- **The fixed $1,000 request and the 40% consistency rule** cap withdrawals at about $1,000 per cycle; the extra profit piles up
  in the account.

## 6. First payout on Pro 25K — everything up to 2026-11-04

**Journey model** (B-16, `first_payout.py`):
- Buy an eval ($75.60, DLL on) → a failed eval costs a $75 reset → a pass leads to funded (next session day) → a funded breach
  before the payout means buying a new eval → the journey ends at the first payout.
- One journey from every start day with ≥ 365 days of data after it (1,564 starts).
- **The first payout needs:**
  - $1,000 request: balance ≥ $27,100 (+$2,100 funded). $500 request: ≥ $26,600 (+$1,600).
  - Cycle profit ≥ $250, and best day ≤ 40% of the cycle.
  - You receive 90%.

**Medians of 200 noise runs:**

| e/f (MNQ per strategy) | First payout | Median days | Paid ≤ 6 months | ≤ 12 months | Fees mean / median | Received − fees | Entry |
|---|---|---|---|---|---|---|---|
| 1/1 | $1,000 | 227 | 31% | 89% | $141 / $151 | ~$760 | B-16 |
| 1/1 | $500 | 196 | 44% | 92% | $141 / $151 | ~$310 | B-18 |
| 1/2 | $1,000 | — | 41% | 70% | 15% spend > $900 or never paid | — | B-17 |
| 2/1 | $1,000 | 175 | 54% | 99% | $190 / $151 | ~$710 | B-20 |
| **2/1** | **$500** | **148** | **66%** | 99% | $190 / $151 | ~$260 | B-20 |

- **Pass ≠ paid** (B-19): the eval takes ~35 *trading* days, but the first payout comes ~5–7½ *calendar* months after buying the
  first eval. The funded account must climb from $25,000 to $26,600–$27,100 at ~$500/month, and about one journey in two loses a
  funded account on the way.
- **Size up the eval, not the funded account:**
  - A failed eval costs only a $75 reset, so 2 MNQ in the eval shortens the wait by ~7 weeks for ~$50 more fees.
  - A lost funded account costs a new eval plus the climb again, so 2 MNQ funded is worse (2.5–5× more funded losses).
- **The payout size changes the time, not the fees:** no account that reached $26,600 was lost before $27,100.
- **Fee spread** (2/1, $1,000; every purchase ≈ $75):

  | Purchases | 1 | 2 | 3 | 4 | 5 | 6 | 7–10 |
  |---|---|---|---|---|---|---|---|
  | Share of journeys | 28% | 35% | 16% | 8% | 7% | 4% | 2% |

  Worst case $754. Budget about $400 to be covered 9 times in 10.

## 7. The grid: every size combo, every Pro size, Flex and Direct, to the first payout and to break-even

**Run** (full version, plan §7): every combo on base, hc25, hc50, top10 and noise runs 1–50 (stage 1, 54 cases × 782 combo ×
policy rows × 1,564 starts). Then the best 2 combos per plan × size × policy, plus Pro 25K 2/2 and 3/2, on noise runs 51–200
(stage 2, 51 combos).
- **Trust checks:**
  - input checksums 442 / 442;
  - `verify` against B-16–B-20 journey by journey;
  - hand checks passed;
  - stage 1 54 / 54 valid and stage 2 150 / 150 valid, with 0 retries and 0 FAILED;
  - 3 re-run cases byte-identical;
  - the input hash `75d29037271d4a5c926c6d1214cf1930695254975a3ade55caef38b916ff3c6b` is identical to this machine's.
- **Wall time:** 2 h 32 min on a MacBook Air M1 with 4 workers.
- The tables below are full depth (200 noise runs) unless marked.

"Net / month" = (0.9 × first payout − mean fees) ÷ (mean days ÷ 30.4). "Never break even" = journeys whose payouts never covered
their fees by the data end (2026-10-14); they count as not broken even and are never dropped.

### 7.1 The 25K combos asked for: 2/2 and 3/2

| Combo | Net / month | Days median (worst 5%) | Paid ≤ 6 / ≤ 12 mo | Fees mean | Never break even |
|---|---|---|---|---|---|
| Pro 25K 2/2 $500 | 30 | 136 (156) | 66% / 94% | 294 | 6.4% |
| Pro 25K 2/2 max | 111 | 142 (160) | 64% / 94% | 301 | 0.0% |
| Pro 25K 3/2 $500 | 16 | 114 (127) | 74% / 97% | 378 | 5.7% |
| Pro 25K 3/2 max | 107 | **120 (133)** | **72% / 96%** | 382 | 0.0% |
| *For comparison:* Pro 25K 3/1 max | 123 | 162 (173) | 61% / 100% | 207 | 0.0% |
| *For comparison:* Pro 25K 2/1 max | 119 | 175 (187) | 54% / 99% | 190 | 0.0% |

- 3/2 is the fastest Pro 25K combo to a first payout (about 4 months).
- Funding at 2 MNQ costs about 1.7 funded losses per journey against 0.5 at 1 MNQ, which pushes the fees to ~$380. So both
  make less per month than 3/1 or 2/1.
- The same as B-17 and B-20: size up the eval, not the funded account.

### 7.2 Best two combos per plan × size ("max" requests)

| Plan | 1st pick | Net / mo | Days (worst 5%) | ≤ 12 mo | Fees | Never BE | 2nd pick | Net / mo | ≤ 12 mo | Fees | Never BE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Pro 25K | 3/1 | 123 | 162 (173) | 100% | 207 | 0% | 2/1 | 119 | 99% | 190 | 0% |
| Pro 50K | 4/3 | 253 | 155 (171) | 89% | 405 | 1.7% | **6/2** | **253** | **99%** | **365** | **0%** |
| Pro 100K | 7/5 | 270 | 150 (166) | 90% | 763 | 6.2% | 6/5 | 269 | 83% | 693 | 13.8% |
| Pro 150K | 10/7 | 340 | 146 (163) | 86% | 912 | 10.0% | 9/7 | 331 | 83% | 861 | 15.0% |
| Flex 25K | 2/1 | 90 | 242 (260) | 81% | 135 | 0% | — (no other combo ≥ 80%) | | | | |
| Flex 50K | 3/5 | 277 | 147 (168) | 81% | 237 | 15.1% | 3/4 | 255 | 80% | 244 | 15.3% |
| Flex 100K | 7/5 | 323 | 147 (164) | 84% | 502 | 14.9% | 6/5 | 297 | 82% | 497 | 15.0% |
| Flex 150K | 10/7 | **396** | 138 (153) | 86% | 704 | 13.7% | 7/10 | 379 | 82% | 628 | 14.8% |
| Direct 25K | 1 | 90 | 178 (185) | 91% | 317 | 0% | 2 | 62 | 93% | 516 | 2.0% |
| Direct 50K | 2 | 203 | 177 (186) | 91% | 489 | 0% | 3 | 179 | 91% | 652 | 1.0% |
| Direct 100K | 3 | 247 | 181 (191) | 92% | 631 | 0% | 4 | 238 | 92% | 735 | 0% |
| Direct 150K | 5 | 296 | 177 (188) | 91% | 787 | 0% | 6 | 287 | 92% | 896 | 0% |

- **Picks:** the selection rule (plan §3.5) is the highest noise-median net per month among combos paid ≥ 80% within 12
  months.
- **The "$500" policy** is in `G1_summary.md`. Its best rows earn $50–60 per month at 25K–50K and $6 to −$53 at 100K–150K.
- **Combos whose median fees exceed the first payout** are all "$500" rows, for example all 100 Pro 150K "$500" combos. No
  "max" row has median fees above its payout.

### 7.3 By start year (base case): median days to the first payout (share paid within 12 months)

| Combo | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 |
|---|---|---|---|---|---|---|---|
| **Pro 50K 6/2 max** | 212 (100%) | 141 (100%) | 215 (100%) | 109 (100%) | 198 (100%) | 97 (100%) | 182 (100%) |
| Pro 25K 3/1 max | 212 (100%) | 139 (100%) | 211 (100%) | 109 (100%) | 194 (100%) | 95 (100%) | 181 (100%) |
| Pro 25K 3/2 max | 154 (100%) | 77 (100%) | 233 (95%) | 79 (100%) | 134 (100%) | 73 (100%) | 217 (100%) |
| Pro 50K 4/3 max | 174 (100%) | 120 (100%) | 189 (100%) | 98 (100%) | 173 (100%) | 87 (100%) | 243 (100%) |
| Pro 150K 10/7 max | 162 (100%) | 117 (100%) | 182 (100%) | 123 (100%) | 166 (100%) | 92 (100%) | 244 (100%) |
| Flex 50K 3/5 max | 162 (100%) | 117 (90%) | 276 (80%) | 115 (100%) | 130 (100%) | 100 (91%) | n/a (0%) |
| Flex 100K 7/5 max | 162 (100%) | 121 (100%) | 175 (100%) | 136 (100%) | 216 (97%) | 93 (91%) | n/a (0%) |
| Flex 150K 10/7 max | 162 (100%) | 117 (100%) | 176 (100%) | 157 (100%) | 146 (100%) | 89 (91%) | 386 (43%) |
| Direct 150K 5 max | 162 (100%) | 140 (100%) | 231 (97%) | 154 (100%) | 186 (100%) | 119 (95%) | 245 (89%) |

Starts per year: 69 (Sep–Dec 2019), 259, 259, 257, 258, 259, 203 (2025 up to 365 days before the data end). "n/a" means fewer
than half were paid.

### 7.4 Why many Flex rows show ~14% "never break even"
- **They are not lost accounts.** In the base journeys every such journey is OPEN at the data end, and they all started in
  2024–2025. For example Flex 100K 7/5 has 226: 203 from 2025 and 23 from 2024.
- **Flex's large "max" payouts need 2× the payout in profit** (the 50% rule) plus 5 qualifying days, and the 2025–26 stretch
  did not deliver that in time.
- **Pro and Direct don't have this problem:** the same starts were paid within a year.

### 7.5 Stress cases (base trade lists with 25% / 50% of each trade's profit removed, or the top 10 trades removed), "max"

Each cell: net per month / paid ≤ 12 months / never break even.

| Combo | Base | Profit −25% | Profit −50% | Top 10 removed |
|---|---|---|---|---|
| **Pro 50K 6/2** | 279 / 100% / 0% | 200 / 93% / 0% | 119 / 67% / 0% | 240 / 98% / 0% |
| Pro 50K 4/3 | 280 / 100% / 0% | 164 / 85% / 2% | 93 / 57% / 15% | 259 / 98% / 0% |
| Pro 25K 3/1 | 135 / 100% / 0% | 97 / 93% / 0% | 61 / 77% / 0% | 117 / 100% / 0% |
| Pro 25K 3/2 | 121 / 99% / 0% | 84 / 96% / 0% | 57 / 95% / 11% | 92 / 96% / 2% |
| Pro 25K 2/2 | 113 / 94% / 0% | 92 / 93% / 6% | 42 / 76% / 8% | 69 / 81% / 10% |
| Pro 100K 7/5 | 295 / 100% / 0% | 174 / 88% / 0% | 36 / 52% / 15% | 256 / 97% / 0% |
| Pro 150K 10/7 | 358 / 100% / 0% | 213 / 88% / 0% | 199 / 78% / 15% | 328 / 99% / 0% |
| Flex 100K 7/5 | 345 / 85% / 14% | 227 / 73% / 15% | 147 / 61% / 15% | 342 / 85% / 14% |
| Flex 150K 10/7 | 309 / 91% / 0% | 374 / 84% / 15% | 158 / 63% / 16% | 316 / 91% / 0% |
| Direct 50K 2 | 222 / 97% / 0% | 154 / 81% / 0% | 93 / 67% / 0% | 214 / 99% / 0% |
| Direct 150K 5 | 324 / 97% / 0% | 232 / 84% / 0% | 130 / 67% / 0% | 312 / 99% / 0% |

Every listed combo stays profitable to the first payout at half the backtest profit. Pro 50K 6/2 and the Direct accounts are the
only ones in the list that keep 0% never breaking even in every stress case.

### 7.6 Full tables
All 782 rows per plan × size (base and noise medians, fees, funded losses, break-even) are in
`reports/lucid_grid_full/G1_summary.md`.

## 8. Lucid rules used and assumptions

Full tables: [prop_grid_plan_v1.md §4](prop_grid_plan_v1.md). Source: `docs/Lucid Trading - All Account Types Rules Report.md`
(read from Lucid's site on 4 Nov 2026).

**Plan terms:**
- Coupon prices; DLL on; EOD trailing MLL locking at size + $100; 90/10 split; $500 minimum payout; at most 5 payouts per
  account.
- **Pro:** buffer, cycle goal and 40% consistency in funded.
- **Flex:** 40% consistency in the eval; funded needs 5 qualifying days and pays 50% of profit up to a cap, and the MLL locks at
  each request.
- **Direct:** no eval; cycle goals of $1,500 / $3,000 / $6,000 / $9,000 for payout 1, and 20% consistency.

**Assumptions:**
- (a) Direct has no balance buffer.
- (b) Flex's trail levels above 25K equal Pro's.
- (c) Flex's "50% of profit" means account profit.
- (d) LucidScale DLL is not modelled.
- (e)/(f) The scaling plans and the size caps never bind (asserted in code).
- (g) "Exactly 40% / 20%" counts as meeting the rule.
- (h) Not modelled: the 4:45 PM flatten, microscalping and HFT, inactivity, On Demand payouts, live accounts, hedging.

**Skipped plans:**
- **Daily:** funded accounts are breached by trading through U.S. red-folder news, and we have no news calendar.
- **Maxx:** invite only.
- **Black:** legacy, not sold.

**Open questions for Lucid support** (from the rules report):
- Which DLL and consistency options are actually sold?
- Does exactly 40% (or 20%) count as meeting the rule?
- Flex: when exactly does the MLL lock at a payout request?
- Flex: is the 50% based on profit or on balance?

## 9. Caveats
- **Data:** NQ from Sep 2019 only (one bear market, 2022). MES is weaker. Data before 2019 needs the user's Databento account.
- **Fills and minute bars:** 1-minute bars with a worst-case path inside each minute. Fills at the modelled slippage; noise runs
  cover 0–1 min of delay and 0–2 extra ticks.
- **Fixed-point targets and stops:** S1's are in fixed points while NQ rose about 3.4× since 2019.
- **Rules change:** Lucid's prices and rules can change, and some help pages disagree (see the rules report).
- **Slow start years:** starts in 2021 and 2025 are slow in every test.

## 10. How to reproduce (from `FLOW_V2/`)
```
python analysis/gb_nq_backtest/first_payout.py --case OUT reports/prop3/q1/s1/trades.csv reports/prop3/q1/s3/trades.csv [--eval-qty N] [--funded-qty N] [--request 500]
python analysis/gb_nq_backtest/lucid_grid.py verify --ref-dir reports --seed 1
python analysis/gb_nq_backtest/lucid_grid.py driver --version full --out reports/lucid_grid_full --workers 4 --batch-cases 2
python analysis/gb_nq_backtest/lucid_grid.py report --out reports/lucid_grid_full      # rebuilds G1_summary.md from the cases
```
- **Two copies of the summary.** `G1_summary_as_run.md` is the summary as the second machine wrote it. `G1_summary.md` was
  rebuilt on 2026-11-04 after the cross-plan ranking fix in §11. Only that table differs.
- **Time:** the full version takes about 2½ h with 4 workers on a 16 GB machine. On this 8 GB machine, run it from a normal
  terminal outside Claude Code, which stopped the background 8-hour run for low memory.
The inputs (trade lists, noise runs, NQ bars) and their hashes are listed in [prop_grid_plan_v1.md §2](prop_grid_plan_v1.md).

## 11. Open questions and next steps

**Decision for the user:** the plan, size and combo to buy. Recommended: Pro 50K 6/2 "max" (§1); the low-outlay alternative is
Pro 25K 3/1 "max".

**Before buying:**
- Confirm the open rules questions with Lucid support (§8), above all:
  - the DLL and consistency options actually sold;
  - whether exactly 40% counts as meeting the rule;
  - that payouts are still capped at 5 per account.
- **Forward-test S1 + S3 on Sim at the chosen sizes.** The eval phase at 6 MNQ per strategy is 12 MNQ open at once at most,
  far below the 50K cap.

**Fixed on 2026-11-04, after the full run** (the second machine reported these and did not change the script):
- `G1_summary.md`'s cross-plan ranking now uses the same rule as the selection (≥ 80% paid within 12 months).
  - Before the fix it showed Flex 25K max 3/2 and Pro 100K $500 3/3, which miss that cut.
  - The rule lives in one function, `rank_combos()`, which still reproduces the frozen selection exactly.
- `free_mb()` now reads `vm_stat` on macOS. It had returned −1 there, which switched off the memory check; the run was
  unaffected, at ~1 GB per worker on 16 GB.
- **B-17's noise funded losses for 1/2:** `vault_break.md` said 2.0. Its own `R2_summary.md` says 1.28, which matches the grid's
  1.29 seed by seed (both count paid journeys only). The doc was corrected, and the "~4× more funded losses" claim became
  2.5–5×.

**Still open:**
- **The live phase after payout 5** is not modelled. Journeys stop there as LIVE.
- **Ongoing income** after break-even is not ranked here; the metric stops at the first payout. A per-year income figure for the
  chosen combo would need a funded-phase run like B-13 at 2 MNQ.
- **NQ data before 2019** would add bear markets to the test, but needs the user's Databento account.
