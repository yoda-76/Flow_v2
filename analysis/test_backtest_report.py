#!/usr/bin/env python3
"""Tests for analysis/backtest_report.py. Plain unittest, no third-party packages.

Run:  python analysis/test_backtest_report.py
"""

import csv
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backtest_report as br  # noqa: E402

UTC = timezone.utc


def utc_ms(y, mo, d, h=12, mi=0):
    return int(datetime(y, mo, d, h, mi, tzinfo=UTC).timestamp() * 1000)


def write_run(path: Path, header: dict, trades: list, summary: dict):
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps({"type": "backtest_run", **header}) + "\n")
        for t in trades:
            f.write(json.dumps({"type": "backtest_trade", **t}) + "\n")
        f.write(json.dumps({"type": "backtest_summary", **summary}) + "\n")


def sample_trade(i, r=1.0):
    return {
        "entryTimeMs": 1_700_000_000_000 + i * 60_000,
        "entryPrice": 100.0 + i,
        "direction": 1 if i % 2 == 0 else -1,
        "stopPrice": 95.0 + i,
        "targetPrice": 110.0 + i,
        "exitTimeMs": 1_700_000_060_000 + i * 60_000,
        "exitPrice": 105.0 + i,
        "exitReason": "target" if r > 0 else "stop",
        "rMultiple": r,
    }


class LoadRunTest(unittest.TestCase):
    def test_parses_header_trades_summary(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "run.jsonl"
            write_run(p,
                      {"strategyId": "market_structure_backtest", "csvPath": "x.csv", "tickSize": 0.1,
                       "barCount": 100, "firstBarMs": 1_700_000_000_000, "lastBarMs": 1_700_006_000_000,
                       "params": {"rr": "2.0"}, "risk": {"dailyLossLimitTicks": 200}, "generatedAtMs": 1_700_010_000_000},
                      [sample_trade(0), sample_trade(1, r=-1.0)],
                      {"trades": 2, "wins": 1, "losses": 1, "totalR": 0.0, "avgR": 0.0, "maxDrawdownR": 1.0,
                       "deniedDailyLoss": 0, "deniedMaxReversals": 0, "deniedRateLimit": 0, "deniedMinDwell": 0,
                       "killSwitchTrips": 0})
            run = br.load_run(p)
            self.assertEqual(run["header"]["strategyId"], "market_structure_backtest")
            self.assertEqual(len(run["trades"]), 2)
            self.assertEqual(run["summary"]["trades"], 2)

    def test_missing_header_or_summary_raises(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "bad.jsonl"
            p.write_text(json.dumps({"type": "backtest_trade", **sample_trade(0)}) + "\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                br.load_run(p)


class RenderTest(unittest.TestCase):
    def _run(self, n_trades, **summary_overrides):
        header = {"strategyId": "market_structure_backtest", "csvPath": "GC_1m_latest.csv", "tickSize": 0.1,
                  "barCount": 1000, "firstBarMs": 1_700_000_000_000, "lastBarMs": 1_700_060_000_000,
                  "params": {"rr": "2.0", "stopRule": "FIXED_BUFFER_TICKS"},
                  "risk": {"fixedContracts": 1, "maxContracts": 1, "dailyLossLimitTicks": 200,
                           "rateLimitPerMinute": 6, "minDwellMs": 5000, "maxReversalsPerSession": 20},
                  "generatedAtMs": 1_700_100_000_000}
        trades = [sample_trade(i, r=1.0 if i % 2 == 0 else -1.0) for i in range(n_trades)]
        summary = {"trades": n_trades, "wins": (n_trades + 1) // 2, "losses": n_trades // 2,
                   "totalR": 1.0, "avgR": 1.0 / max(n_trades, 1), "maxDrawdownR": 3.0,
                   "deniedDailyLoss": 0, "deniedMaxReversals": 0, "deniedRateLimit": 0, "deniedMinDwell": 0,
                   "killSwitchTrips": 0}
        summary.update(summary_overrides)
        return {"header": header, "trades": trades, "summary": summary}

    def test_small_run_shows_every_trade(self):
        text = br.render(self._run(3))
        self.assertIn("market_structure_backtest", text)
        self.assertIn("GC_1m_latest.csv", text)
        self.assertIn("(3 trades.)", text)
        self.assertNotIn("omitted", text)

    def test_large_run_truncates_with_a_note(self):
        text = br.render(self._run(200))
        self.assertIn("omitted", text)
        self.assertIn("of 200 trades shown", text)

    def test_empty_run_says_so(self):
        text = br.render(self._run(0))
        self.assertIn("*(none)*", text)

    def test_denials_and_kill_switch_shown(self):
        text = br.render(self._run(2, deniedDailyLoss=5, killSwitchTrips=2))
        self.assertIn("| 5 |", text)
        self.assertIn("| 2 |", text)


def trade_at(exit_ms, r=1.0, entry_price=100.0, exit_price=105.0, direction=1):
    return {
        "entryTimeMs": exit_ms - 60_000, "entryPrice": entry_price, "direction": direction,
        "stopPrice": entry_price - 5, "targetPrice": entry_price + 10,
        "exitTimeMs": exit_ms, "exitPrice": exit_price,
        "exitReason": "target" if r > 0 else "stop", "rMultiple": r,
    }


class PeriodBreakdownTest(unittest.TestCase):
    def test_day_grouping_uses_ct_1700_rollover_not_utc_date(self):
        # Winter -> CT = UTC-6. 23:00 UTC on 2024-01-15 is exactly 17:00 CT -> rolls to trading day 2024-01-16,
        # NOT the UTC calendar date. This is exactly why trading_day_of (not a naive UTC date) must be used.
        t = trade_at(utc_ms(2024, 1, 15, 23, 0))
        rows = br.period_breakdown([t], "day")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["period"], "2024-01-16")

    def test_day_before_rollover_stays_on_the_same_calendar_date(self):
        t = trade_at(utc_ms(2024, 1, 15, 22, 0))  # 16:00 CT -- before the 17:00 rollover
        rows = br.period_breakdown([t], "day")
        self.assertEqual(rows[0]["period"], "2024-01-15")

    def test_month_label_and_totals(self):
        trades = [trade_at(utc_ms(2023, 7, 10, 12), r=2.0), trade_at(utc_ms(2023, 7, 20, 12), r=-1.0),
                  trade_at(utc_ms(2023, 8, 1, 12), r=1.0)]
        rows = br.period_breakdown(trades, "month")
        by_period = {r["period"]: r for r in rows}
        self.assertEqual(by_period["2023-07"]["trades"], 2)
        self.assertAlmostEqual(by_period["2023-07"]["totalR"], 1.0)
        self.assertEqual(by_period["2023-08"]["trades"], 1)

    def test_year_rolls_up_all_months(self):
        trades = [trade_at(utc_ms(2023, 1, 5, 12), r=1.0), trade_at(utc_ms(2023, 12, 5, 12), r=3.0)]
        rows = br.period_breakdown(trades, "year")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["period"], "2023")
        self.assertAlmostEqual(rows[0]["totalR"], 4.0)

    def test_week_label_is_the_trading_days_monday(self):
        # 2024-01-17 is a Wednesday; its week's Monday is 2024-01-15.
        t = trade_at(utc_ms(2024, 1, 17, 12))
        rows = br.period_breakdown([t], "week")
        self.assertEqual(rows[0]["period"], "2024-01-15")

    def test_win_pct_and_pnl_points_computed(self):
        trades = [trade_at(utc_ms(2023, 7, 10, 12), r=1.0, entry_price=100, exit_price=110, direction=1),
                  trade_at(utc_ms(2023, 7, 11, 12), r=-1.0, entry_price=100, exit_price=95, direction=1)]
        rows = br.period_breakdown(trades, "month")
        r = rows[0]
        self.assertEqual(r["wins"], 1)
        self.assertEqual(r["losses"], 1)
        self.assertAlmostEqual(r["winPct"], 50.0)
        self.assertAlmostEqual(r["totalPnlPoints"], 10.0 + (-5.0))  # (110-100) + (95-100)

    def test_empty_trades_gives_empty_breakdown(self):
        self.assertEqual(br.period_breakdown([], "year"), [])


class CsvWriteTest(unittest.TestCase):
    def test_trades_csv_has_every_trade_and_a_pnl_points_column(self):
        trades = [trade_at(utc_ms(2023, 7, 10, 12), r=1.0, entry_price=100, exit_price=110),
                  trade_at(utc_ms(2023, 7, 11, 12), r=-1.0, entry_price=100, exit_price=95)]
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "x_trades.csv"
            br.write_trades_csv(trades, out)
            with open(out, encoding="utf-8") as f:
                rows = list(csv.DictReader(f))
            self.assertEqual(len(rows), 2)
            self.assertIn("pnlPoints", rows[0])
            self.assertAlmostEqual(float(rows[0]["pnlPoints"]), 10.0)

    def test_periods_csv_has_all_four_period_types(self):
        trades = [trade_at(utc_ms(2023, 7, 10, 12)), trade_at(utc_ms(2024, 3, 1, 12))]
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "x_periods.csv"
            br.write_periods_csv(trades, out)
            with open(out, encoding="utf-8") as f:
                rows = list(csv.DictReader(f))
            types_present = {r["period_type"] for r in rows}
            self.assertEqual(types_present, {"day", "week", "month", "year"})


class MainTest(unittest.TestCase):
    def test_no_file_writes_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "run.jsonl"
            write_run(p, {"strategyId": "x", "csvPath": "c.csv", "tickSize": 1.0, "barCount": 1,
                          "params": {}, "risk": {}, "generatedAtMs": 0},
                      [], {"trades": 0, "wins": 0, "losses": 0, "totalR": 0, "avgR": 0, "maxDrawdownR": 0,
                           "deniedDailyLoss": 0, "deniedMaxReversals": 0, "deniedRateLimit": 0, "deniedMinDwell": 0,
                           "killSwitchTrips": 0})
            rc = br.main([str(p), "--no-file"])
            self.assertEqual(rc, 0)

    def test_writes_out_file_by_default(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "run.jsonl"
            out = Path(d) / "report.md"
            write_run(p, {"strategyId": "x", "csvPath": "c.csv", "tickSize": 1.0, "barCount": 1,
                          "params": {}, "risk": {}, "generatedAtMs": 0},
                      [], {"trades": 0, "wins": 0, "losses": 0, "totalR": 0, "avgR": 0, "maxDrawdownR": 0,
                           "deniedDailyLoss": 0, "deniedMaxReversals": 0, "deniedRateLimit": 0, "deniedMinDwell": 0,
                           "killSwitchTrips": 0})
            rc = br.main([str(p), "--out", str(out)])
            self.assertEqual(rc, 0)
            self.assertTrue(out.is_file())
            self.assertTrue((Path(d) / "report_trades.csv").is_file())
            self.assertTrue((Path(d) / "report_periods.csv").is_file())

    def test_missing_file_returns_2(self):
        rc = br.main(["/no/such/file.jsonl", "--no-file"])
        self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
