"""Rolling and anchored walk-forward of the anchored-VWAP strategy on MES (2022 -> 2026 YTD).

Every one of the 360 settings from optimize_wf.py is run ONCE over the whole file (the strategy is causal, so a window's trades do not
depend on later data); each fold then picks the best setting on its TRAIN window only (score = net / max(closed-trade max drawdown,
$500), >= 40 trades) and reports that setting's result on the next, unseen TEST year. Read-only; places no orders.

    python walk_forward.py [--workers 5]
"""
import argparse, csv, datetime as d, itertools, multiprocessing as mp, os, pickle, statistics, sys, time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import anchored_vwap as av
from va_break_momentum import load_window
from optimize_wf import COST, GRID, CSV, spearman

ms = lambda s: int(d.datetime.fromisoformat(s).replace(tzinfo=d.timezone.utc).timestamp() * 1000)
YEARS = {y: (ms(f"{y}-01-01"), ms(f"{y + 1}-01-01")) for y in range(2022, 2027)}
YEARS[2026] = (ms("2026-01-01"), 1 << 62)
_G = {}


def init(flt="none"):
    _G["flt"] = flt
    _G["m1"] = load_window(CSV, ms("2021-12-15"))
    _G["bars"] = {}


def run_one(cfg):
    tf, ema, rel, stop = cfg
    p = dict(av.P, contracts=3, ema_length=ema, use_relative_volatility_filter=rel is not None,
             max_relative_atr=rel if rel is not None else 1.2, use_atr_stop=stop is not None,
             atr_stop_multiplier=stop if stop is not None else 2.0)
    if _G["flt"] in ("late", "latenofri"):
        p["entry_min_hhmm"] = 1400
    if _G["flt"] in ("nofri", "latenofri"):
        p["skip_friday"] = True
    av.TF_MS = tf * 60_000
    if tf not in _G["bars"]:
        _G["bars"][tf] = av.make_bars(*_G["m1"])
    tr = av.run(_G["bars"][tf], _G["m1"], p, COST, Counter())
    return cfg, [(t["entry_t"], t["pnl_usd"]) for t in tr]


def window(trades, lo, hi):
    return [p for t, p in trades if lo <= t < hi]


def stats(pnl):
    if not pnl:
        return dict(n=0, net=0.0, pf=0.0, dd=0.0, score=-1e9, win=0.0)
    gp, gl = sum(p for p in pnl if p > 0), -sum(p for p in pnl if p <= 0)
    eq = peak = dd = 0.0
    for p in pnl:
        eq += p; peak = max(peak, eq); dd = max(dd, peak - eq)
    return dict(n=len(pnl), net=sum(pnl), pf=gp / gl if gl else 9.99, dd=dd, score=sum(pnl) / max(dd, 500.0), win=sum(p > 0 for p in pnl) / len(pnl))


def label(cfg):
    tf, ema, rel, stop = cfg
    return f"{tf:>2}m EMA {ema:>2} rel {str(rel):>4} stop {('%gx' % stop) if stop else 'off':>4}"


def folds(kind):
    out = []
    for ty in range(2023, 2027):
        lo = YEARS[ty - 1][0] if kind == "rolling" else YEARS[2022][0]
        out.append((ty, lo, YEARS[ty - 1][1]))
    return out


def run_kind(kind, results):
    print(f"\n=== {kind.upper()} WALK-FORWARD  ({'1-year train window' if kind == 'rolling' else 'train = everything from 2022 up to the test year'})")
    print(f"{'test':<5} {'chosen on the train window':<36} | {'train net':>9} {'PF':>5} {'n':>4} | {'TEST net':>9} {'PF':>5} {'DD':>6} {'n':>4} | rank of the choice in the test year")
    cfgs = list(results)
    oos_all, benchmarks = [], Counter()
    tot = dict(chosen=0.0, user=0.0, mean=0.0, best=0.0)
    for ty, lo, hi in folds(kind):
        tr = {c: stats(window(results[c], lo, hi)) for c in cfgs}
        te = {c: stats(window(results[c], *YEARS[ty])) for c in cfgs}
        ok = [c for c in cfgs if tr[c]["n"] >= 40]
        pick = max(ok, key=lambda c: tr[c]["score"])
        order = sorted(cfgs, key=lambda c: te[c]["net"], reverse=True)
        rk = order.index(pick) + 1
        s, t = tr[pick], te[pick]
        print(f"{ty:<5} {label(pick):<36} | {s['net']:+9,.0f} {s['pf']:5.2f} {s['n']:4d} | {t['net']:+9,.0f} {t['pf']:5.2f} {t['dd']:6,.0f} {t['n']:4d} | {rk}/360")
        oos_all.append((ty, window(results[pick], *YEARS[ty])))
        user = (3, 11, 1.1, None)
        tot["chosen"] += t["net"]; tot["user"] += te[user]["net"]; tot["mean"] += statistics.mean(te[c]["net"] for c in cfgs); tot["best"] += te[order[0]]["net"]
        rho = spearman([tr[c]["score"] for c in ok], [te[c]["net"] for c in ok])
        benchmarks[ty] = (rho, sum(te[c]["net"] > 0 for c in cfgs), te[order[0]]["net"], order[0])
    pnl = [p for _, ps in oos_all for p in ps]
    st = stats(pnl)
    print(f"\n  out-of-sample total (2023 -> 2026 YTD, the chosen setting each year): net ${st['net']:+,.0f}, PF {st['pf']:.2f}, n={st['n']}, win {st['win']:.0%}, closed-trade max DD ${st['dd']:,.0f}")
    print(f"  same four years: your fixed settings (3m, EMA 11, rel 1.1, no stop) ${tot['user']:+,.0f} | the average of all 360 settings ${tot['mean']:+,.0f} | best-in-hindsight each year ${tot['best']:+,.0f}")
    print("  per test year: rank correlation train score vs test net / settings profitable that year / best setting in hindsight")
    for ty, (rho, npos, bnet, bcfg) in benchmarks.items():
        print(f"     {ty}: rho {rho:+.2f} | {npos}/360 profitable | best {label(bcfg)} ${bnet:+,.0f}")
    return pnl


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=5)
    ap.add_argument("--filter", choices=["none", "late", "nofri", "latenofri"], default="none", help="engine-level entry filter: late = signal bar open 14:00+, nofri = no Friday entries")
    a = ap.parse_args()
    cfgs = list(itertools.product(GRID["tf"], GRID["ema"], GRID["rel"], GRID["stop"]))
    t0 = time.time()
    path = "reports/walk_forward_trades.pkl" if a.filter == "none" else f"reports/walk_forward_trades_{a.filter}.pkl"
    if os.path.exists(path):
        results = pickle.load(open(path, "rb"))
        print(f"loaded {len(results)} cached runs from {path}")
    else:
        print(f"{len(cfgs)} settings over the whole file, {a.workers} workers", flush=True)
        results = {}
        with mp.get_context("spawn").Pool(a.workers, initializer=init, initargs=(a.filter,)) as pool:
            for i, (cfg, tr) in enumerate(pool.imap_unordered(run_one, cfgs), 1):
                results[cfg] = tr
                if i % 60 == 0:
                    print(f"  {i}/{len(cfgs)} ({time.time() - t0:.0f}s)", flush=True)
        os.makedirs("reports", exist_ok=True)
        pickle.dump(results, open(path, "wb"))
    for kind in ("rolling", "anchored"):
        run_kind(kind, results)
    print(f"\ntotal time {time.time() - t0:.0f}s")
