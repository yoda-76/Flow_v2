"""Gianluca Bruni strategy -- one-shot backtest on 1-minute OHLCV (NQ by default), with HIS numbers, no fitting.

Rules follow docs/dynamic/gianlucaBruniBacktest_plan_v1.md section 5 (best guesses where the video is silent).
Pure standard library. Usage:
    python gb_backtest.py <csv> [--tick 0.25] [--pt-value 20] [--out reports/gb_nq]
Read-only over the CSV; writes only under --out. Places no orders, touches nothing else.
"""
import sys, math, bisect, json, time, datetime, collections, argparse
from datetime import date, timedelta, timezone

H_MS = 3_600_000
DAY_MS = 86_400_000

# ---- his numbers (video) -------------------------------------------------------------------------------------
CFG = dict(
    tf=15,                 # 15m chart
    thr_pts=6.0,           # "6 for NQ" -- points (GB-Q1 default)
    va_pct=0.70,
    vol_ma=20,             # SMA(20)
    atr_n=14, atr_look=200,
    rr=2.0,                # 2R
    min_rr=0.0,            # "we can open a trade also with a less risk-reward"
    fractal=3,             # not in the video: plan baseline
    hazard=0.02, bocpd_trunc=120,
    slip_ticks=1, comm_rt=4.60,
    min_risk_ticks=2,
    last_entry_min=15,     # no entries in the last 15 min of the session
    news=True, reentry_vah=False,
    max_legs_back=3,
    kelly={5: 1 / 2, 4: 1 / 4, 3: 1 / 5, 2: 1 / 6, 1: 1 / 8, 0: 0.0},   # 1/5 -> 1/8 is the plan's guess (video silent)
    kelly_cap=0.02, warmup_risk=0.0025, kelly_window=100, fixed_risk=0.005,
    best_atr=(50, 75), best_age=30,    # HIGH volatility (51-75), FRESH (0-30), New York -- HIS best buckets
)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ---- time ----------------------------------------------------------------------------------------------------------
def build_et_transitions():
    tr = [(-(1 << 62), -5 * H_MS)]
    for y in range(2017, 2030):
        d = date(y, 3, 1); d += timedelta((6 - d.weekday()) % 7 + 7)       # 2nd Sunday of March
        s = datetime.datetime(y, 3, d.day, 7, 0, tzinfo=timezone.utc)      # 02:00 EST
        d = date(y, 11, 1); d += timedelta((6 - d.weekday()) % 7)          # 1st Sunday of November
        e = datetime.datetime(y, 11, d.day, 6, 0, tzinfo=timezone.utc)     # 02:00 EDT
        tr.append((int(s.timestamp() * 1000), -4 * H_MS))
        tr.append((int(e.timestamp() * 1000), -5 * H_MS))
    tr.sort()
    return [t for t, _ in tr], [o for _, o in tr]


def load(path):
    t, o, h, l, c, v = [], [], [], [], [], []
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.split(",")
            t.append(int(p[0])); o.append(float(p[1])); h.append(float(p[2])); l.append(float(p[3]))
            c.append(float(p[4])); v.append(float(p[5]))
    return t, o, h, l, c, v


# ---- sessions, 15m bars, profiles ------------------------------------------------------------------------------
def build(path, tick, cfg):
    t, o, h, l, c, v = load(path)
    n = len(t)
    log(f"loaded {n} 1m bars {datetime.datetime.fromtimestamp(t[0]/1000, timezone.utc):%Y-%m-%d} -> "
        f"{datetime.datetime.fromtimestamp(t[-1]/1000, timezone.utc):%Y-%m-%d}")
    tt, to = build_et_transitions()
    tf = cfg["tf"]
    sid_of = [0] * n
    m_of = [0] * n
    for i in range(n):
        off = to[bisect.bisect_right(tt, t[i]) - 1]
        x = t[i] + off + 6 * H_MS
        sid_of[i] = x // DAY_MS
        m_of[i] = (x % DAY_MS) // 60000
    # sessions: contiguous runs of equal sid
    sessions = []   # dict: sid, i0, i1, bars(range of 15m idx), vah, val, poc, valid
    B = collections.defaultdict(list)  # fields for 15m bars
    bo, bh, bl, bc, bv, bsess, bm0, bm1, bi0, bi1 = ([] for _ in range(10))
    i = 0
    while i < n:
        j = i
        s = sid_of[i]
        while j + 1 < n and sid_of[j + 1] == s:
            j += 1
        S = dict(sid=s, i0=i, i1=j, n1m=j - i + 1)
        S["valid"] = S["n1m"] >= 300
        b0 = len(bo)
        k = i
        while k <= j:
            slot = m_of[k] // tf
            e = k
            while e + 1 <= j and m_of[e + 1] // tf == slot:
                e += 1
            bo.append(o[k]); bh.append(max(h[k:e + 1])); bl.append(min(l[k:e + 1])); bc.append(c[e]); bv.append(sum(v[k:e + 1]))
            bsess.append(len(sessions)); bm0.append(slot * tf); bm1.append(slot * tf + tf); bi0.append(k); bi1.append(e)
            k = e + 1
        S["b0"], S["b1"] = b0, len(bo) - 1
        # volume profile: each 1m bar's volume spread evenly over its high-low range (0.25 ticks)
        lo_t = [round(x / tick) for x in l[i:j + 1]]
        hi_t = [round(x / tick) for x in h[i:j + 1]]
        mn, mx = min(lo_t), max(hi_t)
        diff = [0.0] * (mx - mn + 3)
        for a, b2, vol in zip(lo_t, hi_t, v[i:j + 1]):
            per = vol / (b2 - a + 1)
            diff[a - mn] += per
            diff[b2 - mn + 1] -= per
        prof, run = [], 0.0
        for d_ in diff[:mx - mn + 1]:
            run += d_
            prof.append(run if run > 1e-9 else 0.0)
        total = sum(prof)
        poc = max(range(len(prof)), key=lambda x: prof[x])
        lo = hi = poc
        inside, target, m_ = prof[poc], cfg["va_pct"] * total, len(prof)
        while inside < target:
            up = (prof[hi + 1] if hi + 1 < m_ else 0) + (prof[hi + 2] if hi + 2 < m_ else 0)
            dn = (prof[lo - 1] if lo - 1 >= 0 else 0) + (prof[lo - 2] if lo - 2 >= 0 else 0)
            if (up == 0 and dn == 0) or (hi >= m_ - 1 and lo <= 0):
                break
            if up >= dn:
                hi = min(hi + 2, m_ - 1); inside += up
            else:
                lo = max(lo - 2, 0); inside += dn
        S["vah"], S["val"], S["poc"] = (mn + hi) * tick, (mn + lo) * tick, (mn + poc) * tick
        sessions.append(S)
        i = j + 1
    log(f"{len(sessions)} sessions ({sum(1 for s in sessions if not s['valid'])} invalid <300 bars), {len(bo)} 15m bars")
    return dict(t=t, o=o, h=h, l=l, c=c, v=v, sessions=sessions, bo=bo, bh=bh, bl=bl, bc=bc, bv=bv, bsess=bsess,
                bm0=bm0, bm1=bm1, bi0=bi0, bi1=bi1)


# ---- indicators on the 15m series --------------------------------------------------------------------------------
def atr_pct(D, cfg):
    h, l, c = D["bh"], D["bl"], D["bc"]
    n, N = len(h), cfg["atr_n"]
    out = [None] * n
    tr = [h[0] - l[0]] + [max(h[k] - l[k], abs(h[k] - c[k - 1]), abs(l[k] - c[k - 1])) for k in range(1, n)]
    atr = None
    win, srt = collections.deque(), []
    for k in range(n):
        if k == N - 1:
            atr = sum(tr[:N]) / N
        elif k >= N:
            atr = (atr * (N - 1) + tr[k]) / N
        if atr is None:
            continue
        win.append(atr); bisect.insort(srt, atr)
        if len(win) > cfg["atr_look"]:
            old = win.popleft(); del srt[bisect.bisect_left(srt, old)]
        if len(win) >= 100:
            out[k] = 100.0 * bisect.bisect_right(srt, atr) / len(srt)
    return out


def bocpd_age(D, cfg):
    """Adams-MacKay BOCPD, Normal-Gamma on log(high/low) of each 15m bar, hazard 2%; returns MAP run length per bar."""
    x = [math.log(max(h / l, 1.000001)) for h, l in zip(D["bh"], D["bl"])]
    n0 = min(500, len(x))
    mu0 = sum(x[:n0]) / n0
    var0 = max(sum((a - mu0) ** 2 for a in x[:n0]) / n0, 1e-12)
    k0, a0, b0 = 1.0, 1.0, var0
    H, L = cfg["hazard"], cfg["bocpd_trunc"]
    lg = [math.lgamma(a0 + r / 2 + 0.5) - math.lgamma(a0 + r / 2) for r in range(L + 2)]
    R = [1.0]
    mu, ka, al, be = [mu0], [k0], [a0], [b0]
    ages = []
    ln = math.log
    for xv in x:
        m = len(R)
        pred = [0.0] * m
        for r in range(m):
            a = al[r]
            sc = be[r] * (ka[r] + 1) / (a * ka[r])
            z = (xv - mu[r]) ** 2 / (2 * a * sc)
            pred[r] = math.exp(lg[r] - 0.5 * ln(math.pi * 2 * a * sc) - (a + 0.5) * math.log1p(z))
        growth = [R[r] * pred[r] * (1 - H) for r in range(m)]
        cp = sum(R[r] * pred[r] for r in range(m)) * H
        newR = [cp] + growth
        s = sum(newR)
        newR = [q / s for q in newR] if s > 0 else [1.0] + [0.0] * m
        # parameter update (r -> r+1), new run-length 0 gets the prior
        nmu = [mu0] + [(ka[r] * mu[r] + xv) / (ka[r] + 1) for r in range(m)]
        nka = [k0] + [ka[r] + 1 for r in range(m)]
        nal = [a0] + [al[r] + 0.5 for r in range(m)]
        nbe = [b0] + [be[r] + ka[r] * (xv - mu[r]) ** 2 / (2 * (ka[r] + 1)) for r in range(m)]
        if len(newR) > L:   # truncate: fold the tail into the last kept run length
            newR[L - 1] += sum(newR[L:]); newR = newR[:L]
            nmu, nka, nal, nbe = nmu[:L], nka[:L], nal[:L], nbe[:L]
        R, mu, ka, al, be = newR, nmu, nka, nal, nbe
        ages.append(max(range(len(R)), key=R.__getitem__))
    return ages


def sma_prev(v, n):
    out = [None] * len(v)
    run = 0.0
    for k in range(len(v)):
        if k >= n:
            out[k] = run / n          # mean of the n bars BEFORE bar k
            run -= v[k - n]
        run += v[k]
    return out


# ---- structure: swings, break of structure ------------------------------------------------------------------------
def bos_list(H, L, C, O, fractal):
    """Break-of-structure events [(bos_bar, swing_bar)] for the arrays given (longs); a swing high is confirmed
    `fractal` bars later; BOS = first close above the latest confirmed, unbroken swing high."""
    n, N = len(H), fractal
    out = []
    cur = None
    for b in range(n):
        k = b - N
        if k >= N and all(H[k] > H[k - q] for q in range(1, N + 1)) and all(H[k] > H[k + q] for q in range(1, N + 1)):
            cur = (H[k], k)
        if cur is not None and C[b] > cur[0]:
            out.append((b, cur[1]))
            cur = None
    return out


# ---- signal generation (long logic on possibly mirrored arrays) -----------------------------------------------------
def gen(D, X, cfg, tick, pt_value, direction, atrp, ages, vma):
    """X: dict with arrays o,h,l,c (1m) and bo,bh,bl,bc (15m) in the direction's frame; vah/val per session list."""
    S_ = D["sessions"]
    bv, bm0, bm1, bi1, bsess = D["bv"], D["bm0"], D["bm1"], D["bi1"], D["bsess"]
    O, H, L, C = X["bo"], X["bh"], X["bl"], X["bc"]
    m1o, m1h, m1l, m1c, t1 = X["o"], X["h"], X["l"], X["c"], D["t"]
    thr = cfg["thr_pts"]; slip = cfg["slip_ticks"] * tick
    bos = X["bos"]
    bos_b = [b for b, _ in bos]
    trades = []
    valid_idx = [i for i, s in enumerate(S_) if s["valid"]]
    pos_of = {si: p for p, si in enumerate(valid_idx)}

    def valid_bear(k):   # valid candle: body > longest wick; bearish in this frame
        body = O[k] - C[k]
        if body <= 0:
            return False
        return body > max(H[k] - O[k], C[k] - L[k])

    for p, si in enumerate(valid_idx):
        if p < 2:
            continue
        S, P, P2 = S_[si], S_[valid_idx[p - 1]], S_[valid_idx[p - 2]]
        vah, val = X["vah"][valid_idx[p - 1]], X["val"][valid_idx[p - 1]]
        vah2, val2 = X["vah"][valid_idx[p - 2]], X["val"][valid_idx[p - 2]]
        if not (vah > vah2 and val > val2):          # trend filter: last two completed sessions
            continue
        state, traded = 0, False        # 0 idle, 1 above, 2 active
        ms_low = ms_high = None
        busy_until = -1
        for b in range(S["b0"], S["b1"] + 1):
            hi, lo = H[b], L[b]
            if state == 0:
                if hi > vah:
                    state = 1
                continue
            if state == 1:
                if lo > vah:
                    continue
                # touch -> fix MS low / MS high from the BOS legs just before this bar
                q = bisect.bisect_left(bos_b, b) - 1
                got = None
                for back in range(cfg["max_legs_back"]):
                    if q - back < 0:
                        break
                    bb, ss = bos[q - back]
                    if bb < P["b0"]:
                        break
                    if any(valid_bear(k) for k in range(ss, bb + 1)):
                        got = (min(L[ss:bb + 1]), max(H[bos[q][0]:b + 1]))
                        break
                if got is None:
                    break
                ms_low, ms_high = got
                state = 2
            # ---- state 2: active
            if lo < ms_low or hi >= ms_high:
                break                                # setup cancelled / MS high reached -> finished
            if bm1[b] > 1380 - cfg["last_entry_min"]:
                continue
            if bt_close_t(D, b) <= busy_until or b - 1 < S["b0"]:
                continue
            engulf = C[b] >= H[b - 1] + thr
            if not engulf:
                continue
            if (not traded or cfg["reentry_vah"]) and C[b] < vah + thr:
                continue
            reducing = bv[b] < bv[b - 1]
            lowef = vma[b] is not None and bv[b] < vma[b]
            if not (reducing or lowef):
                continue
            if cfg["news"]:
                s_min = (1080 + bm0[b]) % 1440
                if 495 <= s_min < 525 and date.fromordinal(719163 + S["sid"]).weekday() < 5:
                    continue
            entry = C[b] + slip
            stop = L[b]
            risk = entry - stop
            if risk < cfg["min_risk_ticks"] * tick:
                continue
            target = entry + cfg["rr"] * risk
            capped = False
            if ms_high < target:
                target, capped = ms_high, True
            if target - entry <= 0 or (target - entry) < cfg["min_rr"] * risk:
                continue
            # --- exit simulation on 1m bars after the trigger bar's close
            i, last = bi1[b] + 1, S["i1"]
            exit_px = exit_i = None
            reason = None
            while i <= last:
                if m1l[i] <= stop:
                    exit_px, reason = min(stop, m1o[i]) - slip, "stop"
                    break
                if m1h[i] >= target + tick * cfg["slip_ticks"]:
                    exit_px, reason = target, "ms_high" if capped else "target"
                    break
                i += 1
            if exit_px is None:
                i = last
                exit_px, reason = m1c[last] - slip, "session_end"
            exit_t = t1[i] + 60000
            r_gross = (exit_px - entry) / risk
            cost_r = cfg["comm_rt"] / (risk * pt_value)
            trades.append(dict(dir=direction, sid=S["sid"], entry_t=bt_close_t(D, b), exit_t=exit_t, entry=entry, stop=stop,
                               target=target, exit=exit_px, reason=reason, risk=risk, r_gross=r_gross,
                               r_net=r_gross - cost_r, first=not traded, ms_low=ms_low, ms_high=ms_high,
                               prev_vah=vah, prev_val=val, bar=b, atr_pct=atrp[b], age=ages[b], reducing=reducing,
                               lowef=lowef, close_min=(1080 + bm1[b]) % 1440))
            traded = True
            busy_until = exit_t
            if reason == "session_end":
                break
    return trades


def bt_close_t(D, b):
    return D["t"][D["bi1"][b]] + 60000


# ---- scoring / sizing / metrics ------------------------------------------------------------------------------------
def score_and_size(trades, cfg):
    hist = []     # shadow outcomes of ALL strategy-level signals
    eq_f = eq_k = 1.0
    for tr in trades:
        n = len(hist[-cfg["kelly_window"]:])
        w = hist[-cfg["kelly_window"]:]
        strength = 0.0
        if n >= 30:
            pw = sum(w) / n
            pairs = [(a, b) for a, b in zip(w, w[1:]) if a == 1]
            if len(pairs) >= 5:
                pww = sum(b for _, b in pairs) / len(pairs)
                strength = max(0.0, min(10.0, 10 * (pww - pw) / 0.16))
        q2 = strength >= 3 and len(hist) >= 3 and hist[-1] == 1 and hist[-2] == 0 and hist[-3] == 0
        skip = strength >= 3 and len(hist) >= 3 and hist[-1] == 0 and hist[-2] == 1 and hist[-3] == 1
        a = tr["atr_pct"]
        q1 = a is not None and cfg["best_atr"][0] < a <= cfg["best_atr"][1]
        q3 = tr["age"] <= cfg["best_age"]
        q4 = tr["reducing"]
        q5 = 480 < tr["close_min"] <= 1020
        score = int(q1) + int(q2) + int(q3) + int(q4) + int(q5)
        # Kelly from the last window of shadow outcomes (R multiples)
        rs = tr_hist_r(trades, hist)
        if len(rs) < 30:
            risk_pct = cfg["warmup_risk"]
        else:
            wins = [r for r in rs if r > 0]; loss = [-r for r in rs if r <= 0]
            if not wins or not loss:
                risk_pct = cfg["warmup_risk"]
            else:
                W = len(wins) / len(rs); Rr = (sum(wins) / len(wins)) / (sum(loss) / len(loss))
                f = W - (1 - W) / Rr
                risk_pct = min(cfg["kelly_cap"], max(0.0, f) * cfg["kelly"][score])
        if skip:
            risk_pct = 0.0
        tr.update(score=score, strength=strength, q=(q1, q2, q3, q4, q5), skipped=skip, risk_pct=risk_pct)
        tr["ret_fixed"] = cfg["fixed_risk"] * tr["r_net"]
        tr["ret_kelly"] = risk_pct * tr["r_net"]
        hist.append(1 if tr["r_net"] > 0 else 0)
        tr["_r"] = tr["r_net"]
    return trades


_RS = []
def tr_hist_r(trades, hist):
    # R multiples of the previous len(hist) strategy-level trades (window = last kelly_window)
    k = len(hist)
    return [t["r_net"] for t in trades[max(0, k - CFG["kelly_window"]):k]]


def stats(rs, label=""):
    n = len(rs)
    if n == 0:
        return f"{label:<22} n=0"
    wins = [r for r in rs if r > 0]
    gp, gl = sum(wins), -sum(r for r in rs if r <= 0)
    eq, peak, mdd = 0.0, 0.0, 0.0
    for r in rs:
        eq += r; peak = max(peak, eq); mdd = max(mdd, peak - eq)
    return (f"{label:<22} n={n:<5} win={len(wins) / n:5.1%} exp={sum(rs) / n:+.3f}R tot={sum(rs):+8.1f}R "
            f"PF={(gp / gl if gl else float('inf')):.2f} maxDD={mdd:.1f}R")


def equity_stats(trades, key, days):
    eq, peak, mdd = 1.0, 1.0, 0.0
    daily = collections.defaultdict(float)
    for t in trades:
        eq *= 1 + t[key]
        peak = max(peak, eq); mdd = max(mdd, 1 - eq / peak)
        daily[(t["exit_t"] - 5 * H_MS) // DAY_MS] += t[key]
    vals = [daily.get(d, 0.0) for d in days]
    mu = sum(vals) / len(vals)
    sd = (sum((x - mu) ** 2 for x in vals) / (len(vals) - 1)) ** 0.5 if len(vals) > 1 else 0
    return eq - 1, mdd, (mu / sd * math.sqrt(252) if sd else 0.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv"); ap.add_argument("--tick", type=float, default=0.25)
    ap.add_argument("--pt-value", type=float, default=20.0); ap.add_argument("--out", default="reports/gb_nq")
    ap.add_argument("--thr", type=float, default=None)
    a = ap.parse_args()
    cfg = dict(CFG)
    if a.thr is not None:
        cfg["thr_pts"] = a.thr
    D = build(a.csv, a.tick, cfg)
    log("indicators: ATR percentile, volume SMA, BOCPD (slow)...")
    atrp = atr_pct(D, cfg); vma = sma_prev(D["bv"], cfg["vol_ma"]); ages = bocpd_age(D, cfg)
    S = D["sessions"]
    frames = {}
    for d in ("long", "short"):
        if d == "long":
            X = dict(o=D["o"], h=D["h"], l=D["l"], c=D["c"], bo=D["bo"], bh=D["bh"], bl=D["bl"], bc=D["bc"],
                     vah=[s["vah"] for s in S], val=[s["val"] for s in S])
        else:
            X = dict(o=[-x for x in D["o"]], h=[-x for x in D["l"]], l=[-x for x in D["h"]], c=[-x for x in D["c"]],
                     bo=[-x for x in D["bo"]], bh=[-x for x in D["bl"]], bl=[-x for x in D["bh"]], bc=[-x for x in D["bc"]],
                     vah=[-s["val"] for s in S], val=[-s["vah"] for s in S])
        X["bos"] = bos_list(X["bh"], X["bl"], X["bc"], X["bo"], cfg["fractal"])
        frames[d] = X
    log("BOS events:", {d: len(frames[d]["bos"]) for d in frames})
    allt = []
    for d, X in frames.items():
        tr = gen(D, X, cfg, a.tick, a.pt_value, d, atrp, ages, vma)
        if d == "short":   # un-mirror price fields for reporting
            for t in tr:
                for k in ("entry", "stop", "target", "exit", "ms_low", "ms_high", "prev_vah", "prev_val"):
                    t[k] = -t[k]
        log(f"{d}: {len(tr)} trades")
        allt += tr
    allt.sort(key=lambda t: t["entry_t"])
    merged, free = [], -1
    for t in allt:
        if t["entry_t"] >= free:
            merged.append(t); free = t["exit_t"]
    log(f"merged (one position at a time): {len(merged)} (dropped {len(allt) - len(merged)} overlapping)")
    score_and_size(merged, cfg)
    days = sorted({(s["sid"] - 0) for s in S if s["valid"]})
    # sid -> use exit-day keys consistent with equity_stats (ET date): both are day numbers
    days = [d for d in days if date.fromordinal(719163 + d).weekday() < 5]
    out = []
    P = out.append
    P(f"# NQ run: {len(S)} sessions, {S[0]['sid']}..{S[-1]['sid']}; config {json.dumps({k: v for k, v in cfg.items() if k != 'kelly'})}")
    R = [t["r_net"] for t in merged]
    P(stats(R, "ALL (net of costs)"))
    P(stats([t["r_gross"] for t in merged], "ALL (gross)"))
    for d in ("long", "short"):
        P(stats([t["r_net"] for t in merged if t["dir"] == d], d))
    P(stats([t["r_net"] for t in merged if t["first"]], "first trades"))
    P(stats([t["r_net"] for t in merged if not t["first"]], "re-entries"))
    P("--- by exit reason")
    for rsn in ("target", "ms_high", "stop", "session_end"):
        P(stats([t["r_net"] for t in merged if t["reason"] == rsn], rsn))
    P("--- by year")
    for y in sorted({datetime.datetime.fromtimestamp(t["entry_t"] / 1000, timezone.utc).year for t in merged}):
        P(stats([t["r_net"] for t in merged if datetime.datetime.fromtimestamp(t["entry_t"] / 1000, timezone.utc).year == y], str(y)))
    P("--- his buckets (is HIS 'best' bucket actually best on NQ?)")
    def bucket(t):
        a_ = t["atr_pct"]
        return "LOW" if a_ is not None and a_ <= 25 else "MED" if a_ is not None and a_ <= 50 else "HIGH" if a_ is not None and a_ <= 75 else "EXTREME" if a_ is not None else "n/a"
    for b_ in ("LOW", "MED", "HIGH", "EXTREME"):
        P(stats([t["r_net"] for t in merged if bucket(t) == b_], "ATR " + b_))
    for nm, fn in (("FRESH 0-30", lambda t: t["age"] <= 30), ("MATURE 31-60", lambda t: 30 < t["age"] <= 60),
                   ("OLD >60", lambda t: t["age"] > 60)):
        P(stats([t["r_net"] for t in merged if fn(t)], nm))
    P(stats([t["r_net"] for t in merged if 480 < t["close_min"] <= 1020], "New York 08-17 ET"))
    P(stats([t["r_net"] for t in merged if not 480 < t["close_min"] <= 1020], "outside NY"))
    P(stats([t["r_net"] for t in merged if t["reducing"]], "reducing volume"))
    P(stats([t["r_net"] for t in merged if not t["reducing"]], "not reducing (low-effort only)"))
    P("--- by meta-label score")
    for sc in range(6):
        P(stats([t["r_net"] for t in merged if t["score"] == sc], f"score {sc}"))
    P(stats([t["r_net"] for t in merged if t["skipped"]], "streak-skipped"))
    P("--- equity views")
    for key, nm in (("ret_fixed", "fixed 0.5% risk/trade"), ("ret_kelly", "Kelly-table sizing")):
        tot, mdd, sh = equity_stats(merged, key, days)
        P(f"{nm:<24} return={tot:+.1%} maxDD={mdd:.1%} Sharpe={sh:.2f}")
    h = len(merged) // 2
    P("--- halves (no refit)")
    P(stats(R[:h], "first half")); P(stats(R[h:], "second half"))
    P("--- his claim: 727 trades, +147R (0.20R/trade), Sharpe 2.2, +103.9% at 0.5% risk, maxDD -13.1%")
    text = "\n".join(out)
    print(text)
    import os
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "summary.txt"), "w") as fh:
        fh.write(text + "\n")
    with open(os.path.join(a.out, "trades.csv"), "w") as fh:
        cols = ["dir", "entry_t", "exit_t", "entry", "stop", "target", "exit", "reason", "risk", "r_gross", "r_net", "first",
                "ms_low", "ms_high", "prev_vah", "prev_val", "atr_pct", "age", "reducing", "lowef", "close_min", "score",
                "strength", "skipped", "risk_pct"]
        fh.write(",".join(cols) + "\n")
        for t in merged:
            fh.write(",".join(str(t[c]) for c in cols) + "\n")
    log("wrote", a.out)


if __name__ == "__main__":
    main()
