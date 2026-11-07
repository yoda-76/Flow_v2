# Chart visualization (offline)

Builds multi-timeframe candle data from the 1-minute gold CSV and a static page (TradingView Lightweight Charts 5.2.1) to browse it.
Read-only over the CSV; writes only under `reports/viz/` (gitignored). Pure standard library. Places no orders.

## Run

```
python analysis/visualization/viz_build.py            # defaults: GC, tick 0.1, analysis/data/GC_1m_latest.csv -> reports/viz
python -m unittest analysis/visualization/test_viz_build.py
```

Then open `reports/viz/GC/index.html` in a browser (works from `file://`; internet needed for the CDN chart script).
Options: `--csv --symbol --tick --out --days-1m --days-5m --days-15m`.

## Timeframes and history

| TF | Bucket | History kept (back from last bar) |
|---|---|---|
| 1m | raw bars | 60 days |
| 5m | UTC epoch floor | 365 days |
| 15m | UTC epoch floor | 1095 days |
| 1h, 4h | UTC epoch floor | everything |
| 1D | CME session | everything |

**1D = CME trading session**: starts 17:00 US Central (DST computed by hand, 2007+ US rules) and belongs to the next calendar day, so Sunday 17:00 CT begins Monday's bar. The bar's time is the UTC time of that 17:00 CT start.

Input is sorted and de-duplicated by timestamp (last row wins). Output per timeframe is `data_<tf>.js` (loaded lazily by the page), plus `meta.js` and `index.html`. Times are UTC seconds; the page converts to the selected timezone for display only.

## RR (risk/reward) blocks

An overlay primitive (`rr_block.js`, copied next to `index.html`) draws entry/stop/target boxes on the candles: green reward zone, red
risk zone, entry line, and labels (`Target … +pts (R)`, `Stop … −pts`, `Long|Short entry RR x`). Blocks are stored in UTC seconds and
mapped onto whatever timeframe is showing, so they survive timeframe switches; the price axis autoscales to keep a block fully visible.
Labels shrink or hide when the block is too narrow (< 60 px hides all text).

- **Manual**: top bar `RR: off` cycles `off -> long -> short -> off`. With long/short on, click the chart to place a block: entry = clicked
  price snapped to the tick, 30 bars wide, stop distance = max(20 ticks, average true range of the 14 bars before the click), target =
  2x risk, label `manual`. With the tool off, clicking a manual block asks to remove it. `Clear RR` removes all manual blocks.
  Manual blocks persist per symbol in the browser's localStorage (page works without it).
- **From a file**: `python analysis/visualization/viz_build.py --overlays analysis/visualization/overlays_example.json` writes `overlays.js`
  next to `index.html`. The file is a JSON array of `{id, side, start, end, entry, stop, target, label?}`; `start`/`end` may be ISO strings
  (`"2026-10-14T13:00:00Z"`), UTC ms or UTC seconds. Long needs `stop < entry < target`, short `target < entry < stop`, and `start < end`;
  an invalid block prints a warning and is skipped. Without `--overlays` an empty `overlays.js` is written. Overlay blocks are read-only in the page.

## Session volume profiles (Gianluca Bruni "SVP HD")

`viz_build.py` also writes `profiles.js` (disable with `--no-profiles`) and the page draws it with `vp_profile.js`; the **VP**
button toggles it. NQ example (the GC data builds the same way, default `--tick 0.1`):

    python analysis/visualization/viz_build.py --csv analysis/data/NQ_1m_latest.csv --symbol NQ --tick 0.25

- **One profile per Globex session**, 17:00 CT = 18:00 ET (GB-Q3 default (a)); a completed session with fewer than 300
  1m bars is skipped (holiday stubs, same validity rule as `gb_backtest.py`); the last session is kept as "developing".
- **Maths = `gb_nq_backtest/gb_backtest.py`'s `build()`**: each 1m bar's volume spread evenly over its high-low range at tick
  resolution; POC = busiest level; value area grows from the POC two ticks at a time toward the heavier side until it holds
  `--va-pct` (default 0.70, GB-Q4) of the session. A test pins the output equal to `gb_backtest.build()`.
- **Drawing**: histogram from the session start (blue = inside the value area, grey = outside, orange = POC row), POC (solid)
  and VAH/VAL (dashed) across the session; a completed session's three lines are carried through the **next** session
  (fainter) = the "previous value area" the strategy trades against.
- It is a 1-minute approximation (no tick data), as the strategy doc §387 notes; TradingView's SVP HD will differ slightly.

## Gianluca pre-signal panel ("GB info", top right)

`--indicators` (or `--gb-trades <trades.csv>`, which implies it) writes `indicators.js`; the **GB info** button shows/hides the
panel. It reads the 15m bar that has *closed* by the end of the bar under the cursor (last bar when the cursor is off the chart):

| Row | Value shown | Preferred (his, from `gianlucaBruniStrategy.md` §6-§8) |
|---|---|---|
| Regime freshness | BOCPD regime age in 15m bars + FRESH / MATURE / OLD | FRESH, age 0-30 |
| Volatility regime | ATR(14) percentile over 200 15m bars + LOW / MEDIUM / HIGH / EXTREME | HIGH, percentile 51-75 |
| Price efficiency | reducing volume (vol < previous bar) / low-effort only (vol < MA20) / not reducing | reducing volume |
| Autocorrelation | memory strength 0-10, last 5 trade outcomes, and whether the next trade is BEST / SKIP / normal | next trade after L-L-L-W with strength >= 3; skip after W-W-W-L |

- BOCPD, the volume SMA and the autocorrelation strength formula are the backtest's own (`gb_backtest.py`: imported, not
  copied, except the 10-line strength formula in `ac_strength`, which a test pins to the same behaviour). Verified on NQ: for
  the 86 backtest trades whose trigger bar lies in the 3-year 15m window, age / reducing / low-effort equal `trades.csv`'s
  columns on every one, and the strength before each of the 212 trades equals its `strength` column.
- Autocorrelation needs a trades file (`reports/gb_nq/trades.csv` is the baseline NQ run); without one the row says n/a.
- The 0-10 strength scale is the backtest's guess (GB-Q24, the video gives no formula).

## Backtest trades as RR blocks

`--gb-trades reports/gb_nq/trades.csv` also draws every trade in that file as an RR block (entry, stop, target as traded; the
target is the MS-high cap where that was hit; the block runs from entry to exit; label `#n  net R  exit reason`). The baseline
`reports/gb_nq/trades.csv` is the run with HIS numbers (6-point threshold, 2R, ATR 14/200, hazard 2%, 70% value area, volume SMA 20)
before any optimisation; its gaps-filled-by-us choices are listed in the plan. `--no-gb-blocks` skips the blocks. Only trades
inside the 15m window (3 years) are on screen; zoom in, NQ risks are small (about 15-30 points on a 30,000 chart).
