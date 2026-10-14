"""Meta-label score, Kelly sizing overlay and equity (plan §5.10-5.11, GB-Q22/24/25/33).

Works on the strategy-level trade list from signals.run(). Every signal is an outcome for the rolling statistics
(sized-to-zero trades are shadow-tracked). Only trades in the evaluation window move equity.
"""
from collections import defaultdict

START_EQUITY = 100_000.0
COMMISSION = 4.60
SLIP_RESERVE = 20.0          # 2 ticks x $10, per contract, in the contracts formula
WINDOW = 100
WARMUP_N = 30
WARMUP_RISK = 0.0025
MIN_BUCKET_N = 20
KELLY_FRAC = {5: 1 / 2, 4: 1 / 4, 3: 1 / 5, 2: 1 / 6, 1: 1 / 8, 0: 0.0}
SIZING_BASELINE = {"min_score": 0, "streak": "on", "kelly_cap": 2.0}


def best_buckets(trades):
    """In-sample best bucket per score dimension: highest mean R among buckets with >= MIN_BUCKET_N trades."""
    out = {}
    for key in ("atr_b", "fresh_b", "sess_b"):
        acc = defaultdict(list)
        for t in trades:
            acc[t[key]].append(t["r"])
        ok = [(sum(v) / len(v), b) for b, v in acc.items() if len(v) >= MIN_BUCKET_N]
        out[key] = max(ok)[1] if ok else None
    return out


def autocorr_strength(outcomes):
    """clip(10 * (P(W|W) - P(W)) / 0.16, 0, 10) over the given outcomes (bools, W = True)."""
    n = len(outcomes)
    if n < 2:
        return 0.0
    pw = sum(outcomes) / n
    prev_w = [outcomes[i] for i in range(1, n) if outcomes[i - 1]]
    if not prev_w:
        return 0.0
    pww = sum(prev_w) / len(prev_w)
    return min(10.0, max(0.0, 10 * (pww - pw) / 0.16))


def _streak_before(outcomes):
    """Length of the streak that the LAST outcome broke: (last, prior_streak_len_of_the_opposite_kind)."""
    if not outcomes:
        return None, 0
    last = outcomes[-1]
    n = 0
    for x in reversed(outcomes[:-1]):
        if x == last:
            break
        n += 1
    return last, n


def kelly(outcomes_r):
    """f* = W - (1 - W) / R from R multiples (R = avg win / avg |loss|)."""
    wins = [r for r in outcomes_r if r > 0]
    losses = [-r for r in outcomes_r if r <= 0]
    w = len(wins) / len(outcomes_r)
    if not losses:
        return w
    if not wins:
        return -1.0
    ratio = (sum(wins) / len(wins)) / (sum(losses) / len(losses)) if sum(losses) > 0 else float("inf")
    return w - (1 - w) / ratio if ratio > 0 else -1.0


def overlay(trades, sp, buckets, eval_from_si=None, eval_to_si=None):
    """Size every trade. Returns list of (trade, score, contracts, pnl_usd, equity_after, skip_reason) for the trades
    whose entry session is in [eval_from_si, eval_to_si); earlier trades only feed the rolling statistics."""
    eq = START_EQUITY
    rs = []                 # all strategy-level R outcomes so far
    out = []
    streak_on = sp["streak"] == "on"
    for t in trades:
        in_eval = (eval_from_si is None or t["si"] >= eval_from_si) and (eval_to_si is None or t["si"] < eval_to_si)
        if not in_eval:
            if eval_to_si is not None and t["si"] >= eval_to_si:
                break
            rs.append(t["r"])
            continue
        hist = rs[-WINDOW:]
        wl = [r > 0 for r in hist]
        strength = autocorr_strength(wl)
        last, prior = _streak_before(wl)
        streak_skip = streak_on and strength >= 3 and last is False and prior >= 2
        q2 = streak_on and strength >= 3 and last is True and prior >= 2
        score = (int(buckets.get("atr_b") is not None and t["atr_b"] == buckets["atr_b"]) + int(q2)
                 + int(buckets.get("fresh_b") is not None and t["fresh_b"] == buckets["fresh_b"])
                 + int(t["reducing"])
                 + int(buckets.get("sess_b") is not None and t["sess_b"] == buckets["sess_b"]))
        why = None
        if streak_skip:
            why = "streak"
        elif score == 0 or score < sp["min_score"]:
            why = "score"
        if why:
            risk_pct = 0.0
        elif len(hist) < WARMUP_N:
            risk_pct = WARMUP_RISK
        else:
            f = kelly(hist)
            risk_pct = 0.0 if f <= 0 else min(KELLY_FRAC[score] * f, sp["kelly_cap"] / 100)
            if f <= 0:
                why = "kelly"
        contracts = 0.0
        if risk_pct > 0:
            per = t["risk"] / 10 * 100 + COMMISSION + SLIP_RESERVE
            contracts = int(eq * risk_pct / per / 0.1 + 1e-9) * 0.1
            if contracts < 0.1:
                contracts = 0.0
                why = "too_small"
        pnl = contracts * t["usd"]
        eq += pnl
        out.append((t, score, round(contracts, 1), pnl, eq, why))
        rs.append(t["r"])
    return out
