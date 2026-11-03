# Plan — Lucid first-payout and break-even grid for S1 + S3 (all sizes, Pro / Flex / Direct)

Written 2026-11-04 on the dev machine (8 GB, Windows 10) for two runs of the same work:
- the **8-hour version**, run tonight on the dev machine;
- the **full version**, to be run later on a stronger machine by a Claude session that has **none of the chat context**.

Everything that session needs is in this file and the files it links. Nothing here is a trading decision, and nothing here
places an order (see "Rules in force").

---------------------------------------------------------------------------------------------------------------------------

## 0. If you are the Claude session on the other machine, read this first

**You are working in the handoff folder `prop_grid_handoff/`**, not in a clone of the repo (user, 2026-11-04). The user built it
on the dev machine and copied it over by hand.
- It mirrors the repo's paths, so every command here runs **from the folder root**.
- It holds every script, input and reference output the run needs (layout in §2), plus copies of the rules reports and strategy
  docs.
- There is **no git** here: do not commit, push or clone.
- Links in the copied docs to files outside this folder are dead. Do not re-create those files from memory; the run doesn't
  need them.

1. **Read, in order:**
   - the folder's `CLAUDE.md`;
   - this file;
   - the two Lucid rules reports:
     - `docs/Lucid Pro 25K - Complete Rules Report.md`;
     - `docs/Lucid Trading - All Account Types Rules Report.md`;
   - `docs/dynamic/strategies/vault_break.md` §4, entries B-07 to B-20. These are the earlier results this work extends.
2. **Check the inputs** against `SHA256SUMS.txt`: `sha256sum -c SHA256SUMS.txt`. Any mismatch → stop and tell the user.
3. **Use the grid script.** If `analysis/gb_nq_backtest/lucid_grid.py` is present and §11 says it was verified, **use it as
   is**. Otherwise implement it from §5 and §6.
   - Either way, run §9's verification here before the long run.
   - Its references are in `reference/`, not `reports/first_payout*/`. Pass `--ref-dir reference` (or compare against
     `reference/...` by hand).
4. **Run the full version** (§7) with the batching and completeness rules of §8.
5. **Write the results** inside this folder, so the user can carry it back:
   - `reports/lucid_grid_full/` (all outputs, including `G1_summary.md`, the validation files and the driver log);
   - `docs/dynamic/prop_system_full_v1.md` (§10).
   - Add a short "Status log" entry to §11 of this file.
   - The dev machine's Claude merges the results into the repo when the user brings the folder back.
6. **The user decided everything in §3.** Do not re-ask it. If something is truly undecidable, write it as an open question in
   the report and carry on with the stated default.

---------------------------------------------------------------------------------------------------------------------------

## 1. Background: what is being tested and what is already known

**The system:** two intraday NQ strategies from Matteo Conti's videos, traded together on one prop account. Both always use
**the same size**; "1 unit" = 1 MNQ in each strategy. Size per strategy is written e/f = eval size / funded size, e.g. 2/1 =
2 MNQ each in the eval, 1 MNQ each once funded.
- **S1 = Vault Break**, fixed base "2C" (session open at the 17:00 CT Globex open). Code: `analysis/gb_nq_backtest/vault_break.py`.
  Doc: `docs/dynamic/strategies/vault_break.md`.
- **S3 = Overnight-bias ORB**, script defaults. Code: `analysis/gb_nq_backtest/overnight_orb.py`. Doc:
  `docs/dynamic/strategies/overnight_bias_orb.md`.
- **S2 (VWAP pullback + ADX) is left out on purpose** (B-10, B-11): about $1.9k in 7 years, its profit sits in 2024–25, and it
  moves with S3 (monthly correlation 0.49).
- **Data:** NQ 1-minute bars, `analysis/data/NQ_1m_latest.csv`, 2019-09 → 2026-10-14. MNQ = $2/point, $1 round trip per
  contract, 1 tick slippage, all included in each trade's `pnl_usd`.
- **At 1 MNQ each:** about **$505/month** combined (B-09); 63 of 86 months positive; worst day −$561; monthly correlation
  S1–S3 0.19.

**Edge (robustness round R-1, B-15):**
- **Beats random fakes:** 0 of 10,000 random-direction and random-day versions of each strategy did as well as the real one.
- **Bonferroni p** (counted over every variant tried): 0.008 for S1, 0.005 for S3.
- **Noise injection** (entry 0–1 min late, plus 0–2 ticks of extra slippage on the entry and every exit; 1,000 runs): every run
  profitable. Median runs keep 86% (S1) and 94% (S3) of the base net.
- **Outlier dependence:** S3 keeps only 18% of its net without its top 5% of trades.

**Pro 25K eval pass** (rolling starts: an eval begun on every session day):
- **1/1:** 73.1% pass, median 35 trading days. Under noise, ~67% (worst run 60%).
- **Larger sizes:** 2 MNQ: 52.6% pass, 10 days. 3 MNQ: 45%, 5 days (B-08).

**First payout, Pro 25K** (`first_payout.py`; 200 noise runs, medians across runs):

| e/f | Request | Median days to first payout | Paid ≤ 6 months | ≤ 12 months | Fees mean / median | Entry |
|---|---|---|---|---|---|---|
| 1/1 | $1,000 | 227 | 31% | 89% | $141 / $151 | B-16 |
| 1/1 | $500 | 196 | 44% | 92% | $141 / $151 | B-18 |
| 1/2 | $1,000 | worse: 70% paid ≤ 12 months, 2.0 funded losses per journey | | | | B-17 |
| 2/1 | $1,000 | 175 | 54% | 99% | $190 / $151 | B-20 |
| **2/1** | **$500** | **148** | **66%** | 99% | $190 / $151 | B-20 |

**Lessons so far:**
- **Pass ≠ paid** (B-19): the eval takes ~35 trading days, but the funded account must then climb from $25,000 to $26,600 or
  $27,100 at ~$500/month, and about one journey in two loses a funded account on the way.
- **Size up the eval** (a failed eval costs only a $75 reset), **not the funded account** (a lost funded account costs a new
  eval plus the climb again).
- **Payout size changes the time, not the fees:** no account that reached $26,600 was lost before $27,100.

**Not yet tested (this plan):**
- 25K **2/2** and **3/2**;
- every other 25K combo;
- the bigger Pro sizes;
- the Flex and Direct plans;
- **break-even**, i.e. when payouts received ≥ fees paid. On bigger accounts the fees can exceed the first payout.

---------------------------------------------------------------------------------------------------------------------------

## 2. Inputs (git-ignored in the repo; all included in the handoff folder)

**The handoff folder `prop_grid_handoff/`** is built from the dev machine's repo on 2026-11-04. Its layout matches the repo:
```
CLAUDE.md                      instructions + hard rules for the session there
SHA256SUMS.txt                 checksum of every file below
analysis/gb_nq_backtest/       prop_multi.py, lucid_replay.py, prop_sim.py (imported by prop_multi), first_payout.py,
                               first_payout_report.py, lucid_grid.py (added once verified on the dev machine)
analysis/data/NQ_1m_latest.csv the 1-minute NQ bars (needed: the account is checked against open P&L minute by minute)
reports/prop3/q1/s{1,3}/trades.csv                      base trade lists, 1 MNQ
reports/robust_r1/variants/{hc25,hc50,top10}/s{1,3}.csv stress-case trade lists
reports/robust_r1/noise/s{1,3}/seed_0001..0200/trades.csv  noise-run trade lists
reference/first_payout{,_r500,_f2,_e2_r1000,_e2_r500}/{base,noise/seed_0001}/  journeys.csv + summary.txt from B-16..B-20 (for §9)
docs/                          the two Lucid rules reports; docs/dynamic/prop_grid_plan_v1.md (this file);
                               docs/dynamic/strategies/{vault_break,overnight_bias_orb}.md
```
Checked on the dev machine: `first_payout.py --eval-qty 2 --request 500` run from the folder root reproduces
`reference/first_payout_e2_r500/base/journeys.csv` byte for byte.

**Hashes and regeneration** (for the repo route, or if a file is damaged):

| File | What | sha256 |
|---|---|---|
| `analysis/data/NQ_1m_latest.csv` (127 MB) | NQ 1m bars; header `timestampMs,open,high,low,close,volume`; first row 1566578220000, last 1791989700000 | `67a6c1bc4baae922b3bab5eee43206769362f77a3e12654831b3e142b2c39e62` |
| `reports/prop3/q1/s1/trades.csv` | S1 base, 1 MNQ | `e82988a68b5575a5760b4093aff52f62b68fefded46d9917fa092d60d5b51b6c` |
| `reports/prop3/q1/s3/trades.csv` | S3 base, 1 MNQ | `5ddbf60a080b482057afc706c2fd30b68892da4218de90fb9cfa4578392a104b` |
| `reports/robust_r1/variants/hc25/s1.csv`, `s3.csv` | 25% profit haircut | `899b01d12c61cd7ff2b82e6666bf5dcfe5858966fb62e15f15068a1cb1835474`, `c7bc330a0bc91ebadf3b629f174b923183e4508f62ccd0bf7a7defc8e3d13875` |
| `reports/robust_r1/variants/hc50/s1.csv`, `s3.csv` | 50% haircut | `49d396c6db7400d22e337ddc909ffe9ad15ee70f71988f9baf8c7bdf05cb0079`, `8cb30c05f9919744c71eeabf4685dbe9954ffa4f8d716cce42d3ac917e8f6578` |
| `reports/robust_r1/variants/top10/s1.csv`, `s3.csv` | top 10 trades of each removed | `a7a76c702ed8d73c4cfc2279eba1fff739e86eb2885f698667cab145a89811c4`, `828ed9bac52a029f433553602934515a8ae14e3a1825b5337a0708e6a5e1692f` |
| `reports/robust_r1/noise/s1/seed_0001…0200/trades.csv`, same for `s3` | noise runs 1–200 | `noise/s1/seed_0001`: `fd8ef1f91e17a1e18c65ee5a6d4a87c56fa4ad566ccfeb22334d21d5fa6ffa66` (full list: the handoff folder's `SHA256SUMS.txt`); `noise/s3/seed_0200`: `a8aff7170f3e4bab9113c4f8c332b0b3c30703c44e1fc293df9defcf712d52b4` |

**Easiest:** copy `analysis/data/NQ_1m_latest.csv`, `reports/prop3/q1/` and `reports/robust_r1/{variants,noise}/` from the dev
machine (USB or cloud drive).

**Data source:** the NQ CSV comes from Databento, using the user's account (`docs/dynamic/databento.txt` on the dev machine, not
in git). The user has to provide it; never touch `.env`.

**Regenerate** (run from `FLOW_V2/`; the defaults reproduce the files above byte for byte):
```
python analysis/gb_nq_backtest/vault_break.py   --csv analysis/data/NQ_1m_latest.csv --out reports/prop3/q1/s1 --open-ref globex1700 --qty 1
python analysis/gb_nq_backtest/overnight_orb.py --csv analysis/data/NQ_1m_latest.csv --out reports/prop3/q1/s3 --qty 1 --mult 2 --comm 1 --exit-res 1m
python analysis/gb_nq_backtest/vault_break.py   --csv analysis/data/NQ_1m_latest.csv --out reports/robust_r1/noise/s1 --open-ref globex1700 --qty 1 --entry-delay 1 --noise-slip 2 --seeds 1-200
python analysis/gb_nq_backtest/overnight_orb.py --csv analysis/data/NQ_1m_latest.csv --out reports/robust_r1/noise/s3 --qty 1 --mult 2 --comm 1 --exit-res 1m --entry-delay 1 --noise-slip 2 --seeds 1-200
python -c "import sys; sys.path.insert(0,'analysis/gb_nq_backtest'); import robustness as R
for h in (25,50):
    for s in ('s1','s3'): R.write_variant(f'reports/prop3/q1/{s}/trades.csv', f'reports/robust_r1/variants/hc{h}/{s}.csv', R.haircut(h/100))
for s in ('s1','s3'): R.write_variant(f'reports/prop3/q1/{s}/trades.csv', f'reports/robust_r1/variants/top10/{s}.csv', R.drop_top(10))"
```
- **Noise:** each `--seeds` run loads the data once and writes `seed_XXXX/trades.csv` folders. On a big machine, split it,
  e.g. `--seeds 1-50` … `151-200`, in parallel processes.
- **Haircut** = a fixed $ cost per trade equal to h × the strategy's mean trade, on realized P&L only; the intraday open-P&L
  path is unchanged. **top10** = the 10 largest `pnl_usd` trades of each strategy removed.
- Check the hashes after regenerating. If any differ, stop and report; the results would not be comparable with B-15 to B-20.

---------------------------------------------------------------------------------------------------------------------------

## 3. Decisions (all by the user, 2026-11-04). Do not reopen them.

1. **Plans:**
   - **Pro** and **Flex**, sizes 25K / 50K / 100K / 150K.
   - **Direct**, the same four sizes.
   - **Skipped:**
     - **Daily** (a hard breach on U.S. red-folder news; no historical calendar);
     - **Maxx** (invite only, price unknown);
     - **Black** (legacy, not sold).
2. **Size grid per strategy, scaled by the max loss limit (MLL):**
   - 25K: 1–3;
   - 50K: 2–6;
   - 100K: 3–9;
   - 150K: 5–14.
   - **Every eval/funded combination** inside a size's range is run, e.g. 25K: 1/1, 1/2, 1/3, 2/1 … 3/3 (9 combos), and 50K:
     2/2 … 6/6 (25 combos). Direct has no eval, so it runs the funded size only (3 / 5 / 7 / 10 values).
   - **Combo counts:** Pro 9 + 25 + 49 + 100 = 183; Flex 183; Direct 25.
3. **Payout policies:** each combo is run under two policies.
   - **"$500"**: every payout request is $500, the minimum on every plan.
   - **"max"**: every request is the largest that plan allows for that payout number; wait until the full maximum is available.
4. **Break-even:** journeys continue past the first payout until total received (90% of each payout) ≥ total fees paid. Both the
   first-payout metrics and the break-even metrics are reported. See §5.4.
5. **Ranking metric:** **net per month at the first payout** = (0.9 × first payout − mean fees up to the first payout) ÷ (mean
   calendar days to the first payout ÷ 30.4).
   - Computed over **every start**. Journeys never paid are shown as a share and are never dropped from the denominators of
     the shares.
   - Break-even time is shown next to it. Flag any combo whose median fees exceed its first payout.
6. **Flex eval consistency:** 40%.
7. **Direct:** after a breach, buy a new account at the coupon price.
8. **Depth (funnel, the same for every plan and size, 25K Pro included):**
   - **Step 1:** every combo on base, hc25, hc50, top10 and the first N₁ noise runs.
   - **Step 2:** full depth (noise runs up to 200) for the **best 2 combos per plan × size × policy**, plus 25K Pro **2/2** and
     **3/2** (asked for explicitly) under both policies.
9. **Two versions** (§7):
   - **8-hour**, dev machine: start every 2nd day, N₁ = 30, step 2 to 200.
   - **Full**, other machine: start every day, N₁ = 50, step 2 to 200.
10. **Trust over speed:** batching must never let a report run on incomplete data (§8).
11. **Write-ups:**
    - `docs/dynamic/prop_system_v1.md` (dev machine): one report on deploying S1 + S3 together on Lucid, covering everything
      so far plus the 8-hour grid.
    - `docs/dynamic/prop_system_full_v1.md` (other machine): the full-version results, compared with the 8-hour ones.
12. **Commit and push** when done:
    - Dev machine: on `orderflow-rules-review`.
    - Other machine: no git. Results stay in the handoff folder, and the user carries them back.

---------------------------------------------------------------------------------------------------------------------------

## 4. Lucid rules modelled

**Source:** `docs/Lucid Trading - All Account Types Rules Report.md`, read from Lucid's site on 4 Nov 2026. Coupon prices, DLL
ON (the cheaper option), EOD trailing drawdown.

| Plan | Size | Fee (eval or account) | Reset | Target | MLL | Trail lock: closing balance ≥ L → MLL fixed at M | DLL (soft) |
|---|---|---|---|---|---|---|---|
| Pro | 25K | 75.60 | 75 | 1,250 | 1,000 | 26,100 → 25,100 | 600 |
| Pro | 50K | 120.40 | 120 | 3,000 | 2,000 | 52,100 → 50,100 | 1,200 |
| Pro | 100K | 190.40 | 190 | 6,000 | 3,000 | 103,100 → 100,100 | 1,800 |
| Pro | 150K | 255.50 | 255 | 9,000 | 4,500 | 154,600 → 150,100 | 2,700 |
| Flex | 25K | 45.50 | 45 | 1,250 | 1,000 | 26,100 → 25,100 | 600 |
| Flex | 50K | 80.50 | 80 | 3,000 | 2,000 | 52,100 → 50,100 (assumed) | 1,200 |
| Flex | 100K | 150.50 | 150 | 6,000 | 3,000 | 103,100 → 100,100 (assumed) | 1,800 |
| Flex | 150K | 226.10 | 225 | 9,000 | 4,500 | 154,600 → 150,100 (assumed) | 2,700 |
| Direct | 25K | 230.30 | no reset (buy new) | none | 1,000 | 26,100 → 25,100 | none |
| Direct | 50K | 360.50 | buy new | none | 2,000 | 52,100 → 50,100 | 1,200 |
| Direct | 100K | 490.00 | buy new | none | 3,500 | 103,600 → 100,100 | 2,100 |
| Direct | 150K | 585.20 | buy new | none | 5,000 | 155,100 → 150,100 | 3,000 |

**Funded payout rules** (checked at each session close of the funded account; a payout is taken at that close):

| Plan | Gate for a request of R | Max request, payout 1 / later | Consistency |
|---|---|---|---|
| Pro | balance − R ≥ buffer (= the lock balance L above); cycle profit ≥ goal ($250 / $500 / $750 / $1,000 by size) | 1,000 / 1,500; 2,000 / 2,500; 2,500 / 3,000; 3,000 / 3,500 | largest day in the cycle ≤ 40% of cycle profit |
| Flex | ≥ 5 days in the cycle with day P&L ≥ $100 / $150 / $200 / $250; cycle profit > 0; R ≤ 50% of account profit (balance − start) | cap $1,000 / $2,000 / $2,500 / $3,000 for every payout | none in funded |
| Direct | cycle profit ≥ goal: payout 1 $1,500 / $3,000 / $6,000 / $9,000; later $1,250 / $2,500 / $3,500 / $4,500 | payouts 1–3: 1,000 / 2,000 / 2,500 / 3,000; payouts 4–5: 1,000 / 2,500 / 3,000 / 3,500 | largest day ≤ 20% of cycle profit |

**Rules for every plan:**
- $500 minimum payout; 90/10 split, so you receive 0.9 × R.
- After each payout the cycle resets: cycle profit, the day list and Flex's qualifying-day count.
- At most **5 payouts per account**. After the 5th the account goes to Lucid's live review, which is not modelled; the journey
  stops there and is marked LIVE.

**Flex eval:** passes only when profit ≥ target **and** the largest eval day ≤ **40%** of eval profit.
- This is checked whenever P&L is realized, with today's running P&L counted as today.
- If the target is reached but the day rule fails, trading continues (no fail).

**Flex funded:** when a payout is requested, the MLL moves to the locked level M and stays there. This matters only after the
first payout, because journeys now continue.

**Assumptions** (write each one into the report):
- **(a) Direct has no balance buffer;** none is stated.
- **(b) Flex 50K / 100K / 150K trail levels equal Pro's;** the report gives 25K only.
- **(c) Flex's "50% of profit" means account profit (balance − start).** One page says "account balance".
- **(d) LucidScale DLL is not modelled.** Above the buffer, Lucid replaces the fixed DLL with 60% of the highest EOD profit; the
  fixed DLL is kept, which is the same or stricter.
- **(e) Flex's scaling plan never limits our size** (funded start caps 10 / 20 / 30 / 40 micros). Check it: our max open micros
  is 2 × size; assert from the records' contract count `c`.
- **(f) Max size caps:** Pro, Flex and Direct allow 20 / 40 / 60 / 100 micros. 2 × 14 = 28 ≤ 100, so they never bind; assert
  it anyway.
- **(g) "Exactly 40% / 20%" counts as meeting the rule** (≤), as in B-16.
- **(h) Not modelled:**
  - the 4:45 PM ET flatten (S1 and S3 are intraday);
  - microscalping and HFT (both strategies hold minutes to hours);
  - inactivity;
  - On Demand payouts;
  - live accounts;
  - the hedging rules (one account at a time).

---------------------------------------------------------------------------------------------------------------------------

## 5. Simulation model (extends `analysis/gb_nq_backtest/first_payout.py`; read it, it is short)

### 5.1 Records (unchanged: reuse `prop_multi.py`)
- **`load_trades`, `load_bars`, `build_records`, `attempt_recs`, `fast_sess_ord`** from `analysis/gb_nq_backtest/prop_multi.py`,
  and **`Account`** from `analysis/gb_nq_backtest/lucid_replay.py`.
- **Building:** trades carry `bk` (bars key), `m` = 2.0 (MNQ multiplier) and `q` (size). For size n, use the trades with `q=n`
  and `pnl = pnl_usd × n`. This is exactly `first_payout.run_case`'s `mk()` with `--funded-qty n`. **Build per size, exactly;**
  do not scale finished records (float sums could differ).
- **A record** is `(t, sd, di, un, pnl, k, c)`:
  - t = minute;
  - sd = CME session-day ordinal (the session rolls at 17:00 ET);
  - di = index into the list of session days;
  - un = the sum of the open trades' **worst** open P&L inside that minute (bar low for longs, bar high for shorts);
  - pnl = realized P&L of trades closing at t;
  - k = number of trades closing;
  - c = open contracts.
- **`attempt_recs(S, di, daylist, bars, day_idx, dll)`** gives the records of an account that starts on day `di`. Trades open
  across the start are excluded. With a DLL the full minute path is used.

### 5.2 One account (generalises `first_payout.run_account`)
Walk the records from the start. At each record:
1. **New session day:** close the previous day.
   - `acc.eod()` updates the EOD trailing MLL (peak EOD balance − MLL allowance) and locks it at M once a close ≥ L.
   - Funded: add the day's P&L to the cycle, then check the payout gate (§4) for the policy's next request.
2. **Locked by the DLL:** skip the rest of the day.
3. **Breach:** `bal + un ≤ mll` → FAIL.
4. **DLL:** `(bal − day start balance) + un ≤ −DLL` → realize `un` (flatten at the worst price of that minute), lock the day;
   FAIL if `bal ≤ mll`.
5. **Realize:** add `pnl`; FAIL if `bal ≤ mll`.
   - Eval: PASS if `bal − start ≥ target` (Flex: and the 40% day rule).

**Details:**
- The funded account starts on the session day after the eval pass (`di = pass_di + 1`).
- Data end → OPEN.
- **Payout:**
  - `bal −= R`; received += 0.9 × R; payout count += 1; reset the cycle.
  - Flex: `mll = M`, locked.
  - After the 5th payout → LIVE (stop).

### 5.3 Memoisation (this is what makes the grid affordable)
- **Eval:** `eval_attempt(plan, size, di)` and `funded_attempt(plan, size, policy, di)` are deterministic for a given trade
  list, so cache them per case.
  - Measured on 25K 1/1: 4,937 attempt calls but only 1,102 distinct.
  - Eval attempts at size e are shared by every combo with that eval size.
- **Funded:** make it **lazy**. A cached generator per (plan, size, policy, di) yields payouts one at a time, with (payout day
  index, amount, end state). A journey only pulls payout k+1 if it has not broken even yet.
  - Measured: a funded set to the first payout takes ~2.4 s, but walking every funded account to a breach or the data end
    takes ~25 s. Lazy pulling avoids the 25 s when most journeys break even at payout 1.

### 5.4 Journey (one per start day)
**Fees:**
- **Pro / Flex:** buy the eval (fee) → eval attempts, buying a reset after each FAIL → PASS → funded account → payouts →
  breach → buy a new eval (fee) → …
- **Direct:** buy an account (fee) → funded from that day → breach → buy a new account (fee) → …

**Recorded per journey:**
- start date;
- first-payout day, amount, and fees paid up to then;
- break-even day: the first payout at which cumulative received ≥ cumulative fees;
- the number of payouts needed;
- total fees;
- resets, evals and funded losses;
- the end state: BREAKEVEN, LIVE without break-even, or OPEN at the data end.

**Stop rules:**
- The journey stops at break-even, LIVE, or the data end.
- Days are calendar days from the start day to the event day (as in B-16); trading days are kept too.

**Starts:**
- Every `step`-th session day from the first record day, while `start ≤ last data day − 365 days` (as in B-16: 1,564 starts at
  step 1).
- A journey still running at the data end counts as **not reached**. It is never dropped, so the shares stay fair.

**Chain:** journeys back to back from the first data day, the next starting the day after the previous one's first payout. This
gives independent samples for a 95% t-CI of mean fees and mean days (as in B-16).

### 5.5 Metrics per (case, plan, size, e, f, policy)
- **Counts:** n starts; paid at least once; never paid.
- **First payout:**
  - median and p90 days;
  - shares paid within 4 / 6 / 12 months (30.4-day months, over all n);
  - fees mean / median / p90 / max, and the share with fees > R1;
  - resets / evals / funded losses per journey;
  - net per month (§3.5);
  - chain n and CIs.
- **Break-even:**
  - median and p90 days;
  - shares within 6 / 12 months;
  - the share never broken even by the data end;
  - the share that went LIVE without breaking even;
  - median payouts needed.
- **Checks:** max open micros (assert ≤ the plan cap, and for Flex ≤ the scaling start cap).
- **Base case only:** the per-journey CSV, for by-year tables.

---------------------------------------------------------------------------------------------------------------------------

## 6. Code to write

**`analysis/gb_nq_backtest/lucid_grid.py`**, one script with these subcommands:

| Subcommand | Does |
|---|---|
| `manifest --version {8h,full} --out DIR` | Writes `DIR/manifest.csv`: every expected (case, plan, size, e, f, policy) row for step 1, and `DIR/batches/step1_NNN.txt` case lists |
| `run --batch FILE --out DIR [--step N]` | One process: loads the bars once, runs each case in the list, and writes `DIR/cases/<case>/summary.csv` (+ `journeys_<combo>.csv` for base) **atomically** (temp file, then `os.replace`). It skips a case whose summary exists and passes validation. `--plans`, `--combos FILE` limit the work (used by step 2) |
| `validate --out DIR --step N` | Checks every expected row: present, parses, n = the expected number of starts, no NaN or empty required fields. Writes `DIR/validation_stepN.txt`; exits non-zero if anything is missing or bad |
| `select --out DIR` | Only after step 1 validates: picks the best 2 combos per plan × size × policy (+ 25K Pro 2/2 and 3/2), writes and **freezes** `DIR/step2_selection.csv` and the step-2 manifest and batches |
| `report --out DIR` | Refuses (non-zero exit) unless validation passed for every step it summarises; writes `DIR/G1_summary.md` |
| `driver --version {8h,full} --out DIR [--workers W]` | Runs everything in order with checks, retries and logging (§8) |

**Selection rule (step 2):**
- Use the median over step-1 noise runs of net per month at the first payout, among combos with ≥ 80% of journeys paid within
  12 months.
- If none qualify, use the lowest median days to the first payout.
- Ties: fewer funded losses.

**Constants:**
- Plan rules in one `PLANS` table (§4), with a source note. Assert the 25K Pro row against `lucid_replay.PLANS["25k"]` and
  `first_payout.py`'s constants.
- Cases: `base` (reports/prop3/q1), `hc25`, `hc50`, `top10` (reports/robust_r1/variants), `noise/seed_XXXX`
  (reports/robust_r1/noise/s{1,3}/seed_XXXX).

**Machine notes (the dev machine):**
- **Python paths on Windows:** pass Windows paths to Python (`cygpath -m` in Git Bash).
- **Writing scripts:** write them with the file tool; Bash heredocs with quotes break.
- **Memory:** one process loading the NQ CSV uses ~1–1.5 GB.
- **Long runs:** each long batch runs in the background, with the result written to disk; the session may reap idle shells
  under memory pressure.

---------------------------------------------------------------------------------------------------------------------------

## 7. The two versions

| | **8-hour version** (dev machine, tonight) | **Full version** (stronger machine) |
|---|---|---|
| Starts | every **2nd** session day (`--step 2`, ~782 starts) | **every** session day (`--step 1`, ~1,564 starts) |
| Step 1 cases | base, hc25, hc50, top10, noise seeds **1–30** (34 cases) | base, hc25, hc50, top10, noise seeds **1–50** (54 cases) |
| Step 2 | selected combos, noise seeds 31–200 | selected combos, noise seeds 51–200 |
| Output dir | `reports/lucid_grid_8h/` | `reports/lucid_grid_full/` |
| Workers | 1 (2 only if measured > 1.3× throughput and > 3 GB free) | as many as memory allows: ~1.5 GB per worker plus headroom; one process per batch |
| Estimated time | ~1 h build and verify + ~3.7 h step 1 + ~3.5 h step 2 ≈ **8 h** (±50%: the big accounts are not yet timed) | ≈ **20 h on the dev machine**; divide by the parallel workers on a bigger machine |
| Report | `docs/dynamic/prop_system_v1.md` | `docs/dynamic/prop_system_full_v1.md` |

**Commands** (once the script exists):
```
python analysis/gb_nq_backtest/lucid_grid.py driver --version 8h   --out reports/lucid_grid_8h
python analysis/gb_nq_backtest/lucid_grid.py driver --version full --out reports/lucid_grid_full --workers W
```

**Timing measured on the dev machine** (25K, 1 MNQ, step 1, one trade-list pair):
- loading the bars: 3 s;
- records per size: 1.8 s;
- eval set (all starts): 1.9 s;
- funded set to the first payout: 2.4 s;
- funded set to a breach or the data end: 24.6 s.
- **Estimate per case:** ~50 eval sets + 150 funded sets, about 13 min at step 1 and about 6.5 min at step 2.

**If time runs short** (8-hour version):
- Never shorten step 1 after it has started.
- Cut step 2's seed count in whole blocks (to 100), and say so in the report.

---------------------------------------------------------------------------------------------------------------------------

## 8. Batching and completeness (the results must stay trustworthy)

1. **The manifest comes first.** Nothing is run unless it is in the manifest, and every manifest row must end up valid or be
   listed as FAILED.
2. **Batches:**
   - Each batch is ~20–30 min of cases and one Python process at low priority.
   - Check free memory before each batch.
   - The driver log (`DIR/driver_log.txt`) records the batch name, cases, start and end time, exit code and free memory.
3. **After each batch,** validate it. A missing, partial or invalid case is re-queued (at most 2 retries), then marked
   **FAILED** in `validation_stepN.txt`.
   - A non-zero exit re-queues the batch's unfinished cases.
   - If a worker dies from memory pressure, resume once with 1 worker and log it.
4. **Atomic writes** only. A half-written file can never pass validation.
5. **Gates:**
   - Step 2 is selected only after step 1 validates **completely**.
   - The selection file is frozen; it is never recomputed after step 2 starts.
   - `report` refuses to run on incomplete steps.
6. **No mixing of samples:**
   - A combo is reported either with its full step-2 seed set or at step-1 depth, marked "full depth not run".
   - Every table states its sample, e.g. "30 of 30 noise runs, all 391 combo × policy rows complete".
7. **Reproducibility:**
   - The noise seeds are fixed files (§2).
   - At the end, re-run 3 random finished cases into a temp dir; they must be byte-identical (compare the sha256).
   - Record the input hashes (§2) in `G1_summary.md`.
8. **If anything ends incomplete,** the report covers only the complete parts and lists what is missing. It never estimates
   the missing part.

---------------------------------------------------------------------------------------------------------------------------

## 9. Verification before any long run (must pass; record the results in §11)

1. **Reproduce `first_payout.py` journey by journey** on Pro 25K at step 1, base. Compare each journey's result, fees up to the
   first payout, days, resets and funded losses:
   - 1/1 "$1,000" policy (first payout) vs `reports/first_payout/base/journeys.csv` (B-16);
   - 1/1 "$500" vs `reports/first_payout_r500/base` (B-18);
   - 1/2 "$1,000" vs `reports/first_payout_f2/base` (B-17);
   - 2/1 "$1,000" and "$500" vs `reports/first_payout_e2_r1000/base` and `_e2_r500/base` (B-20);
   - plus one noise seed for 2/1.
   - **Note:** Pro 25K "max" = $1,000 for payout 1, so "max" must match the $1,000 runs.
   - **In the handoff folder:** the references are `reference/<same dir name>/{base,noise/seed_0001}/journeys.csv`.
   - Regenerating any of them takes about 7 s each with `first_payout.py --case OUT S1 S3`, plus `--request 500`,
     `--funded-qty 2` or `--eval-qty 2`, defaults otherwise. It must be byte-identical to the reference.
2. **Check the new rules by hand** against the day-by-day P&L of the journey in question:
   - one Flex eval where the 40% rule delays the pass;
   - one Flex payout (5 qualifying days, 50% of profit, MLL moved to M);
   - one Direct payout (20% rule);
   - one Pro journey that breaks even only after a second payout, or after a funded loss and a new eval.
3. **Asserts on every journey:**
   - break-even at the first payout ⇔ 0.9 × R1 ≥ fees up to the first payout;
   - fees = eval/account fees + resets, consistent with the counts;
   - max open micros ≤ the caps.
4. **Speed:** time one full case (all plans) before choosing the batch size; re-estimate the total and write it in the driver
   log.

---------------------------------------------------------------------------------------------------------------------------

## 10. Report contents (`prop_system_v1.md`; `prop_system_full_v1.md` uses the same layout plus a section comparing it with the 8-hour run)

1. **Bottom line:** the recommended plan, size and e/f combo, and payout policy; expected time, fees, net received, and time to
   break-even; how confident we are, and why.
2. **The system:** S1 + S3, size units, monthly P&L and correlation; links to the strategy docs.
3. **Is the edge real?** B-12, B-08, R-1 (B-15 / B-11) in short.
4. **Passing the eval (Pro 25K):** B-07, B-08, B-10, B-11, B-15 (size vs pass rate; why S2 is left out).
5. **Funded phase and payouts:** B-13.
6. **First payout, Pro 25K, everything so far:**
   - the journey model;
   - B-16 (1/1 $1,000); B-17 (1/2 worse); B-18 ($500); B-19 (pass ≠ paid); B-20 (2/1 best so far, fee spread);
   - then tonight's 2/2 and 3/2.
7. **The grid:**
   - per plan × size, a combo table (median days, paid ≤ 6 / 12 months, fees, net per month, break-even);
   - full-depth results for the selected combos;
   - **a cross-plan ranking table** (best combo per plan × size × policy);
   - by-year medians for the winners.
8. **Lucid rules used:** the §4 tables, assumptions (a) to (h), what is not modelled, and the open questions for Lucid support
   (from the rules report).
9. **Caveats:**
   - NQ data starts in 2019 only;
   - MES is weaker;
   - 1-minute bars with a worst-case path;
   - fills;
   - prices and rules can change;
   - the 2021 and 2025 starts are slow.
10. **How to reproduce:** commands, `reports/` paths, input hashes.
11. **Open questions and next steps.**

**Pointer entries** (dev machine only; the other machine leaves the strategy docs alone):
- `vault_break.md` **B-21** and `overnight_bias_orb.md` **B-17**: a short pointer entry each.
- Update their status lines, the "Prop trading?" summary rows and the findings.
- Add a row to `docs/dynamic/strategies/README.md` if it lists such reports.

---------------------------------------------------------------------------------------------------------------------------

## 11. Status log (append as work proceeds)

- 2026-11-04: plan written and pushed (dev machine, branch `orderflow-rules-review`). Handoff folder built at
  `C:\yadvendra\trading\prop_grid_handoff\` (439 files, ~190 MB). The standalone check passed (`first_payout.py` from the folder
  reproduces `reference/first_payout_e2_r500/base` byte for byte).
- 2026-11-04 ~03:30: **`lucid_grid.py` written and verified on the dev machine; it is in the handoff folder. Use it as is.**
  - **§9.1 passed:** `lucid_grid.py verify --ref-dir reports --seed 1` → all 10 reference sets identical (B-16 1/1 $1,000,
    B-18 1/1 $500, B-17 1/2, B-20 2/1 $1,000 and $500; base and noise seed 1): 15,640 of 15,640 journeys, matching result,
    fees, evals, resets, funded losses, trading days and calendar days. About 16 s.
  - **Mutation check:** with Pro 25K consistency changed to 35%, verify FAILS (649–1,399 of 1,564 identical), so it catches
    rule errors.
  - **§9.2 passed:** `lucid_grid_handcheck.py`, against day P&L recomputed from the trade list, checks:
    - a Flex eval pass delayed by the 40% rule (target on 2020-08-20 with best day 50% → pass on 2020-08-26 at 33%);
    - a Flex payout (18 qualifying days, R ≤ 50% of profit, the gate not met the day before);
    - the Flex MLL locked at 25,100 after a request;
    - a Direct payout (cycle $1,639 ≥ $1,500, best day 19.2%);
    - a Pro 150K 5/5 journey breaking even only at payout 2 after a funded loss.
  - **§9.3 asserts** run inside every case: break-even at payout 1 ⇔ 0.9 × R1 ≥ fees; max open micros ≤ the plan cap; Flex ≤ its
    scaling start cap.
  - **End-to-end smoke test** (`driver --version smoke`: step 40, 2 + 1 noise runs) passed: manifest → 2 batches → validation
    (6/6 cases, 782 rows each) → selection (52 combos) → stage 2 → reproducibility (3 cases byte-identical) → report.
- **Measured speed** on the dev machine with the real code: one stage-1 case (all 782 combo × policy rows) takes **181 s at
  step 2**, with 1 worker. 8-hour version: stage 1 ≈ 34 × 3 min ≈ 1.7 h, plus stage 2 → about 5–6 h. Full version on the dev
  machine: about 2× per case at step 1 → stage 1 ≈ 54 × 6 min ≈ 5.5 h, plus stage 2 → about 12 h. Divide by the workers on a
  bigger machine.
- **In the handoff folder, verify with:** `python analysis/gb_nq_backtest/lucid_grid.py verify --ref-dir reference --seed 1`
  (must print `VERIFY PASSED`), then `python analysis/gb_nq_backtest/lucid_grid_handcheck.py` (must print
  `HAND CHECKS PASSED`). Then start the full run:
  `python analysis/gb_nq_backtest/lucid_grid.py driver --version full --out reports/lucid_grid_full --workers W --batch-cases 2`.
  - W = 1 per ~2.5 GB of free memory, up to (CPU cores − 1).
  - The driver is resumable: if it stops, run the same command again; finished, valid cases are skipped.
  - Do **not** delete `reports/lucid_grid_full/config.json` or the frozen `step2_selection.csv`.
  - At the end, `G1_summary.md` is written automatically.

---------------------------------------------------------------------------------------------------------------------------

## Rules in force (from `CLAUDE.md`; they apply to every model and subagent)
- **Analysis only.** No orders of any kind; no MotiveWave; no `build.sh`.
- **Never read or write `.env`.**
- **Commits and pushes:** only as decided in §3.12; never to `main`. The handoff folder has no git.
- **Reports stay git-ignored** (`reports/`). Only scripts and docs are committed.
