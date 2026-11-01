"""Test of the rule: "if Friday's high is lower than Thursday's high, Friday's low will be visited on Monday."

Part 1 (the claim): P(Monday session trades at or below Friday's low) when Fri high < Thu high, against the base rate when it is not.
Part 2 (a trade version, one possible reading of the rule): on Monday, short with a sell-stop at Friday's low; protective stop at
Friday's high; exit at the Monday session close. 1 contract, 1 tick slippage, commission per round trip.

Sessions are CME trading days (roll at 17:00 ET), so Monday's session starts Sunday 18:00 ET. Read-only; places no orders.

    python friday_low.py --csv analysis/data/GC_1m_latest.csv --days 365
"""
import argparse, math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lucid_replay import session_day, utc

DAY_MS = 86400_000
INST = {"MGC": dict(mult=10.0, tick=0.1, comm=1.0), "GC": dict(mult=100.0, tick=0.1, comm=4.60),
        "MNQ": dict(mult=2.0, tick=0.25, comm=1.0), "NQ": dict(mult=20.0, tick=0.25, comm=4.60)}


def load(path):
    T, O, H, L, C, S = [], [], [], [], [], []
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.split(",")
            t = int(p[0])
            T.append(t); O.append(float(p[1])); H.append(float(p[2])); L.append(float(p[3])); C.append(float(p[4]))
    S = [session_day(t) for t in T]
    rng, i = {}, 0
    n = len(T)
    while i < n:
        j = i
        while j + 1 < n and S[j + 1] == S[i]:
            j += 1
        rng[S[i]] = (i, j)
        i = j + 1
    return T, O, H, L, C, rng


def pct(k, n): return f"{k}/{n} = {k / n:.0%}" if n else "n/a"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", default="analysis/data/GC_1m_latest.csv")
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--syms", default="MGC,GC", help="instruments to trade, comma separated: MGC,GC (gold file) or MNQ,NQ (NQ file)")
    a = ap.parse_args()
    syms = {k: INST[k] for k in a.syms.split(",")}
    T, O, H, L, C, rng = load(a.csv)
    days = sorted(d for d, (i, j) in rng.items() if j - i + 1 >= 60)
    ds = set(days)
    sh = {d: max(H[rng[d][0]:rng[d][1] + 1]) for d in days}
    sl = {d: min(L[rng[d][0]:rng[d][1] + 1]) for d in days}
    last_day = days[-1]
    import datetime as dt
    cut = session_day(T[-1] - a.days * DAY_MS)

    rows = []          # (friday, monday, cond, hit)
    for fri in days:
        if fri.weekday() != 4:
            continue
        thu, mon = fri - dt.timedelta(1), fri + dt.timedelta(3)
        if thu not in ds or mon not in ds:
            continue
        rows.append((fri, mon, sh[fri] < sh[thu], sl[mon] <= sl[fri]))

    for label, win in (("LAST " + str(a.days) + " DAYS", [r for r in rows if r[0] >= cut]), ("ALL DATA", rows)):
        c = [r for r in win if r[2]]
        n = [r for r in win if not r[2]]
        print(f"=== {label}: {len(win)} Fridays ({win[0][0]} -> {win[-1][0]})")
        print(f"  Fri high < Thu high : Monday trades <= Friday low  {pct(sum(r[3] for r in c), len(c))}")
        print(f"  Fri high >= Thu high: Monday trades <= Friday low  {pct(sum(r[3] for r in n), len(n))}")
        if c and n:
            p1, p2 = sum(r[3] for r in c) / len(c), sum(r[3] for r in n) / len(n)
            p = (sum(r[3] for r in win)) / len(win)
            se = math.sqrt(p * (1 - p) * (1 / len(c) + 1 / len(n))) or 1
            print(f"  difference {p1 - p2:+.0%}  (z = {(p1 - p2) / se:+.2f}; |z| < 2 means it is not distinguishable from chance)")
        print()

    # ---- Part 2: trade version
    print("=== TRADE VERSION: Monday sell-stop at Friday's low, stop at Friday's high, out at the Monday close (only when Fri high < Thu high)")
    for sym, ins in syms.items():
        mult, tick, comm = ins["mult"], ins["tick"], ins["comm"]
        for label, win in (("last " + str(a.days) + "d", [r for r in rows if r[0] >= cut and r[2]]), ("all data", [r for r in rows if r[2]])):
            pnl, wins, stops = [], 0, 0
            for fri, mon, _, _ in win:
                i0, i1 = rng[mon]
                entry_px = stop_px = None
                fl, fh = sl[fri], sh[fri]
                res = None
                for i in range(i0, i1 + 1):
                    if entry_px is None:
                        if L[i] <= fl:
                            entry_px = min(fl, O[i]) - tick
                            if H[i] >= fh:
                                res = (fh + tick) - entry_px; stops += 1; break
                    else:
                        if H[i] >= fh:
                            res = (fh + tick) - entry_px; stops += 1; break
                if entry_px is not None and res is None:
                    res = (C[i1] + tick) - entry_px          # short covered at the session close (adverse tick)
                if entry_px is None:
                    continue
                p_usd = -res * mult - comm
                pnl.append(p_usd); wins += p_usd > 0
            net = sum(pnl)
            eq = peak = mdd = 0.0
            for v in pnl:
                eq += v; peak = max(peak, eq); mdd = max(mdd, peak - eq)
            print(f"  {sym:<3} {label:<9} trades {len(pnl):>3} of {len(win)} signal Mondays  win {wins / len(pnl):.0%}  net ${net:+,.0f}  "
                  f"avg ${net / len(pnl):+,.0f}/trade  max DD ${mdd:,.0f}  stops hit {stops}" if pnl else f"  {sym} {label}: no trades")

    print("\n=== TRADE VERSION B: short at the Monday open, target = Friday's low, stop = Friday's high, else out at the Monday close")
    for sym, ins in syms.items():
        for label, win in (("last " + str(a.days) + "d", [r for r in rows if r[0] >= cut and r[2]]), ("all data", [r for r in rows if r[2]])):
            pnl, tgt, stp = open_to_low(rng, O, H, L, C, sh, sl, win, ins, ins["tick"])
            if not pnl:
                print(f"  {sym} {label}: no trades"); continue
            eq = peak = mdd = 0.0
            for v in pnl:
                eq += v; peak = max(peak, eq); mdd = max(mdd, peak - eq)
            print(f"  {sym:<3} {label:<9} trades {len(pnl):>3} of {len(win)}  win {sum(v > 0 for v in pnl) / len(pnl):.0%}  net ${sum(pnl):+,.0f}  "
                  f"avg ${sum(pnl) / len(pnl):+,.0f}/trade  max DD ${mdd:,.0f}  target hit {tgt}, stop hit {stp}")


def open_to_low(rng, O, H, L, C, sh, sl, rows, ins, tick):
    """Variant B: short at the Monday session open (first 1m open - 1 tick), target = Friday's low, stop = Friday's high,
    otherwise out at the Monday close. Skipped when Monday already opens at/below Friday's low."""
    mult, comm = ins["mult"], ins["comm"]
    pnl, tgt, stp = [], 0, 0
    for fri, mon, _, _ in rows:
        i0, i1 = rng[mon]
        fl, fh = sl[fri], sh[fri]
        if O[i0] <= fl or O[i0] >= fh:
            continue
        entry = O[i0] - tick
        exit_px = None
        for i in range(i0, i1 + 1):
            if H[i] >= fh:                      # stop checked first inside a bar
                exit_px = fh + tick; stp += 1; break
            if L[i] <= fl - tick:               # target needs one tick of trade-through
                exit_px = fl; tgt += 1; break
        if exit_px is None:
            exit_px = C[i1] + tick
        pnl.append((entry - exit_px) * mult - comm)
    return pnl, tgt, stp



if __name__ == "__main__":
    main()
