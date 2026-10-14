#!/usr/bin/env bash
# Start runner.py --resume detached (survives the chat/SSH closing), at nice +10, logging to runner_logs/runner.log.
# The runner exits by itself if another copy is alive (lock file) or the state isn't a compute phase.
cd "$(dirname "$0")/../.."
mkdir -p reports/gianluca_backtest/runner_logs
setsid nohup nice -n 10 python3 analysis/gianluca_backtest/runner.py --resume \
  >> reports/gianluca_backtest/runner_logs/runner.log 2>&1 < /dev/null &
echo "started pid $!"
