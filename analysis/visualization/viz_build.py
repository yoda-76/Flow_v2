"""Offline chart-data builder: 1-minute OHLCV CSV -> multi-timeframe .js data files + a static chart page.

Timeframes: 1m 5m 15m 1h 4h 1D. Intraday buckets floor on the UTC epoch; 1D is the CME trading session
(starts 17:00 America/Chicago, belongs to the NEXT calendar day; Sunday 17:00 CT -> Monday's bar).
Pure standard library. Usage:
    python analysis/visualization/viz_build.py [--csv analysis/data/GC_1m_latest.csv] [--symbol GC] [--tick 0.1]
        [--out reports/viz] [--days-1m 60] [--days-5m 365] [--days-15m 1095] [--overlays blocks.json] [--va-pct 0.70] [--no-profiles] [--indicators] [--gb-trades reports/gb_nq/trades.csv]
Then open <out>/<symbol>/index.html in a browser (internet needed for the CDN chart library).
Read-only over the CSV; writes only under --out. Places no orders, touches nothing else.
"""
import argparse
import csv
import json
import shutil
import sys
import time
from array import array
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

H_MS = 3_600_000
DAY_MS = 86_400_000
TF_MS = {"1m": 60_000, "5m": 300_000, "15m": 900_000, "1h": H_MS, "4h": 4 * H_MS}
TIMEFRAMES = ["1m", "5m", "15m", "1h", "4h", "1D"]

_dst_cache = {}


def _nth_sunday(year, month, n):
    d = date(year, month, 1)
    first = d + timedelta(days=(6 - d.weekday()) % 7)  # weekday(): Mon=0 .. Sun=6
    return first + timedelta(days=7 * (n - 1))


def _dst_window_ms(year):
    """(start_utc_ms, end_utc_ms) of US DST in `year` (2007+ rules)."""
    if year not in _dst_cache:
        start = _nth_sunday(year, 3, 2)  # 2nd Sunday of March, 02:00 CST = 08:00 UTC
        end = _nth_sunday(year, 11, 1)   # 1st Sunday of November, 02:00 CDT = 07:00 UTC
        s = int(datetime(start.year, start.month, start.day, 8, tzinfo=timezone.utc).timestamp() * 1000)
        e = int(datetime(end.year, end.month, end.day, 7, tzinfo=timezone.utc).timestamp() * 1000)
        _dst_cache[year] = (s, e)
    return _dst_cache[year]


def us_central_offset_hours(utc_ms):
    """-5 during US DST, else -6 (America/Chicago)."""
    year = datetime.fromtimestamp(utc_ms / 1000, tz=timezone.utc).year
    s, e = _dst_window_ms(year)
    return -5 if s <= utc_ms < e else -6


def session_start_ms(utc_ms):
    """UTC ms of the 17:00 CT start of the CME session containing utc_ms."""
    off = us_central_offset_hours(utc_ms)
    local = utc_ms + off * H_MS
    day = (local - 17 * H_MS) // DAY_MS
    start_local = day * DAY_MS + 17 * H_MS
    off2 = us_central_offset_hours(start_local - off * H_MS)
    return start_local - off2 * H_MS


def tick_decimals(tick):
    exp = Decimal(str(tick)).normalize().as_tuple().exponent
    return max(0, -exp)


def aggregate(rows, key_ms):
    """rows: iterable of (ts_ms,o,h,l,c,v) ascending. key_ms(ts_ms)->bucket start ms.
    Returns [[t_ms,o,h,l,c,v], ...]."""
    out = []
    cur = None
    for ts, o, h, l, c, v in rows:
        k = key_ms(ts)
        if cur is None or cur[0] != k:
            cur = [k, o, h, l, c, v]
            out.append(cur)
        else:
            if h > cur[2]:
                cur[2] = h
            if l < cur[3]:
                cur[3] = l
            cur[4] = c
            cur[5] += v
    return out


def tf_key(tf):
    if tf == "1D":
        return session_start_ms
    ms = TF_MS[tf]
    return lambda ts: (ts // ms) * ms


def read_csv(path):
    """Stream CSV into compact arrays. Returns (arrays, rows_read)."""
    ts, o, h, l, c, v = array("q"), array("d"), array("d"), array("d"), array("d"), array("q")
    n = 0
    with open(path, newline="", encoding="utf-8") as f:
        rd = csv.reader(f)
        next(rd, None)  # header
        for r in rd:
            if len(r) < 6:
                continue
            ts.append(int(r[0]))
            o.append(float(r[1]))
            h.append(float(r[2]))
            l.append(float(r[3]))
            c.append(float(r[4]))
            v.append(int(float(r[5])))
            n += 1
    return (ts, o, h, l, c, v), n


def sort_dedup(arrs):
    """Sort by timestamp, keep the last row for a duplicate timestamp. Returns (list of tuples, dups_dropped)."""
    ts = arrs[0]
    n = len(ts)
    idx = sorted(range(n), key=ts.__getitem__)  # stable: file order kept within equal ts
    rows = []
    dups = 0
    for i in idx:
        row = (ts[i], arrs[1][i], arrs[2][i], arrs[3][i], arrs[4][i], arrs[5][i])
        if rows and rows[-1][0] == row[0]:
            rows[-1] = row
            dups += 1
        else:
            rows.append(row)
    return rows, dups


def trim(bars, days, last_ms):
    if days is None:
        return bars
    cutoff = last_ms - int(days * DAY_MS)
    return [b for b in bars if b[0] >= cutoff]


def to_js_bars(bars, dec):
    return [[b[0] // 1000, round(b[1], dec), round(b[2], dec), round(b[3], dec), round(b[4], dec), b[5]]
            for b in bars]


def write_js(path, tf, js_bars):
    body = json.dumps(js_bars, separators=(",", ":"))
    text = 'window.VIZ_DATA = window.VIZ_DATA || {}; window.VIZ_DATA["%s"] = %s;\n' % (tf, body)
    Path(path).write_text(text, encoding="utf-8")


def parse_time_s(v):
    """UTC ms (int/float, > 1e11) or UTC seconds (number) or ISO string -> UTC seconds (int)."""
    if isinstance(v, bool):
        raise ValueError("bad time %r" % (v,))
    if isinstance(v, (int, float)):
        return int(v / 1000) if v > 1e11 else int(v)
    if isinstance(v, str):
        d = datetime.fromisoformat(v.strip().replace("Z", "+00:00"))
        if d.tzinfo is None:
            d = d.replace(tzinfo=timezone.utc)
        return int(d.timestamp())
    raise ValueError("bad time %r" % (v,))


def validate_block(b, i=0):
    """Returns a clean block dict (times in UTC seconds); raises ValueError describing the problem."""
    if not isinstance(b, dict):
        raise ValueError("not an object")
    side = b.get("side")
    if side not in ("long", "short"):
        raise ValueError("side must be 'long' or 'short'")
    try:
        entry, stop, target = float(b["entry"]), float(b["stop"]), float(b["target"])
        start, end = parse_time_s(b["start"]), parse_time_s(b["end"])
    except (KeyError, TypeError) as e:
        raise ValueError("missing/invalid field %s" % e)
    if side == "long" and not (stop < entry < target):
        raise ValueError("long needs stop < entry < target")
    if side == "short" and not (target < entry < stop):
        raise ValueError("short needs target < entry < stop")
    if not start < end:
        raise ValueError("start must be before end")
    out = {"id": str(b.get("id", i)), "side": side, "start": start, "end": end,
           "entry": entry, "stop": stop, "target": target}
    if b.get("label"):
        out["label"] = str(b["label"])
    return out


def load_overlays(path):
    """Read a JSON array of blocks; invalid ones are skipped with a warning. Returns the clean list."""
    if not path:
        return []
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError("overlays file must be a JSON array")
    ok = []
    for i, b in enumerate(raw):
        try:
            ok.append(validate_block(b, i))
        except ValueError as e:
            name = b.get("id", i) if isinstance(b, dict) else i
            print("WARNING: overlay block %s skipped: %s" % (name, e), file=sys.stderr)
    return ok


def write_overlays(out_dir, blocks):
    text = "window.VIZ_OVERLAYS = " + json.dumps(blocks, separators=(",", ":")) + ";\n"
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    (Path(out_dir) / "overlays.js").write_text(text, encoding="utf-8")


# ---- session volume profiles (Gianluca Bruni "SVP HD": one profile per Globex session, POC / VAH / VAL) ----
SESSION_MS = 23 * H_MS      # 17:00 CT -> 16:00 CT; the 16:00-17:00 CT break is not part of any session
MIN_SESSION_BARS = 300      # a completed session with fewer 1m bars is skipped, same validity rule as gb_backtest.py


def session_profile(rows, tick, va_pct=0.70):
    """rows: (ts,o,h,l,c,v) of ONE session. Each 1m bar's volume is spread evenly over its high-low range at tick
    resolution; POC = busiest level; the value area grows from the POC two ticks at a time toward the side with
    more volume until it holds va_pct of the total. Same algorithm as gb_backtest.build() (a test pins them equal).
    Returns dict(lo, vols, poc, vah, val) with lo/poc/vah/val as prices and vols[i] = volume at lo + i*tick."""
    lo_t = [round(r[3] / tick) for r in rows]
    hi_t = [round(r[2] / tick) for r in rows]
    mn, mx = min(lo_t), max(hi_t)
    diff = [0.0] * (mx - mn + 3)
    for a, b, r in zip(lo_t, hi_t, rows):
        per = r[5] / (b - a + 1)
        diff[a - mn] += per
        diff[b - mn + 1] -= per
    prof, run = [], 0.0
    for d_ in diff[:mx - mn + 1]:
        run += d_
        prof.append(run if run > 1e-9 else 0.0)
    total = sum(prof)
    poc = max(range(len(prof)), key=lambda x: prof[x])
    lo = hi = poc
    inside, target, m_ = prof[poc], va_pct * total, len(prof)
    while inside < target:
        up = (prof[hi + 1] if hi + 1 < m_ else 0) + (prof[hi + 2] if hi + 2 < m_ else 0)
        dn = (prof[lo - 1] if lo - 1 >= 0 else 0) + (prof[lo - 2] if lo - 2 >= 0 else 0)
        if (up == 0 and dn == 0) or (hi >= m_ - 1 and lo <= 0):
            break
        if up >= dn:
            hi = min(hi + 2, m_ - 1)
            inside += up
        else:
            lo = max(lo - 2, 0)
            inside += dn
    return dict(lo=mn * tick, vols=prof, poc=(mn + poc) * tick, vah=(mn + hi) * tick, val=(mn + lo) * tick)


def build_profiles(rows, tick, va_pct=0.70, min_bars=MIN_SESSION_BARS):
    """One profile per session (grouped by session_start_ms). The last session is kept even if short: it is the
    developing one. Returns [{s, e, n, developing, lo, vols, poc, vah, val}] with s/e in UTC seconds."""
    groups, cur, cur_s = [], [], None
    for r in rows:
        k = session_start_ms(r[0])
        if k != cur_s:
            if cur:
                groups.append((cur_s, cur))
            cur, cur_s = [], k
        cur.append(r)
    if cur:
        groups.append((cur_s, cur))
    out = []
    for gi, (s, g) in enumerate(groups):
        last = gi == len(groups) - 1
        if len(g) < min_bars and not last:
            continue
        p = session_profile(g, tick, va_pct)
        p.update(s=s // 1000, e=(s + SESSION_MS) // 1000, n=len(g), developing=last)
        out.append(p)
    return out


def write_profiles(out_dir, profiles, tick, va_pct):
    dec = tick_decimals(tick)
    ss = []
    for p in profiles:
        ss.append({"s": p["s"], "e": p["e"], "dev": 1 if p["developing"] else 0, "lo": round(p["lo"], dec),
                   "poc": round(p["poc"], dec), "vah": round(p["vah"], dec), "val": round(p["val"], dec),
                   "v": [int(round(x)) for x in p["vols"]]})
    body = json.dumps({"tick": tick, "vaPct": va_pct, "sessions": ss}, separators=(",", ":"))
    path = Path(out_dir) / "profiles.js"
    path.write_text("window.VIZ_PROFILES = " + body + ";\n", encoding="utf-8")
    return path.stat().st_size


# ---- Gianluca Bruni pre-signal indicators (15m): regime freshness, price efficiency, autocorrelation ----
AC_WINDOW = 100     # gb_backtest CFG["kelly_window"]: shadow-outcome window for the autocorrelation strength
AC_FULL = 0.16      # gb_backtest's guess for the 0-10 scale (GB-Q24: the video gives no formula)


def load_gb_backtest():
    """Import analysis/gb_nq_backtest/gb_backtest.py so BOCPD / volume SMA are the SAME code the backtest ran."""
    d = Path(__file__).resolve().parent.parent / "gb_nq_backtest"
    if not (d / "gb_backtest.py").exists():
        return None
    if str(d) not in sys.path:
        sys.path.insert(0, str(d))
    import gb_backtest
    return gb_backtest


def ac_strength(w):
    """gb_backtest.score_and_size()'s memory strength from a list of 1/0 outcomes (0 until 30 trades / 5 post-win pairs)."""
    n = len(w)
    if n < 30:
        return 0.0
    pw = sum(w) / n
    pairs = [(a, b) for a, b in zip(w, w[1:]) if a == 1]
    if len(pairs) < 5:
        return 0.0
    pww = sum(b for _, b in pairs) / len(pairs)
    return max(0.0, min(10.0, 10 * (pww - pw) / AC_FULL))


def autocorr_events(trades_csv):
    """From a gb_backtest trades.csv: after each trade's exit, the memory strength and what the NEXT trade is.
    best = L-L-L-W-(W) (trade after the first win that ends a losing streak), skip = W-W-W-L-(L), both only when
    strength >= 3 (same gate as the backtest). Returns [[t_sec, strength, code, last5]], ascending."""
    with open(trades_csv, newline="", encoding="utf-8") as f:
        tr = sorted(csv.DictReader(f), key=lambda r: int(r["entry_t"]))
    hist, ev = [], []
    for r in tr:
        hist.append(1 if float(r["r_net"]) > 0 else 0)
        st = ac_strength(hist[-AC_WINDOW:])
        code = "none"
        if st >= 3 and len(hist) >= 3:
            if hist[-1] == 1 and hist[-2] == 0 and hist[-3] == 0:
                code = "best"
            elif hist[-1] == 0 and hist[-2] == 1 and hist[-3] == 1:
                code = "skip"
        ev.append([int(r["exit_t"]) // 1000, round(st, 1), code, "".join("W" if x else "L" for x in hist[-5:])])
    return ev


def gb_trade_blocks(trades_csv):
    """One RR block per trade of a gb_backtest trades.csv: entry/stop/target as traded (target already capped at the MS
    high where that happened), from the entry time to the exit time, labelled #n, net R and exit reason."""
    with open(trades_csv, newline="", encoding="utf-8") as f:
        tr = sorted(csv.DictReader(f), key=lambda r: int(r["entry_t"]))
    out = []
    for n, r in enumerate(tr, 1):
        b = {"id": "gb%d" % n, "side": r["dir"], "start": int(r["entry_t"]) // 1000, "end": int(r["exit_t"]) // 1000,
             "entry": float(r["entry"]), "stop": float(r["stop"]), "target": float(r["target"]),
             "label": "#%d %+.2fR %s" % (n, float(r["r_net"]), r["reason"])}
        try:
            out.append(validate_block(b, n))
        except ValueError as e:
            print("WARNING: trade #%d skipped: %s" % (n, e), file=sys.stderr)
    return out


def build_indicators(rows, days_15m, trades_csv=None, gb=None):
    """Per-15m-bar BOCPD regime age + volume reducing / low-effort flags, aligned with data_15m.js, plus the
    autocorrelation events from a backtest's trades.csv. Computed on the FULL 15m history (BOCPD needs it), then trimmed."""
    gb = gb or load_gb_backtest()
    if gb is None:
        return None
    bars = aggregate(rows, tf_key("15m"))
    D = dict(bh=[b[2] for b in bars], bl=[b[3] for b in bars], bc=[b[4] for b in bars], bv=[b[5] for b in bars])
    ages = gb.bocpd_age(D, gb.CFG)
    atrp = gb.atr_pct(D, gb.CFG)
    vma = gb.sma_prev(D["bv"], gb.CFG["vol_ma"])
    last_ms = rows[-1][0] if rows else 0
    cutoff = last_ms - int(days_15m * DAY_MS) if days_15m is not None else -1
    out = []
    for i, b in enumerate(bars):
        if b[0] < cutoff:
            continue
        red = 1 if i > 0 and b[5] < bars[i - 1][5] else 0
        low = 1 if vma[i] is not None and b[5] < vma[i] else 0
        out.append([b[0] // 1000, ages[i], red, low, int(b[5]), None if vma[i] is None else round(vma[i], 1),
                    None if atrp[i] is None else round(atrp[i], 1)])
    ac = autocorr_events(trades_csv) if trades_csv else []
    return dict(tf=900, hazard=gb.CFG["hazard"], bestAge=gb.CFG["best_age"], bestAtr=list(gb.CFG["best_atr"]), bars=out, ac=ac, hasAc=bool(trades_csv))


def write_indicators(out_dir, ind):
    path = Path(out_dir) / "indicators.js"
    path.write_text("window.VIZ_IND = " + json.dumps(ind, separators=(",", ":")) + ";\n", encoding="utf-8")
    return path.stat().st_size


def build(rows, symbol, tick, out_dir, csv_name, days_1m=60, days_5m=365, days_15m=1095, chart_html=None, overlays=None,
          profiles=True, va_pct=0.70, indicators=False, gb_trades=None):
    """rows: sorted de-duplicated tuples. Writes files; returns {tf: (bars, bytes)}."""
    dec = tick_decimals(tick)
    limits = {"1m": days_1m, "5m": days_5m, "15m": days_15m}
    out = Path(out_dir) / symbol
    out.mkdir(parents=True, exist_ok=True)
    last_ms = rows[-1][0] if rows else 0
    summary, counts = {}, {}
    for tf in TIMEFRAMES:
        bars = rows if tf == "1m" else None
        if tf == "1m":
            bars = [list(r) for r in trim(rows, limits[tf], last_ms)]
        else:
            bars = trim(aggregate(rows, tf_key(tf)), limits.get(tf), last_ms)
        js = to_js_bars(bars, dec)
        p = out / ("data_%s.js" % tf)
        write_js(p, tf, js)
        counts[tf] = len(js)
        summary[tf] = (len(js), p.stat().st_size)
    meta = {
        "symbol": symbol, "tick": tick, "source": csv_name,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "first": rows[0][0] // 1000 if rows else None, "last": last_ms // 1000 if rows else None,
        "counts": counts, "profiles": bool(profiles), "indicators": bool(indicators),
    }
    (out / "meta.js").write_text("window.VIZ_META = " + json.dumps(meta, separators=(",", ":")) + ";\n",
                                 encoding="utf-8")
    html = Path(chart_html) if chart_html else Path(__file__).resolve().parent / "chart.html"
    if html.exists():
        shutil.copyfile(html, out / "index.html")
    rr_js = html.parent / "rr_block.js"
    if rr_js.exists():
        shutil.copyfile(rr_js, out / "rr_block.js")
    write_overlays(out, overlays or [])
    vp_js = html.parent / "vp_profile.js"
    if vp_js.exists():
        shutil.copyfile(vp_js, out / "vp_profile.js")
    if indicators:
        ind = build_indicators(rows, days_15m, gb_trades)
        if ind is None:
            print("WARNING: --indicators needs analysis/gb_nq_backtest/gb_backtest.py; skipped")
        else:
            summary["indicators"] = (len(ind["bars"]), write_indicators(out, ind))
    if profiles:
        profs = build_profiles(rows, tick, va_pct)
        summary["profiles"] = (len(profs), write_profiles(out, profs, tick, va_pct))
    return summary


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--csv", default="analysis/data/GC_1m_latest.csv")
    ap.add_argument("--symbol", default="GC")
    ap.add_argument("--tick", type=float, default=0.1)
    ap.add_argument("--out", default="reports/viz")
    ap.add_argument("--days-1m", type=float, default=60)
    ap.add_argument("--days-5m", type=float, default=365)
    ap.add_argument("--days-15m", type=float, default=1095)
    ap.add_argument("--overlays", default=None, help="JSON array of RR blocks to draw on the chart")
    ap.add_argument("--va-pct", type=float, default=0.70, help="value-area share of session volume (GB-Q4 default 70%%)")
    ap.add_argument("--indicators", action="store_true",
                    help="add the Gianluca pre-signal panel: BOCPD regime age + volume flags per 15m bar (~25 s on NQ)")
    ap.add_argument("--gb-trades", default=None, help="gb_backtest trades.csv: adds the autocorrelation / best-trade row")
    ap.add_argument("--no-gb-blocks", action="store_true", help="with --gb-trades: do not draw the trades as RR blocks")
    ap.add_argument("--no-profiles", action="store_true", help="skip the session volume profiles")
    a = ap.parse_args()
    t0 = time.time()
    arrs, n = read_csv(a.csv)
    rows, dups = sort_dedup(arrs)
    del arrs
    print("rows read: %d, duplicates dropped: %d, unique bars: %d" % (n, dups, len(rows)))
    overlays = load_overlays(a.overlays)
    if a.gb_trades and not a.no_gb_blocks:
        tb = gb_trade_blocks(a.gb_trades)
        overlays = tb + overlays
        print("trade RR blocks: %d from %s" % (len(tb), a.gb_trades))
    summary = build(rows, a.symbol, a.tick, a.out, Path(a.csv).name, a.days_1m, a.days_5m, a.days_15m,
                    overlays=overlays, profiles=not a.no_profiles, va_pct=a.va_pct,
                    indicators=a.indicators or bool(a.gb_trades), gb_trades=a.gb_trades)
    if "indicators" in summary:
        cnt, size = summary["indicators"]
        print("  indicators: %d 15m bars  %8.2f MB" % (cnt, size / 1e6))
    if "profiles" in summary:
        cnt, size = summary["profiles"]
        print("  session profiles: %d sessions  %8.2f MB" % (cnt, size / 1e6))
    if a.overlays:
        print("overlays: %d block(s) from %s" % (len(overlays), a.overlays))
    for tf in TIMEFRAMES:
        cnt, size = summary[tf]
        print("  %-3s %9d bars  %8.2f MB" % (tf, cnt, size / 1e6))
    print("output: %s  (%.1fs)" % (Path(a.out) / a.symbol, time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
