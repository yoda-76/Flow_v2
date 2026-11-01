# Per-strategy parameter files

`<strategyId>.json`, read by the strategy at activation (FlowRuntimeStudy passes the path as `strategyConfigPath`).
Every key is optional; missing keys keep the defaults documented in each strategy's javadoc (the fixed base configs
in `docs/dynamic/strategies/`). A malformed file stops the strategy at start-up instead of running on defaults.

`skipDates` (comma-separated CT dates): no new entries that day. The Python backtests skip CME holidays and early-close
days by looking ahead in the data (no bar at/after 14:30 CT); live code cannot look ahead, so the dates are listed here.
**The dates below were written from the usual CME equity-index holiday schedule and are UNVERIFIED — check them against
CME's published holiday calendar and extend the list each year.** The Daily Anchored VWAP strategy has no holiday rule
in its backtest, so it has no file.
