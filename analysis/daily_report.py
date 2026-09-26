#!/usr/bin/env python3
"""Nightly review report for one trading day (todo.md Phase 2, D-94).

Reads every session journal under logs/ (decisions.jsonl -- the long-retention
tier, D-15) plus the recorded constructs under data/, and writes ONE page:
every trade, every blocked intent and why, everything that needed attention
(disarms, kill switch, mismatches, flattens), system health, config in force
and data health. Built so a 1-2 hour evening review starts from a page, not
from raw JSONL.

Trading day D = [17:00 CT on D-1, 17:00 CT on D) -- SessionBoundary's own
session (D-29/D-34), named by the day it ends in, the way CME dates it. A
Sunday-evening session belongs to Monday.

Where trades come from: the structured `order_fill` records (D-94: role,
price, time, position after, cash) written by LiveOrderTracker. Journals from
before D-94 only have opaque "ORDER_FILLED bp.aa@..." lines; for those the
report can still list the entries submitted but says plainly it has no prices.

Times: every decision carries one now (`eventTimeMs` on intent_changed, `t` on
log/order_fill lines). A record without its own time takes the last time seen
before it in the file and is marked approximate (~).

No third-party packages, and no zoneinfo: this Windows Python has no tz
database, so US Central time (2007+ DST rules) is computed directly and
tested against the same dates as flow-core's TradingWindowTest.

Usage:
  python analysis/daily_report.py                       # the trading day of the newest journal record
  python analysis/daily_report.py --date 2026-09-29     # a specific trading day
  python analysis/daily_report.py --logs logs --data data --out reports/2026-09-29.md
"""

import argparse
import json
import re
import sys
from collections import Counter, OrderedDict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

UTC = timezone.utc
IST_OFFSET_MS = 5 * 3600 * 1000 + 30 * 60 * 1000  # India: fixed +05:30, no DST
DAY_MS = 86_400_000

# ---------------------------------------------------------------------------
# Time: US Central without a tz database
# ---------------------------------------------------------------------------


def _nth_sunday(year: int, month: int, n: int) -> date:
    d = date(year, month, 1)
    d += timedelta(days=(6 - d.weekday()) % 7)  # first Sunday
    return d + timedelta(weeks=n - 1)


def ct_offset_ms(utc_ms: int) -> int:
    """America/Chicago offset from UTC in ms: -5h in DST (2nd Sun Mar 02:00 CST
    -> 1st Sun Nov 02:00 CDT), else -6h. The 2007+ US rule."""
    y = datetime.fromtimestamp(utc_ms / 1000, UTC).year
    m = _nth_sunday(y, 3, 2)
    n = _nth_sunday(y, 11, 1)
    start = datetime(m.year, m.month, m.day, 8, 0, tzinfo=UTC)  # 02:00 CST
    end = datetime(n.year, n.month, n.day, 7, 0, tzinfo=UTC)    # 02:00 CDT
    dt = datetime.fromtimestamp(utc_ms / 1000, UTC)
    return (-5 if start <= dt < end else -6) * 3600 * 1000


def ct_local(utc_ms: int) -> datetime:
    """Chicago wall-clock as a naive datetime."""
    return datetime.fromtimestamp((utc_ms + ct_offset_ms(utc_ms)) / 1000, UTC).replace(tzinfo=None)


def ct_to_utc_ms(d: date, hour: int, minute: int = 0) -> int:
    naive = datetime(d.year, d.month, d.day, hour, minute, tzinfo=UTC).timestamp() * 1000
    for off_h in (-5, -6):
        cand = int(naive - off_h * 3600 * 1000)
        if ct_offset_ms(cand) == off_h * 3600 * 1000:
            return cand
    return int(naive + 6 * 3600 * 1000)  # unreachable for 17:00 (DST never changes then)


def trading_day_of(utc_ms: int) -> date:
    local = ct_local(utc_ms)
    d = local.date()
    return d + timedelta(days=1) if local.hour >= 17 else d


def trading_day_window(day: date):
    return ct_to_utc_ms(day - timedelta(days=1), 17), ct_to_utc_ms(day, 17)


def session_id_for_day(day: date) -> int:
    """data/<construct>/<sessionId>.jsonl: the epoch-day of the 17:00 CT START of the session."""
    return (day - timedelta(days=1) - date(1970, 1, 1)).days


def fmt_ct(ms, approx=False) -> str:
    if ms is None:
        return "?"
    return ("~" if approx else "") + ct_local(ms).strftime("%m-%d %H:%M:%S") + " CT"


def fmt_ist(ms) -> str:
    if ms is None:
        return "?"
    return datetime.fromtimestamp((ms + IST_OFFSET_MS) / 1000, UTC).strftime("%H:%M") + " IST"


def fmt_dur(ms) -> str:
    if ms is None:
        return "?"
    s = int(ms // 1000)
    h, r = divmod(s, 3600)
    m, s = divmod(r, 60)
    return f"{h}h{m:02d}m" if h else (f"{m}m{s:02d}s" if m else f"{s}s")


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------


class Record(dict):
    """A decisions.jsonl record plus `_t` (ms or None), `_approx`, `_session`."""


def _own_time(r):
    t = r.get("type")
    if t == "intent_changed":
        return r.get("eventTimeMs")
    if t in ("log", "order_fill"):
        return r.get("t")
    if t == "heartbeat":
        return r.get("exchangeTimeMs")
    if t == "SESSION_FLATTEN_DUE":
        return r.get("eventTimeMs")
    if t == "session_header":
        return r.get("sessionStartMs")
    return None


class Session:
    def __init__(self, name):
        self.name = name
        self.records = []
        self.header = {}
        self.parse_errors = 0

    @property
    def strategy(self):
        return self.header.get("strategyId", "?")

    def times(self):
        return [r["_t"] for r in self.records if r["_t"] is not None and not r["_approx"]]


def load_session(session_dir: Path) -> Session:
    s = Session(session_dir.name)
    path = session_dir / "decisions.jsonl"
    last_t = None
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                r = Record(json.loads(line))
            except json.JSONDecodeError:
                s.parse_errors += 1
                continue
            own = _own_time(r)
            if own is not None:
                r["_t"], r["_approx"] = own, False
                last_t = own
            else:
                r["_t"], r["_approx"] = last_t, True
            r["_session"] = s.name
            if r.get("type") == "session_header":
                s.header = r
            s.records.append(r)
    return s


def load_sessions(logs_root: Path):
    out = []
    for d in sorted(p for p in logs_root.iterdir() if p.is_dir()):
        if (d / "decisions.jsonl").exists():
            out.append(load_session(d))
    return out


# ---------------------------------------------------------------------------
# Analysis
# ---------------------------------------------------------------------------

# log-message prefixes that need a human's attention, with a severity.
ATTENTION = OrderedDict([
    ("KILL_SWITCH_TRIGGERED", "ALERT"),
    ("POSITION_MISMATCH_DETECTED", "ALERT"),
    ("POSITION_MISMATCH_CORRECTED", "ALERT"),
    ("LIVE_ENTRY_CANCELLED_DISARMING", "ALERT"),
    ("LIVE_ENTRY_REJECTED_DISARMING", "ALERT"),
    ("REFUSE_TO_ARM", "ALERT"),
    ("ORDER_FILL_RECORD_FAILED", "ALERT"),
    ("RISK_CONFIG_MISSING", "ALERT"),
    ("LIVE_BRACKET_SKIPPED", "ALERT"),
    ("LIVE_FILL_NO_BRACKET", "WARN"),
    ("SESSION_FLATTEN", "NOTE"),
    ("ACTIVATE", "NOTE"),
    ("DEACTIVATE", "NOTE"),
    ("DATA_RECORDER_ON", "NOTE"),
])
ATTENTION_TYPES = {
    "DISARM": "ALERT", "KILL_SWITCH": "ALERT", "data_recorder_disabled": "ALERT",
    "SESSION_FLATTEN_DUE": "NOTE",
}


def _msg_json(msg, prefix):
    """The JSON object after `PREFIX ` in a log message, or {}."""
    try:
        return json.loads(msg[len(prefix):].strip())
    except (ValueError, IndexError):
        return {}


def in_window(r, window):
    t = r["_t"]
    return t is not None and window[0] <= t < window[1]


def build_trades(recs):
    """Trades from order_fill records, in journal order. Returns (trades, orphans)."""
    trades, orphans = [], []
    open_t = None
    last_submit = None
    since_entry = set()
    for r in recs:
        typ = r.get("type")
        if typ == "real_order_submitted":
            last_submit = r
        elif typ == "KILL_SWITCH":
            since_entry.add("kill switch")
        elif typ == "log":
            msg = r.get("msg", "")
            if msg.startswith("LIVE_BRACKET_SUBMITTED "):
                # Logged AFTER the entry fill callback, so it attaches to the trade that just opened.
                bracket = _msg_json(msg, "LIVE_BRACKET_SUBMITTED ")
                if open_t is not None and open_t["stop"] is None:
                    open_t["stop"] = bracket.get("stopPrice")
                    open_t["target"] = bracket.get("targetPrice")
            elif msg.startswith("KILL_SWITCH_TRIGGERED"):
                since_entry.add("kill switch")
            elif msg.startswith("SESSION_FLATTEN"):
                since_entry.add("session-end flatten")
            elif msg.startswith("POSITION_MISMATCH_CORRECTED"):
                since_entry.add("double-fill correction")
        elif typ == "order_fill":
            role = r.get("role")
            if role == "entry" and open_t is None:
                side = "LONG" if r.get("action") == "BUY" else "SHORT"
                open_t = {
                    "session": r["_session"], "side": side, "qty": r.get("filled") or r.get("quantity") or 1,
                    "entry_t": r.get("lastFillTimeMs") or r["_t"], "entry_px": r.get("avgFillPrice"),
                    "point_value": r.get("pointValue"), "reason": (last_submit or {}).get("reason", "?"),
                    "cash_before": (last_submit or {}).get("cashBalance"),
                    "stop": None, "target": None,  # filled in by the LIVE_BRACKET_SUBMITTED line that follows this fill
                }
                since_entry = set()
            elif open_t is not None:
                if r.get("positionAfter") == 0 or role in ("stop", "target"):
                    if role in ("stop", "target"):
                        via = role
                    elif since_entry:
                        via = sorted(since_entry)[0]
                    else:
                        via = "untracked close"
                    px = r.get("avgFillPrice")
                    d = 1 if open_t["side"] == "LONG" else -1
                    pts = None if px is None or open_t["entry_px"] is None else round((px - open_t["entry_px"]) * d, 4)
                    pv = r.get("pointValue") or open_t["point_value"]
                    gross = None if pts is None or pv is None else round(pts * pv * open_t["qty"], 2)
                    cash_after = r.get("cashBalance")
                    cash_delta = None if cash_after is None or open_t["cash_before"] is None \
                        else round(cash_after - open_t["cash_before"], 2)
                    open_t.update(exit_t=r.get("lastFillTimeMs") or r["_t"], exit_px=px, exit_via=via,
                                  points=pts, gross=gross, cash_delta=cash_delta)
                    trades.append(open_t)
                    open_t = None
                    since_entry = set()
                else:
                    orphans.append(r)
            else:
                orphans.append(r)  # a fill with no open trade: the account did something we didn't track
    if open_t is not None:
        open_t.update(exit_t=None, exit_px=None, exit_via="STILL OPEN at end of journal", points=None,
                      gross=None, cash_delta=None)
        trades.append(open_t)
    return trades, orphans


def entries_without_fills(recs):
    """Legacy journals (pre-D-94): real_order_submitted lines, with no fill detail."""
    return [r for r in recs if r.get("type") == "real_order_submitted"]


def intent_analysis(recs):
    """Join intent_changed with its risk_verdict (by strategyId+intentSeq)."""
    intents = {}
    for r in recs:
        if r.get("type") == "intent_changed":
            intents[(r.get("strategyId"), r.get("intentSeq"))] = r
    counts = Counter()
    entries_blocked_by_armed = 0
    listed = []
    allowed_entries = 0
    for r in recs:
        if r.get("type") != "risk_verdict":
            continue
        it = intents.get((r.get("strategyId"), r.get("intentSeq")), {})
        is_entry = (it.get("targetPosition") or 0) != 0 and it.get("reason") != "holding"
        if r.get("allowed"):
            if is_entry:
                allowed_entries += 1
            continue
        v = next((v for v in r.get("verdicts", []) if not v.get("allowed")), {})
        filt, why = v.get("filter", "?"), v.get("reason", "?")
        if is_entry and filt == "armed":
            entries_blocked_by_armed += 1
        # Group "min dwell 5000ms not elapsed (3960ms since last change)" with its siblings: the elapsed time is noise.
        key = "not armed" if filt == "armed" else re.sub(r"\s*\(\d+ms since last change\)", "", why)
        counts[(filt, key)] += 1
        if is_entry and filt != "armed":
            listed.append({"t": it.get("eventTimeMs") or r["_t"], "approx": it.get("eventTimeMs") is None and r["_approx"],
                           "target": it.get("targetPosition"), "reason": it.get("reason"), "filter": filt, "why": why})
    return {"changes": len(intents), "allowed_entries": allowed_entries, "blocked_counts": counts,
            "blocked_entries_listed": listed, "entries_blocked_by_armed": entries_blocked_by_armed}


def attention_events(recs):
    out = []
    for r in recs:
        typ = r.get("type")
        if typ in ATTENTION_TYPES:
            detail = r.get("reason") or r.get("msg") or ""
            out.append((r["_t"], r["_approx"], ATTENTION_TYPES[typ], typ, str(detail)))
        elif typ == "log":
            msg = r.get("msg", "")
            for prefix, sev in ATTENTION.items():
                if msg.startswith(prefix):
                    out.append((r["_t"], r["_approx"], sev, prefix, msg[len(prefix):].strip()))
                    break
        elif typ == "heartbeat" and r.get("healthy") is False:
            out.append((r["_t"], r["_approx"], "ALERT", "unhealthy heartbeat", str(r.get("lastIntentReason"))))
    return out


def health(session, window):
    recs = [r for r in session.records if in_window(r, window)]
    hb = [r["_t"] for r in recs if r.get("type") == "heartbeat"]
    gaps = [b - a for a, b in zip(hb, hb[1:])]
    times = [r["_t"] for r in recs if r["_t"] is not None]
    return {"records": len(recs), "heartbeats": len(hb), "max_hb_gap_ms": max(gaps) if gaps else None,
            "first": min(times) if times else None, "last": max(times) if times else None,
            "parse_errors": session.parse_errors}


def config_in_force(session):
    for r in session.records:
        if r.get("type") == "risk_config_loaded":
            return {k: v for k, v in r.items() if not k.startswith("_") and k not in ("type", "fileLastModifiedMs", "sessionStartMs")}
    return None


# Constructs written every interval (a gap is meaningful) vs only when something happens.
DENSE = {"vwap": 1, "liquidity_map": 1}


def data_health(data_root: Path, day: date):
    sid = session_id_for_day(day)
    rows = []
    if not data_root.exists():
        return sid, rows
    for d in sorted(p for p in data_root.iterdir() if p.is_dir()):
        f = d / f"{sid}.jsonl"
        if not f.exists():
            rows.append({"construct": d.name, "present": False})
            continue
        n = 0
        ts = []
        interval = None
        with f.open(encoding="utf-8") as fh:
            for line in fh:
                try:
                    o = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if o.get("type") == "header":
                    interval = o.get("intervalSeconds")
                    continue
                n += 1
                if "t" in o:
                    ts.append(o["t"])
        gaps = [b - a for a, b in zip(ts, ts[1:])]
        rows.append({"construct": d.name, "present": True, "lines": n, "bytes": f.stat().st_size,
                     "first": ts[0] if ts else None, "last": ts[-1] if ts else None,
                     "max_gap_ms": max(gaps) if gaps else None, "dense": d.name in DENSE, "interval": interval})
    return sid, rows


def build_model(sessions, day, data_root):
    window = trading_day_window(day)
    per_session = []
    all_trades, all_orphans, all_attention, all_legacy = [], [], [], []
    intent_totals = {"changes": 0, "allowed_entries": 0, "entries_blocked_by_armed": 0,
                     "blocked_counts": Counter(), "blocked_entries_listed": []}
    for s in sessions:
        recs = [r for r in s.records if in_window(r, window)]
        if not recs:
            continue
        per_session.append((s, health(s, window), config_in_force(s)))
        trades, orphans = build_trades(recs)
        all_trades += trades
        all_orphans += orphans
        if not any(r.get("type") == "order_fill" for r in recs):
            all_legacy += entries_without_fills(recs)
        all_attention += attention_events(recs)
        ia = intent_analysis(recs)
        for k in ("changes", "allowed_entries", "entries_blocked_by_armed"):
            intent_totals[k] += ia[k]
        intent_totals["blocked_counts"].update(ia["blocked_counts"])
        intent_totals["blocked_entries_listed"] += ia["blocked_entries_listed"]
    all_trades.sort(key=lambda t: t["entry_t"] or 0)
    all_attention.sort(key=lambda a: a[0] or 0)
    sid, data_rows = data_health(data_root, day)
    return {"day": day, "window": window, "sessions": per_session, "trades": all_trades, "orphans": all_orphans,
            "attention": all_attention, "legacy_entries": all_legacy, "intents": intent_totals,
            "data_session_id": sid, "data": data_rows}


# ---------------------------------------------------------------------------
# Rendering (Markdown)
# ---------------------------------------------------------------------------


def _money(x):
    return "n/a" if x is None else f"{'-' if x < 0 else ''}${abs(x):,.2f}"


def _num(x, nd=1):
    return "n/a" if x is None else f"{x:.{nd}f}"


def render(model) -> str:
    day = model["day"]
    w0, w1 = model["window"]
    L = []
    L.append(f"# FLOW daily report — trading day {day.isoformat()} ({day.strftime('%A')})")
    L.append("")
    L.append(f"Window: {fmt_ct(w0)} → {fmt_ct(w1)}  ({fmt_ist(w0)} → {fmt_ist(w1)} on the clock in India). "
             f"`~` before a time = approximate (that record carried no time of its own).")
    L.append("")

    closed = [t for t in model["trades"] if t["points"] is not None]
    open_trades = [t for t in model["trades"] if t["exit_t"] is None]
    alerts = [a for a in model["attention"] if a[2] == "ALERT"]
    L.append("## Summary")
    L.append("")
    if closed:
        wins = [t for t in closed if t["points"] > 0]
        losses = [t for t in closed if t["points"] < 0]
        tot_pts = sum(t["points"] for t in closed)
        tot_gross = sum(t["gross"] for t in closed if t["gross"] is not None)
        L.append(f"- **Trades:** {len(closed)} closed — {len(wins)} win, {len(losses)} loss, "
                 f"{len(closed) - len(wins) - len(losses)} flat; **{tot_pts:+.1f} points**, gross **{_money(tot_gross)}** "
                 f"(before fees).")
        best = max(closed, key=lambda t: t["points"])
        worst = min(closed, key=lambda t: t["points"])
        L.append(f"- Best {best['points']:+.1f} pts, worst {worst['points']:+.1f} pts.")
    elif model["legacy_entries"]:
        L.append(f"- **Trades:** {len(model['legacy_entries'])} real order(s) submitted, but this journal predates "
                 f"fill records (D-94) — no prices or PnL available.")
    else:
        L.append("- **Trades:** none.")
    if open_trades:
        L.append(f"- **{len(open_trades)} trade(s) STILL OPEN at the end of the journal** — check the account.")
    ia = model["intents"]
    L.append(f"- **Intents:** {ia['changes']} strategy intent changes; {ia['allowed_entries']} entries allowed by the "
             f"risk chain; {sum(v for (f, _), v in ia['blocked_counts'].items() if f != 'armed')} blocked by a limit "
             f"(other than 'not armed'); {ia['entries_blocked_by_armed']} entries seen while not armed.")
    L.append(f"- **Needs attention:** {len(alerts)} alert(s), {sum(1 for a in model['attention'] if a[2] == 'WARN')} warning(s)."
             + ("" if alerts else " None."))
    L.append(f"- **Sessions:** {len(model['sessions'])} journal(s) in the window.")
    L.append("")

    # ---- attention first: it is what the reader must not miss
    L.append("## Needs attention")
    L.append("")
    notable = [a for a in model["attention"] if a[2] in ("ALERT", "WARN")]
    if not notable and not model["orphans"]:
        L.append("Nothing.")
    for t, ap, sev, kind, detail in notable:
        L.append(f"- **{sev}** {fmt_ct(t, ap)} — `{kind}` {detail[:300]}")
    for r in model["orphans"]:
        L.append(f"- **ALERT** {fmt_ct(r['_t'], r['_approx'])} — a fill with no open trade to attach it to "
                 f"(role `{r.get('role')}`, {r.get('action')} @ {r.get('avgFillPrice')}, position after "
                 f"{r.get('positionAfter')}) — the account did something the runtime wasn't tracking.")
    L.append("")

    # ---- trades
    L.append("## Trades")
    L.append("")
    if model["trades"]:
        L.append("| # | side | entry (CT) | IST | held | entry px | exit px | exit via | pts | gross $ | cash Δ* | why entered |")
        L.append("|--:|---|---|---|---|--:|--:|---|--:|--:|--:|---|")
        for i, t in enumerate(model["trades"], 1):
            held = fmt_dur(t["exit_t"] - t["entry_t"]) if t["exit_t"] and t["entry_t"] else "?"
            pts = "n/a" if t["points"] is None else f"{t['points']:+.1f}"
            L.append(f"| {i} | {t['side']} {t['qty']} | {fmt_ct(t['entry_t'])} | {fmt_ist(t['entry_t'])} | {held} | "
                     f"{_num(t['entry_px'])} | {_num(t['exit_px'])} | {t['exit_via']} | {pts} | {_money(t['gross'])} | "
                     f"{_money(t['cash_delta'])} | {t['reason']} |")
            if t["stop"] is not None:
                L.append(f"|  | ↳ bracket | stop {_num(t['stop'])} · target {_num(t['target'])} |  |  |  |  |  |  |  |  |  |")
        L.append("")
        L.append("\\* `cash Δ` = the platform's cash balance at exit minus at the order's submission: includes fees, "
                 "but the platform may update it after the fill callback, so treat it as indicative. `gross $` = "
                 "points × point value × qty, before fees.")
    elif model["legacy_entries"]:
        L.append("Journal predates fill records — entries submitted (no prices):")
        L.append("")
        for r in model["legacy_entries"]:
            L.append(f"- {fmt_ct(r['_t'], r['_approx'])} {r.get('side')} {r.get('qty')} {r.get('instrument')} — {r.get('reason')}")
    else:
        L.append("No trades.")
    L.append("")

    # ---- intents
    L.append("## What the risk chain blocked")
    L.append("")
    counts = ia["blocked_counts"]
    if counts:
        L.append("| filter | reason | intents |")
        L.append("|---|---|--:|")
        for (filt, why), n in counts.most_common():
            L.append(f"| {filt} | {why} | {n} |")
        L.append("")
    listed = ia["blocked_entries_listed"]
    if listed:
        L.append(f"Entry intents blocked by a limit (not merely 'not armed'), {min(len(listed), 50)} of {len(listed)}:")
        L.append("")
        for e in sorted(listed, key=lambda e: e["t"] or 0)[:50]:
            side = "long" if (e["target"] or 0) > 0 else "short"
            L.append(f"- {fmt_ct(e['t'], e['approx'])} {side} — {e['reason']} → **{e['filter']}**: {e['why']}")
        L.append("")
    if not counts:
        L.append("Nothing blocked.")
        L.append("")

    # ---- flatten / notes
    notes = [a for a in model["attention"] if a[2] == "NOTE" and a[3] in ("SESSION_FLATTEN", "SESSION_FLATTEN_DUE")]
    L.append("## Session-end flatten")
    L.append("")
    if notes:
        for t, ap, sev, kind, detail in notes:
            L.append(f"- {fmt_ct(t, ap)} `{kind}` {detail[:200]}")
    else:
        L.append("No flatten window reached in the journaled period.")
    L.append("")

    # ---- sessions / health / config
    L.append("## Sessions and system health")
    L.append("")
    L.append("| journal | strategy | first → last (CT) | records | heartbeats | longest heartbeat gap |")
    L.append("|---|---|---|--:|--:|---|")
    for s, h, cfg in model["sessions"]:
        gap = fmt_dur(h["max_hb_gap_ms"])
        flag = " ⚠" if h["max_hb_gap_ms"] and h["max_hb_gap_ms"] > 60_000 else ""
        L.append(f"| `{s.name}` | {s.strategy} | {fmt_ct(h['first'])} → {fmt_ct(h['last'])} | {h['records']} | "
                 f"{h['heartbeats']} | {gap}{flag} |")
    L.append("")
    L.append("Heartbeats come from the runtime's own clock (every ~10 s), so a long gap means the *system* stalled — "
             "not that the market was quiet (the system deliberately does not watch for a silent feed, D-93).")
    errs = sum(h["parse_errors"] for _, h, _ in model["sessions"])
    if errs:
        L.append("")
        L.append(f"**{errs} unparseable line(s)** in the journals.")
    L.append("")
    acts = [a for a in model["attention"] if a[3] == "ACTIVATE"]
    if acts:
        L.append("Account at activation (confirm it is the Simulated account):")
        L.append("")
        for t, ap, sev, kind, detail in acts:
            L.append(f"- {fmt_ct(t, ap)} `ACTIVATE {detail[:160]}`")
        L.append("")
    cfgs = [(s, c) for s, _, c in model["sessions"] if c]
    L.append("## Config in force")
    L.append("")
    if cfgs:
        distinct = []
        for s, c in cfgs:
            if c not in [d for d, _ in distinct]:
                distinct.append((c, s.name))
        for c, name in distinct:
            L.append(f"`{name}`: " + ", ".join(f"{k}={v}" for k, v in c.items()))
            L.append("")
        if len(distinct) > 1:
            L.append("**The config changed between sessions** in this window.")
            L.append("")
    else:
        L.append("No `risk_config_loaded` record found.")
        L.append("")

    # ---- data health
    L.append(f"## Recorded data (data/*/{model['data_session_id']}.jsonl)")
    L.append("")
    if model["data"]:
        L.append("| construct | file | lines | size | first → last (CT) | longest gap |")
        L.append("|---|---|--:|--:|---|---|")
        for row in model["data"]:
            if not row["present"]:
                L.append(f"| {row['construct']} | **missing** | | | | |")
                continue
            gap = fmt_dur(row["max_gap_ms"])
            interval_ms = (row.get("interval") or 1) * 1000
            flag = " ⚠" if row["dense"] and row["max_gap_ms"] and row["max_gap_ms"] > max(30_000, 5 * interval_ms) else ""
            gap = gap + flag if row["dense"] else gap + " (sparse by design)"
            L.append(f"| {row['construct']} | ok | {row['lines']} | {row['bytes'] / 1024:.0f} KB | "
                     f"{fmt_ct(row['first'])} → {fmt_ct(row['last'])} | {gap} |")
    else:
        L.append("No data directory found.")
    L.append("")
    return "\n".join(L)


# ---------------------------------------------------------------------------


def newest_time(sessions):
    ts = [t for s in sessions for t in s.times()]
    return max(ts) if ts else None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--logs", default="logs")
    ap.add_argument("--data", default="data")
    ap.add_argument("--date", help="trading day YYYY-MM-DD (default: the day of the newest journal record)")
    ap.add_argument("--out", help="write here too (default reports/<date>.md)")
    ap.add_argument("--no-file", action="store_true", help="print only, write no file")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):  # the Windows console defaults to cp1252, which can't print the arrows
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    logs = Path(args.logs)
    if not logs.exists():
        print(f"no logs directory at {logs}", file=sys.stderr)
        return 2
    sessions = load_sessions(logs)
    if args.date:
        day = date.fromisoformat(args.date)
    else:
        nt = newest_time(sessions)
        if nt is None:
            print("no timestamped journal records found", file=sys.stderr)
            return 2
        day = trading_day_of(nt)
    model = build_model(sessions, day, Path(args.data))
    text = render(model)
    print(text)
    if not args.no_file:
        out = Path(args.out) if args.out else Path("reports") / f"{day.isoformat()}.md"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
        print(f"\n(written to {out})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
