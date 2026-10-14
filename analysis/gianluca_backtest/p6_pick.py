"""P6: pick A* over A+B+C+D, top 20, A* by year, chunk test, Monte Carlo, 10 trades. Usage: p6_pick.py [top|astar|trades]"""
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

import report
import search

RUNS = Path(__file__).resolve().parents[2] / "reports" / "gianluca_backtest" / "runs"
FL = search.FLOOR["full"]


def lines(*stages):
    out = []
    for s in stages:
        with open(RUNS / f"{s}.jsonl", encoding="utf-8") as fh:
            out += [json.loads(x) for x in fh]
    return [x for x in out if x["eval"] == "full"]


L = lines("A", "B", "C", "D")
what = sys.argv[1] if len(sys.argv) > 1 else "top"
top = search.ranked(L, FL)
a_star = search.with_base(**top[0]["params"])

if what == "top":
    print(f"configs tested (full data): {len(L)}, distinct {len({x['hash'] for x in L})}, eligible "
          f"{sum(x['metrics']['trades'] >= FL for x in L)}")
    print("rank hash             taken  net$     sharpe mdd%  expR   win%  flat1c$  | params != baseline")
    for i, (h, n, net, sh, dd, er, wr, diff) in enumerate(report.top_table(L, FL, 20), 1):
        ln = next(x for x in L if x["hash"] == h)
        print(f"{i:2d} {h} {n:5d} {net:8d} {sh:5.2f} {dd:5.1f} {er:6.3f} {wr:5.1f} {ln['metrics']['flat_usd']:8.0f} | {diff}")

elif what == "astar":
    path, res = report.save_trades_csv(a_star)
    m, sized = res["metrics"], res["sized"]
    print("A*", search.cfg_hash(a_star), "csv", path.name)
    print("diff from baseline", {k: v for k, v in a_star.items() if search.BASELINE[k] != v})
    print({k: (round(v, 3) if isinstance(v, float) else v) for k, v in m.items()})
    print("buckets", res["buckets"])
    base = report.init()
    yr = defaultdict(lambda: [0, 0, 0.0, 0.0, 0.0])
    for t, score, n, pnl, eq, why in sized:
        y = report.et_str(base, t["entry_i"])[:4]
        a = yr[y]
        a[0] += 1; a[1] += n > 0; a[2] += t["r"]; a[3] += pnl; a[4] = eq
    print("year: signals taken sumR pnl$ equity_end")
    for y, a in sorted(yr.items()):
        print(f"  {y}: {a[0]:4d} {a[1]:4d} {a[2]:7.1f} {a[3]:10.0f} {a[4]:10.0f}")
    print("chunks (n, win%, sumR, pnl$):", report.chunk_test(sized))
    taken = [x for x in sized if x[2] > 0]
    print("MC 10k shuffles of taken trades' % returns: % negative final, MDD p5/p50/p95:", report.monte_carlo(taken))

elif what == "trades":
    res = report.sized_trades(a_star)
    sized = res["sized"]
    base = report.init()
    f = search._ctx(a_star["tf"]).f
    b = f.bars
    vas = search._W["vas"][a_star["va_pct"]]
    vma = f.vol_sma if a_star["vol_ma"] == "sma" else f.vol_ema
    taken = [j for j, x in enumerate(sized) if x[2] > 0]
    pick = sorted(random.Random(11).sample(taken, 10))
    for j in pick:
        t, score, n, pnl, eq, why = sized[j]
        k = t["k"]
        mh = max(base.h[t["entry_i"] + 1:t["exit_i"] + 1]) / 10
        ml = min(base.l[t["entry_i"] + 1:t["exit_i"] + 1]) / 10
        ma = search._ctx(a_star["tf"]).ma_for(1, int(a_star["ma_filter"]))[k] / 10 if a_star["ma_filter"] != "off" else None
        print(f"#{j} {'L' if t['dir'] > 0 else 'S'} first={t['first']} trig {report.et_str(base, b.i1[k] - 1)} "
              f"({t['sess_b']}, atr {f.atr_pct[k]:.0f} {t['atr_b']}, age {f.age[k]} {t['fresh_b']}) touch "
              f"{report.et_str(base, b.i1[t['touch']] - 1)} prevVA {[x / 10 for x in vas[t['si'] - 1]]} "
              f"MSlow {t['ms_low'] / 10} MShigh {t['ms_high'] / 10} SMA200 {ma}")
        print(f"   bar {b.o[k] / 10} {b.h[k] / 10} {b.l[k] / 10} {b.c[k] / 10} v{b.v[k]} | prev H/L {b.h[k - 1] / 10}/"
              f"{b.l[k - 1] / 10} v{b.v[k - 1]} volMA {vma[k - 1]:.0f} | thr {t['thr']:.1f} ATR {f.atr[k]:.1f}t")
        print(f"   entry {t['entry'] / 10} stop {t['stop'] / 10} tgt {t['target'] / 10} -> {t['reason']} {t['exit'] / 10}"
              f" at {report.et_str(base, t['exit_i'])} | path H {mh} L {ml} | R {t['r']:.2f} score {score} "
              f"n {n} pnl {pnl:.0f}")
