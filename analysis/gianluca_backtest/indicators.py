"""Causal indicators on TF bars (plan §5.4): a value at bar i uses bars <= i only."""
import math
from array import array
from bisect import bisect_right, insort

VOL_MA_N = 20
ATR_N = 14
ATR_PCT_N = 200
BOCPD_HAZARD = 1 / 50
BOCPD_RMAX = 300
BOCPD_PRIOR_N = 500


def vol_sma(v, n=VOL_MA_N):
    out = array("d")
    s = 0.0
    for i, x in enumerate(v):
        s += x
        if i >= n:
            s -= v[i - n]
        out.append(s / min(i + 1, n))
    return out


def vol_ema(v, n=VOL_MA_N):
    a = 2 / (n + 1)
    out = array("d")
    e = None
    for x in v:
        e = float(x) if e is None else e + a * (x - e)
        out.append(e)
    return out


def atr(h, l, c, n=ATR_N):
    """Wilder ATR in ticks. Before n bars: running mean of TR."""
    out = array("d")
    a = 0.0
    for i in range(len(c)):
        tr = h[i] - l[i] if i == 0 else max(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))
        a = a + (tr - a) / (i + 1) if i < n else (a * (n - 1) + tr) / n
        out.append(a)
    return out


def atr_pct(a, n=ATR_PCT_N):
    """% (0-100) of the last n ATR values (incl. current) that are <= the current one."""
    out = array("d")
    win = []
    for i, x in enumerate(a):
        insort(win, x)
        if i >= n:
            old = a[i - n]
            del win[bisect_right(win, old) - 1]
        out.append(100.0 * bisect_right(win, x) / len(win))
    return out


def atr_bucket(p):
    return "LOW" if p <= 25 else "MEDIUM" if p <= 50 else "HIGH" if p <= 75 else "EXTREME"


def bocpd_age(h, l, hazard=BOCPD_HAZARD, rmax=BOCPD_RMAX, prior_n=BOCPD_PRIOR_N):
    """Adams-MacKay BOCPD, Normal-Gamma on x = log(high/low); returns the MAP run length per bar.
    Run lengths >= rmax are merged into rmax (its posterior keeps updating as the r=rmax-1 lineage)."""
    xs = [math.log(h[i] / l[i]) if l[i] > 0 else 0.0 for i in range(len(h))]
    m = min(prior_n, len(xs))
    mu0 = sum(xs[:m]) / m
    var0 = sum((x - mu0) ** 2 for x in xs[:m]) / m or 1e-12
    k0, a0, b0 = 1.0, 1.0, var0
    # per run length r: kappa = k0 + r, alpha = a0 + r/2 (deterministic) -> precompute constant terms
    R = rmax + 1
    kap = [k0 + r for r in range(R)]
    alp = [a0 + r / 2 for r in range(R)]
    const = [math.lgamma(alp[r] + 0.5) - math.lgamma(alp[r]) - 0.5 * math.log(2 * math.pi) for r in range(R)]
    kfac = [(kap[r] + 1) / kap[r] for r in range(R)]
    ap5 = [alp[r] + 0.5 for r in range(R)]
    p = [1.0]; mu = [mu0]; beta = [b0]
    out = array("l")
    H = hazard; G = 1 - hazard
    for x in xs:
        n = len(p)
        pred = [0.0] * n
        for r in range(n):
            s = beta[r] * kfac[r]          # 2*beta*(k+1)/k / 2
            d = x - mu[r]
            pred[r] = p[r] * math.exp(const[r] - 0.5 * math.log(s) - ap5[r] * math.log1p(d * d / (2 * s)))
        cp = H * sum(pred)
        grow = [q * G for q in pred]
        # new arrays: r=0 (changepoint), r+1 from r (merge overflow into rmax)
        nmu = [mu0]; nbeta = [b0]; npr = [cp]
        for r in range(n):
            k = kap[r]
            d = x - mu[r]
            m2 = (k * mu[r] + x) / (k + 1)
            b2 = beta[r] + k * d * d / (2 * (k + 1))
            if r + 1 < R:
                nmu.append(m2); nbeta.append(b2); npr.append(grow[r])
            else:
                npr[rmax] += grow[r]       # keep the r=rmax-1 lineage's params already placed at rmax
        tot = sum(npr) or 1.0
        p = [q / tot for q in npr]
        mu, beta = nmu, nbeta
        best = 0
        bp = p[0]
        for r in range(1, len(p)):
            if p[r] > bp:
                bp = p[r]; best = r
        out.append(best)
    return out


def fresh_bucket(age):
    return "FRESH" if age <= 30 else "MATURE" if age <= 60 else "OLD"


def swings(h, l, n):
    """(is_high, is_low) bytearrays: bar k is a swing high if h[k] > the highs of the n bars on each side.
    Known only at bar k+n (the caller must respect that)."""
    N = len(h)
    sh = bytearray(N); sl = bytearray(N)
    for k in range(n, N - n):
        hk, lk = h[k], l[k]
        if all(hk > h[j] for j in range(k - n, k + n + 1) if j != k):
            sh[k] = 1
        if all(lk < l[j] for j in range(k - n, k + n + 1) if j != k):
            sl[k] = 1
    return sh, sl
