"""Hand-made bar sequences for the signal rules (plan §9 P1B). TF = 1 so every scripted 1m bar is a TF bar."""
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import data  # noqa: E402
import features as F  # noqa: E402
import signals as S  # noqa: E402

BG = (99.0, 99.0, 99.0, 99.0, 100)
AT = 100                       # scripted bars start at this bar of session 1
# a long setup against prev VAH = 100.0: leg r2..r5 (swing high r2, bearish r3/r4, BOS r5), MS low 98.5,
# MS high 103.5 (r6), touch r8, trigger r9 (entry 101.0, stop 100.0, risk 10 ticks, 2R target 103.0)
SCRIPT = [
    (99.0, 99.3, 98.9, 99.2, 100),     # r0
    (99.2, 99.6, 99.1, 99.5, 100),     # r1
    (99.5, 99.9, 99.4, 99.7, 100),     # r2 swing high
    (99.7, 99.75, 99.0, 99.05, 100),   # r3 valid bearish
    (99.05, 99.1, 98.5, 98.6, 100),    # r4 valid bearish, leg low
    (98.6, 100.8, 98.55, 100.6, 100),  # r5 BOS, above VAH
    (100.6, 103.5, 100.5, 101.2, 100), # r6 MS high
    (101.2, 101.3, 100.4, 100.5, 100), # r7
    (100.5, 100.6, 99.9, 100.1, 100),  # r8 touch
    (100.1, 101.0, 100.0, 100.9, 80),  # r9 trigger (reducing volume)
]
TARGET_HIT = (101.0, 103.2, 100.9, 103.1, 150)


def utc(y, mo, d, h):
    return int(datetime(y, mo, d, h, tzinfo=timezone.utc).timestamp())


def make_ctx(script, after=BG, mirror=False, at=AT):
    sessions = []
    for s in range(2):
        bars_ = [BG] * 1380
        if s == 1:
            bars_ = [BG] * at + list(script) + [after] * (1380 - at - len(script))
        sessions.append(bars_)
    rows = []
    for s, bars_ in enumerate(sessions):
        t0 = utc(2020, 7, 12 + s, 22)
        for i, (o, h, l, c, v) in enumerate(bars_):
            if mirror:
                o, h, l, c = 200 - o, 200 - l, 200 - h, 200 - c
            rows.append(f"{(t0 + 60 * i) * 1000},{o:.2f},{h:.2f},{l:.2f},{c:.2f},{v}\n")
    base = data.parse_csv(None, rows=rows)
    va = (995, 1000, 990) if not mirror else (1005, 1010, 1000)
    vas = {70: [va, va]}
    f = F.build_tf(base, 1)
    return S.Ctx(base, vas, f)


def params(**kw):
    p = dict(S.BASELINE, tf=1, swing_n=2, trend="none")
    p.update(kw)
    return p


def with_bars(*extra):
    return SCRIPT + list(extra)


class SignalTest(unittest.TestCase):
    def test_target(self):
        t = S.run(make_ctx(with_bars(TARGET_HIT)), params())
        self.assertEqual(len(t), 1)
        x = t[0]
        self.assertEqual((x["entry"], x["stop"], x["target"], x["exit"], x["reason"]), (1010, 1000, 1030, 1030, "target"))
        self.assertAlmostEqual(x["r"], 2.0)
        self.assertAlmostEqual(x["usd"], 195.4)
        self.assertTrue(x["first"]); self.assertTrue(x["reducing"])

    def test_stop_first_same_bar(self):
        t = S.run(make_ctx(with_bars((101.0, 103.2, 99.9, 103.1, 150))), params())
        self.assertEqual((t[0]["reason"], t[0]["exit"]), ("stop", 999))
        self.assertAlmostEqual(t[0]["r"], -1.1)

    def test_gap_stop(self):
        t = S.run(make_ctx(with_bars((99.5, 99.6, 99.0, 99.2, 150))), params())
        self.assertEqual((t[0]["reason"], t[0]["exit"]), ("stop", 994))

    def test_session_flatten(self):
        t = S.run(make_ctx(SCRIPT, after=(101.0, 101.0, 101.0, 101.0, 100)), params())
        self.assertEqual((t[0]["reason"], t[0]["exit"]), ("session_end", 1009))

    def test_engulf_margin(self):
        self.assertEqual(len(S.run(make_ctx(with_bars(TARGET_HIT)), params(thr="t2"))), 1)
        self.assertEqual(len(S.run(make_ctx(with_bars(TARGET_HIT)), params(thr="t4"))), 0)   # needs C >= 101.0

    def test_vah_break_margin(self):
        sc = list(SCRIPT)
        sc[8] = (99.9, 99.95, 99.6, 99.7, 100)       # touch bar with H 99.95 < VAH
        sc[9] = (99.7, 100.2, 99.65, 100.1, 80)      # engulf ok with t1 (>= 100.05); VAH break needs >= 100.1
        ok = S.run(make_ctx(sc + [TARGET_HIT]), params(thr="t1", min_rr=0))
        self.assertEqual(len(ok), 1)
        sc[9] = (99.7, 100.2, 99.65, 100.05, 80)
        self.assertEqual(len(S.run(make_ctx(sc + [TARGET_HIT]), params(thr="t1", min_rr=0))), 0)

    def test_invalid_candles_no_ms_low(self):
        sc = list(SCRIPT)
        sc[3] = (99.7, 99.85, 98.9, 99.6, 100)       # body 0.1 < wicks: not valid
        sc[4] = (99.6, 99.8, 98.5, 99.5, 100)
        self.assertEqual(len(S.run(make_ctx(sc + [TARGET_HIT]), params())), 0)

    def test_ms_low_cancel(self):
        sc = list(SCRIPT)
        sc[8] = (100.5, 100.6, 98.4, 100.1, 100)     # touch trades below MS low 98.5
        self.assertEqual(len(S.run(make_ctx(sc + [TARGET_HIT]), params())), 0)
        # close variant: a wick below is fine, only a close below cancels
        self.assertEqual(len(S.run(make_ctx(sc + [TARGET_HIT]), params(ms_break="close"))), 1)

    def test_ceiling_and_min_rr(self):
        sc = list(SCRIPT)
        sc[6] = (100.6, 101.5, 100.5, 101.2, 100)    # MS high 101.5 -> capped target 0.5R
        ctx = make_ctx(sc + [TARGET_HIT])
        self.assertEqual(len(S.run(ctx, params())), 0)
        t = S.run(ctx, params(min_rr=0))
        self.assertEqual((t[0]["target"], t[0]["reason"]), (1015, "target"))
        t = S.run(ctx, params(ceiling="off"))
        self.assertEqual((t[0]["target"], t[0]["reason"]), (1030, "target"))

    def test_reentry_only_when_flat(self):
        extra = [
            (101.0, 102.0, 100.5, 101.5, 100),     # r10: no exit
            (101.5, 102.3, 101.4, 102.2, 50),      # r11: engulf + reducing, but still in the trade
            (102.2, 103.2, 102.1, 103.1, 150),     # r12: target hit
            (103.1, 103.2, 102.0, 102.1, 100),     # r13
            (102.1, 103.4, 102.0, 103.3, 60),      # r14: engulf + reducing, flat -> re-entry
        ]
        t = S.run(make_ctx(SCRIPT + extra, after=(103.0, 103.0, 103.0, 103.0, 100)), params(ceiling="off"))
        self.assertEqual(len(t), 2)
        self.assertEqual(t[0]["reason"], "target")
        self.assertEqual((t[1]["k"] - t[0]["k"], t[1]["first"], t[1]["entry"]), (5, False, 1034))
        self.assertEqual(t[1]["reason"], "session_end")

    def test_news_window(self):
        # session 1 opens 18:00 EDT; bar AT+9 closes at 18:00 + 110 min, far from 08:30: the filter doesn't block it
        self.assertEqual(len(S.run(make_ctx(with_bars(TARGET_HIT)), params(news="0830"))), 1)

    def test_news_blocks_0830(self):
        ctx = make_ctx(with_bars(TARGET_HIT), at=860)   # trigger bar closes 18:00 + 870 min = 08:30 ET
        self.assertEqual(len(S.run(ctx, params())), 1)
        self.assertEqual(len(S.run(ctx, params(news="0830"))), 0)

    def test_no_entry_after_1645(self):
        self.assertEqual(len(S.run(make_ctx(with_bars(TARGET_HIT), at=1354), params())), 1)   # closes 16:44
        self.assertEqual(len(S.run(make_ctx(with_bars(TARGET_HIT), at=1355), params())), 0)   # closes 16:45

    def test_variant_sess_filter(self):
        asia = make_ctx(with_bars(TARGET_HIT))              # trigger closes ~19:50 ET -> ASIA
        ny = make_ctx(with_bars(TARGET_HIT), at=860)        # 08:30 ET -> NY
        self.assertEqual(len(S.run(asia, params(sess_filter="asia_only"))), 1)
        self.assertEqual(len(S.run(asia, params(sess_filter="no_ny"))), 1)
        self.assertEqual(len(S.run(ny, params(sess_filter="no_ny"))), 0)
        self.assertEqual(len(S.run(ny, params(sess_filter="asia_only"))), 0)

    def test_variant_ma_filter(self):
        # background 99.0 for > 200 bars, trigger closes 100.9: above SMA(200) for the long ...
        self.assertEqual(len(S.run(make_ctx(with_bars(TARGET_HIT)), params(ma_filter="200"))), 1)
        # ... and the mirrored short is below it (mirror symmetry)
        self.assertEqual(len(S.run(make_ctx(with_bars(TARGET_HIT), mirror=True), params(ma_filter="200"))), 1)

    def test_variant_stop_atr_and_filters_baseline(self):
        ctx = make_ctx(with_bars(TARGET_HIT))
        base_t = S.run(ctx, params())
        same = S.run(ctx, params(sess_filter="all", atr_filter="all", fresh_filter="all", stop_atr=0.0, ma_filter="off"))
        self.assertEqual(base_t, same)
        wide = S.run(ctx, params(stop_atr=0.5, ceiling="off"))
        self.assertLessEqual(wide[0]["stop"], base_t[0]["stop"])

    def test_short_mirror(self):
        ctx = make_ctx(with_bars(TARGET_HIT), mirror=True)
        t = S.run(ctx, params())
        self.assertEqual(len(t), 1)
        x = t[0]
        self.assertEqual(x["dir"], -1)
        self.assertEqual((x["entry"], x["stop"], x["target"], x["exit"], x["reason"]), (990, 1000, 970, 970, "target"))
        self.assertAlmostEqual(x["r"], 2.0)
        self.assertEqual(len(S.run(ctx, params(direction="long"))), 0)


if __name__ == "__main__":
    unittest.main()
