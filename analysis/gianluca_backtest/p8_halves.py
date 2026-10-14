"""P8: halves results (plan §7). Prints A* on H1/H2, B*, top-50 H2 medians, degradation."""
import json
import statistics as st
from pathlib import Path

import search

RUNS = Path(__file__).resolve().parents[2] / "reports" / "gianluca_backtest" / "runs"


def lines(s):
    with open(RUNS / f"{s}.jsonl", encoding="utf-8") as fh:
        return [json.loads(x) for x in fh]


def fmt(m):
    return (f"sig {m['signals']} taken {m['trades']} win {100 * m['win_rate']:.1f}% exp {m['exp_r']:+.3f}R "
            f"net ${m['net_usd']:,.0f} DD {m['mdd_pct']:.1f}% flat ${m['flat_usd']:,.0f} PF {m['pf']:.2f} "
            f"Sharpe {m['sharpe']:.2f}")


def degr(h1, h2):
    return f"{100 * (h1 - h2) / abs(h1):.0f}%" if h1 else "n/a"


print("A* on halves (buckets learned on full data):")
for ln in lines("A_on_halves"):
    print(f"  {ln['eval']}: {fmt(ln['metrics'])}")

h1 = [ln for s in ("H1_A", "H1_B", "H1_D") for ln in lines(s)]
print(f"H1 search: {len(h1)} runs ({', '.join(f'{s} {len(lines(s))}' for s in ('H1_A', 'H1_B', 'H1_D'))}), eligible "
      f"{sum(x['metrics']['trades'] >= search.FLOOR['H1'] for x in h1)}")
top = search.ranked(h1, search.FLOOR["H1"], by_result=True)[:search.H2_TOP]
h1_by_hash = {x["hash"]: x for x in top}
h2 = {x["hash"]: x for x in lines("H2_top50")}
b = top[0]
print(f"B* {b['hash']} diff from baseline: { {k: v for k, v in b['params'].items() if search.BASELINE.get(k) != v} }")
print(f"  H1: {fmt(b['metrics'])}")
print(f"  H2: {fmt(h2[b['hash']]['metrics'])}")
print(f"  degradation exp {degr(b['metrics']['exp_r'], h2[b['hash']]['metrics']['exp_r'])}, "
      f"Sharpe {degr(b['metrics']['sharpe'], h2[b['hash']]['metrics']['sharpe'])}")
pairs = [(h1_by_hash[h], h2[h]) for h in h1_by_hash if h in h2]
print(f"top {len(pairs)} distinct H1 configs on H2:")
for k in ("net_usd", "exp_r", "sharpe", "mdd_pct", "trades", "flat_usd"):
    a = st.median(p[0]["metrics"][k] for p in pairs)
    z = st.median(p[1]["metrics"][k] for p in pairs)
    print(f"  median {k}: H1 {a:,.3f}  H2 {z:,.3f}")
print(f"  profitable on H2 (Kelly net > 0): {sum(p[1]['metrics']['net_usd'] > 0 for p in pairs)}/{len(pairs)}; "
      f"flat 1-lot > 0: {sum(p[1]['metrics']['flat_usd'] > 0 for p in pairs)}/{len(pairs)}; "
      f"exp R > 0: {sum(p[1]['metrics']['exp_r'] > 0 for p in pairs)}/{len(pairs)}")
med_e1 = st.median(p[0]["metrics"]["exp_r"] for p in pairs)
med_e2 = st.median(p[1]["metrics"]["exp_r"] for p in pairs)
med_s1 = st.median(p[0]["metrics"]["sharpe"] for p in pairs)
med_s2 = st.median(p[1]["metrics"]["sharpe"] for p in pairs)
print(f"  median degradation exp {degr(med_e1, med_e2)}, Sharpe {degr(med_s1, med_s2)}")
dirs = {}
for p in pairs:
    dirs.setdefault(p[0]["params"]["direction"], []).append(p[1]["metrics"]["exp_r"])
print("  H2 exp R by direction:", {d: (len(v), round(st.median(v), 3)) for d, v in dirs.items()})
for i, (p1, p2) in enumerate(pairs[:10], 1):
    d = {k: v for k, v in p1["params"].items() if search.BASELINE.get(k) != v}
    print(f"  #{i} H1 exp {p1['metrics']['exp_r']:+.3f} net ${p1['metrics']['net_usd']:,.0f} | H2 exp "
          f"{p2['metrics']['exp_r']:+.3f} net ${p2['metrics']['net_usd']:,.0f} taken {p2['metrics']['trades']} | "
          f"tf {d.get('tf', 15)} dir {d.get('direction', 'both')} thr {d.get('thr', 't0')} tgt {d.get('target_r', 2.0)}")
