"""Parity check: Java BarReplay trade list vs the Python backtest trade list (stdlib only).

compare:   python analysis/parity_compare.py compare --java DIR/trades.csv --python reports/prop3/q1/s1/trades.csv
                  [--from 2019-10-01] [--to YYYY-MM-DD] [--show 20] [--tol-min 0]
           Trades are matched by (CT entry date, entry minute, side). Python exit reasons map TIME -> EXIT (the Java
           strategies return flat on the last bar before the time exit; BarReplay exits at the next open). Reports
           matched / only-Java / only-Python, exit-reason agreement, mean |P&L diff| over matched trades, the net of
           each list, and the first N mismatches.

skipdates: python analysis/parity_compare.py skipdates --csv analysis/data/NQ_1m_latest.csv --out cfg.json [--from ..]
           Writes a strategy config {"skipDates": "..."} listing the CT dates the Python backtests skip as "not full"
           (a session with bars before 16:00 CT but none at/after 14:30 CT: holidays and early closes). The live
           strategies cannot look ahead, so they only skip such days when told to (see their javadoc).
"""
import argparse
import csv
import os
import sys
from datetime import date, timedelta

REASON_MAP = {"TIME": "EXIT"}


def _ct():
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "gb_nq_backtest"))
    import vault_break as vb
    return vb


def _stamp(vb, ms):
    dn, m = vb.ct_local(ms)
    return "%s %02d:%02d" % (vb.day_to_date(dn).isoformat(), m // 60, m % 60)


def load_avwap(path):
    """anchored_vwap.py trades.csv: entry_t / exit_t are UTC ms (the signal bar's END), side is +1 / -1, reason triangle / data_end."""
    vb = _ct()
    out = []
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            et = _stamp(vb, int(r["entry_t"]))
            xr = {"triangle": "EXIT", "data_end": "DATA_END"}.get(r["reason"], r["reason"])
            out.append(dict(date=et[:10], minute=et[11:16], side="L" if int(r["side"]) > 0 else "S",
                            entry_px=float(r["entry"]), exit_px=float(r["entry"]), exit_reason=xr,
                            exit_time=_stamp(vb, int(r["exit_t"])), entry_time=et, pnl=float(r["pnl_usd"])))
    return out


def load(path, is_python):
    out = []
    with open(path, newline="") as f:
        head = f.readline()
    if is_python and head.startswith("entry_t"):
        return load_avwap(path)
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            side = r.get("side") or "L"
            entry = r["entry_time_ct"]
            xr = r["exit_reason"]
            if is_python:
                xr = REASON_MAP.get(xr, xr)
            out.append(dict(date=r["date"], minute=entry[11:16], side=side, entry_px=float(r.get("entry_px") or r.get("fill")),
                            exit_px=float(r["exit_px"]), exit_reason=xr, exit_time=r["exit_time_ct"], entry_time=entry,
                            pnl=float(r["pnl_usd"])))
    return out


def key(t):
    return (t["date"], t["minute"], t["side"])


def compare(a):
    jv = load(a.java, False)
    py = load(a.python, True)
    lo = a.from_date or "0000-00-00"
    hi = a.to or "9999-99-99"
    jv = [t for t in jv if lo <= t["date"] <= hi]
    if a.drop_gap_roundtrips:
        # a live entry whose fill is already beyond the TP/SL (the Python skips these signals by looking at the next open)
        # becomes a one-bar GAP_TP / GAP_SL round trip in BarReplay; list and exclude them
        gaps = [t for t in jv if t["exit_reason"].startswith("GAP_") and t["entry_time"] == t["exit_time"]]
        print("java same-bar GAP round trips excluded (equivalent of the Python gap skip): %d, net $%.2f" % (
            len(gaps), sum(t["pnl"] for t in gaps)))
        jv = [t for t in jv if t not in gaps]
    py = [t for t in py if lo <= t["date"] <= hi]
    jm = {}
    for t in jv:
        jm.setdefault(key(t), t)
    pm = {}
    for t in py:
        pm.setdefault(key(t), t)
    matched = [k for k in jm if k in pm]
    only_j = [k for k in jm if k not in pm]
    only_p = [k for k in pm if k not in jm]
    same_exit = sum(1 for k in matched if jm[k]["exit_reason"] == pm[k]["exit_reason"])
    diffs = [abs(jm[k]["pnl"] - pm[k]["pnl"]) for k in matched]
    print("java trades=%d python trades=%d (entry dates %s .. %s)" % (len(jv), len(py), lo, hi))
    print("matched=%d (%.2f%% of python)  only-java=%d  only-python=%d" % (
        len(matched), 100.0 * len(matched) / max(1, len(py)), len(only_j), len(only_p)))
    print("matched with identical exit reason: %d (%.2f%% of python)  identical-within-matched: %.2f%%" % (
        same_exit, 100.0 * same_exit / max(1, len(py)), 100.0 * same_exit / max(1, len(matched))))
    print("mean |pnl diff| over matched = $%.3f   max = $%.2f" % (sum(diffs) / max(1, len(diffs)), max(diffs or [0])))
    print("net java = $%.2f   net python = $%.2f   (matched only: java $%.2f python $%.2f)" % (
        sum(t["pnl"] for t in jv), sum(t["pnl"] for t in py),
        sum(jm[k]["pnl"] for k in matched), sum(pm[k]["pnl"] for k in matched)))
    rs = {}
    for k in matched:
        if jm[k]["exit_reason"] != pm[k]["exit_reason"]:
            p = (pm[k]["exit_reason"], jm[k]["exit_reason"])
            rs[p] = rs.get(p, 0) + 1
    if rs:
        print("exit-reason disagreements (python -> java): " + ", ".join("%s->%s:%d" % (p[0], p[1], n) for p, n in sorted(rs.items())))
    # an only-java / only-python pair on the same CT date and side is one trade whose ENTRY BAR differs (the Python fills
    # at the first existing bar of the next 15/30m bucket; live fills at the next 1m bar after the signal bar -- they
    # differ when 1m bars are missing, which is common in the thin 2019-2021 data)
    jg = {}
    for k in only_j:
        jg.setdefault((k[0], k[2]), []).append(k)
    pg = {}
    for k in only_p:
        pg.setdefault((k[0], k[2]), []).append(k)
    paired = [(jg[g][0], pg[g][0]) for g in jg if g in pg and len(jg[g]) == 1 and len(pg[g]) == 1]
    print("only-java/only-python pairs on the same date+side (same signal, different entry bar): %d  -> unexplained only-java=%d only-python=%d" % (
        len(paired), len(only_j) - len(paired), len(only_p) - len(paired)))
    if paired:
        print("   paired: mean |pnl diff| $%.2f; same exit reason %d/%d" % (
            sum(abs(jm[a]["pnl"] - pm[b]["pnl"]) for a, b in paired) / len(paired),
            sum(1 for a, b in paired if jm[a]["exit_reason"] == pm[b]["exit_reason"]), len(paired)))
    rows = []
    for k in matched:
        if jm[k]["exit_reason"] != pm[k]["exit_reason"] or abs(jm[k]["pnl"] - pm[k]["pnl"]) > a.pnl_tol:
            rows.append(("DIFF", k, "py %s %s @%s pnl %.2f | java %s %s @%s pnl %.2f" % (
                pm[k]["exit_reason"], pm[k]["exit_px"], pm[k]["exit_time"], pm[k]["pnl"],
                jm[k]["exit_reason"], jm[k]["exit_px"], jm[k]["exit_time"], jm[k]["pnl"])))
    for k in only_j:
        rows.append(("ONLY-JAVA", k, "%s %s pnl %.2f" % (jm[k]["exit_reason"], jm[k]["exit_px"], jm[k]["pnl"])))
    for k in only_p:
        rows.append(("ONLY-PYTHON", k, "%s %s pnl %.2f" % (pm[k]["exit_reason"], pm[k]["exit_px"], pm[k]["pnl"])))
    rows.sort(key=lambda r: r[1])
    print("first %d mismatches (of %d; a DIFF is a different exit reason or |pnl diff| > $%.2f):" % (
        min(a.show, len(rows)), len(rows), a.pnl_tol))
    for kind, k, txt in rows[:a.show]:
        print("  %-11s %s %s %s  %s" % (kind, k[0], k[1], k[2], txt))
    if a.dump:
        with open(a.dump, "w") as f:
            for kind, k, txt in rows:
                f.write("%s %s %s %s %s\n" % (kind, k[0], k[1], k[2], txt))


def skipdates(a):
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "gb_nq_backtest"))
    import vault_break as vb
    has_bar = {}
    full = {}
    with open(a.csv, newline="") as f:
        rd = csv.reader(f)
        next(rd)
        for r in rd:
            try:
                ts = int(r[0])
            except ValueError:
                continue
            dn, m = vb.ct_local(ts)
            if m < 960:
                has_bar[dn] = True
                if m >= 870:
                    full[dn] = True
    d_from = date.fromisoformat(a.from_date)
    dates = sorted(vb.day_to_date(dn).isoformat() for dn in has_bar
                   if dn not in full and vb.day_to_date(dn) >= d_from and vb.day_to_date(dn).weekday() < 5)
    with open(a.out, "w") as f:
        f.write('{"skipDates": "%s"}\n' % ",".join(dates))
    print("%d not-full CT dates written to %s" % (len(dates), a.out))


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("compare")
    c.add_argument("--java", required=True)
    c.add_argument("--python", required=True)
    c.add_argument("--from", dest="from_date", default=None)
    c.add_argument("--to", default=None)
    c.add_argument("--show", type=int, default=20)
    c.add_argument("--pnl-tol", type=float, default=0.01)
    c.add_argument("--dump", default=None)
    c.add_argument("--drop-gap-roundtrips", action="store_true")
    s = sub.add_parser("skipdates")
    s.add_argument("--csv", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--from", dest="from_date", default="2019-09-01")
    a = p.parse_args()
    (compare if a.cmd == "compare" else skipdates)(a)


if __name__ == "__main__":
    main()
