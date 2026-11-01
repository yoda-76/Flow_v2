"""Do candidate trade filters improve the rolling / anchored walk-forward of the anchored-VWAP strategy on MES?

Screening version: each filter simply drops trades from the cached runs of all 360 settings (reports/walk_forward_trades.pkl), then the same
walk-forward runs (pick the best setting on the train window by score = net / max(closed-trade drawdown, $500), at least MIN_N trades;
trade it on the next unseen year). Dropping trades after the fact is an approximation (it ignores that a skipped trade would have changed
later trades through the open position and the daily attempt rules); confirm any winner with the filter built into the engine.

CAVEAT: the candidate filters were found in factor_analysis.py, which used 2022-2026 trades, so the filter CHOICE is not out-of-sample.
Read-only; places no orders.

    python filter_wf.py
"""
import os, pickle, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import walk_forward as wf
from va_break_momentum import load_window, EtClock
from optimize_wf import CSV
import datetime as d

MIN_N = 25
ms = lambda s: int(d.datetime.fromisoformat(s).replace(tzinfo=d.timezone.utc).timestamp() * 1000)

FILTERS = {
    "none (baseline)": lambda f: True,
    "no Friday entries": lambda f: f["wd"] != 4,
    "entries 14:00 or later only": lambda f: f["hhmm"] >= 1400,
    "gap up > 0.3% only": lambda f: f["gap"] > 0.003,
    "no Friday + entries 14:00+": lambda f: f["wd"] != 4 and f["hhmm"] >= 1400,
    "entries 14:00+ and gap up": lambda f: f["hhmm"] >= 1400 and f["gap"] > 0.003,
    "all three": lambda f: f["wd"] != 4 and f["hhmm"] >= 1400 and f["gap"] > 0.003,
    "no entries before 10:00": lambda f: f["hhmm"] >= 1000,
}


def build_daily():
    m1 = load_window(CSV, ms("2021-08-18"))
    clock = EtClock()
    day = {}
    for i, t in enumerate(m1[0]):
        hhmm, dn = clock.hhmm_date(t)
        if 930 <= hhmm < 1600:
            x = day.get(dn)
            if x is None:
                day[dn] = [m1[1][i], m1[4][i]]
            else:
                x[1] = m1[4][i]
    dates = sorted(day)
    prev = {dates[i]: day[dates[i - 1]][1] for i in range(1, len(dates))}
    gap = {dn: day[dn][0] / prev[dn] - 1 for dn in prev}
    return clock, gap


def decorate(results, clock, gapmap):
    """cfg -> list of (entry_t, pnl, features)."""
    out = {}
    for cfg, tr in results.items():
        tf = cfg[0]
        rows = []
        for t, p in tr:
            hhmm_open, dn = clock.hhmm_date(t - tf * 60000)         # the signal bar's open (entry_t is its close)
            rows.append((t, p, dict(hhmm=hhmm_open, wd=(dn + 3) % 7, gap=gapmap.get(dn, 0.0))))
        out[cfg] = rows
    return out


def pick_and_test(data, flt, kind):
    cfgs = list(data)
    per_year, oos = {}, []
    for ty, lo, hi in wf.folds(kind):
        sel = {c: [(t, p) for t, p, f in data[c] if flt(f)] for c in cfgs}
        tr = {c: wf.stats(wf.window(sel[c], lo, hi)) for c in cfgs}
        ok = [c for c in cfgs if tr[c]["n"] >= MIN_N]
        if not ok:
            per_year[ty] = (None, 0.0, 0)
            continue
        pick = max(ok, key=lambda c: tr[c]["score"])
        pnl = wf.window(sel[pick], *wf.YEARS[ty])
        per_year[ty] = (pick, sum(pnl), len(pnl))
        oos += pnl
    return per_year, wf.stats(oos)


if __name__ == "__main__":
    results = pickle.load(open("reports/walk_forward_trades.pkl", "rb"))
    clock, gapmap = build_daily()
    data = decorate(results, clock, gapmap)
    print(f"{len(data)} settings; minimum trades in the train window to be eligible: {MIN_N}\n")
    for kind in ("rolling", "anchored"):
        print(f"=== {kind.upper()} WALK-FORWARD, OUT-OF-SAMPLE 2023 -> 2026 YTD")
        print(f"{'filter':<30} | {'net':>8} {'PF':>5} {'n':>5} {'DD':>6} | " + " ".join(f"{y:>8}" for y in (2023, 2024, 2025, 2026)))
        for name, flt in FILTERS.items():
            py, st = pick_and_test(data, flt, kind)
            print(f"{name:<30} | {st['net']:+8,.0f} {st['pf']:5.2f} {st['n']:5d} {st['dd']:6,.0f} | " + " ".join(f"{py[y][1]:+8,.0f}" for y in (2023, 2024, 2025, 2026)))
        print()
    # in-sample reference for the fixed platform settings (hindsight-selected, for context only)
    user = (3, 11, 1.1, None)
    print("=== CONTEXT: the platform settings (3m, EMA 11, rel 1.1, no stop) with each filter, 2023 -> 2026 (settings chosen in hindsight)")
    for name, flt in FILTERS.items():
        pnl = [p for t, p, f in data[user] if t >= wf.YEARS[2023][0] and flt(f)]
        s = wf.stats(pnl)
        print(f"  {name:<30} n={s['n']:>4}  PF {s['pf']:5.2f}  net ${s['net']:+8,.0f}  DD ${s['dd']:,.0f}")
