"""Replay a va_break_momentum trades.csv through LucidPro evaluation rules (prop-firm pass/breach chain).

Read-only over the trades CSV and the 1m CSV; places no orders.

    python lucid_replay.py --trades reports/vab_m50_1c/v2/trades.csv --plan 50k [--csv analysis/data/NQ_1m_latest.csv]

Rules (third-party sources, sites disagree on some points -- confirm with Lucid before relying on them):
  25k: target 1,250   max loss limit 1,000 EOD trailing, locks at 25,100 once balance >= 26,100   max 20 micros
  50k: target 3,000   max loss limit 2,000 EOD trailing, lock level NOT stated anywhere found --
       assumed by scaling the 25k pattern (balance >= 52,100 -> limit locks at 50,100)   max 40 micros
  Daily loss limit: none on 25k; 50k lists 1,200 fixed (locks trading for the day, does not fail the account).
       Not modelled -- the worst day in the v2 runs was about -630, well inside it.
  No consistency rule and no minimum trading days in the evaluation.
How it is replayed: trades in order; after each pass or breach a NEW evaluation starts with the next trade.
  The trailing limit updates only at the CME trading-day close (17:00 ET). A breach is tested on the worst open
  P&L inside each trade (from the 1m highs/lows), not just the closed result. A pass is tested at trade close.
  Evaluation fees and funded-phase payout rules are not modelled.
"""
import argparse, csv, bisect, datetime as d

PLANS = {   # start, target, max-loss allowance, lock balance, locked limit, max micro contracts
    "25k": dict(start=25000.0, target=1250.0, allow=1000.0, lock_bal=26100.0, lock_mll=25100.0, max_micros=20),
    "50k": dict(start=50000.0, target=3000.0, allow=2000.0, lock_bal=52100.0, lock_mll=50100.0, max_micros=40),
    "100k": dict(start=100000.0, target=6000.0, allow=3000.0, lock_bal=103100.0, lock_mll=100100.0, max_micros=60),   # buffer 103,100 stated; DLL 1,800 soft (not modelled)
}


def utc(ms):
    return d.datetime.fromtimestamp(ms / 1000, d.timezone.utc).replace(tzinfo=None)


def et_offset(u):                       # US DST: second Sunday of March 02:00 ET .. first Sunday of November 02:00 ET
    def nth(m, wd, n):
        x = d.datetime(u.year, m, 1)
        while x.weekday() != wd:
            x += d.timedelta(1)
        return x + d.timedelta(7 * (n - 1))
    return -4 if nth(3, 6, 2) + d.timedelta(hours=7) <= u < nth(11, 6, 1) + d.timedelta(hours=6) else -5


def session_day(ms):                    # CME trading day rolls at 17:00 ET
    u = utc(ms)
    return (u + d.timedelta(hours=et_offset(u) + 7)).date()


class Account:
    def __init__(self, plan):
        self.p = plan
        self.bal, self.mll = plan["start"], plan["start"] - plan["allow"]
        self.peak_eod, self.locked, self.day, self.n = plan["start"], False, None, 0

    def eod(self):
        self.peak_eod = max(self.peak_eod, self.bal)
        if not self.locked:
            if self.bal >= self.p["lock_bal"]:
                self.locked, self.mll = True, self.p["lock_mll"]
            else:
                self.mll = max(self.mll, self.peak_eod - self.p["allow"])


def load_1m(path, t_lo, t_hi):
    T, H, L = [], [], []
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.split(",")
            t = int(p[0])
            if t_lo <= t <= t_hi:
                T.append(t); H.append(float(p[2])); L.append(float(p[3]))
    return T, H, L


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--trades", required=True, help="trades.csv written by va_break_momentum.py")
    ap.add_argument("--plan", choices=sorted(PLANS), required=True)
    ap.add_argument("--csv", default="analysis/data/NQ_1m_latest.csv", help="1m bars (for the intratrade worst loss)")
    ap.add_argument("--mult", type=float, default=2.0, help="point value used in the backtest: 2 = MNQ")
    ap.add_argument("--exclude-date", action="append", default=[], metavar="MM-DD",
                    help="drop trades entered on this ET date (e.g. 12-16 to test a contract-roll artifact)")
    a = ap.parse_args()
    plan = PLANS[a.plan]

    trades = list(csv.DictReader(open(a.trades)))
    if a.exclude_date:
        keep = lambda t: (utc(int(t["entry_t"])) + d.timedelta(hours=et_offset(utc(int(t["entry_t"]))))).strftime("%m-%d") not in a.exclude_date
        trades = [t for t in trades if keep(t)]
    T, H, L = load_1m(a.csv, int(trades[0]["entry_t"]) - 900000, max(int(t["exit_t"]) for t in trades) + 900000)

    def worst_open(tr):                 # worst open P&L in USD during the trade
        i0, i1 = bisect.bisect_left(T, int(tr["entry_t"])), bisect.bisect_right(T, int(tr["exit_t"]))
        if i0 >= i1:
            return 0.0
        side, ep, q = int(tr["side"]), float(tr["entry"]), int(tr["qty"])
        ext = min(L[i0:i1]) if side == 1 else max(H[i0:i1])
        return min(0.0, side * (ext - ep) * a.mult * q)

    def day_of(i): return utc(int(trades[i]["entry_t"])).strftime("%Y-%m-%d")

    out, acc, start_i = [], Account(plan), 0
    for i, tr in enumerate(trades):
        day = session_day(int(tr["exit_t"]))
        if acc.day is not None and day != acc.day:
            acc.eod()
        low = acc.bal + worst_open(tr)
        if low <= acc.mll:                                  # breached while the trade was open
            out.append(("FAIL", start_i, i, low, acc.n)); acc, start_i = Account(plan), i + 1
            continue
        acc.bal += float(tr["pnl_usd"]); acc.n += 1; acc.day = day
        if acc.bal <= acc.mll:
            out.append(("FAIL", start_i, i, acc.bal, acc.n)); acc, start_i = Account(plan), i + 1
        elif acc.bal - plan["start"] >= plan["target"]:
            out.append(("PASS", start_i, i, acc.bal, acc.n)); acc, start_i = Account(plan), i + 1
    if acc.n:
        out.append(("OPEN", start_i, len(trades) - 1, acc.bal, acc.n))

    print(f"LucidPro {a.plan}: {len(trades)} trades, max qty {max(int(t['qty']) for t in trades)} (cap {plan['max_micros']} micros)")
    print("\nAttempt chain (a new evaluation starts after every pass or breach):")
    for k, (st, i0, i1, bal, n) in enumerate(out, 1):
        print(f" #{k} {st:<4} {day_of(i0)} -> {day_of(i1)}  trades {n:<4} end balance ${bal:,.0f}  (P&L ${bal - plan['start']:+,.0f})")
    print(f"\npasses {sum(r[0] == 'PASS' for r in out)}, breaches {sum(r[0] == 'FAIL' for r in out)}, open {sum(r[0] == 'OPEN' for r in out)}")


if __name__ == "__main__":
    main()
