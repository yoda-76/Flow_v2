"""What explains the anchored-VWAP strategy's results? Slice the trades (MES, 3m, EMA 11, rel ATR 1.1, no stop, 3 contracts)
by market regime, volatility, time of day, weekday, gap, order within the day, and more. Read-only; places no orders.

    python factor_analysis.py
"""
import bisect, datetime as d, os, statistics, sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import anchored_vwap as av
from va_break_momentum import load_window, EtClock

CSV = "analysis/data/MES_1m_latest.csv"
COST = dict(tick=0.25, slip_ticks=1, comm_rt=1.0, mult=5.0, start_equity=25000.0)
ms = lambda s: int(d.datetime.fromisoformat(s).replace(tzinfo=d.timezone.utc).timestamp() * 1000)
FROM = ms("2022-01-15")


def pf(pnl):
    gp, gl = sum(p for p in pnl if p > 0), -sum(p for p in pnl if p <= 0)
    return gp / gl if gl else float("inf")


def row(label, trs):
    pnl = [t["pnl_usd"] for t in trs]
    if not pnl:
        return f"   {label:<38} no trades"
    return (f"   {label:<38} n={len(pnl):>4}  win {sum(p > 0 for p in pnl) / len(pnl):4.0%}  PF {pf(pnl):5.2f}  "
            f"avg ${sum(pnl) / len(pnl):+7.1f}  net ${sum(pnl):+8,.0f}")


def table(title, trades, key, order=None):
    g = defaultdict(list)
    for t in trades:
        g[key(t)].append(t)
    print(f"\n-- {title}")
    for k in (order or sorted(g)):
        if k in g:
            print(row(str(k), g[k]))


def bucket3(vals, v):
    lo, hi = vals[len(vals) // 3], vals[2 * len(vals) // 3]
    return "low" if v <= lo else ("high" if v > hi else "mid")


if __name__ == "__main__":
    m1 = load_window(CSV, ms("2021-08-18"))
    clock = EtClock()
    # --- daily RTH bars (open at 09:30, close at the last bar before 16:00)
    day = {}
    for i, t in enumerate(m1[0]):
        hhmm, dn = clock.hhmm_date(t)
        if 930 <= hhmm < 1600:
            x = day.get(dn)
            if x is None:
                day[dn] = [m1[1][i], m1[2][i], m1[3][i], m1[4][i]]
            else:
                x[1] = max(x[1], m1[2][i]); x[2] = min(x[2], m1[3][i]); x[3] = m1[4][i]
    dates = sorted(k for k in day if True)
    dclose = [day[k][3] for k in dates]
    didx = {k: i for i, k in enumerate(dates)}
    # --- strategy run, same settings as the original
    av.TF_MS = 3 * 60_000
    bars = av.make_bars(*m1)
    p = dict(av.P, contracts=3, ema_length=11, use_atr_stop=False, max_relative_atr=1.1)
    trades = [t for t in av.run(bars, m1, p, COST, Counter()) if t["entry_t"] >= FROM]
    for t in trades:
        hh, dn = clock.hhmm_date(t["entry_t"] - 1)
        t["date"] = dn
        t["hold_h"] = (t["exit_t"] - t["entry_t"]) / 3600000.0
        i = didx.get(dn)
        t["ok"] = False
        if i is not None and i >= 101:
            pc = dclose[i - 1]
            sma100 = sum(dclose[i - 100:i]) / 100.0
            t["above100"] = pc > sma100
            t["ret60"] = pc / dclose[i - 61] - 1
            t["ret20"] = pc / dclose[i - 21] - 1
            rets = [dclose[j] / dclose[j - 1] - 1 for j in range(i - 20, i)]
            t["rv20"] = statistics.pstdev(rets) * (252 ** 0.5)
            t["gap"] = day[dn][0] / pc - 1
            t["prev_ret"] = pc / dclose[i - 2] - 1
            t["prev_range"] = (day[dates[i - 1]][1] - day[dates[i - 1]][2]) / pc
            t["above_open"] = t["signal"] > day[dn][0]
            t["ok"] = True
        t["dow"] = (d.date(1970, 1, 1) + d.timedelta(days=dn)).strftime("%a")      # EtClock returns an epoch day number
        t["year"] = d.datetime.fromtimestamp(t["entry_t"] / 1000, d.timezone.utc).year
        t["month"] = d.datetime.fromtimestamp(t["entry_t"] / 1000, d.timezone.utc).month
    trades = [t for t in trades if t["ok"]]
    byday = defaultdict(list)
    for t in sorted(trades, key=lambda x: x["entry_t"]):
        byday[t["date"]].append(t)
    for ts in byday.values():
        for k, t in enumerate(ts):
            t["order"] = min(k + 1, 3)
            t["prev_lost"] = k > 0 and ts[k - 1]["pnl_usd"] <= 0

    print(f"{len(trades)} trades from 2022-01-15 to the end of the data; total ${sum(t['pnl_usd'] for t in trades):+,.0f}")
    print(row("ALL", trades))
    table("by year", trades, lambda t: t["year"])

    print("\n===== 1. MARKET REGIME (computed from daily closes BEFORE the trade day)")
    table("S&P (MES) above or below its 100-day average", trades, lambda t: "above 100d avg (uptrend)" if t["above100"] else "below 100d avg (downtrend)")
    table("60-day return", trades, lambda t: "60d return > 0" if t["ret60"] > 0 else "60d return <= 0")
    table("20-day return", trades, lambda t: "20d return > 0" if t["ret20"] > 0 else "20d return <= 0")
    table("side x 100d regime", trades, lambda t: ("LONG " if t["side"] > 0 else "SHORT") + (" in uptrend" if t["above100"] else " in downtrend"),
          ["LONG  in uptrend", "SHORT in uptrend", "LONG  in downtrend", "SHORT in downtrend"])
    table("100d regime by year (does the regime effect hold every year?)", trades,
          lambda t: f"{t['year']} {'up  ' if t['above100'] else 'down'}")

    print("\n===== 2. VOLATILITY")
    atrs = sorted(t["atr"] for t in trades)
    table("3-minute ATR at entry (points)", trades, lambda t: bucket3(atrs, t["atr"]), ["low", "mid", "high"])
    rvs = sorted(t["rv20"] for t in trades)
    table("20-day realised volatility (annualised)", trades, lambda t: bucket3(rvs, t["rv20"]), ["low", "mid", "high"])
    prs = sorted(t["prev_range"] for t in trades)
    table("previous day's range", trades, lambda t: bucket3(prs, t["prev_range"]), ["low", "mid", "high"])

    print("\n===== 3. TIME")
    table("entry hour (ET)", trades, lambda t: f"{t['hhmm'] // 100:02d}:00")
    table("weekday of entry", trades, lambda t: t["dow"], ["Mon", "Tue", "Wed", "Thu", "Fri"])
    table("month", trades, lambda t: f"{t['month']:02d}")

    print("\n===== 4. GAP AND PRIOR DAY")
    table("opening gap vs yesterday's close", trades, lambda t: "gap down > 0.3%" if t["gap"] < -0.003 else ("gap up > 0.3%" if t["gap"] > 0.003 else "flat (+-0.3%)"),
          ["gap down > 0.3%", "flat (+-0.3%)", "gap up > 0.3%"])
    table("yesterday up or down", trades, lambda t: "yesterday up" if t["prev_ret"] > 0 else "yesterday down")
    table("trade direction vs yesterday", trades, lambda t: "with yesterday" if (t["prev_ret"] > 0) == (t["side"] > 0) else "against yesterday")
    table("entry price vs today's open x side", trades,
          lambda t: ("LONG " if t["side"] > 0 else "SHORT") + (" above open" if t["above_open"] else " below open"),
          ["LONG  above open", "LONG  below open", "SHORT above open", "SHORT below open"])

    print("\n===== 5. HOW THE TRADE IS TAKEN")
    table("which trade of the day", trades, lambda t: t["order"], [1, 2, 3])
    table("after a loss earlier the same day", trades, lambda t: "previous trade today lost" if t["prev_lost"] else "first trade or previous won")
    table("how long held", trades, lambda t: "<1h" if t["hold_h"] < 1 else ("1-10h same day" if t["hold_h"] < 10 else "over 10h"), ["<1h", "1-10h same day", "over 10h"])
    table("holding over a weekend", trades, lambda t: "spans a weekend" if (t["hold_h"] > 24 and d.datetime.fromtimestamp(t["entry_t"] / 1000, d.timezone.utc).weekday() >= 3
                                                                          and d.datetime.fromtimestamp(t["exit_t"] / 1000, d.timezone.utc).weekday() in (0, 1)) else "no weekend")
    table("short vs long", trades, lambda t: "long" if t["side"] > 0 else "short")


    print("\n===== 6. CONSISTENCY: does the 'good' bucket beat the other bucket in each year? (avg $ per trade; count of years where it does)")
    def consistency(title, good, label_good, label_bad):
        yrs = sorted({t["year"] for t in trades})
        wins, cells = 0, []
        for y in yrs:
            g = [t["pnl_usd"] for t in trades if t["year"] == y and good(t)]
            b = [t["pnl_usd"] for t in trades if t["year"] == y and not good(t)]
            if len(g) >= 8 and len(b) >= 8:
                gm, bm = sum(g) / len(g), sum(b) / len(b)
                wins += gm > bm
                cells.append(f"{y}: {gm:+6.0f} vs {bm:+6.0f} (n={len(g)}/{len(b)})")
            else:
                cells.append(f"{y}: too few (n={len(g)}/{len(b)})")
        usable = sum(1 for c in cells if "too few" not in c)
        print(f"\n   {title}  [{label_good} vs {label_bad}]  -> better in {wins} of {usable} usable years")
        for c in cells:
            print("       " + c)
    consistency("uptrend (above 100-day average)", lambda t: t["above100"], "uptrend", "downtrend")
    consistency("yesterday up", lambda t: t["prev_ret"] > 0, "yesterday up", "yesterday down")
    consistency("gap up > 0.3%", lambda t: t["gap"] > 0.003, "gap up", "otherwise")
    consistency("entry in the first half hour (09:30-09:59)", lambda t: t["hhmm"] // 100 == 9, "09:30 hour", "later")
    consistency("entry 14:00-15:59", lambda t: 1400 <= t["hhmm"] < 1600, "14:00-15:59", "earlier")
    consistency("trade held over a weekend", lambda t: t["hold_h"] > 24 and d.datetime.fromtimestamp(t["entry_t"] / 1000, d.timezone.utc).weekday() >= 3
                and d.datetime.fromtimestamp(t["exit_t"] / 1000, d.timezone.utc).weekday() in (0, 1), "weekend hold", "no weekend")
    consistency("previous trade today lost", lambda t: t["prev_lost"], "after a loss today", "otherwise")
    consistency("previous day had a high range", lambda t: t["prev_range"] > prs[2 * len(prs) // 3], "high prev range", "otherwise")
    consistency("held under 1 hour", lambda t: t["hold_h"] < 1, "<1h", "longer")

    print("")
    print("===== 7. FOLLOW-UPS")
    consistency("Friday entries", lambda t: t["dow"] == "Fri", "Friday", "Mon-Thu")
    consistency("Wednesday entries", lambda t: t["dow"] == "Wed", "Wednesday", "other days")
    ex = [t for t in trades if t["year"] != 2022]
    print("")
    print("   Uptrend vs downtrend with 2022 removed (2023 -> 2026 only):")
    table("   100d regime, 2023+ only", ex, lambda t: "above 100d avg (uptrend)" if t["above100"] else "below 100d avg (downtrend)")
    print("")
    print("   2022 alone:")
    table("   2022 only", [t for t in trades if t["year"] == 2022], lambda t: "above 100d avg (uptrend)" if t["above100"] else "below 100d avg (downtrend)")
    ex_gap = [t for t in ex if t["gap"] > 0.003]
    print(row("   2023+: gap up > 0.3%", ex_gap)); print(row("   2023+: everything else", [t for t in ex if t["gap"] <= 0.003]))
    print(row("   2023+: 14:00-15:59 entries", [t for t in ex if 1400 <= t["hhmm"] < 1600])); print(row("   2023+: entries before 14:00", [t for t in ex if t["hhmm"] < 1400]))
    print(row("   2023+: held < 1h", [t for t in ex if t["hold_h"] < 1])); print(row("   2023+: held >= 1h", [t for t in ex if t["hold_h"] >= 1]))
    print("")
    print("   2022 trade profile: why did 2022 lose? (hold length / hour / side)")
    y22 = [t for t in trades if t["year"] == 2022]
    table("   2022 by side", y22, lambda t: "long" if t["side"] > 0 else "short")
    table("   2022 by hold", y22, lambda t: "<1h" if t["hold_h"] < 1 else ("1-10h" if t["hold_h"] < 10 else "over 10h"), ["<1h", "1-10h", "over 10h"])
    table("   2022 by entry half", y22, lambda t: "before 14:00" if t["hhmm"] < 1400 else "14:00 or later")
