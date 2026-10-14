import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import sizing  # noqa: E402


def tr(r, si=1, risk=10, usd=None, reducing=True, atr="LOW", fresh="FRESH", sess="NY"):
    return {"r": r, "si": si, "risk": risk, "usd": r * risk * 10 - 4.6 if usd is None else usd, "reducing": reducing,
            "atr_b": atr, "fresh_b": fresh, "sess_b": sess, "reason": "x", "exit_i": 0}


SP = dict(sizing.SIZING_BASELINE)


class SizingTest(unittest.TestCase):
    def test_autocorr_calibration(self):
        # P(W) = 0.40, P(W|W) = 0.525 -> 10 * 0.125 / 0.16 = 7.8
        self.assertAlmostEqual(10 * (0.525 - 0.40) / 0.16, 7.8125)
        self.assertEqual(sizing.autocorr_strength([True, False] * 50), 0.0)
        self.assertEqual(sizing.autocorr_strength([True] * 50 + [False] * 50), 10.0)

    def test_kelly(self):
        self.assertAlmostEqual(sizing.kelly([2, -1, 2, -1]), 0.5 - 0.5 / 2)
        self.assertLess(sizing.kelly([-1, -1]), 0)

    def test_warmup_and_contracts(self):
        out = sizing.overlay([tr(2.0)], SP, {"atr_b": "LOW", "fresh_b": None, "sess_b": None})
        t, score, contracts, pnl, eq, why = out[0]
        self.assertEqual(score, 2)                      # atr + reducing
        # 100k * 0.25% = 250 / (1 pt * 100 + 4.6 + 20) = 2.006 -> 2.0 contracts
        self.assertEqual(contracts, 2.0)
        self.assertAlmostEqual(pnl, 2.0 * (200 - 4.6))
        self.assertIsNone(why)

    def test_score_zero_and_min_score(self):
        t0 = tr(1.0, reducing=False)
        out = sizing.overlay([t0], SP, {"atr_b": None, "fresh_b": None, "sess_b": None})
        self.assertEqual((out[0][1], out[0][2], out[0][5]), (0, 0.0, "score"))
        out = sizing.overlay([tr(1.0)], dict(SP, min_score=2), {"atr_b": None, "fresh_b": None, "sess_b": None})
        self.assertEqual(out[0][5], "score")

    def test_kelly_by_score_and_cap(self):
        hist = [tr(2.0), tr(-1.0)] * 20                 # W = .5, R = 2 -> f* = .25
        all_b = {"atr_b": "LOW", "fresh_b": "FRESH", "sess_b": "NY"}
        out = sizing.overlay(hist + [tr(1.0, si=2)], dict(SP, streak="off"), all_b, eval_from_si=2)
        # score 4 (no Q2: streak off) -> 1/4 * .25 = 6.25% -> capped at 2%: 2000 / 124.6 = 16.05 -> 16.0
        self.assertEqual((out[0][1], out[0][2]), (4, 16.0))

    def test_streak_skip(self):
        # strongly autocorrelated history ending W, W, L -> the next trade is skipped
        hist = ([tr(1.0)] * 10 + [tr(-1.0)] * 10) * 2 + [tr(1.0), tr(1.0), tr(-1.0)]
        out = sizing.overlay(hist + [tr(1.0, si=2)], SP, {}, eval_from_si=2)
        self.assertEqual(out[0][5], "streak")
        out = sizing.overlay(hist + [tr(1.0, si=2)], dict(SP, streak="off"), {}, eval_from_si=2)
        self.assertNotEqual(out[0][5], "streak")

    def test_eval_window_history(self):
        out = sizing.overlay([tr(-1.0, si=1), tr(1.0, si=2), tr(1.0, si=3)], SP, {}, eval_from_si=2, eval_to_si=3)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0][0]["si"], 2)

    def test_best_buckets(self):
        ts = [tr(1.0, atr="LOW")] * 20 + [tr(2.0, atr="HIGH")] * 19 + [tr(0.5, atr="MEDIUM")] * 25
        self.assertEqual(sizing.best_buckets(ts)["atr_b"], "LOW")    # HIGH has < 20 trades


if __name__ == "__main__":
    unittest.main()
