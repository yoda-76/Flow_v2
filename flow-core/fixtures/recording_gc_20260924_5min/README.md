# Recording — @GC, 2026-09-24 ~01:37 AM, first 5 minutes

A real live dry-run session (`level_zone_observer`, Armed off, Simulated
account), trimmed to its first 5 minutes. Made to test replay (D-88).

- `raw.jsonl` / `decisions.jsonl` — the session's journals, cut at the same
  clock event (seq 4750). `data/` — what the per-construct recorder wrote
  (`footprint`, `vwap`, `liquidity_map`; 1 s). `vp_live_samples.log` — the two
  lines the live volume-profile feature logged inside the window.
- **No big trades** in this window (quiet night, nothing ≥10 contracts), so
  big-trade recording is not exercised by this fixture.
- **Predates `price_anchor`**: the session's tick anchor was not journaled,
  so `RecordingReplayTest` derives it from the first footprint candle.
  Recordings made after 2026-09-24's `price_anchor` change carry it.
- Market structure was warm-started from 100 historical bars at activation
  (`market_structure_warm_start` in `decisions.jsonl`); those bars are **not**
  in `raw.jsonl`, so market structure is not replay-exact from this file.
- The liquidity map's depth is not in `raw.jsonl` (top-of-book only, D-58),
  so it cannot be replayed; the recorded `data/liquidity_map/` is the record.
