#!/usr/bin/env python3
"""Tests for ops/watchdog.py. Plain unittest, no third-party packages, no network (Telegram is a fake).

Run:  python ops/test_watchdog.py
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import watchdog as wd  # noqa: E402

NOW = 1_790_620_000_000
OPTS = {"down_seconds": 90, "min_free_gb": 5.0, "repeat_minutes": 30, "expect_running": True,
        "process_check": True, "forward_alerts": True, "forward_history": False, "dry_run": False}
MW = [("MotiveWave.exe", 600_000)]


def hb(**over):
    r = {"type": "heartbeat", "healthy": True, "armed": True, "feedState": "LIVE",
         "runtime": {"mode": "SIM_LIVE", "armedSetting": True, "armDenied": False}}
    r.update(over)
    return r


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.logs = self.root / "logs"
        self.logs.mkdir()
        self.tg = []
        self.env = {"TELEGRAM_BOT_TOKEN": "T", "TELEGRAM_CHAT_ID": "C"}
        self.ch = wd.Channel(self.logs / "alerts.log", self.env, telegram=lambda *a: self.tg.append(a) or True)

    def tearDown(self):
        self.tmp.cleanup()

    def journal(self, records, age_s=5, name="lvn_fade_test_1_inst1"):
        d = self.logs / name
        d.mkdir(exist_ok=True)
        f = d / "decisions.jsonl"
        f.write_text("\n".join(json.dumps(r) for r in records) + "\n", encoding="utf-8")
        t = (NOW - age_s * 1000) / 1000
        os.utime(f, (t, t))
        return f

    def run_once(self, now=NOW, procs=MW, disk=100.0, **opt):
        o = dict(OPTS)
        o.update(opt)
        return wd.run_once(self.root, now, o, self.ch, procs, disk)

    def keys(self, findings):
        return [k for _, k, _ in findings]


class TestEvaluate(Base):
    def test_healthy(self):
        self.journal([{"type": "log", "msg": "ACTIVATE pos=0"}, hb()])
        self.assertEqual(self.run_once(), [])
        self.assertEqual(self.ch.sent, [])

    def test_no_journal(self):
        self.assertEqual(self.keys(self.run_once()), ["WATCHDOG_NO_JOURNAL"])

    def test_silent_journal_means_down(self):
        self.journal([hb()], age_s=200)
        f = self.run_once()
        self.assertEqual(self.keys(f), ["WATCHDOG_DOWN"])
        self.assertIn("silent for 200 s", f[0][2])

    def test_just_under_the_limit_is_fine(self):
        self.journal([hb()], age_s=89)
        self.assertEqual(self.run_once(), [])

    def test_clean_stop_is_an_alert_when_a_247_run_is_expected(self):
        self.journal([hb(), {"type": "log", "msg": "DEACTIVATE pos=0"}, {"type": "log", "msg": "DESTROY instance=1"}], age_s=300)
        f = self.run_once()
        self.assertEqual(self.keys(f), ["WATCHDOG_STOPPED"])
        self.assertEqual(f[0][0], "ALERT")
        self.assertEqual(self.run_once(expect_running=False)[0][0], "INFO")

    def test_a_reactivation_after_a_stop_is_running_again(self):
        self.journal([{"type": "log", "msg": "DEACTIVATE pos=0"}, {"type": "log", "msg": "ACTIVATE pos=0"}, hb()])
        self.assertEqual(self.run_once(), [])

    def test_disarmed_by_a_safety_rule(self):
        self.journal([hb(armed=False, runtime={"armDenied": True, "armedSetting": True, "mode": "SIM_LIVE"})])
        self.assertEqual(self.keys(self.run_once()), ["WATCHDOG_DISARMED"])

    def test_not_armed_is_only_a_warning(self):
        self.journal([hb(armed=False, runtime={"armDenied": False, "armedSetting": False, "mode": "DRY_RUN"})])
        f = self.run_once()
        self.assertEqual((f[0][0], f[0][1]), ("WARN", "WATCHDOG_NOT_ARMED"))

    def test_stale_feed_and_unhealthy(self):
        self.journal([hb(feedState="STALE", healthy=False)])
        self.assertEqual(sorted(self.keys(self.run_once())), ["WATCHDOG_FEED", "WATCHDOG_UNHEALTHY"])

    def test_missing_motivewave_process_and_low_disk(self):
        self.journal([hb()])
        self.assertEqual(self.keys(self.run_once(procs=[])), ["WATCHDOG_MW_NOT_RUNNING"])
        self.assertEqual(self.run_once(procs=None), [], "cannot tell (non-Windows) is not an alert")
        self.assertEqual(self.run_once(procs=[], process_check=False), [], "process check switched off")
        self.assertEqual(self.keys(self.run_once(disk=2.5)), ["WATCHDOG_DISK"])


class TestQuietness(Base):
    def test_alert_once_repeat_later_and_recover(self):
        self.journal([hb(feedState="STALE")])
        self.run_once()
        self.assertEqual(len(self.ch.sent), 1)
        self.journal([hb(feedState="STALE")], age_s=-(5 * 60) + 5)     # the runtime is still writing: 5 s old at that moment
        self.run_once(now=NOW + 5 * 60_000)
        self.assertEqual(len(self.ch.sent), 1, "still bad 5 min later: no repeat yet")
        self.journal([hb(feedState="STALE")], age_s=-(31 * 60) + 5)
        self.run_once(now=NOW + 31 * 60_000)
        self.assertEqual(len(self.ch.sent), 2, "repeated after the 30 min window")
        self.assertIn("still: for 31 min", self.ch.sent[1][2])
        self.journal([hb()], age_s=-(40 * 60) + 5)
        self.run_once(now=NOW + 40 * 60_000)
        self.assertEqual(self.ch.sent[-1][:2], ("INFO", "WATCHDOG_FEED_RECOVERED"))
        n = len(self.ch.sent)
        self.run_once(now=NOW + 41 * 60_000)
        self.assertEqual(len(self.ch.sent), n, "and nothing more once healthy")

    def test_alerts_land_in_the_file_and_in_telegram(self):
        self.journal([hb(feedState="STALE")])
        self.run_once()
        text = (self.logs / "alerts.log").read_text(encoding="utf-8")
        self.assertRegex(text, r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d[+-]\d\d:\d\d \[ALERT\] WATCHDOG_FEED: .*\(watchdog\)\n$")
        self.assertEqual(len(self.tg), 1)
        self.assertEqual(self.tg[0][1:], ("T", "C"))
        self.assertIn("FLOW ALERT WATCHDOG_FEED", self.tg[0][0])

    def test_no_credentials_means_file_only(self):
        ch = wd.Channel(self.logs / "alerts.log", {}, telegram=lambda *a: self.fail("must not be called"))
        ch.send("ALERT", "X", "m", NOW)
        self.assertIn("[ALERT] X: m", (self.logs / "alerts.log").read_text(encoding="utf-8"))

    def test_telegram_failure_never_raises(self):
        ch = wd.Channel(self.logs / "alerts.log", self.env, telegram=wd.telegram_send)
        wd_send = wd.telegram_send("x", "badtoken", "1", timeout=0.01)
        self.assertFalse(wd_send)
        ch.telegram = lambda *a: False
        ch.send("ALERT", "X", "m", NOW)   # returns quietly


class TestForwarding(Base):
    def test_only_new_runtime_lines_are_forwarded_and_not_our_own(self):
        self.journal([hb()])
        log = self.logs / "alerts.log"
        log.write_text("2026-09-28T23:35:32+05:30 [INFO] SESSION_START: old history\n", encoding="utf-8")
        self.run_once()
        self.assertEqual(self.ch.sent, [], "first run: history is not forwarded")
        with open(log, "a", encoding="utf-8") as fh:
            fh.write("2026-09-28T23:39:46+05:30 [ALERT] FEED_STALE: FEED_STALE no market data for 20s\n")
            fh.write("2026-09-28T23:39:47+05:30 [ALERT] WATCHDOG_X: from the watchdog itself (watchdog)\n")
        self.run_once(now=NOW + 1000)
        self.assertEqual([(s, k) for s, k, _ in self.ch.sent], [("ALERT", "RUNTIME")])
        self.assertIn("FEED_STALE no market data", self.ch.sent[0][2])
        self.run_once(now=NOW + 2000)
        self.assertEqual(len(self.ch.sent), 1, "already forwarded: not again")

    def test_partial_last_line_waits_for_its_newline(self):
        self.journal([hb()])
        log = self.logs / "alerts.log"
        log.write_text("", encoding="utf-8")
        self.run_once()
        with open(log, "a", encoding="utf-8") as fh:
            fh.write("2026-09-28T23:39:46+05:30 [ALERT] KILL_SWITCH_TRIGGERED: half a li")
        self.run_once(now=NOW + 1000)
        self.assertEqual(self.ch.sent, [])
        with open(log, "a", encoding="utf-8") as fh:
            fh.write("ne\n")
        self.run_once(now=NOW + 2000)
        self.assertEqual(len(self.ch.sent), 1)
        self.assertTrue(self.ch.sent[0][2].endswith("half a line"))

    def test_forward_history_flag(self):
        self.journal([hb()])
        (self.logs / "alerts.log").write_text("2026-09-28T23:35:32+05:30 [WARN] ACCOUNT_ID_UNKNOWN: x\n", encoding="utf-8")
        self.run_once(forward_history=True)
        self.assertEqual([(s, k) for s, k, _ in self.ch.sent], [("WARN", "RUNTIME")])

    def test_forwarding_can_be_switched_off(self):
        self.journal([hb()])
        (self.logs / "alerts.log").write_text("", encoding="utf-8")
        self.run_once(forward_alerts=False)
        with open(self.logs / "alerts.log", "a", encoding="utf-8") as fh:
            fh.write("2026-09-28T23:39:46+05:30 [ALERT] X: y\n")
        self.run_once(now=NOW + 1000, forward_alerts=False)
        self.assertEqual(self.ch.sent, [])


class TestMisc(Base):
    def test_metrics_row_written_each_run(self):
        self.journal([hb()])
        self.run_once(procs=[("MotiveWave.exe", 512_000), ("MotiveWaveHelper.exe", 512_000)], disk=42.26)
        self.run_once(now=NOW + 60_000)
        rows = (self.logs / "watchdog_metrics.csv").read_text(encoding="utf-8").splitlines()
        self.assertEqual(rows[0], "time,journal_age_s,feed_state,armed,motivewave_procs,motivewave_mem_mb,disk_free_gb")
        self.assertEqual(len(rows), 3)
        self.assertTrue(rows[1].endswith(",5,LIVE,True,2,1000.0,42.3"))

    def test_env_file_parsing(self):
        f = self.root / ".env"
        f.write_text('# comment\nOTHER=1\nTELEGRAM_BOT_TOKEN="abc:123"\nTELEGRAM_CHAT_ID = 987\n', encoding="utf-8")
        for k in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"):
            os.environ.pop(k, None)
        self.assertEqual(wd.load_env(f), {"TELEGRAM_BOT_TOKEN": "abc:123", "TELEGRAM_CHAT_ID": "987"})
        self.assertEqual(wd.load_env(self.root / "missing.env"), {})

    def test_dry_run_writes_nothing(self):
        self.journal([hb(feedState="STALE")])
        ch = wd.Channel(self.logs / "alerts.log", self.env, dry_run=True, telegram=lambda *a: self.fail("no send in dry run"))
        wd.run_once(self.root, NOW, dict(OPTS, dry_run=True), ch, MW, 100.0)
        self.assertFalse((self.logs / "alerts.log").exists())
        self.assertFalse((self.logs / "watchdog_state.json").exists())

    def test_cli_test_alert(self):
        rc = wd.main(["--root", str(self.root), "--test-alert"])
        self.assertEqual(rc, 0)
        self.assertIn("WATCHDOG_TEST", (self.root / "logs" / "alerts.log").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
