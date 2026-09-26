#!/usr/bin/env python3
"""Tests for analysis/status.py (plain unittest). Journals come from the daily
report's test builder; file modification times are set explicitly so "now" is
deterministic.

Run:  python analysis/test_status.py
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import daily_report as dr  # noqa: E402
import status as st  # noqa: E402
import test_daily_report as t  # noqa: E402

NOW = t.ct_ms(t.DAY, 10, 15, 0)          # Wed 2026-09-23 10:15:00 CT


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.logs = self.root / "logs"
        self.logs.mkdir()
        self.data = self.root / "data"

    def tearDown(self):
        self.tmp.cleanup()

    def journal(self, name="lvn_fade_test_1_inst1", **kw):
        return t.Journal(self.logs, name=name, **kw)

    def write(self, j, ago_s=3):
        """Write the journal and stamp its file as last modified ago_s seconds before NOW."""
        j.write()
        m = (NOW - ago_s * 1000) / 1000
        os.utime(j.dir / "decisions.jsonl", (m, m))

    def line(self):
        return st.status_line(self.logs, self.data, NOW)

    def touch_data(self, name, ago_s):
        d = self.data / name
        d.mkdir(parents=True, exist_ok=True)
        f = d / f"{dr.session_id_for_day(t.DAY)}.jsonl"
        f.write_text("{}\n", encoding="utf-8")
        m = (NOW - ago_s * 1000) / 1000
        os.utime(f, (m, m))


class TestLiveness(Base):
    def test_alive(self):
        j = self.journal()
        j.heartbeat(NOW - 4000)
        self.write(j)
        line, code = self.line()
        self.assertEqual(code, 0)
        self.assertTrue(line.startswith("ALIVE |"), line)
        self.assertIn("heartbeat 3s ago", line)   # the file was written 3 s ago: newer than the 4 s-old heartbeat
        self.assertIn("lvn_fade_test", line)
        self.assertNotIn("\n", line)

    def test_stale_then_down_thresholds(self):
        for ago_s, want_state, want_code in ((29, "ALIVE", 0), (31, "STALE", 1), (299, "STALE", 1), (301, "DOWN", 2)):
            with self.subTest(ago_s=ago_s):
                self.tearDown()
                self.setUp()
                j = self.journal()
                j.heartbeat(NOW - ago_s * 1000)
                self.write(j, ago_s=ago_s)
                line, code = self.line()
                self.assertEqual((line.split(" |")[0], code), (want_state, want_code), line)

    def test_exact_boundaries_use_the_records_own_time(self):
        # An exact-millisecond record time (file mtimes are floats, so they can't pin a boundary); the file is an hour old.
        for ms, want in ((30_000, "ALIVE"), (30_001, "STALE"), (300_000, "STALE"), (300_001, "DOWN")):
            with self.subTest(silent_ms=ms):
                self.tearDown()
                self.setUp()
                j = self.journal()
                j.heartbeat(NOW - ms)
                self.write(j, ago_s=3600)
                self.assertEqual(self.line()[0].split(" |")[0], want)

    def test_a_fresh_record_counts_even_if_the_file_looks_old(self):
        j = self.journal()
        j.heartbeat(NOW - 2_000)
        self.write(j, ago_s=3600)                # e.g. the file was copied or its mtime reset
        self.assertTrue(self.line()[0].startswith("ALIVE"))

    def test_stopped_cleanly_is_not_reported_as_a_crash(self):
        j = self.journal()
        j.heartbeat(NOW - 600_000)
        j.log(NOW - 590_000, "DEACTIVATE pos=0")
        self.write(j, ago_s=590)
        line, code = self.line()
        self.assertTrue(line.startswith("STOPPED |"), line)
        self.assertEqual(code, 1)
        self.assertIn("stopped 9m ago", line)
        self.assertIn("data: n/a (not running)", line)

    def test_a_later_log_line_after_deactivate_means_not_stopped(self):
        j = self.journal()
        j.log(NOW - 20_000, "DEACTIVATE pos=0")
        j.log(NOW - 5_000, "ACTIVATE pos=0 cash=1")
        self.write(j)
        self.assertTrue(self.line()[0].startswith("ALIVE"))

    def test_no_journal_is_down(self):
        line, code = self.line()
        self.assertEqual(code, 2)
        self.assertTrue(line.startswith("DOWN |"))
        self.assertIn("no journal found", line)

    def test_file_mtime_counts_when_records_are_old(self):
        j = self.journal()
        j.heartbeat(NOW - 3_600_000)             # the last timestamped record is an hour old...
        self.write(j, ago_s=2)                   # ...but the file was written 2 s ago
        self.assertTrue(self.line()[0].startswith("ALIVE"))

    def test_the_newest_journal_wins(self):
        old = self.journal(name="a_old_inst1", strategy="null_strategy")
        old.heartbeat(NOW - 100_000)
        self.write(old, ago_s=100)
        new = self.journal(name="b_new_inst2", strategy="lvn_fade_test")
        new.heartbeat(NOW - 2_000)
        self.write(new, ago_s=2)
        line, _ = self.line()
        self.assertTrue(line.startswith("ALIVE"))
        self.assertIn("lvn_fade_test", line)
        self.assertNotIn("null_strategy", line)


class TestFields(Base):
    def test_armed_from_the_newest_verdict(self):
        j = self.journal()
        j.intent(NOW - 120_000, 1, "e1")                                         # allowed -> armed yes
        self.write(j)
        self.assertIn("armed: yes (verdict 2m ago)", self.line()[0])

        self.tearDown()
        self.setUp()
        j = self.journal()
        j.intent(NOW - 300_000, 1, "e1")
        j.intent(NOW - 30_000, 1, "e2", verdict="blocked", filt="armed", why="not armed")   # newest says not armed
        self.write(j)
        self.assertIn("armed: no (verdict 30s ago)", self.line()[0])

        self.tearDown()
        self.setUp()
        j = self.journal()
        j.heartbeat(NOW - 1000)
        self.write(j)
        self.assertIn("armed: unknown", self.line()[0])

    def test_position_from_the_newest_fill(self):
        for pos_after, want in ((1, "LONG 1"), (-2, "SHORT 2"), (0, "flat")):
            with self.subTest(pos=pos_after):
                self.tearDown()
                self.setUp()
                j = self.journal()
                j.fill(NOW - 20_000, "entry", "BUY", 4300.0, 1)
                j.fill(NOW - 10_000, "stop", "SELL", 4299.0, pos_after, order_id="2")
                self.write(j)
                self.assertIn(f"position: {want}", self.line()[0])
        self.tearDown()
        self.setUp()
        j = self.journal()
        j.heartbeat(NOW - 1000)
        self.write(j)
        self.assertIn("position: unknown", self.line()[0])

    def test_last_trade_and_open_trade(self):
        j = self.journal()
        j.submitted("BUY", "e")
        j.fill(t.ct_ms(t.DAY, 9, 58, 0), "entry", "BUY", 4300.0, 1)
        j.fill(t.ct_ms(t.DAY, 9, 59, 0), "target", "SELL", 4302.0, 0, order_id="2")
        self.write(j)
        self.assertIn("last trade 09:59 CT +2.0 pts", self.line()[0])

        self.tearDown()
        self.setUp()
        j = self.journal()
        j.submitted("BUY", "e")
        j.fill(t.ct_ms(t.DAY, 10, 10, 0), "entry", "BUY", 4300.0, 1)
        self.write(j)
        line = self.line()[0]
        self.assertIn("position: LONG 1 (1 open trade)", line)
        self.assertIn("no closed trade today", line)

    def test_alerts_are_counted_and_flagged_in_the_state_word(self):
        j = self.journal()
        j.heartbeat(NOW - 2000)
        j.log(NOW - 60_000, "POSITION_MISMATCH_DETECTED expectedFlat=true actualPosition=-1")
        j.log(NOW - 50_000, "LIVE_ENTRY_REJECTED_DISARMING -- entry rejected, disarming")
        self.write(j)
        line = self.line()[0]
        self.assertTrue(line.startswith("ALIVE ALERTS 2 |"), line)
        self.assertIn("alerts today: 2", line)

    def test_a_fill_with_no_trade_to_attach_to_is_an_alert(self):
        j = self.journal()
        j.heartbeat(NOW - 2000)
        j.fill(NOW - 30_000, "untracked", "SELL", 4300.0, -1)     # the account moved and nothing opened a trade
        self.write(j)
        line = self.line()[0]
        self.assertTrue(line.startswith("ALIVE ALERTS 1 |"), line)

    def test_an_old_journal_does_not_leak_into_todays_counts(self):
        y = self.journal(name="y_old_inst1", start=t.ct_ms(t.DAY, 9) - 3 * 86_400_000)
        y.log(NOW - 3 * 86_400_000, "POSITION_MISMATCH_DETECTED x")
        self.write(y, ago_s=3 * 86_400)
        j = self.journal(name="z_new_inst2")
        j.heartbeat(NOW - 1000)
        self.write(j)
        self.assertIn("alerts today: 0", self.line()[0])


class TestData(Base):
    def _alive(self):
        j = self.journal()
        j.heartbeat(NOW - 2000)
        self.write(j)

    def test_data_ok_when_both_dense_files_are_fresh(self):
        self._alive()
        self.touch_data("vwap", 3)
        self.touch_data("liquidity_map", 5)
        self.assertIn("data: ok", self.line()[0])

    def test_stale_or_missing_data_is_called_out(self):
        self._alive()
        self.touch_data("vwap", 3)
        self.touch_data("liquidity_map", 120)
        self.assertIn("data: CHECK: liquidity_map 2m old", self.line()[0])
        self.tearDown()
        self.setUp()
        self._alive()
        self.touch_data("vwap", 3)
        self.assertIn("data: CHECK: liquidity_map missing", self.line()[0])

    def test_no_data_directory(self):
        self._alive()
        self.assertIn("data: n/a (no data dir)", self.line()[0])


class TestCli(Base):
    def test_exit_codes(self):
        j = self.journal()
        j.heartbeat(NOW - 2000)
        self.write(j)
        args = ["--logs", str(self.logs), "--data", str(self.data), "--now", str(NOW)]
        self.assertEqual(st.main(args), 0)
        self.assertEqual(st.main(["--logs", str(self.root / "nope"), "--data", str(self.data), "--now", str(NOW)]), 2)

    def test_ago_formatting(self):
        self.assertEqual(st.ago(4000), "4s")
        self.assertEqual(st.ago(89_000), "89s")
        self.assertEqual(st.ago(120_000), "2m")
        self.assertEqual(st.ago(2 * 3600_000 + 5 * 60_000), "2h05m")
        self.assertEqual(st.ago(-500), "0s")


if __name__ == "__main__":
    unittest.main(verbosity=1)
