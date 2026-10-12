"""Tests for ops/gb_backtest_supervisor.py with fake runner scripts (no Claude, no orders).
   python ops\\test_gb_backtest_supervisor.py"""
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gb_backtest_supervisor as sup  # noqa: E402


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        out = self.tmp / "out"
        sup.OUT, sup.STATE, sup.LOG, sup.RUNNER_LOCK = out, out / "state.json", out / "supervisor.log", out / "r.json"
        sup.RUNNER = self.tmp / "runner.py"
        self.flow_home = self.tmp / "flow"
        self.opts = self.parse(["--once", "--flow-home", str(self.flow_home), "--max-attempts", "2"])

    def tearDown(self):
        for p in sup._CHILDREN:
            p.wait(timeout=30)
        sup._CHILDREN.clear()

    def parse(self, args):
        """The options main() would build for `args` (captured; main itself does not run)."""
        import argparse
        captured, orig = {}, argparse.ArgumentParser.parse_args

        def grab(parser, a=None, ns=None):
            captured["o"] = orig(parser, a, ns)
            raise SystemExit(0)
        argparse.ArgumentParser.parse_args = grab
        try:
            sup.main(args)
        except SystemExit:
            pass
        finally:
            argparse.ArgumentParser.parse_args = orig
        return captured["o"]

    def set_state(self, **s):
        sup.write_json(sup.STATE, {"attempts": 0, **s})

    def state(self):
        return json.loads(sup.STATE.read_text())

    def alerts(self):
        p = self.flow_home / "logs" / "alerts.log"
        return p.read_text() if p.exists() else ""

    def runner(self, advance_to=None):
        body = "import json\nfrom pathlib import Path\n"
        if advance_to:
            body += ("S=Path(%r)\ns=json.loads(S.read_text())\ns.update(phase=%r, kind='claude', attempts=0)\n"
                     "S.write_text(json.dumps(s))\n" % (str(sup.STATE), advance_to))
        sup.RUNNER.write_text(body)

    def tick_and_wait(self):
        r = sup.tick(self.opts)
        for p in sup._CHILDREN:
            p.wait(timeout=30)
        return r

    def test_no_state_starts_nothing(self):
        self.assertIn("no state.json", sup.tick(self.opts))

    def test_claude_phase_starts_nothing_and_notifies_once(self):
        self.set_state(phase="P1A_BUILD_CORE", kind="claude")
        sup.tick(self.opts)
        sup.tick(self.opts)
        self.assertEqual(self.alerts().count("say \"continue\""), 1)
        self.assertFalse(sup.RUNNER_LOCK.exists())

    def test_compute_phase_runs_runner_and_follows_its_state_change(self):
        self.runner(advance_to="P4_REVIEW_GOLD")
        self.set_state(phase="P3_SEARCH_FULL_B", kind="compute")
        self.assertIn("started runner", self.tick_and_wait())
        self.assertIn("waiting for Claude", sup.tick(self.opts))
        self.assertEqual(self.state()["phase"], "P4_REVIEW_GOLD")
        self.assertEqual(self.state()["attempts"], 0)
        self.assertIn("P4_REVIEW_GOLD needs Claude", self.alerts())

    def test_failing_runner_goes_stuck_and_reset_resumes(self):
        self.runner()   # exits without advancing
        self.set_state(phase="P3_SEARCH_FULL_A", kind="compute")
        self.tick_and_wait()                       # run 1
        self.tick_and_wait()                       # attempt 1 recorded, run 2
        self.assertEqual(sup.tick(self.opts), "stuck")
        self.assertIn("STUCK", self.alerts())
        sup.main(["--reset-stuck"])
        self.assertEqual(self.state()["kind"], "compute")
        self.assertEqual(self.state()["attempts"], 0)

    def test_compute_phase_without_runner_is_stuck(self):
        self.set_state(phase="P3_SEARCH_FULL_A", kind="compute")
        self.assertEqual(sup.tick(self.opts), "stuck")

    def test_running_runner_is_left_alone(self):
        sup.RUNNER.write_text("import time\ntime.sleep(3)\n")
        self.set_state(phase="P3_SEARCH_FULL_A", kind="compute")
        sup.tick(self.opts)
        self.assertIn("runner running", sup.tick(self.opts))
        self.assertEqual(len(sup._CHILDREN), 1)

    def test_done_notifies_once(self):
        self.set_state(phase="DONE", kind="done")
        sup.tick(self.opts)
        sup.tick(self.opts)
        self.assertEqual(self.alerts().count("DONE"), 1)


if __name__ == "__main__":
    unittest.main()
