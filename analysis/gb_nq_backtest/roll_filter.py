"""Find the quarterly contract-roll jumps in a continuous 1m futures CSV and drop the trades taken on those days.

Roll detection: for each quarterly expiry (third Friday of Mar/Jun/Sep/Dec) take the largest close->next-open jump across the daily
1-hour break (55-75 minutes; weekend gaps are excluded because they hold real moves), within +-8 days of it. The CSV is NOT back-adjusted, so that jump is the gap between the expiring and the
next contract (about 1% of price for equity index futures). Nothing is written to the data; this only labels days.

    python roll_filter.py --csv analysis/data/MES_1m_latest.csv --trades reports/avwap_mes_user/trades.csv --equity 10000
"""
import argparse, bisect, calendar, csv, datetime as d, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lucid_replay import utc, et_offset

UTC = d.timezone.utc


def et_date(ms):
    u = utc(ms)
    return (u + d.timedelta(hours=et_offset(u))).date()


def third_friday(y, m):
    return d.datetime(y, m, [w[4] for w in calendar.monthcalendar(y, m) if w[4]][2], tzinfo=UTC)


def find_rolls(path, min_jump_pts=15.0, window_days=8):
    T, O, C = [], [], []
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.split(",")
            T.append(int(p[0])); O.append(float(p[1])); C.append(float(p[4]))
    first, last = utc(T[0]), utc(T[-1])
    rolls = []
    for y in range(first.year, last.year + 1):
        for m in (3, 6, 9, 12):
            f = third_friday(y, m)
            lo, hi = int((f - d.timedelta(days=window_days)).timestamp() * 1000), int((f + d.timedelta(days=window_days)).timestamp() * 1000)
            if lo < T[0] or hi > T[-1]:
                continue
            i0, i1 = bisect.bisect_left(T, lo), bisect.bisect_right(T, hi)
            best = None
            for i in range(max(i0, 1), i1):
                if 55 * 60000 <= T[i] - T[i - 1] <= 75 * 60000:      # the daily 1-hour break only: weekend gaps hold real moves too
                    j = abs(O[i] - C[i - 1])
                    if best is None or j > best[0]:
                        best = (j, T[i - 1], T[i], C[i - 1], O[i])
            if best and best[0] >= min_jump_pts:
                rolls.append(dict(expiry=f.date(), jump=best[0], signed=best[4] - best[3], t_old=best[1], t_new=best[2],
                                  date_old=et_date(best[1]), date_new=et_date(best[2])))
    return rolls


def summarize(label, tr, equity):
    if not tr:
        return f"{label:<46} no trades"
    pnl = [t["pnl_usd"] for t in tr]
    gp, gl = sum(p for p in pnl if p > 0), -sum(p for p in pnl if p <= 0)
    eq = peak = mdd = 0.0
    for p in pnl:
        eq += p; peak = max(peak, eq); mdd = max(mdd, peak - eq)
    return (f"{label:<46} trades {len(tr):>4}  win {sum(p > 0 for p in pnl) / len(pnl):4.0%}  PF {gp / gl if gl else float('inf'):5.2f}  "
            f"net ${sum(pnl):+9,.0f} ({sum(pnl) / equity:+7.1%})  max DD ${mdd:,.0f} ({mdd / equity:.1%})")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", required=True, help="the 1m CSV the trades were produced from (used to find the rolls)")
    ap.add_argument("--trades", required=True, help="trades.csv from one of the backtest scripts")
    ap.add_argument("--equity", type=float, required=True, help="starting equity used for the percent columns")
    ap.add_argument("--show-rolls", action="store_true")
    ap.add_argument("--write-filtered", default=None, help="write the trades left after removing roll-day entries to this CSV")
    a = ap.parse_args()
    rolls = find_rolls(a.csv)
    if a.show_rolls or True:
        print("detected rolls (largest jump across the daily 1-hour break within +-8 days of expiry):")
        for r in rolls:
            print(f"  expiry {r['expiry']}: {r['signed']:+7.2f} pts  last old bar {utc(r['t_old']):%Y-%m-%d %H:%M} UTC -> first new bar {utc(r['t_new']):%Y-%m-%d %H:%M} UTC  "
                  f"(ET dates {r['date_old']} / {r['date_new']})")
    roll_dates = {r["date_old"] for r in rolls} | {r["date_new"] for r in rolls}
    spans = [(r["t_old"], r["t_new"]) for r in rolls]
    trades = list(csv.DictReader(open(a.trades)))
    for t in trades:
        t["entry_t"], t["exit_t"], t["pnl_usd"] = int(t["entry_t"]), int(t["exit_t"]), float(t["pnl_usd"])
    on_roll = [t for t in trades if et_date(t["entry_t"] - 1) in roll_dates]
    across = [t for t in trades if any(t["entry_t"] <= old + 60000 and t["exit_t"] >= new for old, new in spans)]   # entered on the old contract, still open on the new one
    across_only = [t for t in across if t not in on_roll]
    keep_a = [t for t in trades if t not in on_roll]
    keep_b = [t for t in trades if t not in on_roll and t not in across_only]
    print(f"\ntrades {len(trades)}: entered on a roll day {len(on_roll)} (P&L ${sum(t['pnl_usd'] for t in on_roll):+,.0f}); "
          f"entered earlier but open across the roll jump {len(across_only)} (P&L ${sum(t['pnl_usd'] for t in across_only):+,.0f})\n")
    if a.write_filtered:
        cols = list(csv.DictReader(open(a.trades)).fieldnames)
        with open(a.write_filtered, "w", newline="") as fh:
            w = csv.writer(fh); w.writerow(cols)
            for t in keep_a:
                w.writerow([t[c] for c in cols])
        print(f"wrote {len(keep_a)} trades to {a.write_filtered}")
    print(summarize("ALL trades", trades, a.equity))
    print(summarize("minus trades entered on roll days", keep_a, a.equity))
    print(summarize("minus roll-day entries AND trades open across", keep_b, a.equity))


if __name__ == "__main__":
    main()
