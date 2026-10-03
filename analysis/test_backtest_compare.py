#!/usr/bin/env python3
"""Tests for analysis/backtest_compare.py. Plain unittest, no third-party packages.

Run:  python analysis/test_backtest_compare.py
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backtest_compare as bc  # noqa: E402


def write_run(path: Path, strategy_id, params, generated_at_ms, trades=10, wins=6, total_r=5.0):
    header = {"type": "backtest_run", "strategyId": strategy_id, "csvPath": "GC_1m_latest.csv", "tickSize": 0.1,
              "barCount": 1000, "firstBarMs": 0, "lastBarMs": 1000, "params": params, "risk": {},
              "generatedAtMs": generated_at_ms}
    summary = {"type": "backtest_summary", "trades": trades, "wins": wins, "losses": trades - wins,
               "totalR": total_r, "avgR": total_r / trades, "maxDrawdownR": 2.0,
               "deniedDailyLoss": 0, "deniedMaxReversals": 0, "deniedRateLimit": 0, "deniedMinDwell": 0,
               "killSwitchTrips": 0}
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(header) + "\n")
        f.write(json.dumps(summary) + "\n")


class RenderComparisonTest(unittest.TestCase):
    def test_two_runs_both_appear(self):
        with tempfile.TemporaryDirectory() as d:
            p1, p2 = Path(d) / "a.jsonl", Path(d) / "b.jsonl"
            write_run(p1, "market_structure_backtest", {"rr": "2.0"}, 1000, total_r=5.0)
            write_run(p2, "market_structure_backtest", {"rr": "3.0"}, 2000, total_r=-1.0)
            import backtest_report as br
            runs = [br.load_run(p1), br.load_run(p2)]
            text = bc.render_comparison(runs)
            self.assertIn("rr=2.0", text)
            self.assertIn("rr=3.0", text)

    def test_newest_first_ordering(self):
        with tempfile.TemporaryDirectory() as d:
            p1, p2 = Path(d) / "old.jsonl", Path(d) / "new.jsonl"
            write_run(p1, "s1", {}, 1000)
            write_run(p2, "s2", {}, 5000)
            import backtest_report as br
            runs = [br.load_run(p1), br.load_run(p2)]
            text = bc.render_comparison(runs)
            # "s2" (newer) should appear before "s1" (older) in the rendered table
            self.assertLess(text.index("| s2 "), text.index("| s1 "))


class MainTest(unittest.TestCase):
    def test_no_args_returns_2(self):
        self.assertEqual(bc.main([]), 2)

    def test_missing_file_is_skipped_not_fatal(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "real.jsonl"
            write_run(p, "s1", {}, 1000)
            rc = bc.main([str(p), str(Path(d) / "does_not_exist.jsonl"), "--no-file"])
            self.assertEqual(rc, 0)

    def test_glob_and_file_output(self):
        with tempfile.TemporaryDirectory() as d:
            p1 = Path(d) / "run_a.jsonl"
            p2 = Path(d) / "run_b.jsonl"
            write_run(p1, "s1", {}, 1000)
            write_run(p2, "s2", {}, 2000)
            out = Path(d) / "cmp.md"
            rc = bc.main(["--glob", str(Path(d) / "*.jsonl"), "--out", str(out)])
            self.assertEqual(rc, 0)
            self.assertTrue(out.is_file())
            text = out.read_text(encoding="utf-8")
            self.assertIn("s1", text)
            self.assertIn("s2", text)

    def test_all_files_invalid_returns_2(self):
        with tempfile.TemporaryDirectory() as d:
            bad = Path(d) / "bad.jsonl"
            bad.write_text(json.dumps({"type": "backtest_trade"}) + "\n", encoding="utf-8")
            rc = bc.main([str(bad), "--no-file"])
            self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
