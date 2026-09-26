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

import json
import sys
import tempfile
import unittest
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

    def heartbeat(self, t):
        return self.add({"type": "heartbeat", "seq": self.seq, "generation": self.seq, "exchangeTimeMs": t,
                         "localTimeMs": t + 40, "healthy": True, "lastIntentReason": "none"})

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

    def test_no_data_directory(self):
        self.journal().heartbeat(ct_ms(DAY, 10)).write()
        self.assertIn("No data directory found", dr.render(self.model()))


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


if __name__ == "__main__":
    unittest.main(verbosity=1)
