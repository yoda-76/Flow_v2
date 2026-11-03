"""Overnight Bias + Opening-Range Breakout (both sides) - offline backtest on NQ 1-minute bars, traded as MNQ/NQ.

Source rule file: docs/video_transcripts/strategies/distilled/MatteoConti_3_OvernightBiasORB.txt

Idea: per Central-time day, measure where the 08:30 CT open sits inside the overnight range (ONR). Open in the top
third -> long bias, bottom third -> short bias. The 08:30-08:45 15m bar is the opening range (OR). After it, a 15m
close beyond the OR in the bias direction with ADX(14) > 20 gives a signal; entry at the next 15m bar's open.
Stop = 0.30 x daily volatility, target = 3 x stop distance, flat at 14:30 CT. One trade per day.

Variant codes of the rule file -> flags (1A..13B; defaults are the "A" variants):
  1A --onr-start 0 | 1B --onr-start -60 | 1C --onr-start -420
  2A --onr-basis hl | 2B --onr-basis close
  3A --open-ref open | 3B --open-ref prevclose | 3C --open-ref orclose
  4A --thirds incl | 4B strict | 4C outside_none | 4D pct
  5A --vol-ma sma (default) | 5B --vol-ma atr14 | 5C --vol-ma ema | 5D --vol-session globex | 5E --vol-tr hl
  6A --first-fill 540 | 6B --first-fill 570 | 6C --first-fill 555
  7A --entry-mode state | 7B fresh | 7C first   (--entry-mode stop = extra intrabar stop-order variant)
  8A ADX continuous (always, no reset) | 8C --adx-len 10 / 20  (8B reset at 08:30 and 8D platform ADX not implemented)
  9A --last-fill 855 | 9B --last-fill 840
  10A --sl-ref fill | 10B --sl-ref signal | 10C --sl-ref or
  11A --tp-mode r3 | 11B --tp-mode or | 11C --tp-mode onr
  12A --time-exit-at open --time-exit 870 | 12B --time-exit-at close | 12C --time-exit 810
  13A --exit-res 15m (stop first) | 13B --exit-res 1m

Assumptions:
  - Pure standard library, no tz database: CT = UTC-5 in US DST, UTC-6 otherwise.
  - 15m bars are continuous over all hours (CT quarter-hour buckets); ADX is Wilder over that whole list, no reset.
  - Daily vol for day D uses only sessions strictly before D (see --vol-session/--vol-tr/--vol-ma). EMA is seeded with
    the first TR. atr14 = SMA(vol-len) of Wilder ATR(14) of daily TR.
  - SL/TP are computed unrounded (TP from the unrounded SL distance), then rounded to 0.25 against the trade
    (long: both down, short: both up). Trades whose levels are on the wrong side of the fill are skipped (bad_levels).
  - stop mode: ADX uses the previous completed 15m bar; with --exit-res 1m the entry is the first 1m bar inside the
    15m bar that trades through the level; --sl-ref signal falls back to the fill (no signal bar exists).
  - Both SL and TP touched in one bar: stop wins (entry bar included). Gaps handled at bar open.
  - Slippage applies to entries, stops, gap-stops and time exits; not to TP limits / gap-TP.
  - Read-only over the CSV; writes only under --out.

Example:
  python analysis/gb_nq_backtest/overnight_orb.py --from 2023-01-01 --out reports/orb_run
"""
import argparse
import bisect
import csv
import datetime
import math
import os
import random
from datetime import date, timedelta, timezone

H_MS = 3600 * 1000
DAY_MS = 86400 * 1000
TICK = 0.25
# Robustness noise (analysis/gb_nq_backtest/robustness.py). All off by default -> output unchanged.
#   --entry-shift K : enter K 1m bars after (K<0: before) the normal entry 1m bar.
#   --entry-delay F : fill at o + u*(c-o) of the entry 1m bar, u ~ U(0, F).
#   --noise-slip N  : extra adverse slippage, integer ticks ~ U{0..N}, drawn separately for entry and exit (every exit).
NOISE = dict(on=False, shift=0, delay=0.0, slip=0, rng=None)
CSV_DEFAULT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "NQ_1m_latest.csv")
OOS_START = date(2023, 1, 1)
OR_MIN = 510          # 08:30 CT
TOP_N = 53


def build_ct_transitions():
    tr = [(-(1 << 62), -6 * H_MS)]
    for y in range(2017, 2031):
        d = date(y, 3, 1); d += timedelta((6 - d.weekday()) % 7 + 7)       # 2nd Sunday of March
        s = datetime.datetime(y, 3, d.day, 8, 0, tzinfo=timezone.utc)
        d = date(y, 11, 1); d += timedelta((6 - d.weekday()) % 7)          # 1st Sunday of November
        e = datetime.datetime(y, 11, d.day, 7, 0, tzinfo=timezone.utc)
        tr.append((int(s.timestamp() * 1000), -5 * H_MS))
        tr.append((int(e.timestamp() * 1000), -6 * H_MS))
    tr.sort()
    return [t for t, _ in tr], [o for _, o in tr]


TR_T, TR_O = build_ct_transitions()
EPOCH = date(1970, 1, 1)


def ct_local(ts):
    """UTC ms -> (CT day number since epoch, CT minute of day)."""
    off = TR_O[bisect.bisect_right(TR_T, ts) - 1]
    loc = ts + off
    return loc // DAY_MS, (loc % DAY_MS) // 60000


def day_to_date(dn):
    return EPOCH + timedelta(days=dn)


def fmt_a(A):
    dn, m = divmod(A, 1440)
    return "%s %02d:%02d" % (day_to_date(dn).isoformat(), m // 60, m % 60)


def load_bars(path, min_ts):
    rows = {}
    with open(path, newline="") as f:
        rd = csv.reader(f)
        next(rd)
        for r in rd:
            try:
                ts = int(r[0])
            except ValueError:
                continue
            if ts < min_ts:
                continue
            rows[ts] = (float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
    B = dict(dn=[], m=[], A=[], o=[], h=[], l=[], c=[], v=[])
    for ts in sorted(rows):
        o, h, l, c, v = rows[ts]
        dn, m = ct_local(ts)
        B["dn"].append(dn); B["m"].append(m); B["A"].append(dn * 1440 + m)
        B["o"].append(o); B["h"].append(h); B["l"].append(l); B["c"].append(c); B["v"].append(v)
    return B


def build_15m(B):
    A = B["A"]
    S = []; O = []; H = []; L = []; C = []; V = []; I0 = []; I1 = []
    cur = None
    for i in range(len(A)):
        b = A[i] // 15
        if b != cur:
            cur = b
            S.append(b * 15); O.append(B["o"][i]); H.append(B["h"][i]); L.append(B["l"][i])
            C.append(B["c"][i]); V.append(B["v"][i]); I0.append(i); I1.append(i)
        else:
            if B["h"][i] > H[-1]: H[-1] = B["h"][i]
            if B["l"][i] < L[-1]: L[-1] = B["l"][i]
            C[-1] = B["c"][i]; V[-1] += B["v"][i]; I1[-1] = i
    return dict(s=S, o=O, h=H, l=L, c=C, v=V, i0=I0, i1=I1)


def compute_adx(B, n):
    """Wilder ADX over the continuous list. Returns list with None until available."""
    h, l, c = B["h"], B["l"], B["c"]
    N = len(c)
    adx = [None] * N
    s_tr = s_p = s_m = 0.0
    dxs = []
    prev_adx = None
    for i in range(1, N):
        tr = max(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))
        up = h[i] - h[i - 1]
        dn = l[i - 1] - l[i]
        pdm = up if (up > dn and up > 0) else 0.0
        mdm = dn if (dn > up and dn > 0) else 0.0
        if i < n:
            s_tr += tr; s_p += pdm; s_m += mdm
            continue
        if i == n:
            s_tr += tr; s_p += pdm; s_m += mdm
        else:
            s_tr = s_tr - s_tr / n + tr
            s_p = s_p - s_p / n + pdm
            s_m = s_m - s_m / n + mdm
        pdi = 100.0 * s_p / s_tr if s_tr != 0 else 0.0
        mdi = 100.0 * s_m / s_tr if s_tr != 0 else 0.0
        sm = pdi + mdi
        dx = 100.0 * abs(pdi - mdi) / sm if sm != 0 else 0.0
        if prev_adx is None:
            dxs.append(dx)
            if len(dxs) == n:
                prev_adx = sum(dxs) / n
                adx[i] = prev_adx
        else:
            prev_adx = (prev_adx * (n - 1) + dx) / n
            adx[i] = prev_adx
    return adx


# ---------------------------------------------------------------- daily volatility
def build_vol(args, B):
    """Returns (session keys sorted, vals) with vals[j] = vol using sessions ks[:j] (None if not enough)."""
    sess = {}
    dn_, m_, h_, l_, c_ = B["dn"], B["m"], B["h"], B["l"], B["c"]
    globex = args.vol_session == "globex"
    for i in range(len(dn_)):
        m = m_[i]
        if globex:
            if m >= 1020:
                key = dn_[i] + 1
            elif m < 960:
                key = dn_[i]
            else:
                continue
        else:
            if m >= 960:
                continue
            key = dn_[i]
        x = sess.get(key)
        if x is None:
            sess[key] = [h_[i], l_[i], c_[i]]
        else:
            if h_[i] > x[0]: x[0] = h_[i]
            if l_[i] < x[1]: x[1] = l_[i]
            x[2] = c_[i]
    ks = sorted(sess)
    trs = []
    prev_close = None
    for k in ks:
        H, L, C = sess[k]
        if args.vol_tr == "tr" and prev_close is not None:
            trs.append(max(H, prev_close) - min(L, prev_close))
        else:
            trs.append(H - L)
        prev_close = C
    n = args.vol_len
    vals = [None] * (len(trs) + 1)
    if args.vol_ma == "sma":
        for j in range(n, len(trs) + 1):
            vals[j] = sum(trs[j - n:j]) / n
    elif args.vol_ma == "ema":
        a = 2.0 / (n + 1)
        e = None
        for j in range(1, len(trs) + 1):
            x = trs[j - 1]
            e = x if e is None else a * x + (1 - a) * e
            if j >= n:
                vals[j] = e
    else:  # atr14
        atrs = []
        prev = None
        for t in range(len(trs)):
            if t == 13:
                prev = sum(trs[:14]) / 14.0
                atrs.append(prev)
            elif t > 13:
                prev = (prev * 13 + trs[t]) / 14.0
                atrs.append(prev)
        for j in range(14, len(trs) + 1):
            cnt = j - 13                       # atr values available from trs[:j]
            if cnt >= n:
                vals[j] = sum(atrs[cnt - n:cnt]) / n
    return ks, vals


# ---------------------------------------------------------------- helpers
def rnd_down(x):
    return math.floor(x / TICK + 1e-9) * TICK


def rnd_up(x):
    return math.ceil(x / TICK - 1e-9) * TICK


def bias_of(pos, mode):
    """+1 long, -1 short, 0 none."""
    if mode == "incl":
        return 1 if pos >= 2.0 / 3 else (-1 if pos <= 1.0 / 3 else 0)
    if mode == "strict":
        return 1 if pos > 2.0 / 3 else (-1 if pos < 1.0 / 3 else 0)
    if mode == "outside_none":
        if pos > 1 or pos < 0:
            return 0
        return 1 if pos >= 2.0 / 3 else (-1 if pos <= 1.0 / 3 else 0)
    return 1 if pos >= 0.67 else (-1 if pos <= 0.33 else 0)


def exit_walk(args, B, P, side, D, base, ent15, ent1m, fill, sl, tp, slip):
    """Returns (exit_px, exit_A, reason) or None when no bar could be walked."""
    T = args.time_exit
    lim = base + T + (15 if args.time_exit_at == "close" else 0)
    dayend = base + 1440
    seq = []
    if args.exit_res == "1m":
        A1 = B["A"]
        i = ent1m
        while i < len(A1) and A1[i] < lim and A1[i] < dayend:
            seq.append((A1[i], B["o"][i], B["h"][i], B["l"][i], B["c"][i]))
            i += 1
    else:
        S = P["s"]
        i = ent15
        while i < len(S) and S[i] < lim and S[i] < dayend:
            seq.append((S[i], P["o"][i], P["h"][i], P["l"][i], P["c"][i]))
            i += 1
    if not seq:
        return None
    for a, o, h, l, c in seq:
        if side == 1:
            if o <= sl: return o - slip, a, "GAP_SL"
            if o >= tp: return o, a, "GAP_TP"
            if l <= sl: return sl - slip, a, "SL"
            if h >= tp: return tp, a, "TP"
        else:
            if o >= sl: return o + slip, a, "GAP_SL"
            if o <= tp: return o, a, "GAP_TP"
            if h >= sl: return sl + slip, a, "SL"
            if l <= tp: return tp, a, "TP"
    if args.time_exit_at == "open":
        A1 = B["A"]
        p = bisect.bisect_left(A1, base + T)
        if p < len(A1) and A1[p] < dayend:
            return B["o"][p] - side * slip, A1[p], "TIME"
        q = p - 1
        if q >= 0 and A1[q] >= base:
            return B["c"][q] - side * slip, A1[q], "TIME"
    a, o, h, l, c = seq[-1]
    return c - side * slip, (lim if args.time_exit_at == "close" else a), "TIME"


# ---------------------------------------------------------------- main run
def run(args):
    d_from = date.fromisoformat(args.from_date)
    d_to = date.fromisoformat(args.to) if args.to else date(2100, 1, 1)
    min_ts = int(datetime.datetime.combine(d_from - timedelta(days=60), datetime.time(0),
                                           tzinfo=timezone.utc).timestamp() * 1000)
    B = load_bars(args.csv, min_ts)
    P = build_15m(B)
    adx = compute_adx(P, args.adx_len)
    ks, vals = build_vol(args, B)
    S = P["s"]
    idx15 = {s: i for i, s in enumerate(S)}
    slip = args.slip_ticks * TICK

    dns = B["dn"]
    ranges = []
    s0 = 0
    for i in range(1, len(dns) + 1):
        if i == len(dns) or dns[i] != dns[s0]:
            ranges.append((dns[s0], s0, i))
            s0 = i

    cnt = dict(window_days=0, not_full=0, no_vol=0, no_onr=0, no_or=0, long_bias=0, short_bias=0,
               no_bias=0, no_signal=0, bad_levels=0, no_walk=0, traded=0)
    trades = []
    ms = B["m"]
    for D, a, b in ranges:
        if not (d_from <= day_to_date(D) <= d_to):
            continue
        cnt["window_days"] += 1
        if not any(870 <= ms[i] < 960 for i in range(a, b)):
            cnt["not_full"] += 1
            continue
        j = bisect.bisect_left(ks, D)
        vol = vals[j]
        if vol is None or vol <= 0:
            cnt["no_vol"] += 1
            continue
        base = D * 1440
        lo = bisect.bisect_left(S, base + args.onr_start)
        hi = bisect.bisect_left(S, base + OR_MIN)
        if lo >= hi:
            cnt["no_onr"] += 1
            continue
        if args.onr_basis == "hl":
            onr_hi = max(P["h"][lo:hi]); onr_lo = min(P["l"][lo:hi])
        else:
            onr_hi = max(P["c"][lo:hi]); onr_lo = min(P["c"][lo:hi])
        k = idx15.get(base + OR_MIN)
        if k is None:
            cnt["no_or"] += 1
            continue
        orh, orl = P["h"][k], P["l"][k]
        if args.open_ref == "open":
            ref = P["o"][k]
        elif args.open_ref == "orclose":
            ref = P["c"][k]
        else:
            kp = idx15.get(base + OR_MIN - 15)
            if kp is None:
                cnt["no_or"] += 1
                continue
            ref = P["c"][kp]
        rng = onr_hi - onr_lo
        if rng <= 0:
            cnt["no_bias"] += 1
            continue
        pos = (ref - onr_lo) / rng
        side = bias_of(pos, args.thirds)
        if side == 0:
            cnt["no_bias"] += 1
            continue
        cnt["long_bias" if side == 1 else "short_bias"] += 1
        lvl = orh if side == 1 else orl
        dayend = base + 1440

        def beyond(c):
            return c > orh if side == 1 else c < orl

        def adx_ok(i):
            return adx[i] is not None and adx[i] > args.adx_th

        sig = None          # (signal bar idx, entry 15m idx, entry raw price, entry 1m idx)
        if args.entry_mode == "stop":
            i = k + 1
            while i < len(S) and S[i] < dayend:
                sm = S[i] - base
                if sm > args.last_fill:
                    break
                if sm >= args.first_fill and adx_ok(i - 1):
                    if (side == 1 and P["h"][i] >= lvl) or (side == -1 and P["l"][i] <= lvl):
                        if args.exit_res == "1m":
                            q = P["i0"][i]
                            while q <= P["i1"][i]:
                                if (side == 1 and B["h"][q] >= lvl) or (side == -1 and B["l"][q] <= lvl):
                                    break
                                q += 1
                            op = B["o"][q]
                            raw = max(op, lvl) if side == 1 else min(op, lvl)
                            sig = (i, i, raw, q)
                        else:
                            op = P["o"][i]
                            raw = max(op, lvl) if side == 1 else min(op, lvl)
                            sig = (i, i, raw, P["i0"][i])
                        break
                i += 1
        else:
            i = k + 1
            while i < len(S) and S[i] < dayend:
                s = S[i]
                nxt = idx15.get(s + 15)
                cm = s + 15 - base
                cand = (nxt is not None and s + 15 < dayend and args.first_fill <= cm <= args.last_fill)
                br = beyond(P["c"][i])
                hit = False
                if args.entry_mode == "state":
                    hit = cand and br and adx_ok(i)
                elif args.entry_mode == "fresh":
                    hit = cand and br and adx_ok(i) and not beyond(P["c"][i - 1])
                else:  # first
                    if br:
                        hit = cand and adx_ok(i)
                        if not hit:
                            break
                if hit:
                    sig = (i, nxt, P["o"][nxt], P["i0"][nxt])
                    break
                if args.entry_mode == "first" and br:
                    break
                i += 1
        if sig is None:
            cnt["no_signal"] += 1
            continue
        si, ei, raw_entry, e1 = sig
        xs, ent_a = 0.0, None
        if NOISE["on"]:
            e1 = e1 + NOISE["shift"]
            if not (0 <= e1 < len(B["A"])) or not (base <= B["A"][e1] < base + args.time_exit):
                cnt["noise_skips"] = cnt.get("noise_skips", 0) + 1
                continue
            _o, _c = B["o"][e1], B["c"][e1]
            raw_entry = round((_o + NOISE["rng"].uniform(0.0, NOISE["delay"]) * (_c - _o)) / TICK) * TICK if NOISE["delay"] > 0 else _o
            if NOISE["shift"] != 0:
                ent_a = B["A"][e1]
            raw_entry += side * NOISE["rng"].randint(0, NOISE["slip"]) * TICK
            xs = NOISE["rng"].randint(0, NOISE["slip"]) * TICK
        fill = raw_entry + side * slip
        d = args.sl_k * vol
        if args.sl_ref == "fill":
            refpx = fill
        elif args.sl_ref == "signal":
            refpx = P["c"][si] if args.entry_mode != "stop" else fill
        else:
            refpx = lvl
        sl = refpx - side * d
        dist = abs(fill - sl)
        if args.tp_mode == "r3":
            tdist = args.tp_r * dist
        elif args.tp_mode == "or":
            tdist = 3.0 * (orh - orl)
        else:
            tdist = 3.0 * rng
        tp = fill + side * tdist
        if args.round:
            if side == 1:
                sl, tp = rnd_down(sl), rnd_down(tp)
            else:
                sl, tp = rnd_up(sl), rnd_up(tp)
        if (side == 1 and not (sl < fill < tp)) or (side == -1 and not (tp < fill < sl)):
            cnt["bad_levels"] += 1
            continue
        res = exit_walk(args, B, P, side, D, base, ei, e1, fill, sl, tp, slip)
        if res is None:
            cnt["no_walk"] += 1
            continue
        xpx, xa, reason = res
        xpx -= side * xs
        slipped = reason in ("SL", "GAP_SL", "TIME")
        raw_exit = xpx + side * slip if slipped else xpx
        pnl = side * (xpx - fill) * args.mult * args.qty - args.comm * args.qty
        risk = abs(fill - sl) * args.mult * args.qty
        cnt["traded"] += 1
        trades.append(dict(
            date=day_to_date(D), side="L" if side == 1 else "S", bias_pos=pos, onr_high=onr_hi, onr_low=onr_lo,
            orh=orh, orl=orl, vol=vol, signal_time_ct=fmt_a(S[si]),
            entry_time_ct=fmt_a(ent_a) if ent_a is not None else fmt_a(S[ei] if args.exit_res == "15m" or args.entry_mode != "stop" else B["A"][e1]),
            exit_time_ct=fmt_a(xa), fill=fill, sl=sl, tp=tp, exit_px=xpx, exit_reason=reason,
            adx_at_signal=adx[si] if args.entry_mode != "stop" else adx[si - 1],
            raw_pts=side * (raw_exit - raw_entry), pnl_usd=pnl, risk_usd=risk, r=pnl / risk if risk > 0 else 0.0))
    return trades, cnt


# ---------------------------------------------------------------- stats
def median(xs):
    s = sorted(xs)
    n = len(s)
    if n == 0:
        return 0.0
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def max_dd(pn):
    cum = peak = dd = 0.0
    for p in pn:
        cum += p
        peak = max(peak, cum)
        dd = max(dd, peak - cum)
    return dd


def stats(tr):
    n = len(tr)
    if n == 0:
        return None
    pn = [t["pnl_usd"] for t in tr]
    wl = [p for p in pn if p > 0]
    ls = [p for p in pn if p < 0]
    wins, losses = sum(wl), -sum(ls)
    avg_w = wins / len(wl) if wl else 0.0
    avg_l = losses / len(ls) if ls else 0.0
    reasons = {}
    for t in tr:
        reasons[t["exit_reason"]] = reasons.get(t["exit_reason"], 0) + 1
    return dict(n=n, win=100.0 * len(wl) / n, avg=sum(pn) / n,
                pf=(wins / losses if losses > 0 else float("inf")),
                payoff=(avg_w / avg_l if avg_l > 0 else float("inf")),
                exp_r=sum(t["r"] for t in tr) / n, net=sum(pn), dd=max_dd(pn), reasons=reasons,
                med_risk=median([t["risk_usd"] for t in tr]))


def line(label, tr):
    s = stats(tr)
    if s is None:
        return "  %-9s no trades" % label
    rs = "/".join("%s %.0f%%" % (r, 100.0 * s["reasons"].get(r, 0) / s["n"])
                  for r in ("GAP_SL", "SL", "TP", "GAP_TP", "TIME") if r in s["reasons"])
    return ("  %-9s trades=%d win%%=%.1f avg=$%.2f PF=%.2f payoff=%.2f expR=%.3f net=$%.0f maxDD=$%.0f "
            "medRisk=$%.0f exits[%s]" % (label, s["n"], s["win"], s["avg"], s["pf"], s["payoff"], s["exp_r"],
                                         s["net"], s["dd"], s["med_risk"], rs))


def net_ex_top(tr):
    pn = sorted([t["pnl_usd"] for t in tr], reverse=True)
    pos = [p for p in pn if p > 0][:TOP_N]
    return sum(pn) - sum(pos)


def summary_text(args, trades, cnt):
    L = []
    L.append("params: " + " ".join("%s=%s" % (k, v) for k, v in sorted(vars(args).items())))
    L.append("days: window=%d not_full=%d no_vol=%d no_onr=%d no_or=%d | long-bias=%d short-bias=%d no-bias=%d | "
             "no_signal=%d bad_levels=%d no_walk=%d | days traded=%d" % (
                 cnt["window_days"], cnt["not_full"], cnt["no_vol"], cnt["no_onr"], cnt["no_or"],
                 cnt["long_bias"], cnt["short_bias"], cnt["no_bias"], cnt["no_signal"], cnt["bad_levels"],
                 cnt["no_walk"], cnt["traded"]))
    groups = [("ALL", trades),
              ("IN-SAMPLE (<2023-01-01)", [t for t in trades if t["date"] < OOS_START]),
              ("OUT-OF-SAMPLE (>=2023-01-01)", [t for t in trades if t["date"] >= OOS_START])]
    for name, g in groups:
        L.append("")
        L.append("== " + name + " ==")
        L.append(line("ALL SIDES", g))
        L.append(line("LONG", [t for t in g if t["side"] == "L"]))
        L.append(line("SHORT", [t for t in g if t["side"] == "S"]))
        L.append("  net with the %d largest winners removed: $%.0f" % (TOP_N, net_ex_top(g)))
    L.append("")
    L.append("== PER YEAR ==")
    L.append("year  trades  win%      net USD    maxDD USD     PF")
    for y in sorted({t["date"].year for t in trades}):
        s = stats([t for t in trades if t["date"].year == y])
        L.append("%d  %6d  %5.1f  %11.2f  %11.2f  %5.2f" % (y, s["n"], s["win"], s["net"], s["dd"], s["pf"]))
    return "\n".join(L)


def main():
    p = argparse.ArgumentParser(description="Overnight bias + opening-range breakout backtest (NQ data)")
    p.add_argument("--csv", default=CSV_DEFAULT)
    p.add_argument("--from", dest="from_date", default="2019-09-01")
    p.add_argument("--to", default=None)
    p.add_argument("--out", required=True)
    p.add_argument("--adx-len", type=int, default=14)
    p.add_argument("--adx-th", type=float, default=20.0)
    p.add_argument("--vol-session", choices=["midnight", "globex"], default="midnight")
    p.add_argument("--vol-tr", choices=["tr", "hl"], default="tr")
    p.add_argument("--vol-ma", choices=["sma", "ema", "atr14"], default="sma")
    p.add_argument("--vol-len", type=int, default=15)
    p.add_argument("--onr-start", type=int, default=0, help="minutes relative to D midnight (0, -60, -420)")
    p.add_argument("--onr-basis", choices=["hl", "close"], default="hl")
    p.add_argument("--open-ref", choices=["open", "prevclose", "orclose"], default="open")
    p.add_argument("--thirds", choices=["incl", "strict", "outside_none", "pct"], default="incl")
    p.add_argument("--entry-mode", choices=["state", "fresh", "first", "stop"], default="state")
    p.add_argument("--first-fill", type=int, default=540)
    p.add_argument("--last-fill", type=int, default=855)
    p.add_argument("--sl-k", type=float, default=0.30)
    p.add_argument("--sl-ref", choices=["fill", "signal", "or"], default="fill")
    p.add_argument("--tp-mode", choices=["r3", "or", "onr"], default="r3")
    p.add_argument("--tp-r", type=float, default=3.0)
    p.add_argument("--round", dest="round", action="store_true", default=True)
    p.add_argument("--no-round", dest="round", action="store_false")
    p.add_argument("--exit-res", choices=["15m", "1m"], default="15m")
    p.add_argument("--time-exit", type=int, default=870)
    p.add_argument("--time-exit-at", choices=["open", "close"], default="open")
    p.add_argument("--mult", type=float, default=20.0)
    p.add_argument("--comm", type=float, default=4.60)
    p.add_argument("--qty", type=int, default=1)
    p.add_argument("--slip-ticks", type=float, default=1)
    p.add_argument("--entry-shift", type=int, default=0)
    p.add_argument("--entry-delay", type=float, default=0.0)
    p.add_argument("--noise-slip", type=int, default=0)
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--seeds", default="", help="A-B: run seeds A..B in one process (data loaded once); output in OUT/seed_NNNN")
    args = p.parse_args()
    if args.entry_shift or args.entry_delay > 0 or args.noise_slip > 0:
        NOISE.update(on=True, shift=args.entry_shift, delay=args.entry_delay, slip=args.noise_slip,
                     rng=random.Random(args.seed))

    def one(args):
        trades, cnt = run(args)
        os.makedirs(args.out, exist_ok=True)
        cols = ["date", "side", "bias_pos", "onr_high", "onr_low", "orh", "orl", "vol", "signal_time_ct",
                "entry_time_ct", "exit_time_ct", "fill", "sl", "tp", "exit_px", "exit_reason", "adx_at_signal",
                "raw_pts", "pnl_usd", "risk_usd", "r"]
        with open(os.path.join(args.out, "trades.csv"), "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(cols)
            for t in trades:
                w.writerow([t[c].isoformat() if c == "date" else t[c] for c in cols])
        text = summary_text(args, trades, cnt)
        with open(os.path.join(args.out, "summary.txt"), "w") as f:
            f.write(text + "\n")
        print(text)


    if args.seeds:
        lo, hi = (int(x) for x in args.seeds.split("-"))
        cache = {}
        orig = globals()["load_bars"]
        def cached(*k):
            if k not in cache:
                cache[k] = orig(*k)
            return cache[k]
        globals()["load_bars"] = cached
        root = args.out
        for sd in range(lo, hi + 1):
            NOISE.update(on=True, shift=args.entry_shift, delay=args.entry_delay, slip=args.noise_slip,
                         rng=random.Random(sd))
            args.seed, args.out = sd, os.path.join(root, "seed_%04d" % sd)
            if os.path.exists(os.path.join(args.out, "trades.csv")):
                continue
            one(args)
    else:
        one(args)

if __name__ == "__main__":
    main()
