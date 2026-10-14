"""Per-run metrics (plan §5.12) from the sized overlay."""
import math
from bisect import bisect_right
from collections import Counter

import sizing


def compute(base, sized, si_from, si_to):
    """sized = sizing.overlay(...) output for the eval window [si_from, si_to)."""
    n_sig = len(sized)
    rs = [x[0]["r"] for x in sized]
    taken = [x for x in sized if x[2] > 0]
    wins = sum(1 for r in rs if r > 0)
    eq_end = sized[-1][4] if sized else sizing.START_EQUITY
    # max drawdown % over the trade-by-trade Kelly equity
    peak, mdd = sizing.START_EQUITY, 0.0
    for x in sized:
        peak = max(peak, x[4])
        mdd = max(mdd, (peak - x[4]) / peak)
    gw = sum(x[3] for x in taken if x[3] > 0)
    gl = -sum(x[3] for x in taken if x[3] < 0)
    # daily (per-session) returns, zero-trade sessions included; P&L booked on the exit's session
    n_days = si_to - si_from
    day_pnl = [0.0] * n_days
    for x in sized:
        if x[3]:
            si = bisect_right(base.s_start, x[0]["exit_i"]) - 1
            day_pnl[min(max(si, si_from), si_to - 1) - si_from] += x[3]
    eq = sizing.START_EQUITY
    rets = []
    for d in day_pnl:
        rets.append(d / eq)
        eq += d
    mu = sum(rets) / n_days if n_days else 0.0
    sd = math.sqrt(sum((r - mu) ** 2 for r in rets) / (n_days - 1)) if n_days > 1 else 0.0
    sharpe = mu / sd * math.sqrt(252) if sd > 0 else 0.0
    # max consecutive losses (strategy level)
    mcl = cur = 0
    for r in rs:
        cur = cur + 1 if r <= 0 else 0
        mcl = max(mcl, cur)
    months = n_days / 21.0
    reasons = Counter(x[0]["reason"] for x in sized)
    skips = Counter(x[5] for x in sized if x[5])
    return {
        "signals": n_sig, "trades": len(taken),
        "win_rate": wins / n_sig if n_sig else 0.0,
        "exp_r": sum(rs) / n_sig if n_sig else 0.0,
        "net_usd": eq_end - sizing.START_EQUITY,
        "ret_pct": 100 * (eq_end / sizing.START_EQUITY - 1),
        "mdd_pct": 100 * mdd,
        "flat_usd": sum(x[0]["usd"] for x in sized),
        "pf": gw / gl if gl > 0 else (float("inf") if gw > 0 else 0.0),
        "sharpe": sharpe,
        "max_consec_losses": mcl,
        "trades_per_month": len(taken) / months if months else 0.0,
        "exit_pct": {k: round(100 * v / n_sig, 1) for k, v in reasons.items()} if n_sig else {},
        "skips": dict(skips),
        "autocorr_end": sizing.autocorr_strength([r > 0 for r in rs[-sizing.WINDOW:]]),
    }
