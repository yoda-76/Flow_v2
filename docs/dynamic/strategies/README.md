# Strategy research logs

One file per strategy, named after the strategy (snake case). Each file is the single place to trace what was tested, with what data and
settings, what came out, and what is still open. **Update the file whenever a new test is run** (new entry at the bottom of its research
log, then fold the conclusion into the findings section and refresh the status line and date).

Entry tags: `[BACKTEST]` a run of our Python port on recorded data, `[PROP-SIM]` a prop-firm account simulation on backtest trades,
`[DATA]` a check of the input data, `[CODE]` a change to the tooling. Platform-behaviour evidence still goes to
`../findings.md` / the MotiveWave repo; decisions go to `../decisions.md`.

| Strategy | File | Status |
|---|---|---|
| Daily Anchored VWAP Signal Strategy (MES) | [daily_anchored_vwap.md](daily_anchored_vwap.md) | research in progress, 2026-10-31 |
| Matteo Conti 1: Vault Break (NQ, 30m, long) | [vault_break.md](vault_break.md) | real edge; in portfolio S1 + S3; funded phase simulated, 2026-11-02 |
| Matteo Conti 2: VWAP Pullback + ADX Gate (NQ, 1m, long) | [vwap_pullback_adx.md](vwap_pullback_adx.md) | edge unconfirmed; left out of the portfolio, 2026-11-02 |
| Matteo Conti 3: Overnight-Bias ORB (NQ, 15m, long/short) | [overnight_bias_orb.md](overnight_bias_orb.md) | real edge; in portfolio S1 + S3; funded phase simulated, 2026-11-02 |

Other strategies tested in the same session but not yet written up here: VWAP/EMA9 cross 3m (NQ/gold), value-area break momentum (NQ),
gap + late-day strength 5m (MNQ), Friday-low Monday revisit (gold/NQ), trend following and contrarian (gold). Their numbers are in the
chat history of 2026-10-31 and the scripts under `analysis/gb_nq_backtest/`; write them up here when we return to them.
