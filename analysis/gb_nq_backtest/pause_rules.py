"""What predicts a bad month for the anchored-VWAP strategy on MES, and does a 'pause' rule help?

Part 1: causal pause rules driven by the strategy's own shadow results (the hypothetical trades keep being tracked while paused, so a pause can end).
Part 2: market-state measures of the PREVIOUS month vs this month's P&L (rank correlation) and a concurrent look at what a bad month looks like.
Setting: MES 3m, EMA 11, rel ATR 1.1, no stop, 3 contracts, 2022 -> 2026 YTD. Read-only.

    python analysis/gb_nq_backtest/pause_rules.py
"""
import csv, datetime as d, math, os, pickle, sys, itertools
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from optimize_wf import spearman

KEY = (3, 11, 1.1, None)
DAY = 86_400_000
ym = lambda t: (d.datetime.fromtimestamp(t / 1000 - 5 * 3600, d.timezone.utc)).strftime("%Y-%m")

trades = sorted(pickle.load(open("reports/walk_forward_trades.pkl", "rb"))[KEY])


def stats(pnl):
    if not pnl:
        return 0, 0.0, 0.0, 0.0
    gp, gl = sum(p for p in pnl if p > 0), -sum(p for p in pnl if p <= 0)
    eq = pk = dd = 0.0
    for p in pnl:
        eq += p; pk = max(pk, eq); dd = max(dd, pk - eq)
    return len(pnl), sum(pnl), (gp / gl if gl else 9.99), dd


def apply(rule):
    """rule(state) -> pause_until_ms or None, evaluated after every shadow trade closes (approximated at its entry time order)."""
    taken, paused_until, hist = [], 0, []
    cur_m, mtd = None, 0.0
    for t, p in trades:
        m = ym(t)
        if m != cur_m:
            cur_m, mtd, stopped = m, 0.0, False
        active = t >= paused_until and not (rule.get("mstop") and mtd <= -rule["mstop"])
        if active:
            taken.append((t, p))
        hist.append(p); mtd += p
        r = rule
        if "k" in r and len(hist) >= r["k"] and all(x <= 0 for x in hist[-r["k"]:]):
            paused_until = max(paused_until, t + r["days"] * DAY)
        if "last" in r and len(hist) >= r["last"] and sum(hist[-r["last"]:]) <= -r["thr"]:
            paused_until = max(paused_until, t + r["days"] * DAY)
        if "dd" in r:
            eq = pk = 0.0
            for x in hist:
                eq += x; pk = max(pk, eq)
            if pk - eq >= r["dd"]:
                paused_until = max(paused_until, t + r["days"] * DAY)
    return taken


def show(name, taken):
    row = []
    for lo, hi in (("2022-01", "2026-12"), ("2023-01", "2026-12"), ("2022-01", "2022-12")):
        n, net, pf, dd = stats([p for t, p in taken if lo <= ym(t) <= hi])
        row.append(f"{net:+8,.0f} PF {pf:4.2f} DD {dd:6,.0f} n{n:4d}")
    print(f"{name:<34} | {row[0]} | {row[1]} | {row[2]}")


print("PART 1 - pause rules (columns: 2022->now | 2023->now | 2022 only)")
show("no pause (baseline)", trades)
for k, days in itertools.product((2, 3, 4), (7, 14, 30)):
    show(f"{k} losses in a row -> pause {days}d", apply(dict(k=k, days=days)))
for last, thr, days in itertools.product((5, 8, 10), (500, 1000, 1500), (14, 30)):
    show(f"last {last} trades <= -${thr} -> {days}d", apply(dict(last=last, thr=thr, days=days)))
for dd, days in itertools.product((1000, 1500, 2000, 3000), (14, 30)):
    show(f"shadow DD >= ${dd} -> pause {days}d", apply(dict(dd=dd, days=days)))
for ms in (500, 750, 1000, 1500):
    show(f"month-to-date <= -${ms} -> stop month", apply(dict(mstop=ms)))

# ---- Part 2 ----
rows = list(csv.reader(open("analysis/data/MES_1m_latest.csv")))[1:]
hour = {}
for r in rows:
    t = int(r[0]); hour[t // 3_600_000] = float(r[4])          # last close of each UTC hour
hs = sorted(hour)
per = {}
prev = None
for h in hs:
    if prev is not None and h - prev[0] <= 3:
        r = hour[h] - prev[1]
        if abs(r) < 40:                                          # drops the 50+ pt quarterly roll gaps
            per.setdefault(ym(h * 3_600_000), []).append(r)
    prev = (h, hour[h])
feat = {}
for m, rs in per.items():
    sa = sum(abs(x) for x in rs)
    feat[m] = dict(rv=math.sqrt(sum(x * x for x in rs)), eff=abs(sum(rs)) / sa if sa else 0, drift=sum(rs),
                   chop=sum(1 for a, b in zip(rs, rs[1:]) if a * b < 0) / max(len(rs) - 1, 1))
mp = {}
for t, p in trades:
    mp[ym(t)] = mp.get(ym(t), 0.0) + p
months = sorted(m for m in mp if m in feat)
print("\nPART 2 - previous month's market state vs this month's strategy P&L (Spearman, %d months)" % (len(months) - 1))
for f in ("rv", "eff", "chop", "drift"):
    x = [feat[a][f] for a, b in zip(months, months[1:])]
    y = [mp[b] for a, b in zip(months, months[1:])]
    xc = [feat[b][f] for a, b in zip(months, months[1:])]
    print(f"  {f:<6} prev-month rho {spearman(x, y):+.2f} | same-month rho {spearman(xc, y):+.2f}")
print("  prev-month strategy P&L vs this month: rho %+.2f" % spearman([mp[a] for a, b in zip(months, months[1:])], [mp[b] for a, b in zip(months, months[1:])]))
bad = sorted(months, key=lambda m: mp[m])[:12]
good = sorted(months, key=lambda m: -mp[m])[:12]
av = lambda ms, f: sum(feat[m][f] for m in ms) / len(ms)
print("\n  12 worst vs 12 best months: avg market state in the SAME month")
for f in ("rv", "eff", "chop", "drift"):
    print(f"   {f:<6} worst {av(bad, f):8.2f} | best {av(good, f):8.2f} | all {av(months, f):8.2f}")
print("  worst months:", ", ".join(f"{m}({mp[m]:+,.0f})" for m in bad))
