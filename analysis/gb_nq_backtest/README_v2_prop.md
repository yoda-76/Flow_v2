# VaBreakMomentum v2 — backtest + Lucid prop replay

Files (this folder):
- `va_break_momentum.py` — Python port of the `rq_sdk` strategy (v1/v2/v3), backtested on the 1m NQ CSV. Reads only.
- `lucid_replay.py` — replays a `trades.csv` through LucidPro 25K/50K evaluation rules (pass/breach chain).

Which version matches the other platform: **v2**. 20/20 of its first trades matched entry time, price and outcome
(2026-10-31). v1 (entry window, flatten, breakeven, partial) is a different configuration and did not match.

## Commands (run from `FLOW_V2/`)

```
# MNQ, $50k account, 1 contract, $1 round-trip commission, last 365 days
python analysis/gb_nq_backtest/va_break_momentum.py --version v2 --days 365 --mult 2 --equity 50000 \
    --comm 1.0 --max-contracts 1 --out reports/vab_m50_1c

# all data (Aug 2019 -> latest)
python analysis/gb_nq_backtest/va_break_momentum.py --version v2 --days 2700 --mult 2 --equity 50000 \
    --comm 1.0 --max-contracts 1 --out reports/vab_m50_1c_all

# Lucid replay
python analysis/gb_nq_backtest/lucid_replay.py --trades reports/vab_m50_1c_all/v2/trades.csv --plan 50k
```

Options added 2026-10-31: `--comm` (USD round trip per contract, default 4.60), `--max-contracts` (0 = strategy default),
`--leverage` (notional / equity cap, 0 = off). Defaults leave earlier results unchanged.

## Assumptions to remember
- Prices are **NQ** 1m bars; MNQ is `--mult 2`. Volume is NQ volume. No MNQ CSV yet.
- 15-minute bars are an assumption (the source never says).
- Slippage 1 tick each way. Commission: user's real cost is $1 round trip per MNQ (`--comm 1.0`).
- v2 sizing is 0.5% risk x score factor with a 1-contract floor, so a few trades size above 1 unless `--max-contracts 1`.
- The CSV does not look roll-adjusted (extra trades on 2025-12-16 vs the other platform). Not yet fixed.
- Lucid numbers are from third-party sites; the 50K lock level is a guess. See `lucid_replay.py` docstring.
- 2019 data is sparse (~30% of expected bars).

## Reference results (v2, MNQ, 1 contract, $1 commission, all data)
477 trades, PF 1.04, +0.025R/trade, net +$4,524. Lucid 25K: 6 passes / 7 breaches. Lucid 50K: 1 pass / 1 breach / 1 open.
