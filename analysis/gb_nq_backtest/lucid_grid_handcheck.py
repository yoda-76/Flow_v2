"""Hand checks of the new rules in lucid_grid.py [plan 9.2], against day P&L recomputed independently from the trade list.

    python analysis/gb_nq_backtest/lucid_grid_handcheck.py      (from the FLOW_V2 or handoff-folder root; ~1 min; prints HAND CHECKS PASSED)
"""
import sys, os, collections
sys.path.insert(0, "analysis/gb_nq_backtest")
import lucid_grid as G
from prop_multi import load_trades, fast_sess_ord

ctx = G.Ctx(G.CSV_NQ)
tr = G.load_trades("s1", G.BASE[0], "", "") + G.load_trades("s3", G.BASE[1], "", "")
S1 = G.make_S(ctx, tr, 1)
dayp = collections.defaultdict(float)            # realized P&L per session day ordinal (1 MNQ each), by exit time
for r in tr:
    dayp[fast_sess_ord(r["x"])] += r["pnl"]
D = ctx.daylist
first = S1["recs"][0][2]
starts = range(first, len(D) - 300)

# 1. Flex eval: a pass delayed by the 40% rule
P = dict(G.PLANS["flex_25k"]); Pn = dict(P); Pn.pop("eval_consist")
found = 0
for di in starts:
    r1 = G.eval_attempt(ctx, S1, P, di); r0 = G.eval_attempt(ctx, S1, Pn, di)
    if r1[0] == "PASS" and r0[0] == "PASS" and r1[1] > r0[1]:
        days = [D[k] for k in range(di, r1[1] + 1)]
        prof = sum(dayp[d] for d in days)
        mx = max(dayp[d] for d in days)
        prof0 = sum(dayp[D[k]] for k in range(di, r0[1] + 1)); mx0 = max(dayp[D[k]] for k in range(di, r0[1] + 1))
        print(f"FLEX EVAL start {G._iso(D[di])}: target reached {G._iso(D[r0[1]])} (profit {prof0:.0f}, best day {mx0:.0f} = "
              f"{100 * mx0 / prof0:.0f}% > 40%) -> pass delayed to {G._iso(D[r1[1]])} (profit {prof:.0f}, best day {mx:.0f} = {100 * mx / prof:.0f}%)")
        assert mx0 > 0.4 * prof0 and mx <= 0.4 * prof + 1e-6 and prof >= 1250
        found += 1
        if found >= 2: break
assert found, "no delayed Flex pass found"

# 2. Flex payout: 5 days >= $100, cycle > 0, R <= 50% of profit; MLL moved to the lock at request
def first_payout_check(pk, pol, rule):
    P = G.PLANS[pk]
    for di in starts[::7]:
        o = {"ev": [], "end": None}
        g = G.funded_gen(ctx, S1, P, pol, di, o)
        next(g, None)
        if o["ev"]:
            pdi, R = o["ev"][0]
            days = [D[k] for k in range(di, pdi + 1)]
            rule(P, di, pdi, R, days)
            return
    raise AssertionError("no payout found")

def flex_rule(P, di, pdi, R, days):
    q = sum(dayp[d] >= P["minday"] for d in days); prof = sum(dayp[d] for d in days)
    q_prev = sum(dayp[d] >= P["minday"] for d in days[:-1]); prof_prev = sum(dayp[d] for d in days[:-1])
    print(f"FLEX PAYOUT start {G._iso(D[di])}: paid {R:.0f} on {G._iso(D[pdi])}: qualifying days {q}, profit {prof:.0f} "
          f"(50% = {prof / 2:.0f}); day before: {q_prev} days, 50% = {prof_prev / 2:.0f}")
    assert q >= 5 and prof > 0 and R <= 0.5 * prof + 1e-6
    assert not (q_prev >= 5 and prof_prev > 0 and R <= 0.5 * prof_prev), "gate already met the day before"

def direct_rule(P, di, pdi, R, days):
    prof = sum(dayp[d] for d in days); mx = max(dayp[d] for d in days)
    print(f"DIRECT PAYOUT start {G._iso(D[di])}: paid {R:.0f} on {G._iso(D[pdi])}: cycle profit {prof:.0f} (goal {P['goal1']:.0f}), "
          f"best day {mx:.0f} = {100 * mx / prof:.1f}% (<= 20%)")
    assert prof >= P["goal1"] and mx <= 0.2 * prof + 1e-6

first_payout_check("flex_25k", "max", flex_rule)
first_payout_check("direct_25k", "max", direct_rule)

# Flex MLL lock at request: after the first payout the MLL is the locked level
P = G.PLANS["flex_25k"]
for di in starts[::7]:
    o = {"ev": [], "end": None}
    g = G.funded_gen(ctx, S1, P, "500", di, o)
    next(g, None)
    if o["ev"]:
        fr = g.gi_frame.f_locals
        print(f"FLEX MLL after payout: mll {fr['acc'].mll:.0f} locked {fr['acc'].locked} (expected {P['lock_mll']:.0f}); balance {fr['acc'].bal:.0f}")
        assert fr["acc"].mll == P["lock_mll"] and fr["acc"].locked
        break

# 3. Pro journey that breaks even only after more than one payout (or after a funded loss)
memo = G.Memo(ctx)
S5 = G.make_S(ctx, tr, 5)
for pk, e, f in (("pro_150k", 5, 5), ("pro_25k", 1, 1)):
    for di in starts[::3]:
        j = G.journey(memo, S5 if e == 5 else S1, S5 if f == 5 else S1, pk, e, f, "500", di)
        if j["be"] and j["be"][1] > 1:
            print(f"PRO MULTI-PAYOUT {pk} {e}/{f} start {G._iso(D[di])}: first payout {G._iso(D[j['first'][0]])} "
                  f"(fees then {j['first'][2]:.2f}, 0.9x500 = 450 < fees), break-even at payout {j['be'][1]} on "
                  f"{G._iso(D[j['be'][0]])}: received {0.9 * 500 * j['be'][1]:.0f} >= fees {j['fees']:.2f} "
                  f"(evals {j['evals']}, resets {j['resets']}, funded losses {j['fbr']})")
            assert 450 * j["be"][1] >= j["fees"] - 1e-6 and 450 * (j["be"][1] - 1) < j["fees"]
            assert abs(j["fees"] - (G.PLANS[pk]["fee"] * j["evals"] + G.PLANS[pk]["reset"] * j["resets"])) < 1e-6
            break
print("HAND CHECKS PASSED")
