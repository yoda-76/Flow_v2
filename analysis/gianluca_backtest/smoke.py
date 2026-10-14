"""Smoke run: one config on the real data, print counts + timing. Usage: smoke.py [key=value ...]"""
import sys
import time
from collections import Counter

import data
import features as F
import signals as S

p = dict(S.BASELINE)
for kv in sys.argv[1:]:
    k, v = kv.split("=")
    p[k] = type(S.BASELINE[k])(v)
base = data.load_base()
ctx = S.Ctx(base, F.load_vas(base), F.load_tf(base, p["tf"]))
t0 = time.time()
tr = S.run(ctx, p)
dt = time.time() - t0
n = len(tr)
wins = sum(1 for x in tr if x["r"] > 0)
print(f"{dt:.2f}s trades={n} win%={100 * wins / max(n, 1):.1f} sumR={sum(x['r'] for x in tr):.1f} "
      f"usd1c={sum(x['usd'] for x in tr):.0f}")
print(Counter(x["reason"] for x in tr), Counter(x["dir"] for x in tr), Counter(x["first"] for x in tr))
