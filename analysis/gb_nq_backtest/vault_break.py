"""Vault Break (long only) - offline backtest on NQ 1-minute bars, traded as MNQ.

Source rule file: docs/video_transcripts/strategies/distilled/MatteoConti_1_VaultBreak.txt

Idea: per Central-time session (00:00-16:00 CT) build a noise-up level
(session open + K * 15-day average daily true range). On a 30m bar close between
10:00 and 14:00 CT, if close > noise-up AND close > session VWAP, buy the next
30m bar's open. TP/SL in points, time exit at 14:30 CT.

Assumptions (see the task spec for the full rule set):
  - Pure standard library, no tz database: CT = UTC-5 in US DST, UTC-6 otherwise.
  - Session open = open of the first 1m bar in 00:00-00:30 CT; else day skipped.
  - Volatility = mean daily TR of the last --atr-len sessions with trading.
  - "Next 30m bar" must be the bucket immediately after the signal bucket
    (a data gap there means no entry).
  - Both TP and SL touched in one bar: stop wins. Gaps handled at bar open.
  - Slippage applies to entries, stops, gap-stops and time exits; not to TP limits.
  - Read-only over the CSV; writes only under --out.

Variant flags (defaults = the behaviour described above; every default reproduces the original output):
  --vol-ma {sma,ema,atr14}     sma | EMA(alpha 2/(len+1), seeded with first TR) | SMA(--atr-len) of Wilder ATR(14)
  --vol-session {midnight,globex}  TR session 00:00-16:00 CT | previous 17:00 CT -> 16:00 CT (only vol changes)
  --open-ref {bar0000,close2330,globex1700,rth0830}  noise reference price
  --vwap-anchor MIN            minutes rel. to D 00:00 CT (default 0; -420 = 17:00 prev date; 510 = 08:30; min -480)
  --vwap-basis {typical,close} --vwap-prev (compare against VWAP as of the previous 30m bar's close)
  --first-signal-close / --last-signal-close  CT minute of signal bar close (600 / 840)
  --reentry {exitbar,cooldown,fresh_all,fresh_reentry}
  --max-gap-pts X (0=off), --fill-at {nextopen,signalclose} (optimistic bound)
  --time-exit-min (870), --time-exit-at {lastclose,open}, --intrabar {stop,target,direction}
  Skip counters go to <out>/extra.txt (summary.txt is unchanged in format).

Variant codes (study map):
  S1 volatility: 1B --vol-ma atr14 | 1C --vol-ma ema | 1D --vol-session globex | 1E --tr-mode hl | 1G --k
  S2 open ref:   2B --open-ref close2330 | 2C globex1700 | 2D rth0830
  S3 VWAP:       3B --vwap-anchor -420 | 3C --vwap-anchor 510 | 3D --vwap-basis close | 3E --vwap-prev
  S4 window:     4B --first-signal-close 630 | 4D --last-signal-close
  S5 re-entry:   5B --reentry cooldown | 5C fresh_all | 5D fresh_reentry
  S6:            6B --max-trades
  S7 fill:       7B --max-gap-pts | 7C --fill-at signalclose
  S8 time exit:  8C --time-exit-at open | 8D --time-exit-min 810
  S9 intrabar:   9B --intrabar target | 9C --exit-res 1m (default) | 9D --intrabar direction (use with --exit-res 30m)
  Note: EMA / ATR values depend on how many sessions are loaded (120-day lookback before --from for non-sma).

Example:
  python analysis/gb_nq_backtest/vault_break.py --from 2023-01-01 --out reports/vault_break_run
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


def load_sessions(path, min_ts, want_ext=False):
    """Return ({day_number: [(minute, o, h, l, c, v), ...]} for minutes in [0, 960), ext).

    ext = same shape for minutes >= 960 (evening bars) when want_ext, else {}."""
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
    sess = {}
    ext = {}
    for ts in sorted(rows):
        dn, m = ct_local(ts)
        if m < 960:
            o, h, l, c, v = rows[ts]
            sess.setdefault(dn, []).append((m, o, h, l, c, v))
        elif want_ext:
            o, h, l, c, v = rows[ts]
            ext.setdefault(dn, []).append((m, o, h, l, c, v))
    return sess, ext


def build_vol(args, sess, ext):
    """Return (ks, trs, vals): vals[j] = volatility using the first j TR sessions (None if not enough)."""
    if args.vol_session == "globex":
        keys = sorted(set(sess) | {dn + 1 for dn in ext})
        ssn = {}
        for k in keys:
            bl = [b for b in ext.get(k - 1, ()) if b[0] >= 1020] + list(sess.get(k, ()))
            if bl:
                ssn[k] = bl
        ks = sorted(ssn)
    else:
        ssn = sess
        ks = sorted(sess)
    trs = []
    prev_close = None
    for k in ks:
        bars = ssn[k]
        sh = max(b[2] for b in bars)
        sl_ = min(b[3] for b in bars)
        if args.tr_mode == "tr" and prev_close is not None:
            trs.append(max(sh, prev_close) - min(sl_, prev_close))
        else:
            trs.append(sh - sl_)
        prev_close = bars[-1][4]
    n = args.atr_len
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
    else:  # atr14: Wilder ATR(14) of the TR series, then SMA(n) of that
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
            c_ = j - 13
            if c_ >= n:
                vals[j] = sum(atrs[c_ - n:c_]) / n
    return ks, trs, vals


def build_30m(bars):
    """bars = 1m list. Returns (sorted bucket list, {bucket: [o,h,l,c,first_idx,last_idx]})."""
    bk = {}
    for i, (m, o, h, l, c, v) in enumerate(bars):
        b = m // 30
        x = bk.get(b)
        if x is None:
            bk[b] = [o, h, l, c, i, i]
        else:
            x[1] = max(x[1], h); x[2] = min(x[2], l); x[3] = c; x[5] = i
    return sorted(bk), bk


def exit_walk(args, bars, bk, entry_bucket, fill, slip):
    """Return (exit_px, exit_minute, exit_bucket, reason). Walks from the entry bar."""
    tp = fill + args.tp_pts
    sl = fill - args.sl_pts
    tem = args.time_exit_min
    if args.exit_res == "1m":
        seq = []
        for i in range(bk[entry_bucket][4], len(bars)):
            m, o, h, l, c, v = bars[i]
            if m >= tem:
                break
            seq.append((m, o, h, l, c))
    else:
        seq = []
        for b in sorted(bk):
            if b < entry_bucket or b * 30 >= tem:
                continue
            o, h, l, c = bk[b][:4]
            seq.append((b * 30, o, h, l, c))
    last = None
    for m, o, h, l, c in seq:
        last = (m, c)
        if o <= sl:
            return o - slip, m, m // 30, "GAP_SL"
        if o >= tp:
            return o, m, m // 30, "GAP_TP"
        if args.intrabar == "stop":
            sl_first = True
        elif args.intrabar == "target":
            sl_first = False
        else:
            sl_first = c >= o
        if sl_first:
            if l <= sl:
                return sl - slip, m, m // 30, "SL"
            if h >= tp:
                return tp, m, m // 30, "TP"
        else:
            if h >= tp:
                return tp, m, m // 30, "TP"
            if l <= sl:
                return sl - slip, m, m // 30, "SL"
    if last is None:
        return None
    if args.time_exit_at == "open":
        for bm, bo, bh, bl, bc, bv in bars:
            if bm >= tem:
                return bo - slip, bm, bm // 30, "TIME"
    m, c = last
    return c - slip, m, m // 30, "TIME"


def fmt_t(dn, m):
    return "%s %02d:%02d" % (day_to_date(dn).isoformat(), m // 60, m % 60)


def run(args):
    d_from = date.fromisoformat(args.from_date)
    d_to = date.fromisoformat(args.to) if args.to else date(2100, 1, 1)
    lookback = 40 if args.vol_ma == "sma" else 120
    min_ts = int(datetime.datetime.combine(d_from - timedelta(days=lookback), datetime.time(0), tzinfo=timezone.utc).timestamp() * 1000)
    want_ext = args.vol_session == "globex" or args.open_ref in ("close2330", "globex1700") or args.vwap_anchor < 0
    sess, ext = load_sessions(args.csv, min_ts, want_ext)
    slip = args.slip_ticks * TICK

    days = sorted(sess)
    ks, _trs, vals = build_vol(args, sess, ext)
    trades = []
    cnt = dict(window_days=0, not_full=0, skipped_data=0, signal_days=0,
               open_ref_missing=0, vol_missing=0, fresh_skips=0, gap_skips=0)

    for dn in days:
        bars = sess[dn]
        window = d_from <= day_to_date(dn) <= d_to
        vol = vals[bisect.bisect_left(ks, dn)]

        if window:
            cnt["window_days"] += 1
            full = any(b[0] >= 870 for b in bars)
            buckets, bk = build_30m(bars)
            if not full:
                cnt["not_full"] += 1
            elif vol is None:
                cnt["skipped_data"] += 1
                cnt["vol_missing"] += 1
            else:
                open_px = day_open(args, dn, bars, bk, ext)
                if open_px is None:
                    cnt["skipped_data"] += 1
                    cnt["open_ref_missing"] += 1
                else:
                    trade_day(args, dn, bars, buckets, bk, open_px, vol, slip, trades, cnt, ext)
    return trades, cnt


def day_open(args, dn, bars, bk, ext):
    """Reference price for the noise level (None = skip the day)."""
    ref = args.open_ref
    if ref == "bar0000":
        return bk[0][0] + 0.0 if 0 in bk else None
    if ref == "close2330":
        pv = ext.get(dn - 1)
        if pv and pv[-1][0] >= 1380:
            return pv[-1][4] + 0.0
        return None
    if ref == "globex1700":
        for b in ext.get(dn - 1, ()):
            if b[0] >= 1020:
                return b[1] + 0.0
        return None
    for b in bars:                      # rth0830
        if b[0] >= 510:
            return b[1] + 0.0
    return None


def trade_day(args, dn, bars, buckets, bk, open_px, vol, slip, trades, cnt, ext):
    noise = open_px + args.k * vol
    # cumulative VWAP per 1m bar (anchored at args.vwap_anchor minutes relative to D 00:00 CT)
    cum_pv = cum_v = 0.0
    vwap = []
    anchor = args.vwap_anchor
    use_close = args.vwap_basis == "close"
    if anchor < 0:
        for m, o, h, l, c, v in ext.get(dn - 1, ()):
            if m - 1440 >= anchor:
                cum_pv += (c if use_close else (h + l + c) / 3.0) * v
                cum_v += v
    for m, o, h, l, c, v in bars:
        if m < anchor:
            vwap.append(None)
            continue
        cum_pv += (c if use_close else (h + l + c) / 3.0) * v
        cum_v += v
        vwap.append(cum_pv / cum_v if cum_v > 0 else None)
    n_ent = 0
    had_signal = False
    b = buckets[0]
    last_b = buckets[-1]
    while b <= last_b and n_ent < args.max_trades:
        if b not in bk:
            b += 1
            continue
        close_t = b * 30 + 30
        x = bk[b]
        if args.vwap_prev:
            vw = vwap[bk[b - 1][5]] if (b - 1) in bk else None
        else:
            vw = vwap[x[5]]
        sig = (args.first_signal_close <= close_t <= args.last_signal_close
               and vw is not None and x[3] > noise and x[3] > vw)
        if sig and (args.reentry == "fresh_all" or (args.reentry == "fresh_reentry" and n_ent >= 1)):
            pb = bk.get(b - 1)
            if pb is None or pb[3] > noise:
                cnt["fresh_skips"] += 1
                sig = False
        if sig:
            had_signal = True
            nb = b + 1
            if nb in bk and args.max_gap_pts > 0 and bk[nb][0] - x[3] > args.max_gap_pts:
                cnt["gap_skips"] += 1
            elif nb in bk:
                raw_entry = bk[nb][0] if args.fill_at == "nextopen" else x[3]
                fill = raw_entry + slip
                res = exit_walk(args, bars, bk, nb, fill, slip)
                if res is not None:
                    xpx, xm, xb, reason = res
                    n_ent += 1
                    raw_exit = xpx + slip if reason in ("SL", "GAP_SL", "TIME") else xpx
                    gross = xpx - fill
                    pnl = gross * args.mult * args.qty - args.comm * args.qty
                    trades.append(dict(
                        date=day_to_date(dn), entry_time_ct=fmt_t(dn, nb * 30),
                        exit_time_ct=fmt_t(dn, xm), entry_px=fill, exit_px=xpx,
                        tp=fill + args.tp_pts, sl=fill - args.sl_pts, noise_up=noise,
                        vwap_at_signal=vw, exit_reason=reason, raw_pts=raw_exit - raw_entry,
                        gross_pts=gross, pnl_usd=pnl, trade_no_in_session=n_ent))
                    b = xb          # exit bar itself may signal again
                    if args.reentry == "cooldown":
                        b = xb + 1
                    if b <= nb - 1:
                        b = nb
                    continue
        b += 1
    if had_signal:
        cnt["signal_days"] += 1


def stats(tr):
    n = len(tr)
    if n == 0:
        return None
    pn = [t["pnl_usd"] for t in tr]
    wins = sum(p for p in pn if p > 0)
    losses = -sum(p for p in pn if p < 0)
    cum = peak = dd = 0.0
    for p in pn:
        cum += p
        peak = max(peak, cum)
        dd = max(dd, peak - cum)
    reasons = {}
    for t in tr:
        reasons[t["exit_reason"]] = reasons.get(t["exit_reason"], 0) + 1
    return dict(n=n, win=100.0 * sum(1 for p in pn if p > 0) / n, avg=sum(pn) / n,
                raw20=sum(t["raw_pts"] for t in tr) / n * 20,
                pf=(wins / losses if losses > 0 else float("inf")),
                net=sum(pn), dd=dd, reasons=reasons)


def summary_text(args, trades, cnt):
    L = []
    L.append("params: " + " ".join("%s=%s" % (k, v) for k, v in sorted(vars(args).items())))
    L.append("days in window=%d  skipped not-full=%d  skipped missing open/vol=%d  days with signal=%d" % (
        cnt["window_days"], cnt["not_full"], cnt["skipped_data"], cnt["signal_days"]))
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
    p = argparse.ArgumentParser(description="Vault Break long-only backtest (NQ data, MNQ sizing)")
    p.add_argument("--csv", default=CSV_DEFAULT)
    p.add_argument("--from", dest="from_date", default="2019-09-01")
    p.add_argument("--to", default=None)
    p.add_argument("--out", required=True)
    p.add_argument("--k", type=float, default=0.30)
    p.add_argument("--atr-len", type=int, default=15)
    p.add_argument("--tr-mode", choices=["tr", "hl"], default="tr")
    p.add_argument("--slip-ticks", type=float, default=1)
    p.add_argument("--max-trades", type=int, default=3)
    p.add_argument("--tp-pts", type=float, default=40.0)
    p.add_argument("--sl-pts", type=float, default=75.0)
    p.add_argument("--exit-res", choices=["1m", "30m"], default="1m")
    p.add_argument("--mult", type=float, default=2.0)
    p.add_argument("--comm", type=float, default=1.00)
    p.add_argument("--qty", type=int, default=1)
    p.add_argument("--vol-ma", choices=["sma", "ema", "atr14"], default="sma")
    p.add_argument("--vol-session", choices=["midnight", "globex"], default="midnight")
    p.add_argument("--open-ref", choices=["bar0000", "close2330", "globex1700", "rth0830"], default="bar0000")
    p.add_argument("--vwap-anchor", type=int, default=0)
    p.add_argument("--vwap-basis", choices=["typical", "close"], default="typical")
    p.add_argument("--vwap-prev", action="store_true")
    p.add_argument("--first-signal-close", type=int, default=600)
    p.add_argument("--last-signal-close", type=int, default=840)
    p.add_argument("--reentry", choices=["exitbar", "cooldown", "fresh_all", "fresh_reentry"], default="exitbar")
    p.add_argument("--max-gap-pts", type=float, default=0.0)
    p.add_argument("--fill-at", choices=["nextopen", "signalclose"], default="nextopen")
    p.add_argument("--time-exit-min", type=int, default=870)
    p.add_argument("--time-exit-at", choices=["lastclose", "open"], default="lastclose")
    p.add_argument("--intrabar", choices=["stop", "target", "direction"], default="stop")
    args = p.parse_args()
    if args.vwap_anchor < -480:
        p.error("--vwap-anchor must be >= -480 (only evening bars from 16:00 CT of the previous date are loaded)")

    trades, cnt = run(args)
    os.makedirs(args.out, exist_ok=True)
    cols = ["date", "entry_time_ct", "exit_time_ct", "entry_px", "exit_px", "tp", "sl", "noise_up",
            "vwap_at_signal", "exit_reason", "raw_pts", "gross_pts", "pnl_usd", "trade_no_in_session"]
    with open(os.path.join(args.out, "trades.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for t in trades:
            w.writerow([t[c] if c != "date" else t[c].isoformat() for c in cols])
    text = summary_text(args, trades, cnt)
    with open(os.path.join(args.out, "summary.txt"), "w") as f:
        f.write(text + "\n")
    with open(os.path.join(args.out, "extra.txt"), "w") as f:
        f.write("skip/diagnostic counters: " + " ".join("%s=%s" % kv for kv in cnt.items()) + "\n")
    print(text)


if __name__ == "__main__":
    main()
