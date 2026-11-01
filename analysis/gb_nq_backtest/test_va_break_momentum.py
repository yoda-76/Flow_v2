import os, sys, unittest, datetime
from datetime import timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import va_break_momentum as V


def ms(y, mo, d, h, mi):
    return int(datetime.datetime(y, mo, d, h, mi, tzinfo=timezone.utc).timestamp() * 1000)


class TestValueArea(unittest.TestCase):
    def test_single_bar_spreads_evenly(self):
        vah, val, poc = V.value_area([(100.0, 90.0, 10.0)], 5.0, 0.7)
        self.assertAlmostEqual(val, 90.0)
        self.assertAlmostEqual(vah, 100.0)
        self.assertAlmostEqual(poc, 90.0 + 0.5 * 10 / 3)     # 3 buckets, first one is the POC on a tie

    def test_heavy_bar_pins_value_area_to_its_bucket(self):
        vah, val, poc = V.value_area([(101.0, 100.0, 100.0), (120.0, 100.0, 10.0)], 10.0, 0.7)
        self.assertAlmostEqual(val, 100.0)
        self.assertAlmostEqual(vah, 100.0 + 20 / 3)           # only bucket 0 is needed (103 of 110 > 70%)
        self.assertAlmostEqual(poc, 100.0 + 0.5 * 20 / 3)

    def test_degenerate_inputs(self):
        self.assertIsNone(V.value_area([], 10.0, 0.7))
        self.assertIsNone(V.value_area([(100.0, 100.0, 5.0)], 10.0, 0.7))
        self.assertIsNone(V.value_area([(101.0, 100.0, 0.0)], 10.0, 0.7))

    def test_tie_between_up_and_down_goes_up(self):
        # 3 buckets of 20/3 over 0..20 holding [10, 30 (POC), 10]; 70% of 50 = 35 needs one more bucket and both
        # neighbours tie at 10 -> the source takes the upper one (up >= dn)
        bars = [(6.5, 0.0, 10.0), (13.0, 7.0, 30.0), (20.0, 14.0, 10.0)]
        vah, val, _ = V.value_area(bars, 10.0, 0.7)
        self.assertAlmostEqual(val, 20 / 3)
        self.assertAlmostEqual(vah, 20.0)

    def test_expands_toward_the_heavier_side(self):
        # buckets [10, 30 (POC), 25]: the heavier neighbour (up, 25) is taken first
        bars = [(6.5, 0.0, 10.0), (13.0, 7.0, 30.0), (20.0, 14.0, 25.0)]
        vah, val, _ = V.value_area(bars, 10.0, 0.7)
        self.assertAlmostEqual(val, 20 / 3)
        self.assertAlmostEqual(vah, 20.0)


class TestExit(unittest.TestCase):
    def m1(self, rows):   # rows: (o,h,l,c)
        n = len(rows)
        return ([i * 60000 for i in range(n)], [r[0] for r in rows], [r[1] for r in rows], [r[2] for r in rows],
                [r[3] for r in rows], [1] * n)

    def test_long_stop_wins_when_one_bar_touches_both(self):
        m1 = self.m1([(100, 111, 94, 100)])
        px, i, why = V.simulate_exit(1, 95.0, 110.0, 0, m1, 0.25, 0.25)
        self.assertEqual((why, i, px), ("stop", 0, 94.75))

    def test_long_stop_gap_fills_at_open(self):
        m1 = self.m1([(93, 94, 92, 93)])
        px, _, why = V.simulate_exit(1, 95.0, 110.0, 0, m1, 0.25, 0.25)
        self.assertEqual((why, px), ("stop", 92.75))

    def test_target_needs_one_tick_through(self):
        m1 = self.m1([(100, 110.0, 99, 100), (100, 110.25, 99, 100)])
        px, i, why = V.simulate_exit(1, 95.0, 110.0, 0, m1, 0.25, 0.25)
        self.assertEqual((why, i, px), ("target", 1, 110.0))

    def test_short_mirror(self):
        m1 = self.m1([(100, 100.5, 89.75, 100)])
        px, i, why = V.simulate_exit(-1, 105.0, 90.0, 0, m1, 0.25, 0.25)
        self.assertEqual((why, i, px), ("target", 0, 90.0))
        m1 = self.m1([(107, 108, 100, 100)])
        px, _, why = V.simulate_exit(-1, 105.0, 90.0, 0, m1, 0.25, 0.25)
        self.assertEqual((why, px), ("stop", 107.25))

    def test_data_end(self):
        m1 = self.m1([(100, 101, 99, 100.5)])
        px, i, why = V.simulate_exit(1, 90.0, 120.0, 0, m1, 0.25, 0.25)
        self.assertEqual((why, i, px), ("data_end", 0, 100.25))


class TestBarsAndClock(unittest.TestCase):
    def test_et_clock_follows_dst(self):
        c = V.EtClock()
        self.assertEqual(c.hhmm_date(ms(2026, 7, 1, 13, 30))[0], 930)    # EDT = UTC-4
        self.assertEqual(c.hhmm_date(ms(2026, 1, 15, 14, 30))[0], 930)   # EST = UTC-5

    def test_make_bars_groups_into_quarter_hours_and_skips_gaps(self):
        t0 = ms(2026, 1, 15, 14, 30)
        t = [t0, t0 + 60000, t0 + 14 * 60000, t0 + 15 * 60000, t0 + 45 * 60000]
        o = [1, 2, 3, 4, 5]; h = [10, 11, 12, 13, 14]; l = [0, 1, 2, 3, 4]; c = [5, 6, 7, 8, 9]; v = [1, 1, 1, 1, 1]
        bars = V.make_bars(t, o, h, l, c, v)
        self.assertEqual(len(bars), 3)                                   # the 15:00-15:30 slots are absent, not empty bars
        b = bars[0]
        self.assertEqual((b["o"], b["h"], b["l"], b["c"], b["v"], b["i0"], b["i1"]), (1, 12, 0, 7, 3, 0, 2))

    def test_wilder_atr(self):
        bars = [dict(h=10, l=0, c=5)] * 3
        a = V.atr_series(bars, 2)
        self.assertEqual(a[0], None)
        self.assertEqual(a[1], 10.0)                                     # SMA of the first two ranges
        self.assertEqual(a[2], 10.0)


def bar(h, l, c, o=None):
    return dict(h=h, l=l, c=c, o=c if o is None else o)


def pos(side=1, qty=4, entry=100.0, dist=10.0, sl=90.0, tp=120.0):
    meta = dict(stop0=sl, target0=tp, entry_t=0)
    return V.Position(side, qty, entry, sl, tp, dist, 20.0, 4.6, meta)


class TestPosition(unittest.TestCase):
    P1 = V.P_V1

    def test_partial_closes_half_at_1r_then_breakeven_only_on_a_later_bar(self):
        p = pos()
        self.assertIsNone(p.manage(bar(110.0, 99.0, 108.0), 1000, self.P1, 0.25))
        self.assertEqual((p.qty, p.partial_done, p.be_done, p.sl), (2, True, False, 90.0))   # partial sets closed_something
        self.assertAlmostEqual(p.gross, (107.75 - 100.0) * 20 * 2)                            # filled at close - 1 tick
        self.assertIsNone(p.manage(bar(110.5, 105.0, 109.0), 1010, self.P1, 0.25))
        self.assertEqual((p.qty, p.be_done, p.sl), (2, True, 100.0))

    def test_single_contract_skips_partial_and_goes_straight_to_breakeven(self):
        p = pos(qty=1)
        p.manage(bar(110.0, 99.0, 108.0), 1000, self.P1, 0.25)
        self.assertEqual((p.qty, p.partial_done, p.be_done, p.sl), (1, False, True, 100.0))

    def test_not_triggered_below_1r(self):
        p = pos()
        p.manage(bar(109.75, 99.0, 108.0), 1000, self.P1, 0.25)
        self.assertEqual((p.qty, p.partial_done, p.be_done), (4, False, False))

    def test_short_mirror(self):
        p = pos(side=-1, qty=2, entry=100.0, sl=110.0, tp=80.0)
        p.manage(bar(101.0, 90.0, 92.0), 1000, self.P1, 0.25)
        self.assertEqual((p.qty, p.partial_done), (1, True))
        self.assertAlmostEqual(p.gross, -1 * (92.25 - 100.0) * 20 * 1)                        # buys back at close + 1 tick

    def test_flatten_at_1550_open_time_closes_everything(self):
        p = pos(qty=3)
        self.assertIsNone(p.manage(bar(101.0, 99.0, 100.0), 1545, self.P1, 0.25))
        self.assertEqual(p.manage(bar(101.0, 99.0, 100.0), 1600, self.P1, 0.25), "flat")
        self.assertEqual((p.qty, p.reason), (0, "flatten"))
        self.assertAlmostEqual(p.comm, 4.6 * 3)

    def test_flatten_boundary_is_inclusive(self):
        p = pos(qty=1)
        self.assertEqual(p.manage(bar(101.0, 99.0, 100.0), 1550, self.P1, 0.25), "flat")

    def test_partial_never_closes_the_whole_position(self):
        p = pos(qty=2)
        p.manage(bar(110.0, 99.0, 108.0), 1000, dict(self.P1, partial_frac=1.0), 0.25)
        self.assertEqual((p.qty, p.partial_done, p.be_done), (2, False, True))     # no partial -> falls through to breakeven

    def test_v2_switches_do_nothing(self):
        p = pos()
        self.assertIsNone(p.manage(bar(500.0, 0.0, 100.0), 2000, V.P_V2, 0.25))
        self.assertEqual((p.qty, p.sl, p.partial_done, p.be_done), (4, 90.0, False, False))

    def test_trade_record_r_includes_partial_and_commission(self):
        p = pos(qty=2)
        p.reduce(1, 110.0)          # +10 pts x 20 x 1
        p.reduce(1, 90.0)           # -10 pts x 20 x 1
        p.reason, p.exit_t = "stop", 1
        t = p.trade()
        self.assertAlmostEqual(t["pnl_usd"], 0.0 - 4.6 * 2)
        self.assertAlmostEqual(t["r_net"], -9.2 / (10 * 20 * 2))
        self.assertEqual(t["qty"], 2)

    def test_scan_range_is_bounded(self):
        m1 = ([0, 1, 2], [100, 100, 100], [101, 101, 130], [99, 99, 99], [100, 100, 100], [1, 1, 1])
        self.assertIsNone(V.scan_range(1, 90.0, 120.0, 0, 1, m1, 0.25, 0.25))
        self.assertEqual(V.scan_range(1, 90.0, 120.0, 0, 2, m1, 0.25, 0.25)[2], "target")


class TestMemory(unittest.TestCase):
    P3 = V.P_V3

    def test_clustered_wins_score_full_strength(self):
        # 6 x (W W W W L L): after a win the next trade wins 3/4 of the time, overall 4/6 -> edge 0.083
        out = [1, 1, 1, 1, 0, 0] * 6
        pw, pww, s = V.memory_strength(out, self.P3)
        self.assertAlmostEqual(pw, 4 / 6)
        self.assertAlmostEqual(pww, 18 / 24)
        self.assertAlmostEqual(s, 10 * (18 / 24 - 4 / 6) / 0.16)

    def test_alternating_outcomes_have_negative_edge_so_zero_strength(self):
        _, pww, s = V.memory_strength([1, 0] * 20, self.P3)
        self.assertEqual((pww, s), (0.0, 0.0))      # after a win the next one always loses

    def test_needs_min_outcomes_and_min_after_win_cases(self):
        self.assertEqual(V.memory_strength([1, 1, 1, 0] * 7, self.P3), (0.0, 0.0, 0.0))             # 28 < 30 outcomes
        pw, pww, s = V.memory_strength([0] * 30 + [1], self.P3)                                       # one win -> 1 after-case < 5
        self.assertEqual((pww, s), (0.0, 0.0))
        self.assertGreater(pw, 0)

    def test_direction_flag_and_clamp(self):
        out = [1, 1, 1, 1, 0, 0, 0, 0] * 5                                                            # wins follow wins strongly
        self.assertEqual(V.memory_strength(out, self.P3)[2], 10.0)                                    # clamped
        self.assertEqual(V.memory_strength(out, dict(self.P3, mem_sign=-1))[2], 0.0)                  # opposite direction scores 0
        self.assertEqual(V.memory_strength(out, dict(self.P3, mem_enable=False)), (0.0, 0.0, 0.0))


class TestVersions(unittest.TestCase):
    def test_v1_overrides_only_v1_switches(self):
        for k in ("va_pct", "threshold", "swing_len", "rr_target", "f3", "risk_pct"):
            self.assertEqual(V.P_V1[k], V.P_V2[k])
        self.assertEqual((V.P_V1["max_trades_per_day"], V.P_V1["daily_loss_pct"], V.P_V1["max_stop_atr"], V.P_V1["min_rr"],
                          V.P_V1["entry_end"], V.P_V1["use_score_scaling"], V.P_V1["qty_floor_one"]),
                         (2, 1.5, 1.5, 1.0, 1530, False, False))
        self.assertFalse(V.P_V2["flatten_at_end"])


if __name__ == "__main__":
    unittest.main()
