"""Is the anchored-VWAP edge real? Diagnostics on the backtest, plus out-of-sample / robustness re-runs.

Read-only. Part A needs reports/avwap_mes_2023/trades.csv (3 contracts, MES, 2023-01-01 -> now). Parts B and C re-run the engine
from anchored_vwap.py over the whole MES file (2021-08 -> 2026-10) and over the NQ file.

    python edge_check.py
"""
import bisect, csv, datetime as d, os, random, statistics, sys, time
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import anchored_vwap as av
from lucid_replay import session_day, utc
from va_break_momentum import load_window

MES = dict(csv="analysis/data/MES_1m_latest.csv", mult=5.0, tick=0.25)
MNQ = dict(csv="analysis/data/NQ_1m_latest.csv", mult=2.0, tick=0.25)
BASE = dict(av.P, contracts=3, ema_length=11, use_atr_stop=False, max_relative_atr=1.1)


def pf(pnl):
    gp, gl = sum(p for p in pnl if p > 0), -sum(p for p in pnl if p <= 0)
    return gp / gl if gl else float("inf")


def line(label, pnl):
    if not pnl:
        return f"  {label:<34} no trades"
    w = [p for p in pnl if p > 0]
    return (f"  {label:<34} n={len(pnl):>4}  win {len(w) / len(pnl):4.0%}  PF {pf(pnl):5.2f}  net ${sum(pnl):+9,.0f}  "
            f"avg ${sum(pnl) / len(pnl):+7.1f}/trade")


def part_a(path):
    tr = list(csv.DictReader(open(path)))
    for t in tr:
        t["entry_t"], t["exit_t"], t["pnl"], t["side"], t["qty"] = int(t["entry_t"]), int(t["exit_t"]), float(t["pnl_usd"]), int(t["side"]), int(t["qty"])
        t["hold_h"] = (t["exit_t"] - t["entry_t"]) / 3600000.0
    pnl = [t["pnl"] for t in tr]
    n = len(pnl)
    print(f"=== A. THE 2023-2026 TRADES THEMSELVES ({n} trades, 3 MES)")
    w, l = [p for p in pnl if p > 0], [p for p in pnl if p <= 0]
    print(f"  win {len(w) / n:.0%}; avg win ${sum(w) / len(w):.0f}, avg loss ${sum(l) / len(l):.0f} (payoff {abs(sum(w) / len(w) / (sum(l) / len(l))):.2f}); "
          f"median trade ${statistics.median(pnl):+.0f}; worst 5 trades {[round(x) for x in sorted(pnl)[:5]]}; best 5 {[round(x) for x in sorted(pnl)[-5:]]}")
    srt = sorted(pnl, reverse=True)
    tot = sum(pnl)
    print(f"  total ${tot:+,.0f}; top 10 trades = ${sum(srt[:10]):+,.0f} ({sum(srt[:10]) / tot:.0%} of the total); without the best 10: ${tot - sum(srt[:10]):+,.0f}; "
          f"without the best 20: ${tot - sum(srt[:20]):+,.0f}")
    se = statistics.stdev(pnl) / n ** 0.5
    print(f"  per-trade mean ${statistics.mean(pnl):.1f}, std error ${se:.1f}, t = {statistics.mean(pnl) / se:.2f}")
    byday = defaultdict(float)
    for t in tr:
        byday[session_day(t["exit_t"])] += t["pnl"]
    days = list(byday.values())
    random.seed(7)
    boot = sorted(sum(random.choices(days, k=len(days))) for _ in range(10000))
    print(f"  bootstrap over {len(days)} trading days: total P&L 5th-95th percentile ${boot[500]:+,.0f} .. ${boot[9500]:+,.0f}; "
          f"share of resamples <= 0: {sum(b <= 0 for b in boot) / len(boot):.1%}")
    print("  --- by year")
    for y in sorted({utc(t['entry_t']).year for t in tr}):
        print(line(str(y), [t["pnl"] for t in tr if utc(t["entry_t"]).year == y]))
    print("  --- by side")
    print(line("long", [t["pnl"] for t in tr if t["side"] > 0])); print(line("short", [t["pnl"] for t in tr if t["side"] < 0]))
    print("  --- by how long the trade was held")
    for lab, a, b in (("under 1 hour", 0, 1), ("1-10 hours (same day)", 1, 10), ("over 10 hours (overnight/weekend)", 10, 1e9)):
        print(line(lab, [t["pnl"] for t in tr if a <= t["hold_h"] < b]))
    # flip test: same entries and exits, opposite direction
    slip_cost = 2 * 0.25 * 5.0
    flip = [-(t["pnl"] + t["qty"] * 1.0 + t["qty"] * slip_cost) - t["qty"] * 1.0 - t["qty"] * slip_cost for t in tr]
    print("  --- flip test (same entry/exit times, opposite direction, same costs)")
    print(line("original", pnl)); print(line("flipped", flip))
    print("  --- loss tail")
    big = [p for p in pnl if p < -1000]
    print(f"  losing trades worse than -$1,000: {len(big)} of {n}; total of those ${sum(big):+,.0f}; "
          f"longest hold {max(t['hold_h'] for t in tr):.0f} h")


def split(trades, periods):
    out = {}
    for name, a, b in periods:
        lo, hi = int(d.datetime.fromisoformat(a).replace(tzinfo=d.timezone.utc).timestamp() * 1000), int(d.datetime.fromisoformat(b).replace(tzinfo=d.timezone.utc).timestamp() * 1000)
        out[name] = [t["pnl_usd"] for t in trades if lo <= t["entry_t"] < hi]
    return out


def run_cfg(m1, bars_by_tf, inst, tf, over):
    av.TF_MS = tf * 60_000
    if tf not in bars_by_tf:
        bars_by_tf[tf] = av.make_bars(*m1)
    p = dict(BASE, **over)
    cost = dict(tick=inst["tick"], slip_ticks=1, comm_rt=1.0, mult=inst["mult"], start_equity=25000.0)
    from collections import Counter
    return av.run(bars_by_tf[tf], m1, p, cost, Counter())


def part_b():
    print("\n=== B. NEVER-SEEN PERIOD AND PARAMETER ROBUSTNESS (MES, 3 contracts, no stop; PF / net per period)")
    print("  periods: [2021-09 -> 2022-12] never looked at | [2023-01 -> 2025-10-13] before the 14 Oct 2025 test window | [2025-10-14 -> now] the window the settings came from")
    periods = [("21-22", "2021-09-15", "2023-01-01"), ("23-25", "2023-01-01", "2025-10-14"), ("last yr", "2025-10-14", "2026-12-31")]
    m1 = load_window(MES["csv"], 0)
    cache = {}
    def row(label, tf, over):
        t0 = time.time()
        trades = run_cfg(m1, cache, MES, tf, over)
        sp = split(trades, periods)
        print(f"  {label:<44}" + "".join(f" | {k}: PF {pf(v):4.2f} ${sum(v):+7,.0f} n={len(v):>3}" for k, v in sp.items()), flush=True)
    row("baseline: 3m, EMA 11, rel ATR 1.1, no stop", 3, {})
    print("  --- EMA length x max relative ATR (3m bars)")
    for ema in (9, 11, 15, 20):
        for rel in (1.0, 1.1, 1.2, 1.5):
            row(f"EMA {ema}, rel ATR {rel}", 3, dict(ema_length=ema, max_relative_atr=rel))
    print("  --- timeframe")
    for tf in (1, 5, 15):
        row(f"{tf}m bars (EMA 11, rel ATR 1.1)", tf, {})
    print("  --- with the ATR stop switched on (2x ATR)")
    row("3m, EMA 11, rel ATR 1.1, ATR stop ON", 3, dict(use_atr_stop=True))
    row("3m, EMA 20, rel ATR 1.2, ATR stop ON (source defaults)", 3, dict(use_atr_stop=True, ema_length=20, max_relative_atr=1.2))


def part_c():
    print("\n=== C. SAME RULES ON A DIFFERENT INSTRUMENT: MNQ (NQ prices, $2/pt, 3 contracts)")
    periods = [("19-22", "2019-09-15", "2023-01-01"), ("23-26", "2023-01-01", "2026-12-31")]
    m1 = load_window(MNQ["csv"], 0)
    cache = {}
    for label, over in (("baseline: 3m, EMA 11, rel ATR 1.1, no stop", {}), ("with ATR stop ON", dict(use_atr_stop=True))):
        trades = run_cfg(m1, cache, MNQ, 3, over)
        sp = split(trades, periods)
        print(f"  {label:<44}" + "".join(f" | {k}: PF {pf(v):4.2f} ${sum(v):+7,.0f} n={len(v):>3}" for k, v in sp.items()), flush=True)


if __name__ == "__main__":
    part_a("reports/avwap_mes_2023/trades.csv")
    part_b()
    part_c()
