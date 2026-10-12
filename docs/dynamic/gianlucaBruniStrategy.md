# Gianluca Bruni — value-area break momentum strategy

**Built:** 2026-10-12 from `docs/video_transcripts/strategies/raw/GianlucaBruni.txt` (transcript) and 18 screenshots of the video
(`C:\yadvendra\New folder\Screenshot 2026-10-12 2215..2312`). Short-form notes: `docs/video_transcripts/strategies/distilled/GianlucaBruni.txt`.
**Video:** "3x Trading WORLD CHAMPION Reveals the 'Low-Effort' Strategy That Won the Robbins Cup (Live On Chart)" — youtube.com/watch?v=nlLGlVJGrtw.
**Status:** DRAFT. Every rule is what the speaker said or showed. Wherever he left something undefined, the rule points to a question
in `gianlucaBruniStrategy_toBeReviewed_v1.md` (written **→ GB-Qn**) and gives the default this document assumes until you decide.
Defaults are marked *(default)*.
**Not a decision record.** Nothing here is adopted by FLOW yet; nothing is in `decisions.md`.

## How to read this

- `[hh:mm:ss]` = where in the video the rule comes from. `[S]` = confirmed from a screenshot.
- Rules are written for **longs**. Shorts are the exact mirror (swap high/low, above/below, bullish/bearish, VAH/VAL) — the speaker:
  "you do the same thing for highs" [01:16:01].
- His instrument in the video is **NQ** (CME Nasdaq futures). He also mentions forex. Nothing he gives is specific to gold; every number
  that depends on the instrument is flagged.
- His performance numbers are his own claims from his own software. Unverified.

---

## 1. Idea in one paragraph

In a market that is trending (each day's value area higher than the last), wait for price to pull back into the **previous day's
value area**. Enter long when a 15-minute candle **breaks back out above the previous value-area high** while **volume falls or stays
low** ("low effort, big result" — price moves easily). Stop under that candle, target 2R, never beyond the last swing high. Keep adding
trades on each new low-effort breakout candle until price reaches that swing high. Size every trade by a 5-question score
(volatility regime, regime freshness, win/loss streak, volume pattern, session) mapped to a fraction of Kelly.

He says half his profit came from the setup and half from the sizing model [00:11:14–00:11:35].

---

## 2. Chart and data

### 2.1 Timeframe
- **One timeframe: 15-minute.** Structure, value area, trigger, stop and target are all read on the same 15m chart. Never switch
  timeframes for entry [01:10:21, 01:14:03, 01:17:34]. `[S]` whiteboard header "15 TF"; chart "NQ1! · 15 · CME".
  ("If you change time frame, this will be different" [01:10:27].)

### 2.2 Session and volume profile
- **Session volume profile** per trading session, giving **POC, VAH (value-area high), VAL (value-area low)** [01:01:08].
  He uses TradingView "SVP HD".
- **Session = the full futures trading day.** `[S]` On his NQ chart (ETH, UTC+2) each profile starts at ≈00:00 UTC+2 = 18:00 New York =
  CME Globex open. So one profile per Globex day (18:00 → 17:00 ET), not separate London / New York profiles. **→ GB-Q3**
  (for GC on our feed this is the existing 17:00 CT rollover, `SessionBoundary.Tracker`.)
- **Value-area percentage:** not stated. *(default)* 70%. **→ GB-Q4**
- **"Previous session"** = the last *completed* session. **"Current / developing session"** = the one in progress.

### 2.3 Volume average
- A moving average of bar volume, **length 20**, plotted over the volume bars [00:51:41]. `[S]` visible on every chart.
- SMA vs EMA unclear (says "simple MA" once, "EMA" afterwards). *(default)* SMA. **→ GB-Q5**

### 2.4 Volatility regime indicator
- **ATR Percentile**: ATR length **14**, percentile lookback **200** bars [00:28:41]. Computed on the 15m chart.
  "Because I tested it and saw this gives me the best result" [00:29:12].

### 2.5 Regime-freshness indicator (BOCPD)
- **Bayesian Online Change Point Detection** [00:47:20]. `[S]` His panel ("AE - BCP…") reads:
  Changepoint 2% · Regime Age *N* bars · MAP Length *N* bars · Last Break · Hazard H 2% · Monitor = **Volatility** · Mean Regime 50 bars.
- Meaning: change points are detected on a **volatility** series, hazard rate 2% (= expected regime length 1/0.02 = 50 bars), and
  **regime age = bars since the last detected change point**. Values seen on screen: 30, 39, 50, 52, 60 bars.
- The exact volatility input and the run-length cap are not visible. **→ GB-Q20**

---

## 3. Building blocks (measurable definitions)

### 3.1 Valid candle
A candle is **valid** when its **body size > its longest wick** (the larger of upper and lower wick), measured in ticks [01:12:44].
- Body = |close − open|. Upper wick = high − max(open, close). Lower wick = min(open, close) − low.
- Valid ⇔ body > max(upper wick, lower wick). *(Strict ">"; equality = not valid.)* **→ GB-Q9**
- Examples `[S]`: body 4 / wick 3 → valid; body 3 / wick 6 → not valid. On NQ: body 16 / wick 56 → not valid [01:20:41];
  body 54 / wick 24 → valid [01:21:12].
- A **valid bearish candle** = valid and close < open. A **valid bullish candle** = valid and close > open.

### 3.2 Engulfing (his definition — not the textbook pattern)
A candle **engulfs** when its **close is beyond the previous candle's extreme**: for longs, close > previous candle's **high** (its
wick top, not its body) [01:06:51–01:08:06].
- "We need to take the highest of wick… the body closes above that."
- Candle size does not matter. "The result" = taking out the previous high [01:07:51].
- The previous candle can be **any colour** `[S]` (whiteboard shows a blue candle engulfing a blue one; on the NQ chart re-entries
  engulf green candles).
- **Minimum margin:** close − previous high ≥ **threshold** (§3.4) [01:24:45, 01:26:14]. A close beyond the previous high by less than
  the threshold is **not** an engulfing ("three is not six, so we can't take the trade yet").

### 3.3 Value-area-high break
A candle **breaks the previous VAH** when its **close** is above the previous session's VAH by **≥ threshold** (§3.4)
[01:17:52–01:19:00]. `[S]` whiteboard: 0.6 above the line = broken; 0.4 = not broken, wait for another candle.
- A wick above VAH does not count; only the close.

### 3.4 Threshold
- Speaker: **0.6 for forex, 6 for NQ** [01:17:44, 01:24:53]. Same threshold for the engulf margin (§3.2) and the VAH break (§3.3).
- **Unit is unclear.** TradingView's ruler displays "points (percent) ticks" and he reads the first number, so 6 on NQ is most likely
  **6 points (24 ticks)**. No value for gold. *(default)* a GC-specific value to be swept in backtest. **→ GB-Q1, GB-Q2**

### 3.5 Price efficiency (the volume condition)
"Low effort, big result": the candle achieves its engulf/break on **less** volume [00:51:00–00:54:41, 01:06:00]. Two variants:

| Variant | Condition on the trigger candle | Notes |
|---|---|---|
| **(a) Reducing volume** | volume < previous candle's volume | Counts even if volume is still above the MA [01:12:58, 01:19:12] |
| **(b) Low effort** | volume < volume MA(20) | Counts even if volume rose vs the previous candle [01:26:50, 01:27:17] |
| Both (a) and (b) | — | "Combination" — strongest [01:06:21] |
| Neither (volume rising **and** ≥ MA) | — | "High effort, high result" → **no trade** [01:28:00, 01:29:00] |

- (a) **or** (b) is enough to enter.
- (a) is the variant that earns a point in the score (§7, Q4) `[S]`.
- (a) is a **one-bar** comparison per [01:19:12] ("falling volume from the current candle to the previous"). The whiteboard sketch shows
  two falling bars, consistent with that. **→ GB-Q6**

### 3.6 Market-structure low (MS low)
The level that must hold for a long setup to stay alive [01:12:10–01:16:01, 01:20:16–01:22:40].
1. Find the **break of structure** (the breakout of the trend leg). **→ GB-Q7** *(default: a 15m close above the most recent confirmed
   swing high)*.
2. Take the **pullback that came just before** that breakout.
3. Inside that pullback, walking back from the breakout toward the past, find the **closest valid bearish candle** (§3.1).
4. If that pullback has **no** valid bearish candle, go back to the **previous** pullback and repeat. ("If this one is not valid, we go
   lower.") It is "really uncommon" to need to go back [01:22:12].
5. **MS low = the lowest low of that pullback** — not the valid candle's own low. "We don't need to check if this candle is valid. We need
   to take this candle and take the lowest level" [01:21:33–01:22:00]. `[S]` on the NQ chart the MS-low line sits at the pullback's swing
   low (~29,640). **→ GB-Q8**
6. A low that is **not anchored** by a valid candle "means nothing" — breaking it does not invalidate anything [01:15:22].

Mirror for shorts: MS high = highest high of the rally that contains the closest valid bullish candle before the breakdown.

How pullbacks are delimited (where one starts and ends) is not stated. **→ GB-Q7**

### 3.7 Market-structure high (MS high) — the target ceiling
`[S]` The peak marked "K" on the whiteboard; on the NQ chart, a horizontal line at the **prior swing high** (~29,966) — the high made
**before** the pullback into the previous value area [01:04:00, 01:27:28]. **→ GB-Q10**

---

## 4. Setup conditions (all must hold)

### 4.1 Trend filter
The current session's developing value area has **shifted up** relative to the previous session's value area → "trending long"
[01:00:55, 01:19:45]. `[S]` His chart shows a staircase of daily value areas, each higher than the one before; overlapping value areas
still count (18 Sep VA ≈ 29,650–29,980 overlaps 17 Sep VA ≈ 29,560–29,800).
*(default)* developing VAH > previous VAH **and** developing VAL > previous VAL. **→ GB-Q11**
When it is evaluated (developing VA at the trigger, or the last two completed sessions) is open. **→ GB-Q27**

### 4.2 Pullback into the previous value area
Price trades back **into** the previous session's value area (at or below previous VAH) [01:01:33]. A **wick touch is enough**; a close
inside is not required [01:23:38, 01:24:13] `[S]`.
Whether price must have traded above the previous VAH first, and how deep the pullback must go: **→ GB-Q31, GB-Q32**.
If the pullback spans a session rollover, which VA is "previous": **→ GB-Q28**.

### 4.3 MS low intact
The MS low (§3.6) has **not** been broken [01:05:07–01:05:20].
- Breaking the **previous VAL** does **not** invalidate — only the MS low does ("even if it breaks this level, it does not matter").
- If the MS low breaks → setup **cancelled**: "forget the whole thing". A trigger after an MS-low break has negative expectancy in his
  testing [01:11:32–01:11:49]. "Broken" = ? (trade-through vs close-through) **→ GB-Q12**

### 4.4 Not near high-impact news
No trade when NFP, CPI or similar news falls **during the trigger**; the strategy also tests poorly on days with lots of news
[01:17:02–01:17:21]. *(default)* skip any trigger whose candle overlaps a high-impact release ± 15 min. **→ GB-Q13**

---

## 5. Entry, stop, target

### 5.1 First trade — the trigger
On **one 15m candle close**, all of [01:18:58–01:19:17]:
1. close above the **previous VAH** by ≥ threshold (§3.3), **and**
2. the candle is an **engulfing** (§3.2) by ≥ threshold, **and**
3. the **volume condition** (§3.5 a or b) holds,

with §4.1–4.4 true at that moment.
- Engulfing + low volume **before** the VAH break does not count ("premature… it's an intent, but we need to break this level")
  [01:25:20–01:25:34].
- Worked rejections on the NQ chart [01:23:30–01:27:00]: broke VAH but volume rising and above MA → no trade (it would have stopped out);
  engulf 24 ≥ 6 with falling volume but VAH not yet broken → no trade; engulf only 3 < 6 → wait; VAH break only 5 < 6 → wait.

### 5.2 Entry price
**Market order at the trigger candle's close** [01:05:59–01:06:05, 01:27:39 "we have a market entry"].

### 5.3 Stop
**The trigger candle's low** (the engulfing candle, not the candle it engulfed) [01:05:59–01:06:10]. Exactly at the low — no buffer
mentioned. **→ GB-Q14**
`[S]` NQ trade 1: entry 29,780.75, stop 29,752.75 (28.25 points / 113 ticks).

### 5.4 Target
**Fixed 2R** (1:2 risk:reward) [00:55:13–00:55:30]. He tested 1:1, 1:2, 1:3, 1:4, 1:5 — 1:2 had the best result.
`[S]` NQ trade 1: target +56.25 points, R:R 1.99, hit.

### 5.5 Ceiling — the MS high
- Never target beyond the MS high (§3.7). If 2R would land above it, **target the MS high instead** and accept the smaller R:R
  [01:11:05–01:11:24].
- **Any open position is closed when price reaches the MS high.**
- **Once price reaches the MS high, the setup is finished** — no more trades, even if a trigger appears [01:29:09–01:29:13].
- No minimum R:R is given for a ceiling that is very close. **→ GB-Q15**

### 5.6 Re-entries (continuation trades)
After the first trade, **every later 15m candle that is an engulfing (≥ threshold) and meets the volume condition is another entry**,
same rules: market at close, stop at its own low, target 2R capped at the MS high [01:08:30–01:09:30, 01:28:20–01:28:50].
- **Only if the first trade was taken.** No first trade at the VAH break → no continuation trades, "no matter how much it chains"
  [01:09:32–01:09:49].
- `[S]` NQ trade 2: entry ≈29,851.75, stop ≈29,822.75, taken after trade 1 had already hit its target; ceiling ≈29,966.
- Whether a re-entry may open while the previous trade is still open is not shown. **→ GB-Q16** *(default under FLOW's 1-contract cap:
  no — re-entries only when flat)*.
- Whether a re-entry must also close above the previous VAH by the threshold. **→ GB-Q17** *(default: no — engulf + volume only, since
  price is already above VAH)*.
- He says the score of a continuation trade is usually as good as the first (regime and freshness barely change) [01:09:56–01:10:12].

### 5.7 After the setup ends
The setup ends when price reaches the MS high, or the MS low breaks. A new setup needs a new pullback into the **new** previous
session's value area [01:10:44–01:11:00]. More than one setup per session: **→ GB-Q29**.

### 5.9 Session end
Not mentioned: hold overnight, or flatten at session end? *(default)* flatten at session end. **→ GB-Q30**

### 5.8 No other filters
"When the price comes down, doesn't break the lowest and goes up, the trade is always valid" — claimed positive expectancy over the last
three years [01:18:42–01:18:58]. No time-of-day filter, no candle-beauty filter ("we don't care how beautiful it is" [01:26:05]).

---

## 6. Filters used by the score

### 6.1 Volatility regime — ATR percentile (§2.4) [00:27:50–00:28:24]

| ATR percentile | Regime |
|---|---|
| 0–25 | LOW |
| 26–50 | MEDIUM |
| 51–75 | HIGH |
| 76–100 | EXTREME |

"Every day when you open your chart you should know in which regime you are… it's mandatory" [00:30:22].
Measure the strategy's expectancy separately in each regime; the one with the best expectancy is the "best regime". His: HIGH.
Read at the trigger candle. **→ GB-Q19**

### 6.2 Regime freshness — BOCPD regime age (§2.5) `[S]` [00:48:41–00:48:55]

| Regime age (bars) | Freshness |
|---|---|
| 0–30 | FRESH |
| 31–60 | MATURE |
| 61–100 | OLD |

His strategy performs best when FRESH. Board says "Regimes age 0–100"; the panel shows bars. Ages > 100 not addressed. **→ GB-Q20**

### 6.3 Session
Measure expectancy per session. His: **New York better than London** [00:57:40–00:58:12]. Since the profile session is the whole Globex
day (§2.2), "New York" here means the **trigger time** falls in NY hours. Hours not defined. **→ GB-Q21**

### 6.4 Re-check every 100 trades
Which regime, freshness and session are "best" can change; re-check every 100 trades [00:49:41].

---

## 7. Meta-label — rating each setup 0–5 `[S]` [00:56:11–00:58:20]

Five yes/no questions, one point each:

| # | Question | His "yes" |
|---|---|---|
| Q1 | Is this setup in my best regime? | HIGH volatility (ATR pct 51–75) |
| Q2 | Is this the best trade based on autocorrelation? | L‑L‑L‑W‑**(W)**: the trade right after the first win that ends a losing streak (§8.3) |
| Q3 | Is it the best freshness? | FRESH (regime age 0–30) |
| Q4 | Which price efficiency? | **Reducing volume** (§3.5 a) `[S]` ticked on the board; "low effort" not ticked **→ GB-Q18** |
| Q5 | In which session was it taken? | New York |

- **Every score is traded** — the score only sets size. "Why shouldn't you take the trade if you have the advantage?" [00:59:30–00:59:46].
- "Best" for Q1, Q3, Q5 is **per strategy**, from your own backtest breakdowns — not his values. For gold they must be re-derived.

---

## 8. Risk model

### 8.1 Kelly fraction by score `[S]` [00:58:23–00:59:21]

| Score | Risk |
|---|---|
| 5/5 | ½ Kelly |
| 4/5 | ¼ Kelly |
| 3/5 | ⅕ Kelly |
| 2/5 | ⅙ Kelly |
| 1/5 | "risk very little" — no number **→ GB-Q22** |
| 0/5 | not mentioned **→ GB-Q22** |

- Kelly fraction f* = W − (1 − W) / R, where W = win rate and R = average win / average loss, both from the backtest of this strategy.
  (Standard formula; he names Kelly but doesn't write it.) Window for W and R: **→ GB-Q33**.
- **Full Kelly is never used** — "it assumes the strategy never changes" [00:58:34].
- He does not recommend a size for others ("depends if you trade prop, championship or personal account").
- **FLOW constraint:** `config/risk.json` caps size at **1 contract**, so on GC this model can only decide *trade (1) or skip (0)* unless the
  cap changes. **→ GB-Q23**

### 8.2 Autocorrelation ("memory") of the win/loss sequence [00:30:48–00:36:03, 00:43:39–00:45:47] `[S]`
- Take the sequence of trade outcomes (W/L), ignoring R size.
- **Unconditional win rate** = normal win rate (board example: 40%).
- **Conditional win rate** = win rate of trades that follow a loss (or a win). Board example: W‑L‑L‑W‑W‑L → 2 of 6.
- Compare the two → a **strength score 0–10**:

  | Score | Meaning |
  |---|---|
  | 0–2 | no autocorrelation — outcomes are random |
  | 3–5 | basic |
  | 6–8 | strong |
  | 9–10 | probably overfit ("if you have it you found the holy grail") |

  Same bands for negative autocorrelation.
- **Positive** → wins follow wins, losses follow losses (typical of trend strategies in trending markets). **Negative** → they
  alternate (typical of mean reversion).
- His strategy: **7.9** "memory strength"; after a win, the next trade wins 52.5% of the time [01:35:37–01:35:47].
- The formula that turns (conditional vs unconditional) into 0–10 is not given. **→ GB-Q24**

### 8.3 Streak-based trade selection (positive autocorrelation only) `[S]` [00:34:04–00:39:50]
- **Best trade:** L‑L‑L‑W‑**(W)** — the trade right after the **first win** that ends a losing streak. Biggest size (scores the Q2 point).
  If autocorrelation is only basic (3–5), it is still the big trade but with less risk [00:35:25–00:35:34].
- **Skipped trade:** W‑W‑W‑L‑**(L)** — the trade right after the **first loss** that ends a winning streak (circled on the board). "I don't
  take this trade, never."
- **Later trades inside a losing streak:** taken, lowest size ("the worst setup… still profitable"), so the flip back to a win is caught.
- This reading reconciles a garbled exchange with the circle on the board. **→ GB-Q25**
- For negative autocorrelation the logic inverts (he has had only one such strategy) [00:35:43–00:36:20].

### 8.4 When autocorrelation decays [00:45:00–00:45:56] `[S]`
- Same degradation rule as other metrics (§9.3): 8 → 4 (−50%) = still autocorrelated but weaker → risk less.
- If it drops to ~0, stop using the streak rules (the edge itself may still be there) until **~100 more trades** re-validate it
  (board: "0–100 → 5").

---

## 9. Validation protocol (how he decides an edge is real)

### 9.1 In-sample / out-of-sample [00:14:03–00:15:08, 00:19:47–00:20:12]
Build the rules on one period (his example: 2024–25), then run them **unchanged** on later unseen data (2026). **Never change rules
during the out-of-sample test** ("in 2026 we can have different conditions so I will make new rules — No"). Split for GC: **→ GB-Q34**.

### 9.2 Metrics [00:23:05–00:24:30, 00:15:27–00:17:30]
- Judge by **expectancy** (average R per 1R risked) and **Sharpe**, not win rate. "Win rate alone is basically a scam."
- Sharpe: ≥ 1.5 good, ≈ 1.4 still good, < 1 not good.
- (His "0.2 expectancy, risk $1,000 → $1,200" is misspoken: 0.2R = +$200 average per $1,000 risked.)

### 9.3 Degradation thresholds [00:15:08–00:15:25, 00:20:56–00:22:13] `[S]`
Comparing out-of-sample (or live) against in-sample:

| Drop in expectancy / Sharpe | Meaning | Action |
|---|---|---|
| ~20–25% | normal drift | none |
| ≥ 30% | warning | lower risk; run a failure test |
| ≥ 50% | edge not solid | rework or retire |

`[S]` His software draws "warning" at half the record (−50%) and "extreme positive" at +50%.

### 9.4 Overfitting checks [00:17:35–00:18:52]
- Count how many variants you tested — testing many triggers/structures will find a good Sharpe by chance (probability of backtest
  overfitting, PBO).
- **Chunk test:** split the backtest into ~10 parts; metrics should be stable across them.
- Fewer rules: "the more you add, the less results you have" [01:42:44].

### 9.5 Ongoing monitoring
- Rolling **60-trade** Sharpe and expectancy vs the whole record [01:30:02–01:30:34].
- **Monte Carlo:** 10,000 reshuffles of trade order; check how many lose money, best/average/worst [01:37:43–01:38:20].
- Luck can explain 50–100 trades, not 10,000 [00:10:50].
- Include costs — slippage, commission, swap [01:29:51–01:30:00]. `[S]` His report uses cost = 0.027 (apparently R per trade).

---

## 10. His reported results (his claims) `[S]` [01:29:24–01:30:34, 01:38:05]

NQ, ~2 years, flat risk 0.5% per trade:

| Metric | Value |
|---|---|
| Trades | 727 (~1.4 per trading day) |
| Net | +147.04R, +103.9% |
| Max drawdown | −13.1% |
| Expectancy | +0.20R (rolling-60 average +0.17R) |
| Sharpe | 2.20 (rolling-60 average 2.05) |
| Rolling-60 Sharpe range | −4.68 … +14.10 |
| Rolling-60 expectancy range | −0.33R … +0.97R |
| Rolling-60 windows below zero | 22% |
| Longest spell under the warning line | 97 windows |
| Latest window at recording | Sharpe −1.86, expectancy −0.15R |
| Monte Carlo | 41 / 10,000 shuffles lose money; worst −15R |

Note: the edge was in a negative stretch when the video was recorded.

---

## 11. What FLOW would need to test it

- **Data:** `analysis/data/GC_1m_latest.csv` (~6.2 years of @GC 1-minute OHLCV) is enough for everything except an exact volume
  profile. The daily value area must be **approximated** from 1-minute bars (spread each bar's volume across its high–low range).
  Check the approximation against MotiveWave's tick-built profile on the days kept in `data/`. **→ GB-Q26**
- **Engine:** the bar-close backtest engine (D-122). Resolve a 15m bar that touches both stop and target by looking at the 1-minute bars,
  or count it as a loss.
- **Not needed:** order book, footprint or bid/ask data. D-06 (order-flow execution can't be backtested) does not block this strategy.
- **Hard rules unchanged:** gold only; Sim only; strategy emits `Intent`, never touches orders; 1-contract cap and daily-loss limit from
  `config/risk.json` apply on top of everything above.
