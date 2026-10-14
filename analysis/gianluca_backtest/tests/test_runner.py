"""runner.py --resume on the 20-config DRY stage: runs, survives a kill mid-way, resumes, advances state.
Uses the real cached features (P1A must have built them) and a temp output dir (GB_OUT)."""
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
RUNNER = HERE / "runner.py"


class RunnerTest(unittest.TestCase):
    def test_dry_resume(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "state.json").write_text(json.dumps({"phase": "TEST_DRY", "kind": "compute", "attempts": 0}))
            env = dict(os.environ, GB_OUT=str(out))
            runs = out / "runs" / "DRY.jsonl"
            p = subprocess.Popen([sys.executable, str(RUNNER), "--resume", "--workers", "2"], env=env,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
            t0 = time.time()
            while time.time() - t0 < 300:
                if runs.exists() and len(runs.read_text().splitlines()) >= 3:
                    break
                time.sleep(0.2)
            p.kill(); p.wait()
            first = len(runs.read_text().splitlines())
            self.assertGreaterEqual(first, 3)
            self.assertLess(first, 20, "killed too late to test resuming")
            self.assertEqual(json.loads((out / "state.json").read_text())["phase"], "TEST_DRY")
            r = subprocess.run([sys.executable, str(RUNNER), "--resume", "--workers", "2"], env=env,
                               capture_output=True, text=True, timeout=600)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            lines = [json.loads(x) for x in runs.read_text().splitlines()]
            self.assertEqual(len({x["hash"] for x in lines}), 20)
            self.assertEqual(len(lines), 20)
            st = json.loads((out / "state.json").read_text())
            self.assertEqual((st["phase"], st["kind"]), ("TEST_DONE", "done"))
            self.assertFalse((out / "runner.lock.json").exists())
            m = lines[0]["metrics"]
            for k in ("signals", "trades", "net_usd", "sharpe", "mdd_pct", "flat_usd", "exp_r"):
                self.assertIn(k, m)


if __name__ == "__main__":
    unittest.main()
