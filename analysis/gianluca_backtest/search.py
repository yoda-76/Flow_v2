"""Parameter space, evaluation, stage config generators (plan §6-7).

A config is a flat dict of signal params (signals.BASELINE) + sizing params (sizing.SIZING_BASELINE).
hash = sha1 of the sorted-params JSON. Periods: FULL = sessions [1, n), H1 = [1, mid), H2 = [mid, n), mid = n // 2.
"""
import hashlib
import json
import random

import data
import features as F
import metrics
import signals as S
import sizing

# first value = baseline
SPACE = {
    "tf": [15, 5, 10, 30],
    "thr": S.THR_VALUES,
    "vol_cond": ["either", "reducing", "loweffort", "both", "none"],
    "target_r": [2.0, 1.0, 1.5, 2.5, 3.0],
    "swing_n": [3, 2, 5],
    "va_pct": [70, 60, 80],
    "vol_ma": ["sma", "ema"],
    "trend": ["both", "poc", "none"],
    "trend_timing": ["completed", "developing"],
    "ceiling": ["on", "off"],
    "min_rr": [1.0, 0.0, 0.5],
    "reentry_vah": ["off", "on"],
    "session_end": ["flatten", "hold"],
    "stop_buf": [0, 1, 3],
    "news": ["off", "0830"],
    "ms_break": ["trade", "close"],
    "setups": ["one", "many"],
    "direction": ["both", "long", "short"],
    "min_score": [0, 1, 2, 3],
    "streak": ["on", "off"],
    "kelly_cap": [2.0, 1.0],
}
A_KEYS = ["tf", "thr", "vol_cond", "target_r", "swing_n"]
B_KEYS = [k for k in SPACE if k not in A_KEYS]
# P4 gold variants (2026-10-13), baseline first = the behaviour before P4. Added after B_KEYS so stage B (already
# run) keeps its meaning; stage_b adds them via VARIANT_KEYS for the H1 search, coord descent covers all of SPACE.
VARIANTS = {
    "sess_filter": ["all", "no_ny", "asia_only"],
    "atr_filter": ["all", "no_low"],
    "fresh_filter": ["all", "fresh_only"],
    "stop_atr": [0.0, 0.25, 0.5],
    "ma_filter": ["off", "200"],
}
SPACE.update(VARIANTS)
VARIANT_KEYS = list(VARIANTS)
C_TOP, C_GRID_TOP = 20, 5
BASELINE = {k: v[0] for k, v in SPACE.items()}
assert {k: BASELINE[k] for k in S.BASELINE} == S.BASELINE
SIGNAL_KEYS = [k for k in SPACE if k not in sizing.SIZING_BASELINE]

FLOOR = {"full": 150, "H1": 75, "H2": 0}
B_TOP, B_SAMPLES, D_CAP, H2_TOP = 20, 150, 300, 50


def cfg_hash(cfg):
    return hashlib.sha1(json.dumps(cfg, sort_keys=True).encode()).hexdigest()[:16]


# ---------------- evaluation (runs inside worker processes) ----------------
_W = {}


def _worker_init():
    base = data.load_base()
    _W["base"] = base
    _W["vas"] = F.load_vas(base)
    _W["ctx"] = {}


def _ctx(tf):
    c = _W["ctx"].get(tf)
    if c is None:
        c = _W["ctx"][tf] = S.Ctx(_W["base"], _W["vas"], F.load_tf(_W["base"], tf))
    return c


def periods(n):
    mid = n // 2
    return {"full": (1, n), "H1": (1, mid), "H2": (mid, n)}


def evaluate(cfg, eval_p="full", learn_p=None, want_trades=False):
    """Metrics for cfg on period eval_p, score buckets learned on learn_p (default = eval_p)."""
    base = _W["base"]
    per = periods(len(base.s_day))
    learn_p = learn_p or eval_p
    e0, e1 = per[eval_p]
    l0, l1 = per[learn_p]
    cfg = {**BASELINE, **cfg}          # A/B lines predate the P4 variants
    sig = {k: cfg[k] for k in S.BASELINE}
    hi = max(e1, l1)
    trades = S.run(_ctx(cfg["tf"]), sig, 1, hi)
    buckets = sizing.best_buckets([t for t in trades if l0 <= t["si"] < l1])
    sp = {k: cfg[k] for k in sizing.SIZING_BASELINE}
    sized = sizing.overlay(trades, sp, buckets, e0, e1)
    m = metrics.compute(base, sized, e0, e1)
    if m["pf"] == float("inf"):
        m["pf"] = 999.0
    out = {"metrics": m, "buckets": buckets}
    if want_trades:
        out["sized"] = sized
    return out


def task(job):
    """job = {"cfg", "eval", "learn", "seed", "tag"} -> result line dict."""
    r = evaluate(job["cfg"], job.get("eval", "full"), job.get("learn"))
    return {"hash": cfg_hash(job["cfg"]), "tag": job.get("tag"), "seed": job.get("seed"), "eval": job.get("eval", "full"),
            "learn": job.get("learn") or job.get("eval", "full"), "params": job["cfg"], **r}


# ---------------- ranking ----------------
def rank_key(line, floor):
    m = line["metrics"]
    return (m["trades"] >= floor, m["net_usd"], m["sharpe"])


def result_key(ln):
    m = ln["metrics"]
    return (m["signals"], m["trades"], round(m["net_usd"], 2))


def ranked(lines, floor, distinct=True, by_result=False):
    """Best first. distinct: drop repeated config hashes; by_result: also drop configs whose result (signals, taken,
    net $) equals a better-ranked one -- different params that produce the same trades (P6 finding)."""
    seen, out = set(), []
    for ln in sorted(lines, key=lambda x: rank_key(x, floor), reverse=True):
        k = result_key(ln) if by_result else ln["hash"]
        if distinct and k in seen:
            continue
        seen.add(k); out.append(ln)
    return out


# ---------------- stage configs ----------------
def with_base(**kw):
    c = dict(BASELINE)
    c.update(kw)
    return c


def stage_a(limit=None):
    out = []
    for tf in SPACE["tf"]:
        for thr in SPACE["thr"]:
            for vc in SPACE["vol_cond"]:
                for tr in SPACE["target_r"]:
                    for n in SPACE["swing_n"]:
                        out.append(with_base(tf=tf, thr=thr, vol_cond=vc, target_r=tr, swing_n=n))
    return out[:limit] if limit else out


def stage_b(a_lines, floor, seed0=1000, by_result=False):
    """150 seeded random samples of B_KEYS (+variants) around each of the top 20 A configs, then a one-at-a-time sweep
    around the single best. Returns list of (cfg, seed)."""
    top = ranked(a_lines, floor, by_result=by_result)[:B_TOP]
    keys = B_KEYS + VARIANT_KEYS
    out = []
    for i, ln in enumerate(top):
        seed = seed0 + i
        rnd = random.Random(seed)
        for _ in range(B_SAMPLES):
            c = dict(ln["params"])
            for k in keys:
                c[k] = rnd.choice(SPACE[k])
            out.append((c, seed))
    best = top[0]["params"]
    for k in keys:
        for v in SPACE[k][1:] if best[k] == SPACE[k][0] else SPACE[k]:
            if v != best[k]:
                c = dict(best); c[k] = v
                out.append((c, None))
    return out


def stage_c(prior, floor):
    """P4 variants: each variant value one at a time on the 20 best A+B configs, plus the full variant grid on the 5
    best. Returns normalized cfgs (deduplicated)."""
    import itertools
    top = [with_base(**ln["params"]) for ln in ranked(prior, floor)[:C_TOP]]
    out, seen = [], set()

    def add(c):
        h = cfg_hash(c)
        if h not in seen:
            seen.add(h); out.append(c)
    for base_cfg in top:
        for k in VARIANT_KEYS:
            for v in VARIANTS[k][1:]:
                c = dict(base_cfg); c[k] = v; add(c)
    for base_cfg in top[:C_GRID_TOP]:
        for combo in itertools.product(*(VARIANTS[k] for k in VARIANT_KEYS)):
            c = dict(base_cfg); c.update(zip(VARIANT_KEYS, combo))
            if c != base_cfg:
                add(c)
    return out


def coord_descent(start, evaluate_many, floor, cap=D_CAP):
    """Coordinate descent: for each parameter in turn, evaluate every other value (one batch), move to the best if it
    beats the current; repeat full passes until a pass makes no move. evaluate_many(cfgs) -> lines (cached by hash).
    Returns (best line, runs used)."""
    cur = evaluate_many([start])[0]
    used = 0
    keys = list(SPACE)
    improved = True
    while improved and used < cap:
        improved = False
        for k in keys:
            cands = []
            for v in SPACE[k]:
                if v != cur["params"][k]:
                    c = dict(cur["params"]); c[k] = v
                    cands.append(c)
            cands = cands[:max(0, cap - used)]
            if not cands:
                break
            lines = evaluate_many(cands)
            used += len(lines)
            best = max(lines, key=lambda x: rank_key(x, floor))
            if rank_key(best, floor) > rank_key(cur, floor):
                cur = best; improved = True
    return cur, used
