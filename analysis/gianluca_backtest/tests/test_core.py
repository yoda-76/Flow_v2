import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import bars  # noqa: E402
import data  # noqa: E402
import indicators as ind  # noqa: E402
import vprofile as prof  # noqa: E402
import timeutil as tu  # noqa: E402


def utc(y, mo, d, h, mi=0):
    return int(datetime(y, mo, d, h, mi, tzinfo=timezone.utc).timestamp())


def rows(start_utc, n, price=100.0, vol=10, step_s=60):
    out = []
    for i in range(n):
        p = price + (i % 5) * 0.1
        out.append(f"{(start_utc + i * step_s) * 1000},{p},{p + 0.2},{p - 0.1},{p + 0.1},{vol}\n")
    return out


class TimeTest(unittest.TestCase):
    def test_dst_2021(self):
        self.assertEqual(tu.et_offset_s(utc(2021, 3, 14, 6, 59)), -18000)
        self.assertEqual(tu.et_offset_s(utc(2021, 3, 14, 7, 0)), -14400)
        self.assertEqual(tu.et_offset_s(utc(2021, 11, 7, 5, 59)), -14400)
        self.assertEqual(tu.et_offset_s(utc(2021, 11, 7, 6, 0)), -18000)

    def test_dst_2025(self):
        self.assertEqual(tu.et_offset_s(utc(2025, 3, 9, 6, 59)), -18000)
        self.assertEqual(tu.et_offset_s(utc(2025, 3, 9, 7, 0)), -14400)
        self.assertEqual(tu.et_offset_s(utc(2025, 11, 2, 5, 59)), -14400)
        self.assertEqual(tu.et_offset_s(utc(2025, 11, 2, 6, 0)), -18000)

    def test_session_assignment(self):
        # Sunday 2020-07-12 22:00 UTC = 18:00 EDT -> session of Monday 2020-07-13
        et_min = tu.et_seconds(utc(2020, 7, 12, 22)) // 60
        sd = tu.session_of_et_min(et_min)
        self.assertEqual(str(tu.day_to_date(sd)), "2020-07-13")
        self.assertEqual(tu.session_open_et_min(sd), et_min)
        # 16:59 ET belongs to the same session; 17:00 ET to none
        self.assertEqual(tu.session_of_et_min(tu.et_seconds(utc(2020, 7, 13, 20, 59)) // 60), sd)
        self.assertIsNone(tu.session_of_et_min(tu.et_seconds(utc(2020, 7, 13, 21, 0)) // 60))


class DataBarsTest(unittest.TestCase):
    def make(self):
        # full session Mon 2020-07-13 (1380 bars) + a 17:xx bar + a short (10-bar) session
        r = rows(utc(2020, 7, 12, 22), 1380)
        r += rows(utc(2020, 7, 13, 21, 5), 1)                     # 17:05 EDT: maintenance -> dropped
        r += rows(utc(2020, 7, 13, 22), 10)                       # short session -> dropped
        return data.parse_csv(None, rows=r)

    def test_parse(self):
        b = self.make()
        self.assertEqual(b.stats["rows_in_17h_break"], 1)
        self.assertEqual(b.stats["short_sessions_dropped"], 1)
        self.assertEqual(len(b.s_day), 1)
        self.assertEqual(len(b.c), 1380)
        self.assertEqual(b.o[0], 1000)

    def test_bars(self):
        b = self.make()
        tb = bars.build(b, 15)
        self.assertEqual(len(tb), 92)
        self.assertEqual(tb.close_et[0] - tu.session_open_et_min(b.s_day[0]), 15)
        self.assertEqual(tb.v[0], 150)
        self.assertEqual(tb.i1[-1], 1380)
        self.assertEqual(tb.h[0], max(b.h[0:15]))

    def test_partial_and_gap(self):
        r = rows(utc(2020, 7, 12, 22), 1370)                      # session ends 16:50 -> last 15m bar partial
        b = data.parse_csv(None, rows=r[:600] + r[640:])          # 40-minute gap
        self.assertEqual(b.stats["gaps_over_30m"], 1)
        tb = bars.build(b, 30)
        self.assertEqual(tb.i1[-1] - tb.i0[-1], 20)


class ProfileTest(unittest.TestCase):
    def test_spread(self):
        lo, vols = prof.build_profile([12, 10], [10, 10], [30, 5], 0, 2)
        self.assertEqual(lo, 10)
        self.assertEqual([round(x, 6) for x in vols], [15, 10, 10])

    def test_va(self):
        vols = [1, 1, 5, 10, 20, 8, 2, 1, 1, 1]          # total 50
        poc, vah, val = prof.value_area(100, vols, 70)
        self.assertEqual(poc, 104)
        # 20 -> up(8,2)=10 vs dn(10,5)=15: add dn -> 35 (70% of 50 = 35) done
        self.assertEqual((vah, val), (104, 102))

    def test_poc_tie(self):
        vols = [5, 1, 1, 1, 5, 9, 9]
        poc, _, _ = prof.value_area(0, vols, 70)
        # mean = (0*5+1+2+3+4*5+5*9+6*9)/31 ~ 4.1 -> tie between 5 and 6 goes to 5
        self.assertEqual(poc, 5)


class IndicatorTest(unittest.TestCase):
    def test_vol_ma(self):
        v = list(range(1, 31))
        s = ind.vol_sma(v)
        self.assertAlmostEqual(s[19], sum(range(1, 21)) / 20)
        self.assertAlmostEqual(s[29], sum(range(11, 31)) / 20)
        self.assertAlmostEqual(s[1], 1.5)
        e = ind.vol_ema([10, 20])
        self.assertAlmostEqual(e[1], 10 + (2 / 21) * 10)

    def test_atr(self):
        h = [10] * 20; l = [8] * 20; c = [9] * 20
        a = ind.atr(h, l, c)
        self.assertAlmostEqual(a[-1], 2)
        p = ind.atr_pct([1, 2, 3, 2, 1])
        self.assertEqual(list(p), [100, 100, 100, 75, 40])

    def test_atr_pct_window(self):
        a = list(range(300))
        p = ind.atr_pct(a, n=200)
        self.assertEqual(p[-1], 100)
        p = ind.atr_pct([5] + [1] * 3, n=3)
        self.assertEqual([round(x, 2) for x in p], [100, 50, 66.67, 100])

    def test_swings(self):
        h = [1, 2, 5, 2, 1, 2, 3]
        l = [5, 4, 3, 4, 0, 4, 5]
        sh, sl = ind.swings(h, l, 2)
        self.assertEqual([i for i, x in enumerate(sh) if x], [2])
        self.assertEqual([i for i, x in enumerate(sl) if x], [4])

    def test_bocpd_detects_change(self):
        import random
        rnd = random.Random(1)
        h, l = [], []
        for i in range(600):
            w = 0.002 if i < 400 else 0.02
            lo = 1000.0
            h.append(lo * (1 + abs(rnd.gauss(w, w / 4)))); l.append(lo)
        age = ind.bocpd_age(h, l, prior_n=100)
        self.assertGreater(age[399], 100)
        self.assertLess(age[420], 40)
        self.assertGreaterEqual(age[599], 150)


if __name__ == "__main__":
    unittest.main()
