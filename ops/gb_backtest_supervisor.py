"""Keeps the compute phases of the Gianluca Bruni curiosity backtest running on the VM (plan:
docs/dynamic/gianlucaBruniBacktest_plan_v1.md, §10).

It never starts Claude. Claude phases run in an interactive chat on the VM; when a chat stops on a usage limit, the
user types "continue" in it. This script only looks after the grid runs, which need no Claude:

  * reports/gianluca_backtest/state.json says the current phase and its kind: "claude", "compute", "done" or "stuck".
  * Every tick (default every 10 min, --loop):
      - compute phase: if analysis/gianluca_backtest/runner.py is not running, start it (BELOW_NORMAL priority, so
        MotiveWave keeps the CPU). The runner advances state.json itself when the phase is complete. A runner that exits
        without advancing counts as a failed attempt; after --max-attempts in a row -> STUCK.
      - claude phase: start nothing; notify once that the chat needs a "continue".
      - done / stuck: notify once.
  * Notifications: one line appended to <FLOW_HOME>/logs/alerts.log ("[INFO] GB backtest: ..."), which ops/watchdog.py
    already forwards to Telegram. Nothing else outside reports/gianluca_backtest/ is written.

Nothing here places an order, touches MotiveWave, or reads .env.

  python ops\\gb_backtest_supervisor.py --once            one tick, then exit
  python ops\\gb_backtest_supervisor.py --loop            forever (what the scheduled task runs)
  python ops\\gb_backtest_supervisor.py --status          print state + runner lock
  python ops\\gb_backtest_supervisor.py --reset-stuck     clear STUCK/attempts after the cause is fixed
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "reports" / "gianluca_backtest"
STATE = OUT / "state.json"
LOG = OUT / "supervisor.log"
RUNNER_LOCK = OUT / "runner.lock.json"
RUNNER = REPO / "analysis" / "gianluca_backtest" / "runner.py"

BELOW_NORMAL = 0x00004000
NO_WINDOW = 0x08000000

_CHILDREN = []


def now():
    return time.time()


def stamp(t=None):
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now() if t is None else t))


def log(msg):
    OUT.mkdir(parents=True, exist_ok=True)
    with open(LOG, "a", encoding="utf-8") as fh:
        fh.write(f"{stamp()} {msg}\n")


def read_json(path, default=None):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, indent=2), encoding="utf-8")
    os.replace(tmp, path)


def notify(flow_home, msg, dry_run=False):
    """One line into FLOW's alerts.log; ops/watchdog.py forwards new lines there to Telegram."""
    log("NOTIFY " + msg)
    if dry_run:
        return
    try:
        alerts = Path(flow_home) / "logs" / "alerts.log"
        alerts.parent.mkdir(parents=True, exist_ok=True)
        with open(alerts, "a", encoding="utf-8") as fh:
            fh.write(f"{stamp()} [INFO] GB backtest: {msg}\n")
    except OSError as e:
        log(f"notify failed: {e}")


def notify_once(opts, state, key, msg):
    """Notify at most once per (phase, key); the marker lives in state.json."""
    marker = f"{state.get('phase')}:{key}"
    if state.get("notified") == marker:
        return
    state["notified"] = marker
    write_json(STATE, state)
    notify(opts.flow_home, msg, opts.dry_run)


def pid_alive(pid, image_hint):
    """True if `pid` is running and its image name contains image_hint (guards against PID reuse)."""
    if os.name != "nt":
        try:
            os.kill(pid, 0)
        except OSError:
            return False
        try:
            return image_hint.lower() in Path(f"/proc/{pid}/cmdline").read_bytes().decode(errors="replace").lower()
        except OSError:
            return True   # no /proc: trust os.kill
    try:
        out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
                             capture_output=True, text=True, timeout=30).stdout
    except (OSError, subprocess.SubprocessError):
        return True   # cannot tell: assume alive, never start a second copy
    first = out.strip().splitlines()[0] if out.strip() else ""
    if not first.startswith('"'):
        return False  # "INFO: No tasks are running which match the specified criteria."
    return image_hint.lower() in first.split(",")[0].strip('"').lower()


def reap_children():
    """Forget handles of children that have exited (the lock file, not these handles, drives the logic)."""
    _CHILDREN[:] = [p for p in _CHILDREN if p.poll() is None]


def start_runner(opts, state):
    if not RUNNER.exists():
        return f"{RUNNER} does not exist yet, but phase {state['phase']} is a compute phase"
    logfile = OUT / "runner_logs" / f"{time.strftime('%Y%m%d_%H%M%S')}_{state['phase']}.log"
    logfile.parent.mkdir(parents=True, exist_ok=True)
    if opts.dry_run:
        log(f"DRY-RUN would start runner for {state['phase']}")
        return None
    # low priority either way: BELOW_NORMAL on Windows, nice +10 in its own session on Linux
    extra = {"creationflags": BELOW_NORMAL | NO_WINDOW} if os.name == "nt" else \
        {"start_new_session": True, "preexec_fn": lambda: os.nice(10)}
    with open(logfile, "ab") as fh:
        p = subprocess.Popen([opts.python or sys.executable, "-u", str(RUNNER), "--resume"], cwd=str(REPO),
                             stdout=fh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, **extra)
    _CHILDREN.append(p)   # keep the handle: dropping a running Popen raises a ResourceWarning
    write_json(RUNNER_LOCK, {"pid": p.pid, "image": "python", "started": now(), "phase": state["phase"],
                             "log": str(logfile)})
    log(f"started runner for {state['phase']} pid={p.pid} log={logfile.name}")
    return None


def mark_stuck(opts, state, why):
    state.update(resume_kind=state.get("kind"), kind="stuck", note=why)
    write_json(STATE, state)
    notify_once(opts, state, "stuck", f"STUCK in {state.get('phase')}: {why} -- fix, then run: "
                                      f"python ops/gb_backtest_supervisor.py --reset-stuck")


def tick(opts):
    reap_children()
    state = read_json(STATE)
    if state is None:
        return "no state.json yet (the Claude chat creates it in P0)"

    lock = read_json(RUNNER_LOCK)
    if lock:
        if pid_alive(int(lock["pid"]), lock.get("image", "")):
            return f"runner running ({(now() - lock.get('started', now())) / 3600:.1f} h, phase {lock.get('phase')})"
        RUNNER_LOCK.unlink(missing_ok=True)
        if state.get("phase") != lock.get("phase"):
            state["attempts"] = 0
            log(f"runner finished; phase advanced {lock.get('phase')} -> {state.get('phase')}")
        else:
            state["attempts"] = int(state.get("attempts", 0)) + 1
            log(f"runner exited without finishing {state.get('phase')} (attempt {state['attempts']}/{opts.max_attempts})"
                f" -- see {lock.get('log')}")
        write_json(STATE, state)

    kind = state.get("kind")
    if kind == "done":
        notify_once(opts, state, "done", "DONE -- results in docs/dynamic/gianlucaBruniBacktest_results_v1.md")
        return "done"
    if kind == "stuck":
        notify_once(opts, state, "stuck", f"STUCK in {state.get('phase')}: {state.get('note', '')}")
        return "stuck"
    if kind == "claude":
        notify_once(opts, state, "claude", f"phase {state.get('phase')} needs Claude -- open the chat on the VM and "
                                           f"say \"continue\"")
        return f"waiting for Claude ({state.get('phase')})"
    if kind != "compute":
        mark_stuck(opts, state, f"unknown kind {kind!r} in state.json")
        return "stuck"
    if int(state.get("attempts", 0)) >= opts.max_attempts:
        mark_stuck(opts, state, f"runner failed {opts.max_attempts} times in a row")
        return "stuck"
    err = start_runner(opts, state)
    if err:
        mark_stuck(opts, state, err)
        return "stuck"
    return f"started runner for {state['phase']}"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--once", action="store_true")
    mode.add_argument("--loop", action="store_true")
    mode.add_argument("--status", action="store_true")
    mode.add_argument("--reset-stuck", action="store_true")
    ap.add_argument("--interval-min", type=float, default=10)
    ap.add_argument("--max-attempts", type=int, default=4, help="failed runner exits in a row before STUCK")
    ap.add_argument("--python", default=None, help="python used for the runner (default: this one)")
    ap.add_argument("--flow-home", default=os.environ.get("FLOW_HOME") or str(REPO))
    ap.add_argument("--dry-run", action="store_true", help="log what would start; start nothing, notify nobody")
    opts = ap.parse_args(argv)

    if opts.status:
        print(json.dumps({"state": read_json(STATE), "runner_lock": read_json(RUNNER_LOCK)}, indent=2))
        return 0
    if opts.reset_stuck:
        state = read_json(STATE)
        if state is None:
            print("no state.json")
            return 1
        if state.get("kind") == "stuck":
            state["kind"] = state.get("resume_kind") or "claude"
        state.update(attempts=0, note="reset by user", notified=None)
        write_json(STATE, state)
        print(f"state reset: phase {state['phase']} kind {state['kind']}")
        return 0
    if opts.once:
        print(tick(opts))
        return 0
    log(f"supervisor loop starting (every {opts.interval_min} min)")
    while True:
        try:
            log("tick: " + tick(opts))
        except Exception as e:   # never let the loop die on one bad tick
            log(f"tick error: {e!r}")
        time.sleep(opts.interval_min * 60)


if __name__ == "__main__":
    sys.exit(main())
