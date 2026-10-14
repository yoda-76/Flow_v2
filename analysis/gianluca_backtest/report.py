"""Report helpers for the Claude phases (P2, P6, P8): trade dumps, chunk test, Monte Carlo, top tables."""
import csv
import random
from pathlib import Path

import search
import timeutil as tu

TRADES_DIR = Path(__file__).resolve().parents[2] / "reports" / "gianluca_backtest" / "trades"


def init():
    if "base" not in search._W:
        search._worker_init()
    return search._W["base"]


def sized_trades(cfg, eval_p="full", learn_p=None):
    init()
    return search.evaluate(cfg, eval_p, learn_p, want_trades=True)


def et_str(base, i):
    """ET wall-clock of the 1m bar i's close."""
    m = base.et[i] + 1
    d = tu.day_to_date(m // 1440)
    return f"{d} {m % 1440 // 60:02d}:{m % 60:02d}"


def save_trades_csv(cfg, eval_p="full", learn_p=None, path=None):
    base = init()
    res = sized_trades(cfg, eval_p, learn_p)
    path = Path(path or TRADES_DIR / f"{search.cfg_hash(cfg)}_{eval_p}.csv")
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["entry_et", "exit_et", "dir", "entry", "stop", "target", "exit", "reason", "r", "usd_1c", "score",
                    "contracts", "pnl", "equity", "skip", "atr_b", "fresh_b", "sess_b", "reducing", "first"])
        for t, score, n, pnl, eq, why in res["sized"]:
            w.writerow([et_str(base, t["entry_i"]), et_str(base, t["exit_i"]), t["dir"], t["entry"] / 10, t["stop"] / 10,
                        t["target"] / 10, t["exit"] / 10, t["reason"], round(t["r"], 3), round(t["usd"], 2), score, n,
                        round(pnl, 2), round(eq, 2), why or "", t["atr_b"], t["fresh_b"], t["sess_b"], t["reducing"],
                        t["first"]])
    return path, res


def chunk_test(sized, n=10):
    """Split the eval trades into n equal-count chunks: (trades, win%, sum R, pnl $) per chunk."""
    out = []
    k = len(sized)
    for c in range(n):
        part = sized[c * k // n:(c + 1) * k // n]
        rs = [x[0]["r"] for x in part]
        out.append((len(part), round(100 * sum(r > 0 for r in rs) / max(1, len(rs)), 1), round(sum(rs), 1),
                    round(sum(x[3] for x in part))))
    return out


def monte_carlo(sized, n=10_000, seed=7):
    """Shuffle the per-trade % returns (pnl / equity before) and compound. Returns (% negative final equity,
    max-DD % percentiles 5/50/95)."""
    rets, eq = [], 100_000.0
    for x in sized:
        before = x[4] - x[3]
        rets.append(x[3] / before if before else 0.0)
    rnd = random.Random(seed)
    neg, dds = 0, []
    for _ in range(n):
        rnd.shuffle(rets)
        e, peak, mdd = 1.0, 1.0, 0.0
        for r in rets:
            e *= 1 + r
            peak = max(peak, e)
            mdd = max(mdd, (peak - e) / peak)
        neg += e < 1.0
        dds.append(100 * mdd)
    dds.sort()
    return 100 * neg / n, [round(dds[int(q * (n - 1))], 1) for q in (0.05, 0.5, 0.95)]


def monte_carlo_resize(trades, sp, buckets, n=10_000, seed=7):
    """Shuffle the strategy-level trade order and re-run the sizing overlay (rolling Kelly + streak rules depend on
    order). Returns (% shuffles with final equity < start, final-equity p5/p50/p95, max-DD % p5/p50/p95)."""
    import sizing
    rnd = random.Random(seed)
    seq = list(trades)
    finals, dds = [], []
    for _ in range(n):
        rnd.shuffle(seq)
        peak, mdd, eq = sizing.START_EQUITY, 0.0, sizing.START_EQUITY
        for x in sizing.overlay(seq, sp, buckets):
            eq = x[4]
            peak = max(peak, eq)
            mdd = max(mdd, (peak - eq) / peak)
        finals.append(eq); dds.append(100 * mdd)
    finals.sort(); dds.sort()
    q = lambda a: [round(a[int(p * (n - 1))], 1) for p in (0.05, 0.5, 0.95)]  # noqa: E731
    return 100 * sum(f < sizing.START_EQUITY for f in finals) / n, q(finals), q(dds)


def top_table(lines, floor, n=20):
    rows = []
    for ln in search.ranked(lines, floor)[:n]:
        m, p = ln["metrics"], ln["params"]
        diff = {k: v for k, v in p.items() if search.BASELINE.get(k) != v}
        rows.append((ln["hash"], m["trades"], round(m["net_usd"]), round(m["sharpe"], 2), round(m["mdd_pct"], 1),
                     round(m["exp_r"], 3), round(100 * m["win_rate"], 1), diff))
    return rows
