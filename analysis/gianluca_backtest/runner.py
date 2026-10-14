"""Resumable compute runner (plan §10.3).

  python3 analysis/gianluca_backtest/runner.py --resume     run the current compute phase from state.json, then advance

Reads reports/gianluca_backtest/state.json (GB_OUT env overrides the directory, for tests). Runs the stage's configs
not yet in runs/<stage>.jsonl (one flushed line per run), writes runs/<stage>.done, and when every stage of the phase
is done sets state.json to the next phase. Killing it loses at most the runs in flight. Never touches anything outside
the output directory and the feature cache. No orders, no MotiveWave, no .env.
"""
import argparse
import json
import multiprocessing as mp
import os
import sys
import time
import traceback
from pathlib import Path

import search

OUT = Path(os.environ.get("GB_OUT") or Path(__file__).resolve().parents[2] / "reports" / "gianluca_backtest")
STATE = OUT / "state.json"
RUNS = OUT / "runs"
LOCK = OUT / "runner.lock.json"

PHASES = {   # phase -> (stages, next phase, next kind)
    "P3_SEARCH_FULL_A": (["A"], "P3_SEARCH_FULL_B", "compute"),
    "P3_SEARCH_FULL_B": (["B"], "P4_REVIEW_GOLD", "claude"),
    "P5_SEARCH_FULL_C": (["C"], "P5_SEARCH_FULL_D", "compute"),
    "P5_SEARCH_FULL_D": (["D"], "P6_PICK_BEST", "claude"),
    "P7_SPLIT": (["A_on_halves", "H1_A", "H1_B", "H1_D", "H2_top50"], "P8_REPORT", "claude"),
    "TEST_DRY": (["DRY"], "TEST_DONE", "done"),
}
FULL_STAGES = ["A", "B", "C", "D"]
H1_STAGES = ["H1_A", "H1_B", "H1_D"]


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def read_json(p, default=None):
    try:
        return json.loads(Path(p).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def write_json(p, obj):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(p.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, indent=2), encoding="utf-8")
    os.replace(tmp, p)


def load_lines(stage):
    f = RUNS / f"{stage}.jsonl"
    out = []
    if f.exists():
        with open(f, encoding="utf-8") as fh:
            for line in fh:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass       # a line cut off by a kill
    return out


def key(cfg_hash, eval_p, learn_p):
    return f"{cfg_hash}|{eval_p}|{learn_p}"


def line_key(ln):
    return key(ln["hash"], ln["eval"], ln["learn"])


def job_key(job):
    e = job.get("eval", "full")
    return key(search.cfg_hash(job["cfg"]), e, job.get("learn") or e)


class Runner:
    def __init__(self, workers):
        self.workers = workers
        self.pool = None

    def _pool(self):
        if self.pool is None:
            self.pool = mp.get_context("forkserver").Pool(self.workers, initializer=search._worker_init)
        return self.pool

    def close(self):
        if self.pool is not None:
            self.pool.terminate()

    def run_jobs(self, stage, jobs):
        """Run jobs not already in runs/<stage>.jsonl; return all of this stage's lines for these jobs, in order."""
        RUNS.mkdir(parents=True, exist_ok=True)
        have = {line_key(ln): ln for ln in load_lines(stage)}
        todo = [j for j in jobs if job_key(j) not in have]
        if todo:
            log(f"{stage}: {len(todo)} to run ({len(jobs) - len(todo)} already done), {self.workers} workers")
            t0 = time.time()
            with open(RUNS / f"{stage}.jsonl", "a", encoding="utf-8") as fh:
                for i, res in enumerate(self._pool().imap_unordered(search.task, todo, chunksize=1), 1):
                    fh.write(json.dumps(res) + "\n"); fh.flush()
                    have[line_key(res)] = res
                    if i % 100 == 0 or i == len(todo):
                        log(f"{stage}: {i}/{len(todo)} ({(time.time() - t0) / i:.2f} s/run)")
        return [have[job_key(j)] for j in jobs]

    # ---- stages ----
    def stage(self, name):
        done = RUNS / f"{name}.done"
        if done.exists():
            return
        getattr(self, "st_" + name)()
        done.write_text(time.strftime("%Y-%m-%d %H:%M:%S"))
        log(f"{name}: done")

    def st_DRY(self):
        self.run_jobs("DRY", [{"cfg": c} for c in search.stage_a(limit=20)])

    def st_A(self):
        self.run_jobs("A", [{"cfg": c} for c in search.stage_a()])

    def st_H1_A(self):
        self.run_jobs("H1_A", [{"cfg": c, "eval": "H1"} for c in search.stage_a()])

    def _b(self, name, src, eval_p, floor):
        cf = RUNS / f"{name}.configs.json"
        cfgs = read_json(cf)
        if cfgs is None:
            cfgs = [[c, s] for c, s in search.stage_b(load_lines(src), floor, by_result=(name != "B"))]
            write_json(cf, cfgs)
        self.run_jobs(name, [{"cfg": c, "seed": s, "eval": eval_p} for c, s in cfgs])

    def st_B(self):
        self._b("B", "A", "full", search.FLOOR["full"])

    def st_H1_B(self):
        self._b("H1_B", "H1_A", "H1", search.FLOOR["H1"])

    def st_C(self):
        cf = RUNS / "C.configs.json"
        cfgs = read_json(cf)
        if cfgs is None:
            if not hasattr(search, "stage_c"):
                raise RuntimeError("stage C not defined yet (P4 adds search.stage_c)")
            prior = load_lines("A") + load_lines("B")
            cfgs = search.stage_c(prior, search.FLOOR["full"])
            write_json(cf, cfgs)
        self.run_jobs("C", [{"cfg": c} for c in cfgs])

    def _d(self, name, prior_stages, eval_p, floor):
        prior = [ln for s in prior_stages for ln in load_lines(s) if ln["eval"] == eval_p]
        start = search.with_base(**search.ranked(prior, floor)[0]["params"])
        cache = {line_key(ln): ln for ln in prior}

        def evaluate_many(cfgs):
            jobs = [{"cfg": c, "eval": eval_p} for c in cfgs]
            missing = [j for j in jobs if job_key(j) not in cache]
            for ln in self.run_jobs(name, missing) if missing else []:
                cache[line_key(ln)] = ln
            return [cache[job_key(j)] for j in jobs]

        best, used = search.coord_descent(start, evaluate_many, floor)
        log(f"{name}: coordinate descent used {used} runs; best {best['hash']} net ${best['metrics']['net_usd']:.0f}")

    def st_D(self):
        self._d("D", ["A", "B", "C"], "full", search.FLOOR["full"])

    def st_H1_D(self):
        self._d("H1_D", H1_STAGES[:2], "H1", search.FLOOR["H1"])

    def st_A_on_halves(self):
        lines = [ln for s in FULL_STAGES for ln in load_lines(s) if ln["eval"] == "full"]
        a_star = search.ranked(lines, search.FLOOR["full"])[0]["params"]
        self.run_jobs("A_on_halves", [{"cfg": a_star, "eval": "H1", "learn": "full", "tag": "A*"},
                                      {"cfg": a_star, "eval": "H2", "learn": "full", "tag": "A*"}])

    def st_H2_top50(self):
        lines = [ln for s in H1_STAGES for ln in load_lines(s) if ln["eval"] == "H1"]
        top = search.ranked(lines, search.FLOOR["H1"], by_result=True)[:search.H2_TOP]
        self.run_jobs("H2_top50", [{"cfg": ln["params"], "eval": "H2", "learn": "H1", "tag": f"H1rank{i + 1}"}
                                   for i, ln in enumerate(top)])


def pid_alive(pid):
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    try:
        return "runner.py" in Path(f"/proc/{pid}/cmdline").read_bytes().decode(errors="replace")
    except OSError:
        return True


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--resume", action="store_true", required=True)
    ap.add_argument("--workers", type=int, default=None)
    a = ap.parse_args(argv)
    state = read_json(STATE)
    if not state or state.get("kind") != "compute" or state.get("phase") not in PHASES:
        log(f"nothing to run: state = {state}")
        return 0
    lock = read_json(LOCK)
    if lock and lock.get("pid") != os.getpid() and pid_alive(int(lock["pid"])):
        log(f"another runner is alive (pid {lock['pid']}); exiting")
        return 0
    write_json(LOCK, {"pid": os.getpid(), "image": "python", "started": time.time(), "phase": state["phase"]})
    cfg = read_json(OUT / "config.json", {})
    workers = a.workers or cfg.get("workers") or max(1, (os.cpu_count() or 2) // 2)
    r = Runner(workers)
    try:
        # no supervisor on this VM: keep going while the next phase is also a compute phase
        while state and state.get("kind") == "compute" and state.get("phase") in PHASES:
            phase = state["phase"]
            stages, nxt, nkind = PHASES[phase]
            log(f"phase {phase}: stages {stages}")
            for s in stages:
                r.stage(s)
            state = read_json(STATE)
            if state.get("phase") == phase:
                state.update(phase=nxt, kind=nkind, attempts=0, note=f"{phase} done {time.strftime('%Y-%m-%d %H:%M')}")
                write_json(STATE, state)
                log(f"phase {phase} complete -> {nxt}")
        return 0
    except Exception:
        traceback.print_exc()
        return 1
    finally:
        r.close()
        cur = read_json(LOCK)
        if cur and cur.get("pid") == os.getpid():
            LOCK.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
