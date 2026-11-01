"""One-account-at-a-time LucidPro 25K lifecycle: evaluation -> funded -> payouts, over a window of va_break_momentum trades.

Read-only over the trades CSV and the 1m CSV; places no orders.

    python prop_sim.py --trades reports/vab_m25_1c_all/v2/trades.csv [--days 365] [--fee 0] [--rolling]

Lifecycle (the user's spec, 2026-10-31):
  - Buy ONE evaluation account at the window start. Trade it until it passes (+1,250) or breaches (-> buy another).
  - On a pass the account is reset to 25,000 and becomes FUNDED. While funded no new evaluation is bought.
  - Funded: withdraw 1,000 whenever Lucid's payout conditions allow it, and keep trading the same account.
  - Only when the funded account breaches is another evaluation bought.
Lucid LucidPro 25K rules used (third-party sources, confirm with Lucid): max loss limit 1,000 EOD trailing from 25,000
(starts 24,000; locks at 25,100 once balance >= 26,100), breach tested intratrade on worst open P&L, no daily loss limit
modelled (sources conflict: none vs optional 600), max 20 micros.
Payout conditions used: balance must stay >= 26,100 (buffer) AFTER the withdrawal -> balance >= 27,100 for a 1,000 request;
  cycle profit (since funding / last payout) >= 250; largest single day <= 40% of cycle profit; 90/10 split.
  Requests are evaluated at each trading-day close. The simple rule "withdraw 1,000 when profit reaches 1,000" cannot be
  used as stated: it would leave the balance at 25,000, under the 26,100 buffer (and at or below the trailing limit).
NOT modelled: evaluation/reset fees (unknown -- use --fee), taxes, payout processing charges, rule changes over time.
"""
import argparse, bisect, csv, datetime as d, os, statistics, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lucid_replay import PLANS, Account, load_1m, session_day, utc

PLAN = PLANS["25k"]
BUFFER, REQUEST, CYCLE_GOAL, CONSISTENCY, SPLIT = 26100.0, 1000.0, 250.0, 0.40, 0.90
CYCLE_GOALS = {"25k": 250.0, "100k": 750.0}      # cycle profit goal per plan (50k not sourced -> pass --cycle-goal)
DEFAULT_REQUEST = {"25k": 1000.0, "100k": 2500.0}   # first-payout caps: 25k 1,000; 100k 2,500
# User-defined "doubling" payout schedule (not a Lucid rule): the next payout is DBL_START, doubling after each payout while the
# previous one is below DBL_CAP, then +DBL_STEP each time (100, 200, 400, 800, 1600, 2100, 2600, ...). A payout is taken when the
# funded account's profit (balance - plan start, already net of earlier payouts) reaches TWICE the next payout, i.e. half of it is paid
# out and half stays: 200 -> pays 100 -> 100 left; 400 -> 200 -> 200; 800 -> 400 -> 400; 1600 -> 800; 3200 -> 1600; 4200 -> 2100.
PAYOUT_MODE, DBL_START, DBL_CAP, DBL_STEP = "lucid", 100.0, 1000.0, 500.0


def simulate(trades, worst_open, payouts_on=True):
    """trades: list of csv rows (chronological). Returns a result dict and an event log."""
    ev, bought, passes, eval_fails, funded_breaches, payouts = [], 1, 0, 0, 0, []
    mode, acct = "eval", Account(PLAN)
    runs, cur = [], None          # funded accounts: dict(start, end, peak, end_bal, status)
    cyc_days, cyc_pnl_today = {}, 0.0
    nxt = DBL_START

    def day_close(day_just_ended):                      # trailing limit updates, then a payout may be requested
        nonlocal cyc_pnl_today, nxt
        acct.eod()
        if mode == "funded":
            cur["peak"] = max(cur["peak"], acct.bal)
            cyc_days[day_just_ended] = cyc_days.get(day_just_ended, 0.0) + cyc_pnl_today
            cyc_pnl_today = 0.0
            cycle = sum(cyc_days.values())
            best = max(cyc_days.values(), default=0.0)
            if PAYOUT_MODE == "doubling":
                profit = acct.bal - PLAN["start"]
                if payouts_on and profit >= 2.0 * nxt:
                    acct.bal -= nxt
                    payouts.append((day_just_ended, nxt))
                    ev.append((day_just_ended, f"PAYOUT {nxt:,.0f}  (profit was ${profit:,.0f}, ${profit - nxt:,.0f} left; balance ${acct.bal:,.0f})"))
                    nxt = nxt * 2.0 if nxt < DBL_CAP else nxt + DBL_STEP
            elif (payouts_on and acct.bal - REQUEST >= BUFFER and cycle >= CYCLE_GOAL and best <= CONSISTENCY * cycle):
                acct.bal -= REQUEST
                payouts.append((day_just_ended, REQUEST))
                ev.append((day_just_ended, f"PAYOUT {REQUEST:,.0f}  (balance now ${acct.bal:,.0f}, cycle profit was ${cycle:,.0f})"))
                cyc_days.clear()

    for tr in trades:
        day = session_day(int(tr["exit_t"]))
        if acct.day is not None and day != acct.day:
            day_close(acct.day)
        low = acct.bal + worst_open(tr)
        if low <= acct.mll:                              # breached while the trade was open
            ev.append((day, f"{'FUNDED' if mode == 'funded' else 'EVAL'} BREACH at ${low:,.0f} (limit ${acct.mll:,.0f}) -> buy new eval"))
            funded_breaches += mode == "funded"; eval_fails += mode == "eval"
            if mode == "funded":
                cur.update(end=day, end_bal=low, status="breached"); runs.append(cur)
            bought += 1; mode, acct, cyc_days, cyc_pnl_today = "eval", Account(PLAN), {}, 0.0
            continue
        pnl = float(tr["pnl_usd"])
        acct.bal += pnl; acct.n += 1; acct.day = day
        if mode == "funded":
            cyc_pnl_today += pnl
        if acct.bal <= acct.mll:
            ev.append((day, f"{'FUNDED' if mode == 'funded' else 'EVAL'} BREACH at close ${acct.bal:,.0f} -> buy new eval"))
            funded_breaches += mode == "funded"; eval_fails += mode == "eval"
            if mode == "funded":
                cur.update(end=day, end_bal=acct.bal, status="breached"); runs.append(cur)
            bought += 1; mode, acct, cyc_days, cyc_pnl_today = "eval", Account(PLAN), {}, 0.0
        elif mode == "eval" and acct.bal - PLAN["start"] >= PLAN["target"]:
            passes += 1
            ev.append((day, f"EVAL PASSED at ${acct.bal:,.0f} -> reset to ${PLAN['start']:,.0f}, FUNDED begins"))
            mode, acct, cyc_days, cyc_pnl_today = "funded", Account(PLAN), {}, 0.0
            nxt = DBL_START                            # a new funded account restarts the schedule
            cur = dict(start=day, peak=PLAN["start"])
    if mode == "funded":
        cur.update(end=acct.day, end_bal=acct.bal, status="open"); runs.append(cur)
    if acct.day is not None:
        day_close(acct.day)
    gross = sum(p[1] for p in payouts)
    return dict(bought=bought, passes=passes, eval_fails=eval_fails, funded_breaches=funded_breaches,
                payouts=len(payouts), gross=gross, runs=runs, net=gross * SPLIT, end_mode=mode, end_bal=acct.bal), ev


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--trades", required=True)
    ap.add_argument("--csv", default="analysis/data/NQ_1m_latest.csv")
    ap.add_argument("--days", type=int, default=365, help="window length ending at the last trade")
    ap.add_argument("--plan", choices=["25k", "50k", "100k"], default="25k")
    ap.add_argument("--request", type=float, default=None, help="payout size per request (default: 25k 1,000; 100k 2,500)")
    ap.add_argument("--cycle-goal", type=float, default=None)
    ap.add_argument("--payout-mode", choices=["lucid", "doubling"], default="lucid", help="lucid = published rules (buffer, 40%% consistency); doubling = the user-defined 100,200,400,800,1600,2100,... schedule")
    ap.add_argument("--fee", type=float, default=0.0, help="cost per evaluation account bought (USD) -- unknown, set yours")
    ap.add_argument("--mult", type=float, default=2.0)
    ap.add_argument("--no-payout", action="store_true", help="never withdraw: show how far funded accounts grow")
    ap.add_argument("--rolling", action="store_true", help="also run every 1-month-step window of --days over the whole file")
    a = ap.parse_args()
    global PLAN, BUFFER, REQUEST, CYCLE_GOAL, PAYOUT_MODE
    PAYOUT_MODE = a.payout_mode
    PLAN = PLANS[a.plan]
    BUFFER = PLAN["lock_bal"]
    REQUEST = a.request if a.request is not None else DEFAULT_REQUEST.get(a.plan, 1000.0)
    CYCLE_GOAL = a.cycle_goal if a.cycle_goal is not None else CYCLE_GOALS.get(a.plan)
    if CYCLE_GOAL is None and PAYOUT_MODE == "lucid":
        ap.error("no sourced cycle profit goal for this plan; pass --cycle-goal")

    allt = list(csv.DictReader(open(a.trades)))
    T, H, L = load_1m(a.csv, int(allt[0]["entry_t"]) - 900000, max(int(t["exit_t"]) for t in allt) + 900000)

    def worst_open(tr):
        i0, i1 = bisect.bisect_left(T, int(tr["entry_t"])), bisect.bisect_right(T, int(tr["exit_t"]))
        if i0 >= i1:
            return 0.0
        side, ep, q = int(tr["side"]), float(tr["entry"]), int(tr["qty"])
        ext = min(L[i0:i1]) if side == 1 else max(H[i0:i1])
        return min(0.0, side * (ext - ep) * a.mult * q)

    day_ms = 86400_000
    last = max(int(t["exit_t"]) for t in allt)
    win = [t for t in allt if int(t["entry_t"]) >= last - a.days * day_ms]
    res, ev = simulate(win, worst_open, payouts_on=not a.no_payout)
    print(f"Window: {utc(int(win[0]['entry_t'])):%Y-%m-%d} -> {utc(last):%Y-%m-%d}  ({len(win)} trades, Lucid {a.plan}; contracts as in the trades file; payout request ${REQUEST:,.0f})\n")
    for day, msg in ev:
        print(f" {day}  {msg}")
    print(f"\nEvaluations bought : {res['bought']}   (passed {res['passes']}, failed {res['eval_fails']}; funded breaches {res['funded_breaches']})")
    print(f"Payouts            : {res['payouts']}  gross withdrawn ${res['gross']:,.0f}  -> ${res['net']:,.0f} after the 90/10 split")
    for k, r in enumerate(res["runs"], 1):
        print(f"Funded #{k}: {r['start']} -> {r['end']}  {r['status']:<8} peak ${r['peak']:,.0f}  end ${r['end_bal']:,.0f}  (P&L ${r['end_bal'] - PLAN['start']:+,.0f})")
    print(f"Ending state       : {res['end_mode']} account, balance ${res['end_bal']:,.0f}")
    print(f"Fee breakeven      : withdrawals cover the evals only if each eval costs <= ${res['net'] / res['bought']:,.2f}"
          f"  (set --fee to your price; at ${a.fee:,.0f}: net ${res['net'] - a.fee * res['bought']:+,.0f})")

    if a.rolling:
        out, start = [], int(allt[0]["entry_t"])
        while start + a.days * day_ms <= last:
            w = [t for t in allt if start <= int(t["entry_t"]) < start + a.days * day_ms]
            if len(w) > 20:
                out.append(simulate(w, worst_open, payouts_on=not a.no_payout)[0])
            start += 30 * day_ms
        net = [r["net"] for r in out]; bought = [r["bought"] for r in out]
        print(f"\nRolling {a.days}-day windows (every 30 days), n={len(out)}:")
        print(f"  evals bought  median {statistics.median(bought):.0f}  range {min(bought)}-{max(bought)}")
        print(f"  net payouts   median ${statistics.median(net):,.0f}  range ${min(net):,.0f}-${max(net):,.0f}")
        print(f"  windows with at least one payout: {sum(r['payouts'] > 0 for r in out)}/{len(out)}")
        print(f"  windows where payouts exceed {a.fee:,.0f} x evals bought: {sum(r['net'] > a.fee * r['bought'] for r in out)}/{len(out)}")


if __name__ == "__main__":
    main()
