"""Lucid first-payout + break-even grid for S1 + S3 (offline). Plan: docs/dynamic/prop_grid_plan_v1.md (sections in brackets).

Read-only over trade CSVs and the 1m CSV; writes only under --out. Places no orders.

    python analysis/gb_nq_backtest/lucid_grid.py driver   --version {8h,full} --out DIR [--workers W]
    python analysis/gb_nq_backtest/lucid_grid.py manifest --version {8h,full} --out DIR
    python analysis/gb_nq_backtest/lucid_grid.py run      --out DIR --batch FILE
    python analysis/gb_nq_backtest/lucid_grid.py validate --out DIR --stage {1,2}
    python analysis/gb_nq_backtest/lucid_grid.py select   --out DIR
    python analysis/gb_nq_backtest/lucid_grid.py report   --out DIR
    python analysis/gb_nq_backtest/lucid_grid.py verify   [--ref-dir reports] [--seed 1]

Model [5]: per-minute records from prop_multi.build_records (worst open P&L inside each minute), Account from lucid_replay
(EOD trailing MLL, lock), soft DLL, eval / funded attempts memoised per (plan, size[, policy], start day); funded attempts are
lazy generators that keep taking payouts (max 5 per account) so journeys can run on to break-even (0.9 x payouts >= fees).
"""
import argparse, bisect, csv, ctypes, glob, gzip, hashlib, json, math, os, random, shutil, statistics, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lucid_replay import Account, PLANS as LR_PLANS
from prop_multi import load_trades, load_bars, fast_sess_ord, build_records, day_start_ms

CSV_NQ = "analysis/data/NQ_1m_latest.csv"
BASE = ("reports/prop3/q1/s1/trades.csv", "reports/prop3/q1/s3/trades.csv")
VAR = "reports/robust_r1/variants/{v}/{s}.csv"
NOISE = "reports/robust_r1/noise/{s}/seed_{i:04d}/trades.csv"
MULT, SPLIT, MAX_PAYOUTS, HORIZON = 2.0, 0.90, 5, 365
VERSIONS = {"8h": dict(step=2, n1=30, n2=200), "full": dict(step=1, n1=50, n2=200),
            "smoke": dict(step=40, n1=2, n2=3)}          # smoke = pipeline test only
POLICIES = ("500", "max")

# ---------------------------------------------------------------- plans [4] (source: docs/Lucid Trading - All Account Types Rules Report.md, 2026-11-04)
def _p(kind, size, fee, reset, target, allow, lock_bal, dll, lo, hi, cap_micros, **kw):
    start = {"25k": 25000.0, "50k": 50000.0, "100k": 100000.0, "150k": 150000.0}[size]
    return dict(kind=kind, size=size, start=start, fee=fee, reset=reset, target=target, allow=allow, lock_bal=lock_bal,
                lock_mll=start + 100.0, dll=dll, sizes=list(range(lo, hi + 1)), cap_micros=cap_micros, **kw)

PLANS = {
    # Pro: payout gate bal - R >= buffer (= lock_bal), cycle >= goal, best day <= 40%; max payout 1 / 2+
    "pro_25k":  _p("pro", "25k", 75.60, 75.0, 1250.0, 1000.0, 26100.0, 600.0, 1, 3, 20, goal=250.0, consist=0.40, max1=1000.0, max2=1500.0),
    "pro_50k":  _p("pro", "50k", 120.40, 120.0, 3000.0, 2000.0, 52100.0, 1200.0, 2, 6, 40, goal=500.0, consist=0.40, max1=2000.0, max2=2500.0),
    "pro_100k": _p("pro", "100k", 190.40, 190.0, 6000.0, 3000.0, 103100.0, 1800.0, 3, 9, 60, goal=750.0, consist=0.40, max1=2500.0, max2=3000.0),
    "pro_150k": _p("pro", "150k", 255.50, 255.0, 9000.0, 4500.0, 154600.0, 2700.0, 5, 14, 100, goal=1000.0, consist=0.40, max1=3000.0, max2=3500.0),
    # Flex: eval 40% day rule; funded: >= 5 days with day P&L >= minday, cycle > 0, R <= 50% of (bal - start), cap; MLL -> lock at request
    "flex_25k":  _p("flex", "25k", 45.50, 45.0, 1250.0, 1000.0, 26100.0, 600.0, 1, 3, 20, eval_consist=0.40, minday=100.0, cap=1000.0, scale_cap=10),
    "flex_50k":  _p("flex", "50k", 80.50, 80.0, 3000.0, 2000.0, 52100.0, 1200.0, 2, 6, 40, eval_consist=0.40, minday=150.0, cap=2000.0, scale_cap=20),
    "flex_100k": _p("flex", "100k", 150.50, 150.0, 6000.0, 3000.0, 103100.0, 1800.0, 3, 9, 60, eval_consist=0.40, minday=200.0, cap=2500.0, scale_cap=30),
    "flex_150k": _p("flex", "150k", 226.10, 225.0, 9000.0, 4500.0, 154600.0, 2700.0, 5, 14, 100, eval_consist=0.40, minday=250.0, cap=3000.0, scale_cap=40),
    # Direct: no eval; buy a new account after a breach; cycle >= goal (payout 1 / 2+), best day <= 20%; max payouts 1-3 / 4-5
    "direct_25k":  _p("direct", "25k", 230.30, 230.30, 0.0, 1000.0, 26100.0, 0.0, 1, 3, 20, goal1=1500.0, goal2=1250.0, consist=0.20, max13=1000.0, max45=1000.0),
    "direct_50k":  _p("direct", "50k", 360.50, 360.50, 0.0, 2000.0, 52100.0, 1200.0, 2, 6, 40, goal1=3000.0, goal2=2500.0, consist=0.20, max13=2000.0, max45=2500.0),
    "direct_100k": _p("direct", "100k", 490.00, 490.00, 0.0, 3500.0, 103600.0, 2100.0, 3, 9, 60, goal1=6000.0, goal2=3500.0, consist=0.20, max13=2500.0, max45=3000.0),
    "direct_150k": _p("direct", "150k", 585.20, 585.20, 0.0, 5000.0, 155100.0, 3000.0, 5, 14, 100, goal1=9000.0, goal2=4500.0, consist=0.20, max13=3000.0, max45=3500.0),
}
_lr = LR_PLANS["25k"]                       # the 25K Pro row must equal the model used in B-11..B-20
assert (_lr["start"], _lr["target"], _lr["allow"], _lr["lock_bal"], _lr["lock_mll"]) == \
       (PLANS["pro_25k"]["start"], PLANS["pro_25k"]["target"], PLANS["pro_25k"]["allow"], PLANS["pro_25k"]["lock_bal"], PLANS["pro_25k"]["lock_mll"])
for _k, _v in PLANS.items():
    assert abs(_v["lock_bal"] - _v["allow"] - _v["lock_mll"]) < 1e-9, _k      # the trail reaches the lock exactly at lock_bal


def combos(pk):
    p = PLANS[pk]
    if p["kind"] == "direct":
        return [(0, f) for f in p["sizes"]]
    return [(e, f) for e in p["sizes"] for f in p["sizes"]]


def request(p, k, policy):
    """Request for the k-th payout (1-based) of an account under a policy."""
    if policy == "500":
        return 500.0
    if p["kind"] == "pro":
        return p["max1"] if k == 1 else p["max2"]
    if p["kind"] == "flex":
        return p["cap"]
    return p["max13"] if k <= 3 else p["max45"]


def cid(pk, e, f, pol):
    return f"{pk}|{e}|{f}|{pol}"


def label(c):
    pk, e, f, pol = c.split("|")
    return f"{pk} {'' if e == '0' else e + '/'}{f} ${pol}" if pol == "500" else f"{pk} {'' if e == '0' else e + '/'}{f} max"

# ---------------------------------------------------------------- data
class Ctx:
    def __init__(self, csv_path):
        self.bars = {csv_path: load_bars(csv_path, 0, 4102444800000)}
        self.bk = csv_path
        self.daylist = sorted({fast_sess_ord(t) for t in self.bars[csv_path][0]})
        self.day_idx = {o: i for i, o in enumerate(self.daylist)}


def make_S(ctx, tr, n):
    """Records at size n per strategy: trades with q=n and pnl x n (exact, as first_payout.py's mk())."""
    trs = [dict(r, bk=ctx.bk, m=MULT, q=n, pnl=r["pnl"] * n) for r in tr]
    rc, mx, _m = build_records(trs, ctx.bars, ctx.day_idx, ctx.daylist)
    return dict(trades=trs, recs=rc, win={}, RT=[r[0] for r in rc], SDs=[r[1] for r in rc], maxc=mx)


def window(ctx, S, di):
    """(pre, si): an account starting at session index di walks pre (records of trades opened after the start that overlap
    trades straddling the start, rebuilt without those) then S['recs'][si:]. Same rule as prop_multi.attempt_recs, no copy."""
    W = S["win"]
    if di not in W:
        D = ctx.daylist[di]
        sms = day_start_ms(D)
        st = [tr for tr in S["trades"] if tr["e"] < sms <= tr["x"]]
        if not st:
            W[di] = ((), bisect.bisect_left(S["SDs"], D))
        else:
            mx = max(tr["x"] for tr in st)
            inc = [tr for tr in S["trades"] if sms <= tr["e"] <= mx]
            hr, _c, _m = build_records(inc, ctx.bars, ctx.day_idx, ctx.daylist, tmax=mx)
            W[di] = (tuple(hr), bisect.bisect_right(S["RT"], mx))
    return W[di]


def walk(S, pre, si):
    yield from pre
    recs = S["recs"]
    for i in range(si, len(recs)):
        yield recs[i]

# ---------------------------------------------------------------- accounts [5.2]
def eval_attempt(ctx, S, p, di):
    """-> (PASS|FAIL|OPEN, end_di)."""
    pre, si = window(ctx, S, di)
    acc, start, dll = Account(p), p["start"], p["dll"]
    ec = p.get("eval_consist")
    cur = cur_di = None
    dstart, locked, today, days = start, False, 0.0, {}
    for t, sd, ddi, un, pnl, k, _c in walk(S, pre, si):
        if sd != cur:
            if cur is not None:
                acc.eod()
            cur, cur_di, dstart, locked, today = sd, ddi, acc.bal, False, 0.0
        if locked:
            continue
        if acc.bal + un <= acc.mll:
            return "FAIL", ddi
        if dll and (acc.bal - dstart) + un <= -dll:
            acc.bal += un; today += un; locked = True
            if ec: days[sd] = today
            if acc.bal <= acc.mll:
                return "FAIL", ddi
            continue
        if pnl != 0.0 or k:
            acc.bal += pnl; today += pnl
            if acc.bal <= acc.mll:
                return "FAIL", ddi
            prof = acc.bal - start
            if ec:
                days[sd] = today
                if prof >= p["target"] and max(days.values()) <= ec * prof:
                    return "PASS", ddi
            elif prof >= p["target"]:
                return "PASS", ddi
    return "OPEN", cur_di


def funded_gen(ctx, S, p, policy, di, out):
    """Generator over one funded account from session index di. Appends (payout_di, amount) to out['ev'] as payouts happen
    (one per yield); sets out['end'] = (FAIL|OPEN|LIVE, di) when it stops."""
    pre, si = window(ctx, S, di)
    acc, start, dll, kind = Account(p), p["start"], p["dll"], p["kind"]
    cur = cur_di = None
    dstart, locked, today, cyc, npay = start, False, 0.0, {}, 0

    def dayclose(day):
        acc.eod()
        cyc[day] = cyc.get(day, 0.0) + today
        R = request(p, npay + 1, policy)
        tot = sum(cyc.values())
        if kind == "pro":
            ok = acc.bal - R >= p["lock_bal"] and tot >= p["goal"] and max(cyc.values()) <= p["consist"] * tot
        elif kind == "flex":
            ok = (sum(v >= p["minday"] for v in cyc.values()) >= 5 and tot > 0 and R <= 0.5 * (acc.bal - start)
                  and R <= p["cap"])
        else:
            goal = p["goal1"] if npay == 0 else p["goal2"]
            ok = tot >= goal and max(cyc.values()) <= p["consist"] * tot
        return R if (ok and acc.bal - R > acc.mll) else 0.0

    def pay(R):
        nonlocal npay, cyc
        acc.bal -= R; npay += 1; cyc = {}
        if kind == "flex":
            acc.locked, acc.mll = True, p["lock_mll"]

    for t, sd, ddi, un, pnl, k, _c in walk(S, pre, si):
        if sd != cur:
            if cur is not None:
                R = dayclose(cur)
                if R:
                    pay(R); out["ev"].append((cur_di, R))
                    if npay >= MAX_PAYOUTS:
                        out["end"] = ("LIVE", cur_di); return
                    yield
            cur, cur_di, dstart, locked, today = sd, ddi, acc.bal, False, 0.0
        if locked:
            continue
        if acc.bal + un <= acc.mll:
            out["end"] = ("FAIL", ddi); return
        if dll and (acc.bal - dstart) + un <= -dll:
            acc.bal += un; today += un; locked = True
            if acc.bal <= acc.mll:
                out["end"] = ("FAIL", ddi); return
            continue
        if pnl != 0.0 or k:
            acc.bal += pnl; today += pnl
            if acc.bal <= acc.mll:
                out["end"] = ("FAIL", ddi); return
    if cur is not None:
        R = dayclose(cur)
        if R:
            pay(R); out["ev"].append((cur_di, R))
            if npay >= MAX_PAYOUTS:
                out["end"] = ("LIVE", cur_di); return
    out["end"] = ("OPEN", cur_di)


class Memo:
    def __init__(self, ctx):
        self.ctx, self.ev, self.fu = ctx, {}, {}

    def eval(self, S, size, pk, di):
        k = (pk, size, di)
        if k not in self.ev:
            self.ev[k] = eval_attempt(self.ctx, S, PLANS[pk], di)
        return self.ev[k]

    def payout(self, S, size, pk, pol, di, j):
        """j-th payout (0-based) of the funded account started at di, or None if it ended before; then .end is set."""
        k = (pk, size, pol, di)
        o = self.fu.get(k)
        if o is None:
            o = self.fu[k] = {"ev": [], "end": None}
            o["gen"] = funded_gen(self.ctx, S, PLANS[pk], pol, di, o)
        while len(o["ev"]) <= j and o["end"] is None:
            next(o["gen"], None)
        if o["end"] is not None:
            o["gen"] = None
        return (o["ev"][j] if j < len(o["ev"]) else None), o

# ---------------------------------------------------------------- journeys [5.4]
def journey(memo, SE, SF, pk, e, f, pol, start_di):
    p = PLANS[pk]
    direct = p["kind"] == "direct"
    fees, evals, resets, fbr, rec, npay = p["fee"], 1, 0, 0, 0.0, 0
    first = None
    di, mode = start_di, ("funded" if direct else "eval")
    nd = len(memo.ctx.daylist)
    end = ("OPEN", None)
    while di < nd:
        if mode == "eval":
            res, edi = memo.eval(SE, e, pk, di)
            if res == "OPEN" or edi is None:
                break
            if res == "FAIL":
                resets += 1; fees += p["reset"]
            else:
                mode = "funded"
            di = edi + 1
            continue
        j = 0
        while True:
            ev, o = memo.payout(SF, f, pk, pol, di, j)
            if ev is None:
                break
            pdi, amt = ev
            npay += 1; rec += SPLIT * amt
            if first is None:
                first = (pdi, amt, fees, evals, resets, fbr)
            if rec >= fees - 1e-9:
                return dict(first=first, be=(pdi, npay), fees=fees, evals=evals, resets=resets, fbr=fbr, end="BREAKEVEN")
            j += 1
        state, edi = o["end"]
        if state == "LIVE":
            end = ("LIVE", edi); break
        if state != "FAIL" or edi is None:
            break
        fbr += 1; evals += 1; fees += p["fee"]
        mode = "funded" if direct else "eval"
        di = edi + 1
    return dict(first=first, be=None, fees=fees, evals=evals, resets=resets, fbr=fbr, end=end[0])


def pct(v, q):
    v = sorted(v)
    if not v:
        return float("nan")
    k = (len(v) - 1) * q
    fl = math.floor(k)
    return v[fl] if fl == k else v[fl] + (v[fl + 1] - v[fl]) * (k - fl)


def ci(v):
    if len(v) < 2:
        return float("nan"), float("nan")
    m, s = statistics.mean(v), statistics.stdev(v)
    h = 1.96 * s / math.sqrt(len(v))
    return m - h, m + h


SUMMARY_COLS = ["plan", "e", "f", "policy", "n", "paid", "never_paid", "r1", "d1_median", "d1_p90", "le4m", "le6m", "le12m",
                "fees1_mean", "fees1_median", "fees1_p90", "fees1_max", "fees1_gt_r1", "resets1", "evals1", "fbr1",
                "net_per_month", "be_n", "be_median", "be_p90", "be_le6m", "be_le12m", "never_be", "live_no_be",
                "be_payouts_median", "chain_n", "chain_fees_mean", "chain_fees_lo", "chain_fees_hi", "chain_days_mean",
                "chain_days_lo", "chain_days_hi", "max_micros"]


def stats(rows, daylist, chain):
    n = len(rows)
    paid = [r for r in rows if r["first"] is not None]
    d1 = [daylist[r["first"][0]] - daylist[r["s"]] for r in paid]
    f1 = [r["first"][2] for r in paid]
    r1 = [r["first"][1] for r in paid]
    be = [r for r in rows if r["be"] is not None]
    dbe = [daylist[r["be"][0]] - daylist[r["s"]] for r in be]
    sh = lambda xs, m: 100.0 * sum(x <= m * 30.4 for x in xs) / n
    out = dict(n=n, paid=len(paid), never_paid=n - len(paid), r1=(statistics.mean(r1) if r1 else float("nan")),
               d1_median=pct(d1, .5), d1_p90=pct(d1, .9), le4m=sh(d1, 4), le6m=sh(d1, 6), le12m=sh(d1, 12),
               fees1_mean=(statistics.mean(f1) if f1 else float("nan")), fees1_median=pct(f1, .5), fees1_p90=pct(f1, .9),
               fees1_max=(max(f1) if f1 else float("nan")),
               fees1_gt_r1=(100.0 * sum(r["first"][2] > r["first"][1] for r in paid) / n),
               resets1=(statistics.mean(r["first"][4] for r in paid) if paid else float("nan")),
               evals1=(statistics.mean(r["first"][3] for r in paid) if paid else float("nan")),
               fbr1=(statistics.mean(r["first"][5] for r in paid) if paid else float("nan")),
               net_per_month=((SPLIT * statistics.mean(r1) - statistics.mean(f1)) / (statistics.mean(d1) / 30.4)
                              if paid and statistics.mean(d1) > 0 else float("nan")),
               be_n=len(be), be_median=pct(dbe, .5), be_p90=pct(dbe, .9), be_le6m=sh(dbe, 6), be_le12m=sh(dbe, 12),
               never_be=100.0 * (n - len(be)) / n, live_no_be=100.0 * sum(r["end"] == "LIVE" for r in rows) / n,
               be_payouts_median=pct([r["be"][1] for r in be], .5))
    cf = [c["first"][2] for c in chain]
    cd = [daylist[c["first"][0]] - daylist[c["s"]] for c in chain]
    lo, hi = ci(cf)
    lo2, hi2 = ci(cd)
    out.update(chain_n=len(chain), chain_fees_mean=(statistics.mean(cf) if cf else float("nan")), chain_fees_lo=lo,
               chain_fees_hi=hi, chain_days_mean=(statistics.mean(cd) if cd else float("nan")), chain_days_lo=lo2,
               chain_days_hi=hi2)
    return out


def fmt(v):
    if isinstance(v, float):
        return "nan" if v != v else f"{v:.4f}"
    return str(v)

# ---------------------------------------------------------------- one case
def case_paths(case):
    if case == "base":
        return BASE
    if case in ("hc25", "hc50", "top10"):
        return VAR.format(v=case, s="s1"), VAR.format(v=case, s="s3")
    i = int(case.split("_")[1])
    return NOISE.format(s="s1", i=i), NOISE.format(s="s3", i=i)


def run_case(ctx, case, out_dir, step, only=None, journeys_out=False):
    """only: set of combo ids to run (stage 2); None = every combo of every plan."""
    t0 = time.time()
    s1, s3 = case_paths(case)
    tr = load_trades("s1", s1, "", "") + load_trades("s3", s3, "", "")
    daylist = ctx.daylist
    want = {}
    for pk in PLANS:
        for e, f in combos(pk):
            for pol in POLICIES:
                if only is None or cid(pk, e, f, pol) in only:
                    want.setdefault(pk, []).append((e, f, pol))
    rows_out, jrows = [], []
    Ss = {}
    base_S = None
    for size_key in ("25k", "50k", "100k", "150k"):
        pks = [pk for pk in want if PLANS[pk]["size"] == size_key]
        if not pks:
            continue
        need = sorted({x for pk in pks for e, f, _ in want[pk] for x in (e, f) if x})
        for n in list(Ss):
            if n not in need:
                del Ss[n]
        for n in need:
            if n not in Ss:
                Ss[n] = make_S(ctx, tr, n)
        if base_S is None:
            base_S = Ss[need[0]]
        first = Ss[need[0]]["recs"][0][2]
        last_ok = daylist[-1] - HORIZON
        starts = [di for di in range(first, len(daylist), step) if daylist[di] <= last_ok]
        for pk in pks:
            memo = Memo(ctx)
            p = PLANS[pk]
            for e, f, pol in want[pk]:
                SE, SF = (Ss[e] if e else None), Ss[f]
                mm = max(SF["maxc"], SE["maxc"] if SE else 0)
                assert mm <= p["cap_micros"], (pk, e, f, mm)
                if p["kind"] == "flex":
                    assert SF["maxc"] <= p["scale_cap"], (pk, f, SF["maxc"])
                rows = []
                for di in starts:
                    j = journey(memo, SE, SF, pk, e, f, pol, di)
                    j["s"] = di
                    rows.append(j)
                chain, di = [], first
                while di < len(daylist):
                    j = journey(memo, SE, SF, pk, e, f, pol, di)
                    if j["first"] is None:
                        break
                    j["s"] = di
                    chain.append(j)
                    di = j["first"][0] + 1
                for r in rows:          # [9.3] break-even at payout 1 <=> 0.9 x R1 >= fees up to then
                    if r["first"] is not None:
                        at1 = SPLIT * r["first"][1] >= r["first"][2] - 1e-9
                        assert at1 == (r["be"] is not None and r["be"][1] == 1), (pk, e, f, pol, r)
                st = stats(rows, daylist, chain)
                st.update(plan=pk, e=e, f=f, policy=pol, max_micros=mm)
                rows_out.append(st)
                if journeys_out:
                    for r in rows:
                        fr = r["first"]
                        jrows.append([pk, e, f, pol, _iso(daylist[r["s"]]), r["end"],
                                      "" if fr is None else daylist[fr[0]] - daylist[r["s"]],
                                      "" if fr is None else fr[0] - r["s"] + 1, "" if fr is None else f"{fr[1]:.2f}",
                                      "" if fr is None else f"{fr[2]:.2f}", "" if fr is None else fr[3],
                                      "" if fr is None else fr[4], "" if fr is None else fr[5],
                                      "" if r["be"] is None else daylist[r["be"][0]] - daylist[r["s"]],
                                      "" if r["be"] is None else r["be"][1], f"{r['fees']:.2f}", r["evals"], r["resets"], r["fbr"]])
    d = os.path.join(out_dir, case)
    os.makedirs(d, exist_ok=True)
    tmp = os.path.join(d, "summary.csv.tmp")
    with open(tmp, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(SUMMARY_COLS)
        for st in rows_out:
            w.writerow([fmt(st[c]) for c in SUMMARY_COLS])
    if journeys_out:
        tj = os.path.join(d, "journeys.csv.gz.tmp")
        with gzip.open(tj, "wt", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["plan", "e", "f", "policy", "start", "end", "days_first", "tdays_first", "r1", "fees_first",
                        "evals_first", "resets_first", "fbr_first", "days_be", "payouts_be", "fees_total", "evals",
                        "resets", "fbr"])
            w.writerows(jrows)
        os.replace(tj, os.path.join(d, "journeys.csv.gz"))
    os.replace(tmp, os.path.join(d, "summary.csv"))
    print(f"[{time.strftime('%H:%M:%S')}] {case}: {len(rows_out)} rows, {time.time() - t0:.0f}s", flush=True)


def _iso(o):
    import datetime
    return datetime.date.fromordinal(o).isoformat()

# ---------------------------------------------------------------- manifest / batches / validation [8]
def cases_stage1(v):
    return ["base", "hc25", "hc50", "top10"] + [f"seed_{i:04d}" for i in range(1, VERSIONS[v]["n1"] + 1)]


def all_ids():
    return [cid(pk, e, f, pol) for pk in PLANS for e, f in combos(pk) for pol in POLICIES]


def load_cfg(out):
    return json.load(open(os.path.join(out, "config.json")))


def cmd_manifest(a):
    os.makedirs(os.path.join(a.out, "batches"), exist_ok=True)
    cfg = dict(version=a.version, **VERSIONS[a.version], batch_cases=a.batch_cases, created=time.strftime("%Y-%m-%d %H:%M:%S"),
               inputs=input_hashes())
    p = os.path.join(a.out, "config.json")
    if os.path.exists(p):
        old = load_cfg(a.out)
        if (old["version"], old["step"], old["n1"]) != (cfg["version"], cfg["step"], cfg["n1"]):
            sys.exit(f"{a.out} already holds a different version: {old}")
        if old["inputs"] != cfg["inputs"]:
            sys.exit("input files changed since the manifest was written -- stop and investigate")
        return
    json.dump(cfg, open(p, "w"), indent=1)
    ids = all_ids()
    with open(os.path.join(a.out, "manifest_stage1.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["case", "combo"])
        for c in cases_stage1(a.version):
            for i in ids:
                w.writerow([c, i])
    write_batches(a.out, "stage1", cases_stage1(a.version), a.batch_cases)
    print(f"manifest: {len(cases_stage1(a.version))} cases x {len(ids)} combos")


def write_batches(out, stage, cases, k):
    for i in range(0, len(cases), k):
        with open(os.path.join(out, "batches", f"{stage}_{i // k + 1:03d}.txt"), "w") as fh:
            fh.write("\n".join(cases[i:i + k]) + "\n")


def input_hashes():
    files = [CSV_NQ] + list(BASE) + [VAR.format(v=v, s=s) for v in ("hc25", "hc50", "top10") for s in ("s1", "s3")] + \
            [NOISE.format(s=s, i=i) for s in ("s1", "s3") for i in range(1, 201)]
    h = hashlib.sha256()
    for f in files:
        with open(f, "rb") as fh:
            h.update(hashlib.sha256(fh.read()).digest())
    return h.hexdigest()


def expected(out, stage):
    if stage == 1:
        rows = list(csv.DictReader(open(os.path.join(out, "manifest_stage1.csv"))))
    else:
        rows = list(csv.DictReader(open(os.path.join(out, "manifest_stage2.csv"))))
    exp = {}
    for r in rows:
        exp.setdefault(r["case"], set()).add(r["combo"])
    return exp


def stage_dir(out, stage):
    return os.path.join(out, "cases" if stage == 1 else "cases2")


REQUIRED = ["n", "paid", "never_paid", "le6m", "le12m", "never_be", "fees1_gt_r1", "chain_n", "max_micros"]


def check_case(out, stage, case, combos_exp, n_exp):
    p = os.path.join(stage_dir(out, stage), case, "summary.csv")
    if not os.path.exists(p):
        return "missing"
    try:
        rows = list(csv.DictReader(open(p)))
    except Exception as ex:          # noqa: BLE001
        return f"unreadable: {ex}"
    got = {cid(r["plan"], r["e"], r["f"], r["policy"]): r for r in rows}
    if len(got) != len(rows):
        return "duplicate rows"
    if set(got) != combos_exp:
        return f"rows {len(got)} != expected {len(combos_exp)}"
    for k, r in got.items():
        for c in REQUIRED:
            if r[c] in ("", "nan"):
                return f"{k}: {c} empty"
        if int(r["n"]) != n_exp:
            return f"{k}: n {r['n']} != {n_exp}"
        if int(r["paid"]) + int(r["never_paid"]) != n_exp:
            return f"{k}: paid + never_paid != n"
        if int(r["paid"]) and r["d1_median"] in ("", "nan"):
            return f"{k}: d1_median empty with paid > 0"
    return "ok"


def n_expected(out):
    cfg = load_cfg(out)
    ctx_days = json.load(open(os.path.join(out, "n_starts.json")))["n"] if os.path.exists(os.path.join(out, "n_starts.json")) else None
    return ctx_days


def cmd_validate(a, quiet=False):
    exp = expected(a.out, a.stage)
    n_exp = n_expected(a.out)
    if n_exp is None:
        sys.exit("n_starts.json missing (written by the first run)")
    bad, L = 0, []
    for case in sorted(exp):
        r = check_case(a.out, a.stage, case, exp[case], n_exp)
        L.append(f"{case}\t{r}")
        bad += r != "ok"
    fail_p = os.path.join(a.out, f"failed_stage{a.stage}.txt")
    failed = open(fail_p).read().split() if os.path.exists(fail_p) else []
    L.append(f"TOTAL {len(exp)} cases, {len(exp) - bad} ok, {bad} not ok; FAILED after retries: {len(failed)} {' '.join(failed)}")
    with open(os.path.join(a.out, f"validation_stage{a.stage}.txt"), "w") as fh:
        fh.write("\n".join(L) + "\n")
    if not quiet:
        print(L[-1])
    return bad == 0


def cmd_run(a):
    cfg = load_cfg(a.out)
    stage = 2 if os.path.basename(a.batch).startswith("stage2") else 1
    cases = [l.strip() for l in open(a.batch) if l.strip()]
    exp = expected(a.out, stage)
    n_exp = n_expected(a.out)
    todo = [c for c in cases if n_exp is None or check_case(a.out, stage, c, exp[c], n_exp) != "ok"]
    if not todo:
        return
    ctx = Ctx(CSV_NQ)
    if n_exp is None:
        tr = load_trades("s1", BASE[0], "", "") + load_trades("s3", BASE[1], "", "")
        S = make_S(ctx, tr, 1)
        first = S["recs"][0][2]
        last_ok = ctx.daylist[-1] - HORIZON
        n = len([di for di in range(first, len(ctx.daylist), cfg["step"]) if ctx.daylist[di] <= last_ok])
        json.dump(dict(n=n), open(os.path.join(a.out, "n_starts.json"), "w"))
        del S
    for c in todo:
        only = exp[c] if stage == 2 else None
        run_case(ctx, c, stage_dir(a.out, stage), cfg["step"], only=only, journeys_out=(c == "base" and stage == 1))

# ---------------------------------------------------------------- selection [6]
def read_stage(out, stage, cases):
    res = {}
    for c in cases:
        for r in csv.DictReader(open(os.path.join(stage_dir(out, stage), c, "summary.csv"))):
            res.setdefault(cid(r["plan"], r["e"], r["f"], r["policy"]), {})[c] = r
    return res


def f_(r, k):
    v = r[k]
    return float("nan") if v in ("", "nan") else float(v)


def noise_median(by_case, k):
    v = [f_(r, k) for c, r in by_case.items() if c.startswith("seed_")]
    v = [x for x in v if x == x]
    return statistics.median(v) if v else float("nan")


def rank_combos(res, pk, pol):
    """The selection rule [plan 3.5], shared by `select` and the report's cross-plan ranking.
    Returns (ranked candidates, reason); each candidate is (combo id, net/month, le12m, d1 median, fbr1), noise medians."""
    cand = []
    for e, f in combos(pk):
        k = cid(pk, e, f, pol)
        by = res[k]
        cand.append((k, noise_median(by, "net_per_month"), noise_median(by, "le12m"), noise_median(by, "d1_median"),
                     noise_median(by, "fbr1")))
    ok = [c for c in cand if c[2] >= 80.0 and c[1] == c[1]]
    if ok:
        return sorted(ok, key=lambda c: (-c[1], c[4])), "net_per_month (le12m >= 80%)"
    return sorted([c for c in cand if c[3] == c[3]], key=lambda c: (c[3], c[4])), "lowest median days (none with le12m >= 80%)"


def cmd_select(a):
    cfg = load_cfg(a.out)
    a.stage = 1
    if not cmd_validate(a, quiet=True):
        sys.exit("stage 1 is not complete and valid -- selection refused")
    sel_p = os.path.join(a.out, "step2_selection.csv")
    if os.path.exists(sel_p):
        print("selection already frozen:", sel_p)
        return
    res = read_stage(a.out, 1, cases_stage1(cfg["version"]))
    sel = []
    for pk in PLANS:
        for pol in POLICIES:
            ok, why = rank_combos(res, pk, pol)
            for c in ok[:2]:
                sel.append((c[0], why, f"{c[1]:.2f}", f"{c[2]:.1f}", f"{c[3]:.1f}"))
    for e, f in ((2, 2), (3, 2)):
        for pol in POLICIES:
            k = cid("pro_25k", e, f, pol)
            if k not in [s[0] for s in sel]:
                sel.append((k, "asked by the user (25K 2/2, 3/2)", "", "", ""))
    with open(sel_p, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["combo", "why", "net_per_month_noise_median", "le12m_noise_median", "d1_median_noise_median"])
        w.writerows(sel)
    cases2 = [f"seed_{i:04d}" for i in range(cfg["n1"] + 1, cfg["n2"] + 1)]
    with open(os.path.join(a.out, "manifest_stage2.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["case", "combo"])
        for c in cases2:
            for s in sel:
                w.writerow([c, s[0]])
    write_batches(a.out, "stage2", cases2, cfg["batch_cases2"] if "batch_cases2" in cfg else max(1, cfg["batch_cases"] * 3))
    print(f"selected {len(sel)} combos; stage 2: {len(cases2)} cases")

# ---------------------------------------------------------------- driver [8]
def free_mb():
    try:
        class MS(ctypes.Structure):
            _fields_ = [("l", ctypes.c_ulong), ("load", ctypes.c_ulong), ("tot", ctypes.c_ulonglong), ("avail", ctypes.c_ulonglong),
                        ("tp", ctypes.c_ulonglong), ("ap", ctypes.c_ulonglong), ("tv", ctypes.c_ulonglong), ("av", ctypes.c_ulonglong),
                        ("x", ctypes.c_ulonglong)]
        m = MS(); m.l = ctypes.sizeof(MS)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
        return m.avail // 2 ** 20
    except Exception:                 # noqa: BLE001
        try:
            for line in open("/proc/meminfo"):
                if line.startswith("MemAvailable"):
                    return int(line.split()[1]) // 1024
        except Exception:             # noqa: BLE001
            pass
        try:                          # macOS: free + inactive + speculative pages from vm_stat
            out = subprocess.run(["vm_stat"], capture_output=True, text=True, timeout=10).stdout
            page = int(out.split("page size of")[1].split()[0])
            n = {ln.split(":")[0].strip(): int(ln.split(":")[1].strip().rstrip(".")) for ln in out.splitlines()[1:] if ":" in ln}
            return (n["Pages free"] + n.get("Pages inactive", 0) + n.get("Pages speculative", 0)) * page // 2 ** 20
        except Exception:             # noqa: BLE001
            pass
    return -1


def log(out, msg):
    line = f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(os.path.join(out, "driver_log.txt"), "a") as fh:
        fh.write(line + "\n")


def run_stage(a, stage):
    exp = expected(a.out, stage)
    batches = sorted(glob.glob(os.path.join(a.out, "batches", f"stage{stage}_*.txt")))
    tries = {}
    queue = list(batches)
    running = []
    kw = {}
    if os.name == "nt":
        kw["creationflags"] = 0x00004000          # BELOW_NORMAL_PRIORITY_CLASS
    else:
        kw["preexec_fn"] = lambda: os.nice(10)
    while queue or running:
        while queue and len(running) < a.workers:
            fm = free_mb()
            if running and 0 <= fm < a.min_free_mb:
                break
            b = queue.pop(0)
            tries[b] = tries.get(b, 0) + 1
            lf = open(os.path.join(a.out, "logs", os.path.basename(b) + f".try{tries[b]}.log"), "w")
            pr = subprocess.Popen([sys.executable, os.path.abspath(__file__), "run", "--out", a.out, "--batch", b],
                                  stdout=lf, stderr=subprocess.STDOUT, **kw)
            running.append((pr, b, time.time(), lf))
            log(a.out, f"start {os.path.basename(b)} try {tries[b]} cases {open(b).read().split()} free_mb {fm}")
        time.sleep(5)
        for item in list(running):
            pr, b, t0, lf = item
            if pr.poll() is None:
                continue
            running.remove(item); lf.close()
            n_exp = n_expected(a.out)
            cs = open(b).read().split()
            st = {c: (check_case(a.out, stage, c, exp[c], n_exp) if n_exp else "missing") for c in cs}
            bad = [c for c, s in st.items() if s != "ok"]
            log(a.out, f"end {os.path.basename(b)} rc {pr.returncode} {time.time() - t0:.0f}s ok {len(cs) - len(bad)}/{len(cs)}"
                       + (f" bad {st}" if bad else ""))
            if bad:
                if tries[b] < 3:
                    queue.append(b)
                    if pr.returncode and a.workers > 1:
                        a.workers = 1
                        log(a.out, "a batch failed: continuing with 1 worker")
                else:
                    fp = os.path.join(a.out, f"failed_stage{stage}.txt")
                    with open(fp, "a") as fh:
                        fh.write("\n".join(bad) + "\n")
                    log(a.out, f"FAILED after 3 tries: {bad}")
    a.stage = stage
    return cmd_validate(a)


def cmd_driver(a):
    os.makedirs(os.path.join(a.out, "logs"), exist_ok=True)
    cmd_manifest(a)
    log(a.out, f"driver start version {a.version} workers {a.workers} free_mb {free_mb()}")
    if not run_stage(a, 1):
        log(a.out, "stage 1 incomplete -- stopping before selection (see validation_stage1.txt)")
        sys.exit(1)
    cmd_select(a)
    if not run_stage(a, 2):
        log(a.out, "stage 2 incomplete -- report will mark the selected combos at stage-1 depth only")
    repro(a)
    cmd_report(a)
    log(a.out, "driver done")


def repro(a):
    """[8.7] re-run 3 random finished stage-1 cases into a temp dir; summaries must be byte-identical."""
    cfg = load_cfg(a.out)
    rnd = random.Random(20261104)
    cases = rnd.sample(cases_stage1(cfg["version"]), 3)
    tmp = os.path.join(a.out, "repro_tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    ctx = Ctx(CSV_NQ)
    L = []
    for c in cases:
        run_case(ctx, c, tmp, cfg["step"], journeys_out=(c == "base"))
        h1 = hashlib.sha256(open(os.path.join(stage_dir(a.out, 1), c, "summary.csv"), "rb").read()).hexdigest()
        h2 = hashlib.sha256(open(os.path.join(tmp, c, "summary.csv"), "rb").read()).hexdigest()
        L.append(f"{c}\t{'identical' if h1 == h2 else 'DIFFERENT'}\t{h1}\t{h2}")
    with open(os.path.join(a.out, "repro_check.txt"), "w") as fh:
        fh.write("\n".join(L) + "\n")
    log(a.out, "repro: " + "; ".join(l.split("\t")[0] + " " + l.split("\t")[1] for l in L))
    shutil.rmtree(tmp, ignore_errors=True)

# ---------------------------------------------------------------- report [10]
def cmd_report(a):
    cfg = load_cfg(a.out)
    a.stage = 1
    if not cmd_validate(a, quiet=True):
        sys.exit("stage 1 not complete and valid -- report refused")
    s2_ok = os.path.exists(os.path.join(a.out, "manifest_stage2.csv"))
    if s2_ok:
        a.stage = 2
        s2_ok = cmd_validate(a, quiet=True)
    c1 = cases_stage1(cfg["version"])
    seeds1 = [c for c in c1 if c.startswith("seed_")]
    res1 = read_stage(a.out, 1, c1)
    sel = list(csv.DictReader(open(os.path.join(a.out, "step2_selection.csv")))) if os.path.exists(os.path.join(a.out, "step2_selection.csv")) else []
    res2 = {}
    if s2_ok:
        c2 = sorted({r["case"] for r in csv.DictReader(open(os.path.join(a.out, "manifest_stage2.csv")))})
        res2 = read_stage(a.out, 2, c2)
    n = json.load(open(os.path.join(a.out, "n_starts.json")))["n"]
    L = [f"# Lucid grid, S1 + S3 -- version {cfg['version']}", "",
         f"Starts every {cfg['step']} session day(s): **{n} journeys per row**. Stage 1: base, hc25, hc50, top10 and noise runs "
         f"1-{cfg['n1']} -- **{len(c1)} of {len(c1)} cases, all {len(all_ids())} combo x policy rows valid**. Stage 2 (selected "
         f"combos, noise runs {cfg['n1'] + 1}-{cfg['n2']}): **{'complete' if s2_ok else 'NOT complete -- selected combos shown at stage-1 depth'}**.",
         f"Input hash: `{cfg['inputs']}`. Days = calendar days from buying the first eval/account. Shares count every start; "
         "journeys never paid by the data end count as not paid.", ""]

    def cell(by, k, src):
        return noise_median(by, k) if src == "noise" else f_(by["base"], k)

    hdr = ("| e/f | policy | 1st payout | days median (base / noise) | paid <= 6 mo | <= 12 mo | fees mean | fees > payout | "
           "funded losses | net / month | break-even days median | never break even |")
    for pk in PLANS:
        L += [f"## {pk}", "", f"Noise columns: median over {len(seeds1)} stage-1 noise runs.", "", hdr, "|" + "---|" * 12]
        for e, f in combos(pk):
            for pol in POLICIES:
                by = res1[cid(pk, e, f, pol)]
                L.append(f"| {e}/{f} | {pol} | {cell(by, 'r1', 'base'):.0f} | {cell(by, 'd1_median', 'base'):.0f} / "
                         f"{cell(by, 'd1_median', 'noise'):.0f} | {cell(by, 'le6m', 'noise'):.0f}% | {cell(by, 'le12m', 'noise'):.0f}% | "
                         f"{cell(by, 'fees1_mean', 'noise'):.0f} | {cell(by, 'fees1_gt_r1', 'noise'):.0f}% | {cell(by, 'fbr1', 'noise'):.2f} | "
                         f"{cell(by, 'net_per_month', 'noise'):.0f} | {cell(by, 'be_median', 'noise'):.0f} | {cell(by, 'never_be', 'noise'):.1f}% |")
        L.append("")
    L += ["## Selected combos at full depth", "",
          f"Noise median and worst 5% over {'all ' + str(cfg['n2']) if s2_ok else str(len(seeds1)) + ' (stage 2 not complete)'} noise runs.", "",
          "| combo | why | days median (worst 5%) | paid <= 6 mo | <= 12 mo | fees mean | net / month | break-even median | never break even |",
          "|---|---|---|---|---|---|---|---|---|"]
    for s in sel:
        by = dict(res1[s["combo"]])
        if s2_ok:
            by.update(res2.get(s["combo"], {}))
        nz = [r for c, r in by.items() if c.startswith("seed_")]
        g = lambda k, q: pct([f_(r, k) for r in nz if f_(r, k) == f_(r, k)], q)
        L.append(f"| {label(s['combo'])} | {s['why']} | {g('d1_median', .5):.0f} ({g('d1_median', .95):.0f}) | {g('le6m', .5):.0f}% | "
                 f"{g('le12m', .5):.0f}% | {g('fees1_mean', .5):.0f} | {g('net_per_month', .5):.0f} | {g('be_median', .5):.0f} | "
                 f"{g('never_be', .5):.1f}% |  ({len(nz)} runs)")
    L += ["", "## Cross-plan ranking (best combo per plan x size x policy: the selection rule, stage-1 noise medians)", "",
          "| plan | policy | best e/f | rule | net / month | days median | paid <= 12 mo | fees mean | break-even median |",
          "|---|---|---|---|---|---|---|---|---|"]
    for pk in PLANS:
        for pol in POLICIES:
            ranked, why = rank_combos(res1, pk, pol)
            k = ranked[0][0]
            by = res1[k]
            e, f = k.split("|")[1:3]
            L.append(f"| {pk} | {pol} | {e}/{f} | {why} | {noise_median(by, 'net_per_month'):.0f} | {noise_median(by, 'd1_median'):.0f} | "
                     f"{noise_median(by, 'le12m'):.0f}% | {noise_median(by, 'fees1_mean'):.0f} | {noise_median(by, 'be_median'):.0f} |")
    rp = os.path.join(a.out, "repro_check.txt")
    L += ["", "Reproducibility check: " + (open(rp).read().replace("\n", "; ") if os.path.exists(rp) else "not run")]
    fails = [os.path.join(a.out, f"failed_stage{s}.txt") for s in (1, 2)]
    L += ["FAILED cases: " + (" ".join(open(p).read().split() for p in fails if os.path.exists(p)) if any(os.path.exists(p) for p in fails) else "none")]
    with open(os.path.join(a.out, "G1_summary.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(str(x) for x in L) + "\n")
    print(f"wrote {os.path.join(a.out, 'G1_summary.md')}")

# ---------------------------------------------------------------- verify [9.1]
def cmd_verify(a):
    """Pro 25K first payout vs first_payout.py reference journeys (B-16..B-20), every start day."""
    ctx = Ctx(CSV_NQ)
    checks = [("first_payout", 1, 1, "max"), ("first_payout_r500", 1, 1, "500"), ("first_payout_f2", 1, 2, "max"),
              ("first_payout_e2_r1000", 2, 1, "max"), ("first_payout_e2_r500", 2, 1, "500")]
    allok = True
    for case in ("base", f"seed_{a.seed:04d}"):
        s1, s3 = case_paths(case)
        tr = load_trades("s1", s1, "", "") + load_trades("s3", s3, "", "")
        Ss = {n: make_S(ctx, tr, n) for n in (1, 2)}
        memo = Memo(ctx)
        sub = "base" if case == "base" else f"noise/seed_{a.seed:04d}"
        for d, e, f, pol in checks:
            ref = os.path.join(a.ref_dir, d, sub, "journeys.csv")
            if not os.path.exists(ref):
                print(f"skip {d}/{sub}: no reference"); continue
            rows = list(csv.DictReader(open(ref)))
            bad = 0
            for r in rows:
                import datetime
                di = ctx.day_idx[datetime.date.fromisoformat(r["start"]).toordinal()]
                j = journey(memo, Ss[e], Ss[f], "pro_25k", e, f, pol, di)
                fr = j["first"]
                if fr is None:
                    ok = r["result"] != "PAYOUT"
                else:
                    ok = (r["result"] == "PAYOUT" and abs(float(r["cost"]) - fr[2]) < 0.005 and int(r["evals"]) == fr[3]
                          and int(r["resets"]) == fr[4] and int(r["funded_breaches"]) == fr[5]
                          and int(r["trading_days"]) == fr[0] - di + 1 and int(r["calendar_days"]) == ctx.daylist[fr[0]] - ctx.daylist[di])
                if not ok:
                    bad += 1
                    if bad <= 3:
                        print("  mismatch", d, sub, r, fr)
            print(f"{d}/{sub} e{e}/f{f} {pol}: {len(rows) - bad}/{len(rows)} journeys identical")
            allok &= bad == 0
    print("VERIFY", "PASSED" if allok else "FAILED")
    sys.exit(0 if allok else 1)


def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)
    for name in ("driver", "manifest"):
        q = sp.add_parser(name)
        q.add_argument("--version", choices=sorted(VERSIONS), required=True)
        q.add_argument("--out", required=True)
        q.add_argument("--workers", type=int, default=1)
        q.add_argument("--batch-cases", type=int, default=3)
        q.add_argument("--min-free-mb", type=int, default=3000)
    q = sp.add_parser("run"); q.add_argument("--out", required=True); q.add_argument("--batch", required=True)
    q = sp.add_parser("validate"); q.add_argument("--out", required=True); q.add_argument("--stage", type=int, choices=(1, 2), required=True)
    q = sp.add_parser("select"); q.add_argument("--out", required=True)
    q = sp.add_parser("report"); q.add_argument("--out", required=True)
    q = sp.add_parser("verify"); q.add_argument("--ref-dir", default="reports"); q.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    dict(driver=cmd_driver, manifest=cmd_manifest, run=cmd_run, validate=cmd_validate, select=cmd_select, report=cmd_report,
         verify=cmd_verify)[a.cmd](a)


if __name__ == "__main__":
    main()
