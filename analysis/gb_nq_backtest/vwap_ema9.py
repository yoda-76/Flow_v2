"""3-minute VWAP / EMA(9) crossover v2 -- Python port of the rq_sdk strategy `VwapEma9Cross3m`, backtested on 1m bars.

A line-for-line port: same state machine, same order of updates inside `on_bar`, same defaults. Where the Rust source
leaves the engine's behaviour open, the assumption is listed in ASSUMPTIONS below. Reuses the helpers of
va_break_momentum.py (ET clock, 1m stop scan, ATR, stats). Pure standard library. Read-only over the CSV; writes only
under --out. Places no orders.

    python vwap_ema9.py --csv analysis/data/NQ_1m_latest.csv --days 365 --mult 2 --tick 0.25 --equity 25000 --comm 1.0 --out reports/vwap_mnq
    python vwap_ema9.py --csv analysis/data/GC_1m_latest.csv --days 365 --mult 10 --tick 0.1  --equity 25000 --comm 1.0 --out reports/vwap_mgc
"""
import sys, os, math, argparse, collections, datetime, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gb_backtest import stats, DAY_MS
from va_break_momentum import load_window, EtClock, scan_range, atr_series, iso

ASSUMPTIONS = """\
ASSUMPTIONS (the Rust source does not say)
 1. Bars are 3 minutes (the strategy name), built from 1m and aligned to the UTC epoch (= ET). bar.time = bar open; ET hhmm
    and date come from it. EMA and ATR run continuously over all bars the chart has (24h futures), never reset.
 2. EMA(9) = standard EMA (alpha = 2/(n+1)) seeded with the SMA of the first 9 closes; first value on the 9th bar.
    ATR(14) = Wilder's, seeded with the SMA of the first 14 true ranges.
 3. Market orders (entry, EMA-close exit, flatten) fill at the bar close +/- 1 tick (adverse). A hard stop fills at the stop
    (or the 1m open if it gapped through) -/+ 1 tick. Stop is checked inside the bar on 1m resolution. Commission is charged
    per contract round trip (--comm). No profit target by default (target_r = 0).
 4. on_bar logic (breakeven, EMA-close exit, flatten, entries) runs at the 3m bar close. A stop moved to breakeven takes effect
    from the next 1m bar. set_position_sl_tp(Some(entry), None) keeps any existing target.
 5. Source quirks kept on purpose: (a) the day change and the anchor reset BEFORE the 'not anchored -> return', so bars
    between midnight ET and 09:30 ET do nothing at all (no VWAP, no position management); (b) 'anchored' stays true after the
    session end, so VWAP keeps accumulating through the evening session until midnight ET; (c) bars_since_exit is reset to 0 at
    ENTRY and only counts bars that start flat, so with cooldown_bars = 1 a new entry needs two flat bars; (d) opp_bars is
    updated AFTER the entry check, so at a cross it still holds the streak the EMA had on the OLD side; (e) flatten fires on
    the first bar whose OPEN time is >= 15:58, i.e. the 16:00 bar, and fills at that bar's close (16:03 ET).
"""

P = dict(ema_period=9, qty=1, allow_short=True, anchor_hhmm=930, session_filter=True, session_end_hhmm=1600,
         flat_at_end=True, exit_hhmm=1558, min_bars_opposite=5, require_slope=True, early_ticks=0, ema_close_stop=True,
         min_hold_bars=2, stop_atr_mult=5.0, atr_period=14, target_r=0.0, breakeven_r=1.0, cooldown_bars=1)
TF_MS = 3 * 60_000


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


def ema_series(bars, n):
    out, e, k = [], None, 2.0 / (n + 1)
    for idx, b in enumerate(bars):
        if idx == n - 1:
            e = sum(x["c"] for x in bars[:n]) / n
        elif idx >= n:
            e = b["c"] * k + e * (1 - k)
        out.append(e)
    return out


class Pos:
    def __init__(self, side, qty, entry, sl, tp, dist, mult, comm_rt, meta):
        self.side, self.qty, self.entry, self.sl, self.tp, self.dist = side, qty, entry, sl, tp, dist
        self.mult, self.comm_rt, self.meta = mult, comm_rt, meta
        self.gross = self.comm = 0.0
        self.be_done, self.reason, self.exit_t = False, None, None

    def close(self, px):
        self.gross = self.side * (px - self.entry) * self.mult * self.qty
        self.comm = self.comm_rt * self.qty

    def trade(self):
        risk = self.dist * self.mult * self.qty
        pnl = self.gross - self.comm
        return dict(self.meta, side=self.side, qty=self.qty, entry=self.entry, stop=self.meta["stop0"], dist=self.dist,
                    reason=self.reason, exit_t=self.exit_t, pnl_usd=pnl, r_gross=self.gross / risk, r_net=pnl / risk)


def run(bars, m1, p, cost, funnel):
    clock = EtClock()
    tick, slip, mult = cost["tick"], cost["slip_ticks"] * cost["tick"], cost["mult"]
    m1t = m1[0]
    emas, atrs = ema_series(bars, max(p["ema_period"], 2)), atr_series(bars, max(p["atr_period"], 2))
    anchor, end = max(p["anchor_hhmm"], 0), max(p["session_end_hhmm"], 0)
    day, anchored, pv_sum, vol_sum = 0, False, 0.0, 0.0
    prev_ema = prev_vwap = None
    opp_bars = bars_held = bars_since_exit = 0
    risk_px, be_done = 0.0, False
    pos, trades = None, []
    NOTP = 1e18

    def finish(ps):
        trades.append(ps.trade())

    for bi, bar in enumerate(bars):
        close_t = bar["t0"] + TF_MS
        # stop (and target) inside this bar, 1m resolution
        if pos is not None:
            hit = scan_range(pos.side, pos.sl, pos.tp, bar["i0"], bar["i1"], m1, tick, slip)
            if hit:
                px, ei, why = hit
                pos.close(px); pos.reason = "be_stop" if (why == "stop" and pos.be_done) else why
                pos.exit_t = m1t[ei] + 60000
                finish(pos); pos = None
        hhmm, dnum = clock.hhmm_date(bar["t0"])

        # session VWAP anchored at the open, reset once a day
        if dnum != day:
            day, anchored, pv_sum, vol_sum, opp_bars, prev_ema, prev_vwap = dnum, False, 0.0, 0.0, 0, None, None
        if not anchored and hhmm >= anchor:
            anchored, pv_sum, vol_sum, opp_bars, prev_ema, prev_vwap = True, 0.0, 0.0, 0, None, None
        if not anchored:
            continue
        pv_sum += (bar["h"] + bar["l"] + bar["c"]) / 3.0 * bar["v"]
        vol_sum += bar["v"]
        if vol_sum <= 0.0:
            continue
        vwap = pv_sum / vol_sum
        ema = emas[bi]
        if ema is None:
            continue

        # position management
        if pos is not None:
            bars_held += 1
            if p["breakeven_r"] > 0.0 and not be_done and risk_px > 0.0:
                moved = (bar["c"] - pos.entry) if pos.side > 0 else (pos.entry - bar["c"])
                if moved >= p["breakeven_r"] * risk_px:
                    pos.sl, pos.be_done, be_done = pos.entry, True, True
            flatten = p["flat_at_end"] and hhmm >= max(p["exit_hhmm"], 0)
            why = None
            if flatten:
                why = "flatten"
            elif p["ema_close_stop"] and bars_held > p["min_hold_bars"]:
                if (pos.side > 0 and bar["c"] < ema) or (pos.side < 0 and bar["c"] > ema):
                    why = "ema_close"
            if why:
                pos.close(bar["c"] - pos.side * slip); pos.reason = why; pos.exit_t = close_t
                finish(pos); pos = None
        else:
            be_done, bars_held = False, 0
            bars_since_exit += 1

        # entries
        in_window = (not p["session_filter"]) or (anchor <= hhmm < end)
        ready = pos is None and in_window and bars_since_exit > p["cooldown_bars"]
        a = atrs[bi]
        if prev_ema is None or prev_vwap is None or a is None:
            prev_ema, prev_vwap = ema, vwap
            continue
        pe, pvw = prev_ema, prev_vwap
        if math.isfinite(a) and a > 0.0:
            gap = p["early_ticks"] * tick
            slope_up = (not p["require_slope"]) or ema > pe
            slope_dn = (not p["require_slope"]) or ema < pe
            cross_up, cross_dn = pe <= pvw and ema > vwap, pe >= pvw and ema < vwap
            pre_up = gap > 0.0 and pe < pvw and ema < vwap and (vwap - ema) <= gap and ema > pe
            pre_dn = gap > 0.0 and pe > pvw and ema > vwap and (ema - vwap) <= gap and ema < pe
            fresh = opp_bars >= p["min_bars_opposite"]
            if cross_up or cross_dn:
                funnel["cross seen"] += 1
                if not in_window: funnel["blocked: outside session window"] += 1
                elif not ready: funnel["blocked: in position / cooldown"] += 1
                elif not fresh: funnel["blocked: EMA not held the other side long enough"] += 1
                elif (cross_up and not slope_up) or (cross_dn and not slope_dn): funnel["blocked: slope"] += 1
                elif cross_dn and not p["allow_short"]: funnel["blocked: shorts off"] += 1
            side = 0
            if ready and fresh and (cross_up or pre_up) and slope_up:
                side = 1
            elif ready and fresh and p["allow_short"] and (cross_dn or pre_dn) and slope_dn:
                side = -1
            if side:
                dist = p["stop_atr_mult"] * a
                entry = bar["c"] + side * slip
                stop = bar["c"] - side * dist
                tp = (bar["c"] + side * p["target_r"] * dist) if p["target_r"] > 0 else side * NOTP
                risk_px, be_done, bars_held, bars_since_exit = dist, False, 0, 0
                pos = Pos(side, p["qty"], entry, stop, tp, dist, mult, cost["comm_rt"],
                          dict(entry_t=close_t, signal=bar["c"], stop0=stop, hhmm=hhmm, atr=a))
                funnel["entered"] += 1

        # streak of bars the EMA has held on its current side of VWAP
        if ema > vwap:
            opp_bars = 0 if pe <= pvw else opp_bars + 1
        elif ema < vwap:
            opp_bars = 0 if pe >= pvw else opp_bars + 1
        prev_ema, prev_vwap = ema, vwap
    if pos is not None:                      # data ended with the position open
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
    for rsn in ("ema_close", "stop", "be_stop", "flatten", "target", "data_end"):
        P_(stats([t["r_net"] for t in trades if t["reason"] == rsn], rsn))
    P_("--- by entry hour (ET)")
    for h in range(9, 16):
        P_(stats([t["r_net"] for t in trades if h * 100 <= t["hhmm"] < (h + 1) * 100], f"{h:02d}:00"))
    P_("--- by quarter of the window")
    if trades:
        t0, t1 = trades[0]["entry_t"], trades[-1]["entry_t"] + 1
        for q in range(4):
            a, b = t0 + (t1 - t0) * q // 4, t0 + (t1 - t0) * (q + 1) // 4
            P_(stats([t["r_net"] for t in trades if a <= t["entry_t"] < b], f"{iso(a)[:10]}..{iso(b)[:10]}"))
    usd = sum(t["pnl_usd"] for t in trades)
    clock = EtClock()
    day_pnl = collections.defaultdict(float)
    for t in trades:
        day_pnl[clock.hhmm_date(t["exit_t"] - 1)[1]] += t["pnl_usd"]
    eq = peak = mdd = 0.0
    for t in trades:
        eq += t["pnl_usd"]; peak = max(peak, eq); mdd = max(mdd, peak - eq)
    P_("--- dollars (1 contract)")
    P_(f"net P&L ${usd:+,.0f} on ${equity_start:,.0f} start ({usd / equity_start:+.1%}); max drawdown ${mdd:,.0f}"
       + (f"; worst day ${min(day_pnl.values()):+,.0f}, best day ${max(day_pnl.values()):+,.0f}; "
          f"{len(day_pnl)} trading days with an exit" if day_pnl else ""))
    if trades:
        P_(f"trades {len(trades)} in {days} days; avg stop {sum(t['dist'] for t in trades) / len(trades):.2f} points "
           f"(${sum(t['dist'] for t in trades) / len(trades) * mult:,.0f} per contract)")
    return "\n".join(out)


def write_trades(path, trades):
    cols = ["entry_t", "exit_t", "side", "qty", "signal", "entry", "stop", "reason", "dist", "r_gross", "r_net", "pnl_usd", "hhmm", "atr"]
    with open(path, "w") as fh:
        fh.write(",".join(cols) + "\n")
        for t in trades:
            fh.write(",".join(str(round(t[c], 4)) if isinstance(t[c], float) else str(t[c]) for c in cols) + "\n")


def main():
    global TF_MS
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--warmup-days", type=int, default=10)
    ap.add_argument("--mult", type=float, required=True, help="point value: MNQ 2, NQ 20, MGC 10, GC 100")
    ap.add_argument("--tick", type=float, required=True, help="tick size: NQ/MNQ 0.25, GC/MGC 0.10")
    ap.add_argument("--equity", type=float, default=25000.0)
    ap.add_argument("--comm", type=float, default=1.0, help="commission per contract, round trip (USD)")
    ap.add_argument("--tf", type=int, default=3, help="bar minutes (the strategy says 3)")
    ap.add_argument("--no-short", action="store_true")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    TF_MS = a.tf * 60_000
    cost = dict(tick=a.tick, slip_ticks=1, comm_rt=a.comm, mult=a.mult, start_equity=a.equity)
    p = dict(P, allow_short=not a.no_short)
    t0 = time.time()
    with open(a.csv, "rb") as fh:
        fh.seek(0, 2); fh.seek(max(fh.tell() - 4096, 0))
        last_ms = int(fh.read().decode().strip().splitlines()[-1].split(",")[0])
    rep_from = last_ms - a.days * DAY_MS
    m1 = load_window(a.csv, rep_from - a.warmup_days * DAY_MS)
    print(f"loaded {len(m1[0])} 1m bars {iso(m1[0][0])} -> {iso(m1[0][-1])} UTC ({time.time() - t0:.0f}s)", flush=True)
    bars = make_bars(*m1)
    funnel = collections.Counter()
    trades = [t for t in run(bars, m1, p, cost, funnel) if t["entry_t"] >= rep_from]
    os.makedirs(a.out, exist_ok=True)
    write_trades(os.path.join(a.out, "trades.csv"), trades)
    head = (f"VwapEma9Cross3m on {os.path.basename(a.csv)}, last {a.days} days ({iso(rep_from)} -> {iso(m1[0][-1])} UTC), "
            f"{len(bars)} {a.tf}m bars\nparams {p}\ncosts {cost}\n")
    body = report(trades, a.days, a.equity, a.mult)
    body += "\n--- signal funnel (whole run incl. warm-up)\n" + "\n".join(f"{k:<52} {v}" for k, v in sorted(funnel.items(), key=lambda kv: -kv[1]))
    with open(os.path.join(a.out, "summary.txt"), "w", encoding="utf-8") as fh:
        fh.write(head + body + "\n\n" + ASSUMPTIONS)
    print("\n" + head + body)


if __name__ == "__main__":
    main()
