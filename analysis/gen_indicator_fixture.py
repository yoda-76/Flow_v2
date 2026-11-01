"""Generates the golden fixture for flow-core's com.flow.bars.BarsAndIndicatorsTest.

Reads analysis/data/NQ_1m_latest.csv, takes the 1m bars from 2026-06-01 00:00 UTC up to (not including) 2026-07-10 00:00 UTC
and writes, under flow-core/fixtures/indicators_nq_2026/:
  bars.csv      timestampMs,open,high,low,close,volume        (timestamp = bar OPEN time, as in the source CSV)
  expected.csv  timestampMs,adx14,vwap0830,vwap0000,vwapPrev1700,dailyVol15
                (prices in POINTS; empty cell = not available at that bar)

The reference logic is copied in spirit from analysis/gb_nq_backtest/ (the Python backtests the live strategies must
match): compute_adx + the VWAP block of vwap_adx.py, build_vol / daily TR of vault_break.py. Pure standard library
(no zoneinfo): CT = UTC-5 in US DST, UTC-6 otherwise, via the same transition table.

Run from anywhere:  python analysis/gen_indicator_fixture.py
"""
import bisect
import csv
import datetime
import os
from datetime import date, timedelta, timezone

H_MS = 3600 * 1000
DAY_MS = 86400 * 1000
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "data", "NQ_1m_latest.csv")
OUT = os.path.join(HERE, "..", "flow-core", "fixtures", "indicators_nq_2026")
T_FROM = int(datetime.datetime(2026, 6, 1, tzinfo=timezone.utc).timestamp() * 1000)
T_TO = int(datetime.datetime(2026, 7, 10, tzinfo=timezone.utc).timestamp() * 1000)


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


def ct_local(ts):
    """UTC ms -> (CT day number since epoch, CT minute of day)."""
    off = TR_O[bisect.bisect_right(TR_T, ts) - 1]
    loc = ts + off
    return loc // DAY_MS, (loc % DAY_MS) // 60000


def load():
    rows = {}
    with open(SRC, newline="") as f:
        rd = csv.reader(f)
        next(rd)
        for r in rd:
            try:
                ts = int(r[0])
            except ValueError:
                continue
            if T_FROM <= ts < T_TO:
                rows[ts] = (r[1], r[2], r[3], r[4], r[5])   # keep source text for bars.csv
    return rows


def compute_adx(h, l, c, n):
    """Verbatim logic of vwap_adx.compute_adx over lists."""
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


def vwap_series(dns, ms, h, l, c, v, anchor):
    """vwap_adx.trade_day's VWAP block (typical price), per bar of the continuous list."""
    ranges = []
    s = 0
    for i in range(1, len(dns) + 1):
        if i == len(dns) or dns[i] != dns[s]:
            ranges.append((dns[s], s, i))
            s = i
    rng = {r[0]: (r[1], r[2]) for r in ranges}
    out = [None] * len(dns)
    px = lambda i: (h[i] + l[i] + c[i]) / 3.0
    for dn, a, b in ranges:
        cum_pv = cum_v = 0.0
        day_anchor = anchor
        if anchor < 0:
            day_anchor = 0
            prev = rng.get(dn - 1)
            if prev:
                thr = 1440 + anchor
                for i in range(prev[0], prev[1]):
                    if ms[i] >= thr:
                        cum_pv += px(i) * v[i]
                        cum_v += v[i]
        for i in range(a, b):
            if ms[i] < day_anchor:
                continue
            cum_pv += px(i) * v[i]
            cum_v += v[i]
            out[i] = cum_pv / cum_v if cum_v > 0 else None
    return out


def daily_vol(dns, ms, h, l, c, n):
    """vault_break.build_vol (vol_ma=sma, midnight session, tr mode): mean TR of the n sessions BEFORE the bar's date."""
    sess = {}
    for i in range(len(dns)):
        if ms[i] < 960:
            sess.setdefault(dns[i], []).append(i)
    ks = sorted(sess)
    trs = []
    prev_close = None
    for k in ks:
        idx = sess[k]
        sh = max(h[i] for i in idx)
        sl = min(l[i] for i in idx)
        if prev_close is not None:
            trs.append(max(sh, prev_close) - min(sl, prev_close))
        else:
            trs.append(sh - sl)
        prev_close = c[idx[-1]]
    out = []
    for i in range(len(dns)):
        j = bisect.bisect_left(ks, dns[i])
        out.append(sum(trs[j - n:j]) / n if j >= n else None)
    return out


def fmt(x):
    return "" if x is None else repr(x)


def main():
    rows = load()
    tss = sorted(rows)
    o = [float(rows[t][0]) for t in tss]
    h = [float(rows[t][1]) for t in tss]
    l = [float(rows[t][2]) for t in tss]
    c = [float(rows[t][3]) for t in tss]
    v = [float(rows[t][4]) for t in tss]
    dns, ms = [], []
    for t in tss:
        dn, m = ct_local(t)
        dns.append(dn); ms.append(m)
    adx = compute_adx(h, l, c, 14)
    v0830 = vwap_series(dns, ms, h, l, c, v, 510)
    v0000 = vwap_series(dns, ms, h, l, c, v, 0)
    vprev = vwap_series(dns, ms, h, l, c, v, -420)
    dvol = daily_vol(dns, ms, h, l, c, 15)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "bars.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["timestampMs", "open", "high", "low", "close", "volume"])
        for t in tss:
            w.writerow([t] + list(rows[t]))
    with open(os.path.join(OUT, "expected.csv"), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["timestampMs", "adx14", "vwap0830", "vwap0000", "vwapPrev1700", "dailyVol15"])
        for i, t in enumerate(tss):
            w.writerow([t, fmt(adx[i]), fmt(v0830[i]), fmt(v0000[i]), fmt(vprev[i]), fmt(dvol[i])])
    print("wrote %d bars to %s" % (len(tss), os.path.normpath(OUT)))
    print("adx available: %d, vwap0830: %d, vwap0000: %d, vwapPrev1700: %d, dailyVol15: %d" % tuple(
        sum(1 for x in s if x is not None) for s in (adx, v0830, v0000, vprev, dvol)))


if __name__ == "__main__":
    main()
