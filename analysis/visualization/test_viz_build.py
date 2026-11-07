#!/usr/bin/env python3
"""Tests for analysis/visualization/viz_build.py. Plain unittest, no third-party packages.

Run (repo root):  python -m unittest analysis/visualization/test_viz_build.py
             or:  python analysis/visualization/test_viz_build.py
"""

import csv
import json
import re
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import viz_build as vb  # noqa: E402

UTC = timezone.utc


def ms(y, mo, d, h=0, mi=0):
    return int(datetime(y, mo, d, h, mi, tzinfo=UTC).timestamp() * 1000)


def row(ts, o, h, l, c, v):
    return (ts, o, h, l, c, v)


class TestDst(unittest.TestCase):
    def test_2026_boundaries(self):
        self.assertEqual(vb.us_central_offset_hours(ms(2026, 3, 8, 7, 59)), -6)
        self.assertEqual(vb.us_central_offset_hours(ms(2026, 3, 8, 8, 0)), -5)
        self.assertEqual(vb.us_central_offset_hours(ms(2026, 11, 1, 6, 59)), -5)
        self.assertEqual(vb.us_central_offset_hours(ms(2026, 11, 1, 7, 0)), -6)

    def test_midsummer_and_winter(self):
        self.assertEqual(vb.us_central_offset_hours(ms(2025, 7, 1)), -5)
        self.assertEqual(vb.us_central_offset_hours(ms(2025, 1, 15)), -6)


class TestSession(unittest.TestCase):
    def test_boundary_winter(self):
        # Tue 2026-01-13: 16:59 CT = 22:59 UTC, 17:00 CT = 23:00 UTC
        a = vb.session_start_ms(ms(2026, 1, 13, 22, 59))
        b = vb.session_start_ms(ms(2026, 1, 13, 23, 0))
        self.assertNotEqual(a, b)
        self.assertEqual(a, ms(2026, 1, 12, 23, 0))
        self.assertEqual(b, ms(2026, 1, 13, 23, 0))

    def test_boundary_summer(self):
        # 16:59 CDT = 21:59 UTC
        a = vb.session_start_ms(ms(2026, 7, 7, 21, 59))
        b = vb.session_start_ms(ms(2026, 7, 7, 22, 0))
        self.assertEqual(b - a, vb.DAY_MS)
        self.assertEqual(b, ms(2026, 7, 7, 22, 0))

    def test_sunday_evening_is_monday_bar(self):
        sun_open = ms(2026, 1, 11, 23, 0)  # Sunday 17:00 CT
        mon_midday = ms(2026, 1, 12, 15, 0)
        self.assertEqual(vb.session_start_ms(sun_open), sun_open)
        self.assertEqual(vb.session_start_ms(mon_midday), sun_open)

    def test_session_across_dst_change(self):
        # Sat/Sun 2026-03-08 DST starts; Sunday 17:00 CDT = 22:00 UTC
        self.assertEqual(vb.session_start_ms(ms(2026, 3, 9, 12, 0)), ms(2026, 3, 8, 22, 0))


class TestAggregate(unittest.TestCase):
    def setUp(self):
        self.t0 = ms(2026, 1, 13, 10, 0)
        self.rows = [row(self.t0 + i * 60_000, 100 + i, 101 + i, 99 + i, 100.5 + i, 10) for i in range(10)]

    def test_5m(self):
        bars = vb.aggregate(self.rows, vb.tf_key("5m"))
        self.assertEqual(len(bars), 2)
        self.assertEqual(bars[0], [self.t0, 100, 105, 99, 104.5, 50])
        self.assertEqual(bars[1], [self.t0 + 300_000, 105, 110, 104, 109.5, 50])

    def test_1h_partial_and_split(self):
        rows = [row(self.t0 + 50 * 60_000, 1, 5, 0.5, 2, 3), row(self.t0 + 61 * 60_000, 2, 9, 2, 8, 4)]
        bars = vb.aggregate(rows, vb.tf_key("1h"))
        self.assertEqual(bars, [[self.t0, 1, 5, 0.5, 2, 3], [self.t0 + 3_600_000, 2, 9, 2, 8, 4]])

    def test_daily_aggregation(self):
        a = ms(2026, 1, 13, 22, 58)
        rows = [row(a, 10, 12, 9, 11, 1), row(a + 60_000, 11, 20, 10, 15, 2), row(a + 120_000, 15, 16, 1, 3, 4)]
        bars = vb.aggregate(rows, vb.tf_key("1D"))
        self.assertEqual(bars, [[ms(2026, 1, 12, 23), 10, 20, 9, 15, 3], [ms(2026, 1, 13, 23), 15, 16, 1, 3, 4]])


class TestBuild(unittest.TestCase):
    def write_csv(self, d, lines):
        p = Path(d) / "in.csv"
        with open(p, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["timestampMs", "open", "high", "low", "close", "volume"])
            w.writerows(lines)
        return p

    def test_dup_and_out_of_order(self):
        with tempfile.TemporaryDirectory() as d:
            t = ms(2026, 1, 13, 10, 0)
            p = self.write_csv(d, [(t + 60000, 2, 2, 2, 2, 2), (t, 1, 1, 1, 1, 1), (t, 5, 5, 5, 5, 5)])
            arrs, n = vb.read_csv(p)
            rows, dups = vb.sort_dedup(arrs)
            self.assertEqual((n, dups, len(rows)), (3, 1, 2))
            self.assertEqual([r[0] for r in rows], [t, t + 60000])
            self.assertEqual(rows[0][1], 5)  # keep last

    def test_1m_trim_and_rounding(self):
        with tempfile.TemporaryDirectory() as d:
            last = ms(2026, 1, 13, 10, 0)
            rows = [row(last - 3 * vb.DAY_MS, 1225.6000000001, 1225.7, 1225.5, 1225.6, 7),
                    row(last - 1 * vb.DAY_MS, 1225.1, 1225.1999999, 1225.1, 1225.1, 7),
                    row(last, 1230.3, 1230.3, 1230.3, 1230.3, 7)]
            summ = vb.build(rows, "GC", 0.1, d, "in.csv", days_1m=2, days_5m=2, days_15m=2)
            self.assertEqual(summ["1m"][0], 2)
            self.assertEqual(summ["1h"][0], 3)  # 1h keeps everything
            txt = (Path(d) / "GC" / "data_1m.js").read_text()
            m = re.fullmatch(r'window\.VIZ_DATA = window\.VIZ_DATA \|\| \{\}; window\.VIZ_DATA\["1m"\] = (.*);\n', txt)
            self.assertIsNotNone(m)
            data = json.loads(m.group(1))
            self.assertEqual(data[0], [(last - vb.DAY_MS) // 1000, 1225.1, 1225.2, 1225.1, 1225.1, 7])
            alltxt = "".join(p.read_text() for p in (Path(d) / "GC").glob("data_*.js"))
            self.assertNotIn("1225.6000000001", alltxt)
            meta = (Path(d) / "GC" / "meta.js").read_text()
            self.assertTrue(meta.startswith("window.VIZ_META = "))
            self.assertEqual(json.loads(meta[len("window.VIZ_META = "):-2])["counts"]["1m"], 2)

    def test_tick_decimals(self):
        self.assertEqual(vb.tick_decimals(0.1), 1)
        self.assertEqual(vb.tick_decimals(0.25), 2)
        self.assertEqual(vb.tick_decimals(1), 0)


class TestOverlays(unittest.TestCase):
    T0 = ms(2026, 10, 14, 13, 0)

    def blk(self, **kw):
        b = {"id": "a", "side": "long", "start": self.T0, "end": self.T0 + 3_600_000,
             "entry": 100.0, "stop": 95.0, "target": 110.0}
        b.update(kw)
        return b

    def test_valid_long_and_short(self):
        l = vb.validate_block(self.blk())
        self.assertEqual((l["side"], l["start"], l["end"]), ("long", self.T0 // 1000, self.T0 // 1000 + 3600))
        s = vb.validate_block(self.blk(side="short", stop=105.0, target=90.0, label="x"))
        self.assertEqual((s["side"], s["label"]), ("short", "x"))

    def test_wrong_ordering_rejected(self):
        with self.assertRaises(ValueError):
            vb.validate_block(self.blk(stop=105.0))                      # long with stop above entry
        with self.assertRaises(ValueError):
            vb.validate_block(self.blk(side="short"))                    # short with long prices
        with self.assertRaises(ValueError):
            vb.validate_block(self.blk(end=self.T0))                     # start == end
        with self.assertRaises(ValueError):
            vb.validate_block(self.blk(side="flat"))

    def test_time_parsing(self):
        self.assertEqual(vb.parse_time_s("2026-10-14T13:00:00Z"), self.T0 // 1000)
        self.assertEqual(vb.parse_time_s(self.T0), self.T0 // 1000)
        self.assertEqual(vb.parse_time_s(self.T0 // 1000), self.T0 // 1000)

    def test_file_written_and_invalid_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d) / "o.json"
            src.write_text(json.dumps([self.blk(start="2026-10-14T13:00:00Z", end="2026-10-14T14:00:00Z"),
                                       self.blk(id="bad", stop=200.0)]))
            blocks = vb.load_overlays(src)
            self.assertEqual([b["id"] for b in blocks], ["a"])
            vb.write_overlays(d, blocks)
            txt = (Path(d) / "overlays.js").read_text()
            m = re.fullmatch(r"window\.VIZ_OVERLAYS = (.*);\n", txt)
            self.assertEqual(json.loads(m.group(1))[0]["start"], self.T0 // 1000)
            vb.write_overlays(d, [])
            self.assertEqual((Path(d) / "overlays.js").read_text(), "window.VIZ_OVERLAYS = [];\n")


class TestProfiles(unittest.TestCase):
    TICK = 0.25

    def synth(self, start_ms, n, seed):
        """n deterministic 1m bars from start_ms: wandering price, varying volume."""
        out, px, x = [], 20000.0, seed
        for i in range(n):
            x = (x * 1103515245 + 12345) % (2 ** 31)
            step = ((x % 41) - 20) * self.TICK
            o = px
            c = px + step
            h = max(o, c) + (x % 7) * self.TICK
            l = min(o, c) - (x % 5) * self.TICK
            out.append(row(start_ms + i * 60_000, o, h, l, c, 1 + x % 90))
            px = c
        return out

    def test_matches_gb_backtest(self):
        # pin the profile maths to the Gianluca backtest's own build(): same POC / VAH / VAL per session
        gb_dir = Path(__file__).resolve().parent.parent / "gb_nq_backtest"
        if not (gb_dir / "gb_backtest.py").exists():
            self.skipTest("gb_backtest.py not present")
        sys.path.insert(0, str(gb_dir))
        import gb_backtest as gb
        s1 = ms(2026, 1, 6, 23)            # 18:00 ET (winter) = session start
        s2 = ms(2026, 1, 7, 23)
        rows = self.synth(s1, 400, 7) + self.synth(s2, 400, 11)
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "x.csv"
            with open(p, "w") as f:
                f.write("timestampMs,open,high,low,close,volume\n")
                for r in rows:
                    f.write("%d,%s,%s,%s,%s,%s\n" % r)
            ref = gb.build(str(p), self.TICK, dict(tf=15, va_pct=0.70))["sessions"]
        mine = vb.build_profiles(rows, self.TICK, 0.70, min_bars=1)
        self.assertEqual(len(ref), 2)
        self.assertEqual(len(mine), 2)
        for a, b in zip(ref, mine):
            self.assertAlmostEqual(a["poc"], b["poc"])
            self.assertAlmostEqual(a["vah"], b["vah"])
            self.assertAlmostEqual(a["val"], b["val"])

    def test_profile_properties(self):
        rows = self.synth(ms(2026, 1, 6, 23), 400, 3)
        p = vb.session_profile(rows, self.TICK, 0.70)
        self.assertAlmostEqual(sum(p["vols"]), sum(r[5] for r in rows), places=6)   # volume conserved
        self.assertLessEqual(p["val"], p["poc"])
        self.assertLessEqual(p["poc"], p["vah"])
        k = lambda price: round((price - p["lo"]) / self.TICK)
        inside = sum(p["vols"][k(p["val"]):k(p["vah"]) + 1])
        self.assertGreaterEqual(inside, 0.70 * sum(p["vols"]) - 1e-6)               # value area holds >= 70%
        self.assertEqual(p["vols"][k(p["poc"])], max(p["vols"]))                    # POC is the busiest level

    def test_session_grouping_and_validity(self):
        # a full session, a short (holiday-style) session that must be dropped, then a short developing one that is kept
        a = self.synth(ms(2026, 1, 6, 23), 400, 1)
        b = self.synth(ms(2026, 1, 7, 23), 100, 2)
        c = self.synth(ms(2026, 1, 8, 23), 50, 3)
        profs = vb.build_profiles(a + b + c, self.TICK, 0.70)
        self.assertEqual([p["s"] for p in profs], [ms(2026, 1, 6, 23) // 1000, ms(2026, 1, 8, 23) // 1000])
        self.assertEqual([p["developing"] for p in profs], [False, True])
        self.assertEqual(profs[0]["e"] - profs[0]["s"], 23 * 3600)

    def test_write_profiles(self):
        rows = self.synth(ms(2026, 1, 6, 23), 400, 5)
        with tempfile.TemporaryDirectory() as d:
            vb.write_profiles(d, vb.build_profiles(rows, self.TICK), self.TICK, 0.70)
            m = re.fullmatch(r"window\.VIZ_PROFILES = (.*);\n", (Path(d) / "profiles.js").read_text())
            data = json.loads(m.group(1))
            s = data["sessions"][0]
            self.assertEqual(data["tick"], self.TICK)
            self.assertEqual(s["dev"], 1)
            self.assertLessEqual(s["val"], s["poc"])
            self.assertAlmostEqual(sum(s["v"]), sum(r[5] for r in rows), delta=len(s["v"]))   # int rounding only


class TestAutocorr(unittest.TestCase):
    def write_trades(self, d, outcomes):
        p = Path(d) / "t.csv"
        with open(p, "w") as f:
            f.write("entry_t,exit_t,r_net\n")
            for i, w in enumerate(outcomes):
                f.write("%d,%d,%s\n" % ((i + 1) * 1_000_000, (i + 1) * 1_000_000 + 60_000, 1.5 if w else -1.0))
        return p

    def test_strength_gates_and_direction(self):
        self.assertEqual(vb.ac_strength([1, 0] * 10), 0.0)                       # < 30 trades: warm-up
        self.assertEqual(vb.ac_strength([1, 0] * 20), 0.0)                       # alternating: no positive memory
        self.assertGreaterEqual(vb.ac_strength(([1] * 5 + [0] * 5) * 6), 3.0)    # streaky: strong memory

    def test_best_and_skip_patterns(self):
        base = ([1] * 5 + [0] * 5) * 4
        with tempfile.TemporaryDirectory() as d:
            ev = vb.autocorr_events(self.write_trades(d, base + [1]))            # ...L L L W  -> next trade is the best one
            self.assertEqual((ev[-1][2], ev[-1][3]), ("best", "LLLLW"))
            self.assertEqual(ev[-1][0], 41 * 1_000_000 // 1000 + 60)             # event time = that trade's EXIT, in seconds
            ev = vb.autocorr_events(self.write_trades(d, [0] * 5 + [1] * 5 + base[:-5] + [0]))
            self.assertEqual(ev[-1][2], "skip")                                  # ...W W W L  -> skip the next trade
            ev = vb.autocorr_events(self.write_trades(d, [1, 0] * 20 + [1]))
            self.assertEqual(ev[-1][2], "none")                                  # no memory: no pattern is acted on


class TestTradeBlocks(unittest.TestCase):
    def test_blocks_from_trades_csv(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "t.csv"
            lines = ["dir,entry_t,exit_t,entry,stop,target,exit,reason,r_net",
                     "long,1000000000,1000900000,100.0,98.0,104.0,104.0,target,1.9",
                     "short,2000000000,2000600000,200.0,203.0,194.0,203.0,stop,-1.1",
                     "long,3000000000,3000600000,100.0,101.0,104.0,104.0,target,1.0"]    # last: stop above entry, invalid
            p.write_text("\n".join(lines) + "\n", encoding="utf-8")
            b = vb.gb_trade_blocks(p)
        self.assertEqual([x["side"] for x in b], ["long", "short"])
        self.assertEqual((b[0]["start"], b[0]["end"]), (1_000_000, 1_000_900))           # ms -> s
        self.assertEqual((b[1]["entry"], b[1]["stop"], b[1]["target"]), (200.0, 203.0, 194.0))
        self.assertEqual(b[0]["label"], "#1 +1.90R target")
        self.assertEqual(b[1]["label"], "#2 -1.10R stop")


if __name__ == "__main__":
    unittest.main()
