#!/usr/bin/env python3
"""Tests for analysis/daily_report.py. Plain unittest, no third-party packages.

Journals here are synthetic but shaped like the real lines (intent_changed,
risk_verdict, real_order_submitted, log, heartbeat, risk_config_loaded were
copied from real sessions; order_fill is what OrderGateway.describeFill
writes -- OrderGatewayTest/LiveOrderTrackerTest pin its field names on the
Java side). What this file cannot prove is that a REAL armed session's journal
parses the same way: that is the first thing to check after the next live Sim
window (todo.md).

Run:  python analysis/test_daily_report.py
"""

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from unittest import mock
from datetime import date, datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import daily_report as dr  # noqa: E402

UTC = timezone.utc


def utc_ms(y, mo, d, h, mi=0, s=0):
    return int(datetime(y, mo, d, h, mi, s, tzinfo=UTC).timestamp() * 1000)


def ct_ms(day, h, mi=0, s=0):
    """Wall-clock CT -> epoch ms, via a fixed CDT (-5h) offset: valid for the September dates used here."""
    return utc_ms(day.year, day.month, day.day, h + 5, mi, s)


DAY = date(2026, 9, 23)  # a Wednesday; trading day window = 09-22 17:00 CT -> 09-23 17:00 CT


class Journal:
    """Builds a decisions.jsonl the way the runtime writes one."""

    def __init__(self, root: Path, name="lvn_fade_test_1790000000000_inst1", strategy="lvn_fade_test", start=None):
        self.dir = root / name
        self.dir.mkdir(parents=True)
        self.lines = []
        self.seq = 0
        self.intent_seq = 0
        start = start if start is not None else ct_ms(DAY, 9, 0)
        self.add({"type": "session_header", "strategyId": strategy, "symbol": "@GC", "tickSize": 0.1,
                  "sessionStartMs": start, "instanceId": 1, "mode": "DRY_RUN"})

    def add(self, obj):
        self.lines.append(json.dumps(obj, separators=(",", ":")))
        return self

    def raw(self, text):
        self.lines.append(text)
        return self

    def config(self, **over):
        cfg = {"type": "risk_config_loaded", "fixedContracts": 1, "maxContracts": 1, "dailyLossLimitTicks": 200,
               "rateLimitPerMinute": 6, "minDwellMs": 5000, "maxReversalsPerSession": 20,
               "lagQueueDepthThreshold": 1000, "lagProcessingMsThreshold": 2000, "fileLastModifiedMs": 1, "sessionStartMs": 2}
        cfg.update(over)
        return self.add(cfg)

    def heartbeat(self, t, exchange=None, armed=None, mode="DRY_RUN", denied=False):
        """A heartbeat at wall-clock time t. exchangeTimeMs is the last TICK's time: near t on a busy market,
        frozen (pass `exchange`) on a quiet one. With `armed` given it carries the D-97 armed/runtime fields."""
        rec = {"type": "heartbeat", "seq": self.seq, "generation": self.seq,
               "exchangeTimeMs": t - 40 if exchange is None else exchange,
               "localTimeMs": t, "healthy": True, "lastIntentReason": "none"}
        if armed is not None:
            rec["armed"] = armed
            rec["runtime"] = {"mode": mode, "armedSetting": armed or denied, "armDenied": denied, "refusedAtActivation": False}
        return self.add(rec)

    def arming(self, t, armed, mode="SIM_LIVE", setting=None, denied=False, refused=False):
        """An arming_state record (D-97)."""
        return self.add({"type": "arming_state", "seq": self.seq, "eventTimeMs": t, "armed": armed,
                         "runtime": {"mode": mode, "armedSetting": armed if setting is None else setting,
                                     "armDenied": denied, "refusedAtActivation": refused}})

    def log(self, t, msg):
        return self.add({"type": "log", "t": t, "msg": msg})

    def intent(self, t, target, reason, stop=None, tgt=None, verdict="allowed", filt=None, why=None):
        self.seq += 1
        self.intent_seq += 1
        self.add({"type": "intent_changed", "seq": self.seq, "eventTimeMs": t, "strategyId": "lvn_fade_test",
                  "intentSeq": self.intent_seq, "targetPosition": target, "stopPriceTicks": stop,
                  "targetPriceTicks": tgt, "reason": reason})
        if verdict == "allowed":
            vs = [{"filter": f, "allowed": True, "reason": "ok"} for f in ("armed", "session_open", "readiness")]
            self.add({"type": "risk_verdict", "seq": self.seq, "strategyId": "lvn_fade_test",
                      "intentSeq": self.intent_seq, "allowed": True, "verdicts": vs})
        else:
            vs = [{"filter": "armed", "allowed": True, "reason": "ok"}] if filt != "armed" else []
            vs.append({"filter": filt, "allowed": False, "reason": why})
            self.add({"type": "risk_verdict", "seq": self.seq, "strategyId": "lvn_fade_test",
                      "intentSeq": self.intent_seq, "allowed": False, "verdicts": vs})
        return self

    def submitted(self, side, reason, cash=98640.0):
        return self.add({"type": "real_order_submitted", "orderType": "MARKET", "instrument": "GCZ6", "side": side,
                         "qty": 1, "positionBefore": 0, "cashBalance": cash, "reason": reason})

    def bracket(self, t, closing, stop, target):
        msg = "LIVE_BRACKET_SUBMITTED " + json.dumps({"type": "real_bracket_submitted", "instrument": "GCZ6",
                                                      "closingSide": closing, "qty": 1, "stopPrice": stop,
                                                      "targetPrice": target, "reason": "r"})
        return self.log(t, msg)

    def fill(self, t, role, action, px, pos_after, cash=98640.0, order_id="1", **extra):
        rec = {"type": "order_fill", "t": t + 30, "role": role, "orderId": order_id, "instrument": "GCZ6",
               "action": action, "quantity": 1, "filled": 1, "avgFillPrice": px, "lastFillPrice": px,
               "lastFillTimeMs": t, "stopPrice": None, "limitPrice": None, "positionAfter": pos_after,
               "cashBalance": cash, "sdkTotalRealizedPnL": 0.0, "pointValue": 100.0, "tickSize": 0.1}
        rec.update(extra)
        return self.add(rec)

    def write(self):
        (self.dir / "decisions.jsonl").write_text("\n".join(self.lines) + "\n", encoding="utf-8")
        return self.dir


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.logs = self.root / "logs"
        self.logs.mkdir()
        self.data = self.root / "data"

    def tearDown(self):
        self.tmp.cleanup()

    def model(self, day=DAY):
        return dr.build_model(dr.load_sessions(self.logs), day, self.data)

    def journal(self, **kw):
        return Journal(self.logs, **kw)


# ---------------------------------------------------------------------------


class TestTime(unittest.TestCase):
    def test_known_real_instant(self):
        # First footprint line of the real 2026-09-24 recording: 15:07:37 CT (CDT).
        self.assertEqual(dr.fmt_ct(1790194057000), "09-23 15:07:37 CT")

    def test_dst_boundaries(self):
        # 2026: DST began 03-08 at 08:00 UTC, ends 11-01 at 07:00 UTC.
        self.assertEqual(dr.ct_offset_ms(utc_ms(2026, 3, 8, 7, 59, 59)), -6 * 3600_000)
        self.assertEqual(dr.ct_offset_ms(utc_ms(2026, 3, 8, 8, 0, 0)), -5 * 3600_000)
        self.assertEqual(dr.ct_offset_ms(utc_ms(2026, 11, 1, 6, 59, 59)), -5 * 3600_000)
        self.assertEqual(dr.ct_offset_ms(utc_ms(2026, 11, 1, 7, 0, 0)), -6 * 3600_000)
        self.assertEqual(dr.ct_offset_ms(utc_ms(2026, 7, 1, 12)), -5 * 3600_000)
        self.assertEqual(dr.ct_offset_ms(utc_ms(2026, 1, 15, 12)), -6 * 3600_000)

    def test_trading_day(self):
        self.assertEqual(dr.trading_day_of(ct_ms(DAY, 10)), DAY)
        self.assertEqual(dr.trading_day_of(ct_ms(DAY, 16, 59, 59)), DAY)
        self.assertEqual(dr.trading_day_of(ct_ms(DAY, 17)), date(2026, 9, 24), "17:00 CT starts the NEXT trading day")
        sunday = date(2026, 9, 27)
        self.assertEqual(dr.trading_day_of(ct_ms(sunday, 17, 30)), date(2026, 9, 28), "Sunday evening belongs to Monday")

    def test_window(self):
        w0, w1 = dr.trading_day_window(date(2026, 9, 28))  # Monday: Sun 17:00 CT -> Mon 17:00 CT
        self.assertEqual(w0, ct_ms(date(2026, 9, 27), 17))
        self.assertEqual(w1, ct_ms(date(2026, 9, 28), 17))
        # and across the DST end (CST after 11-01): 17:00 CST = 23:00 UTC
        w0, w1 = dr.trading_day_window(date(2026, 11, 4))
        self.assertEqual(w1, utc_ms(2026, 11, 4, 23))

    def test_data_session_id_matches_real_files(self):
        # data/*/20718.jsonl is the real recording made on 2026-09-23 CT.
        self.assertEqual(dr.session_id_for_day(DAY), 20718)

    def test_ist(self):
        self.assertEqual(dr.fmt_ist(ct_ms(DAY, 15, 7)), "01:37 IST")  # 15:07 CDT = 20:07 UTC = 01:37 IST next day


class TestTrades(Base):
    def _entry_long(self, j, t0=None, px=4300.0, cash=98640.0):
        t0 = t0 or ct_ms(DAY, 10, 0, 0)
        j.intent(t0, 1, "lvn_fade entered=above zone=[1,2]", 10, 30)
        j.submitted("BUY", "lvn_fade entered=above zone=[1,2]", cash)
        j.fill(t0 + 500, "entry", "BUY", px, 1, cash)
        j.bracket(t0 + 900, "SELL", px - 1.0, px + 2.0)
        return t0

    def test_long_stopped_out(self):
        j = self.journal()
        t0 = self._entry_long(j)
        j.fill(t0 + 60_000, "stop", "SELL", 4299.0, 0, 98540.0, order_id="2")
        j.write()
        tr = self.model()["trades"]
        self.assertEqual(len(tr), 1)
        t = tr[0]
        self.assertEqual((t["side"], t["entry_px"], t["exit_px"], t["exit_via"]), ("LONG", 4300.0, 4299.0, "stop"))
        self.assertAlmostEqual(t["points"], -1.0)
        self.assertAlmostEqual(t["gross"], -100.0)
        self.assertAlmostEqual(t["cash_delta"], -100.0)
        self.assertEqual((t["stop"], t["target"]), (4299.0, 4302.0))
        self.assertIn("lvn_fade entered=above", t["reason"])
        self.assertEqual(t["exit_t"] - t["entry_t"], 59_500)

    def test_short_target_hit(self):
        j = self.journal()
        t0 = ct_ms(DAY, 11)
        j.submitted("SELL", "lvn_fade entered=below")
        j.fill(t0, "entry", "SELL", 4300.1, -1)
        j.bracket(t0 + 100, "BUY", 4301.1, 4298.1)
        j.fill(t0 + 120_000, "target", "BUY", 4298.1, 0, order_id="3")
        j.write()
        t = self.model()["trades"][0]
        self.assertEqual((t["side"], t["exit_via"]), ("SHORT", "target"))
        self.assertAlmostEqual(t["points"], 2.0)   # short: (exit - entry) * -1
        self.assertAlmostEqual(t["gross"], 200.0)

    def test_session_flatten_close_is_labelled(self):
        j = self.journal()
        t0 = self._entry_long(j, ct_ms(DAY, 15, 30))
        j.add({"type": "SESSION_FLATTEN_DUE", "reason": "flatten window", "eventTimeMs": ct_ms(DAY, 15, 55), "seq": 9})
        j.log(ct_ms(DAY, 15, 55), 'SESSION_FLATTEN {"type":"kill_switch_flatten","positionBefore":1}')
        j.fill(ct_ms(DAY, 15, 55, 1), "untracked", "SELL", 4300.5, 0, order_id="9")
        j.write()
        m = self.model()
        self.assertEqual(m["trades"][0]["exit_via"], "session-end flatten")
        kinds = [a[3] for a in m["attention"]]
        self.assertIn("SESSION_FLATTEN_DUE", kinds)
        self.assertIn("SESSION_FLATTEN", kinds)

    def test_kill_switch_close_is_labelled_and_alerts(self):
        j = self.journal()
        self._entry_long(j)
        j.add({"type": "KILL_SWITCH", "reason": "daily loss limit breached", "seq": 5})
        j.log(ct_ms(DAY, 10, 5), 'KILL_SWITCH_TRIGGERED {"type":"kill_switch_flatten"}')
        j.fill(ct_ms(DAY, 10, 5, 1), "untracked", "SELL", 4280.0, 0, order_id="8")
        j.write()
        m = self.model()
        self.assertEqual(m["trades"][0]["exit_via"], "kill switch")
        self.assertTrue(any(a[2] == "ALERT" and a[3] == "KILL_SWITCH" for a in m["attention"]))

    def test_double_fill_leaves_an_orphan_and_alerts(self):
        j = self.journal()
        t0 = self._entry_long(j)
        j.fill(t0 + 60_000, "stop", "SELL", 4299.0, 0, order_id="2")
        j.fill(t0 + 60_100, "untracked", "SELL", 4302.0, -1, order_id="3")  # the target ALSO filled: now short
        j.log(t0 + 61_000, "POSITION_MISMATCH_DETECTED expectedFlat=true actualPosition=-1 -- both bracket legs likely filled, flattening")
        j.write()
        m = self.model()
        self.assertEqual(len(m["trades"]), 1)
        self.assertEqual(len(m["orphans"]), 1)
        self.assertEqual(m["orphans"][0]["positionAfter"], -1)
        self.assertTrue(any(a[3] == "POSITION_MISMATCH_DETECTED" for a in m["attention"]))
        text = dr.render(m)
        self.assertIn("a fill with no open trade", text)

    def test_manual_close_is_not_left_open(self):
        """F-3 (2026-09-28, L-3): a close the study did not place shows up as account_position_change -> the trade is
        closed by it, priced from the cash change, and no 'STILL OPEN' remains."""
        j = self.journal()
        t0 = self._entry_long(j)  # long 1 @4300, cash 98640 at the entry
        t_close = t0 + 40_000
        j.add({"type": "account_position_change", "t": t_close, "previousAccountPosition": 1, "accountPosition": 0,
               "studyPosition": 1, "explainedByFill": False, "cashBalance": 98540.0})
        j.log(t_close, "ACCOUNT_POSITION_CHANGED_UNTRACKED 1 -> 0 (study position 1) -- not from an order this study placed")
        j.write()
        m = self.model()
        self.assertEqual(len(m["trades"]), 1)
        t = m["trades"][0]
        self.assertEqual(t["exit_t"], t_close)
        self.assertEqual(t["exit_via"], "closed outside the study (manual / platform)")
        self.assertAlmostEqual(t["points"], -1.0)          # -100 cash / (100 point value x 1 lot)
        self.assertAlmostEqual(t["gross"], -100.0)
        text = dr.render(m)
        self.assertNotIn("STILL OPEN", text)
        self.assertTrue(any(a[3] == "ACCOUNT_POSITION_CHANGED_UNTRACKED" and a[2] == "ALERT" for a in m["attention"]))

    def test_account_change_explained_by_a_fill_or_not_flat_does_not_close_a_trade(self):
        j = self.journal()
        self._entry_long(j)
        j.add({"type": "account_position_change", "t": ct_ms(DAY, 10, 0, 5), "previousAccountPosition": 0,
               "accountPosition": 1, "studyPosition": 1, "explainedByFill": True, "cashBalance": 98640.0})
        j.add({"type": "account_position_change", "t": ct_ms(DAY, 10, 0, 6), "previousAccountPosition": 1,
               "accountPosition": 0, "studyPosition": 0, "explainedByFill": True, "cashBalance": 98640.0})
        j.write()
        self.assertIsNone(self.model()["trades"][0]["exit_t"], "explained changes are the study's own fills; the fill record closes the trade")

    def test_fill_far_from_its_order_price_is_flagged_and_excluded(self):
        """F-20: a target limit at 4302.0 filled at 4310.0 (the Sim engine after a data outage) is a probable artifact."""
        j = self.journal()
        t0 = self._entry_long(j)   # long @4300, target 4302.0
        j.fill(t0 + 60_000, "target", "SELL", 4310.0, 0, 99640.0, order_id="2", limitPrice=4302.0)   # +80 ticks through
        t1 = ct_ms(DAY, 11)
        j.submitted("SELL", "normal")
        j.fill(t1, "entry", "SELL", 4310.0, -1, 99640.0, order_id="3")
        j.bracket(t1 + 100, "BUY", 4311.0, 4308.0)
        j.fill(t1 + 20_000, "target", "BUY", 4308.0, 0, 99840.0, order_id="4", limitPrice=4308.0)   # exactly at its price
        j.write()
        m = self.model()
        a, b = m["trades"]
        self.assertTrue(a["artifact"])
        self.assertAlmostEqual(a["exit_off_ticks"], 80.0)
        self.assertFalse(b["artifact"])
        text = dr.render(m)
        self.assertIn("1 exit(s) filled more than 3 ticks away", text)
        self.assertIn("without them $200.00", text)      # +1000 artifact, +200 real -> gross 1200; without: 200
        self.assertIn("⚠ (+80 ticks vs its order price)", text)

    def test_exit_across_a_data_gap_gets_a_realistic_estimate(self):
        """F-25: the Sim filled a stop at its own price (4299.0) although price came back at 4295.0 after a gap."""
        j = self.journal()
        j.add({"type": "price_anchor", "price": 4300.0, "tickSize": 0.1})
        t0 = ct_ms(DAY, 10)
        j.submitted("BUY", "long")
        j.fill(t0, "entry", "BUY", 4300.0, 1)
        j.bracket(t0 + 100, "SELL", 4299.0, 4301.0)
        j.add({"type": "data_gap", "seq": 9, "startLocalMs": t0 + 5_000, "endLocalMs": t0 + 295_000, "durationMs": 290_000,
               "priceBeforeTicks": -3, "priceAfterTicks": -50, "jumpTicks": -47})
        j.fill(t0 + 295_100, "stop", "SELL", 4299.0, 0, 99900.0, order_id="2", stopPrice=4299.0)   # filled AT its stop
        # a second trade, no gap: must be untouched
        t1 = ct_ms(DAY, 11)
        j.submitted("BUY", "normal")
        j.fill(t1, "entry", "BUY", 4300.0, 1, 99900.0, order_id="3")
        j.bracket(t1 + 100, "SELL", 4299.0, 4301.0)
        j.fill(t1 + 10_000, "target", "SELL", 4301.0, 0, 100000.0, order_id="4", limitPrice=4301.0)
        j.write()
        m = self.model()
        a, b = m["trades"]
        self.assertTrue(a["gap_fill"])
        self.assertAlmostEqual(a["gap_exit_px"], 4295.0)
        self.assertAlmostEqual(a["gap_points"], -5.0)
        self.assertAlmostEqual(a["gap_gross"], -500.0)
        self.assertAlmostEqual(a["gross"], -100.0)
        self.assertFalse(b["gap_fill"])
        text = dr.render(m)
        self.assertIn("1 exit(s) filled across a data gap", text)
        self.assertIn("realistic estimate **-$500.00**", text)
        self.assertIn("**-$400.00** realistic", text)      # day: recorded 0 = -100 + 100 ; realistic -500 + 100
        self.assertIn("across a data gap (realistic exit 4295.0, -5.0 pts)", text)

    def test_gap_limit_fill_is_never_better_than_its_limit(self):
        j = self.journal()
        j.add({"type": "price_anchor", "price": 4300.0, "tickSize": 0.1})
        t0 = ct_ms(DAY, 10)
        j.submitted("BUY", "long")
        j.fill(t0, "entry", "BUY", 4300.0, 1)
        j.bracket(t0 + 100, "SELL", 4299.0, 4301.0)
        j.add({"type": "data_gap", "seq": 9, "durationMs": 100000, "priceBeforeTicks": 5, "priceAfterTicks": 80, "jumpTicks": 75})
        j.fill(t0 + 100_000, "target", "SELL", 4308.0, 0, 100800.0, order_id="2", limitPrice=4301.0)  # Sim: at market, 7 pts better
        j.write()
        t = self.model()["trades"][0]
        self.assertTrue(t["gap_fill"])
        self.assertAlmostEqual(t["gap_exit_px"], 4301.0)
        self.assertAlmostEqual(t["gap_points"], 1.0)
        self.assertTrue(t["artifact"], "it is also flagged by the far-from-its-own-price rule")

    def test_shorts_and_stops_measure_the_favourable_direction(self):
        j = self.journal()
        t0 = ct_ms(DAY, 10)
        j.submitted("SELL", "short")
        j.fill(t0, "entry", "SELL", 4300.0, -1)
        j.bracket(t0 + 100, "BUY", 4301.0, 4298.0)
        j.fill(t0 + 5_000, "stop", "BUY", 4301.2, 0, order_id="2", stopPrice=4301.0)   # short's stop filled 2 ticks WORSE
        j.write()
        t = self.model()["trades"][0]
        self.assertAlmostEqual(t["exit_off_ticks"], -2.0)
        self.assertFalse(t["artifact"], "2 ticks of stop slippage is normal")

    def test_execution_cost_section_and_feed_alerts(self):
        j = self.journal()
        t = ct_ms(DAY, 10)
        for i, (last, touch, sub, fill) in enumerate([(2, 1, 4, 5), (1, 0, 6, 8), (3, 2, 5, 12)]):
            j.add({"type": "entry_execution", "t": t + i * 1000, "orderId": str(i), "side": "BUY", "fillPriceTicks": 10,
                   "slippageVsLastTicks": last, "slippageVsTouchTicks": touch, "spreadTicks": 2,
                   "signalToSubmitMs": sub, "submitToFillMs": fill})
        j.add({"type": "FEED_STALE", "reason": "no market data for 21s during the trading window", "seq": 1})
        j.add({"type": "data_gap", "seq": 2, "startLocalMs": t, "endLocalMs": t + 164000, "durationMs": 164000,
               "priceBeforeTicks": 67, "priceAfterTicks": 149, "jumpTicks": 82})
        j.log(t + 2, "FEED_RESUME_FLATTEN target 4302.0 reached/crossed at 4302.5 -- {}")
        j.log(t + 3, "FEED_RESUME_KEEP price is still between the stop 1 and the target 2")
        j.write()
        m = self.model()
        text = dr.render(m)
        self.assertIn("## Execution cost (entries)", text)
        self.assertIn("| fill vs the last-trade price at the signal | 3 | 2.00 | 2 | 3 | 3 |", text)
        self.assertIn("| order submitted -> fill callback | 3 | 8.33 ms | 8 ms | 12 ms | 12 ms |", text)
        sev = {a[3]: a[2] for a in m["attention"]}
        self.assertEqual(sev["FEED_STALE"], "ALERT")
        self.assertEqual(sev["data_gap"], "WARN")
        self.assertEqual(sev["FEED_RESUME_FLATTEN"], "ALERT")
        self.assertEqual(sev["FEED_RESUME_KEEP"], "NOTE")
        gap = [a for a in m["attention"] if a[3] == "data_gap"][0]
        self.assertIn("164s without ticks", gap[4])
        self.assertIn("jump 82", gap[4])

    def test_flatten_verification_lines_are_alerts(self):
        j = self.journal()
        t = ct_ms(DAY, 10)
        j.log(t, "FLATTEN_CORRECTING KILL_SWITCH study=-1 account=-1 -- still not flat: {}")
        j.log(t + 1, "FLATTEN_NOT_CONFIRMED KILL_SWITCH study=-1 account=-1 after 3 looks -- CHECK THE ACCOUNT BY HAND")
        j.log(t + 2, "POSITION_SOURCES_DISAGREE KILL_SWITCH study=1 account=0 -- no close sent")
        j.log(t + 3, "FLATTEN_VERIFIED KILL_SWITCH account flat, nothing resting")
        j.write()
        sev = {a[3]: a[2] for a in self.model()["attention"]}
        self.assertEqual(sev["FLATTEN_CORRECTING"], "ALERT")
        self.assertEqual(sev["FLATTEN_NOT_CONFIRMED"], "ALERT")
        self.assertEqual(sev["POSITION_SOURCES_DISAGREE"], "ALERT")
        self.assertEqual(sev["FLATTEN_VERIFIED"], "NOTE")

    def test_trade_still_open_is_flagged(self):
        j = self.journal()
        self._entry_long(j)
        j.write()
        m = self.model()
        self.assertIsNone(m["trades"][0]["exit_t"])
        self.assertIn("STILL OPEN", m["trades"][0]["exit_via"])
        self.assertIn("STILL OPEN", dr.render(m))

    def test_two_trades_in_order_with_summary(self):
        j = self.journal()
        t0 = self._entry_long(j)
        j.fill(t0 + 60_000, "target", "SELL", 4302.0, 0, order_id="2")   # +2.0
        t1 = ct_ms(DAY, 12)
        j.submitted("SELL", "second")
        j.fill(t1, "entry", "SELL", 4310.0, -1)
        j.bracket(t1 + 100, "BUY", 4311.0, 4308.0)
        j.fill(t1 + 30_000, "stop", "BUY", 4311.0, 0, order_id="5")      # -1.0
        t2 = ct_ms(DAY, 13)
        j.submitted("BUY", "third, no bracket was ever placed")
        j.fill(t2, "entry", "BUY", 4320.0, 1)
        j.fill(t2 + 10_000, "untracked", "SELL", 4320.0, 0, order_id="7")
        j.write()
        m = self.model()
        first, second, third = m["trades"]
        self.assertEqual((first["stop"], first["target"]), (4299.0, 4302.0))
        self.assertEqual((second["stop"], second["target"]), (4311.0, 4308.0), "each trade shows ITS OWN bracket")
        self.assertEqual((third["stop"], third["target"]), (None, None), "and a trade with none doesn't inherit the last one")
        self.assertEqual([t["reason"] for t in m["trades"]][1], "second")
        text = dr.render(m)
        self.assertIn("3 closed", text)
        self.assertIn("1 win, 1 loss, 1 flat", text)
        self.assertIn("+1.0 points", text)
        self.assertIn("$100.00", text)   # gross +200 - 100 + 0

    def test_partial_entry_fills_merge_into_one_trade(self):
        # A10: a second order_fill with role=entry and the SAME orderId updates the open trade (cumulative
        # qty and avgFillPrice) instead of becoming an orphan; entry time stays the first slice's.
        j = self.journal()
        t0 = ct_ms(DAY, 10)
        j.intent(t0, 2, "lvn_fade entered=above zone=[1,2]", 10, 30)
        j.submitted("BUY", "lvn_fade entered=above zone=[1,2]")
        j.fill(t0 + 500, "entry", "BUY", 4300.0, 1, order_id="1", filled=1)   # first slice: 1 of 2 filled
        j.fill(t0 + 700, "entry", "BUY", 4300.5, 2, order_id="1", filled=2)  # second slice: 2 of 2 filled
        j.bracket(t0 + 900, "SELL", 4298.0, 4303.0)
        j.fill(t0 + 60_000, "stop", "SELL", 4299.0, 0, order_id="2")
        j.write()
        m = self.model()
        self.assertEqual(len(m["trades"]), 1)
        self.assertEqual(len(m["orphans"]), 0, "the second slice must not become an orphan")
        t = m["trades"][0]
        self.assertEqual(t["qty"], 2, "qty is the LATEST cumulative filled count")
        self.assertEqual(t["entry_px"], 4300.5, "entry price is the latest avgFillPrice")
        self.assertEqual(t["entry_t"], t0 + 500, "entry time stays the FIRST slice's")

    def test_entry_fill_with_different_order_id_while_open_is_still_orphan(self):
        # A10 negative case: unchanged behaviour for a genuinely different order (e.g. a second signal that
        # slipped through) arriving as an entry fill while a trade is already open.
        j = self.journal()
        t0 = self._entry_long(j)  # order_id defaults to "1"
        j.fill(t0 + 700, "entry", "BUY", 4301.0, 2, order_id="99")  # a different order id while the trade is open
        j.fill(t0 + 60_000, "stop", "SELL", 4299.0, 0, order_id="2")
        j.write()
        m = self.model()
        self.assertEqual(len(m["trades"]), 1)
        self.assertEqual(len(m["orphans"]), 1)
        self.assertEqual(m["orphans"][0]["orderId"], "99")
        t = m["trades"][0]
        self.assertEqual(t["qty"], 1, "the original entry is unchanged by the unrelated order id")
        self.assertEqual(t["entry_px"], 4300.0)

    def test_legacy_journal_without_fills_falls_back(self):
        j = self.journal()
        j.submitted("SELL", "old style entry")
        j.log(ct_ms(DAY, 10), "ORDER_FILLED bp.aa@4ecca8f8")
        j.write()
        m = self.model()
        self.assertEqual(m["trades"], [])
        self.assertEqual(len(m["legacy_entries"]), 1)
        text = dr.render(m)
        self.assertIn("predates fill records", text)
        self.assertIn("old style entry", text)


class TestIntentsAndAttention(Base):
    def test_blocked_intents_are_grouped_and_split_from_not_armed(self):
        j = self.journal()
        t = ct_ms(DAY, 10)
        j.intent(t, 1, "entry A", 1, 2, verdict="blocked", filt="churn", why="min dwell 5000ms not elapsed (3960ms since last change)")
        j.intent(t + 1000, -1, "entry B", 1, 2, verdict="blocked", filt="churn", why="min dwell 5000ms not elapsed (12ms since last change)")
        j.intent(t + 2000, 1, "entry C", 1, 2, verdict="blocked", filt="rate_limit", why="6 changes in the last 60s, limit 6")
        j.intent(t + 3000, 1, "entry D", 1, 2, verdict="blocked", filt="armed", why="not armed")
        j.intent(t + 4000, 1, "holding", 1, 2, verdict="blocked", filt="armed", why="not armed")  # not an entry
        j.intent(t + 5000, 0, "stop_hit", None, None, verdict="blocked", filt="churn", why="min dwell 5000ms not elapsed (1ms since last change)")
        j.intent(t + 6000, 1, "entry E", 1, 2)  # allowed
        j.write()
        ia = self.model()["intents"]
        self.assertEqual(ia["blocked_counts"][("churn", "min dwell 5000ms not elapsed")], 3, "elapsed-time noise is grouped")
        self.assertEqual(ia["blocked_counts"][("rate_limit", "6 changes in the last 60s, limit 6")], 1)
        self.assertEqual(ia["blocked_counts"][("armed", "not armed")], 2)
        self.assertEqual(ia["allowed_entries"], 1)
        self.assertEqual(ia["entries_blocked_by_armed"], 1, "the 'holding' restatement is not counted as an entry")
        listed = {e["reason"] for e in ia["blocked_entries_listed"]}
        self.assertEqual(listed, {"entry A", "entry B", "entry C"}, "'not armed' and flat intents are not individually listed")
        self.assertEqual(ia["changes"], 7)

    def test_attention_types_and_severity(self):
        j = self.journal()
        t = ct_ms(DAY, 10)
        j.add({"type": "DISARM", "reason": "pipeline exception: boom", "seq": 3})
        j.log(t, "REFUSE_TO_ARM existing position=1 activeOrders=0 -- clear manually before arming")
        j.log(t + 1, "LIVE_ENTRY_REJECTED_DISARMING -- entry rejected, disarming")
        j.log(t + 2, "LIVE_FILL_NO_BRACKET targetPosition=1 stopTicks=None targetTicks=None")
        j.log(t + 3, "ACTIVATE pos=0 cash=98910.0 -- CONFIRM: is this the Simulated account?")
        j.log(t + 4, "ORDER_MODIFIED bp.aa@1")  # noise: must not appear
        j.add({"type": "heartbeat", "seq": 1, "exchangeTimeMs": t + 5, "healthy": False, "lastIntentReason": "none"})
        j.write()
        att = self.model()["attention"]
        by_kind = {a[3]: a[2] for a in att}
        self.assertEqual(by_kind["DISARM"], "ALERT")
        self.assertEqual(by_kind["REFUSE_TO_ARM"], "ALERT")
        self.assertEqual(by_kind["LIVE_ENTRY_REJECTED_DISARMING"], "ALERT")
        self.assertEqual(by_kind["LIVE_FILL_NO_BRACKET"], "WARN")
        self.assertEqual(by_kind["ACTIVATE"], "NOTE")
        self.assertEqual(by_kind["unhealthy heartbeat"], "ALERT")
        self.assertNotIn("ORDER_MODIFIED", by_kind)
        text = dr.render(self.model())
        self.assertIn("REFUSE_TO_ARM", text)
        self.assertIn("Account at activation", text)

    def test_new_attention_prefixes_are_flagged_with_the_right_severity(self):
        # A5: the newest alert lines (D-99, D-106, B6's "any lost leg") must be caught, with the severities
        # the finding asked for.
        j = self.journal()
        t = ct_ms(DAY, 10)
        j.log(t, "LIVE_ENTRY_PARTIAL_FLATTENED qty=2 filled=1 -- flattening the partial before it completed")
        j.log(t + 1, "LIVE_ENTRY_PARTIAL_FILL qty=2 filled=1 orderId=1")
        j.log(t + 2, "LIVE_BRACKET_SIZE_FROM_FILL qty=1 filled=1")
        j.log(t + 3, "RISK_LOCAL_CONFIG_IGNORED reason=malformed json")
        j.log(t + 4, "ORDER_REJECTED bp.aa@1 reason=insufficient margin")
        j.log(t + 5, "LIVE_LEG_LOST role=stop orderId=2 reason=expired unnoticed")
        j.write()
        att = self.model()["attention"]
        by_kind = {a[3]: a[2] for a in att}
        self.assertEqual(by_kind["LIVE_ENTRY_PARTIAL_FLATTENED"], "ALERT")
        self.assertEqual(by_kind["LIVE_ENTRY_PARTIAL_FILL"], "WARN")
        self.assertEqual(by_kind["LIVE_BRACKET_SIZE_FROM_FILL"], "WARN")
        self.assertEqual(by_kind["RISK_LOCAL_CONFIG_IGNORED"], "ALERT")
        self.assertEqual(by_kind["ORDER_REJECTED"], "ALERT")
        self.assertEqual(by_kind["LIVE_LEG_LOST"], "ALERT")
        text = dr.render(self.model())
        for kind in ("LIVE_ENTRY_PARTIAL_FLATTENED", "LIVE_ENTRY_PARTIAL_FILL", "LIVE_BRACKET_SIZE_FROM_FILL",
                     "RISK_LOCAL_CONFIG_IGNORED", "ORDER_REJECTED", "LIVE_LEG_LOST"):
            self.assertIn(kind, text)

    def test_arm_still_denied_and_dom_backlog_skipped_are_flagged_with_the_right_severity(self):
        # A6 / E1: ARM_STILL_DENIED (a kill switch/order anomaly already disarmed this instance) is an ALERT;
        # DOM_BACKLOG_SKIPPED (the event queue fell behind, intermediate DOM snapshots skipped) is only a WARN.
        j = self.journal()
        t = ct_ms(DAY, 10)
        j.log(t, "ARM_STILL_DENIED -- a safety rule (daily-loss kill switch or an order anomaly) disarmed this "
                 "study earlier; remove and re-add the study to arm again")
        j.log(t + 1, "DOM_BACKLOG_SKIPPED total=3 -- the event queue fell behind; intermediate order-book "
                     "snapshots were skipped (the latest book is always kept)")
        j.write()
        att = self.model()["attention"]
        by_kind = {a[3]: a[2] for a in att}
        self.assertEqual(by_kind["ARM_STILL_DENIED"], "ALERT")
        self.assertEqual(by_kind["DOM_BACKLOG_SKIPPED"], "WARN")
        text = dr.render(self.model())
        self.assertIn("ARM_STILL_DENIED", text)
        self.assertIn("DOM_BACKLOG_SKIPPED", text)

    def test_order_rejected_for_a_bracket_leg_is_still_caught(self):
        # B6's specific case: a rejected/cancelled bracket LEG, not just an entry.
        j = self.journal()
        j.log(ct_ms(DAY, 10), "ORDER_REJECTED bp.aa@2 role=stop reason=price through the market")
        j.write()
        att = self.model()["attention"]
        self.assertTrue(any(a[3] == "ORDER_REJECTED" and a[2] == "ALERT" for a in att))

    def test_lag_blocks_are_aggregated_into_one_warn_line(self):
        # C2: several risk_verdicts blocked by the lag filter collapse into ONE WARN line per session, not
        # one line per block.
        j = self.journal()
        t = ct_ms(DAY, 10)
        j.intent(t, 1, "entry A", 1, 2, verdict="blocked", filt="lag", why="queue depth 1500 >= 1000")
        j.intent(t + 3 * 60_000, 1, "entry B", 1, 2, verdict="blocked", filt="lag", why="queue depth 1800 >= 1000")
        j.intent(t + 7 * 60_000, -1, "entry C", 1, 2, verdict="blocked", filt="lag", why="processing 2500ms >= 2000ms")
        j.write()
        att = self.model()["attention"]
        lag_events = [a for a in att if a[3] == "LAG_GUARD"]
        self.assertEqual(len(lag_events), 1, "one aggregated line, not one per block")
        t_, ap, sev, kind, detail = lag_events[0]
        self.assertEqual(sev, "WARN")
        self.assertEqual(detail, "lag guard blocked 3 entries (first 10:00 CT, last 10:07 CT)")
        text = dr.render(self.model())
        self.assertIn("lag guard blocked 3 entries (first 10:00 CT, last 10:07 CT)", text)

    def test_allowed_verdicts_do_not_trigger_the_lag_line(self):
        j = self.journal()
        j.intent(ct_ms(DAY, 10), 1, "entry ok")  # allowed, no filter blocked it
        j.write()
        att = self.model()["attention"]
        self.assertFalse(any(a[3] == "LAG_GUARD" for a in att))

    def test_a_block_whose_first_verdict_is_not_lag_is_not_counted_as_a_lag_block(self):
        # The rule is specifically "the FIRST non-allowed verdict has filter lag" -- a lag verdict present
        # later in the list, behind a different blocking filter, does not count.
        j = self.journal()
        j.add({"type": "intent_changed", "seq": 1, "eventTimeMs": ct_ms(DAY, 10), "strategyId": "lvn_fade_test",
               "intentSeq": 1, "targetPosition": 1, "stopPriceTicks": 1, "targetPriceTicks": 2, "reason": "entry X"})
        j.add({"type": "risk_verdict", "seq": 1, "strategyId": "lvn_fade_test", "intentSeq": 1, "allowed": False,
               "verdicts": [{"filter": "churn", "allowed": False, "reason": "min dwell not elapsed"},
                            {"filter": "lag", "allowed": False, "reason": "queue depth 1500 >= 1000"}]})
        j.write()
        att = self.model()["attention"]
        self.assertFalse(any(a[3] == "LAG_GUARD" for a in att))


class TestWindowAndSessions(Base):
    def test_only_records_in_the_trading_day_are_reported(self):
        j = self.journal()
        j.intent(ct_ms(DAY, 16, 59, 0), 1, "in window", 1, 2, verdict="blocked", filt="churn", why="x")
        j.intent(ct_ms(DAY, 17, 0, 1), 1, "after 17:00 CT -> next trading day", 1, 2, verdict="blocked", filt="churn", why="x")
        j.intent(ct_ms(date(2026, 9, 22), 16, 59, 0), 1, "before the window", 1, 2, verdict="blocked", filt="churn", why="x")
        j.write()
        ia = self.model()["intents"]
        self.assertEqual([e["reason"] for e in ia["blocked_entries_listed"]], ["in window"])
        self.assertEqual(self.model(date(2026, 9, 24))["intents"]["blocked_entries_listed"][0]["reason"],
                         "after 17:00 CT -> next trading day")

    def test_record_without_a_time_inherits_the_last_one_and_is_approximate(self):
        j = self.journal()
        j.heartbeat(ct_ms(DAY, 10, 0, 0))
        j.raw(json.dumps({"type": "log", "msg": "REFUSE_TO_ARM legacy line with no t"}))
        j.write()
        (a,) = [x for x in self.model()["attention"] if x[3] == "REFUSE_TO_ARM"]
        self.assertTrue(a[1], "flagged approximate")
        self.assertEqual(a[0], ct_ms(DAY, 10, 0, 0))
        self.assertIn("~09-23 10:00:00 CT", dr.render(self.model()))

    def test_sessions_health_and_gap_flag(self):
        j = self.journal(name="s_a_inst1")
        for s in range(0, 60, 10):
            j.heartbeat(ct_ms(DAY, 10, 0, s))
        j.heartbeat(ct_ms(DAY, 10, 4, 50))  # a 4-minute hole in the system clock (10:00:50 -> 10:04:50)
        j.write()
        (s, h, cfg), = self.model()["sessions"]
        self.assertEqual(h["heartbeats"], 7)
        self.assertEqual(h["max_hb_gap_ms"], 240_000)
        self.assertIn("4m00s ⚠", dr.render(self.model()))

    def test_a_quiet_market_is_not_reported_as_a_system_stall(self):
        # The last tick was at 10:00:00; then nothing traded for 8 minutes. The runtime's clock kept beating every
        # 10 s (localTimeMs), while exchangeTimeMs stayed frozen -- then a tick arrived and it jumped.
        j = self.journal()
        frozen = ct_ms(DAY, 10, 0, 0)
        for i in range(0, 49):
            j.heartbeat(ct_ms(DAY, 10, 0, 0) + i * 10_000, exchange=frozen)
        j.heartbeat(ct_ms(DAY, 10, 8, 10), exchange=ct_ms(DAY, 10, 8, 5))   # a tick finally arrives
        j.write()
        (s, h, cfg), = self.model()["sessions"]
        self.assertEqual(h["max_hb_gap_ms"], 10_000, "the runtime clock never skipped a beat")
        self.assertNotIn("⚠", dr.render(self.model()).split("## Sessions and system health")[1].split("## Config")[0])

    def test_a_real_system_stall_is_still_caught_when_the_market_is_busy(self):
        j = self.journal()
        j.heartbeat(ct_ms(DAY, 10, 0, 0))
        j.heartbeat(ct_ms(DAY, 10, 0, 10))
        j.heartbeat(ct_ms(DAY, 10, 5, 10))    # the runtime's clock stopped for 5 minutes, ticks or not
        j.write()
        (s, h, cfg), = self.model()["sessions"]
        self.assertEqual(h["max_hb_gap_ms"], 300_000)
        self.assertIn("5m00s ⚠", dr.render(self.model()))

    def test_config_change_between_sessions_is_called_out(self):
        self.journal(name="s_a_inst1").config().heartbeat(ct_ms(DAY, 10)).write()
        self.journal(name="s_b_inst2").config(dailyLossLimitTicks=50).heartbeat(ct_ms(DAY, 11)).write()
        text = dr.render(self.model())
        self.assertIn("dailyLossLimitTicks=50", text)
        self.assertIn("config changed between sessions", text)

    def test_unparseable_lines_are_counted_not_fatal(self):
        j = self.journal()
        j.heartbeat(ct_ms(DAY, 10))
        j.raw("{this is not json")
        j.write()
        m = self.model()
        self.assertEqual(m["sessions"][0][1]["parse_errors"], 1)
        self.assertIn("1 unparseable line", dr.render(m))

    def test_a_session_with_nothing_in_the_window_is_omitted(self):
        self.journal(name="old_inst1", start=ct_ms(date(2026, 9, 1), 10)).heartbeat(ct_ms(date(2026, 9, 1), 10)).write()
        self.assertEqual(self.model()["sessions"], [])


class TestArming(Base):
    def test_timeline_and_armed_duration(self):
        j = self.journal()
        j.arming(ct_ms(DAY, 9, 0, 0), False, mode="DRY_RUN")
        j.arming(ct_ms(DAY, 10, 0, 0), True)
        j.arming(ct_ms(DAY, 11, 30, 0), False, setting=True, denied=True)      # a mismatch disarmed it
        j.arming(ct_ms(DAY, 13, 0, 0), True)
        j.heartbeat(ct_ms(DAY, 13, 45, 0))                                     # still armed when the journal ends
        j.write()
        m = self.model()
        (s, recs, armed_ms, known), = m["arming"]
        self.assertTrue(known)
        self.assertEqual(len(recs), 4)
        self.assertEqual(armed_ms, 90 * 60_000 + 45 * 60_000)                  # 10:00-11:30 plus 13:00-13:45
        text = dr.render(m)
        self.assertIn("armed for 2h15m in this window (4 state record(s))", text)
        self.assertIn("## Arming", text)
        self.assertIn("10:00:00 CT — ARMED (SIM_LIVE)", text)
        self.assertIn("denied after arming", text)
        self.assertIn("09:00:00 CT — not armed (DRY_RUN)", text)

    def test_never_armed_says_dry_run_only(self):
        j = self.journal()
        j.arming(ct_ms(DAY, 9, 0, 0), False, mode="DRY_RUN")
        j.heartbeat(ct_ms(DAY, 9, 10, 0))
        j.write()
        self.assertIn("never armed in this window (1 state record(s)) — dry-run only", dr.render(self.model()))

    def test_refused_at_activation_is_explained(self):
        j = self.journal()
        j.arming(ct_ms(DAY, 9, 0, 0), False, setting=True, denied=True, refused=True)
        j.write()
        self.assertIn("refused at activation", dr.render(self.model()))

    def test_legacy_journal_says_not_recorded(self):
        j = self.journal()
        j.heartbeat(ct_ms(DAY, 9, 0, 0))
        j.write()
        text = dr.render(self.model())
        self.assertIn("**Arming:** not recorded", text)
        self.assertIn("predate D-97", text)

    def test_the_armed_interval_is_clipped_to_the_window(self):
        # armed at 16:30 and still armed at 17:01: only the 30 min before the 17:00 CT boundary belong to this day
        j = self.journal()
        j.arming(ct_ms(DAY, 16, 30, 0), True)
        j.heartbeat(ct_ms(DAY, 17, 1, 0), armed=True, mode="SIM_LIVE")
        j.write()
        (s, recs, armed_ms, known), = self.model()["arming"]
        self.assertEqual(armed_ms, 30 * 60_000)
        # ...and the next trading day sees it armed from its very start, with no arming_state of its own
        (s2, recs2, ms2, known2), = self.model(date(2026, 9, 24))["arming"]
        self.assertTrue(known2)
        self.assertEqual(recs2, [])

    def test_an_all_day_armed_study_is_not_reported_as_never_armed(self):
        # The study armed the previous morning and NEVER changed: no arming_state in today's window, only heartbeats.
        j = self.journal(start=ct_ms(date(2026, 9, 22), 9, 0))
        j.arming(ct_ms(date(2026, 9, 22), 9, 0, 5), True)          # BEFORE the 17:00 CT boundary: belongs to the previous day
        for i in range(0, 61, 10):
            j.heartbeat(ct_ms(DAY, 9, 0, 0) + i * 1000, armed=True, mode="SIM_LIVE")
        j.write()
        m = self.model()
        (s, recs, armed_ms, known), = m["arming"]
        self.assertTrue(known)
        self.assertEqual(recs, [], "no change record today")
        # armed from the window start (17:00 CT the previous day) until the last record (09:01:00): 16 h 1 min
        self.assertEqual(armed_ms, 16 * 3600_000 + 60_000)
        text = dr.render(m)
        self.assertNotIn("never armed", text)
        self.assertIn("no change during this window — ARMED (SIM_LIVE), as of the last heartbeat", text)

    def test_heartbeats_alone_give_the_armed_duration(self):
        j = self.journal()
        base = ct_ms(DAY, 10, 0, 0)
        for i in range(0, 6):
            j.heartbeat(base + i * 10_000, armed=True, mode="SIM_LIVE")           # armed 10:00:00 - 10:00:50
        j.heartbeat(base + 60_000, armed=False, mode="SIM_LIVE")                   # disarmed by 10:01:00
        j.heartbeat(base + 70_000, armed=False, mode="SIM_LIVE")
        j.write()
        (s, recs, armed_ms, known), = self.model()["arming"]
        self.assertEqual(armed_ms, 60_000)                                         # 10:00:00 -> 10:01:00
        self.assertEqual(recs, [])

    def test_a_flag_recorded_only_on_other_days_does_not_make_today_known(self):
        # The only arming records are days old; today's records (legacy heartbeats) do not say. Today is "not recorded",
        # not "never armed" -- the flag can't be assumed to have stayed put across a journal that stopped recording it.
        j = self.journal(start=ct_ms(date(2026, 9, 20), 9, 0))
        j.arming(ct_ms(date(2026, 9, 20), 9, 0, 5), True)
        j.heartbeat(ct_ms(DAY, 9, 0, 0))
        j.write()
        m = self.model()
        (s, recs, armed_ms, known), = m["arming"]
        self.assertFalse(known)
        self.assertIn("**Arming:** not recorded", dr.render(m))

    def test_time_before_the_window_is_not_counted(self):
        j = self.journal(start=ct_ms(date(2026, 9, 21), 18, 0))
        j.arming(ct_ms(date(2026, 9, 21), 18, 0, 5), True)                         # two days back
        j.arming(ct_ms(DAY, 9, 0, 0), False)                                       # disarmed at 09:00 today
        j.write()
        (s, recs, armed_ms, known), = self.model()["arming"]
        # armed from the window start (09-22 17:00 CT) to 09:00: 16 h -- NOT the whole time since 09-21 18:00
        self.assertEqual(armed_ms, 16 * 3600_000)

    def test_two_sessions_add_up(self):
        a = self.journal(name="s_a_inst1")
        a.arming(ct_ms(DAY, 9, 0, 0), True)
        a.arming(ct_ms(DAY, 9, 30, 0), False)
        a.write()
        b = self.journal(name="s_b_inst2")
        b.arming(ct_ms(DAY, 12, 0, 0), True)
        b.heartbeat(ct_ms(DAY, 12, 10, 0))
        b.write()
        m = self.model()
        self.assertEqual(sum(x[2] for x in m["arming"]), 30 * 60_000 + 10 * 60_000)


class TestDataHealth(Base):
    def _construct(self, name, times, interval=1):
        d = self.data / name
        d.mkdir(parents=True, exist_ok=True)
        lines = [json.dumps({"type": "header", "construct": name, "sessionId": 20718, "intervalSeconds": interval, "t": times[0]})]
        lines += [json.dumps({"t": t, "v": 1}) for t in times]
        (d / "20718.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def test_dense_gap_is_flagged_sparse_is_not_and_missing_is_reported(self):
        base = ct_ms(DAY, 10)
        self._construct("vwap", [base + i * 1000 for i in range(5)] + [base + 200_000])        # 196 s hole
        self._construct("footprint", [base, base + 500_000])                                    # sparse by design
        (self.data / "liquidity_map").mkdir(parents=True)                                       # no file for this day
        rows = {r["construct"]: r for r in dr.data_health(self.data, DAY)[1]}
        self.assertTrue(rows["vwap"]["present"])
        self.assertEqual(rows["vwap"]["max_gap_ms"], 196_000)
        self.assertFalse(rows["liquidity_map"]["present"])
        self.assertFalse(rows["footprint"]["dense"])
        self.journal().heartbeat(base).write()
        text = dr.render(self.model())
        self.assertIn("| vwap | ok | 6 |", text)
        self.assertIn("3m16s ⚠", text)
        self.assertIn("sparse by design", text)
        self.assertIn("| liquidity_map | **missing**", text)

    def test_event_driven_constructs_with_no_file_are_not_called_missing(self):
        # D-103: a closed/silent market writes no footprint/vwap/big_trades/bars file; the order-book and
        # market-structure recorders write regardless, so no file THERE is a real "missing".
        for name in ("footprint", "vwap", "big_trades", "bars", "liquidity_map", "market_structure"):
            (self.data / name).mkdir(parents=True)
        self.journal().heartbeat(ct_ms(DAY, 10)).write()
        text = dr.render(self.model())
        for name in ("footprint", "vwap", "big_trades", "bars"):
            self.assertIn(f"| {name} | none recorded — written only once trades occur (a fault only if the market traded)", text)
            self.assertNotIn(f"| {name} | **missing**", text)
        for name in ("liquidity_map", "market_structure"):
            self.assertIn(f"| {name} | **missing**", text)

    def test_no_data_directory(self):
        self.journal().heartbeat(ct_ms(DAY, 10)).write()
        self.assertIn("No data directory found", dr.render(self.model()))


class TestPerInstrumentDataLayout(Base):
    """Findings F-1 (2026-09-27): the runtime writes data/<construct>/<symbol>/<sid>.jsonl; older files are flat."""

    def _day_file(self, rel, n=3):
        f = self.data / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        base = ct_ms(DAY, 10)
        lines = [json.dumps({"type": "header", "construct": rel.split("/")[0], "intervalSeconds": 1, "t": base})]
        lines += [json.dumps({"t": base + i * 1000, "v": 1}) for i in range(n)]
        f.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def test_symbol_dir_mirrors_the_java_rule(self):
        self.assertEqual(dr.symbol_dir("@GC"), "GC")
        self.assertEqual(dr.symbol_dir("GCZ6"), "GCZ6")
        self.assertEqual(dr.symbol_dir("A/B:C"), "A_B_C")
        self.assertEqual(dr.symbol_dir(None), "unknown")
        self.assertEqual(dr.symbol_dir("@"), "unknown")

    def test_data_file_prefers_the_symbol_folder_and_falls_back_to_the_flat_file(self):
        sid = dr.session_id_for_day(DAY)
        self.assertEqual(dr.data_file(self.data, "vwap", sid, "@GC"), self.data / "vwap" / f"{sid}.jsonl",
                         "nothing exists yet: the old flat path is returned")
        self._day_file(f"vwap/GC/{sid}.jsonl")
        self.assertEqual(dr.data_file(self.data, "vwap", sid, "@GC"), self.data / "vwap" / "GC" / f"{sid}.jsonl")
        self.assertEqual(dr.data_file(self.data, "vwap", sid), self.data / "vwap" / "GC" / f"{sid}.jsonl",
                         "no symbol given: the default (gold) folder")

    def test_report_reads_the_sessions_instrument_folder_not_another_instruments(self):
        sid = dr.session_id_for_day(DAY)
        self._day_file(f"vwap/GC/{sid}.jsonl", n=4)
        self._day_file(f"vwap/ESZ6/{sid}.jsonl", n=9)         # another instrument, same day
        self.journal().heartbeat(ct_ms(DAY, 10)).write()      # the journal's session_header says @GC
        text = dr.render(self.model())
        self.assertIn("| vwap | ok | 4 |", text, "gold's own file (4 lines), not ES's (9)")
        self.assertIn(f"data/*/GC/{sid}.jsonl", text)

    def test_old_flat_files_still_read(self):
        sid = dr.session_id_for_day(DAY)
        self._day_file(f"vwap/{sid}.jsonl", n=2)
        self.journal().heartbeat(ct_ms(DAY, 10)).write()
        self.assertIn("| vwap | ok | 2 |", dr.render(self.model()))


class TestCli(Base):
    def test_end_to_end_writes_a_file(self):
        j = self.journal()
        j.config()
        j.submitted("BUY", "e")
        j.fill(ct_ms(DAY, 10), "entry", "BUY", 4300.0, 1)
        j.fill(ct_ms(DAY, 10, 1), "target", "SELL", 4302.0, 0, order_id="2")
        j.write()
        out = self.root / "rep" / "r.md"
        rc = dr.main(["--logs", str(self.logs), "--data", str(self.data), "--date", DAY.isoformat(), "--out", str(out)])
        self.assertEqual(rc, 0)
        text = out.read_text(encoding="utf-8")
        for section in ("# FLOW daily report", "## Summary", "## Needs attention", "## Trades",
                        "## What the risk chain blocked", "## Session-end flatten",
                        "## Sessions and system health", "## Config in force", "## Recorded data"):
            self.assertIn(section, text)
        self.assertIn("+2.0 points", text)

    def test_default_day_is_the_newest_records_day(self):
        self.journal().heartbeat(ct_ms(DAY, 10)).write()
        out = self.root / "d.md"
        self.assertEqual(dr.main(["--logs", str(self.logs), "--data", str(self.data), "--out", str(out)]), 0)
        self.assertIn("trading day 2026-09-23", out.read_text(encoding="utf-8"))

    def test_missing_logs_dir_is_an_error_not_a_crash(self):
        self.assertEqual(dr.main(["--logs", str(self.root / "nope"), "--no-file"]), 2)

    def test_empty_logs_is_an_error_not_a_crash(self):
        self.assertEqual(dr.main(["--logs", str(self.logs), "--data", str(self.data), "--no-file"]), 2)


class TestFlowHomeDefaults(Base):
    """D-101: logs/data/reports default under $FLOW_HOME when it is set, and are unchanged (relative) when not."""

    def env(self, **kw):
        patcher = mock.patch.dict(os.environ)
        patcher.start()
        self.addCleanup(patcher.stop)
        os.environ.pop("FLOW_HOME", None)
        os.environ.update(kw)

    def test_default_dir_unset_is_the_old_relative_name(self):
        self.assertEqual(dr.default_dir("logs", {}), "logs")
        self.assertEqual(dr.default_dir("reports", {}), "reports")

    def test_blank_counts_as_unset(self):
        self.assertEqual(dr.default_dir("data", {"FLOW_HOME": ""}), "data")
        self.assertEqual(dr.default_dir("data", {"FLOW_HOME": "   "}), "data")

    def test_set_puts_each_dir_under_the_home(self):
        home = {"FLOW_HOME": str(self.root)}
        self.assertEqual(dr.default_dir("logs", home), str(self.root / "logs"))
        self.assertEqual(dr.default_dir("data", home), str(self.root / "data"))
        self.assertEqual(dr.default_dir("reports", home), str(self.root / "reports"))
        self.assertEqual(dr.default_dir("logs", {"FLOW_HOME": f"  {self.root}  "}), str(self.root / "logs"))

    def test_default_dir_reads_the_real_environment_when_no_mapping_is_given(self):
        self.env(FLOW_HOME=str(self.root))
        self.assertEqual(dr.default_dir("logs"), str(self.root / "logs"))

    def test_cli_reads_and_writes_under_the_home_with_no_flags(self):
        self.journal().heartbeat(ct_ms(DAY, 10)).write()
        self.env(FLOW_HOME=str(self.root))
        self.assertEqual(dr.main(["--date", DAY.isoformat()]), 0)
        self.assertTrue((self.root / "reports" / f"{DAY.isoformat()}.md").exists(),
                        "the report lands in $FLOW_HOME/reports, not the current directory")

    def test_the_data_directory_comes_from_the_home_too(self):
        self.journal().heartbeat(ct_ms(DAY, 10)).write()
        d = self.root / "data" / "vwap"
        d.mkdir(parents=True)
        base = ct_ms(DAY, 10)
        (d / "20718.jsonl").write_text("\n".join(
            [json.dumps({"type": "header", "construct": "vwap", "sessionId": 20718, "intervalSeconds": 1, "t": base})]
            + [json.dumps({"t": base + i * 1000, "v": 1}) for i in range(3)]) + "\n", encoding="utf-8")
        self.env(FLOW_HOME=str(self.root))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.assertEqual(dr.main(["--date", DAY.isoformat(), "--no-file"]), 0)
        self.assertIn("| vwap | ok |", buf.getvalue())
        self.assertNotIn("No data directory found", buf.getvalue())

    def test_a_home_with_no_logs_is_an_error(self):
        self.env(FLOW_HOME=str(self.root / "elsewhere"))
        self.assertEqual(dr.main(["--no-file"]), 2)

    def test_explicit_flags_beat_the_home(self):
        self.journal().heartbeat(ct_ms(DAY, 10)).write()
        self.env(FLOW_HOME=str(self.root / "elsewhere"))
        out = self.root / "explicit.md"
        rc = dr.main(["--logs", str(self.logs), "--data", str(self.data), "--date", DAY.isoformat(), "--out", str(out)])
        self.assertEqual(rc, 0)
        self.assertTrue(out.exists())
        self.assertFalse((self.root / "elsewhere").exists())


if __name__ == "__main__":
    unittest.main(verbosity=1)
