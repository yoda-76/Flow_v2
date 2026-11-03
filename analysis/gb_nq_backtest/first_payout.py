"""Cost and time to the FIRST $1,000 Lucid payout -- S1 + S3 on one LucidPro 25K account, one account at a time (offline).

Read-only over trade CSVs and the 1m CSV; writes only under each case's out dir. Places no orders.

    python analysis/gb_nq_backtest/first_payout.py --case OUT S1_TRADES S3_TRADES [--case ...] [--cases-file F]
        [--csv analysis/data/NQ_1m_latest.csv] [--step 1] [--horizon-days 365]

One process loads the 1m bars once and runs every case (memory: ~1 GB). A case whose OUT/summary.txt exists is skipped.

JOURNEY (user, 2026-11-03): buy an eval ($75.60, DLL ON) -> eval FAIL: buy a reset ($75) and start again -> eval PASS: the
funded account starts the next session day -> FIRST PAYOUT ends the journey -> a FUNDED breach before that: buy a new eval
($75.60) and continue. Payout = $1,000 request, received $900 after the 90/10 split.
Rules (docs/Lucid Pro 25K - Complete Rules Report.md, from Lucid's site 2026-11-02; constants from lucid_replay.PLANS["25k"]):
  * eval: target +$1,250; max loss limit $1,000 EOD trailing (locks at $25,100 once a close >= $26,100);
  * DLL $600 soft in eval and funded: closed-today + open worst <= -600 -> flat at that minute, no more trades that day.
    Above $26,100 Lucid replaces it with 60% of the highest EOD profit ("LucidScale"); not modelled -- $600 kept, which is
    the same or stricter once the profit is >= $1,000;
  * first payout, checked at each funded session close: balance - 1,000 >= $26,100 (i.e. balance >= $27,100), profit since
    funding >= $250, largest single day <= 40% of that profit.
Account mechanics, the minute path (bar low/high worst case) and the exclusion of trades straddling a start are those of
prop_multi.py (same functions).

STARTS: a journey from every --step-th session day whose date leaves >= --horizon-days of data afterwards (so late starts
are not cut short); a journey still running at the end of the data is OPEN (reported, excluded from cost/time stats).
CHAIN: journeys back to back from the first day (independent samples) -> mean cost / days with a t-based 95% CI.
"""
import argparse, bisect, csv, math, os, statistics, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lucid_replay import PLANS, Account
from prop_multi import load_trades, load_bars, fast_sess_ord, build_records, attempt_recs, DAY_MS

EVAL_FEE, RESET_FEE, REQUEST, GOAL, CONSIST, SPLIT, DLL = 75.60, 75.0, 1000.0, 250.0, 0.40, 0.90, 600.0
PLAN = PLANS["25k"]
BUFFER = PLAN["lock_bal"]          # 26,100


def run_account(arec, si, funded):
    """-> (res, day_ordinal, day_index); res PASS / FAIL / PAYOUT / OPEN."""
    acc = Account(PLAN)
    start = PLAN["start"]
    cur = cur_di = None
    dstart, locked, today, cyc = start, False, 0.0, {}

    def dayclose(day):
        acc.eod()
        if not funded:
            return False
        cyc[day] = cyc.get(day, 0.0) + today
        tot = sum(cyc.values())
        return acc.bal - REQUEST >= BUFFER and tot >= GOAL and max(cyc.values()) <= CONSIST * tot

    for i in range(si, len(arec)):
        t, sd, di, un, pnl, k, _c = arec[i]
        if sd != cur:
            if cur is not None and dayclose(cur):
                return "PAYOUT", cur, cur_di
            cur, cur_di, dstart, locked, today = sd, di, acc.bal, False, 0.0
        if locked:
            continue
        if acc.bal + un <= acc.mll:
            return "FAIL", sd, di
        if (acc.bal - dstart) + un <= -DLL:
            acc.bal += un; today += un; locked = True
            if acc.bal <= acc.mll:
                return "FAIL", sd, di
            continue
        if pnl != 0.0 or k:
            acc.bal += pnl; today += pnl
            if acc.bal <= acc.mll:
                return "FAIL", sd, di
            if not funded and acc.bal - start >= PLAN["target"]:
                return "PASS", sd, di
    if cur is not None and dayclose(cur):
        return "PAYOUT", cur, cur_di
    return "OPEN", cur, cur_di


def journey(SE, SF, start_di, ctx):
    """SE = records at the eval size, SF = at the funded size."""
    daylist = ctx["daylist"]
    cost, evals, resets, fbr, mode, di = EVAL_FEE, 1, 0, 0, "eval", start_di
    eval_days = 0
    while di < len(daylist):
        S = SF if mode == "funded" else SE
        arec, si = attempt_recs(S, di, daylist, ctx["bars"], ctx["day_idx"], DLL)
        if si >= len(arec):
            break
        res, sd, edi = run_account(arec, si, mode == "funded")
        if res == "OPEN" or edi is None:
            break
        if res == "PAYOUT":
            return dict(result="PAYOUT", cost=cost, evals=evals, resets=resets, fbreach=fbr, end_di=edi,
                        tdays=edi - start_di + 1, cdays=daylist[edi] - daylist[start_di])
        if res == "FAIL":
            if mode == "eval":
                resets += 1; cost += RESET_FEE
            else:
                fbr += 1; evals += 1; cost += EVAL_FEE; mode = "eval"
        else:                                  # PASS
            mode = "funded"
        di = edi + 1
    return dict(result="OPEN", cost=cost, evals=evals, resets=resets, fbreach=fbr, end_di=None, tdays=None, cdays=None)


def pct(v, q):
    v = sorted(v)
    if not v:
        return float("nan")
    k = (len(v) - 1) * q
    f = math.floor(k)
    return v[f] if f == k else v[f] + (v[f + 1] - v[f]) * (k - f)


def ci_mean(v):
    if len(v) < 2:
        return (float("nan"), float("nan"))
    m, s = statistics.mean(v), statistics.stdev(v)
    h = 1.96 * s / math.sqrt(len(v))
    return (m - h, m + h)


def run_case(out, s1, s3, ctx, a):
    t0 = time.time()
    os.makedirs(out, exist_ok=True)
    tr = load_trades("s1", s1, "", "") + load_trades("s3", s3, "", "")
    for r in tr:
        r["bk"], r["m"], r["q"] = a.csv, a.mult, 1
    def mk(trs):
        rc, _mx, _m = build_records(trs, ctx["bars"], ctx["day_idx"], ctx["daylist"])
        return dict(trades=trs, recs=rc, win={}, RT=[r[0] for r in rc], SDs=[r[1] for r in rc])
    S = mk(tr)
    recs = S["recs"]
    # pnl_usd per file is for 1 contract incl. its commission -> scales linearly with size
    sized = lambda n: S if n == 1 else mk([dict(r, q=n, pnl=r["pnl"] * n) for r in tr])
    SE, SF = sized(a.eval_qty), sized(a.funded_qty)
    daylist = ctx["daylist"]
    first = recs[0][2]
    last_ok = daylist[-1] - a.horizon_days
    rows = []
    for di in range(first, len(daylist), a.step):
        if daylist[di] > last_ok:
            break
        j = journey(SE, SF, di, ctx)
        j["start"] = daylist[di]
        rows.append(j)
    chain, di = [], first
    while di < len(daylist):
        j = journey(SE, SF, di, ctx)
        if j["result"] != "PAYOUT":
            break
        chain.append(j)
        di = j["end_di"] + 1
    iso = lambda o: __import__("datetime").date.fromordinal(o).isoformat()
    with open(os.path.join(out, "journeys.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["start", "result", "cost", "evals", "resets", "funded_breaches", "trading_days", "calendar_days"])
        for j in rows:
            w.writerow([iso(j["start"]), j["result"], f"{j['cost']:.2f}", j["evals"], j["resets"], j["fbreach"],
                        j["tdays"] if j["tdays"] is not None else "", j["cdays"] if j["cdays"] is not None else ""])
    done = [j for j in rows if j["result"] == "PAYOUT"]
    cost = [j["cost"] for j in done]
    cd = [j["cdays"] for j in done]
    td = [j["tdays"] for j in done]
    L = [f"first_payout: s1={s1} s3={s3} step={a.step} horizon={a.horizon_days}d eval_qty={a.eval_qty} funded_qty={a.funded_qty} request={REQUEST:.0f}",
         f"journeys {len(rows)}  reached payout {len(done)}  open at data end {len(rows) - len(done)}"]
    if done:
        L += [f"cost   mean {statistics.mean(cost):.2f} median {statistics.median(cost):.2f} p90 {pct(cost, .9):.2f} max {max(cost):.2f}",
              f"net    mean {REQUEST * SPLIT - statistics.mean(cost):.2f}",
              f"cdays  mean {statistics.mean(cd):.1f} median {statistics.median(cd):.1f} p90 {pct(cd, .9):.1f} max {max(cd)}",
              f"tdays  mean {statistics.mean(td):.1f} median {statistics.median(td):.1f} p90 {pct(td, .9):.1f} max {max(td)}",
              f"resets mean {statistics.mean(j['resets'] for j in done):.2f}  funded_breaches mean {statistics.mean(j['fbreach'] for j in done):.2f}",
              "budget " + " ".join(f"<={b}:{100 * sum(c <= b + 1e-9 for c in cost) / len(rows):.1f}%" for b in (75.6, 150.6, 300.6, 500.6, 900)),
              "within " + " ".join(f"{m}mo:{100 * sum(x <= m * 30.4 for x in cd) / len(rows):.1f}%" for m in (2, 4, 6, 12)),
              f"cost>900 {100 * sum(c > 900 for c in cost) / len(rows):.1f}%"]
    if chain:
        cc, cdd = [j["cost"] for j in chain], [j["cdays"] for j in chain]
        lo, hi = ci_mean(cc)
        lo2, hi2 = ci_mean(cdd)
        L.append(f"chain  n {len(chain)}  cost mean {statistics.mean(cc):.2f} [{lo:.2f}, {hi:.2f}]  cdays mean "
                 f"{statistics.mean(cdd):.1f} [{lo2:.1f}, {hi2:.1f}]")
    L.append(f"runtime {time.time() - t0:.1f}s")
    with open(os.path.join(out, "summary.txt"), "w") as fh:
        fh.write("\n".join(L) + "\n")
    print(f"[{time.strftime('%H:%M:%S')}] {out}: {L[1]} ({time.time() - t0:.0f}s)", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--case", nargs=3, action="append", default=[], metavar=("OUT", "S1", "S3"))
    ap.add_argument("--cases-file", default="", help="lines: OUT S1 S3")
    ap.add_argument("--csv", default="analysis/data/NQ_1m_latest.csv")
    ap.add_argument("--mult", type=float, default=2.0)
    ap.add_argument("--step", type=int, default=1)
    ap.add_argument("--request", type=float, default=1000.0, help="first payout request ($500 minimum, $1,000 max for payout 1)")
    ap.add_argument("--eval-qty", type=int, default=1, help="contracts per strategy in the eval phase")
    ap.add_argument("--funded-qty", type=int, default=1, help="contracts per strategy in the funded phase (eval stays at 1)")
    ap.add_argument("--horizon-days", type=int, default=365)
    a = ap.parse_args()
    global REQUEST
    REQUEST = a.request
    cases = list(a.case)
    if a.cases_file:
        cases += [l.split() for l in open(a.cases_file) if l.strip()]
    cases = [c for c in cases if not os.path.exists(os.path.join(c[0], "summary.txt"))]
    if not cases:
        return
    bars = {a.csv: load_bars(a.csv, 0, 4102444800000)}
    daylist = sorted({fast_sess_ord(t) for t in bars[a.csv][0]})
    ctx = dict(bars=bars, daylist=daylist, day_idx={o: i for i, o in enumerate(daylist)})
    for out, s1, s3 in cases:
        run_case(out, s1, s3, ctx, a)


if __name__ == "__main__":
    main()
