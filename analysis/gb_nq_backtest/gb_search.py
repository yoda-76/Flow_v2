"""Gianluca Bruni strategy on NQ 1m OHLCV: feature precompute + parameter search + in/out-of-sample test.
Stdlib only. Read-only over the CSV; writes only under --out.
    python gb_search.py precompute <csv> --out reports/gb_nq        (slow, once: ~10 min)
    python gb_search.py search --out reports/gb_nq [--workers 6]    (stages S0..S3, resumable via runs.jsonl)
Rules/guesses: docs/dynamic/gianlucaBruniBacktest_plan_v1.md §5, extended with variants for each guess.
"""
import sys, os, math, bisect, json, time, pickle, random, hashlib, datetime, argparse, collections
from array import array
from datetime import date, timedelta, timezone

H_MS, DAY_MS, TICK, PTV, COMM = 3_600_000, 86_400_000, 0.25, 20.0, 4.60
WIN_LO = date(2019, 12, 1)          # warm-up start (earlier months are sparse)
TRADE_LO, TRADE_HI = date(2020, 4, 1), date(2026, 3, 31)
TFS = (5, 10, 15, 30)
VAS = (0.6, 0.7, 0.8)
NAN = float("nan")


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def sid_of_date(d):
    return d.toordinal() - 719163


# ------------------------------------------------------------------ precompute -------------------------------------------
def et_transitions():
    tr = [(-(1 << 62), -5 * H_MS)]
    for y in range(2017, 2030):
        d = date(y, 3, 1); d += timedelta((6 - d.weekday()) % 7 + 7)
        tr.append((int(datetime.datetime(y, 3, d.day, 7, 0, tzinfo=timezone.utc).timestamp() * 1000), -4 * H_MS))
        d = date(y, 11, 1); d += timedelta((6 - d.weekday()) % 7)
        tr.append((int(datetime.datetime(y, 11, d.day, 6, 0, tzinfo=timezone.utc).timestamp() * 1000), -5 * H_MS))
    tr.sort()
    return [t for t, _ in tr], [o for _, o in tr]


def value_area(prof, pct):
    m_ = len(prof)
    total = sum(prof)
    poc = max(range(m_), key=prof.__getitem__)
    lo = hi = poc
    inside, target = prof[poc], pct * total
    while inside < target:
        up = (prof[hi + 1] if hi + 1 < m_ else 0) + (prof[hi + 2] if hi + 2 < m_ else 0)
        dn = (prof[lo - 1] if lo - 1 >= 0 else 0) + (prof[lo - 2] if lo - 2 >= 0 else 0)
        if (up == 0 and dn == 0) or (hi >= m_ - 1 and lo <= 0):
            break
        if up >= dn:
            hi = min(hi + 2, m_ - 1); inside += up
        else:
            lo = max(lo - 2, 0); inside += dn
    return lo, hi, poc


def atr_pct_series(h, l, c, n=14, look=200):
    N = len(h)
    out = array("d", [NAN]) * N
    tr = [h[0] - l[0]] + [max(h[k] - l[k], abs(h[k] - c[k - 1]), abs(l[k] - c[k - 1])) for k in range(1, N)]
    atr, win, srt = None, collections.deque(), []
    for k in range(N):
        if k == n - 1:
            atr = sum(tr[:n]) / n
        elif k >= n:
            atr = (atr * (n - 1) + tr[k]) / n
        if atr is None:
            continue
        win.append(atr); bisect.insort(srt, atr)
        if len(win) > look:
            old = win.popleft(); del srt[bisect.bisect_left(srt, old)]
        if len(win) >= 100:
            out[k] = 100.0 * bisect.bisect_right(srt, atr) / len(srt)
    return out


def bocpd_ages(h, l, hazard=0.02, L=120):
    x = [math.log(max(a / b, 1.000001)) for a, b in zip(h, l)]
    n0 = min(500, len(x))
    mu0 = sum(x[:n0]) / n0
    b0 = max(sum((a - mu0) ** 2 for a in x[:n0]) / n0, 1e-12)
    k0 = a0 = 1.0
    lg = [math.lgamma(a0 + r / 2 + 0.5) - math.lgamma(a0 + r / 2) for r in range(L + 2)]
    R, mu, ka, al, be = [1.0], [mu0], [k0], [a0], [b0]
    ages = array("i")
    ln, l1p, exp, pi = math.log, math.log1p, math.exp, math.pi
    for xv in x:
        m = len(R)
        pred = [0.0] * m
        for r in range(m):
            a = al[r]
            sc = be[r] * (ka[r] + 1) / (a * ka[r])
            pred[r] = exp(lg[r] - 0.5 * ln(pi * 2 * a * sc) - (a + 0.5) * l1p((xv - mu[r]) ** 2 / (2 * a * sc)))
        cp = 0.0
        growth = [0.0] * m
        for r in range(m):
            w = R[r] * pred[r]
            cp += w
            growth[r] = w * (1 - hazard)
        newR = [cp * hazard] + growth
        s = sum(newR)
        newR = [q / s for q in newR] if s > 0 else [1.0] + [0.0] * m
        nmu = [mu0] + [(ka[r] * mu[r] + xv) / (ka[r] + 1) for r in range(m)]
        nka = [k0] + [ka[r] + 1 for r in range(m)]
        nal = [a0] + [al[r] + 0.5 for r in range(m)]
        nbe = [b0] + [be[r] + ka[r] * (xv - mu[r]) ** 2 / (2 * (ka[r] + 1)) for r in range(m)]
        if len(newR) > L:
            newR[L - 1] += sum(newR[L:]); newR = newR[:L]
            nmu, nka, nal, nbe = nmu[:L], nka[:L], nal[:L], nbe[:L]
        R, mu, ka, al, be = newR, nmu, nka, nal, nbe
        ages.append(max(range(len(R)), key=R.__getitem__))
    return ages


def vol_ma_prev(v, n=20):
    sma = array("d", [NAN]) * len(v)
    ema = array("d", [NAN]) * len(v)
    run, a, e = 0.0, 2 / (n + 1), None
    for k in range(len(v)):
        if k >= n:
            sma[k] = run / n
            run -= v[k - n]
        if e is not None:
            ema[k] = e                       # EMA of the bars BEFORE bar k
        run += v[k]
        e = v[k] if e is None else a * v[k] + (1 - a) * e
    return sma, ema


def precompute(csv_path, out_dir):
    t0 = time.time()
    lo_ms = int(datetime.datetime(WIN_LO.year, WIN_LO.month, WIN_LO.day, tzinfo=timezone.utc).timestamp() * 1000)
    hi_ms = int(datetime.datetime(2026, 4, 2, tzinfo=timezone.utc).timestamp() * 1000)
    T, O, H, L, C, V = array("q"), array("d"), array("d"), array("d"), array("d"), array("d")
    with open(csv_path) as fh:
        fh.readline()
        for line in fh:
            p = line.split(",")
            t = int(p[0])
            if t < lo_ms or t >= hi_ms:
                continue
            T.append(t); O.append(float(p[1])); H.append(float(p[2])); L.append(float(p[3])); C.append(float(p[4]))
            V.append(float(p[5]))
    n = len(T)
    log(f"1m bars in window: {n}")
    tt, to = et_transitions()
    sid_arr, m_arr = array("i"), array("i")
    for i in range(n):
        x = T[i] + to[bisect.bisect_right(tt, T[i]) - 1] + 6 * H_MS
        sid_arr.append(x // DAY_MS); m_arr.append((x % DAY_MS) // 60000)
    # sessions + completed volume profiles (one profile, three VA%)
    sess = []
    i = 0
    while i < n:
        j = i
        s = sid_arr[i]
        while j + 1 < n and sid_arr[j + 1] == s:
            j += 1
        S = dict(sid=s, i0=i, i1=j, valid=(j - i + 1) >= 300)
        if S["valid"]:
            lo_t = [round(x / TICK) for x in L[i:j + 1]]
            hi_t = [round(x / TICK) for x in H[i:j + 1]]
            mn, mx = min(lo_t), max(hi_t)
            diff = [0.0] * (mx - mn + 3)
            for a, b, vol in zip(lo_t, hi_t, V[i:j + 1]):
                per = vol / (b - a + 1)
                diff[a - mn] += per; diff[b - mn + 1] -= per
            prof, run = [], 0.0
            for d_ in diff[:mx - mn + 1]:
                run += d_
                prof.append(run if run > 1e-9 else 0.0)
            S["va"] = {}
            for pct in VAS:
                lo, hi, poc = value_area(prof, pct)
                S["va"][pct] = ((mn + hi) * TICK, (mn + lo) * TICK, (mn + poc) * TICK)   # vah, val, poc
        sess.append(S)
        i = j + 1
    log(f"{len(sess)} sessions, {sum(1 for s in sess if s['valid'])} valid")
    P = dict(t=T, o=O, h=H, l=L, c=C, v=V, sess=sess, tf={})
    for tf in TFS:
        t1 = time.time()
        bo, bh, bl, bc, bv = array("d"), array("d"), array("d"), array("d"), array("d")
        bm0, bm1, bi1 = array("i"), array("i"), array("i")
        sb0, sb1 = array("i"), array("i")
        for S in sess:
            i, j = S["i0"], S["i1"]
            sb0.append(len(bo))
            k = i
            while k <= j:
                slot = m_arr[k] // tf
                e = k
                while e + 1 <= j and m_arr[e + 1] // tf == slot:
                    e += 1
                bo.append(O[k]); bh.append(max(H[k:e + 1])); bl.append(min(L[k:e + 1])); bc.append(C[e])
                bv.append(sum(V[k:e + 1])); bm0.append(slot * tf); bm1.append(slot * tf + tf); bi1.append(e)
                k = e + 1
            sb1.append(len(bo) - 1)
        td = dict(bo=bo, bh=bh, bl=bl, bc=bc, bv=bv, bm0=bm0, bm1=bm1, bi1=bi1, sb0=sb0, sb1=sb1)
        td["atr"] = atr_pct_series(bh, bl, bc)
        td["sma"], td["ema"] = vol_ma_prev(bv)
        td["age"] = bocpd_ages(bh, bl)
        if tf == 15:      # developing value area at each bar close (70%)
            dvah, dval = array("d", [NAN]) * len(bo), array("d", [NAN]) * len(bo)
            bi0_of = {}
            for si, S in enumerate(sess):
                if not S["valid"]:
                    continue
                i, j = S["i0"], S["i1"]
                lo_t = [round(x / TICK) for x in L[i:j + 1]]; hi_t = [round(x / TICK) for x in H[i:j + 1]]
                mn, mx = min(lo_t), max(hi_t)
                prof = [0.0] * (mx - mn + 1)
                k = i
                for b in range(sb0[si], sb1[si] + 1):
                    while k <= bi1[b]:
                        a, z = lo_t[k - i] - mn, hi_t[k - i] - mn
                        per = V[k] / (z - a + 1)
                        for q in range(a, z + 1):
                            prof[q] += per
                        k += 1
                    lo, hi, _ = value_area(prof, 0.70)
                    dvah[b], dval[b] = (mn + hi) * TICK, (mn + lo) * TICK
            td["dvah"], td["dval"] = dvah, dval
        P["tf"][tf] = td
        log(f"tf={tf}: {len(bo)} bars, features done in {time.time() - t1:.0f}s")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "cache.pkl"), "wb") as fh:
        pickle.dump(P, fh, protocol=4)
    log(f"cache written, total {time.time() - t0:.0f}s")


# ------------------------------------------------------------------ signal generation --------------------------------------
_P = None
_CACHE = {}


def load_cache(out_dir):
    global _P
    if _P is None:
        with open(os.path.join(out_dir, "cache.pkl"), "rb") as fh:
            _P = pickle.load(fh)
    return _P


def bos_list(H, L, C, N):
    out, cur = [], None
    for b in range(len(H)):
        k = b - N
        if k >= N and all(H[k] > H[k - q] for q in range(1, N + 1)) and all(H[k] > H[k + q] for q in range(1, N + 1)):
            cur = (H[k], k)
        if cur is not None and C[b] > cur[0]:
            out.append((b, cur[1])); cur = None
    return out


def frame(P, tf, side):
    key = (tf, side)
    if key in _CACHE:
        return _CACHE[key]
    td = P["tf"][tf]
    if side == "L":
        f = dict(O=td["bo"], H=td["bh"], L=td["bl"], C=td["bc"])
    else:
        neg = lambda a: array("d", [-x for x in a])
        f = dict(O=neg(td["bo"]), H=neg(td["bl"]), L=neg(td["bh"]), C=neg(td["bc"]))
    f["bos"] = {}
    _CACHE[key] = f
    return f


def m1(P, side):
    key = ("m1", side)
    if key not in _CACHE:
        if side == "L":
            _CACHE[key] = (P["o"], P["h"], P["l"], P["c"])
        else:
            neg = lambda a: array("d", [-x for x in a])
            _CACHE[key] = (neg(P["o"]), neg(P["l"]), neg(P["h"]), neg(P["c"]))
    return _CACHE[key]


def gen(P, cfg, side, lo_sid, hi_sid):
    tf = cfg["tf"]
    td = P["tf"][tf]
    f = frame(P, tf, side)
    O, H, L, C = f["O"], f["H"], f["L"], f["C"]
    N = cfg["fractal"]
    if N not in f["bos"]:
        bl_ = bos_list(H, L, C, N)
        f["bos"][N] = (bl_, [b for b, _ in bl_])
    bos, bos_b = f["bos"][N]
    o1, h1, l1, c1 = m1(P, side)
    T1 = P["t"]
    S_ = P["sess"]
    sgn = 1 if side == "L" else -1
    bv, bm0, bm1_, bi1, sb0, sb1 = td["bv"], td["bm0"], td["bm1"], td["bi1"], td["sb0"], td["sb1"]
    vma = td["sma"] if cfg["vma"] == "sma" else td["ema"]
    atr, age = td["atr"], td["age"]
    dvah_a, dval_a = td.get("dvah"), td.get("dval")
    vk = cfg["va_pct"]

    def VA(si):
        v = S_[si]["va"][vk]
        return (v[0], v[1], v[2]) if sgn == 1 else (-v[1], -v[0], -v[2])

    def valid_bear(k):
        body = O[k] - C[k]
        return body > 0 and body > max(H[k] - O[k], C[k] - L[k])

    valid_idx = [i for i, s in enumerate(S_) if s["valid"]]
    thr_v, thr_e = cfg["thr_vah"], cfg["thr_eng"]
    slip = TICK
    buf = cfg["stop_buf"] * TICK
    trades = []
    busy_until = -1
    tm, many, above_req = cfg["trend"], cfg["setups"] == "many", cfg["above_req"]
    rn = cfg["reduc_n"]
    for p, si in enumerate(valid_idx):
        if p < 2:
            continue
        S = S_[si]
        if S["sid"] < lo_sid or S["sid"] > hi_sid:
            continue
        pi_, p2 = valid_idx[p - 1], valid_idx[p - 2]
        vah, val, poc = VA(pi_)
        vah2, val2, poc2 = VA(p2)
        if tm == "completed_both" and not (vah > vah2 and val > val2):
            continue
        if tm == "completed_poc" and not poc > poc2:
            continue
        state = 0 if above_req else 1
        traded = False
        ms_low = ms_high = None
        b0, b1 = sb0[si], sb1[si]
        pb0 = sb0[pi_]
        for b in range(b0, b1 + 1):
            hi, lo = H[b], L[b]
            if state == 0:
                if hi > vah:
                    state = 1
                continue
            if state == 1:
                if lo > vah:
                    continue
                q = bisect.bisect_left(bos_b, b) - 1
                got = None
                for back in range(3):
                    if q - back < 0:
                        break
                    bb, ss = bos[q - back]
                    if bb < pb0:
                        break
                    kk = None
                    for k in range(bb, ss - 1, -1):
                        if valid_bear(k):
                            kk = k
                            break
                    if kk is None:
                        continue
                    ml = {"leg": min(L[ss:bb + 1]), "valid_to_bos": min(L[kk:bb + 1]), "valid_candle": L[kk]}[cfg["ms_low_mode"]]
                    mh = {"peak": max(H[bos[q][0]:b + 1]), "session": max(H[b0:b + 1]),
                          "lookback": max(H[pb0:b + 1])}[cfg["ms_high_mode"]]
                    got = (ml, mh)
                    break
                if got is None:
                    if many:
                        state = 0 if above_req else 1
                        continue
                    break
                ms_low, ms_high = got
                state = 2
            broke = (C[b] < ms_low) if cfg["ms_break"] == "close" else (lo < ms_low)
            if broke or hi >= ms_high:
                if many:
                    state, traded = (0 if above_req else 1), False
                    continue
                break
            if bm1_[b] > 1380 - cfg["cutoff"] or b - 1 < b0 or bi1_close(T1, bi1, b) <= busy_until:
                continue
            if C[b] < H[b - 1] + thr_e:
                continue
            if (not traded or cfg["reentry_vah"]) and C[b] < vah + thr_v:
                continue
            if tm == "developing":
                dv, dl = (dvah_a[b], dval_a[b]) if sgn == 1 else (-dval_a[b], -dvah_a[b])
                if not (dv > vah and dl > val):
                    continue
            red = b - rn >= 0 and all(bv[b - q] < bv[b - q - 1] for q in range(rn))
            low = vma[b] == vma[b] and bv[b] < vma[b]
            vc = cfg["volc"]
            if not ((vc == "either" and (red or low)) or (vc == "reducing" and red) or (vc == "lowef" and low)
                    or (vc == "both" and red and low) or vc == "none"):
                continue
            s_min = (1080 + bm0[b]) % 1440
            if cfg["news"] and s_min < 525 and s_min + tf > 495 and date.fromordinal(719163 + S["sid"]).weekday() < 5:
                continue
            cm = (1080 + bm1_[b]) % 1440
            hf = cfg["hour_f"]
            if (hf == "ny" and not 480 < cm <= 1020) or (hf == "exasia" and not 120 < cm <= 1020):
                continue
            a_ = atr[b]
            af = cfg["atr_f"]
            if af != "none":
                if a_ != a_:
                    continue
                if (af == "gt25" and a_ <= 25) or (af == "gt50" and a_ <= 50) or (af == "25to75" and not 25 < a_ <= 75) \
                        or (af == "le75" and a_ > 75):
                    continue
            ag = cfg["age_f"]
            if (ag == "le30" and age[b] > 30) or (ag == "le60" and age[b] > 60):
                continue
            entry = C[b] + slip
            stop = L[b] - buf
            risk = entry - stop
            if risk < 2 * TICK:
                continue
            target = entry + cfg["rr"] * risk
            capped = False
            if cfg["ceiling"] and ms_high < target:
                target, capped = ms_high, True
            if target - entry <= 0 or (target - entry) < cfg["min_rr"] * risk:
                continue
            i = bi1[b] + 1
            last = S["i1"] if cfg["sess_end"] == "flatten" else S_[min(si + 3, len(S_) - 1)]["i1"]
            ex = None
            while i <= last:
                if l1[i] <= stop:
                    ex, reason = min(stop, o1[i]) - slip, 0
                    break
                if h1[i] >= target + slip:
                    ex, reason = target, 1 if capped else 2
                    break
                i += 1
            if ex is None:
                i = min(i, last)
                ex, reason = c1[i] - slip, 3
            exit_t = T1[i] + 60000
            rg = (ex - entry) / risk
            rnet = rg - COMM / (risk * PTV)
            trades.append((bi1_close(T1, bi1, b), exit_t, rnet, rg, risk, reason, not traded, S["sid"], a_, age[b], cm, red))
            traded = True
            busy_until = exit_t
            if reason == 3 and cfg["sess_end"] == "flatten":
                break
    return trades


def bi1_close(T1, bi1, b):
    return T1[bi1[b]] + 60000


# ------------------------------------------------------------------ evaluation ----------------------------------------------
def evaluate(P, cfg, lo_sid, hi_sid, want_trades=False):
    sides = {"both": ("L", "S"), "long": ("L",), "short": ("S",)}[cfg["direction"]]
    allt = []
    for sd in sides:
        for t in gen(P, cfg, sd, lo_sid, hi_sid):
            allt.append(t + (sd,))
    allt.sort(key=lambda t: t[0])
    tr, free = [], -1
    for t in allt:
        if t[0] >= free:
            tr.append(t); free = t[1]
    n = len(tr)
    R = [t[2] for t in tr]
    m = dict(n=n)
    if n:
        wins = [r for r in R if r > 0]
        gp, gl = sum(wins), -sum(r for r in R if r <= 0)
        eq = peak = mdd = 0.0
        for r in R:
            eq += r; peak = max(peak, eq); mdd = max(mdd, peak - eq)
        days = collections.defaultdict(float)
        for t in tr:
            days[(t[1] - 5 * H_MS) // DAY_MS] += 0.005 * t[2]
        alld = [s["sid"] for s in P["sess"] if s["valid"] and lo_sid <= s["sid"] <= hi_sid
                and date.fromordinal(719163 + s["sid"]).weekday() < 5]
        vals = [days.get(d, 0.0) for d in alld] or [0.0]
        mu = sum(vals) / len(vals)
        sd_ = (sum((x - mu) ** 2 for x in vals) / max(1, len(vals) - 1)) ** 0.5
        m.update(win=len(wins) / n, exp=sum(R) / n, tot=sum(R), pf=(gp / gl if gl else 99.0), mdd=mdd,
                 sharpe=(mu / sd_ * math.sqrt(252) if sd_ else 0.0))
    else:
        m.update(win=0, exp=0, tot=0, pf=0, mdd=0, sharpe=0)
    return (m, tr) if want_trades else m


# ------------------------------------------------------------------ search space ------------------------------------------
SPACE = collections.OrderedDict([
    ("tf", [15, 5, 10, 30]),
    ("thr_vah", [6.0, 0.0, 2.0, 4.0, 10.0, 15.0, 20.0]),
    ("thr_eng", [6.0, 0.0, 2.0, 4.0, 10.0, 15.0, 20.0]),
    ("va_pct", [0.7, 0.6, 0.8]),
    ("vma", ["sma", "ema"]),
    ("volc", ["either", "reducing", "lowef", "both", "none"]),
    ("reduc_n", [1, 2]),
    ("rr", [2.0, 1.0, 1.5, 2.5, 3.0]),
    ("min_rr", [0.0, 0.5, 1.0]),
    ("ceiling", [True, False]),
    ("fractal", [3, 2, 5]),
    ("trend", ["completed_both", "completed_poc", "none", "developing"]),
    ("setups", ["one", "many"]),
    ("ms_break", ["trade", "close"]),
    ("ms_low_mode", ["leg", "valid_to_bos", "valid_candle"]),
    ("ms_high_mode", ["peak", "session", "lookback"]),
    ("above_req", [True, False]),
    ("stop_buf", [0, 2, 4]),
    ("news", [True, False]),
    ("sess_end", ["flatten", "hold"]),
    ("reentry_vah", [False, True]),
    ("cutoff", [15, 0, 60]),
    ("direction", ["both", "long", "short"]),
    ("atr_f", ["none", "gt25", "gt50", "25to75", "le75"]),
    ("age_f", ["none", "le30", "le60"]),
    ("hour_f", ["all", "ny", "exasia"]),
])
BASE = {k: v[0] for k, v in SPACE.items()}


def fix(cfg):
    if cfg["trend"] == "developing" and cfg["tf"] != 15:
        cfg = dict(cfg, trend="completed_both")
    return cfg


def chash(cfg):
    return hashlib.sha1(json.dumps(cfg, sort_keys=True).encode()).hexdigest()[:12]


def rand_cfg(rng):
    cfg = {}
    for k, vals in SPACE.items():
        if k == "tf":
            cfg[k] = rng.choices(vals, weights=[0.4, 0.2, 0.2, 0.2])[0]
        else:
            cfg[k] = rng.choice(vals)
    return fix(cfg)


def neighbors(cfg):
    out = []
    for k, vals in SPACE.items():
        for v in vals:
            if v != cfg[k]:
                out.append(fix(dict(cfg, **{k: v})))
    seen, res = set(), []
    for c in out:
        h = chash(c)
        if h not in seen and c != cfg:
            seen.add(h); res.append(c)
    return res


def objective(m, floor):
    return m["tot"] if m["n"] >= floor else -1e6 + m["n"]


# ------------------------------------------------------------------ parallel runner ---------------------------------------
_OUT = None


def _init(out_dir):
    global _OUT
    _OUT = out_dir
    load_cache(out_dir)


def _eval(args):
    cfg, lo, hi = args
    return evaluate(_P, cfg, lo, hi)


def run_many(pool, cfgs, lo, hi, store, tag):
    todo = [c for c in cfgs if (chash(c), lo, hi) not in store]
    if todo:
        res = pool.map(_eval, [(c, lo, hi) for c in todo], chunksize=2)
        for c, m in zip(todo, res):
            store[(chash(c), lo, hi)] = (c, m)
    return [store[(chash(c), lo, hi)] for c in cfgs]


def fmt(m):
    return (f"n={m['n']:<4} win={m['win']:.1%} exp={m['exp']:+.3f}R tot={m['tot']:+7.1f}R PF={m['pf']:.2f} "
            f"DD={m['mdd']:.1f}R Sh={m['sharpe']:+.2f}")


def descent(pool, start, lo, hi, store, floor, max_rounds=6):
    best = start
    bm = run_many(pool, [best], lo, hi, store, "d")[0][1]
    for rnd in range(max_rounds):
        res = run_many(pool, neighbors(best), lo, hi, store, "d")
        c2, m2 = max(res, key=lambda r: objective(r[1], floor))
        if objective(m2, floor) > objective(bm, floor) + 1e-9:
            best, bm = c2, m2
        else:
            break
    return best, bm


def search(out_dir, workers, n_random, seed):
    import multiprocessing as mp
    P = load_cache(out_dir)
    sids = sorted(s["sid"] for s in P["sess"] if s["valid"] and sid_of_date(TRADE_LO) <= s["sid"] <= sid_of_date(TRADE_HI))
    lo, hi = sids[0], sids[-1]
    mid = sids[len(sids) // 2]
    h1 = (lo, mid - 1)
    h2 = (mid, hi)
    d = lambda s: date.fromordinal(719163 + s).isoformat()
    log(f"trading window {d(lo)}..{d(hi)} ({len(sids)} sessions); H1 {d(h1[0])}..{d(h1[1])}  H2 {d(h2[0])}..{d(h2[1])}")
    rng = random.Random(seed)
    store = {}
    lines = []
    W = lines.append
    with mp.Pool(workers, initializer=_init, initargs=(out_dir,)) as pool:
        FL, FLH = 100, 50
        # S0: one-at-a-time around his/baseline numbers (full window)
        base = fix(dict(BASE))
        b0 = run_many(pool, [base], lo, hi, store, "s0")[0][1]
        W(f"## S0 baseline (his numbers + plan guesses), full window\n{fmt(b0)}\n")
        nb = run_many(pool, neighbors(base), lo, hi, store, "s0")
        W("## S0 one change at a time from baseline (full window; baseline tot=%+.1fR)" % b0["tot"])
        W("| param | value | n | exp R | tot R | PF | maxDD | Sharpe | tot vs base |")
        W("|---|---|---|---|---|---|---|---|---|")
        for c, m in nb:
            ch = [(k, c[k]) for k in SPACE if c[k] != base[k]]
            k, v = ch[0]
            W(f"| {k} | {v} | {m['n']} | {m['exp']:+.3f} | {m['tot']:+.1f} | {m['pf']:.2f} | {m['mdd']:.1f} | {m['sharpe']:+.2f} | {m['tot'] - b0['tot']:+.1f} |")
        log("S0 done")
        # S1: random search, full window
        rcfgs = [rand_cfg(rng) for _ in range(n_random)]
        t1 = time.time()
        rres = run_many(pool, rcfgs, lo, hi, store, "s1")
        log(f"S1 {len(rcfgs)} random runs in {time.time() - t1:.0f}s")
        ranked = sorted(rres + nb, key=lambda r: -objective(r[1], FL))
        starts = []
        for c, m in ranked:
            if all(chash(c) != chash(s) for s in starts):
                starts.append(c)
            if len(starts) == 5:
                break
        finals = []
        for s in starts:
            finals.append(descent(pool, s, lo, hi, store, FL))
        finals.sort(key=lambda r: -objective(r[1], FL))
        A, Am = finals[0]
        W(f"\n## A* = best on the full window (after {len(store)} runs)\n{fmt(Am)}\n```\n{json.dumps(A)}\n```")
        # A* on halves
        for nm, (a, b) in (("H1", h1), ("H2", h2)):
            m = run_many(pool, [A], a, b, store, "a")[0][1]
            W(f"A* on {nm}: {fmt(m)}")
        allfull = sorted([(c, m) for (h_, a, b), (c, m) in store.items() if (a, b) == (lo, hi)],
                         key=lambda r: -objective(r[1], FL))
        W("\n### Top 15 full-window configs (differences from baseline shown)")
        W("| # | n | exp R | tot R | PF | DD | Sharpe | non-baseline settings |")
        W("|---|---|---|---|---|---|---|---|")
        seen = set()
        for c, m in allfull:
            if chash(c) in seen:
                continue
            seen.add(chash(c))
            ch = ", ".join(f"{k}={c[k]}" for k in SPACE if c[k] != base[k])
            W(f"| {len(seen)} | {m['n']} | {m['exp']:+.3f} | {m['tot']:+.1f} | {m['pf']:.2f} | {m['mdd']:.1f} | {m['sharpe']:+.2f} | {ch or 'baseline'} |")
            if len(seen) == 15:
                break
        # marginal table over the random runs (full window)
        W("\n### What each guess does on average (random runs, full window, configs with n >= %d)" % FL)
        W("| param | value | configs | mean exp R | mean tot R | best tot R |")
        W("|---|---|---|---|---|---|")
        pool_r = [(c, m) for c, m in rres if m["n"] >= FL]
        for k, vals in SPACE.items():
            for v in vals:
                sel = [m for c, m in pool_r if c[k] == v]
                if len(sel) >= 15:
                    W(f"| {k} | {v} | {len(sel)} | {sum(m['exp'] for m in sel) / len(sel):+.3f} | "
                      f"{sum(m['tot'] for m in sel) / len(sel):+.1f} | {max(m['tot'] for m in sel):+.1f} |")
        # S3: in-sample (H1) overfit -> out-of-sample (H2)
        t2 = time.time()
        r1 = run_many(pool, rcfgs, h1[0], h1[1], store, "h1")
        r1 += run_many(pool, neighbors(base), h1[0], h1[1], store, "h1")
        log(f"H1 random in {time.time() - t2:.0f}s")
        r1.sort(key=lambda r: -objective(r[1], FLH))
        st1 = []
        for c, m in r1:
            if all(chash(c) != chash(s) for s in st1):
                st1.append(c)
            if len(st1) == 5:
                break
        f1 = [descent(pool, s, h1[0], h1[1], store, FLH) for s in st1]
        f1.sort(key=lambda r: -objective(r[1], FLH))
        Bc, Bm = f1[0]
        allh1 = sorted([(c, m) for (h_, a, b), (c, m) in store.items() if (a, b) == h1], key=lambda r: -objective(r[1], FLH))
        top, seen = [], set()
        for c, m in allh1:
            if chash(c) not in seen:
                seen.add(chash(c)); top.append((c, m))
            if len(top) == 50:
                break
        oos = run_many(pool, [c for c, _ in top], h2[0], h2[1], store, "h2")
        W(f"\n## In-sample / out-of-sample (H1 {d(h1[0])}..{d(h1[1])} -> H2 {d(h2[0])}..{d(h2[1])})")
        W(f"B* (best on H1, after descent) in-sample: {fmt(Bm)}")
        Bo = run_many(pool, [Bc], h2[0], h2[1], store, "h2")[0][1]
        W(f"B* out-of-sample (H2): {fmt(Bo)}\n```\n{json.dumps(Bc)}\n```")
        ex = sorted(m["exp"] for _, m in oos)
        tt = sorted(m["tot"] for _, m in oos)
        sh = sorted(m["sharpe"] for _, m in oos)
        nn = sorted(m["n"] for _, m in oos)
        med = lambda a: a[len(a) // 2]
        isx = sorted(m["exp"] for _, m in top)
        W(f"Top-50 H1 configs: in-sample median exp={med(isx):+.3f}R; on H2: median exp={med(ex):+.3f}R, median tot={med(tt):+.1f}R, "
          f"median Sharpe={med(sh):+.2f}, median trades={med(nn)}; profitable on H2: {sum(1 for _, m in oos if m['tot'] > 0)}/{len(oos)}")
        base_h2 = run_many(pool, [base], h2[0], h2[1], store, "h2")[0][1]
        base_h1 = run_many(pool, [base], h1[0], h1[1], store, "h1")[0][1]
        W(f"Baseline (his numbers) H1: {fmt(base_h1)}\nBaseline (his numbers) H2: {fmt(base_h2)}")
        W(f"Total distinct runs: {len(store)}")
        # persist
        with open(os.path.join(out_dir, "runs.jsonl"), "w") as fh:
            for (h_, a, b), (c, m) in store.items():
                fh.write(json.dumps(dict(h=h_, lo=a, hi=b, cfg=c, m=m)) + "\n")
        # trades of A* and B* for the record
        for nm, c in (("A", A), ("B", Bc)):
            m, tr = evaluate(P, c, lo, hi, True)
            with open(os.path.join(out_dir, f"trades_{nm}star_full.csv"), "w") as fh:
                fh.write("entry_t,exit_t,r_net,r_gross,risk,reason,first,sid,atr,age,close_min,reducing,side\n")
                for t in tr:
                    fh.write(",".join(str(x) for x in t) + "\n")
    text = "\n".join(lines)
    with open(os.path.join(out_dir, "search_results.md"), "w") as fh:
        fh.write(text + "\n")
    print(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["precompute", "search"])
    ap.add_argument("csv", nargs="?")
    ap.add_argument("--out", default="reports/gb_nq")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--random", type=int, default=2500)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    if a.cmd == "precompute":
        precompute(a.csv, a.out)
    else:
        search(a.out, a.workers, a.random, a.seed)


if __name__ == "__main__":
    main()
