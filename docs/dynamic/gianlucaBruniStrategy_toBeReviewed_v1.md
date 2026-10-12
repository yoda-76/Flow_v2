# Gianluca Bruni strategy — OPEN QUESTIONS (to be reviewed, v1)

**Built:** 2026-10-12, alongside `gianlucaBruniStrategy.md`. Every **→ GB-Qn** in that file is answered here.
**Status:** every question is OPEN until you answer it.
**How to use:** each question has the timestamps to watch, what he said, the options, and the default the strategy file assumes
until you decide. Write your answer on the **Answer:** line. If the video settles it, note the timestamp; if it doesn't, pick an
option (or write "backtest it" to make it a swept parameter).
**When answered:** answers go into `gianlucaBruniStrategy_reviewed_v1.md` (this file + your answers), and `gianlucaBruniStrategy.md`
is updated to match. Same pattern as `orderFlowRules_toBeReviewed_vN` / `_reviewed_vN`.

Already settled from the screenshots (not re-asked): 15m timeframe; session = full Globex day; Kelly ½ / ¼ / ⅕ / ⅙ for 5–2 points;
meta-label volume point = reducing volume (but see GB-Q18); engulfed candle can be any colour; MS low = pullback's lowest low (but see
GB-Q8); MS high = prior swing high (but see GB-Q10); BOCPD hazard 2%, monitors volatility, age in bars.

Priority: **P1** = changes which trades exist. **P2** = changes size or a filter. **P3** = detail / backtest parameter.

---

## A. Thresholds and measurements

### GB-Q1 — Threshold unit (P1)
**Watch:** 01:17:40–01:18:20 (whiteboard 0.6 / 0.4), 01:24:40–01:25:00 ("24… six is for NASDAQ"), 01:26:10–01:26:40 ("three is not six",
"it's five").
**He said:** 0.6 for forex, 6 for NQ, "the movement of ticks". He measures with TradingView's ruler, which shows
"points (percent) ticks" — e.g. his stop label "28.25 (0.095%) 113".
**Question:** on NQ, is 6 = 6 **points** (24 ticks) or 6 **ticks** (1.5 points)? Check which number on the ruler he reads at 01:24:45
and 01:26:14.
**Options:** (a) points  (b) ticks  (c) pips for forex / points for futures.
**Default:** (a) points.
**Answer:**

### GB-Q2 — Threshold value for gold (P1)
**He said:** nothing about gold.
**Question:** what threshold do we use for GC (applies to both the VAH-break margin and the engulf margin)?
**Options:** (a) fixed ticks, swept in backtest (e.g. 0, 2, 4, 6, 10, 20 ticks)  (b) scaled to volatility (e.g. a fraction of ATR(14),
since NQ's 6 points ≈ a fixed share of its 15m ATR)  (c) separate thresholds for the two margins.
**Default:** (a) sweep fixed ticks.
**Answer:**

### GB-Q3 — Session boundary for the profile (P1)
**Watch:** 01:19:36–01:20:10 and the chart at 222143 — where each profile starts.
**What the screenshot shows:** profiles start at ≈00:00 UTC+2 = 18:00 New York (Globex open), chart in ETH mode.
**Question:** confirm it is one profile per full Globex day (not RTH-only, not London/NY sub-sessions).
**Options:** (a) full Globex day, our 17:00 CT rollover  (b) RTH only (09:30–16:00 ET)  (c) something else.
**Default:** (a).
**Answer:**

### GB-Q4 — Value-area percentage (P3)
**Watch:** any moment the SVP HD settings are visible.
**Question:** 70% (TradingView default) or something else?
**Default:** 70%.
**Answer:**

### GB-Q5 — Volume MA type (P3)
**Watch:** 00:51:30–00:52:00 ("simple MA… 20"), later mentions all say "EMA" (00:54:20, 01:06:14, 01:27:00). Look for the indicator name
on the chart's volume pane.
**Options:** (a) SMA(20)  (b) EMA(20).
**Default:** (a) SMA.
**Answer:**

### GB-Q6 — "Reducing volume": one bar or several? (P2)
**Watch:** 00:51:00–00:53:30 (whiteboard sketch), 01:05:40–01:06:20, 01:19:10 ("falling volume from the current candle to the previous").
**Question:** is "reducing volume" just trigger volume < previous candle volume, or must volume decline over 2–3 bars?
**Options:** (a) one bar  (b) two consecutive declines  (c) N bars, swept.
**Default:** (a) one bar.
**Answer:**

### GB-Q9 — Valid candle: strict or inclusive, and doji (P3)
**Question:** body = longest wick → valid or not? (Rare on GC ticks but happens.)
**Default:** strict — body must be **greater**.
**Answer:**

---

## B. Structure (the main source of hidden judgment)

### GB-Q7 — What is a "pullback" and a "break of structure"? (P1)
**Watch:** 01:12:10–01:16:01 (whiteboard), 01:20:16–01:22:40 (NQ chart, walking back candle by candle), 01:22:20–01:22:40 ("this is
other retracement").
**He said:** "the valid short candle before the breakout… the closest to the break of structure… if this retracement has none, go to the
previous one". He never defines breakout, or where a retracement starts and ends.
**Question:** how do we detect pullbacks and the break of structure in code?
**Options:**
(a) swing points by fractal (N bars each side, N swept, e.g. 2/3/5); pullback = from a swing high to the next swing low; break of
structure = 15m close above the last swing high
(b) reuse FLOW's existing market-structure construct (`marketStructureRules.md`)
(c) a pullback = any run of consecutive lower-closing candles
**Default:** (a), N swept.
**Answer:**

### GB-Q8 — MS low: lowest low of which span? (P1)
**Watch:** 01:21:30–01:22:10 ("we will take the lowest point of this point in general… we don't do this, but we do this").
**What the screenshot shows:** line at the pullback's swing low (~29,640), not at the valid candle.
**Question:** is MS low (a) the lowest low of the whole pullback that contains the valid candle, or (b) the lowest low from the valid
candle up to the breakout, or (c) the valid candle's own low?
**Default:** (a).
**Answer:**

### GB-Q10 — MS high (target ceiling): which high? (P1)
**Watch:** 01:03:37–01:04:15 ("we can take each trade till this level… the market structure high"), 01:27:25–01:27:40, and the line at
~29,966 in 230055.
**Question:** is it (a) the swing high made just before the pullback into the previous VA, (b) the highest high of the current session,
(c) the highest high of the whole trend leg, (d) found with the mirror of the valid-candle rule?
**Default:** (a).
**Answer:**

### GB-Q11 — Trend filter: exact test (P1)
**Watch:** 01:00:35–01:01:30 (whiteboard: two profiles, "the value area is higher"), 01:19:36–01:20:10 ("we have a shift of the value
area… we classify this as a trending long market"), and 222143 (daily VA staircase).
**Question:** what counts as "shifted up"?
**Options:** (a) developing VAH > previous VAH **and** developing VAL > previous VAL (overlap allowed)  (b) developing POC > previous VAH
(no overlap)  (c) developing POC > previous POC  (d) the whole developing VA above previous VAH.
**Default:** (a). (The chart shows overlapping VAs still counted, which rules out (d).)
**Answer:**

### GB-Q27 — When is the trend filter evaluated? (P1)
**Problem:** early in a session the developing value area is tiny and jumps around. If the pullback-and-trigger happens in the first
hour, the "developing VA" barely exists.
**Options:** (a) at the trigger candle, using the developing VA at that moment  (b) use the last two **completed** sessions (yesterday's
VA above the day before's) and today is the day we trade the pullback into yesterday's VA  (c) require N hours of developing session
first.
**Default:** (b) — it matches "previous VA" as the pullback zone and avoids the tiny-VA problem. Check the NQ example: is the trigger in
the session *after* the shift (b) or the same session (a)?
**Answer:**

### GB-Q12 — "MS low broken" means what? (P1)
**Watch:** 01:11:30–01:11:50, 01:05:07–01:05:20.
**Options:** (a) any trade below the MS low  (b) a 15m close below it  (c) close below by ≥ threshold.
**Default:** (a) trade-through.
**Answer:**

### GB-Q28 — Previous VA changes at the session rollover during a setup (P2)
**Problem:** a pullback can start in one session and trigger in the next. At rollover, "previous session VA" becomes a different value
area.
**Options:** (a) always use the VA of the session immediately before the trigger candle's session  (b) lock the VA at the moment the
pullback first touches it.
**Default:** (a).
**Answer:**

### GB-Q29 — More than one setup per session? (P2)
**Problem:** after an MS-low break, or after reaching the MS high, can a new setup form in the same session using the same previous VA?
**He said:** after the MS high, "we need to wait for another session that will return in this kind of value" [01:10:44].
**Options:** (a) one setup per session  (b) a new setup whenever a fresh pullback into the previous VA happens with a new intact MS low.
**Default:** (a) after the MS high is reached; (b) is unclear after an MS-low break.
**Answer:**

---

## C. Entry, exit, re-entry

### GB-Q13 — News rule scope (P2)
**Watch:** 01:17:00–01:17:25.
**He said:** "NFP, CPI, some kind of news during the trigger… one filter I made is does my strategy perform good in a day with lots of
news. The result is no."
**Options:** (a) skip triggers within ±N minutes of a high-impact release  (b) skip the whole day of NFP/CPI/FOMC  (c) both, tested.
Also: which events count?
**Default:** (a) ±15 min, events = NFP, CPI, FOMC; test (b) too.
**Answer:**

### GB-Q14 — Stop placement detail (P3)
**Watch:** 01:05:59–01:06:10, 01:27:39, and 225939 (stop 29,752.75 — compare with the trigger candle's low).
**Question:** exactly at the trigger candle's low, or one tick below?
**Default:** exactly at the low (FLOW's stop logic may add a tick; decide).
**Answer:**

### GB-Q15 — Minimum R:R when the MS high is close (P2)
**Watch:** 01:11:05–01:11:24 ("we can open a trade also with a less risk-reward").
**Question:** is there a floor? A ceiling 0.3R away makes a near-zero-reward trade.
**Options:** (a) no floor  (b) skip if ceiling < 1R  (c) floor swept.
**Default:** (b) 1R, swept.
**Answer:**

### GB-Q16 — Re-entry while a trade is still open? (P2)
**Watch:** 01:08:30–01:09:30, 01:28:20–01:28:50, 230055.
**What's shown:** both examples hit target before the next entry.
**Options:** (a) re-entries only when flat  (b) stacking allowed (needs the size cap raised).
**Default:** (a) — forced anyway by FLOW's 1-contract cap.
**Answer:**

### GB-Q17 — Does a re-entry need its own VAH margin? (P2)
**Question:** re-entry = engulf + volume only, or must it also close ≥ threshold above the previous VAH?
**Default:** engulf + volume only.
**Answer:**

### GB-Q30 — Holding overnight / end-of-session exit (P1)
**He said:** nothing.
**Question:** is an open trade held through the session close (17:00 CT halt) and into the next session, or flattened at session end?
**Options:** (a) hold until stop/target/MS high  (b) flatten at session end  (c) no new entries in the last N minutes of the session.
**Default:** (b) flatten at session end — FLOW's existing session handling; test (a).
**Answer:**

### GB-Q31 — Does price need to have traded above the previous VAH before the pullback? (P2)
**Problem:** "pull back into the previous VA" implies price was above it first. If a session opens inside yesterday's VA, is that a
"pullback"?
**Options:** (a) price must have traded above previous VAH earlier in the setup  (b) any time price is in the previous VA counts.
**Default:** (a).
**Answer:**

### GB-Q32 — Pullback depth (P3)
**Question:** does price have to reach a certain depth inside the previous VA (e.g. POC), or is a touch of VAH enough?
**He said:** "price entered here also with a wick is okay" [01:23:38].
**Default:** a touch at or below previous VAH.
**Answer:**

---

## D. Score and sizing

### GB-Q18 — Volume point in the meta-label: reducing volume only, or combination? (P2)
**Watch:** 00:53:10–00:53:35 ("this is actually the best setup… we will call it reducing volume versus low effort"), 00:57:20–00:57:35
("is in a reducing volume, yes we add a point"), 01:06:15–01:06:25 ("combination").
**What the screenshot shows:** "Reducing volume ✓", "Low effort" unticked.
**Options:** (a) point if (a)-variant holds, regardless of MA  (b) point only if both (a) and (b) hold.
**Default:** (a).
**Answer:**

### GB-Q19 — When is the regime read? (P3)
**Options:** (a) ATR percentile at the trigger candle  (b) once per day at the session open ("every day when you open your chart").
**Default:** (a).
**Answer:**

### GB-Q20 — BOCPD details (P2)
**Watch:** 00:47:20–00:49:00 (board), and any frame where the BOCPD panel/settings are readable (222143, 222257, 222517).
**Known:** hazard 2%, monitors volatility, mean regime 50 bars, age in bars.
**Open:** which volatility series (ATR? |returns|? bar range?); what happens above 100 bars ("0–100" on the board — capped at 100, or
treated as OLD?); the indicator's full name (panel shows "AE - BCP…").
**Default:** our own BOCPD on 15m log-range, hazard 1/50; age > 100 = OLD.
**Answer:**

### GB-Q21 — "New York session" hours for the score (P2)
**Watch:** 00:57:40–00:58:12.
**Options:** (a) RTH 09:30–16:00 ET  (b) 08:00–17:00 ET  (c) 08:20–13:30 ET (COMEX gold pit hours).
**Default:** (b), and check per-hour expectancy in backtest.
**Answer:**

### GB-Q22 — Size for score 1 and 0 (P2)
**Watch:** 00:59:00–00:59:50 ("also if I have one to five, I will take the trade but I will risk so less").
**Options:** for 1/5: ⅛ or ⅒ Kelly. For 0/5: same as 1/5, or skip.
**Default:** 1/5 = ⅛ Kelly, 0/5 = skip.
**Answer:**

### GB-Q23 — How does Kelly sizing map onto FLOW's 1-contract cap? (P1 for us)
**Problem:** `config/risk.json` allows max 1 contract. Fractional Kelly then only means 1 or 0.
**Options:** (a) ignore sizing; trade every setup at 1 lot, use the score only for analysis  (b) score threshold — trade only setups with
score ≥ K  (c) raise the cap for this strategy (a separate risk decision)  (d) backtest with fractional size in R, live with 1 lot.
**Default:** (d) — backtest reports both flat-1R and Kelly-weighted R; live stays at 1 lot.
**Answer:**

### GB-Q24 — Autocorrelation 0–10 formula (P2)
**Watch:** 00:43:39–00:45:20 (board: 40% unconditional, "COWR", W‑L‑L‑W‑W‑L, 2/6).
**He said:** "a compare of two models, conditional and unconditional rate"; also "search for an online calculator".
**Options:** (a) lag-1 autocorrelation of the ±1 outcome series, scaled to 0–10  (b) |P(W|W) − P(W)| or |P(W|L) − P(W)| scaled  (c) a
runs test (Wald–Wolfowitz) z-score mapped to 0–10.
**Default:** (b) with a significance check; record the raw numbers so the mapping can change later.
**Answer:**

### GB-Q25 — Streak rule: which trades are skipped? (P2)
**Watch:** 00:37:30–00:39:55 (board: W→W—W‑L‑(L)‑L with the second L circled; L‑L‑L‑W‑(W) circled).
**He said, contradictorily:** "when I switch to a loss, I won't take this trade" / "this one yes I take, but it's the worst setup" /
"I don't take this trade never… of course this I take, because I need to wait for the actual reversion of the streak".
**Reading in the strategy file:** skip only the trade right after the first loss that ends a winning streak; take later losing-streak
trades at the lowest size.
**Options:** (a) as above  (b) skip every trade after a loss until a win (needs paper-tracking outcomes to know when the win comes)
(c) don't skip anything; streak only affects size.
**Default:** (a).
**Answer:**

### GB-Q33 — Kelly inputs: over which window? (P3)
**Question:** win rate and payoff for Kelly — whole backtest, rolling last N trades (60? 100?), or per score bucket?
**Default:** rolling last 100 trades, per the "re-check every 100 trades" rule.
**Answer:**

---

## E. Data / testing

### GB-Q26 — Is the 1-minute volume-profile approximation good enough? (P1)
**Problem:** our history is 1-minute OHLCV; a true profile needs volume at each price. Approximation = spread each 1m bar's volume
evenly over its high–low range.
**Question:** accept after checking it against MotiveWave's tick-built profile on the recorded days in `data/`? What error is
acceptable?
**Options:** (a) accept if VAH/VAL within ±2 ticks on most days  (b) accept and make the threshold large enough to swamp the error
(c) don't accept; record tick data first.
**Default:** (a), measured before the backtest is trusted.
**Answer:**

### GB-Q34 — In-sample / out-of-sample split for GC (P2)
**Problem:** "best regime / freshness / session" (score points) must be learned on one period and tested on another (his §9.1 rule).
**Options:** (a) 2021-10 → 2024-12 in-sample, 2025-01 → 2026-10 out-of-sample  (b) walk-forward by year.
**Default:** (a).
**Answer:**

---

## F. Index

| ID | Topic | Priority | Section in strategy file |
|---|---|---|---|
| GB-Q1 | Threshold unit | P1 | §3.4 |
| GB-Q2 | Threshold value for GC | P1 | §3.4 |
| GB-Q3 | Session boundary | P1 | §2.2 |
| GB-Q4 | Value-area % | P3 | §2.2 |
| GB-Q5 | SMA vs EMA | P3 | §2.3 |
| GB-Q6 | Reducing volume: 1 bar or more | P2 | §3.5 |
| GB-Q7 | Pullback / break of structure | P1 | §3.6 |
| GB-Q8 | MS low span | P1 | §3.6 |
| GB-Q9 | Valid candle strictness | P3 | §3.1 |
| GB-Q10 | MS high | P1 | §3.7 |
| GB-Q11 | Trend filter test | P1 | §4.1 |
| GB-Q12 | MS low "broken" | P1 | §4.3 |
| GB-Q13 | News scope | P2 | §4.4 |
| GB-Q14 | Stop at low or one tick below | P3 | §5.3 |
| GB-Q15 | Minimum R:R under ceiling | P2 | §5.5 |
| GB-Q16 | Re-entry while open | P2 | §5.6 |
| GB-Q17 | Re-entry VAH margin | P2 | §5.6 |
| GB-Q18 | Volume point in score | P2 | §7 |
| GB-Q19 | When regime is read | P3 | §6.1 |
| GB-Q20 | BOCPD details | P2 | §2.5, §6.2 |
| GB-Q21 | NY hours | P2 | §6.3 |
| GB-Q22 | Size for score 1 and 0 | P2 | §8.1 |
| GB-Q23 | Kelly vs 1-contract cap | P1 | §8.1 |
| GB-Q24 | Autocorrelation formula | P2 | §8.2 |
| GB-Q25 | Streak skip rule | P2 | §8.3 |
| GB-Q26 | 1m volume-profile approximation | P1 | §11 |
| GB-Q27 | When the trend filter is evaluated | P1 | §4.1 |
| GB-Q28 | Previous VA at rollover | P2 | §4 |
| GB-Q29 | Setups per session | P2 | §5.7 |
| GB-Q30 | Overnight hold / session-end exit | P1 | §5 |
| GB-Q31 | Must price be above VAH first | P2 | §4.2 |
| GB-Q32 | Pullback depth | P3 | §4.2 |
| GB-Q33 | Kelly input window | P3 | §8.1 |
| GB-Q34 | IS/OOS split for GC | P2 | §9.1 |
