"""Walk-forward test of the anchored-VWAP strategy: optimise on 2022, test untouched on 2023-01-01 -> end of data.

Grid (360 combinations): timeframe {3,5,15} x EMA length {5,9,11,15,20,30} x max relative ATR {1.0,1.1,1.2,1.5,off} x stop {off,2x,3x,5x ATR}.
Everything else fixed: MES, 3 contracts, $25k, $1 round trip, 1 tick slippage each way, fills at the bar close, ATR 14 / baseline 50, 2 extra attempts.
Selection rule (fixed before looking): maximise score = net P&L / max(closed-trade max drawdown, $500) over 2022, with at least 40 trades.
The test period is only used to report; it never feeds the choice. Read-only; places no orders.

    python optimize_wf.py [--workers 4]
"""
import argparse, bisect, csv, datetime as d, itertools, multiprocessing as mp, os, sys, time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import anchored_vwap as av
from va_break_momentum import load_window

CSV = "analysis/data/MES_1m_latest.csv"
COST = dict(tick=0.25, slip_ticks=1, comm_rt=1.0, mult=5.0, start_equity=25000.0)
ms = lambda s: int(d.datetime.fromisoformat(s).replace(tzinfo=d.timezone.utc).timestamp() * 1000)
OPT = (ms("2022-01-01"), ms("2023-01-01"))
TEST_START = ms("2023-01-01")
GRID = dict(tf=(3, 5, 15), ema=(5, 9, 11, 15, 20, 30), rel=(1.0, 1.1, 1.2, 1.5, None), stop=(None, 2.0, 3.0, 5.0))

_G = {}


def init():
    m = load_window(CSV, OPT[0] - 12 * 86400000)
    k = bisect.bisect_left(m[0], OPT[1])
    _G["opt"] = tuple(c[:k] for c in m)                 # 2022 data only: nothing after 2022-12-31 is visible to the optimiser
    _G["test"] = tuple(c[bisect.bisect_left(m[0], TEST_START - 12 * 86400000):] for c in m)
    _G["bars"] = {}


def metrics(trades, lo, hi):
    tr = [t for t in trades if lo <= t["entry_t"] < hi]
    pnl = [t["pnl_usd"] for t in tr]
    if not pnl:
        return dict(n=0, net=0.0, pf=0.0, win=0.0, dd=0.0, worst=0.0, score=-1e9)
    gp, gl = sum(p for p in pnl if p > 0), -sum(p for p in pnl if p <= 0)
    eq = peak = dd = 0.0
    for p in pnl:
        eq += p; peak = max(peak, eq); dd = max(dd, peak - eq)
    return dict(n=len(pnl), net=sum(pnl), pf=gp / gl if gl else 9.99, win=sum(p > 0 for p in pnl) / len(pnl), dd=dd, worst=min(pnl),
                score=sum(pnl) / max(dd, 500.0))


def run_one(cfg):
    tf, ema, rel, stop = cfg
    p = dict(av.P, contracts=3, ema_length=ema, use_relative_volatility_filter=rel is not None,
             max_relative_atr=rel if rel is not None else 1.2, use_atr_stop=stop is not None,
             atr_stop_multiplier=stop if stop is not None else 2.0)
    out = dict(tf=tf, ema=ema, rel=rel, stop=stop)
    for name, (lo, hi) in (("opt", OPT), ("test", (TEST_START, 1 << 62))):
        key = (name, tf)
        av.TF_MS = tf * 60_000
        if key not in _G["bars"]:
            _G["bars"][key] = av.make_bars(*_G[name])
        trades = av.run(_G["bars"][key], _G[name], p, COST, Counter())
        for k, v in metrics(trades, lo, hi).items():
            out[f"{name}_{k}"] = v
    return out


def rank(xs):
    order = sorted(range(len(xs)), key=lambda i: xs[i])
    r = [0.0] * len(xs)
    for pos, i in enumerate(order):
        r[i] = pos
    return r


def spearman(a, b):
    ra, rb = rank(a), rank(b)
    n = len(a)
    ma, mb = sum(ra) / n, sum(rb) / n
    cov = sum((x - ma) * (y - mb) for x, y in zip(ra, rb))
    va, vb = sum((x - ma) ** 2 for x in ra), sum((y - mb) ** 2 for y in rb)
    return cov / (va * vb) ** 0.5


def fmt(r):
    return (f"tf {r['tf']:>2}m EMA {r['ema']:>2} relATR {str(r['rel']):>4} stop {str(r['stop']) + 'x' if r['stop'] else 'off':>4}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=4)
    a = ap.parse_args()
    cfgs = list(itertools.product(GRID["tf"], GRID["ema"], GRID["rel"], GRID["stop"]))
    print(f"{len(cfgs)} combinations, {a.workers} workers", flush=True)
    t0 = time.time()
    with mp.get_context("spawn").Pool(a.workers, initializer=init) as pool:
        res = []
        for i, r in enumerate(pool.imap_unordered(run_one, cfgs), 1):
            res.append(r)
            if i % 40 == 0:
                print(f"  {i}/{len(cfgs)} done ({time.time() - t0:.0f}s)", flush=True)
    os.makedirs("reports", exist_ok=True)
    with open("reports/optimize_wf.csv", "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(res[0].keys())); w.writeheader(); w.writerows(res)

    ok = [r for r in res if r["opt_n"] >= 40]
    ok.sort(key=lambda r: r["opt_score"], reverse=True)
    print(f"\n{len(ok)} of {len(res)} combinations have >= 40 trades in 2022")
    print("\n=== TOP 10 ON 2022 (score = net / max(DD,$500)) and what each did on 2023 -> now")
    print(f"{'settings':<42} | {'2022 net':>9} {'PF':>5} {'DD':>6} {'n':>4} {'score':>5} | {'TEST net':>9} {'PF':>5} {'DD':>6} {'n':>4}")
    for r in ok[:10]:
        print(f"{fmt(r):<42} | {r['opt_net']:+9,.0f} {r['opt_pf']:5.2f} {r['opt_dd']:6,.0f} {r['opt_n']:4d} {r['opt_score']:5.2f} | "
              f"{r['test_net']:+9,.0f} {r['test_pf']:5.2f} {r['test_dd']:6,.0f} {r['test_n']:4d}")
    best = ok[0]
    print(f"\nCHOSEN (best 2022 score): {fmt(best)}")
    print(f"  2022 (in-sample)  : n={best['opt_n']} net ${best['opt_net']:+,.0f} PF {best['opt_pf']:.2f} win {best['opt_win']:.0%} max DD ${best['opt_dd']:,.0f} worst trade ${best['opt_worst']:,.0f}")
    print(f"  2023 -> now (test): n={best['test_n']} net ${best['test_net']:+,.0f} PF {best['test_pf']:.2f} win {best['test_win']:.0%} max DD ${best['test_dd']:,.0f} worst trade ${best['test_worst']:,.0f}")
    pos_opt = sum(r["opt_net"] > 0 for r in ok)
    pos_test = sum(r["test_net"] > 0 for r in res)
    print(f"\n=== DOES 2022 PREDICT 2023+ ?  ({len(ok)} combinations with >= 40 trades in 2022)")
    print(f"  profitable in 2022: {pos_opt}/{len(ok)};  profitable on the test period (all 360): {pos_test}/{len(res)}")
    print(f"  rank correlation between 2022 score and test-period net P&L: {spearman([r['opt_score'] for r in ok], [r['test_net'] for r in ok]):+.2f}")
    top = ok[:20]
    print(f"  top 20 by 2022 score: profitable on test {sum(r['test_net'] > 0 for r in top)}/20, mean test net ${sum(r['test_net'] for r in top) / 20:+,.0f}, "
          f"vs all-combination mean ${sum(r['test_net'] for r in ok) / len(ok):+,.0f}")
    print("\n=== REFERENCE SETTINGS")
    for lab, key in (("your settings (3m, EMA 11, rel 1.1, no stop)", (3, 11, 1.1, None)), ("source defaults (3m, EMA 20, rel 1.2, 2x stop)", (3, 20, 1.2, 2.0))):
        r = next(x for x in res if (x["tf"], x["ema"], x["rel"], x["stop"]) == key)
        print(f"  {lab:<48} 2022: n={r['opt_n']:>3} net ${r['opt_net']:+7,.0f} PF {r['opt_pf']:.2f} | test: n={r['test_n']:>3} net ${r['test_net']:+8,.0f} PF {r['test_pf']:.2f} DD ${r['test_dd']:,.0f}")
    print(f"\ntotal time {time.time() - t0:.0f}s; full table in reports/optimize_wf.csv")
