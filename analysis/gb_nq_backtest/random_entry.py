#!/usr/bin/env python3
"""Random-entry Monte Carlo benchmark: does a strategy's ENTRY SIGNAL beat random entries with the same exits?

Offline, stdlib only, read-only (writes only under --out). Never touches orders or .env.

Modes: time = random entry minute (own date/side/TP-SL distances); side = own minute, random side;
both = random minute and random side.  See --help.
"""
import argparse
import bisect
import csv
import datetime
import os
import random
import statistics
import sys
import time
from datetime import date, timedelta, timezone

H_MS = 3600000
DAY_MS = 86400000
EPOCH_ORD = date(1970, 1, 1).toordinal()
TICK = 0.25


def build_ct_transitions():
    tr = [(-(1 << 62), -6 * H_MS)]
    for y in range(2017, 2031):
        d = date(y, 3, 1); d += timedelta((6 - d.weekday()) % 7 + 7)       # 2nd Sunday of March
        s = datetime.datetime(y, 3, d.day, 8, 0, tzinfo=timezone.utc)
        d = date(y, 11, 1); d += timedelta((6 - d.weekday()) % 7)          # 1st Sunday of November
        e = datetime.datetime(y, 11, d.day, 7, 0, tzinfo=timezone.utc)
        tr.append((int(s.timestamp() * 1000), -5 * H_MS))
        tr.append((int(e.timestamp() * 1000), -6 * H_MS))
    tr.sort()
    return [t for t, _ in tr], [o for _, o in tr]


TR_T, TR_O = build_ct_transitions()


def ct_local(ts):
    """UTC ms -> (CT day number since epoch, CT minute of day)."""
    off = TR_O[bisect.bisect_right(TR_T, ts) - 1]
    loc = ts + off
    return loc // DAY_MS, (loc % DAY_MS) // 60000


def ct_str_to_dm(s):
    """'YYYY-MM-DD HH:MM' (CT) -> (CT day number, minute of day). No UTC round trip needed."""
    dn = date(int(s[0:4]), int(s[5:7]), int(s[8:10])).toordinal() - EPOCH_ORD
    return dn, int(s[11:13]) * 60 + int(s[14:16])


def load_days(path):
    """-> {dn: (m_list, o, h, l, c)} sorted by time within the CT day."""
    rows = {}
    with open(path, newline="") as f:
        rd = csv.reader(f)
        next(rd)
        for r in rd:
            try:
                ts = int(r[0])
            except ValueError:
                continue
            rows[ts] = (float(r[1]), float(r[2]), float(r[3]), float(r[4]))
    days = {}
    for ts in sorted(rows):
        dn, m = ct_local(ts)
        o, h, l, c = rows[ts]
        D = days.get(dn)
        if D is None:
            D = days[dn] = ([], [], [], [], [])
        D[0].append(m); D[1].append(o); D[2].append(h); D[3].append(l); D[4].append(c)
    return days


def classify(reason):
    r = (reason or "").lower()
    if "tp" in r or "target" in r or "profit" in r:
        return "tp"
    if "sl" in r or "stop" in r:
        return "sl"
    return "time"


def simulate(D, i, side, tpd, sld, slip, time_exit, mult, comm):
    """One synthetic trade entered at bar index i of day tuple D. -> (pnl, reason)."""
    M, O, Hh, L, C = D
    fill = O[i] + side * slip
    tp = fill + side * tpd
    sl = fill - side * sld
    n = len(M)
    j = i
    ex = None
    reason = "time"
    if side > 0:
        while j < n and M[j] < time_exit:
            o = O[j]
            if o <= sl:
                ex = o - slip; reason = "sl"; break
            if o >= tp:
                ex = o; reason = "tp"; break
            if L[j] <= sl:
                ex = sl - slip; reason = "sl"; break
            if Hh[j] >= tp:
                ex = tp; reason = "tp"; break
            j += 1
    else:
        while j < n and M[j] < time_exit:
            o = O[j]
            if o >= sl:
                ex = o + slip; reason = "sl"; break
            if o <= tp:
                ex = o; reason = "tp"; break
            if Hh[j] >= sl:
                ex = sl + slip; reason = "sl"; break
            if L[j] <= tp:
                ex = tp; reason = "tp"; break
            j += 1
    if ex is None:
        if j < n:
            ex = O[j] - side * slip                      # open of first bar at/after time_exit
        else:
            ex = C[n - 1] - side * slip                  # day ended first: last close
    return side * (ex - fill) * mult - comm, reason


def stats(pnls):
    n = len(pnls)
    if n == 0:
        return dict(n=0, mean=0.0, win=0.0, pf=0.0, net=0.0)
    gw = sum(p for p in pnls if p > 0)
    gl = -sum(p for p in pnls if p < 0)
    net = sum(pnls)
    return dict(n=n, mean=net / n, win=100.0 * sum(1 for p in pnls if p > 0) / n,
                pf=(gw / gl if gl > 0 else float("inf")), net=net)


def pct(sorted_v, q):
    if not sorted_v:
        return float("nan")
    k = (len(sorted_v) - 1) * q
    lo = int(k); hi = min(lo + 1, len(sorted_v) - 1)
    return sorted_v[lo] + (sorted_v[hi] - sorted_v[lo]) * (k - lo)


def dist(vals):
    s = sorted(vals)
    return "mean %.2f  sd %.2f  p5 %.2f  p50 %.2f  p95 %.2f  min %.2f  max %.2f" % (
        statistics.fmean(s), statistics.pstdev(s) if len(s) > 1 else 0.0,
        pct(s, .05), pct(s, .5), pct(s, .95), s[0], s[-1])


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--trades", required=True)
    ap.add_argument("--csv", default="analysis/data/NQ_1m_latest.csv")
    ap.add_argument("--window-start", type=int, required=True)
    ap.add_argument("--window-end", type=int, required=True)
    ap.add_argument("--grid", type=int, default=1)
    ap.add_argument("--time-exit", type=int, required=True)
    ap.add_argument("--mult", type=float, default=20.0)
    ap.add_argument("--comm", type=float, default=4.60)
    ap.add_argument("--slip-ticks", type=float, default=1.0)
    ap.add_argument("--n", type=int, default=500)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--mode", choices=["time", "side", "both"], default="time")
    ap.add_argument("--by-period", action="store_true")
    ap.add_argument("--date-pool", choices=["own", "all"], default="own",
                    help="own = the trade's own date; all = uniform random date among all full CT days in the file's trade-date range")
    ap.add_argument("--after-actual", action="store_true",
                    help="(time mode, own pool) random minute only strictly after the actual entry minute")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.date_pool == "all" and a.mode == "side":
        sys.exit("--date-pool all needs --mode time or both")
    if a.after_actual and (a.mode != "time" or a.date_pool != "own"):
        sys.exit("--after-actual needs --mode time and --date-pool own")
    t0 = time.time()
    slip = a.slip_ticks * TICK

    days = load_days(a.csv)

    # ---- trades
    T = []
    with open(a.trades, newline="") as f:
        for r in csv.DictReader(f):
            entry = float(r["entry_px"] if r.get("entry_px") not in (None, "") else r["fill"])
            side = 1
            if r.get("side"):
                side = 1 if r["side"].strip().upper().startswith(("L", "B")) or r["side"].strip() == "1" else -1
            dn, m = ct_str_to_dm(r["entry_time_ct"])
            T.append(dict(dn=dn, m=m, side=side, tpd=abs(float(r["tp"]) - entry), sld=abs(entry - float(r["sl"])),
                          pnl=float(r["pnl_usd"]), reason=classify(r.get("exit_reason")),
                          early=(date.fromordinal(dn + EPOCH_ORD) < date(2023, 1, 1))))
    if not T:
        sys.exit("no trades")

    # candidate entry bar indices per day (window + grid)
    cand = {}
    for t in T:
        dn = t["dn"]
        if dn in cand:
            continue
        D = days.get(dn)
        cand[dn] = [] if D is None else [i for i, m in enumerate(D[0])
                                         if a.window_start <= m <= a.window_end and m % a.grid == 0]

    # pool of eligible dates for --date-pool all
    pool = []
    if a.date_pool == "all":
        lo = min(t["dn"] for t in T); hi = max(t["dn"] for t in T)
        for dn in sorted(days):
            if dn < lo or dn > hi:
                continue
            D = days[dn]
            if dn not in cand:
                cand[dn] = [i for i, m in enumerate(D[0]) if a.window_start <= m <= a.window_end and m % a.grid == 0]
            if cand[dn] and D[0][-1] >= a.time_exit:
                pool.append(dn)
        if not pool:
            sys.exit("empty date pool")

    out = []
    def emit(s=""):
        out.append(s); print(s)

    # ---- actual
    act = stats([t["pnl"] for t in T])
    emit("random_entry.py  trades=%s  mode=%s  n=%d seed=%d" % (a.trades, a.mode, a.n, a.seed))
    emit("window %d..%d CT-min grid %d, time-exit %d, mult %g comm %.2f slip %g ticks" % (
        a.window_start, a.window_end, a.grid, a.time_exit, a.mult, a.comm, a.slip_ticks))
    emit("")
    def line(lbl, s):
        return "%-22s n %5d  mean %8.2f  win %5.1f%%  PF %5.2f  net %12.2f" % (lbl, s["n"], s["mean"], s["win"], s["pf"], s["net"])
    emit(line("ACTUAL (file pnl)", act))
    if a.by_period:
        emit(line("  actual < 2023", stats([t["pnl"] for t in T if t["early"]])))
        emit(line("  actual >= 2023", stats([t["pnl"] for t in T if not t["early"]])))

    # ---- sanity: re-simulate own entries
    own = []; match = 0; checked = 0; missing = 0
    for t in T:
        D = days.get(t["dn"])
        if D is None:
            missing += 1; continue
        k = bisect.bisect_left(D[0], t["m"])
        if k >= len(D[0]) or D[0][k] != t["m"]:
            missing += 1; continue
        p, rs = simulate(D, k, t["side"], t["tpd"], t["sld"], slip, a.time_exit, a.mult, a.comm)
        own.append(p); checked += 1
        match += (rs == t["reason"])
    so = stats(own)
    emit("")
    emit("SANITY own entries re-simulated: n %d (no-bar %d)  mean %.2f (file mean %.2f)  exit_reason matches %d/%d" % (
        checked, missing, so["mean"], act["mean"], match, checked))

    # ---- shuffles
    rng = random.Random(a.seed)
    rows = []
    early_means = []; late_means = []
    skipped = 0
    skipped_after = 0
    for s in range(a.n):
        pn = []; pe = []; pl = []
        for t in T:
            D = days.get(t["dn"])
            if a.date_pool == "all":
                dn2 = pool[rng.randrange(len(pool))]
                D = days[dn2]
                c = cand[dn2]
                k = c[rng.randrange(len(c))]
            elif a.after_actual:
                c = [i for i in (cand.get(t["dn"]) or []) if D[0][i] > t["m"]]
                if not c:
                    skipped_after += 1; continue
                k = c[rng.randrange(len(c))]
            elif a.mode == "side":
                if D is None:
                    skipped += 1; continue
                k = bisect.bisect_left(D[0], t["m"])
                if k >= len(D[0]) or D[0][k] != t["m"]:
                    skipped += 1; continue
            else:
                c = cand.get(t["dn"])
                if not c:
                    skipped += 1; continue
                k = c[rng.randrange(len(c))]
            side = t["side"] if a.mode == "time" else (1 if rng.random() < 0.5 else -1)
            p, _ = simulate(D, k, side, t["tpd"], t["sld"], slip, a.time_exit, a.mult, a.comm)
            pn.append(p)
            (pe if t["early"] else pl).append(p)
        st = stats(pn)
        rows.append((st["mean"], st["net"], st["pf"]))
        early_means.append(statistics.fmean(pe) if pe else 0.0)
        late_means.append(statistics.fmean(pl) if pl else 0.0)

    means = [r[0] for r in rows]; nets = [r[1] for r in rows]; pfs = [r[2] for r in rows if r[2] != float("inf")]
    emit("")
    emit("SHUFFLES: %d, skipped synthetic trades (no bar on date/window): %d total (%.1f per shuffle)" % (
        a.n, skipped, skipped / max(a.n, 1)))
    if a.date_pool == "all":
        emit("date-pool all: %d eligible CT dates (trade-date range, window bars on grid, bar at/after time-exit)" % len(pool))
    if a.after_actual:
        emit("after-actual: skipped (no grid minute after actual entry): %d total (%.1f per shuffle, of %d trades)" % (
            skipped_after, skipped_after / max(a.n, 1), len(T)))
    emit("mean pnl/trade : " + dist(means))
    emit("net           : " + dist(nets))
    emit("PF            : " + (dist(pfs) if pfs else "n/a"))
    k = sum(1 for m in means if m >= act["mean"])
    emit("p-value (shuffle mean >= actual %.2f): %d/%d -> (k+1)/(n+1) = %.4f" % (act["mean"], k, a.n, (k + 1) / (a.n + 1)))
    if a.by_period:
        for lbl, flag, arr in (("< 2023", True, early_means), ("from 2023", False, late_means)):
            am = stats([t["pnl"] for t in T if t["early"] == flag])["mean"]
            kk = sum(1 for m in arr if m >= am)
            emit("period %-9s actual mean %8.2f | shuffle mean %s | p %.4f" % (
                lbl, am, dist(arr), (kk + 1) / (a.n + 1)))
    emit("runtime %.1fs" % (time.time() - t0))

    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "summary.txt"), "w") as f:
        f.write("\n".join(out) + "\n")
    with open(os.path.join(a.out, "shuffles.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["shuffle", "mean_pnl", "net", "pf"])
        for i, r in enumerate(rows):
            w.writerow([i, "%.4f" % r[0], "%.2f" % r[1], "%.4f" % r[2]])


if __name__ == "__main__":
    main()
