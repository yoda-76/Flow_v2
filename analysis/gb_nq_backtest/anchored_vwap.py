"""Daily Anchored VWAP Signal Strategy -- Python port of the rq_sdk strategy `DailyAnchoredVwapStrategy`, backtested on 1m bars.

A line-for-line port: same state machine, same order of updates inside `on_bar`, same defaults. Where the Rust source
leaves the engine's behaviour open, the assumption is listed in ASSUMPTIONS below. Reuses helpers of va_break_momentum.py and
vwap_ema9.py. Pure standard library. Read-only over the CSV; writes only under --out. Places no orders.

    python anchored_vwap.py --csv analysis/data/NQ_1m_latest.csv --days 365 --tf 5 --mult 2 --tick 0.25 --equity 25000 --comm 1.0 --out reports/avwap_mnq
"""
import sys, os, argparse, collections, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gb_backtest import stats, DAY_MS
from va_break_momentum import load_window, EtClock, scan_range, atr_series, iso
from vwap_ema9 import Pos, write_trades, ema_series

ASSUMPTIONS = """\
ASSUMPTIONS (the Rust source does not say)
 1. The timeframe is not in the source. Default 5 minutes (--tf), bars built from 1m and aligned to the UTC epoch (= ET).
    bar.time = bar open; ET hhmm/date come from it. EMA(20), ATR(14) and baseline ATR(50) run continuously over all bars.
 2. EMA = standard EMA seeded with the SMA of the first n closes; ATR = Wilder's (SMA seed). Fills: market orders at the bar
    close +/- 1 tick (adverse); the ATR stop fills at the stop (or the 1m open if it gapped) -/+ 1 tick; commission per
    contract round trip (--comm). The stop is checked on 1m resolution inside the bar.
 3. The "daily" VWAP resets at the ET CALENDAR DATE change (midnight ET) and accumulates every bar, including the overnight
    session; only signals are limited to 09:30-16:00 ET (inclusive of the 16:00 bar). Kept as written.
 4. Source quirks kept on purpose: (a) several early returns (no EMA yet, no volume, ATR not ready) happen BEFORE prev_close /
    prev_vwap are updated; (b) the signal chain is one if / else-if ladder, so only one event per bar; (c) the marker names are
    "wrong way round" for the usual convention - a GREEN dot enters LONG, a RED dot enters SHORT, a GREEN triangle closes a LONG
    and a RED triangle closes a SHORT; (d) there is no time exit and no session flatten: a position can stay open overnight or
    over a weekend until its stop or a triangle fires - and triangles only fire inside the 09:30-16:00 window;
    (e) first_trade_won uses the trade's NET P&L (after commission), the daily counters reset at midnight ET.
 5. A position closed by its stop during a bar updates the daily counters before that bar's on_bar runs; a close_position() from a
    triangle updates them right after that bar's signal logic.
"""

P = dict(use_typical_price=True, session_start_hhmm=930, session_end_hhmm=1600, ema_length=20, contracts=1, atr_period=14,
         atr_stop_multiplier=2.0, use_atr_stop=True, use_relative_volatility_filter=True, baseline_atr_period=50,
         max_relative_atr=1.20, additional_attempts=2)
TF_MS = 5 * 60_000
FILL = "close"          # "close": market orders fill at the signal bar's close; "next_open": at the open of the next 1m bar (more realistic)


def make_bars(t, o, h, l, c, v):
    bars, i, n = [], 0, len(t)
    while i < n:
        slot = t[i] // TF_MS
        j = i
        while j + 1 < n and t[j + 1] // TF_MS == slot:
            j += 1
        bars.append(dict(t0=slot * TF_MS, o=o[i], h=max(h[i:j + 1]), l=min(l[i:j + 1]), c=c[j], v=sum(v[i:j + 1]), i0=i, i1=j))
        i = j + 1
    return bars


def run(bars, m1, p, cost, funnel):
    clock = EtClock()
    tick, slip, mult = cost["tick"], cost["slip_ticks"] * cost["tick"], cost["mult"]
    m1t = m1[0]
    emas = ema_series(bars, max(p["ema_length"], 1))
    atrs = atr_series(bars, max(p["atr_period"], 1))
    batrs = atr_series(bars, max(p["baseline_atr_period"], 2))
    a, b = max(min(p["session_start_hhmm"], 2359), 0), max(min(p["session_end_hhmm"], 2359), 0)

    def in_session(h):
        return (a <= h <= b) if a <= b else (h >= a or h <= b)

    day = 0
    pv = vol = 0.0
    regime = 0
    waiting_red = waiting_green = has_prev = False
    prev_close = prev_vwap = 0.0
    first_closed = first_won = False
    attempts = 0
    pos, trades = None, []
    NOTP = 1e18

    def allowed():
        if not first_closed:
            return True
        return False if first_won else attempts < p["additional_attempts"]

    def finish(ps):                                     # on_trade(PositionClosed)
        nonlocal first_closed, first_won, attempts
        tr = ps.trade()
        trades.append(tr)
        if not first_closed:
            first_closed, first_won = True, tr["pnl_usd"] > 0.0
        else:
            attempts += 1

    for bi, bar in enumerate(bars):
        close_t = bar["t0"] + TF_MS
        if pos is not None:
            hit = scan_range(pos.side, pos.sl, pos.tp, bar["i0"], bar["i1"], m1, tick, slip)
            if hit:
                px, ei, why = hit
                pos.close(px); pos.reason = why; pos.exit_t = m1t[ei] + 60000
                finish(pos); pos = None
        hhmm, dnum = clock.hhmm_date(bar["t0"])

        if dnum != day:
            day, pv, vol, regime = dnum, 0.0, 0.0, 0
            waiting_red = waiting_green = has_prev = False
            first_closed = first_won = False
            attempts = 0
        px_ = (bar["h"] + bar["l"] + bar["c"]) / 3.0 if p["use_typical_price"] else bar["c"]
        v = max(bar["v"], 0.0)
        pv += px_ * v
        vol += v
        ema = emas[bi]
        if ema is None:
            continue
        if vol <= 0.0:
            continue
        vwap = pv / vol
        atr, batr = atrs[bi], batrs[bi]
        if atr is None or batr is None or atr <= 0.0 or batr <= 0.0:
            continue
        rel = atr / batr
        vol_ok = (not p["use_relative_volatility_filter"]) or rel <= max(p["max_relative_atr"], 0.1)
        red_dot = green_dot = red_tri = green_tri = False
        c = bar["c"]
        if in_session(hhmm):
            if waiting_red and c > ema:
                waiting_red, regime, red_tri = False, -2, True
            elif waiting_green and c < ema:
                waiting_green, regime, green_tri = False, 2, True
            elif regime == -2 and ema > vwap:
                regime = -3
            elif regime == 2 and ema < vwap:
                regime = 3
            elif regime == -3 and has_prev and prev_close >= prev_vwap and c < vwap:
                regime, green_dot = 0, True
            elif regime == 3 and has_prev and prev_close <= prev_vwap and c > vwap:
                regime, red_dot = 0, True
            elif regime == 0:
                if ema < vwap and c < ema:
                    waiting_red, regime, red_dot = True, -1, True
                elif ema > vwap and c > ema:
                    waiting_green, regime, green_dot = True, 1, True
        if green_dot or red_dot:
            funnel["dot signals"] += 1
        gate_ok = hhmm >= p.get("entry_min_hhmm", 0) and not (p.get("skip_friday") and (dnum + 3) % 7 == 4)   # optional entry filters (research)
        if allowed() and vol_ok and pos is None and gate_ok:
            dist = atr * max(p["atr_stop_multiplier"], 0.1)
            if p.get("fixed_stop_usd"):                                   # research: fixed dollar stop for the whole position
                dist = p["fixed_stop_usd"] / (mult * max(p["contracts"], 1))
            side = 1 if green_dot else (-1 if red_dot else 0)
            if side:
                stop = c - side * dist if (p["use_atr_stop"] or p.get("fixed_stop_usd")) else -side * NOTP
                px_fill = (m1[1][bar["i1"] + 1] if FILL == "next_open" and bar["i1"] + 1 < len(m1[1]) else c)
                pos = Pos(side, max(p["contracts"], 1), px_fill + side * slip, stop, side * NOTP, dist, mult, cost["comm_rt"],
                          dict(entry_t=close_t, signal=c, stop0=stop, hhmm=hhmm, atr=atr))
                funnel["ENTERED"] += 1
        elif green_dot or red_dot:
            if not vol_ok:
                funnel["blocked: relative ATR above the limit"] += 1
            elif not gate_ok:
                funnel["blocked: entry filter (time / weekday)"] += 1
            elif pos is not None:
                funnel["blocked: already in a position"] += 1
            else:
                funnel["blocked: daily attempts used up / first trade won"] += 1
        if (green_tri and pos is not None and pos.side > 0) or (red_tri and pos is not None and pos.side < 0):
            px_fill = (m1[1][bar["i1"] + 1] if FILL == "next_open" and bar["i1"] + 1 < len(m1[1]) else c)
            pos.close(px_fill - pos.side * slip); pos.reason = "triangle"; pos.exit_t = close_t
            finish(pos); pos = None
        prev_close, prev_vwap, has_prev = c, vwap, True
    if pos is not None:
        c = bars[-1]["c"]
        pos.close(c - pos.side * slip); pos.reason, pos.exit_t = "data_end", bars[-1]["t0"] + TF_MS
        finish(pos)
    return trades


def report(trades, days, equity_start, mult):
    out = []
    P_ = out.append
    P_(stats([t["r_net"] for t in trades], "ALL (net)"))
    P_(stats([t["r_gross"] for t in trades], "ALL (gross)"))
    P_(stats([t["r_net"] for t in trades if t["side"] > 0], "long"))
    P_(stats([t["r_net"] for t in trades if t["side"] < 0], "short"))
    P_("--- by exit reason")
    for rsn in ("stop", "triangle", "data_end"):
        P_(stats([t["r_net"] for t in trades if t["reason"] == rsn], rsn))
    if trades:
        holds = sorted((t["exit_t"] - t["entry_t"]) / 60000 for t in trades)
        overnight = sum(1 for h in holds if h > 600)
        P_(f"hold time (min): median {holds[len(holds) // 2]:.0f}, max {holds[-1]:.0f}; held over 10 hours: {overnight} trades")
        P_("--- by quarter of the window")
        t0, t1 = trades[0]["entry_t"], trades[-1]["entry_t"] + 1
        for q in range(4):
            lo, hi = t0 + (t1 - t0) * q // 4, t0 + (t1 - t0) * (q + 1) // 4
            P_(stats([t["r_net"] for t in trades if lo <= t["entry_t"] < hi], f"{iso(lo)[:10]}..{iso(hi)[:10]}"))
    usd = sum(t["pnl_usd"] for t in trades)
    clock = EtClock()
    day_pnl = collections.defaultdict(float)
    for t in trades:
        day_pnl[clock.hhmm_date(t["exit_t"] - 1)[1]] += t["pnl_usd"]
    eq = peak = mdd = 0.0
    for t in trades:
        eq += t["pnl_usd"]; peak = max(peak, eq); mdd = max(mdd, peak - eq)
    P_("--- dollars (1 contract)")
    P_(f"net P&L ${usd:+,.0f} on ${equity_start:,.0f} start ({usd / equity_start:+.1%}); max drawdown ${mdd:,.0f} ({mdd / equity_start:.1%})"
       + (f"; worst day ${min(day_pnl.values()):+,.0f}, best day ${max(day_pnl.values()):+,.0f}; {len(day_pnl)} days with an exit" if day_pnl else ""))
    if trades:
        P_(f"trades {len(trades)} in {days} days; avg stop {sum(t['dist'] for t in trades) / len(trades):.1f} points "
           f"(${sum(t['dist'] for t in trades) / len(trades) * mult:,.0f} per contract)")
    return "\n".join(out)


def main():
    global TF_MS, FILL
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--warmup-days", type=int, default=10)
    ap.add_argument("--mult", type=float, required=True)
    ap.add_argument("--tick", type=float, required=True)
    ap.add_argument("--equity", type=float, default=25000.0)
    ap.add_argument("--comm", type=float, default=1.0)
    ap.add_argument("--tf", type=int, default=5, help="bar minutes (the source never says)")
    ap.add_argument("--ema", type=int, default=None, help="EMA length (source default 20)")
    ap.add_argument("--contracts", type=int, default=None, help="contracts per trade (source default 1)")
    ap.add_argument("--atr-mult", type=float, default=None, help="ATR stop multiplier (source default 2.0)")
    ap.add_argument("--stop-usd", type=float, default=None, help="fixed dollar stop for the whole position (research); overrides the ATR stop distance")
    ap.add_argument("--no-atr-stop", action="store_true", help="use_atr_stop = false: no stop at all, exits only on a triangle")
    ap.add_argument("--max-rel-atr", type=float, default=None, help="maximum relative ATR (source default 1.20)")
    ap.add_argument("--start", default=None, help="window start date YYYY-MM-DD (UTC midnight); overrides --days")
    ap.add_argument("--fill", choices=["close", "next_open"], default="close", help="where market orders fill: the signal bar close (default) or the next 1m open")
    ap.add_argument("--slip-ticks", type=float, default=1.0)
    ap.add_argument("--end", default=None, help="window end date YYYY-MM-DD (UTC midnight); default = last bar in the file")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.ema is not None: P["ema_length"] = a.ema
    if a.contracts is not None: P["contracts"] = a.contracts
    if a.atr_mult is not None: P["atr_stop_multiplier"] = a.atr_mult
    if a.no_atr_stop: P["use_atr_stop"] = False
    if a.stop_usd: P["fixed_stop_usd"] = a.stop_usd
    if a.max_rel_atr is not None: P["max_relative_atr"] = a.max_rel_atr
    TF_MS = a.tf * 60_000
    FILL = a.fill
    cost = dict(tick=a.tick, slip_ticks=a.slip_ticks, comm_rt=a.comm, mult=a.mult, start_equity=a.equity)
    t0 = time.time()
    with open(a.csv, "rb") as fh:
        fh.seek(0, 2); fh.seek(max(fh.tell() - 4096, 0))
        last_ms = int(fh.read().decode().strip().splitlines()[-1].split(",")[0])
    if a.end:
        import datetime as _dt
        last_ms = int(_dt.datetime.strptime(a.end, "%Y-%m-%d").replace(tzinfo=_dt.timezone.utc).timestamp() * 1000)
    rep_from = last_ms - a.days * DAY_MS
    if a.start:
        import datetime as _dt2
        rep_from = int(_dt2.datetime.strptime(a.start, "%Y-%m-%d").replace(tzinfo=_dt2.timezone.utc).timestamp() * 1000)
        a.days = int((last_ms - rep_from) / DAY_MS)
    m1 = load_window(a.csv, rep_from - a.warmup_days * DAY_MS)
    if a.end:                                            # drop bars after the window end
        import bisect as _bs
        k = _bs.bisect_right(m1[0], last_ms)
        m1 = tuple(col[:k] for col in m1)
    print(f"loaded {len(m1[0])} 1m bars {iso(m1[0][0])} -> {iso(m1[0][-1])} UTC ({time.time() - t0:.0f}s)", flush=True)
    bars = make_bars(*m1)
    funnel = collections.Counter()
    trades = [t for t in run(bars, m1, P, cost, funnel) if t["entry_t"] >= rep_from]
    os.makedirs(a.out, exist_ok=True)
    write_trades(os.path.join(a.out, "trades.csv"), trades)
    head = (f"DailyAnchoredVwap on {os.path.basename(a.csv)}, {a.tf}m bars, last {a.days} days ({iso(rep_from)} -> {iso(m1[0][-1])} UTC)\n"
            f"params {P}\ncosts {cost}\n")
    body = report(trades, a.days, a.equity, a.mult)
    body += "\n--- signal funnel (whole run incl. warm-up)\n" + "\n".join(f"{k:<52} {v}" for k, v in sorted(funnel.items(), key=lambda kv: -kv[1]))
    with open(os.path.join(a.out, "summary.txt"), "w", encoding="utf-8") as fh:
        fh.write(head + body + "\n\n" + ASSUMPTIONS)
    print("\n" + head + body)


if __name__ == "__main__":
    main()
