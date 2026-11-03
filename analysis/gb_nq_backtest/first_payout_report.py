"""Summarise reports/first_payout (first_payout.py outputs): base / stress cases and the distribution over noise runs.

    python analysis/gb_nq_backtest/first_payout_report.py [--dir reports/first_payout]   -> DIR/R2_summary.md
"""
import argparse, csv, glob, math, os, statistics


def pct(v, q):
    v = sorted(v)
    if not v:
        return float("nan")
    k = (len(v) - 1) * q
    f = math.floor(k)
    return v[f] if f == k else v[f] + (v[f + 1] - v[f]) * (k - f)


def case_stats(path):
    rows = list(csv.DictReader(open(os.path.join(path, "journeys.csv"))))
    n = len(rows)
    done = [r for r in rows if r["result"] == "PAYOUT"]
    cost = [float(r["cost"]) for r in done]
    cd = [int(r["calendar_days"]) for r in done]
    op = n - len(done)
    chain = ""
    for line in open(os.path.join(path, "summary.txt")):
        if line.startswith("chain"):
            chain = line.split(None, 1)[1].strip()
    return dict(n=n, open=op, cost_mean=statistics.mean(cost), cost_med=statistics.median(cost), cost_p90=pct(cost, .9),
                cost_max=max(cost), cd_med=statistics.median(cd), cd_p90=pct(cd, .9), cd_max=max(cd),
                le6=100 * sum(x <= 6 * 30.4 for x in cd) / n, le12=100 * sum(x <= 12 * 30.4 for x in cd) / n,
                c300=100 * sum(c <= 300.6 for c in cost) / n, c900=100 * sum(c > 900 for c in cost) / n,
                resets=statistics.mean(int(r["resets"]) for r in done), fbr=statistics.mean(int(r["funded_breaches"]) for r in done),
                chain=chain, rows=rows)


def by_year(rows):
    out = {}
    for r in rows:
        y = r["start"][:4]
        out.setdefault(y, []).append(r)
    res = {}
    for y, rs in sorted(out.items()):
        d = [r for r in rs if r["result"] == "PAYOUT"]
        res[y] = (len(rs), statistics.median(float(r["cost"]) for r in d) if d else float("nan"),
                  statistics.median(int(r["calendar_days"]) for r in d) if d else float("nan"), len(rs) - len(d))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="reports/first_payout")
    a = ap.parse_args()
    L = ["# First $1,000 payout: S1 + S3, 1 MNQ each, LucidPro 25K, one account at a time",
         "",
         "Journey: eval $75.60 -> eval fail: reset $75 -> pass: funded -> funded breach before the payout: new eval $75.60 -> first "
         "payout ($1,000 requested, $900 received). DLL $600 on. One journey per start day (every session day with >= 365 days of "
         "data after it). Cost = fees only; net = $900 - cost.",
         "",
         "| case | journeys | open at end | cost mean | cost median | cost p90 | cost max | fees <= $300 | fees > $900 | "
         "days median | days p90 | days max | <= 6 months | <= 12 months | resets / journey | funded breaches / journey |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    base = None
    for c, label in (("base", "base"), ("hc25", "profit cut 25%"), ("hc50", "profit cut 50%"), ("top10", "top 10 trades of each removed")):
        p = os.path.join(a.dir, c)
        if not os.path.exists(os.path.join(p, "journeys.csv")):
            continue
        s = case_stats(p)
        if c == "base":
            base = s
        L.append(f"| {label} | {s['n']} | {s['open']} | {s['cost_mean']:.0f} | {s['cost_med']:.0f} | {s['cost_p90']:.0f} | "
                 f"{s['cost_max']:.0f} | {s['c300']:.1f}% | {s['c900']:.1f}% | {s['cd_med']:.0f} | {s['cd_p90']:.0f} | {s['cd_max']} | "
                 f"{s['le6']:.1f}% | {s['le12']:.1f}% | {s['resets']:.2f} | {s['fbr']:.2f} |")
    ns = [case_stats(p) for p in sorted(glob.glob(os.path.join(a.dir, "noise", "seed_*"))) if os.path.exists(os.path.join(p, "journeys.csv"))]
    if ns:
        f = lambda k, q: pct([s[k] for s in ns], q)
        for q, lab in ((0.5, "median"), (0.05, "best 5%"), (0.95, "worst 5%")):
            qq = q if lab != "best 5%" else 0.05
            L.append(f"| noise ({len(ns)} runs): {lab} | | {f('open', .5):.0f} | {f('cost_mean', qq):.0f} | {f('cost_med', qq):.0f} | "
                     f"{f('cost_p90', qq):.0f} | {f('cost_max', qq):.0f} | {f('c300', 1 - qq):.1f}% | {f('c900', qq):.1f}% | "
                     f"{f('cd_med', qq):.0f} | {f('cd_p90', qq):.0f} | {f('cd_max', qq):.0f} | {f('le6', 1 - qq):.1f}% | "
                     f"{f('le12', 1 - qq):.1f}% | {f('resets', qq):.2f} | {f('fbr', qq):.2f} |")
    L.append("")
    L.append("Noise rows: the given percentile of each column across runs, taken separately per column (worst 5% = high "
             "costs / long times / low shares). Days = calendar days from buying the first eval to the payout day.")
    L.append("")
    if base:
        L.append(f"Independent (back-to-back) journeys, base: {base['chain']}")
        for c in ("hc25", "hc50", "top10"):
            p = os.path.join(a.dir, c)
            if os.path.exists(os.path.join(p, "summary.txt")):
                L.append(f"- {c}: {case_stats(p)['chain']}")
        L.append("")
        L.append("By start year (base): starts / median fees / median calendar days / open")
        L.append("")
        L.append("| year | starts | median fees | median days | open |")
        L.append("|---|---|---|---|---|")
        for y, (n, c, d, o) in by_year(base["rows"]).items():
            L.append(f"| {y} | {n} | {c:.0f} | {d:.0f} | {o} |")
        if ns:
            yd = {}
            for s in ns:
                for y, (n, c, d, o) in by_year(s["rows"]).items():
                    yd.setdefault(y, []).append(d)
            L.append("")
            L.append("Median calendar days by start year under noise (median / worst run): " +
                     "; ".join(f"{y} {statistics.median(v):.0f} / {max(v):.0f}" for y, v in sorted(yd.items())))
    with open(os.path.join(a.dir, "R2_summary.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    main()
