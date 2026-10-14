"""P2 (1): exact footprint profile vs the 1m approximation (plan §5.3). Information only."""
import json
from bisect import bisect_right
from pathlib import Path

import data
import features as F
import timeutil as tu
import vprofile as prof

DIR = Path(__file__).resolve().parents[2] / "analysis" / "data" / "gb_vp_check"

base = data.load_base()
vas = F.load_vas(base)
import sys
FILES = [Path(x) for x in sys.argv[1:]] or [DIR / f"{n}.jsonl" for n in ("20723", "20724")]
for path in FILES:
    name = path.stem
    vol = {}
    first_t = None
    with open(path, encoding="utf-8") as fh:
        rows = [json.loads(x) for x in fh]
    rows = [r for r in rows if r.get("type") != "header"]
    first_t = rows[0]["t"] // 1000
    si = bisect_right(base.s_start, bisect_right(base.utc, first_t) - 1) - 1
    open_utc = base.utc[base.s_start[si]]
    end_utc = base.utc[base.s_end[si] - 1] + 60
    used = 0
    for r in rows:
        t = r["t"] // 1000
        if not (open_utc <= t < end_utc):
            continue
        used += 1
        for x in r["rows"]:
            p = int(round(x["p"] * 10))
            vol[p] = vol.get(p, 0) + x["a"] + x["b"]
    lo, hi = min(vol), max(vol)
    exact = prof.value_area(lo, [vol.get(p, 0) for p in range(lo, hi + 1)], 70)
    approx = vas[70][si]
    ts = [r["t"] // 1000 for r in rows if open_utc <= r["t"] // 1000 < end_utc]
    i0 = bisect_right(base.utc, min(ts) - 60)          # 1m bars whose minute overlaps the footprint's span
    i1 = bisect_right(base.utc, max(ts))
    lo2, v2 = prof.build_profile(base.h, base.l, base.v, i0, i1)
    same = prof.value_area(lo2, v2, 70)
    print(f"  footprint span {tu.day_to_date(0)} +{min(ts)}..{max(ts)} = {(max(ts) - min(ts)) / 3600:.1f} h, "
          f"1m vol in span {sum(base.v[i0:i1])}")
    print("  approx over the same span POC/VAH/VAL", [x / 10 for x in same],
          "diff ticks", [a - e for a, e in zip(same, exact)])
    fp_total = sum(vol.values())
    m1_total = sum(base.v[base.s_start[si]:base.s_end[si]])
    print(f"file {name}: session {tu.day_to_date(base.s_day[si])}, rows used {used}/{len(rows)}, "
          f"vol footprint {fp_total} vs 1m {m1_total}")
    print("  exact  POC/VAH/VAL", [x / 10 for x in exact])
    print("  approx POC/VAH/VAL", [x / 10 for x in approx])
    print("  diff ticks (approx - exact)", [a - e for a, e in zip(approx, exact)])
