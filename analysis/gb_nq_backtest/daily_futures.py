"""Daily/weekly backtests of two of the four "Strategy 10.x" futures endpoints, adapted from stateless API calls into backtests.

  trend   <- trend_following.py (10.4): signal = sign of the mean of sign(20d), sign(60d), sign(120d) returns.
  pair    <- contrarian.py (10.3): weekly; long the asset with the lowest relative 4-week return, short the highest.
             Adapted to a 2-asset universe (gold vs NQ), top_n = 1, market = the average of the two.

The other two files are NOT backtestable with the data here (see the answer): hedging_with_futures.py is a hedge-ratio
calculator, calendar_spread.py needs near AND far contract prices.

Read-only over the 1m CSVs; writes nothing but stdout and --out. Places no orders.

    python daily_futures.py --days 365 --out reports/daily_futures
"""
import argparse, bisect, csv, datetime as d, math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lucid_replay import session_day, utc

DAY_MS = 86400_000
INSTR = {   # point value, tick, commission per contract round trip (USD)
    "MGC": dict(csv="analysis/data/GC_1m_latest.csv", mult=10.0, tick=0.1, comm=1.0),
    "GC": dict(csv="analysis/data/GC_1m_latest.csv", mult=100.0, tick=0.1, comm=4.60),
    "MNQ": dict(csv="analysis/data/NQ_1m_latest.csv", mult=2.0, tick=0.25, comm=1.0),
    "NQ": dict(csv="analysis/data/NQ_1m_latest.csv", mult=20.0, tick=0.25, comm=4.60),
}
_cache = {}


def daily_closes(path, t_from_ms):
    """{session_date: last 1m close} on CME trading days (roll at 17:00 ET); sessions with < 60 bars dropped (holidays)."""
    key = (path, t_from_ms)
    if key in _cache:
        return _cache[key]
    last, cnt, close = {}, {}, {}
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.split(",")
            t = int(p[0])
            if t < t_from_ms:
                continue
            sd = session_day(t)
            cnt[sd] = cnt.get(sd, 0) + 1
            close[sd] = float(p[4])
    days = sorted(k for k in close if cnt[k] >= 60)
    _cache[key] = ([k for k in days], [close[k] for k in days])
    return _cache[key]


def stat_block(label, pnl_by_day, equity0, n_changes, extra="", ppy=252):
    eq, peak, mdd, vals = equity0, equity0, 0.0, list(pnl_by_day)
    for v in vals:
        eq += v; peak = max(peak, eq); mdd = max(mdd, peak - eq)
    net = sum(vals)
    mean = net / len(vals) if vals else 0.0
    sd = (sum((v - mean) ** 2 for v in vals) / max(len(vals) - 1, 1)) ** 0.5 if vals else 0.0
    sharpe = mean / sd * math.sqrt(ppy) if sd > 0 else 0.0
    return (f"{label:<28} net ${net:+10,.0f} ({net / equity0:+6.1%} of ${equity0:,.0f})  maxDD ${mdd:,.0f} ({mdd / equity0:.1%})  "
            f"Sharpe {sharpe:+.2f}  position changes {n_changes}{extra}")


# ---- trend following -----------------------------------------------------------------------------------------------
def trend(days, closes, rep_from_date, inst, equity0, lookbacks=(20, 60, 120)):
    mult, slip, comm = inst["mult"], inst["tick"], inst["comm"]
    pos, pnl, changes, flips, hold = 0, [], 0, 0, []
    long_d = short_d = flat_d = 0
    for i in range(max(lookbacks), len(closes) - 1):
        score = sum(((closes[i] / closes[i - lb] - 1) > 0) - ((closes[i] / closes[i - lb] - 1) < 0) for lb in lookbacks) / len(lookbacks)
        target = (score > 0) - (score < 0)              # int(np.sign(momentum_score)), 1 contract
        day = days[i + 1]
        cost = 0.0
        if target != pos:                              # trade at the close of the signal day, 1 tick slippage + half the round-trip commission per contract
            n = abs(target - pos)
            cost = n * (slip * mult + comm / 2.0)
            if day >= rep_from_date:
                changes += 1
            pos = target
        day_pnl = pos * (closes[i + 1] - closes[i]) * mult - cost
        if day >= rep_from_date:
            pnl.append(day_pnl)
            long_d += pos > 0; short_d += pos < 0; flat_d += pos == 0
    return pnl, changes, (long_d, short_d, flat_d)


# ---- contrarian (pair) -----------------------------------------------------------------------------------------------
def weekly(days, closes):
    """Last session close of every ISO week -> (week_end_dates, closes)."""
    wd, wc, last_week = [], [], None
    for dd, c in zip(days, closes):
        wk = dd.isocalendar()[:2]
        if wk != last_week:
            wd.append(dd); wc.append(c); last_week = wk
        else:
            wd[-1], wc[-1] = dd, c
    return wd, wc


def pair(sym_a, sym_b, t_from_ms, rep_from_date, equity0, lookback=4):
    ia, ib = INSTR[sym_a], INSTR[sym_b]
    da, ca = daily_closes(ia["csv"], t_from_ms)
    db, cb = daily_closes(ib["csv"], t_from_ms)
    common = sorted(set(da) & set(db))
    ma, mb = dict(zip(da, ca)), dict(zip(db, cb))
    wd, wa = weekly(common, [ma[x] for x in common])
    _, wb = weekly(common, [mb[x] for x in common])
    pos_a = pos_b = 0
    pnl, changes, picks = [], 0, {sym_a: 0, sym_b: 0}
    for i in range(lookback, len(wd) - 1):
        ra, rb = wa[i] / wa[i - lookback] - 1, wb[i] / wb[i - lookback] - 1
        mkt = (ra + rb) / 2.0
        rel_a, rel_b = ra - mkt, rb - mkt
        # sorted ascending by relative return: the lowest is bought, the highest sold (top_n = 1)
        tgt_a, tgt_b = (1, -1) if rel_a < rel_b else (-1, 1)
        if rel_a == rel_b:
            tgt_a = tgt_b = 0
        cost = 0.0
        for pos_, tgt_, ins, in ((pos_a, tgt_a, ia), (pos_b, tgt_b, ib)):
            cost += abs(tgt_ - pos_) * (ins["tick"] * ins["mult"] + ins["comm"] / 2.0)
        if (tgt_a, tgt_b) != (pos_a, pos_b) and wd[i + 1] >= rep_from_date:
            changes += 1
        pos_a, pos_b = tgt_a, tgt_b
        p = pos_a * (wa[i + 1] - wa[i]) * ia["mult"] + pos_b * (wb[i + 1] - wb[i]) * ib["mult"] - cost
        if wd[i + 1] >= rep_from_date:
            pnl.append(p)
            picks[sym_a if pos_a > 0 else sym_b] += 1
    return pnl, changes, picks


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--equity", type=float, default=25000.0)
    ap.add_argument("--warmup-days", type=int, default=420, help="calendar days of history before the window (120 sessions + 4 weeks)")
    a = ap.parse_args()
    last_ms = 0
    for k in ("GC", "NQ"):
        with open(INSTR[k]["csv"], "rb") as fh:
            fh.seek(0, 2); fh.seek(max(fh.tell() - 4096, 0))
            last_ms = max(last_ms, int(fh.read().decode().strip().splitlines()[-1].split(",")[0]))
    rep_from_ms = last_ms - a.days * DAY_MS
    rep_from = session_day(rep_from_ms)
    t_from = rep_from_ms - a.warmup_days * DAY_MS
    print(f"window {rep_from} -> {session_day(last_ms)}  ({a.days} days), 1 contract per leg, slippage 1 tick/side, "
          f"MGC/MNQ comm $1 RT, GC/NQ $4.60 RT, equity ${a.equity:,.0f}\n")

    print("== TREND FOLLOWING (trend_following.py): sign(20d)+sign(60d)+sign(120d) / 3, trade the sign, daily, 1 contract")
    for sym in ("MGC", "GC"):
        days, closes = daily_closes(INSTR[sym]["csv"], t_from)
        pnl, ch, (lg, sh, fl) = trend(days, closes, rep_from, INSTR[sym], a.equity)
        print(stat_block(sym, pnl, a.equity, ch, f"   days long/short/flat {lg}/{sh}/{fl}"))
    days, closes = daily_closes(INSTR["GC"]["csv"], t_from)
    bh = [(closes[i + 1] - closes[i]) * INSTR["MGC"]["mult"] for i in range(len(closes) - 1) if days[i + 1] >= rep_from]
    print(stat_block("buy & hold 1 MGC (reference)", bh, a.equity, 1))

    print("\n== CONTRARIAN (contrarian.py), gold vs NQ, weekly, 4-week relative return, top_n = 1, market = average of the two")
    for ga, gb in (("MGC", "MNQ"), ("GC", "NQ")):
        pnl, ch, picks = pair(ga, gb, t_from, rep_from, a.equity)
        print(stat_block(f"{ga} vs {gb}", pnl, a.equity, ch, f"   weeks long {ga}/long {gb}: {picks[ga]}/{picks[gb]}", ppy=52))


if __name__ == "__main__":
    main()
