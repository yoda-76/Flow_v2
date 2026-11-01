"""VWAP Pullback + ADX (long only) - offline backtest on NQ 1-minute bars, traded as MNQ.

Source rule file: docs/video_transcripts/strategies/distilled/MatteoConti_2_VwapPullbackADX.txt

Idea: per Central-time day, build the 08:30-09:00 CT opening range. After 09:00, once a 1m bar
closes above the range high (armed), wait for price to pull back to VWAP (low <= VWAP, touched),
then take a long when price is back above VWAP and ADX(14) is above a threshold but falling
(trend strong yet fading). Next bar's open is the entry; TP = highest high of the last 5 bars,
SL = lowest low of the last 20 bars, time exit at 14:55 CT.

Assumptions:
  - Pure standard library, no tz database: CT = UTC-5 in US DST, UTC-6 otherwise.
  - Bars are all 24h Globex 1m bars in ONE continuous list; ADX (Wilder) runs over the whole list
    with no daily reset and ignores time gaps. Earlier data (--from minus 40 days) warms it up.
  - VWAP is anchored at --vwap-anchor (CT minute), typical price (h+l+c)/3 volume weighted.
  - Day needs bars in 08:30-09:00 (opening range) and in 14:30-15:00 (full day) CT, else skipped.
  - TP/SL are computed from the signal bar's window (signal bar included); entry at next bar open
    (must be same day, minute <= --last-entry); skipped if open is already beyond TP or SL.
  - Both TP and SL touched in one bar: stop wins. Gaps handled at bar open.
  - Slippage applies to entries, stops, gap-stops and time exits; not to TP limits / gap-TP.
  - Read-only over the CSV; writes only under --out.

Variant flags (all default to the baseline behaviour above; baseline output is byte-identical):
  --or-end N            OR = minutes [510, N); arming walk starts at N          S2-1B 525, 1C 515, 1D 570
  --vwap-anchor M       CT minute of day; negative = minutes before midnight of the previous CT date
                        (-420 = 17:00 CT prev date; VWAP accumulates from there)  2A 0, 2C -420
  --vwap-basis B        typical | close                                          2D close
  --arm A               close | intrabar (arm when high > ORH)                   3C intrabar
  --touch-after-arm     touch only counts on a bar after the arming bar          3B
  --arm-expiry N        no entry within N bars of arming (arming bar = 0): disarm + clear touch; a later
                        bar meeting the arm rule re-arms (signal allowed while i-arm_i <= N)   3D 60/120/180
  --touch T             le | lt | near (low <= vwap + --touch-ticks*0.25) | closebelow   3F lt, 3G near, 3H closebelow
  --adx-cmp C           le | lt | tol (adx <= ref + --adx-tol); --adx-lag L (ref = ADX L bars back)   4C lt, 4D tol, 4H lag 2
  --adx-len / --adx-th  4B --adx-len 10/20, 4E --adx-th 15/25
  --adx-reset           ADX recomputed per day from that day's bars with minute >= 510    4F
  --mode latch          (existing)                                               5B
  --touch-window N      touch must be within last N bars (most recent touch; stale -> cleared)   5C 10/30
  --max-trades / --max-consec-losses (existing)   6A --max-trades 1, 6C --max-trades 99 --max-consec-losses 99
  --first-fill M        entry bar minute must be >= M                            6D 600, 6E 570
  --last-entry M        (existing, may be up to 955)                             6F
  --tp-len / --sl-len   window lengths (5 / 20)                                  7F
  --exclude-signal-bar  windows = bars i-len..i-1 instead of i-len+1..i          7B
  --trail X             none | tp | sl | both: for bar k > entry bar the level is recomputed from the window
                        ending at k-1 (entry bar uses the signal-time level)     7C tp, 7D sl, 7E both
  --gap-tp skip|scratch scratch: next open >= TP -> enter and exit at that open (SCRATCH_TP)   7H
  --gap-sl skip|stopout stopout: next open <= SL -> exit at open - slip (SCRATCH_SL)           7J
  --min-tp-pts X        skip if TP - next open < X                               7K
  --tp-through          TP limit fills only if high >= TP + 0.25 (gap-open TP unchanged)         7L
  --exit-min M          (existing, may be 955/960)                               8A 955, 8C 960
  --intrabar stop|target  target = TP wins when both touched in one bar          9B target
New counters (skip_mintp, both_touched, ...) go to extra.txt, never summary.txt.

Example:
  python analysis/gb_nq_backtest/vwap_adx.py --from 2023-01-01 --out reports/vwap_adx_run
"""
import argparse
import bisect
import csv
import datetime
import os
from datetime import date, timedelta, timezone

H_MS = 3600 * 1000
DAY_MS = 86400 * 1000
TICK = 0.25
CSV_DEFAULT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "NQ_1m_latest.csv")
OOS_START = date(2023, 1, 1)
OR_START, OR_END = 510, 540
FULL_START, FULL_END = 870, 900


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


def load_bars(path, min_ts):
    """All bars >= min_ts, sorted, de-duplicated (last wins). Returns parallel lists."""
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
    B = dict(dn=[], m=[], o=[], h=[], l=[], c=[], v=[])
    for ts in sorted(rows):
        o, h, l, c, v = rows[ts]
        dn, m = ct_local(ts)
        B["dn"].append(dn); B["m"].append(m)
        B["o"].append(o); B["h"].append(h); B["l"].append(l); B["c"].append(c); B["v"].append(v)
    return B


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


def fmt_t(dn, m):
    return "%s %02d:%02d" % (day_to_date(dn).isoformat(), m // 60, m % 60)


def run(args):
    d_from = date.fromisoformat(args.from_date)
    d_to = date.fromisoformat(args.to) if args.to else date(2100, 1, 1)
    min_ts = int(datetime.datetime.combine(d_from - timedelta(days=40), datetime.time(0), tzinfo=timezone.utc).timestamp() * 1000)
    B = load_bars(args.csv, min_ts)
    adx = compute_adx(B, args.adx_len)
    slip = args.slip_ticks * TICK

    # day ranges (bars are time-ordered so each CT day is contiguous)
    ranges = []
    dns = B["dn"]
    s = 0
    for i in range(1, len(dns) + 1):
        if i == len(dns) or dns[i] != dns[s]:
            ranges.append((dns[s], s, i))
            s = i

    rng = {r[0]: (r[1], r[2]) for r in ranges}
    trades = []
    cnt = dict(window_days=0, no_or=0, not_full=0, armed_days=0, trade_days=0,
               skip_tp=0, skip_sl=0, stop_days=0, skip_mintp=0, both_touched=0,
               scratch_tp=0, scratch_sl=0)
    for dn, a, b in ranges:
        if not (d_from <= day_to_date(dn) <= d_to):
            continue
        cnt["window_days"] += 1
        ms = B["m"]
        orh = orl = None
        for i in range(a, b):
            if OR_START <= ms[i] < args.or_end:
                orh = B["h"][i] if orh is None else max(orh, B["h"][i])
                orl = B["l"][i] if orl is None else min(orl, B["l"][i])
        if orh is None:
            cnt["no_or"] += 1
            continue
        if not any(FULL_START <= ms[i] < FULL_END for i in range(a, b)):
            cnt["not_full"] += 1
            continue
        trade_day(args, B, adx, dn, a, b, orh, slip, trades, cnt, rng.get(dn - 1))
    return trades, cnt


def trade_day(args, B, adx, dn, a, b, orh, slip, trades, cnt, prev_rng=None):
    ms, O, H, L, C, V = B["m"], B["o"], B["h"], B["l"], B["c"], B["v"]
    # VWAP per bar of the day
    close_basis = args.vwap_basis == "close"

    def px(i):
        return C[i] if close_basis else (H[i] + L[i] + C[i]) / 3.0

    vwap = {}
    cum_pv = cum_v = 0.0
    day_anchor = args.vwap_anchor
    if args.vwap_anchor < 0:
        day_anchor = 0
        if prev_rng:
            thr = 1440 + args.vwap_anchor
            for i in range(prev_rng[0], prev_rng[1]):
                if ms[i] >= thr:
                    cum_pv += px(i) * V[i]
                    cum_v += V[i]
    for i in range(a, b):
        if ms[i] < day_anchor:
            vwap[i] = None
            continue
        cum_pv += px(i) * V[i]
        cum_v += V[i]
        vwap[i] = cum_pv / cum_v if cum_v > 0 else None

    if args.adx_reset:
        idx = [i for i in range(a, b) if ms[i] >= 510]
        sub = dict(h=[H[i] for i in idx], l=[L[i] for i in idx], c=[C[i] for i in idx])
        sa = compute_adx(sub, args.adx_len)
        dmap = {idx[q]: sa[q] for q in range(len(idx))}

        def adx_at(i):
            return dmap.get(i)
    else:
        def adx_at(i):
            return adx[i] if i >= 0 else None

    def adx_ok(i):
        cur = adx_at(i)
        ref_i = i - args.adx_lag
        ref = adx_at(ref_i) if ref_i >= 0 else None
        if cur is None or ref is None or not cur > args.adx_th:
            return False
        if args.adx_cmp == "lt":
            return cur < ref
        if args.adx_cmp == "tol":
            return cur <= ref + args.adx_tol
        return cur <= ref

    def touch_cond(i, vw):
        if args.touch == "lt":
            return L[i] < vw
        if args.touch == "near":
            return L[i] <= vw + args.touch_ticks * TICK
        if args.touch == "closebelow":
            return C[i] < vw
        return L[i] <= vw

    def tp_level(i):
        w = H[max(0, i - args.tp_len):i] if args.exclude_signal_bar else H[max(0, i - args.tp_len + 1):i + 1]
        return max(w) if w else None

    def sl_level(i):
        w = L[max(0, i - args.sl_len):i] if args.exclude_signal_bar else L[max(0, i - args.sl_len + 1):i + 1]
        return min(w) if w else None

    armed = touched = reclaimed = False
    ever_armed = False
    arm_i = touch_i = -1
    n_ent = 0
    consec = 0
    stopped = False
    tp_thr = TICK if args.tp_through else 0.0
    i = a
    while i < b:
        m = ms[i]
        if m < args.or_end:
            i += 1
            continue
        if m >= args.exit_min:
            break
        if n_ent >= args.max_trades:
            break
        if armed and args.arm_expiry > 0 and i - arm_i > args.arm_expiry:
            armed = touched = reclaimed = False
        if not armed and (C[i] > orh if args.arm == "close" else H[i] > orh):
            armed = True
            ever_armed = True
            arm_i = i
        vw = vwap[i]
        if (armed and vw is not None and (args.touch_window > 0 or not touched)
                and (i > arm_i or not args.touch_after_arm) and touch_cond(i, vw)):
            touched = True
            touch_i = i
        if touched and args.touch_window > 0 and i - touch_i >= args.touch_window:
            touched = False
        signal = False
        if touched:
            if args.mode == "recheck":
                signal = vw is not None and C[i] > vw and adx_ok(i)
            else:
                if vw is not None and C[i] > vw:
                    reclaimed = True
                signal = reclaimed and adx_ok(i)
        if not signal:
            i += 1
            continue
        # signal at bar i
        j = i + 1
        if not (j < b and ms[j] <= args.last_entry and ms[j] < args.exit_min and ms[j] >= args.first_fill):
            i += 1
            continue
        tp = tp_level(i)
        sl = sl_level(i)
        if tp is None or sl is None:
            i += 1
            continue
        o = O[j]
        scratch = None
        if o >= tp:
            if args.gap_tp == "scratch":
                scratch = "SCRATCH_TP"
            else:
                cnt["skip_tp"] += 1
                i += 1
                continue
        elif o <= sl:
            if args.gap_sl == "stopout":
                scratch = "SCRATCH_SL"
            else:
                cnt["skip_sl"] += 1
                i += 1
                continue
        if scratch is None and args.min_tp_pts > 0 and tp - o < args.min_tp_pts:
            cnt["skip_mintp"] += 1
            i += 1
            continue
        fill = o + slip
        xpx = None
        if scratch is not None:
            k = j
            if scratch == "SCRATCH_TP":
                xpx, reason = o, scratch
                cnt["scratch_tp"] += 1
            else:
                xpx, reason = o - slip, scratch
                cnt["scratch_sl"] += 1
        else:
            # exit walk from bar j
            k = j
            last_k = None
            ctp, csl = tp, sl
            while k < b and ms[k] < args.exit_min:
                last_k = k
                if k > j and args.trail != "none":
                    if args.trail in ("tp", "both"):
                        t2 = tp_level(k - 1)
                        if t2 is not None:
                            ctp = t2
                    if args.trail in ("sl", "both"):
                        s2 = sl_level(k - 1)
                        if s2 is not None:
                            csl = s2
                if O[k] <= csl:
                    xpx, reason = O[k] - slip, "GAP_SL"
                elif O[k] >= ctp:
                    xpx, reason = O[k], "GAP_TP"
                else:
                    sl_hit = L[k] <= csl
                    tp_hit = H[k] >= ctp + tp_thr
                    if sl_hit and tp_hit:
                        cnt["both_touched"] += 1
                    if args.intrabar == "target":
                        if tp_hit:
                            xpx, reason = ctp, "TP"
                        elif sl_hit:
                            xpx, reason = csl - slip, "SL"
                    else:
                        if sl_hit:
                            xpx, reason = csl - slip, "SL"
                        elif tp_hit:
                            xpx, reason = ctp, "TP"
                if xpx is not None:
                    break
                k += 1
            if xpx is None:
                k = last_k
                xpx, reason = C[k] - slip, "TIME"
        slipped = reason in ("SL", "GAP_SL", "TIME", "SCRATCH_SL")
        raw_exit = xpx + slip if slipped else xpx
        gross = xpx - fill
        pnl = gross * args.mult * args.qty - args.comm * args.qty
        cost = args.comm * args.qty + slip * args.mult * args.qty * (2 if slipped else 1)
        n_ent += 1
        trades.append(dict(
            date=day_to_date(dn), signal_time_ct=fmt_t(dn, ms[i]), entry_time_ct=fmt_t(dn, ms[j]),
            exit_time_ct=fmt_t(dn, ms[k]), entry_px=fill, exit_px=xpx, tp=tp, sl=sl,
            tp_dist_pts=tp - fill, sl_dist_pts=fill - sl, orh=orh, vwap_at_signal=vw,
            adx_at_signal=adx_at(i), exit_reason=reason, raw_pts=raw_exit - o, gross_pts=gross,
            pnl_usd=pnl, trade_no_in_day=n_ent, cost_usd=cost))
        touched = reclaimed = False
        if pnl < 0:
            consec += 1
        else:
            consec = 0
        if consec >= args.max_consec_losses:
            stopped = True
            break
        i = k + 1
    if ever_armed:
        cnt["armed_days"] += 1
    if n_ent > 0:
        cnt["trade_days"] += 1
    if stopped:
        cnt["stop_days"] += 1


def median(xs):
    s = sorted(xs)
    n = len(s)
    if n == 0:
        return 0.0
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def stats(tr):
    n = len(tr)
    if n == 0:
        return None
    pn = [t["pnl_usd"] for t in tr]
    wl = [p for p in pn if p > 0]
    ls = [p for p in pn if p < 0]
    wins = sum(wl)
    losses = -sum(ls)
    cum = peak = dd = 0.0
    for p in pn:
        cum += p
        peak = max(peak, cum)
        dd = max(dd, peak - cum)
    reasons = {}
    for t in tr:
        reasons[t["exit_reason"]] = reasons.get(t["exit_reason"], 0) + 1
    avg_w = wins / len(wl) if wl else 0.0
    avg_l = -losses / len(ls) if ls else 0.0
    avg_cost = sum(t["cost_usd"] for t in tr) / n
    return dict(n=n, win=100.0 * len(wl) / n, avg=sum(pn) / n,
                raw20=sum(t["raw_pts"] for t in tr) / n * 20,
                pf=(wins / losses if losses > 0 else float("inf")),
                net=sum(pn), dd=dd, reasons=reasons,
                med_tp=median([t["tp_dist_pts"] for t in tr]),
                med_sl=median([t["sl_dist_pts"] for t in tr]),
                avg_w=avg_w, avg_l=avg_l, avg_cost=avg_cost,
                cost_pct=(100.0 * avg_cost / avg_w if avg_w > 0 else float("nan")))


def summary_text(args, trades, cnt):
    L = []
    L.append("params: " + " ".join("%s=%s" % (k, v) for k, v in sorted(vars(args).items())))
    L.append("days in window=%d  skipped no-OR=%d  skipped not-full=%d  days armed=%d  days with >=1 trade=%d" % (
        cnt["window_days"], cnt["no_or"], cnt["not_full"], cnt["armed_days"], cnt["trade_days"]))
    sig = len(trades) + cnt["skip_tp"] + cnt["skip_sl"]
    L.append("signals=%d  skip_tp=%d (%.1f%% of signals)  skip_sl=%d  days stopped (consec losses)=%d" % (
        sig, cnt["skip_tp"], 100.0 * cnt["skip_tp"] / sig if sig else 0.0, cnt["skip_sl"], cnt["stop_days"]))
    groups = [("ALL", trades),
              ("IN-SAMPLE (<2023-01-01)", [t for t in trades if t["date"] < OOS_START]),
              ("OUT-OF-SAMPLE (>=2023-01-01)", [t for t in trades if t["date"] >= OOS_START])]
    for name, g in groups:
        L.append("")
        L.append("== " + name + " ==")
        s = stats(g)
        if s is None:
            L.append("no trades")
            continue
        L.append("trades=%d  win%%=%.1f  avg pnl=$%.2f  avg raw $/1NQ no costs=$%.2f  PF=%.2f" % (
            s["n"], s["win"], s["avg"], s["raw20"], s["pf"]))
        L.append("net=$%.2f  maxDD=$%.2f  exits=%s" % (s["net"], s["dd"], dict(sorted(s["reasons"].items()))))
        L.append("median tp_dist=%.2f pts  median sl_dist=%.2f pts  avg winner=$%.2f  avg loser=$%.2f" % (
            s["med_tp"], s["med_sl"], s["avg_w"], s["avg_l"]))
        L.append("cost/trade=$%.2f  = %.1f%% of avg winner" % (s["avg_cost"], s["cost_pct"]))
        yrs = {}
        for t in g:
            yrs[t["date"].year] = yrs.get(t["date"].year, 0) + 1
        L.append("trades per year: " + ", ".join("%d:%d" % kv for kv in sorted(yrs.items())))
    L.append("")
    L.append("== PER YEAR ==")
    L.append("year  trades  win%      net USD    maxDD USD")
    for y in sorted({t["date"].year for t in trades}):
        s = stats([t for t in trades if t["date"].year == y])
        L.append("%d  %6d  %5.1f  %11.2f  %11.2f" % (y, s["n"], s["win"], s["net"], s["dd"]))
    return "\n".join(L)


def main():
    p = argparse.ArgumentParser(description="VWAP pullback + ADX long-only backtest (NQ data, MNQ sizing)")
    p.add_argument("--csv", default=CSV_DEFAULT)
    p.add_argument("--from", dest="from_date", default="2019-09-01")
    p.add_argument("--to", default=None)
    p.add_argument("--out", required=True)
    p.add_argument("--adx-len", type=int, default=14)
    p.add_argument("--adx-th", type=float, default=20.0)
    p.add_argument("--vwap-anchor", type=int, default=510, help="CT minute of day (510=08:30, 0=00:00)")
    p.add_argument("--exit-min", type=int, default=895, help="CT minute; bars with minute >= this are not traded")
    p.add_argument("--last-entry", type=int, default=870, help="latest CT minute of the entry bar")
    p.add_argument("--max-trades", type=int, default=4)
    p.add_argument("--max-consec-losses", type=int, default=2)
    p.add_argument("--mode", choices=["recheck", "latch"], default="recheck")
    p.add_argument("--mult", type=float, default=2.0)
    p.add_argument("--comm", type=float, default=1.00)
    p.add_argument("--qty", type=int, default=1)
    p.add_argument("--slip-ticks", type=float, default=1)
    p.add_argument("--or-end", type=int, default=540)
    p.add_argument("--vwap-basis", choices=["typical", "close"], default="typical")
    p.add_argument("--arm", choices=["close", "intrabar"], default="close")
    p.add_argument("--touch-after-arm", action="store_true")
    p.add_argument("--arm-expiry", type=int, default=0)
    p.add_argument("--touch", choices=["le", "lt", "near", "closebelow"], default="le")
    p.add_argument("--touch-ticks", type=float, default=2)
    p.add_argument("--adx-cmp", choices=["le", "lt", "tol"], default="le")
    p.add_argument("--adx-tol", type=float, default=0.05)
    p.add_argument("--adx-lag", type=int, default=1)
    p.add_argument("--adx-reset", action="store_true")
    p.add_argument("--touch-window", type=int, default=0)
    p.add_argument("--first-fill", type=int, default=0)
    p.add_argument("--tp-len", type=int, default=5)
    p.add_argument("--sl-len", type=int, default=20)
    p.add_argument("--exclude-signal-bar", action="store_true")
    p.add_argument("--trail", choices=["none", "tp", "sl", "both"], default="none")
    p.add_argument("--gap-tp", choices=["skip", "scratch"], default="skip")
    p.add_argument("--gap-sl", choices=["skip", "stopout"], default="skip")
    p.add_argument("--min-tp-pts", type=float, default=0.0)
    p.add_argument("--tp-through", action="store_true")
    p.add_argument("--intrabar", choices=["stop", "target"], default="stop")
    args = p.parse_args()

    trades, cnt = run(args)
    os.makedirs(args.out, exist_ok=True)
    cols = ["date", "signal_time_ct", "entry_time_ct", "exit_time_ct", "entry_px", "exit_px", "tp", "sl",
            "tp_dist_pts", "sl_dist_pts", "orh", "vwap_at_signal", "adx_at_signal", "exit_reason",
            "raw_pts", "gross_pts", "pnl_usd", "trade_no_in_day"]
    with open(os.path.join(args.out, "trades.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for t in trades:
            w.writerow([t[c] if c != "date" else t[c].isoformat() for c in cols])
    text = summary_text(args, trades, cnt)
    with open(os.path.join(args.out, "summary.txt"), "w") as f:
        f.write(text + "\n")
    with open(os.path.join(args.out, "extra.txt"), "w") as f:
        f.write("\n".join("%s=%s" % kv for kv in sorted(cnt.items())) + "\n")
    print(text)


if __name__ == "__main__":
    main()
