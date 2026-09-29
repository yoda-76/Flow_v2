#!/usr/bin/env python3
"""FLOW watchdog -- the alerting that lives OUTSIDE MotiveWave (2026-09-28).

The runtime raises its own alerts (logs/alerts.log) but if MotiveWave crashes, freezes, the PC restarts, or the disk
fills, nothing inside it is left running to say so. This script is that outside view. Run it every minute (Windows Task
Scheduler -- ops/register_tasks.ps1 -- or `--loop 60`), on the same machine as MotiveWave. It only READS the journals
and the process list; it never touches MotiveWave, the study, an order or the account.

What it checks each run
  journal        the newest logs/*/decisions.jsonl must have been written to in the last --down-seconds (default 90;
                 the runtime writes a heartbeat about every 10 s). Silence = MotiveWave/the study crashed or froze.
  clean stop     the study logged DEACTIVATE/DESTROY as its last act -> STOPPED (an ALERT when --expect-running, the
                 default, because a 24/7 run should never be stopped; INFO with --no-expect-running)
  heartbeat      newest heartbeat: healthy=false, armed=false with armDenied (a kill switch / safety disarm),
                 feedState not LIVE (market data stale)
  process        a MotiveWave process exists (Windows; --no-process-check to skip)
  disk           free space on the drive holding the repo >= --min-free-gb (default 5)
  alerts.log     with --forward-alerts (default), NEW lines the runtime wrote to logs/alerts.log are forwarded to the
                 same channel, so a Telegram bot works without any Java-side Telegram code.
Also appends a row to logs/watchdog_metrics.csv every run (journal age, feed state, MotiveWave memory, free disk) --
that file is the soak-test record ("measure memory after day one").

Channel: every alert is appended to logs/alerts.log (tagged "(watchdog)") and, if TELEGRAM_BOT_TOKEN and
TELEGRAM_CHAT_ID are set in the environment or in <repo>/.env, sent to that Telegram chat. This script reads .env when
YOU run it; nothing else in the project does. `--test-alert` sends one message so you can check the bot.

State (logs/watchdog_state.json) makes it quiet: an alert is sent when a condition starts, repeated every
--repeat-minutes (default 30) while it lasts, and a "recovered" note is sent when it clears.

Exit code: 0 all healthy, 1 something is wrong, 2 the watchdog itself failed.
"""

import argparse
import csv
import json
import os
import platform
import shutil
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

CLEAN_STOP_PREFIXES = ("DEACTIVATE", "DESTROY")
WATCHDOG_TAG = "(watchdog)"


# ---------------------------------------------------------------------------
# reading the journals (cheap: only the tail of the newest one)
# ---------------------------------------------------------------------------

def newest_journal(logs: Path):
    """(mtime_ms, dir) of the session directory whose decisions.jsonl was written most recently, or None."""
    best = None
    if not logs.exists():
        return None
    for d in logs.iterdir():
        f = d / "decisions.jsonl"
        if d.is_dir() and f.exists():
            m = f.stat().st_mtime * 1000
            if best is None or m > best[0]:
                best = (m, d)
    return best


def tail_records(path: Path, nbytes: int = 400_000):
    """The last records of a jsonl file (the first, possibly partial, line is dropped)."""
    size = path.stat().st_size
    with open(path, "rb") as fh:
        if size > nbytes:
            fh.seek(size - nbytes)
            fh.readline()
        data = fh.read()
    out = []
    for line in data.decode("utf-8", errors="replace").splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out


def journal_facts(logs: Path, now_ms: int):
    nj = newest_journal(logs)
    if nj is None:
        return {"found": False}
    mtime_ms, d = nj
    recs = tail_records(d / "decisions.jsonl")
    hb = next((r for r in reversed(recs) if r.get("type") == "heartbeat"), None)
    last_log = next((r for r in reversed(recs) if r.get("type") == "log"), None)
    clean_stop = bool(last_log and last_log.get("msg", "").startswith(CLEAN_STOP_PREFIXES))
    return {"found": True, "dir": d.name, "age_ms": int(max(0, now_ms - mtime_ms)), "clean_stop": clean_stop,
            "stop_msg": last_log.get("msg") if clean_stop else None, "hb": hb}


# ---------------------------------------------------------------------------
# judging (pure)
# ---------------------------------------------------------------------------

def evaluate(facts, opts, mw_running, disk_free_gb):
    """List of (severity, key, message)."""
    out = []
    if not facts.get("found"):
        out.append(("ALERT", "WATCHDOG_NO_JOURNAL", "no journal found under logs/ -- the study has never run here, or FLOW_HOME is wrong"))
    elif facts["clean_stop"]:
        sev = "ALERT" if opts["expect_running"] else "INFO"
        out.append((sev, "WATCHDOG_STOPPED", f"the study is stopped ({facts['stop_msg']}); last journal write "
                    f"{facts['age_ms'] // 60000} min ago" + (" -- a 24/7 run should not be stopped" if opts["expect_running"] else "")))
    elif facts["age_ms"] > opts["down_seconds"] * 1000:
        out.append(("ALERT", "WATCHDOG_DOWN", f"the journal has been silent for {facts['age_ms'] // 1000} s (limit "
                    f"{opts['down_seconds']} s) -- MotiveWave or the study crashed or froze"))
    else:
        hb = facts.get("hb")
        if hb is None:
            out.append(("WARN", "WATCHDOG_NO_HEARTBEAT", "the newest journal has no heartbeat record yet"))
        else:
            rt = hb.get("runtime") if isinstance(hb.get("runtime"), dict) else {}
            if hb.get("healthy") is False:
                out.append(("ALERT", "WATCHDOG_UNHEALTHY", "the pipeline reports healthy=false (a feature/strategy exception disarmed it)"))
            if hb.get("armed") is False and rt.get("armDenied"):
                out.append(("ALERT", "WATCHDOG_DISARMED", "the runtime is DISARMED by a safety rule (kill switch, order anomaly, refused account, "
                            "refused at activation) -- remove and re-add the study after checking the account"))
            elif hb.get("armed") is False and opts["expect_running"]:
                out.append(("WARN", "WATCHDOG_NOT_ARMED", "the study is running but not armed (Armed unchecked or Mode not SIM_LIVE)"))
            feed = hb.get("feedState")
            if feed and feed != "LIVE":
                out.append(("ALERT", "WATCHDOG_FEED", f"market-data feed state is {feed} -- MotiveWave may need a manual Rithmic disconnect/connect"))
    if mw_running is False:
        out.append(("ALERT", "WATCHDOG_MW_NOT_RUNNING", "no MotiveWave process is running on this machine"))
    if disk_free_gb is not None and disk_free_gb < opts["min_free_gb"]:
        out.append(("ALERT", "WATCHDOG_DISK", f"only {disk_free_gb:.1f} GB free on the drive holding the repo (limit {opts['min_free_gb']} GB)"))
    return out


# ---------------------------------------------------------------------------
# channel
# ---------------------------------------------------------------------------

def load_env(env_file: Path):
    """TELEGRAM_* values: real environment first, then a simple KEY=VALUE .env in the repo root (only those keys)."""
    vals = {k: os.environ[k] for k in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID") if os.environ.get(k)}
    if env_file.exists() and len(vals) < 2:
        try:
            for line in env_file.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k, v = k.strip(), v.strip().strip('"').strip("'")
                if k in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID") and v and k not in vals:
                    vals[k] = v
        except OSError:
            pass
    return vals


def telegram_send(text, token, chat_id, timeout=10):
    """True if Telegram accepted the message. Never raises."""
    try:
        url = f"https://api.telegram.org/bot{token}/sendMessage"
        body = urllib.parse.urlencode({"chat_id": chat_id, "text": text[:3900]}).encode()
        with urllib.request.urlopen(urllib.request.Request(url, data=body), timeout=timeout) as resp:
            return 200 <= resp.status < 300
    except Exception:
        return False


def fmt_ts(ms):
    """2026-09-28T23:35:32+05:30 -- the same shape the runtime's LogFileAlertSink writes."""
    return datetime.fromtimestamp(ms / 1000).astimezone().isoformat(timespec="seconds")


class Channel:
    """Appends to logs/alerts.log and (if configured) Telegram. `sent` records every call for tests."""

    def __init__(self, alerts_log: Path, env: dict, dry_run=False, telegram=telegram_send):
        self.alerts_log, self.env, self.dry_run, self.telegram = alerts_log, env, dry_run, telegram
        self.sent = []

    def send(self, severity, key, message, now_ms, from_watchdog=True):
        self.sent.append((severity, key, message))
        tag = f" {WATCHDOG_TAG}" if from_watchdog else ""
        line = f"{fmt_ts(now_ms)} [{severity}] {key}: {message}{tag}"
        if self.dry_run:
            print("DRY-RUN", line)
            return
        if from_watchdog:
            try:
                self.alerts_log.parent.mkdir(parents=True, exist_ok=True)
                with open(self.alerts_log, "a", encoding="utf-8") as fh:
                    fh.write(line + "\n")
            except OSError:
                pass
        if self.env.get("TELEGRAM_BOT_TOKEN") and self.env.get("TELEGRAM_CHAT_ID"):
            icon = {"ALERT": "\U0001F534", "WARN": "\U0001F7E1", "INFO": "ℹ️"}.get(severity, "")
            self.telegram(f"{icon} FLOW {severity} {key}\n{message}\n[{socket.gethostname()}]",
                          self.env["TELEGRAM_BOT_TOKEN"], self.env["TELEGRAM_CHAT_ID"])


# ---------------------------------------------------------------------------
# one run
# ---------------------------------------------------------------------------

def list_motivewave_processes():
    """[(name, mem_kb)] for processes whose name contains 'motivewave' (Windows tasklist); None if it cannot tell."""
    if platform.system() != "Windows":
        return None
    try:
        out = subprocess.run(["tasklist", "/FO", "CSV", "/NH"], capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    procs = []
    for row in csv.reader(out.splitlines()):
        if len(row) >= 5 and "motivewave" in row[0].lower():
            digits = "".join(ch for ch in row[4] if ch.isdigit())
            procs.append((row[0], int(digits) if digits else 0))
    return procs


def run_once(root: Path, now_ms: int, opts, channel: Channel, procs=None, disk_free_gb=None):
    """Returns the list of current findings. `procs` is a list of (name, mem_kb) or None (unknown)."""
    logs = root / "logs"
    facts = journal_facts(logs, now_ms)
    mw_running = None if procs is None else len(procs) > 0
    findings = evaluate(facts, opts, mw_running if opts["process_check"] else None, disk_free_gb)

    state_file = logs / "watchdog_state.json"
    try:
        state = json.loads(state_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        state = {}
    active = state.get("active", {})
    repeat_ms = opts["repeat_minutes"] * 60_000
    now_keys = set()
    for sev, key, msg in findings:
        now_keys.add(key)
        prev = active.get(key)
        if prev is None or now_ms - prev["last_sent"] >= repeat_ms:
            note = "" if prev is None else f" (still: for {(now_ms - prev['since']) // 60000} min)"
            channel.send(sev, key, msg + note, now_ms)
            active[key] = {"since": prev["since"] if prev else now_ms, "last_sent": now_ms, "severity": sev}
    for key in list(active):
        if key not in now_keys:
            if active[key].get("severity") in ("ALERT", "WARN"):
                channel.send("INFO", key + "_RECOVERED", f"{key} cleared after {(now_ms - active[key]['since']) // 60000} min", now_ms)
            del active[key]

    # forward NEW lines the runtime wrote to logs/alerts.log (skipping our own)
    alerts_log = logs / "alerts.log"
    offset = state.get("alerts_offset")
    if opts["forward_alerts"] and alerts_log.exists():
        size = alerts_log.stat().st_size
        if offset is None:
            offset = size if not opts["forward_history"] else 0     # first run: do not flood with history
        if size < offset:
            offset = 0                                              # the file was replaced/rotated
        if size > offset:
            with open(alerts_log, "rb") as fh:
                fh.seek(offset)
                chunk = fh.read()
            complete = chunk[: chunk.rfind(b"\n") + 1]
            for line in complete.decode("utf-8", errors="replace").splitlines():
                if line.strip() and not line.rstrip().endswith(WATCHDOG_TAG):
                    sev = "ALERT" if "[ALERT]" in line else "WARN" if "[WARN]" in line else "INFO"
                    channel.send(sev, "RUNTIME", line.split("] ", 1)[-1], now_ms, from_watchdog=False)
            offset += len(complete)
    elif offset is None:
        offset = 0   # no alerts.log yet: whatever appears later is new

    state = {"active": active, "alerts_offset": offset}
    if not opts.get("dry_run"):
        try:
            logs.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps(state), encoding="utf-8")
            write_metrics(logs / "watchdog_metrics.csv", now_ms, facts, procs, disk_free_gb)
        except OSError:
            pass
    return findings


def write_metrics(path: Path, now_ms, facts, procs, disk_free_gb):
    new = not path.exists()
    hb = facts.get("hb") or {}
    mem_mb = None if procs is None else round(sum(m for _, m in procs) / 1024, 1)
    with open(path, "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        if new:
            w.writerow(["time", "journal_age_s", "feed_state", "armed", "motivewave_procs", "motivewave_mem_mb", "disk_free_gb"])
        w.writerow([fmt_ts(now_ms), None if not facts.get("found") else facts["age_ms"] // 1000, hb.get("feedState"), hb.get("armed"),
                    None if procs is None else len(procs), mem_mb, None if disk_free_gb is None else round(disk_free_gb, 1)])


# ---------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", default=os.environ.get("FLOW_HOME") or str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--down-seconds", type=int, default=90)
    ap.add_argument("--min-free-gb", type=float, default=5.0)
    ap.add_argument("--repeat-minutes", type=int, default=30)
    ap.add_argument("--no-expect-running", dest="expect_running", action="store_false")
    ap.add_argument("--no-process-check", dest="process_check", action="store_false")
    ap.add_argument("--no-forward-alerts", dest="forward_alerts", action="store_false")
    ap.add_argument("--forward-history", action="store_true", help="on the first run also forward what is already in alerts.log")
    ap.add_argument("--loop", type=int, metavar="SECONDS", help="keep running, once every SECONDS (instead of Task Scheduler)")
    ap.add_argument("--dry-run", action="store_true", help="print what would be sent; write and send nothing")
    ap.add_argument("--test-alert", action="store_true", help="send one test message through the channel and exit")
    ap.add_argument("--now", type=int, help="epoch ms to treat as now (testing)")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    root = Path(args.root)
    opts = {"down_seconds": args.down_seconds, "min_free_gb": args.min_free_gb, "repeat_minutes": args.repeat_minutes,
            "expect_running": args.expect_running, "process_check": args.process_check,
            "forward_alerts": args.forward_alerts, "forward_history": args.forward_history, "dry_run": args.dry_run}
    env = load_env(root / ".env")
    channel = Channel(root / "logs" / "alerts.log", env, dry_run=args.dry_run)
    if args.test_alert:
        have = bool(env.get("TELEGRAM_BOT_TOKEN") and env.get("TELEGRAM_CHAT_ID"))
        channel.send("INFO", "WATCHDOG_TEST", "test message from the FLOW watchdog" + ("" if have else " (no Telegram credentials found: file only)"),
                     int(time.time() * 1000))
        print("sent to logs/alerts.log" + (" and Telegram" if have else " only -- TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set"))
        return 0
    while True:
        try:
            now = args.now if args.now is not None else int(time.time() * 1000)
            try:
                disk = shutil.disk_usage(root).free / 1e9
            except OSError:
                disk = None
            findings = run_once(root, now, opts, channel, list_motivewave_processes(), disk)
            print(("OK" if not findings else "PROBLEM: " + "; ".join(f"{k}" for _, k, _ in findings)), flush=True)
            code = 0 if not findings else 1
        except Exception as e:  # the watchdog must never die quietly
            print("WATCHDOG ERROR", repr(e), file=sys.stderr, flush=True)
            code = 2
        if not args.loop:
            return code
        time.sleep(args.loop)


if __name__ == "__main__":
    sys.exit(main())
