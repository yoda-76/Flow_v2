#!/usr/bin/env python3
"""Tests for analysis/trade_view.py (plain unittest). Journals come from the daily
report's own test builder, so trade numbering/format stay in step with it; the
recorded data is synthetic but in the exact shape DataRecorder writes.

Run:  python analysis/test_trade_view.py
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import daily_report as dr  # noqa: E402
import test_daily_report as t  # noqa: E402
import trade_view as tv  # noqa: E402

DAY = t.DAY
E0 = t.ct_ms(DAY, 10, 0, 0)          # entry moment
SID = dr.session_id_for_day(DAY)


def candle(tt, o, h, l, c, rows):
    return {"t": tt, "o": o, "h": h, "l": l, "c": c, "v": sum(a + b for _, a, b in rows), "n": 1,
            "rows": [{"p": p, "a": a, "b": b} for p, a, b in rows]}


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.logs = self.root / "logs"
        self.logs.mkdir()
        self.data = self.root / "data"

    def tearDown(self):
        self.tmp.cleanup()

    def write_construct(self, name, lines, sid=SID):
        d = self.data / name
        d.mkdir(parents=True, exist_ok=True)
        f = d / f"{sid}.jsonl"
        head = json.dumps({"type": "header", "construct": name, "sessionId": sid, "intervalSeconds": 1, "t": lines[0]["t"]})
        body = [json.dumps(x, separators=(",", ":")) for x in lines]
        f.write_text("\n".join([head] + body) + "\n", encoding="utf-8")

    def long_trade(self, entry=4300.0, stop=4299.0, target=4302.0, exit_role="stop", exit_px=4299.0, exit_after=60_000):
        j = t.Journal(self.logs)
        j.submitted("BUY", "fade the low")
        j.fill(E0, "entry", "BUY", entry, 1)
        j.bracket(E0 + 300, "SELL", stop, target)
        j.fill(E0 + exit_after, exit_role, "SELL", exit_px, 0, order_id="2")
        j.write()

    def short_trade(self):
        j = t.Journal(self.logs)
        j.submitted("SELL", "fade the high")
        j.fill(E0, "entry", "SELL", 4300.0, -1)
        j.bracket(E0 + 300, "BUY", 4301.0, 4298.0)
        j.fill(E0 + 60_000, "stop", "BUY", 4301.0, 0, order_id="2")
        j.write()

    def trade(self, **kw):
        m = dr.build_model(dr.load_sessions(self.logs), DAY, self.data)
        return m["trades"][kw.get("n", 0)]

    def ctx(self, before=300, after=300):
        tr = self.trade()
        s, e = tr["entry_t"] - before * 1000, (tr["exit_t"] or tr["entry_t"]) + after * 1000
        ctx = {"found": {}}
        for name in ("footprint", "vwap", "liquidity_map", "big_trades", "market_structure"):
            lines, found = tv.load_construct(self.data, name, s, e)
            ctx[name], ctx["found"][name] = lines, found
        return tr, ctx

    def opts(self, **kw):
        o = {"before": 300, "after": 300, "flow_secs": 60, "ladder_ticks": 12, "tick": 0.1}
        o.update(kw)
        return o

    def page(self, **kw):
        tr, ctx = self.ctx()
        return tv.render(tr, 1, DAY, ctx, self.opts(**kw))


class TestLoading(Base):
    def test_only_lines_in_range_and_header_skipped(self):
        self.write_construct("vwap", [{"t": E0 + i * 1000, "vwap": 1.0 + i, "vol": 1} for i in range(-3, 8)])
        lines, found = tv.load_construct(self.data, "vwap", E0, E0 + 3000)
        self.assertTrue(found)
        self.assertEqual([x["t"] for x in lines], [E0, E0 + 1000, E0 + 2000, E0 + 3000])

    def test_missing_construct_reports_not_found(self):
        lines, found = tv.load_construct(self.data, "footprint", E0, E0 + 1000)
        self.assertEqual((lines, found), ([], False))

    def test_window_spanning_two_sessions_reads_both_files(self):
        boundary = t.ct_ms(DAY, 17, 0, 0)
        self.write_construct("vwap", [{"t": boundary - 2000, "vwap": 1.0, "vol": 1}, {"t": boundary - 1000, "vwap": 2.0, "vol": 1}], SID)
        self.write_construct("vwap", [{"t": boundary, "vwap": 3.0, "vol": 1}, {"t": boundary + 1000, "vwap": 4.0, "vol": 1}], SID + 1)
        lines, _ = tv.load_construct(self.data, "vwap", boundary - 1500, boundary + 500)
        self.assertEqual([x["vwap"] for x in lines], [2.0, 3.0])

    def test_last_at_or_before(self):
        ls = [{"t": 1, "x": "a"}, {"t": 5, "x": "b"}, {"t": 9, "x": "c"}]
        self.assertEqual(tv.last_at_or_before(ls, 5)["x"], "b")
        self.assertEqual(tv.last_at_or_before(ls, 8)["x"], "b")
        self.assertIsNone(tv.last_at_or_before(ls, 0))


class TestAnalysis(Base):
    def test_excursions_long(self):
        self.long_trade()
        tr = self.trade()
        cs = [candle(E0 + 1000, 4300, 4300.5, 4299.8, 4300.2, [(4300.0, 1, 0)]),
              candle(E0 + 20_000, 4300.2, 4301.4, 4300.1, 4301.0, [(4301.0, 1, 0)]),   # MFE +1.4
              candle(E0 + 40_000, 4301.0, 4301.1, 4299.2, 4299.3, [(4299.3, 0, 1)])]   # MAE 0.8
        ex = tv.excursions(tr, cs)
        self.assertAlmostEqual(ex["mfe"], 1.4)
        self.assertEqual(ex["mfe_t"], E0 + 20_000)
        self.assertAlmostEqual(ex["mae"], 0.8)
        self.assertEqual(ex["mae_t"], E0 + 40_000)
        self.assertAlmostEqual(ex["target_dist"], 2.0)
        self.assertAlmostEqual(ex["target_pct"], 70.0)   # 1.4 of 2.0
        self.assertAlmostEqual(ex["stop_dist"], 1.0)
        self.assertAlmostEqual(ex["stop_pct"], 80.0)     # 0.8 of 1.0

    def test_excursions_short_is_mirrored(self):
        self.short_trade()
        tr = self.trade()
        cs = [candle(E0 + 5000, 4300, 4300.6, 4299.1, 4299.5, [(4299.5, 0, 1)])]   # favour: 0.9 down; against: 0.6 up
        ex = tv.excursions(tr, cs)
        self.assertAlmostEqual(ex["mfe"], 0.9)
        self.assertAlmostEqual(ex["mae"], 0.6)
        self.assertAlmostEqual(ex["target_dist"], 2.0)     # 4300 -> 4298
        self.assertAlmostEqual(ex["stop_dist"], 1.0)       # 4300 -> 4301

    def test_excursion_never_negative_and_none_without_candles(self):
        self.long_trade()
        tr = self.trade()
        self.assertIsNone(tv.excursions(tr, []))
        ex = tv.excursions(tr, [candle(E0 + 1000, 4299.5, 4299.6, 4299.4, 4299.5, [(4299.5, 0, 1)])])
        self.assertEqual(ex["mfe"], 0.0)   # never went in favour: reported as 0, not a negative "best"

    def test_flow_totals_and_ladder(self):
        cs = [candle(1, 4300, 4300.1, 4300, 4300.1, [(4300.0, 2, 3), (4300.1, 4, 0)]),
              candle(2, 4300, 4300, 4300, 4300, [(4300.0, 1, 1)])]
        f = tv.flow_totals(cs)
        self.assertEqual((f["ask"], f["bid"], f["vol"], f["delta"]), (7, 4, 11, 3))
        lad = tv.ladder(cs, 0.1)
        self.assertEqual(lad[43000], [3, 4])
        self.assertEqual(lad[43001], [4, 0])

    def test_book_in_the_way_long_and_short(self):
        self.long_trade()          # entry 4300.0, stop 4299.0, target 4302.0
        tr = self.trade()
        snap = {"asks": [{"p": 4300.5, "s": 10}, {"p": 4301.9, "s": 5}, {"p": 4302.0, "s": 1}, {"p": 4302.1, "s": 99}],
                "bids": [{"p": 4299.9, "s": 7}, {"p": 4299.0, "s": 3}, {"p": 4298.9, "s": 50}]}
        w = tv.book_in_the_way(snap, tr, 0.1)
        self.assertEqual(w["target_blocking"], 16)   # asks 4300.0..4302.0 inclusive; 4302.1 excluded
        self.assertEqual(w["stop_support"], 10)      # bids 4299.0..4300.0; 4298.9 excluded
        # a short: the side that blocks is the BIDS between entry and target
        self.tearDown(); self.setUp()
        self.short_trade()         # entry 4300.0, stop 4301.0, target 4298.0
        s = self.trade()
        w2 = tv.book_in_the_way({"asks": [{"p": 4300.4, "s": 4}, {"p": 4301.0, "s": 6}], "bids": [{"p": 4299.0, "s": 9}, {"p": 4297.9, "s": 80}]}, s, 0.1)
        self.assertEqual(w2["target_blocking"], 9)
        self.assertEqual(w2["stop_support"], 10)

    def test_bucket_choice_keeps_tables_short(self):
        self.assertEqual(tv.pick_bucket(5 * 60 * 1000), 10_000)          # 5 min -> 30 rows of 10 s
        self.assertLessEqual(20 * 60 * 1000 / tv.pick_bucket(20 * 60 * 1000), 40)
        self.assertEqual(tv.pick_bucket(10 * 3600 * 1000), 900_000)

    def test_bucketize_aggregates_and_attaches_vwap(self):
        cs = [candle(E0 + 1000, 4300.0, 4300.3, 4299.9, 4300.1, [(4300.0, 2, 1)]),
              candle(E0 + 5000, 4300.1, 4300.6, 4300.0, 4300.5, [(4300.5, 3, 0)]),
              candle(E0 + 25_000, 4300.5, 4300.5, 4300.4, 4300.4, [(4300.4, 0, 2)])]
        vw = [{"t": E0, "vwap": 4300.0}, {"t": E0 + 12_000, "vwap": 4300.2}, {"t": E0 + 29_000, "vwap": 4300.3}]
        b = tv.bucketize(cs, vw, 10_000, E0)
        self.assertEqual(len(b), 2)                                       # the middle bucket had no trades: skipped
        first = b[0]
        self.assertEqual((first["o"], first["h"], first["l"], first["c"]), (4300.0, 4300.6, 4299.9, 4300.5))
        self.assertEqual((first["ask"], first["bid"]), (5, 1))
        self.assertEqual(first["vwap"], 4300.0)                           # bucket 0 = [E0, E0+10s): the 4300.2 print is AFTER it
        self.assertEqual(b[1]["vwap"], 4300.3)                            # bucket [E0+20s, E0+30s) ends after the 4300.3 print


class TestRendering(Base):
    def _full_data(self):
        self.long_trade()
        fp = [candle(E0 - 30_000, 4300.3, 4300.4, 4300.2, 4300.3, [(4300.3, 3, 1)]),
              candle(E0 - 5000, 4300.1, 4300.1, 4300.0, 4300.0, [(4300.0, 1, 4)]),
              candle(E0 + 10_000, 4300.0, 4300.9, 4299.9, 4300.8, [(4300.0, 0, 2), (4300.8, 6, 1)]),
              candle(E0 + 59_000, 4300.0, 4300.0, 4299.0, 4299.0, [(4299.0, 0, 3)]),
              candle(E0 + 90_000, 4299.0, 4299.1, 4298.9, 4299.0, [(4299.0, 2, 2)])]
        self.write_construct("footprint", fp)
        self.write_construct("vwap", [{"t": E0 + i * 10_000, "vwap": 4300.4 - i * 0.05, "vol": 100 + i} for i in range(-3, 10)])
        self.write_construct("liquidity_map", [{"t": E0 - 1000, "bid": 4299.9, "ask": 4300.1, "windowTicks": 100,
                                                "bids": [{"p": 4299.9, "s": 4}, {"p": 4299.5, "s": 20}],
                                                "asks": [{"p": 4300.1, "s": 3}, {"p": 4301.0, "s": 40}]}])

    def test_full_page_has_every_section_and_the_numbers(self):
        self._full_data()
        p = self.page()
        for h in ("# Trade 1 — LONG 1", "## How the trade travelled", "## Order flow: before, during, after",
                  "## Price over time", "## Footprint ladder — the 60s before entry", "## Footprint ladder — during the trade",
                  "## Resting liquidity at entry", "## Big trades during the trade", "## Market structure"):
            self.assertIn(h, p)
        self.assertIn("fade the low", p)
        self.assertIn("stop 4299.0 · target 4302.0", p)
        self.assertIn("**Best it got (MFE):** +0.9 pts", p)         # 4300.9 high
        self.assertIn("**Worst (MAE):** 1.0 pts", p)                # 4299.0 low
        self.assertIn("Went **45%** of the way to the target", p)   # 0.9 / 2.0
        self.assertIn("Went **100%** of the way to the stop", p)
        self.assertIn("Entry vs VWAP", p)
        self.assertIn("◀ ENTRY", p)
        self.assertIn("◀ EXIT", p)

    def test_flow_windows_before_during_after(self):
        self._full_data()
        p = self.page()
        # before entry (60 s): candles at -30 s and -5 s -> ask 4, bid 5 -> vol 9, delta -1
        self.assertIn("| 60s before entry | 9 | 4 | 5 | -1 |", p)
        # during: +10 s and +59 s (exit at +60 s) -> ask 6, bid 6 (2+1+3) -> vol 12, delta 0
        self.assertIn("| during the trade | 12 | 6 | 6 | +0 |", p)
        # after exit (60 s): the candle at +90 s -> ask 2 bid 2
        self.assertIn("| 60s after exit | 4 | 2 | 2 | +0 |", p)

    def test_candle_exactly_at_entry_or_exit_belongs_to_the_trade(self):
        self.long_trade()   # entry at E0, exit at E0 + 60 s
        self.write_construct("footprint", [
            candle(E0 - 1000, 4300, 4300, 4300, 4300, [(4300.0, 1, 0)]),        # last second before entry -> "before"
            candle(E0, 4300, 4300, 4300, 4300, [(4300.0, 0, 5)]),                # the entry second -> "during", NOT before
            candle(E0 + 60_000, 4299, 4299, 4299, 4299, [(4299.0, 7, 0)]),       # the exit second -> "during", NOT after
            candle(E0 + 61_000, 4299, 4299, 4299, 4299, [(4299.0, 0, 3)])])     # first second after exit -> "after"
        p = self.page()
        self.assertIn("| 60s before entry | 1 | 1 | 0 | +1 |", p)
        self.assertIn("| during the trade | 12 | 7 | 5 | +2 |", p)
        self.assertIn("| 60s after exit | 3 | 0 | 3 | -3 |", p)

    def test_liquidity_section_numbers(self):
        self._full_data()
        p = self.page()
        self.assertIn("**In the way of the target:** 43 lots", p)   # asks 4300.1(3) + 4301.0(40) between 4300.0 and 4302.0
        self.assertIn("**Behind the stop:** 24 lots", p)            # bids between the stop 4299.0 and entry 4300.0: 4299.9 (4) + 4299.5 (20)
        self.assertIn("Best bid 4299.9, best ask 4300.1", p)

    def test_missing_constructs_are_said_out_loud(self):
        self.long_trade()
        p = self.page()
        self.assertIn("No footprint data was recorded", p)
        self.assertIn("No liquidity-map data was recorded", p)
        self.assertIn("No VWAP data recorded", p)
        self.assertIn("No big-trade data recorded", p)
        self.assertIn("No market-structure data recorded", p)

    def test_ladder_trims_empty_rows_but_keeps_the_trades_own_lines(self):
        self._full_data()
        tr, ctx = self.ctx()
        pre = [c for c in ctx["footprint"] if c["t"] < E0]
        text = "\n".join(tv.render_ladder(tv.ladder(pre, 0.1), tr, 0.1, 43000 - 12, 43000 + 12, "T"))
        rows = [l for l in text.split("\n") if l.startswith("| 4")]
        self.assertEqual(len(rows), 16, "trimmed from the 25-tick window to: highest traded price+1 .. stop-1")
        self.assertTrue(rows[0].startswith("| 4300.4 |"), "one tick above the highest traded price (4300.3)")
        self.assertTrue(rows[-1].startswith("| 4298.9 |"), "one tick below the stop line")
        self.assertTrue(any("ENTRY" in r for r in rows))
        self.assertTrue(any("4299.0" in r and "stop" in r for r in rows), "the stop line is kept even with no volume there")

    def test_big_trades_and_market_structure_when_present(self):
        self._full_data()
        self.write_construct("big_trades", [{"t": E0 + 20_000, "trades": [{"ts": E0 + 19_500, "p": 4300.8, "size": 12, "ask": True}]}])
        self.write_construct("market_structure", [{"t": E0 + 30_000, "price": 4300.5, "changes": ["trend UP->DOWN"], "trend": "DOWN", "pullback": "NONE"}])
        p = self.page()
        self.assertIn("BUY  12 @ 4300.8  ◀ during trade", p)
        self.assertIn("trend UP->DOWN", p)

    def test_open_trade_renders_without_exit(self):
        j = t.Journal(self.logs)
        j.submitted("BUY", "still open")
        j.fill(E0, "entry", "BUY", 4300.0, 1)
        j.write()
        self.write_construct("footprint", [candle(E0 + 1000, 4300, 4300.2, 4299.9, 4300.1, [(4300.0, 1, 0)])])
        p = self.page()
        self.assertIn("**Exit:** still open", p)


class TestCli(Base):
    def _run(self, *args):
        return tv.main(["--logs", str(self.logs), "--data", str(self.data), *args])

    def test_list_then_view_then_file_written(self):
        self.long_trade()
        self.write_construct("footprint", [candle(E0 + 1000, 4300, 4300.2, 4299.9, 4300.1, [(4300.0, 1, 0)])])
        self.assertEqual(self._run("--date", DAY.isoformat()), 0)
        out = self.root / "v.md"
        self.assertEqual(self._run("--date", DAY.isoformat(), "--trade", "1", "--out", str(out)), 0)
        self.assertIn("# Trade 1", out.read_text(encoding="utf-8"))

    def test_bad_trade_number_is_a_clean_error(self):
        self.long_trade()
        self.assertEqual(self._run("--date", DAY.isoformat(), "--trade", "2", "--no-file"), 2)
        self.assertEqual(self._run("--date", DAY.isoformat(), "--trade", "0", "--no-file"), 2)

    def test_legacy_trade_without_fill_data_is_a_clean_error(self):
        j = t.Journal(self.logs)
        j.submitted("SELL", "legacy")
        j.log(t.ct_ms(DAY, 10), "ORDER_FILLED bp.aa@1")
        j.write()
        self.assertEqual(self._run("--date", DAY.isoformat(), "--no-file"), 0)   # lists nothing, no crash
        self.assertEqual(self._run("--date", DAY.isoformat(), "--trade", "1", "--no-file"), 2)

    def test_trade_with_a_fill_but_no_price_is_a_clean_error(self):
        j = t.Journal(self.logs)
        j.submitted("BUY", "price unavailable")
        j.fill(E0, "entry", "BUY", None, 1)
        j.write()
        self.assertEqual(self._run("--date", DAY.isoformat(), "--trade", "1", "--no-file"), 2)

    def test_missing_logs_dir(self):
        self.assertEqual(tv.main(["--logs", str(self.root / "nope"), "--no-file"]), 2)


if __name__ == "__main__":
    unittest.main(verbosity=1)
