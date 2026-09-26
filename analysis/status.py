#!/usr/bin/env python3
""""Is it alive" -- one line, for the short windows when you check in (todo.md Phase 2).

  python analysis/status.py

prints e.g.

  ALIVE | 09-29 10:15:03 CT (20:45 IST) | heartbeat 4s ago | lvn_fade_test | armed: yes (verdict 2m ago) | position: flat | last trade 09:58 CT +2.0 pts | alerts today: 0 | data: ok

State (first word):
  ALIVE    the newest journal was written to within the last 30 s
  STALE    30 s - 5 min since it was written: probably stalled or just stopped
  DOWN     more than 5 min, or no journal at all
  STOPPED  the study logged DEACTIVATE as its last act (a clean stop, not a crash)
Exit code: 0 ALIVE, 1 STALE/STOPPED, 2 DOWN -- so a script can act on it.
"ALERTS n" is added after the state when today's journals contain alerts
(kill switch, disarm, position mismatch, ...): see the daily report.

What "alive" means: the journal's own clock. The runtime writes a heartbeat
about every 10 s from its own timer, so silence means the SYSTEM stopped -- not
that the market went quiet. The system deliberately does not watch for a
silent feed (D-93).

Where each field comes from:
  - armed: the journal records it since D-97 (every heartbeat and arming_state
    carries the effective flag and the mode), e.g. "armed: yes (SIM_LIVE) (as of
    4s ago)". Journals from before that only allow a guess from the newest risk
    verdict, printed as "(inferred, ...)"; "unknown" if there is none.
  - position: from the newest order_fill record (D-94); "unknown" for sessions
    before that. (The journal still stores no position of its own.)
Everything comes from the files under logs/ and data/; nothing talks to
MotiveWave or the broker.
"""

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import daily_report as dr  # noqa: E402

ALIVE_MS = 30_000
STALE_MS = 300_000
DENSE = ("vwap", "liquidity_map")   # written every interval, so their file must be fresh while running
DATA_FRESH_MS = 30_000


def ago(ms):
    if ms is None:
        return "?"
    s = max(0, int(ms // 1000))
    if s < 90:
        return f"{s}s"
    if s < 5400:
        return f"{s // 60}m"
    return f"{s // 3600}h{(s % 3600) // 60:02d}m"


def newest_journal(logs: Path):
    best = None
    for d in logs.iterdir() if logs.exists() else []:
        f = d / "decisions.jsonl"
        if f.exists():
            m = f.stat().st_mtime * 1000
            if best is None or m > best[0]:
                best = (m, d)
    return best  # (mtime_ms, dir) or None


def last_activity_ms(session, mtime_ms):
    """Newest of: the last record carrying its own time, and the file's modification time."""
    own = [r["_t"] for r in session.records if r["_t"] is not None and not r["_approx"]]
    cands = [mtime_ms] + ([max(own)] if own else [])
    return int(max(cands))


def armed_state(session, now):
    """The armed flag. Exact when the journal records it (D-97: every heartbeat and arming_state carries it);
    otherwise INFERRED from the newest risk verdict's 'armed' filter and labelled as such; else 'unknown'."""
    for r in reversed(session.records):
        if r.get("type") in ("heartbeat", "arming_state") and r.get("armed") is not None:
            rt = r.get("runtime") if isinstance(r.get("runtime"), dict) else {}
            mode = f" {rt['mode']}" if rt.get("mode") else ""
            if r["armed"]:
                word = "yes" + mode
            elif rt.get("armDenied"):
                word = "DENIED" + mode     # the setting is on but the system disarmed itself (or refused at activation)
            else:
                word = "no" + mode
            return word + ("" if r["_t"] is None else f" (as of {ago(now - r['_t'])} ago)")
    for r in reversed(session.records):
        if r.get("type") == "risk_verdict":
            v = next((v for v in r.get("verdicts", []) if v.get("filter") == "armed"), None)
            if v is not None:
                t = r["_t"]
                age = "" if t is None else f", verdict {ago(now - t)} ago"
                return ("yes" if v.get("allowed") else "no") + f" (inferred{age})"
    return "unknown"


def position_state(session):
    for r in reversed(session.records):
        if r.get("type") == "order_fill" and r.get("positionAfter") is not None:
            p = r["positionAfter"]
            return "flat" if p == 0 else (f"LONG {p}" if p > 0 else f"SHORT {-p}")
    return "unknown"


def stopped_cleanly(session):
    for r in reversed(session.records):
        if r.get("type") == "log":
            return r.get("msg", "").startswith("DEACTIVATE")
    return False


def data_state(data_root: Path, day, now_ms):
    sid = dr.session_id_for_day(day)
    if not data_root.exists():
        return "n/a (no data dir)"
    problems = []
    for name in DENSE:
        f = data_root / name / f"{sid}.jsonl"
        if not f.exists():
            problems.append(f"{name} missing")
            continue
        age = now_ms - f.stat().st_mtime * 1000
        if age > DATA_FRESH_MS:
            problems.append(f"{name} {ago(age)} old")
    return "ok" if not problems else "CHECK: " + ", ".join(problems)


def status_line(logs: Path, data: Path, now_ms: int):
    """Returns (line, exit_code)."""
    newest = newest_journal(logs)
    now_txt = f"{dr.fmt_ct(now_ms)[:-3].strip()} CT ({dr.fmt_ist(now_ms)})"
    if newest is None:
        return f"DOWN | {now_txt} | no journal found under {logs}", 2
    mtime_ms, jdir = newest
    session = dr.load_session(jdir)
    last = last_activity_ms(session, mtime_ms)
    silent = now_ms - last

    if stopped_cleanly(session):
        state, code = "STOPPED", 1
    elif silent <= ALIVE_MS:
        state, code = "ALIVE", 0
    elif silent <= STALE_MS:
        state, code = "STALE", 1
    else:
        state, code = "DOWN", 2

    day = dr.trading_day_of(now_ms)
    model = dr.build_model(recent_sessions(logs, dr.trading_day_window(day)[0]), day, data)
    alerts = sum(1 for a in model["attention"] if a[2] == "ALERT") + len(model["orphans"])
    closed = [t for t in model["trades"] if t["points"] is not None]
    open_n = sum(1 for t in model["trades"] if t["exit_t"] is None)
    if closed:
        lt = closed[-1]
        last_trade = f"last trade {dr.ct_local(lt['exit_t']).strftime('%H:%M')} CT {lt['points']:+.1f} pts"
    else:
        last_trade = "no closed trade today"

    parts = [state + (f" ALERTS {alerts}" if alerts else ""), now_txt,
             (f"heartbeat {ago(silent)} ago" if state in ("ALIVE", "STALE", "DOWN") else f"stopped {ago(silent)} ago"),
             session.strategy, f"armed: {armed_state(session, now_ms)}",
             f"position: {position_state(session)}" + (f" ({open_n} open trade)" if open_n else ""),
             last_trade, f"alerts today: {alerts}",
             "data: " + (data_state(data, day, now_ms) if state in ("ALIVE", "STALE") else "n/a (not running)")]
    return " | ".join(parts), code


def recent_sessions(logs: Path, since_ms):
    """Only journals modified since the trading day began (older ones cannot hold today's records)."""
    out = []
    for d in sorted(p for p in logs.iterdir() if p.is_dir()):
        f = d / "decisions.jsonl"
        if f.exists() and f.stat().st_mtime * 1000 >= since_ms:
            out.append(dr.load_session(d))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--logs", default="logs")
    ap.add_argument("--data", default="data")
    ap.add_argument("--now", type=int, help="epoch ms to treat as now (for testing)")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    now = args.now if args.now is not None else int(time.time() * 1000)
    line, code = status_line(Path(args.logs), Path(args.data), now)
    print(line)
    return code


if __name__ == "__main__":
    sys.exit(main())
