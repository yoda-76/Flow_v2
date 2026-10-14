"""Signal generation -> strategy-level trade list (plan §5.5-5.9).

Shorts are run as longs on a mirrored price series (o,h,l,c -> -o,-l,-h,-c; VAH <-> -VAL), so there is one code
path. One position at a time across both directions. Prices are int ticks; thresholds may be fractional ticks.
"""
from array import array

import indicators as ind
import timeutil as tu
import vprofile as prof

COMMISSION = 4.60       # $ per round trip per contract
TICK_USD = 10.0
LAST_ENTRY_REL = tu.SESSION_LEN_MIN - 15   # bar must close before 16:45 ET (minutes after the 18:00 open)
NEWS_FROM, NEWS_TO = 8 * 60 + 15, 8 * 60 + 45

BASELINE = {
    "tf": 15, "thr": "t0", "vol_cond": "either", "target_r": 2.0, "swing_n": 3,
    "va_pct": 70, "vol_ma": "sma", "trend": "both", "trend_timing": "completed", "ceiling": "on", "min_rr": 1.0,
    "reentry_vah": "off", "session_end": "flatten", "stop_buf": 0, "news": "off", "ms_break": "trade",
    "setups": "one", "direction": "both",
    # P4 gold variants (baseline = the behaviour before P4)
    "sess_filter": "all", "atr_filter": "all", "fresh_filter": "all", "stop_atr": 0.0, "ma_filter": "off",
}
THR_VALUES = ["t0", "t2", "t4", "t6", "t10", "t15", "t20", "t30", "a0.1", "a0.2", "a0.3"]

IDLE, ABOVE, ACTIVE, TRADED, ENDED = range(5)


class Side:
    """Price series for one direction (+1 long as-is, -1 short mirrored)."""

    def __init__(self, sgn, base, f):
        b = f.bars
        self.sgn = sgn
        if sgn > 0:
            self.o, self.h, self.l, self.c = b.o, b.h, b.l, b.c
            self.mo, self.mh, self.ml, self.mc = base.o, base.h, base.l, base.c
        else:
            neg = lambda a: array("l", (-x for x in a))  # noqa: E731
            self.o, self.h, self.l, self.c = neg(b.o), neg(b.l), neg(b.h), neg(b.c)
            self.mo, self.mh, self.ml, self.mc = neg(base.o), neg(base.l), neg(base.h), neg(base.c)


def mirror_va(va, sgn):
    """(poc, vah, val) for this side: shorts see (-poc, -val, -vah)."""
    if va is None or sgn > 0:
        return va
    return (-va[0], -va[2], -va[1])


def bos_events(side_h, side_c, swing_high, n):
    """BOS list [(bos_bar, swing_bar)] for one side: close above the most recent known, unbroken swing high.
    A swing at bar k is known at bar k+n."""
    out = []
    last_sw = -1
    broken = True
    for i in range(len(side_c)):
        k = i - n
        if k >= 0 and swing_high[k]:
            last_sw = k; broken = False
        if not broken and side_c[i] > side_h[last_sw]:
            out.append((i, last_sw)); broken = True
    return out


class Ctx:
    """Everything a run needs that doesn't depend on rule params beyond (tf, swing_n); built once per worker."""

    def __init__(self, base, vas, f):
        self.base, self.vas, self.f = base, vas, f
        self.sides = {1: Side(1, base, f), -1: Side(-1, base, f)}
        self.bos = {}          # (sgn, n) -> (list of bos bars, list of swing bars)
        self.dev_cache = {}
        self.ma = {}           # (sgn, n) -> SMA(n) of the side's TF closes

    def ma_for(self, sgn, n):
        key = (sgn, n)
        if key not in self.ma:
            self.ma[key] = ind.vol_sma(self.sides[sgn].c, n)
        return self.ma[key]

    def bos_for(self, sgn, n):
        key = (sgn, n)
        if key not in self.bos:
            sh, sl = self.f.swings[n]
            s = self.sides[sgn]
            ev = bos_events(s.h, s.c, sh if sgn > 0 else sl, n)
            self.bos[key] = ([e[0] for e in ev], [e[1] for e in ev])
        return self.bos[key]

    def dev_va(self, si, upto, pct):
        key = (si, upto, pct)
        v = self.dev_cache.get(key)
        if v is None:
            v = self.dev_cache[key] = prof.session_va(self.base, si, pct, upto)
        return v


def thr_ticks(spec, atr_ticks):
    return float(spec[1:]) if spec[0] == "t" else float(spec[1:]) * atr_ticks


def ms_levels(side, bos_bars, bos_sw, touch, legs=3):
    """(ms_low, ms_high) at the touch bar, or None. bos_bars sorted ascending."""
    import bisect
    j = bisect.bisect_right(bos_bars, touch) - 1
    if j < 0:
        return None
    last_bos = bos_bars[j]
    ms_low = None
    for t in range(j, max(-1, j - legs), -1):
        b, sw = bos_bars[t], bos_sw[t]
        o, c = side.o, side.c
        h, l = side.h, side.l
        found = False
        for x in range(b, sw - 1, -1):
            body = abs(c[x] - o[x])
            if c[x] < o[x] and body > max(h[x] - max(o[x], c[x]), min(o[x], c[x]) - l[x]):
                found = True
                break
        if found:
            ms_low = min(l[sw:b + 1])
            break
    if ms_low is None:
        return None
    return ms_low, max(side.h[last_bos:touch + 1])


def walk_exit(side, base, start, stop, target, flatten, sess_end):
    """Walk 1m bars from `start`. Returns (exit_idx, exit_price, reason). Stop wins ties."""
    mo, mh, ml, mc = side.mo, side.mh, side.ml, side.mc
    n = len(mc)
    end = sess_end if flatten else n
    for i in range(start, end):
        if ml[i] <= stop:
            return i, (mo[i] if mo[i] < stop else stop) - 1, "stop"
        if mh[i] >= target + 1:
            return i, target, "target"
    if flatten:
        return end - 1, mc[end - 1] - 1, "session_end"
    return n - 1, mc[n - 1] - 1, "data_end"


def session_bucket(mod):
    m = (mod - 1) % 1440
    if m >= 18 * 60 or m < 2 * 60:
        return "ASIA"
    return "LONDON" if m < 8 * 60 else "NY"


def run(ctx, p, si_from=1, si_to=None):
    """Strategy-level trade list for params p over sessions [si_from, si_to)."""
    base, vas, f = ctx.base, ctx.vas, ctx.f
    b = f.bars
    va = vas[p["va_pct"]]
    vma = f.vol_sma if p["vol_ma"] == "sma" else f.vol_ema
    vol = b.v
    n_sw = p["swing_n"]
    dirs = {"both": (1, -1), "long": (1,), "short": (-1,)}[p["direction"]]
    flatten = p["session_end"] == "flatten"
    ceiling = p["ceiling"] == "on"
    min_rr = p["min_rr"]
    tgt_r = p["target_r"]
    buf = p["stop_buf"]
    vc = p["vol_cond"]
    many = p["setups"] == "many"
    news = p["news"] != "off"
    close_break = p["ms_break"] == "close"
    trend = p["trend"]
    developing = p["trend_timing"] == "developing"
    re_vah = p["reentry_vah"] == "on"
    sess_f = p.get("sess_filter", "all")
    atr_f = p.get("atr_filter", "all")
    fresh_f = p.get("fresh_filter", "all")
    stop_atr = p.get("stop_atr", 0.0)
    ma_f = p.get("ma_filter", "off")
    ma_n = int(ma_f) if ma_f != "off" else 0
    bos = {s: ctx.bos_for(s, n_sw) for s in dirs}
    si_to = len(base.s_day) if si_to is None else si_to

    trades = []
    flat_after = -1        # 1m index of the open trade's exit; flat at bar k if flat_after < b.i1[k]

    def trend_ok(sgn, si, k):
        if trend == "none":
            return True
        prev = mirror_va(va[si - 1], sgn)
        if developing:
            cur = mirror_va(ctx.dev_va(si, b.i1[k], p["va_pct"]), sgn)
        else:
            if si < 2:
                return False
            cur, prev = prev, mirror_va(va[si - 2], sgn)
        if cur is None or prev is None:
            return False
        if trend == "poc":
            return cur[0] > prev[0]
        return cur[1] > prev[1] and cur[2] > prev[2]

    for si in range(max(si_from, 1), si_to):
        k0, k1 = b.s_first[si], b.s_last[si]
        open_min = tu.session_open_et_min(base.s_day[si])
        sess_end = base.s_end[si]
        st = {}
        for sgn in dirs:
            pv = mirror_va(va[si - 1], sgn)
            ok = pv is not None and (developing or trend_ok(sgn, si, k0))
            st[sgn] = {"state": IDLE if ok else ENDED, "vah": pv[1] if pv else 0, "ms_low": 0, "ms_high": 0,
                       "touch": -1}
        for k in range(k0, k1):
            mod = b.close_et[k] % 1440
            for sgn in dirs:
                s = st[sgn]
                if s["state"] == ENDED:
                    continue
                side = ctx.sides[sgn]
                h, l, c = side.h, side.l, side.c
                vah = s["vah"]
                if s["state"] == IDLE:
                    if h[k] > vah:
                        s["state"] = ABOVE
                    continue
                if s["state"] == ABOVE:
                    if l[k] > vah:
                        continue
                    lv = ms_levels(side, bos[sgn][0], bos[sgn][1], k)
                    if lv is None:
                        s["state"] = IDLE if many else ENDED
                        continue
                    s["ms_low"], s["ms_high"] = lv
                    s["touch"] = k
                    s["state"] = ACTIVE
                # ACTIVE or TRADED
                if (c[k] if close_break else l[k]) < s["ms_low"]:
                    s["state"] = IDLE if many else ENDED
                    continue
                if flat_after < b.i1[k] and b.close_et[k] - open_min < LAST_ENTRY_REL and k > 0:
                    thr = thr_ticks(p["thr"], f.atr[k])
                    first = s["state"] == ACTIVE
                    sig = c[k] >= h[k - 1] + thr
                    if sig and (first or re_vah):
                        sig = c[k] >= vah + thr
                    if sig:
                        red = vol[k] < vol[k - 1]
                        low = vol[k] < vma[k - 1]
                        sig = (vc == "none" or (vc == "either" and (red or low)) or (vc == "reducing" and red)
                               or (vc == "loweffort" and low) or (vc == "both" and red and low))
                    if sig and news:
                        bar_open = mod - b.tf if mod >= b.tf else mod - b.tf + 1440
                        sig = not (bar_open < NEWS_TO and mod > NEWS_FROM)
                    if sig and developing:
                        sig = trend_ok(sgn, si, k)
                    if sig and sess_f != "all":
                        sb = session_bucket(mod)
                        sig = sb != "NY" if sess_f == "no_ny" else sb == "ASIA"
                    if sig and atr_f == "no_low":
                        sig = f.atr_pct[k] > 25
                    if sig and fresh_f == "fresh_only":
                        sig = f.age[k] <= 30
                    if sig and ma_f != "off":
                        sig = k >= ma_n and c[k] > ctx.ma_for(sgn, ma_n)[k]
                    if sig:
                        entry = c[k] + 1
                        stop = l[k] - buf - int(round(stop_atr * f.atr[k]))
                        risk = entry - stop
                        target = entry + tgt_r * risk
                        if risk < 2:
                            sig = False
                        elif ceiling:
                            if s["ms_high"] <= entry:
                                s["state"] = IDLE if many else ENDED
                                continue
                            if target > s["ms_high"]:
                                target = s["ms_high"]
                            if (target - entry) / risk < min_rr:
                                sig = False
                    if sig:
                        target = int(round(target))
                        xi, xp, why = walk_exit(side, base, b.i1[k], stop, target, flatten, sess_end)
                        flat_after = xi
                        r = (xp - entry) / risk
                        trades.append({
                            "dir": sgn, "si": si, "k": k, "entry_i": b.i1[k] - 1, "exit_i": xi,
                            "entry": sgn * entry, "stop": sgn * stop, "target": sgn * target, "exit": sgn * xp,
                            "risk": risk, "r": r, "usd": (xp - entry) * TICK_USD - COMMISSION, "reason": why,
                            "first": s["state"] == ACTIVE, "atr_b": ind.atr_bucket(f.atr_pct[k]),
                            "fresh_b": ind.fresh_bucket(f.age[k]), "sess_b": session_bucket(mod),
                            "reducing": vol[k] < vol[k - 1], "mod": mod, "vah": sgn * vah, "ms_low": sgn * s["ms_low"],
                            "ms_high": sgn * s["ms_high"], "touch": s["touch"], "thr": thr,
                        })
                        s["state"] = TRADED
                if k > s["touch"] and h[k] >= s["ms_high"]:
                    s["state"] = IDLE if many else ENDED
    return trades


def needs_dev(p):
    return p["trend_timing"] == "developing" and p["trend"] != "none"
