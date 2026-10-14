"""P4: read stage A+B results. Usage: p4_review.py [top|params|buckets HASH]"""
import json
import statistics as st
import sys
from collections import defaultdict
from pathlib import Path

import search

RUNS = Path(__file__).resolve().parents[2] / "reports" / "gianluca_backtest" / "runs"


def lines(*stages):
    out = []
    for s in stages:
        with open(RUNS / f"{s}.jsonl", encoding="utf-8") as fh:
            out += [json.loads(x) for x in fh]
    return out


what = sys.argv[1] if len(sys.argv) > 1 else "top"
A, B = lines("A"), lines("B")
allx = A + B
FL = search.FLOOR["full"]

if what == "top":
    for name, ls in (("A", A), ("B", B)):
        el = [x for x in ls if x["metrics"]["trades"] >= FL]
        prof = [x for x in el if x["metrics"]["net_usd"] > 0]
        print(f"{name}: {len(ls)} runs, {len(el)} eligible (>= {FL} taken), {len(prof)} eligible & profitable")
    print("hash             taken  net$     ret%  sharpe mdd%  expR   win%  flat1c$  | params != baseline")
    for ln in search.ranked(allx, FL)[:15]:
        m, p = ln["metrics"], ln["params"]
        diff = {k: v for k, v in p.items() if search.BASELINE.get(k) != v}
        print(f"{ln['hash']} {m['trades']:5d} {m['net_usd']:8.0f} {m['ret_pct']:6.1f} {m['sharpe']:5.2f} "
              f"{m['mdd_pct']:5.1f} {m['exp_r']:6.3f} {100 * m['win_rate']:5.1f} {m['flat_usd']:8.0f}  | {diff}")

elif what == "params":
    # per parameter value: median net $ and share profitable among eligible runs (A+B)
    el = [x for x in allx if x["metrics"]["trades"] >= FL]
    for k in search.SPACE:
        acc = defaultdict(list)
        for x in el:
            acc[x["params"][k]].append(x["metrics"]["net_usd"])
        cells = [f"{v}: n{len(a)} med{st.median(a):.0f} p{100 * sum(y > 0 for y in a) / len(a):.0f}%"
                 for v, a in acc.items()]
        print(k, " | ".join(cells))

elif what == "buckets":
    import report
    h = sys.argv[2]
    ln = next(x for x in allx if x["hash"] == h)
    res = report.sized_trades(ln["params"])
    sized = res["sized"]
    print("buckets learned", res["buckets"], "exit", ln["metrics"]["exit_pct"], "skips", ln["metrics"]["skips"])
    for key in ("atr_b", "fresh_b", "sess_b", "dir", "reason", "first", "reducing", "hour", "wd", "year"):
        acc = defaultdict(list)
        for t, *_ in sized:
            if key == "hour":
                v = t["mod"] // 60
            elif key == "wd":
                v = (report.search._W["base"].s_day[t["si"]] + 3) % 7      # 0 = Monday
            elif key == "year":
                v = report.et_str(report.search._W["base"], t["entry_i"])[:4]
            else:
                v = t[key]
            acc[v].append(t["r"])
        print(key, " | ".join(f"{v}: n{len(a)} {sum(a) / len(a):+.2f}R" for v, a in sorted(acc.items(), key=lambda z: str(z[0]))))
