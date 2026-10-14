"""P6: Monte Carlo for A* — shuffle the trade order, re-run his sizing. Usage: p6_mc.py [n]"""
import sys
import time

import p6_pick  # noqa: F401  (computes a_star from the run files; prints nothing with argv 'mc')
import report
import sizing

n = int(sys.argv[2]) if len(sys.argv) > 2 else 10_000
cfg = p6_pick.a_star
res = report.sized_trades(cfg)
trades = [x[0] for x in res["sized"]]
sp = {k: cfg[k] for k in sizing.SIZING_BASELINE}
t0 = time.time()
neg, finals, dds = report.monte_carlo_resize(trades, sp, res["buckets"], n)
print(f"{n} shuffles in {time.time() - t0:.0f}s: % final < $100k = {neg:.1f}; final equity p5/p50/p95 {finals}; "
      f"max DD % p5/p50/p95 {dds}")
