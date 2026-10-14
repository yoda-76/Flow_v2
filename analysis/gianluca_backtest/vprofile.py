"""Volume profile from 1m bars and the TradingView-style value area (plan §5.3). Prices in int ticks."""


def build_profile(h, l, v, i0, i1):
    """Spread each 1m bar's volume evenly over every tick from low to high. Returns (lo_tick, vols list)."""
    if i1 <= i0:
        return 0, []
    lo = min(l[i0:i1]); hi = max(h[i0:i1])
    diff = [0.0] * (hi - lo + 2)
    for k in range(i0, i1):
        a = l[k] - lo; b = h[k] - lo
        share = v[k] / (b - a + 1)
        diff[a] += share
        diff[b + 1] -= share
    vols = []
    run = 0.0
    for x in range(hi - lo + 1):
        run += diff[x]
        vols.append(run if run > 1e-9 else 0.0)
    return lo, vols


def value_area(lo, vols, pct):
    """(poc, vah, val) in ticks; pct like 70. POC ties -> level closest to the volume-weighted mean.
    VA grows from the POC by comparing the next 2 levels above vs below and adding the larger pair (ties: above)."""
    n = len(vols)
    total = sum(vols)
    if n == 0 or total <= 0:
        return None
    mx = max(vols)
    mean = sum(i * x for i, x in enumerate(vols)) / total
    poc = min((i for i in range(n) if vols[i] >= mx - 1e-9 * mx), key=lambda i: (abs(i - mean), i))
    target = total * pct / 100.0
    a = b = poc
    va = vols[poc]
    while va < target - 1e-9 * total:
        up = vols[b + 1:b + 3]
        dn = vols[max(0, a - 2):a]
        if not up and not dn:
            break
        if up and (not dn or sum(up) >= sum(dn)):
            va += sum(up); b += len(up)
        else:
            va += sum(dn); a -= len(dn)
    return lo + poc, lo + b, lo + a


def session_va(base, si, pct, upto=None):
    """VA of session si from its 1m bars [start, upto) (upto None = the whole session)."""
    i0 = base.s_start[si]
    i1 = base.s_end[si] if upto is None else upto
    lo, vols = build_profile(base.h, base.l, base.v, i0, i1)
    return value_area(lo, vols, pct)


def completed_vas(base, pcts=(70, 60, 80)):
    """{pct: [(poc, vah, val) per session]}; builds each session's profile once."""
    out = {p: [] for p in pcts}
    for si in range(len(base.s_day)):
        lo, vols = build_profile(base.h, base.l, base.v, base.s_start[si], base.s_end[si])
        for p in pcts:
            out[p].append(value_area(lo, vols, p))
    return out
