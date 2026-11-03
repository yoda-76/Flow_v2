"""Robustness round R-1 for S1 (Vault Break, base 2C) and S3 (Overnight-bias ORB, defaults) -- offline analysis only.

Read-only over analysis/data and earlier reports; writes only under --out (default reports/robust_r1). Places no orders.
Tests follow docs/video_transcripts/strategies/raw/how_to_backtest.txt plus prop-level robustness of the
"S1 + S3, 1 MNQ each, LucidPro 25K: 73.1% pass, median 35 trading days" claim (vault_break.md B-11).

    python analysis/gb_nq_backtest/robustness.py [--steps nulls,noise,shift,prop,stats,mes] [--workers 10]
        [--null-n 10000] [--noise-n 1000] [--prop-n 200]

Steps
  nulls  random_entry.py, 10,000 shuffles each: S1 random side / random days / random later entry; S3 the same three.
         Inputs = the 1 NQ, $4.60 RT trade lists used in B-12 / B-08.
  noise  vault_break.py / overnight_orb.py re-run per seed with --entry-delay 1 --noise-slip 2 (1 MNQ, $1 RT).
  shift  same scripts with --entry-shift K, K = -5..5 (1m bars).
  prop   prop_multi.py 25K, 1 MNQ each, set s1+s3, with and without --dll 600, on: base, noise pairs (seed i of S1 with
         seed i of S3), profit haircut 25% / 50% (a fixed $ cost per trade = that share of the strategy's mean trade;
         realized P&L only, the intraday open-P&L path is unchanged -- an approximation), top-10 trades of each
         strategy removed, shift K pairs.
  stats  power curve, outlier dependence, multiple-testing correction, Wilson CI of chained attempts, pass % by year.
  mes    S1 + S3 unchanged on MES (S1 TP/SL scaled to 11 / 20 points as in B-12), prop at 1 MES each, plus NQ over
         the same window for comparison.
"""
import argparse, csv, math, os, re, statistics, subprocess, sys, time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable
NQ = "analysis/data/NQ_1m_latest.csv"
MES = "analysis/data/MES_1m_1793460005535.csv"      # MES_1m_latest.csv was overwritten by a short file on 2026-11-02
S1_ARGS = ["--open-ref", "globex1700", "--qty", "1"]                       # MNQ, mult 2, $1 RT (script defaults)
S3_ARGS = ["--qty", "1", "--mult", "2", "--comm", "1", "--exit-res", "1m"]
S1_BASE = "reports/prop3/q1/s1/trades.csv"
S3_BASE = "reports/prop3/q1/s3/trades.csv"
S1_NQ = "reports/s1_sweep/2C_open17_00/trades.csv"                         # 1 NQ, $4.60 RT (B-12)
S3_NQ = "reports/orb/base/trades.csv"                                      # 1 NQ, $4.60 RT (B-08)
SHIFTS = [-5, -4, -3, -2, -1, 1, 2, 3, 4, 5]


def run(cmd, log):
    t = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True)
    with open(log, "a") as fh:
        fh.write(f"[{time.strftime('%H:%M:%S')}] rc={r.returncode} {time.time() - t:.0f}s {' '.join(cmd)}\n")
        if r.returncode:
            fh.write(r.stderr[-2000:] + "\n")
    return r


def pool(jobs, workers, log):
    with ThreadPoolExecutor(workers) as ex:
        res = list(ex.map(lambda c: run(c, log), jobs))
    bad = sum(r.returncode != 0 for r in res)
    if bad:
        print(f"  {bad} of {len(jobs)} jobs failed (see {log})")
    return res


def pnls(path, start=None, end=None):
    out = []
    with open(path, newline="") as fh:
        for r in csv.DictReader(fh):
            out.append(float(r["pnl_usd"]))
    return out


def tstat(v):
    n = len(v)
    if n < 3:
        return 0.0
    sd = statistics.pstdev(v)
    return statistics.mean(v) / (sd / math.sqrt(n)) if sd > 0 else 0.0


def p_two(t):
    return math.erfc(abs(t) / math.sqrt(2))


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    den = 1 + z * z / n
    c = (p + z * z / (2 * n)) / den
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den
    return (c - h, c + h)


def pctl(v, q):
    v = sorted(v)
    if not v:
        return float("nan")
    k = (len(v) - 1) * q
    f = math.floor(k)
    return v[f] if f == k else v[f] + (v[f + 1] - v[f]) * (k - f)


# ------------------------------------------------------------------------------------------------ nulls
def step_nulls(a, log):
    d = os.path.join(a.out, "nulls")
    jobs, names = [], []
    spec = [
        ("s1_side", S1_NQ, ["--window-start", "600", "--window-end", "840", "--grid", "30", "--time-exit", "870", "--mode", "side"]),
        ("s1_alldays", S1_NQ, ["--window-start", "600", "--window-end", "840", "--grid", "30", "--time-exit", "870", "--mode", "time", "--date-pool", "all"]),
        ("s1_after", S1_NQ, ["--window-start", "600", "--window-end", "840", "--grid", "30", "--time-exit", "870", "--mode", "time", "--after-actual"]),
        ("s3_side", S3_NQ, ["--window-start", "540", "--window-end", "855", "--grid", "15", "--time-exit", "870", "--mode", "side"]),
        ("s3_alldays", S3_NQ, ["--window-start", "540", "--window-end", "855", "--grid", "15", "--time-exit", "870", "--mode", "both", "--date-pool", "all"]),
        ("s3_after", S3_NQ, ["--window-start", "540", "--window-end", "855", "--grid", "15", "--time-exit", "870", "--mode", "time", "--after-actual"]),
    ]
    for name, tr, extra in spec:
        names.append(name)
        jobs.append([PY, os.path.join(HERE, "random_entry.py"), "--trades", tr, "--n", str(a.null_n), "--seed", "11",
                     "--out", os.path.join(d, name)] + extra)
    pool(jobs, a.workers, log)


# ------------------------------------------------------------------------------------------------ noise / shift
def strat_cmd(s, out, extra, csv_path=NQ, base_extra=None):
    script = "vault_break.py" if s == "s1" else "overnight_orb.py"
    base = base_extra if base_extra is not None else (S1_ARGS if s == "s1" else S3_ARGS)
    return [PY, os.path.join(HERE, script), "--csv", csv_path, "--out", out] + base + extra


def step_noise(a, log):
    jobs = []                                    # one process per chunk of seeds: data loaded once (8 GB machine)
    chunk = max(1, (a.noise_n + 3) // 4)
    for s in ("s1", "s3"):
        for lo in range(1, a.noise_n + 1, chunk):
            hi = min(a.noise_n, lo + chunk - 1)
            jobs.append(strat_cmd(s, os.path.join(a.out, "noise", s),
                                  ["--entry-delay", "1", "--noise-slip", "2", "--seeds", f"{lo}-{hi}"]))
    print(f"  noise: {len(jobs)} processes, {a.noise_n} seeds per strategy")
    pool(jobs, a.workers, log)


def step_shift(a, log):
    jobs = []
    for s in ("s1", "s3"):
        for k in SHIFTS:
            jobs.append(strat_cmd(s, os.path.join(a.out, "shift", s, f"k{k:+d}"), ["--entry-shift", str(k)]))
    pool(jobs, a.workers, log)


# ------------------------------------------------------------------------------------------------ prop
ROW = re.compile(r"^s1\+s3\s+(\d+)\s+([-\d,]+)\s+([-\d,]+)\s+([-\d,]+)\s+(\d+)\S?\s*\|\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*\|\s*(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\S+)\s+(\S+)\s+(\S+)")


def parse_prop(out):
    p = os.path.join(out, "summary.txt")
    if not os.path.exists(p):
        return None
    for line in open(p):
        m = ROW.match(line)
        if m:
            g = m.groups()
            f = lambda x: float(x.replace(",", "")) if x not in ("-",) else float("nan")
            return dict(trades=int(g[0]), net=f(g[1]), maxdd=f(g[2]), worstday=f(g[3]), ch_pass=int(g[5]),
                        ch_fail=int(g[6]), roll_n=int(g[9]), pass_pct=float(g[11]), fail_pct=float(g[12]),
                        med_days=f(g[14]), mean_days=f(g[15]), med_fail=f(g[16]))
    return None


def prop_cmd(s1, s3, out, dll, extra=None):
    c = [PY, os.path.join(HERE, "prop_multi.py"), "--trades", f"s1={s1}", "--trades", f"s3={s3}", "--qty", "1",
         "--sets", "s1+s3", "--plans", "25k", "--rolling-csv", "--out", out]
    if dll:
        c += ["--dll", str(dll)]
    return c + (extra or [])


def write_variant(src, dst, fn):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(src, newline="") as fh:
        rd = csv.DictReader(fh)
        rows = list(rd)
        cols = rd.fieldnames
    rows = fn(rows)
    with open(dst, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)


def haircut(h):
    def fn(rows):
        mean = statistics.mean(float(r["pnl_usd"]) for r in rows)
        for r in rows:
            r["pnl_usd"] = f"{float(r['pnl_usd']) - h * mean:.4f}"
        return rows
    return fn


def drop_top(k):
    def fn(rows):
        top = set(sorted(range(len(rows)), key=lambda i: -float(rows[i]["pnl_usd"]))[:k])
        return [r for i, r in enumerate(rows) if i not in top]
    return fn


def step_prop(a, log):
    P = os.path.join(a.out, "prop")
    var = os.path.join(a.out, "variants")
    for h in (25, 50):
        write_variant(S1_BASE, os.path.join(var, f"hc{h}", "s1.csv"), haircut(h / 100))
        write_variant(S3_BASE, os.path.join(var, f"hc{h}", "s3.csv"), haircut(h / 100))
    write_variant(S1_BASE, os.path.join(var, "top10", "s1.csv"), drop_top(10))
    write_variant(S3_BASE, os.path.join(var, "top10", "s3.csv"), drop_top(10))
    jobs = []
    for dll, tag in ((0, "nodll"), (600, "dll600")):
        jobs.append(prop_cmd(S1_BASE, S3_BASE, os.path.join(P, tag, "base"), dll))
        for v in ("hc25", "hc50", "top10"):
            jobs.append(prop_cmd(os.path.join(var, v, "s1.csv"), os.path.join(var, v, "s3.csv"), os.path.join(P, tag, v), dll))
        for k in SHIFTS:
            s1 = os.path.join(a.out, "shift", "s1", f"k{k:+d}", "trades.csv")
            s3 = os.path.join(a.out, "shift", "s3", f"k{k:+d}", "trades.csv")
            if os.path.exists(s1) and os.path.exists(s3):
                jobs.append(prop_cmd(s1, s3, os.path.join(P, tag, f"shift{k:+d}"), dll))
        for i in range(1, a.prop_n + 1):
            s1 = os.path.join(a.out, "noise", "s1", f"seed_{i:04d}", "trades.csv")
            s3 = os.path.join(a.out, "noise", "s3", f"seed_{i:04d}", "trades.csv")
            if os.path.exists(s1) and os.path.exists(s3):
                jobs.append(prop_cmd(s1, s3, os.path.join(P, tag, "noise", f"seed_{i:04d}"), dll))
    print(f"  prop: {len(jobs)} runs")
    pool(jobs, a.workers, log)


# ------------------------------------------------------------------------------------------------ mes
def step_mes(a, log):
    d = os.path.join(a.out, "mes")
    frm = ["--from", "2021-10-01"]
    jobs = [
        strat_cmd("s1", os.path.join(d, "s1"), frm, MES, ["--open-ref", "globex1700", "--qty", "1", "--mult", "5",
                                                          "--tp-pts", "11", "--sl-pts", "20"]),
        strat_cmd("s3", os.path.join(d, "s3"), frm, MES, ["--qty", "1", "--mult", "5", "--comm", "1", "--exit-res", "1m"]),
    ]
    pool(jobs, a.workers, log)
    pj = [prop_cmd(os.path.join(d, "s1", "trades.csv"), os.path.join(d, "s3", "trades.csv"), os.path.join(d, "prop_mes"), 0,
                   ["--csv", MES, "--mult", "5"]),
          prop_cmd(S1_BASE, S3_BASE, os.path.join(d, "prop_nq_same_window"), 0, ["--from", "2021-10-01"])]
    pool(pj, a.workers, log)


# ------------------------------------------------------------------------------------------------ stats / report
def null_summary(path):
    txt = open(path).read()
    act = re.search(r"ACTUAL \(file pnl\)\s+n\s+(\d+)\s+mean\s+([-\d.]+)", txt)
    mp = re.search(r"mean pnl/trade : mean ([-\d.]+)\s+sd ([-\d.]+).*?max ([-\d.]+)", txt)
    pv = re.search(r"p-value.*?: (\d+)/(\d+)", txt)
    sk = re.search(r"skipped synthetic trades.*?\(([\d.]+) per shuffle\)", txt)
    return dict(actual=float(act.group(2)), null_mean=float(mp.group(1)), null_sd=float(mp.group(2)),
                null_max=float(mp.group(3)), k=int(pv.group(1)), n=int(pv.group(2)), skip=sk.group(1) if sk else "?")


def kurt(v):
    m = statistics.mean(v)
    s2 = statistics.pvariance(v)
    return sum((x - m) ** 4 for x in v) / len(v) / (s2 * s2) - 3.0 if s2 > 0 else 0.0


def by_year(rolling_csv):
    yr = defaultdict(lambda: [0, 0])
    for r in csv.DictReader(open(rolling_csv)):
        if r["result"] not in ("PASS", "FAIL"):
            continue
        y = r["start_date"][:4]
        yr[y][0] += r["result"] == "PASS"
        yr[y][1] += 1
    return dict(sorted(yr.items()))


def step_stats(a, log, L):
    L.append("# Robustness round R-1: S1 + S3 (generated by analysis/gb_nq_backtest/robustness.py)\n")
    L.append(f"Generated {time.strftime('%Y-%m-%d %H:%M')}. All $ figures per the stated contract. Places no orders.\n")

    # 1. nulls
    L.append("## 1. Permutation nulls (video test 1); 1 NQ, $4.60 RT, same exits\n")
    L.append("| test | actual $/trade | null mean | null sd | null max | z | shuffles >= actual | p (empirical) | p (normal fit) |")
    L.append("|---|---|---|---|---|---|---|---|---|")
    nulls = {}
    for name in ("s1_side", "s1_alldays", "s1_after", "s3_side", "s3_alldays", "s3_after"):
        p = os.path.join(a.out, "nulls", name, "summary.txt")
        if not os.path.exists(p):
            L.append(f"| {name} | missing | | | | | | | |")
            continue
        s = null_summary(p)
        z = (s["actual"] - s["null_mean"]) / s["null_sd"] if s["null_sd"] > 0 else float("inf")
        pe = (s["k"] + 1) / (s["n"] + 1)
        pn = 0.5 * math.erfc(z / math.sqrt(2))
        nulls[name] = (pe, pn)
        L.append(f"| {name} | {s['actual']:.1f} | {s['null_mean']:.1f} | {s['null_sd']:.1f} | {s['null_max']:.1f} | {z:.1f} | "
                 f"{s['k']}/{s['n']} | {pe:.5f} | {pn:.2e} |")
    L.append("\nside = same entry minute, coin picks the direction; alldays = random entry minute on random days "
             "(S3: random side too); after = random minute after the real entry, same day.\n")

    # 2. multiple testing
    L.append("## 2. Counting the tries (video test 2)\n")
    tries = dict(s1=34 + 45, s3=27 + 25)
    L.append("Tries counted from the research logs: S1 = 34 variants (B-05 sweep) + 45 grid points (B-12) = 79; "
             "S3 = 27 variants (B-02) + 25 grid points (B-08) = 52; together 131 (S2's sweeps are a different rule set and "
             "are listed as a sensitivity row).\n")
    L.append("| strategy | trades | mean $ | t | p (t-test) | Bonferroni x tries | worst of side/alldays null p (empirical) x tries |")
    L.append("|---|---|---|---|---|---|---|")
    for s, path in (("s1", S1_NQ), ("s3", S3_NQ)):
        v = pnls(path)
        t = tstat(v)
        pt = p_two(t)
        worst_null = max([nulls[k][0] for k in (s + "_side", s + "_alldays") if k in nulls] or [float("nan")])
        L.append(f"| {s.upper()} | {len(v)} | {statistics.mean(v):.1f} | {t:.2f} | {pt:.2e} | {min(1, pt * tries[s]):.2e} | "
                 f"{min(1, worst_null * tries[s]):.4f} |")
    L.append(f"\nSensitivity, every variant tried in the project on these three video strategies (S1 79 + S2 62 [6 first variants + 56 sweep] "
             f"+ S3 52 = 193): multiply the p-values by 193 instead.\n")

    # 3. power
    L.append("## 3. Statistical power (video test 3): t-stat after the first N trades (chronological)\n")
    L.append("| N | S1 t | S1 p | S3 t | S3 p |")
    L.append("|---|---|---|---|---|")
    v1, v3 = pnls(S1_NQ), pnls(S3_NQ)
    for N in (50, 100, 200, 300, 400, 500, 693, 1000, 1517):
        c1 = f"{tstat(v1[:N]):.2f} | {p_two(tstat(v1[:N])):.3f}" if N <= len(v1) else "- | -"
        c3 = f"{tstat(v3[:N]):.2f} | {p_two(tstat(v3[:N])):.3f}" if N <= len(v3) else "- | -"
        L.append(f"| {N} | {c1} | {c3} |")
    for s, v in (("S1", v1), ("S3", v3)):
        stay = None
        for N in range(30, len(v) + 1):
            if p_two(tstat(v[:N])) < 0.05:
                if stay is None:
                    stay = N
            else:
                stay = None
        mde = 2.8 * statistics.pstdev(v) / math.sqrt(len(v))
        L.append(f"\n{s}: p < 0.05 from trade {stay} onward and never lost after that; smallest edge detectable at 80% power "
                 f"with all {len(v)} trades = ${mde:.0f}/trade (actual ${statistics.mean(v):.0f}).")
    L.append("")

    # 4. noise
    L.append("## 4. Noise injection (video test 4); 1 MNQ, $1 RT; entry 0-1 min late + 0-2 extra ticks on every fill\n")
    base = dict(s1=sum(pnls(S1_BASE)), s3=sum(pnls(S3_BASE)))
    L.append("| strategy | runs | base net | median net | 5th pct | worst run | runs > 0 | median net / base |")
    L.append("|---|---|---|---|---|---|---|---|")
    for s in ("s1", "s3"):
        nets = []
        for i in range(1, a.noise_n + 1):
            p = os.path.join(a.out, "noise", s, f"seed_{i:04d}", "trades.csv")
            if os.path.exists(p):
                nets.append(sum(pnls(p)))
        if nets:
            L.append(f"| {s.upper()} | {len(nets)} | {base[s]:,.0f} | {statistics.median(nets):,.0f} | {pctl(nets, 0.05):,.0f} | "
                     f"{min(nets):,.0f} | {sum(n > 0 for n in nets)}/{len(nets)} | {statistics.median(nets) / base[s]:.2f} |")
    L.append("")

    # 5. shift
    L.append("## 5. Latency shift (video test 5): entry moved K one-minute bars; 1 MNQ, $1 RT; net $\n")
    L.append("| K | S1 net | S1 trades | S3 net | S3 trades |")
    L.append("|---|---|---|---|---|")
    for k in [-5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5]:
        cells = []
        for s in ("s1", "s3"):
            p = (S1_BASE if s == "s1" else S3_BASE) if k == 0 else os.path.join(a.out, "shift", s, f"k{k:+d}", "trades.csv")
            if os.path.exists(p):
                v = pnls(p)
                cells += [f"{sum(v):,.0f}", str(len(v))]
            else:
                cells += ["-", "-"]
        L.append(f"| {k:+d} | " + " | ".join(cells) + " |")
    L.append("\nNegative K enters before the signal bar has closed (uses information not yet known) -- shown only for "
             "symmetry with the video.\n")

    # 6. outliers
    L.append("## 6. Outlier dependence (video test 6); 1 NQ, $4.60 RT\n")
    L.append("| strategy | net | excess kurtosis | drop top 10: net kept | drop top 1% | drop top 5% |")
    L.append("|---|---|---|---|---|---|")
    for s, v in (("S1", v1), ("S3", v3)):
        sv = sorted(v, reverse=True)
        tot = sum(v)
        keep = lambda k: f"{100 * sum(sv[k:]) / tot:.0f}%"
        L.append(f"| {s} | {tot:,.0f} | {kurt(v):.2f} | {keep(10)} | {keep(max(1, round(len(v) * 0.01)))} | "
                 f"{keep(max(1, round(len(v) * 0.05)))} |")
    L.append("")

    # 7. prop
    L.append("## 7. The prop claim: S1 + S3, 1 MNQ each, LucidPro 25K (rolling start every session day)\n")
    for tag, title in (("nodll", "no daily loss limit"), ("dll600", "Lucid's optional $600 daily loss limit ON")):
        P = os.path.join(a.out, "prop", tag)
        L.append(f"### 7{'a' if tag == 'nodll' else 'b'}. {title}\n")
        L.append("| case | net $ | worst day | pass % | median days | chained pass/fail | chained 95% CI |")
        L.append("|---|---|---|---|---|---|---|")
        rows = [("base", "base"), ("haircut 25%", "hc25"), ("haircut 50%", "hc50"), ("top 10 of each removed", "top10")]
        rows += [(f"shift {k:+d}", f"shift{k:+d}") for k in SHIFTS]
        for label, sub in rows:
            r = parse_prop(os.path.join(P, sub))
            if r:
                lo, hi = wilson(r["ch_pass"], r["ch_pass"] + r["ch_fail"])
                L.append(f"| {label} | {r['net']:,.0f} | {r['worstday']:,.0f} | {r['pass_pct']:.1f} | {r['med_days']:.0f} | "
                         f"{r['ch_pass']}/{r['ch_fail']} | {100 * lo:.0f}-{100 * hi:.0f}% |")
        nz = [parse_prop(os.path.join(P, "noise", f"seed_{i:04d}")) for i in range(1, a.prop_n + 1)]
        nz = [x for x in nz if x]
        if nz:
            pp = [x["pass_pct"] for x in nz]
            md = [x["med_days"] for x in nz]
            L.append(f"| noise, {len(nz)} runs: median | | | {statistics.median(pp):.1f} | {statistics.median(md):.0f} | | |")
            L.append(f"| noise: 5th pct / worst | | | {pctl(pp, 0.05):.1f} / {min(pp):.1f} | {pctl(md, 0.95):.0f} (95th) | | |")
        L.append("")
        rc = os.path.join(P, "base", "rolling_25k_s1+s3.csv")
        if os.path.exists(rc):
            L.append("Pass % by start year (base, rolling starts):\n")
            L.append("| year | starts | pass % |")
            L.append("|---|---|---|")
            for y, (k, n) in by_year(rc).items():
                L.append(f"| {y} | {n} | {100 * k / n:.1f} |")
            L.append("")
        # by year under noise: median across runs
        yrs = defaultdict(list)
        for i in range(1, a.prop_n + 1):
            p = os.path.join(P, "noise", f"seed_{i:04d}", "rolling_25k_s1+s3.csv")
            if os.path.exists(p):
                for y, (k, n) in by_year(p).items():
                    yrs[y].append(100 * k / n)
        if yrs:
            L.append("Pass % by start year under noise (median / worst across runs): " +
                     "; ".join(f"{y} {statistics.median(v):.0f} / {min(v):.0f}" for y, v in sorted(yrs.items())) + "\n")

    # 8. MES
    L.append("## 8. Untouched market: MES from 2021-10, settings unchanged (S1 TP/SL scaled 11 / 20 pts), 1 MES each\n")
    d = os.path.join(a.out, "mes")
    for s in ("s1", "s3"):
        p = os.path.join(d, s, "trades.csv")
        if os.path.exists(p):
            v = pnls(p)
            L.append(f"- {s.upper()} MES: {len(v)} trades, net ${sum(v):,.0f}, mean ${statistics.mean(v):.2f}, t {tstat(v):.2f}")
    for sub, label in (("prop_mes", "MES"), ("prop_nq_same_window", "NQ (MNQ), same window")):
        r = parse_prop(os.path.join(d, sub))
        if r:
            lo, hi = wilson(r["ch_pass"], r["ch_pass"] + r["ch_fail"])
            L.append(f"- prop 25K, {label}: pass {r['pass_pct']:.1f}%, median {r['med_days']:.0f} days, chained "
                     f"{r['ch_pass']}/{r['ch_fail']} (95% CI {100 * lo:.0f}-{100 * hi:.0f}%), net ${r['net']:,.0f}")
    L.append("")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="reports/robust_r1")
    ap.add_argument("--steps", default="nulls,noise,shift,prop,mes,stats")
    ap.add_argument("--workers", type=int, default=2)        # ~1 GB per process; 8 GB machine
    ap.add_argument("--null-n", type=int, default=10000)
    ap.add_argument("--noise-n", type=int, default=1000)
    ap.add_argument("--prop-n", type=int, default=200)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    log = os.path.join(a.out, "run.log")
    steps = [s.strip() for s in a.steps.split(",")]
    t0 = time.time()
    for s in ("nulls", "noise", "shift", "prop", "mes"):
        if s in steps:
            print(f"[{time.strftime('%H:%M:%S')}] step {s}", flush=True)
            globals()["step_" + s](a, log)
    if "stats" in steps:
        print(f"[{time.strftime('%H:%M:%S')}] step stats", flush=True)
        L = []
        step_stats(a, log, L)
        with open(os.path.join(a.out, "R1_summary.md"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(L) + "\n")
    print(f"done in {time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    main()
