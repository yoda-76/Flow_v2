"""Split a strategy's trades by market regime (uptrend vs downtrend) -- no look-ahead.

Read-only over the CSV and the trade lists; prints a report. Places no orders.

    python analysis/gb_nq_backtest/regime_split.py --csv analysis/data/NQ_1m_latest.csv \
        --trades S1=reports/s1_sweep/2C_open17_00/trades.csv --trades S3=reports/orb/base/trades.csv

Daily close = close of the last 1m bar before 16:00 CT on each CT date. For a trade entered on CT date D, every
regime is computed from daily closes of dates BEFORE D only:
  sma100  : prior close below its 100-day simple average  -> DOWN, else UP
  ret20   : prior close below the close 20 trading days earlier -> DOWN, else UP
  dd10    : prior close more than 10% below its highest close of the prior 252 trading days -> CORRECTION, else NORMAL
Named sell-off windows (by entry date) are reported separately. The CSV is not roll-adjusted: a quarterly roll moves
the series by a few points, small next to these thresholds.
"""
import argparse, bisect, csv, collections, datetime as d, statistics as st

H = 3600 * 1000


def ct_offsets():
    tr = [(-(1 << 62), -6 * H)]
    for y in range(2017, 2031):
        x = d.date(y, 3, 1); x += d.timedelta((6 - x.weekday()) % 7 + 7)
        s = d.datetime(y, 3, x.day, 8, 0, tzinfo=d.timezone.utc)
        x = d.date(y, 11, 1); x += d.timedelta((6 - x.weekday()) % 7)
        e = d.datetime(y, 11, x.day, 7, 0, tzinfo=d.timezone.utc)
        tr.append((int(s.timestamp() * 1000), -5 * H)); tr.append((int(e.timestamp() * 1000), -6 * H))
    tr.sort()
    return [t for t, _ in tr], [o for _, o in tr]


TT, TO = ct_offsets()

WINDOWS = [  # (label, start, end) -- entry date inclusive
    ("2020 Covid crash", "2020-02-19", "2020-03-23"),
    ("2022 bear market", "2022-01-01", "2022-12-31"),
    ("2023 Aug-Oct pullback", "2023-07-20", "2023-10-27"),
    ("2024 Jul-Aug pullback", "2024-07-11", "2024-08-07"),
    ("2025 tariff sell-off", "2025-02-19", "2025-04-21"),
]


def daily_closes(path):
    last = {}
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.split(",")
            t = int(p[0])
            loc = t + TO[bisect.bisect_right(TT, t) - 1]
            m = (loc % 86400000) // 60000
            if m >= 960:
                continue
            day = d.date(1970, 1, 1) + d.timedelta(days=loc // 86400000)
            prev = last.get(day)
            if prev is None or t > prev[0]:
                last[day] = (t, float(p[4]))
    days = sorted(last)
    return days, [last[x][1] for x in days]


def regimes(days, closes):
    """regime per date D, from closes strictly before D."""
    out = {}
    for i in range(1, len(days)):
        j = i - 1  # last known close
        r = {}
        if j >= 99:
            r["sma100"] = "DOWN" if closes[j] < sum(closes[j - 99:j + 1]) / 100 else "UP"
        if j >= 20:
            r["ret20"] = "DOWN" if closes[j] < closes[j - 20] else "UP"
        if j >= 251:
            hi = max(closes[j - 251:j + 1])
            r["dd10"] = "CORRECTION" if closes[j] < 0.9 * hi else "NORMAL"
        out[days[i]] = r
    return out


def stats(p):
    if not p:
        return "  n=   0"
    w = [x for x in p if x > 0]; l = [x for x in p if x <= 0]
    pf = sum(w) / -sum(l) if l and sum(l) < 0 else float("inf")
    t = st.mean(p) / (st.stdev(p) / len(p) ** 0.5) if len(p) > 2 and st.stdev(p) > 0 else 0
    return "n=%4d win=%5.1f%% avg=$%7.1f PF=%5.2f t=%5.2f net=$%9.0f" % (len(p), 100 * len(w) / len(p), st.mean(p), pf, t, sum(p))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="analysis/data/NQ_1m_latest.csv")
    ap.add_argument("--trades", action="append", required=True, help="LABEL=PATH")
    a = ap.parse_args()
    days, closes = daily_closes(a.csv)
    R = regimes(days, closes)
    # share of days in each regime, for context
    cnt = collections.Counter()
    for x in R.values():
        for k, v in x.items():
            cnt[(k, v)] += 1
    print("Days per regime (all CT dates with a daily close):",
          ", ".join(f"{k}={v}:{n}" for (k, v), n in sorted(cnt.items())))
    for spec in a.trades:
        lab, path = spec.split("=", 1)
        T = list(csv.DictReader(open(path)))
        print(f"\n######## {lab}  ({path})")
        print("  ALL                      " + stats([float(t["pnl_usd"]) for t in T]))
        for key, vals in (("sma100", ("UP", "DOWN")), ("ret20", ("UP", "DOWN")), ("dd10", ("NORMAL", "CORRECTION"))):
            for v in vals:
                sel = [t for t in T if R.get(d.date.fromisoformat(t["date"]), {}).get(key) == v]
                print(f"  {key:6s} {v:11s}        " + stats([float(t["pnl_usd"]) for t in sel]))
                if "side" in T[0]:
                    for s in ("L", "S"):
                        ss = [float(t["pnl_usd"]) for t in sel if t["side"] == s]
                        print(f"           {'':11s} side {s} " + stats(ss))
        print("  named sell-off windows:")
        for wl, ws, we in WINDOWS:
            sel = [t for t in T if ws <= t["date"] <= we]
            line = "    %-24s " % wl + stats([float(t["pnl_usd"]) for t in sel])
            if "side" in T[0] and sel:
                line += "  | L " + "%+.0f" % sum(float(t["pnl_usd"]) for t in sel if t["side"] == "L") \
                        + " S " + "%+.0f" % sum(float(t["pnl_usd"]) for t in sel if t["side"] == "S")
            print(line)
        # NQ change over each window, for context
    print("\nNQ move over each window (daily closes):")
    for wl, ws, we in WINDOWS:
        i0 = bisect.bisect_left(days, d.date.fromisoformat(ws)); i1 = bisect.bisect_right(days, d.date.fromisoformat(we)) - 1
        if 0 < i0 <= i1:
            print("    %-24s %+.1f%%" % (wl, 100 * (closes[i1] / closes[i0 - 1] - 1)))


if __name__ == "__main__":
    main()
