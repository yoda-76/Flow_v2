"""Quick sanity print of cached features (P1A/P2 helper)."""
import sys
from collections import Counter

import data
import features as F
import indicators as ind
import timeutil as tu

tf = int(sys.argv[1]) if len(sys.argv) > 1 else 15
base = data.load_base()
vas = F.load_vas(base)
bad = sum(1 for p in vas for x in vas[p] if x is None or not (x[2] <= x[0] <= x[1]))
print("VA bad:", bad)
for si in (0, 800, len(base.s_day) - 1):
    print(tu.day_to_date(base.s_day[si]), {p: tuple(t / 10 for t in vas[p][si]) for p in vas})
f = F.load_tf(base, tf)
print("tf", tf, "bars", len(f.bars), "fresh buckets", Counter(ind.fresh_bucket(a) for a in f.age))
print("atr buckets", Counter(ind.atr_bucket(p) for p in f.atr_pct))
print("swings", {n: (sum(s[0]), sum(s[1])) for n, s in f.swings.items()})
