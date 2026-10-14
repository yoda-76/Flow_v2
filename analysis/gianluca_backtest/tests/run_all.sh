#!/usr/bin/env bash
# Run every Gianluca backtest test file. Usage: bash analysis/gianluca_backtest/tests/run_all.sh
set -e
cd "$(dirname "$0")"
for f in test_core.py test_signals.py test_sizing.py test_runner.py; do
  echo "== $f"
  python3 "$f" 2>&1 | tail -3
done
