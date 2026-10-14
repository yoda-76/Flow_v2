#!/usr/bin/env python3
"""Telegram notifier for live Sim trades (2026-10-11). Read-only over logs/; never touches an order.

Follows, from the moment it starts (no history):
  - the newest logs/<strategy>_*/decisions.jsonl: order_fill (entry / stop / target / ...), order_refused,
    entry_missed, kill_switch_flatten
  - logs/orderflow_eval_*.log: the strategy's entry / exit / error lines (prices converted with the session's price_anchor)
  - logs/alerts.log: new [ALERT] lines the runtime writes (not the watchdog's own), same key at most once per 10 min;
    --no-alerts turns this off when ops/watchdog.py runs (it forwards alerts.log itself; run it with
    --no-forward-orderflow so entries/exits are not sent twice)
and sends each to Telegram through ops/watchdog.py's channel (TELEGRAM_* from the environment or the repo's .env).

  python3 ops/trade_notify.py [--strategy orderflow_execution] [--poll 5] [--no-alerts] [--dry-run]
"""
import argparse
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from watchdog import load_env, telegram_send  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
LOGS = ROOT / "logs"
ALERT_RE = re.compile(r"\[ALERT\] ([A-Z_]+):")


class Follower:
    """New complete lines appended to a file since the last call. Starts at the end unless from_start."""

    def __init__(self, path, from_start=False):
        self.path, self.buf = path, b""
        self.offset = 0 if from_start or not path.exists() else path.stat().st_size

    def lines(self):
        try:
            size = self.path.stat().st_size
        except OSError:
            return []
        if size < self.offset:
            self.offset, self.buf = 0, b""
        if size == self.offset:
            return []
        with open(self.path, "rb") as fh:
            fh.seek(self.offset)
            data = fh.read(size - self.offset)
        self.offset = size
        data = self.buf + data
        parts = data.split(b"\n")
        self.buf = parts.pop()
        return [p.decode("utf-8", "replace") for p in parts if p.strip()]


def newest_session(strategy):
    dirs = [d for d in LOGS.glob(f"{strategy}_*") if (d / "decisions.jsonl").exists()]
    return max(dirs, key=lambda d: d.stat().st_mtime) if dirs else None


def read_anchor(decisions):
    try:
        with open(decisions, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if '"price_anchor"' in line:
                    r = json.loads(line)
                    return r["price"], r["tickSize"]
    except (OSError, ValueError, KeyError):
        pass
    return None, None


def px(ticks, anchor, tick):
    if ticks is None or anchor is None:
        return str(ticks) + " ticks"
    return f"{anchor + ticks * tick:.1f}"


def main(argv=None):
    global LOGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--strategy", default="orderflow_execution")
    ap.add_argument("--poll", type=float, default=5.0)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--logs", default=str(LOGS), help="logs folder (testing)")
    ap.add_argument("--no-alerts", dest="alerts", action="store_false",
                    help="do not forward alerts.log [ALERT] lines (when ops/watchdog.py already forwards alerts.log)")
    ap.add_argument("--once", action="store_true", help="two polls, then exit (testing)")
    args = ap.parse_args(argv)
    LOGS = Path(args.logs)

    env = load_env(ROOT / ".env")
    if not args.dry_run and not (env.get("TELEGRAM_BOT_TOKEN") and env.get("TELEGRAM_CHAT_ID")):
        print("TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set", file=sys.stderr)
        return 2

    def send(text):
        stamp = time.strftime("%H:%M:%S UTC", time.gmtime())
        msg = f"{text}\n[{stamp}]"
        print(msg.replace("\n", " | "), flush=True)
        if not args.dry_run:
            telegram_send(msg, env["TELEGRAM_BOT_TOKEN"], env["TELEGRAM_CHAT_ID"])

    session, dec = None, None
    anchor, tick = None, None
    evals = {p: Follower(p) for p in LOGS.glob("orderflow_eval_*.log")}
    alerts = Follower(LOGS / "alerts.log")
    last_alert = {}
    entry_fill = None  # (price, +1/-1, qty, pointValue)
    send(f"FLOW trade notifier started ({args.strategy})")

    polls = 0
    while True:
        s = newest_session(args.strategy)
        if s != session and s is not None:
            session = s
            dec = Follower(s / "decisions.jsonl", from_start=session is not None and dec is not None)
            anchor, tick = read_anchor(s / "decisions.jsonl")
        if anchor is None and session is not None:
            anchor, tick = read_anchor(session / "decisions.jsonl")

        for line in dec.lines() if dec else []:
            try:
                r = json.loads(line)
            except ValueError:
                continue
            t = r.get("type")
            if t == "order_fill":
                role, side = r.get("role"), r.get("action")
                price, qty = r.get("avgFillPrice"), r.get("filled") or r.get("quantity")
                head = f"{'🟢' if role == 'entry' else '🏁'} FILL {role}: {side} {qty} {r.get('instrument')} @ {price}"
                extra = f"account={r.get('accountId')} position after={r.get('positionAfter')}"
                if role == "entry":
                    entry_fill = (price, 1 if side == "BUY" else -1, qty or 1, r.get("pointValue") or 100.0)
                elif entry_fill and price is not None:
                    e, d, q, pv = entry_fill
                    pnl = (price - e) * d * q * pv
                    extra += f"\nP&L {pnl:+.0f} USD ({(price - e) * d / (r.get('tickSize') or 0.1):+.0f} ticks) vs entry {e}"
                    entry_fill = None
                send(f"{head}\n{extra}\ncash={r.get('cashBalance')}")
            elif t in ("order_refused", "entry_missed", "kill_switch_flatten"):
                send(f"⚠️ {t}\n{line[:600]}")

        for p in LOGS.glob("orderflow_eval_*.log"):
            if p not in evals:
                evals[p] = Follower(p, from_start=True)
        for f in evals.values():
            for line in f.lines():
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                t = r.get("type")
                if t == "entry":
                    side = "LONG" if r.get("dir", 0) > 0 else "SHORT"
                    send(f"📈 SIGNAL entry {side} model={r.get('model')} idea={r.get('idea')}\n"
                         f"entry {px(r.get('entry'), anchor, tick)} stop {px(r.get('stop'), anchor, tick)} "
                         f"target {px(r.get('target'), anchor, tick)} rr={r.get('rr', 0):.2f}")
                elif t == "exit":
                    send(f"📉 SIGNAL exit {r.get('reason')} model={r.get('model')} price {px(r.get('price'), anchor, tick)}")
                elif t == "error":
                    send(f"🔴 STRATEGY ERROR ({r.get('where')})\n{str(r.get('error'))[:500]}")

        now = time.time()
        for line in alerts.lines() if args.alerts else []:
            m = ALERT_RE.search(line)
            if not m or "(watchdog)" in line:
                continue
            if now - last_alert.get(m.group(1), 0) < 600:
                continue
            last_alert[m.group(1)] = now
            send(f"🔴 {line[:800]}")

        polls += 1
        if args.once and polls >= 2:
            return 0
        time.sleep(args.poll)


if __name__ == "__main__":
    sys.exit(main())
