"""P2 (2)-(4): baseline metrics, 10 baseline trades with every input, timing. Usage: p2_baseline.py [metrics|trades|time]"""
import random
import sys
import time

import report
import search

what = sys.argv[1] if len(sys.argv) > 1 else "metrics"
base = report.init()
cfg = dict(search.BASELINE)

if what == "metrics":
    res = report.sized_trades(cfg)
    m = res["metrics"]
    print({k: (round(v, 3) if isinstance(v, float) else v) for k, v in m.items()})
    print("buckets", res["buckets"])

elif what == "trades":
    f = search._ctx(cfg["tf"]).f
    b = f.bars
    vas = search._W["vas"][cfg["va_pct"]]
    sized = report.sized_trades(cfg)["sized"]
    rnd = random.Random(3)
    pick = sorted(rnd.sample(range(len(sized)), 10))
    for j in pick:
        t, score, n, pnl, eq, why = sized[j]
        k, sg = t["k"], t["dir"]
        prev = vas[t["si"] - 1]
        mh = max(base.h[t["entry_i"] + 1:t["exit_i"] + 1]) / 10
        ml = min(base.l[t["entry_i"] + 1:t["exit_i"] + 1]) / 10
        print(f"#{j} {'L' if sg > 0 else 'S'} trig {report.et_str(base, b.i1[k] - 1)} touch "
              f"{report.et_str(base, b.i1[t['touch']] - 1)} prevVA poc/vah/val {[x / 10 for x in prev]} "
              f"side-VAH {t['vah'] / 10} MSlow {t['ms_low'] / 10} MShigh {t['ms_high'] / 10}")
        print(f"   bar OHLCV {b.o[k] / 10} {b.h[k] / 10} {b.l[k] / 10} {b.c[k] / 10} {b.v[k]} | prev bar H/L "
              f"{b.h[k - 1] / 10}/{b.l[k - 1] / 10} V {b.v[k - 1]} volMA[prev] {f.vol_sma[k - 1]:.0f} thr {t['thr']}")
        print(f"   entry {t['entry'] / 10} stop {t['stop'] / 10} tgt {t['target'] / 10} -> {t['reason']} "
              f"{t['exit'] / 10} at {report.et_str(base, t['exit_i'])} | path H {mh} L {ml} | R {t['r']:.2f} "
              f"score {score} n {n} why {why}")

elif what == "time":
    rnd = random.Random(5)
    grid = search.stage_a()
    cfgs = [rnd.choice(grid) for _ in range(20)]
    for tf in (15, 5, 10, 30):
        search._ctx(tf)                      # load caches first; time only the runs
    by_tf = {}
    for c in cfgs:
        t0 = time.time()
        search.evaluate(c)
        by_tf.setdefault(c["tf"], []).append(time.time() - t0)
    print({tf: (len(v), round(sum(v) / len(v), 2)) for tf, v in sorted(by_tf.items())})
