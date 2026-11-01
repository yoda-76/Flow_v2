"""5-minute "opening gap + late-day strength" long-only day trade -- Python port of the rq_sdk strategy `GapLateStrength5m`.

A line-for-line port: same state machine, same order of updates inside `on_bar`, same defaults. Where the Rust source
leaves the engine's behaviour open, the assumption is listed in ASSUMPTIONS below. Reuses helpers of va_break_momentum.py
and vwap_ema9.py. Pure standard library. Read-only over the CSV; writes only under --out. Places no orders.

    python gap_late.py --csv analysis/data/NQ_1m_latest.csv --days 365 --mult 2 --tick 0.25 --equity 25000 --comm 1.0 --out reports/gap_mnq
"""
import sys, os, argparse, collections, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gb_backtest import stats, DAY_MS
from va_break_momentum import load_window, EtClock, scan_range, iso
from vwap_ema9 import Pos, write_trades

ASSUMPTIONS = """\
ASSUMPTIONS (the Rust source does not say)
 1. Bars are 5 minutes (the strategy name), built from 1m and aligned to the UTC epoch (= ET). bar.time = bar open; ET hhmm and
    date come from it. A bar is "RTH" when its OPEN time is in [09:30, 16:00).
 2. The signal is read on the first bar whose open time is >= 15:00, using THAT bar's close (so the price is the 15:05 price);
    the market buy fills at that same bar close + 1 tick, i.e. ~15:05. Exit fills at the close of the first bar whose open is
    >= 15:55 (the 16:00 close) - 1 tick. Commission per contract round trip (--comm).
 3. The 1% stop is placed by the NEXT on_bar after the entry bar (the source sets it when it first sees a position), so it is
    live from the 1m bar after that one: there is no stop for the first ~5 minutes. Stop fills at the stop (or the 1m open if it
    gapped through) - 1 tick. It is checked on 1m resolution.
 4. Sizing: notional / (price x multiplier), floored, at least 1 -> with MNQ at ~25-30k that is 1 contract for the default
    100,000 notional.
 5. The regime SMA is over the last 50 sessions' RTH closes BEFORE today (today's close is not in it), as in the source.
    Sessions are ET calendar dates that had at least one RTH bar. The strategy needs 50 such sessions of history: the loader
    reads extra warm-up so the window itself is not starved.
 6. Source quirk kept: signaled is set the first time the 15:00+ code is reached, so the day is evaluated once; a failed
    filter at that bar means no trade that day.
"""

P = dict(min_gap_pct=0.60, regime_days=50, min_range_pos=0.77, signal_hhmm=1500, rth_open_hhmm=930, exit_hhmm=1555,
         stop_pct=1.0, notional=100000.0, fixed_qty=0)
TF_MS = 5 * 60_000


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


def run(bars, m1, p, cost, funnel, days_log):
    clock = EtClock()
    tick, slip, mult = cost["tick"], cost["slip_ticks"] * cost["tick"], cost["mult"]
    m1t = m1[0]
    daily, prev_close, last_rth_close = [], 0.0, 0.0
    day, today_open, hi, lo = 0, 0.0, float("-inf"), float("inf")
    have_open = signaled = traded = stop_set = False
    pos, trades = None, []
    NOSTOP = 1e18

    def finish(ps):
        trades.append(ps.trade())

    for bar in bars:
        close_t = bar["t0"] + TF_MS
        if pos is not None:
            hit = scan_range(pos.side, pos.sl, pos.tp, bar["i0"], bar["i1"], m1, tick, slip)
            if hit:
                px, ei, why = hit
                pos.close(px); pos.reason = why; pos.exit_t = m1t[ei] + 60000
                finish(pos); pos = None
        hhmm, dnum = clock.hhmm_date(bar["t0"])

        if dnum != day:                                         # new ET date: finalise the previous session's RTH close
            if day != 0 and last_rth_close > 0.0:
                daily.append(last_rth_close)
                while len(daily) > max(p["regime_days"], 5):
                    daily.pop(0)
                prev_close = last_rth_close
            day, today_open, hi, lo = dnum, 0.0, float("-inf"), float("inf")
            have_open = signaled = traded = False
            last_rth_close = 0.0

        if p["rth_open_hhmm"] <= hhmm < 1600:
            if not have_open:
                today_open, have_open = bar["o"], True
            hi, lo = max(hi, bar["h"]), min(lo, bar["l"])
            last_rth_close = bar["c"]

        if pos is not None:                                      # position management
            if not stop_set:
                pos.sl = pos.entry * (1.0 - max(p["stop_pct"], 0.01) / 100.0)
                stop_set = True
            if hhmm >= max(p["exit_hhmm"], 0):
                pos.close(bar["c"] - pos.side * slip); pos.reason = "flatten"; pos.exit_t = close_t
                finish(pos); pos = None
            continue
        stop_set = False

        if signaled or traded or hhmm < p["signal_hhmm"] or hhmm >= 1600:
            continue
        signaled = True
        funnel["days evaluated at the signal bar"] += 1
        if not have_open or prev_close <= 0.0 or hi <= lo:
            funnel["skipped: no RTH open / prior close"] += 1
            continue
        if len(daily) < max(p["regime_days"], 5):
            funnel["skipped: not enough history for the SMA"] += 1
            continue
        sma = sum(daily) / len(daily)
        gap_pct = (today_open - prev_close) / prev_close * 100.0
        range_pos = (bar["c"] - lo) / (hi - lo)
        regime_ok = prev_close < sma
        gap_ok = gap_pct > p["min_gap_pct"]
        strength_ok = bar["c"] > today_open and range_pos >= p["min_range_pos"]
        funnel["regime ok (prev close < SMA)"] += regime_ok
        funnel["gap ok (> min gap)"] += gap_ok
        funnel["strength ok (above open and top of range)"] += strength_ok
        funnel["regime + gap"] += regime_ok and gap_ok
        days_log.append((bar["t0"], regime_ok, gap_ok, strength_ok, round(gap_pct, 2), round(range_pos, 2)))
        if not (regime_ok and gap_ok and strength_ok):
            continue
        px = bar["c"]
        qty = p["fixed_qty"] if p["fixed_qty"] > 0 else max(int(p["notional"] // (px * mult)), 1)
        traded = True
        dist = px * max(p["stop_pct"], 0.01) / 100.0
        pos = Pos(1, qty, px + slip, -NOSTOP, NOSTOP, dist, mult, cost["comm_rt"],
                  dict(entry_t=close_t, signal=px, stop0=px * (1 - p["stop_pct"] / 100.0), hhmm=hhmm, atr=0.0))
        funnel["ENTERED"] += 1
    if pos is not None:
        c = bars[-1]["c"]
        pos.close(c - pos.side * slip); pos.reason, pos.exit_t = "data_end", bars[-1]["t0"] + TF_MS
        finish(pos)
    return trades


def main():
    global TF_MS
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--warmup-days", type=int, default=130, help="calendar days of history before the window (50 sessions needed)")
    ap.add_argument("--mult", type=float, required=True)
    ap.add_argument("--tick", type=float, required=True)
    ap.add_argument("--equity", type=float, default=25000.0)
    ap.add_argument("--comm", type=float, default=1.0)
    ap.add_argument("--tf", type=int, default=5)
    ap.add_argument("--qty", type=int, default=0, help="fixed contracts (0 = size from the 100,000 notional, as the source does)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    TF_MS = a.tf * 60_000
    P["fixed_qty"] = a.qty
    cost = dict(tick=a.tick, slip_ticks=1, comm_rt=a.comm, mult=a.mult, start_equity=a.equity)
    t0 = time.time()
    with open(a.csv, "rb") as fh:
        fh.seek(0, 2); fh.seek(max(fh.tell() - 4096, 0))
        last_ms = int(fh.read().decode().strip().splitlines()[-1].split(",")[0])
    rep_from = last_ms - a.days * DAY_MS
    m1 = load_window(a.csv, rep_from - a.warmup_days * DAY_MS)
    print(f"loaded {len(m1[0])} 1m bars {iso(m1[0][0])} -> {iso(m1[0][-1])} UTC ({time.time() - t0:.0f}s)", flush=True)
    bars = make_bars(*m1)
    funnel, days_log = collections.Counter(), []
    trades = [t for t in run(bars, m1, P, cost, funnel, days_log) if t["entry_t"] >= rep_from]
    os.makedirs(a.out, exist_ok=True)
    write_trades(os.path.join(a.out, "trades.csv"), trades)
    rl = [r for r in days_log if r[0] >= rep_from]
    out = [f"GapLateStrength5m on {os.path.basename(a.csv)}, last {a.days} days ({iso(rep_from)} -> {iso(m1[0][-1])} UTC), {len(bars)} {a.tf}m bars",
           f"params {P}", f"costs {cost}",
           stats([t["r_net"] for t in trades], "ALL (net)"), stats([t["r_gross"] for t in trades], "ALL (gross)")]
    for rsn in ("stop", "flatten", "data_end"):
        out.append(stats([t["r_net"] for t in trades if t["reason"] == rsn], rsn))
    usd = sum(t["pnl_usd"] for t in trades)
    eq = peak = mdd = 0.0
    for t in trades:
        eq += t["pnl_usd"]; peak = max(peak, eq); mdd = max(mdd, peak - eq)
    out.append(f"net P&L ${usd:+,.0f} on ${a.equity:,.0f} ({usd / a.equity:+.1%}); max drawdown ${mdd:,.0f}; trades {len(trades)} in {a.days} days"
               + (f"; avg stop {sum(t['dist'] for t in trades) / len(trades):.0f} points" if trades else ""))
    out.append("--- trades (entry ET date, entry px, exit reason, P&L $)")
    for t in trades:
        out.append(f"  {iso(t['entry_t'] - 1)[:10]}  in {t['entry']:.2f}  {t['reason']:<8} ${t['pnl_usd']:+,.0f}")
    out.append(f"--- condition funnel, window only ({len(rl)} sessions evaluated)")
    out.append(f"  regime ok {sum(r[1] for r in rl)}, gap ok {sum(r[2] for r in rl)}, strength ok {sum(r[3] for r in rl)}, "
               f"regime+gap {sum(r[1] and r[2] for r in rl)}, all three {sum(r[1] and r[2] and r[3] for r in rl)}")
    with open(os.path.join(a.out, "summary.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n\n" + ASSUMPTIONS)
    print("\n" + "\n".join(out))


if __name__ == "__main__":
    main()
