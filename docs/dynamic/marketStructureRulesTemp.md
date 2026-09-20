# Market Structure Rules — TEMPORARY, ambiguities resolved by best judgment

**SUPERSEDED 2026-09-20 — kept only as historical record, do not build
against this file anymore.** The user has now reviewed
`marketStructureRules.md` directly (`market_structure_review.md`), and
the real answers are folded into that file's own text (each marked
`REVIEWED 2026-09-20`). **5 of the 7 guesses below turned out wrong**:
points 1 (CHOCH anchor — this file guessed "close of first candle,"
actual answer is "at init time, open-or-live-price"), 2's N>2 case (this
file guessed "anchored to the run's first candle," actual answer is
"sliding consecutive-pair"), 4 ("1% of what" — this file guessed
high-low range, actual answer is body height), 5 (flip-watch's next
reference — this file guessed SBR/RBS, actual answer is DT/DB), and 6
(DT/DB window — this file guessed "from the A+ candle," actual answer is
"from the SBR/RBS candle"). Only points 3 and 7 turned out to match this
file's guesses. `MarketStructureFeature.java` (D-60) and
`MarketStructureFeatureTest` (D-68) still implement/test THIS file's
(wrong) guesses as of 2026-09-20 — see `marketStructureRules.md`'s own
closing section and `decisions.md` for the pending rework.

---

**Original framing below, preserved verbatim as the historical record of
what was actually built against, not updated to match the real
answers:**

**Status: unreviewed by the user.** `marketStructureRules.md` (the
original distillation, all 7 ambiguities left open) stays as the
source of truth pending review. This file exists only so implementation
could proceed now, per the user's explicit instruction ("fill the
ambiguity with whatever u feel good and move ahead"). **Every
resolution below is a guess, not a confirmed answer** — flagged in
`todo.md` as an important point to come back to. When the user reviews
`marketStructureRules.md`, any answer that contradicts what's below
means the implementation built against this file needs to change, not
just the doc.

Only the 7 resolved points are covered here — everything else is
identical to `marketStructureRules.md`, not repeated.

---

## Resolutions

**1. CHOCH point — which OHLC value of the first candle?**
→ **Close.** Every other rule in this system (pullback validation,
continuation, flip confirmation) keys off candle close, never a wick or
open value — using close for CHOCH too is the only internally
consistent choice among the four candidates.

**2. 2-candle pullback check past a 3rd+ consecutive counter-trend candle**
→ **Anchored to the run's first candle, not sliding.** Once a
counter-trend run starts, its first candle is the fixed reference for
the whole attempt. Every later candle in the run is checked against
that *same* first candle's low (uptrend) / high (downtrend) — not
against whichever candle immediately preceded it. Simpler than a
sliding window, and it gives §4's "highest/lowest candle of the
pullback" a stable, well-defined starting point.

**3. Does a valid pullback's candle range keep growing until continuation?**
→ **Yes.** The pullback's candle set is the *entire* counter-trend run,
from its first candle through to whichever candle immediately precedes
confirmed continuation (§3). This is necessary for "highest/lowest
candle of the pullback" to mean anything for a pullback longer than 2
candles.

**4. "1% of what"?**
→ **1% of that candle's own high-low range** (`high - low` on that one
candle), not the price level and not a fixed amount. Scale-invariant
(works the same on a $2 stock and a $500 index) and needs no external
volatility measure (no ATR, nothing beyond the candle itself). Applied
uniformly to TJL1/TJL2 (§4) and DT/DB (§6).

Sub-resolution for zone direction, since "extends 1% above/below the
body" can land on either side of the wick extreme depending on how big
the body-to-wick gap already is: **a zone is always constructed as
`[min(wickPoint, bodyEdge ± offset), max(wickPoint, bodyEdge ± offset)]`**
— i.e., take the wick point and the offset-from-body point, and span
between whichever is smaller and whichever is larger. Guarantees a
valid (non-inverted) zone in every case rather than assuming the offset
point always lands on the "expected" side.

**5. What does flip-watch key off right after the first flip, before a
new real TJL pair exists in the new trend?**
→ **The newly-assigned SBR/RBS zone plays exactly the role CHOCH played
initially.** `lastTJL2` (the value flip-watch always reads) is set
directly to the new SBR/RBS zone at the moment of the flip. Clean and
requires no special-casing: SBR/RBS already occupies the structural
"far-side/invalidation boundary" role by construction (§4's own named
invariant), which is exactly what `lastTJL2` always means.

**6. "Highest/lowest point after A+" — over what window?**
→ **From the A+ candle itself through the flip-confirming candle,
inclusive.** A+ is always the specific candle that produced the
*previous* TJL1 (its own high or low extreme, whichever direction
formed that zone) — not just "the TJL1 zone" as a price range. The
scan for DT/DB's own extreme runs from that exact candle forward,
through and including the second consecutive close that confirmed the
flip.

Implementation note this resolution requires: the feature must retain
a reference to *which candle* produced the current TJL1 (not just the
resulting price zone), and must accumulate every bar from that candle
forward for as long as that TJL pair stands, precisely so this window
is available if a flip happens later. Reset only when a new TJL pair
actually forms.

**7. Is CHOCH ever referenced again after the first real TJL pair forms?**
→ **No, never again — permanently retired the first time a real pair
forms**, including across any number of future flips (resolution #5's
SBR/RBS-as-TJL2 mechanism handles every flip after the first
unconditionally; there is no scenario that falls back to CHOCH a second
time).
