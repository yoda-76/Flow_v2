"""Prop-firm evaluation simulator for SEVERAL strategies trading ONE account at the same time (offline analysis).

Read-only over the trades CSVs and the 1m CSV; writes only under --out. Places no orders.

    python analysis/gb_nq_backtest/prop_multi.py --trades s1=reports/prop3/s1/trades.csv \\
        --trades s2=reports/prop3/s2/trades.csv --trades s3=reports/prop3/s3/trades.csv \\
        --out reports/prop3/sim_all [--sets s1,s2,s3,s1+s3,s1+s2+s3] [--plans 25k,50k,100k] \\
        [--from 2020-01-01 --to 2024-12-31] [--expiry-days 60] [--dll 1200] \\
        [--csv-for NAME=PATH] [--mult-for NAME=X] [--qty-for NAME=N] [--hold-cols]

TRADE FILE FORMATS (detected by header columns)
  * CT format: entry_time_ct / exit_time_ct ('YYYY-MM-DD HH:MM' CT), entry_px or fill, side L/S (absent = long), pnl_usd.
  * MS format: entry_t / exit_t = UTC ms of the 1m-bar open minute (used as is), side numeric 1 / -1, entry (price),
    pnl_usd (already includes qty and commission; the file's qty column is ignored for P&L -- unrealized uses --qty-for).
  --from / --to filter by the CT date of the entry (CT string date, or the CT date of entry_t).

PER-STRATEGY INSTRUMENT
  --csv-for / --mult-for / --qty-for NAME=... (repeatable) override the global --csv / --mult / --qty for one strategy.
  Its unrealized P&L at minute t uses ITS OWN instrument's 1m bar at t (low long / high short) * its mult * its qty.
  Each distinct CSV is loaded once. The session-day list = union of session days with a bar in ANY loaded CSV.

TRADES OPEN ACROSS SESSION-DAY BOUNDARIES (overnight / weekend holds)
  An attempt that starts at session day D (CHAIN: next day after PASS/FAIL; ROLLING: every day) includes ONLY trades with
  entry >= start of D (17:00 ET the previous calendar day). A trade entered before D and still open at that start is
  excluded from that attempt entirely (neither its unrealized nor its closing P&L). Implemented by rebuilding only the
  window [start of D, last exit of the straddling trades] of the merged path and splicing it onto the precomputed path;
  with no straddling trades the path is the precomputed one (results identical to the old behaviour).
  The EOD trailing update (Account.eod at session roll) uses the CLOSED balance even if a position is open overnight.

--hold-cols (auto-on when any MS-format file or any *-for flag is used) appends maxHold_h and worstTr (worst single-trade
pnl) columns to the per-set table; otherwise the table layout is the legacy one (keeps old summaries byte-identical).

WHAT IT MODELS
  * The trades of a set (e.g. "s1+s3") are merged into ONE account. Per 1m bar minute t the account is
    closed balance + sum over trades open at t (entry_ms <= t < exit_ms) of side*(W - entry_px)*mult*qty,
    W = bar low (long) / bar high (short) of minute t (worst case inside the minute). At t == exit_ms the trade's
    pnl_usd is realized (it is no longer open at t). Several trades may be open at once.
  * Rules per plan come from lucid_replay.PLANS / Account (start, target, EOD-trailing max loss limit, lock level,
    max micros). The limit trails via Account.eod() when the CME session day changes (roll 17:00 ET).
    FAIL: closed + open worst <= mll at any minute (checked before realizing that minute's exits), or closed <= mll
    after realizing. PASS: closed balance - start >= target, checked when trades close. Either ends the attempt.
  * CHAIN: one attempt after another over the window; the next one starts on the next session day after PASS/FAIL
    (after the last counted day for EXPIRED).
  * ROLLING START: an independent attempt from every session day that has a set trade within the next 60 calendar
    days; ends on PASS / FAIL / EXPIRED / end of data (OPEN, excluded from the rates).
  * --expiry-days N (0 = none): an attempt with no result after N trading days (session days with a 1m bar) is EXPIRED.
  * --dll X (0 = off, soft daily loss limit): if within a session day (closed P&L that day + current open worst)
    <= -X, all open positions are closed at that minute (realized at the worst price of that minute = the open worst),
    and every later trade event of that day is ignored (later entries not taken). Does not fail the account by itself.
    (With --dll off, minute paths are compressed to one worst point per close event: same result, faster.)

WHAT IT DOES NOT MODEL
  Evaluation / reset fees, payouts, funded phase, rule changes over time, consistency rules, minimum trading days,
  fills inside a minute beyond the worst-price assumption, slippage beyond what is in pnl_usd, the 50k/100k lock levels beyond lucid_replay's assumptions.
  Rule source = lucid_replay.py (third-party Lucid rules -- confirm with Lucid before relying on them).
"""
import argparse, bisect, csv, datetime as d, os, statistics, sys, time
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lucid_replay import PLANS, Account, session_day
from prop_sim import DEFAULT_REQUEST, CYCLE_GOALS, CONSISTENCY, SPLIT, DBL_START, DBL_CAP, DBL_STEP   # BUFFER = plan["lock_bal"]

H_MS, DAY_MS = 3600000, 86400000
ORD0 = 719163                                     # date(1970,1,1).toordinal()


def build_transitions(std_off, dst_off, start_utc_hour, end_utc_hour):
    tr = [(-(1 << 62), std_off * H_MS)]
    for y in range(2017, 2031):
        x = d.date(y, 3, 1); x += d.timedelta((6 - x.weekday()) % 7 + 7)       # 2nd Sunday of March
        s = d.datetime(y, 3, x.day, start_utc_hour, 0, tzinfo=d.timezone.utc)
        x = d.date(y, 11, 1); x += d.timedelta((6 - x.weekday()) % 7)          # 1st Sunday of November
        e = d.datetime(y, 11, x.day, end_utc_hour, 0, tzinfo=d.timezone.utc)
        tr.append((int(s.timestamp() * 1000), dst_off * H_MS))
        tr.append((int(e.timestamp() * 1000), std_off * H_MS))
    tr.sort()
    return [t for t, _ in tr], [o for _, o in tr]


CT_T, CT_O = build_transitions(-6, -5, 8, 7)      # CT: DST 2nd Sun Mar 08:00 UTC .. 1st Sun Nov 07:00 UTC (as vault_break.py)
ET_T, ET_O = build_transitions(-5, -4, 7, 6)      # ET: same rule as lucid_replay.et_offset
_ct_cache = {}


def ct_to_ms(s):
    """'YYYY-MM-DD HH:MM' in CT -> UTC ms (inverse of vault_break's table)."""
    v = _ct_cache.get(s)
    if v is None:
        loc = ((d.date(int(s[0:4]), int(s[5:7]), int(s[8:10])).toordinal() - ORD0) * DAY_MS
               + int(s[11:13]) * H_MS + int(s[14:16]) * 60000)
        u = loc + 5 * H_MS                                           # try the DST offset (-5)
        v = u if CT_O[bisect.bisect_right(CT_T, u) - 1] == -5 * H_MS else loc + 6 * H_MS
        _ct_cache[s] = v
    return v


def fast_sess_ord(ms):                       # ordinal of the CME session day (rolls 17:00 ET), same as lucid_replay.session_day
    off = ET_O[bisect.bisect_right(ET_T, ms) - 1]
    return (ms + off + 7 * H_MS) // DAY_MS + ORD0


def ct_date_of_ms(ms):
    return d.date.fromordinal((ms + CT_O[bisect.bisect_right(CT_T, ms) - 1]) // DAY_MS + ORD0).isoformat()


def day_start_ms(sd_ord):
    """UTC ms of the start (17:00 ET of the previous calendar day) of the session day with ordinal sd_ord."""
    loc = (sd_ord - 1 - ORD0) * DAY_MS + 17 * H_MS
    u = loc + 4 * H_MS
    return u if ET_O[bisect.bisect_right(ET_T, u) - 1] == -4 * H_MS else loc + 5 * H_MS


def load_trades(name, path, d_from, d_to):
    out = []
    with open(path, newline="") as fh:
        rd = csv.DictReader(fh)
        ms_fmt = "entry_t" in (rd.fieldnames or [])
        for r in rd:
            if ms_fmt:
                e, x = int(float(r["entry_t"])), int(float(r["exit_t"]))
                ed = ct_date_of_ms(e)
            else:
                ed = r["entry_time_ct"][:10]
            if d_from and ed < d_from: continue
            if d_to and ed > d_to: continue
            if ms_fmt:
                px, side = float(r["entry"]), (-1 if float(r["side"]) < 0 else 1)
            else:
                e, x = ct_to_ms(r["entry_time_ct"]), ct_to_ms(r["exit_time_ct"])
                px = r.get("entry_px") or r.get("fill")
                sd = r.get("side")
                side = -1 if (sd or "L").strip().upper().startswith("S") else 1
            out.append(dict(name=name, e=e, x=x, px=float(px), side=side, pnl=float(r["pnl_usd"])))
    return out


def load_bars(path, lo, hi):
    T, H, L = [], [], []
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.split(",")
            t = int(p[0])
            if lo <= t <= hi:
                T.append(t); H.append(float(p[2])); L.append(float(p[3]))
            elif t > hi:
                break
    return T, H, L


def build_records(trades, bars, day_idx, daylist, tmax=None):
    """trades carry bk (bars key), m (mult), q (qty). tmax: only minutes/closes with t <= tmax (window build)."""
    unreal, cnt, pnlat, ncl = defaultdict(float), defaultdict(int), defaultdict(float), defaultdict(int)
    for tr in trades:
        T, H, L = bars[tr["bk"]]
        mult, qty = tr["m"], tr["q"]
        i0, i1 = bisect.bisect_left(T, tr["e"]), bisect.bisect_left(T, tr["x"])
        if tmax is not None: i1 = min(i1, bisect.bisect_right(T, tmax))
        s, px = tr["side"], tr["px"]
        for i in range(i0, i1):
            t = T[i]
            unreal[t] += s * ((L[i] if s > 0 else H[i]) - px) * mult * qty
            cnt[t] += qty
        if tmax is None or tr["x"] <= tmax:
            pnlat[tr["x"]] += tr["pnl"]; ncl[tr["x"]] += 1
    recs, mism = [], 0
    for t in sorted(set(unreal) | set(pnlat)):
        sd = session_day(t).toordinal()
        if sd != fast_sess_ord(t): mism += 1
        di = day_idx.get(sd)
        if di is None: di = max(0, bisect.bisect_right(daylist, sd) - 1)
        recs.append((t, sd, di, unreal.get(t, 0.0), pnlat.get(t, 0.0), ncl.get(t, 0), cnt.get(t, 0)))
    return recs, (max(cnt.values()) if cnt else 0), mism


def compress(recs):
    """Balance is constant between close events, so only the worst open point between them matters (no-DLL case)."""
    out, g = [], None
    for t, sd, di, un, pnl, k, c in recs:
        if g is not None and g[1] != sd:
            out.append((g[0], g[1], g[2], g[3], 0.0, 0, 0)); g = None
        g = [t, sd, di, un if g is None else min(g[3], un)]
        if k > 0 or pnl != 0.0:
            out.append((t, sd, di, g[3], pnl, k, 0)); g = None
    if g is not None:
        out.append((g[0], g[1], g[2], g[3], 0.0, 0, 0))
    return out


def sim(recs, start_i, start_di, plan, expiry, dll, last_di):
    """-> (result, end_sd, end_di, days, ntrades, pnl, next_i). result in PASS/FAIL/EXPIRED/OPEN."""
    acc, cur, ntr, dstart, locked, cur_di = Account(plan), None, 0, plan["start"], False, start_di
    target, start = plan["target"], plan["start"]
    for i in range(start_i, len(recs)):
        t, sd, di, un, pnl, k, _c = recs[i]
        if sd != cur:
            if cur is not None:
                acc.eod()
            if expiry and di - start_di >= expiry:
                return ("EXPIRED", cur, cur_di, expiry, ntr, acc.bal - start, i)
            cur, cur_di, dstart, locked = sd, di, acc.bal, False
        if locked:
            continue
        low = acc.bal + un
        if low <= acc.mll:
            return ("FAIL", sd, di, di - start_di + 1, ntr, low - start, i + 1)
        if dll and (acc.bal - dstart) + un <= -dll:
            acc.bal += un; locked = True                       # flatten at the worst price of this minute, rest of day ignored
            if acc.bal <= acc.mll:
                return ("FAIL", sd, di, di - start_di + 1, ntr, acc.bal - start, i + 1)
            continue
        if pnl != 0.0 or k:
            acc.bal += pnl; ntr += k
            if acc.bal <= acc.mll:
                return ("FAIL", sd, di, di - start_di + 1, ntr, acc.bal - start, i + 1)
            if acc.bal - start >= target:
                return ("PASS", sd, di, di - start_di + 1, ntr, acc.bal - start, i + 1)
    return ("OPEN", cur, cur_di, (cur_di - start_di + 1) if cur is not None else 0, ntr, acc.bal - start, len(recs))


def attempt_recs(S, di, daylist, bars, day_idx, dll):
    """-> (recs, si) for an attempt starting at session day index di; trades straddling the start are excluded."""
    D = daylist[di]
    W = S["win"]
    if di not in W:
        sms = day_start_ms(D)
        st = [tr for tr in S["trades"] if tr["e"] < sms <= tr["x"]]
        if not st:
            W[di] = None
        else:
            mx = max(tr["x"] for tr in st)
            inc = [tr for tr in S["trades"] if sms <= tr["e"] <= mx]
            hr, _c, _m = build_records(inc, bars, day_idx, daylist, tmax=mx)
            W[di] = (hr if dll else compress(hr), bisect.bisect_right(S["RT"], mx))
    w = W[di]
    if w is None:
        return S["recs"], bisect.bisect_left(S["SDs"], D)
    return w[0] + S["recs"][w[1]:], 0


def life_account(arec, si, start_di, end_di, plan, funded, o, fa, ev):
    """One account (eval or funded) on the merged records. -> (res, end_sd, end_di, bal, peak); res FAIL/PASS/OPEN.
    Funded: payouts requested at each session-day close (fa holds cycle state); data stops after session index end_di."""
    acc, cur, cur_di, dstart, locked, today = Account(plan), None, start_di, plan["start"], False, 0.0
    start, target, dll = plan["start"], plan["target"], o["dll"]
    peak = start

    def dayclose(day):
        nonlocal today, peak
        acc.eod()
        if not funded: return
        peak = max(peak, acc.bal)
        fa["cyc"][day] = fa["cyc"].get(day, 0.0) + today
        today = 0.0
        when = d.date.fromordinal(day).isoformat()
        if o["mode"] == "doubling":
            profit = acc.bal - start
            if not o["nopay"] and profit >= 2.0 * fa["nxt"]:
                amt = fa["nxt"]; acc.bal -= amt; fa["pay"].append((day, amt)); o["allpay"].append((day, amt))
                ev.append((when, f"PAYOUT {amt:,.0f}  (profit was ${profit:,.0f}, ${profit - amt:,.0f} left; balance ${acc.bal:,.0f})"))
                fa["nxt"] = amt * 2.0 if amt < DBL_CAP else amt + DBL_STEP
        else:
            cyc = sum(fa["cyc"].values()); best = max(fa["cyc"].values(), default=0.0)
            if (not o["nopay"] and acc.bal - o["req"] >= BUFFER_OF(plan) and cyc >= o["goal"] and best <= CONSISTENCY * cyc):
                acc.bal -= o["req"]; fa["pay"].append((day, o["req"])); o["allpay"].append((day, o["req"]))
                ev.append((when, f"PAYOUT {o['req']:,.0f}  (balance now ${acc.bal:,.0f}, cycle profit was ${cyc:,.0f})"))
                fa["cyc"].clear()

    for i in range(si, len(arec)):
        t, sd, di, un, pnl, k, _c = arec[i]
        if di > end_di: break
        if sd != cur:
            if cur is not None: dayclose(cur)
            cur, cur_di, dstart, locked, today = sd, di, acc.bal, False, 0.0
        if locked: continue
        low = acc.bal + un
        if low <= acc.mll:
            return ("FAIL", sd, di, low, peak)
        if dll and (acc.bal - dstart) + un <= -dll:
            acc.bal += un; today += un; locked = True
            if acc.bal <= acc.mll: return ("FAIL", sd, di, acc.bal, peak)
            continue
        if pnl != 0.0 or k:
            acc.bal += pnl; today += pnl
            if acc.bal <= acc.mll: return ("FAIL", sd, di, acc.bal, peak)
            if not funded and acc.bal - start >= target: return ("PASS", sd, di, acc.bal, peak)
    if cur is not None: dayclose(cur)
    return ("OPEN", cur, cur_di, acc.bal, peak)


def BUFFER_OF(plan):
    return plan["lock_bal"]


def life_run(S, start_di, end_di, plan, o, daylist, bars, day_idx):
    """Eval -> funded -> payouts lifecycle, one account at a time. -> result dict."""
    ev, runs, o["allpay"] = [], [], []
    bought, passes, efail, fbreach = 1, 0, 0, 0
    mode, di, fa, first_pass, state = "eval", start_di, None, None, None
    iso = lambda sd: d.date.fromordinal(sd).isoformat()
    while di <= end_di:
        arec, si = attempt_recs(S, di, daylist, bars, day_idx, o["dll"])
        if si >= len(arec):
            break
        funded = mode == "funded"
        if funded and fa is None:
            fa = dict(cyc={}, nxt=DBL_START, pay=[], start=daylist[di], sdi=di)
        res, esd, edi, bal, peak = life_account(arec, si, di, end_di, plan, funded, o, fa, ev)
        if funded:
            fa["peak"] = max(peak, bal) if res == "OPEN" else peak
        if res == "OPEN":
            if funded:
                runs.append(dict(fa, end=esd, end_bal=bal, status="open", days=(edi - fa["sdi"] + 1) if esd else 0))
            state = f"{mode} account open, balance ${bal:,.0f}"
            break
        if res == "FAIL":
            if funded:
                ev.append((iso(esd), f"FUNDED BREACH at ${bal:,.0f} -> buy new eval"))
                runs.append(dict(fa, end=esd, end_bal=bal, status="breached", days=edi - fa["sdi"] + 1)); fbreach += 1
            else:
                ev.append((iso(esd), f"EVAL BREACH at ${bal:,.0f} -> buy new eval")); efail += 1
            bought += 1; mode, fa = "eval", None
        else:
            passes += 1
            if first_pass is None: first_pass = esd
            ev.append((iso(esd), f"EVAL PASSED at ${bal:,.0f} -> FUNDED begins next session day")); mode, fa = "funded", None
        di = edi + 1
    if state is None: state = f"{mode} account idle at data end"
    gross = sum(a for _, a in o["allpay"])
    return dict(ev=ev, runs=runs, bought=bought, passes=passes, efail=efail, fbreach=fbreach, pay=list(o["allpay"]),
                gross=gross, net=gross * SPLIT, state=state, first_pass=first_pass, t0=daylist[start_di] if start_di < len(daylist) else None)


def pctile(v, p):
    s = sorted(v)
    if not s: return float("nan")
    x = (len(s) - 1) * p / 100.0
    lo = int(x); hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (x - lo)


def stats_for(trades, mult, qty):
    ts = sorted(trades, key=lambda r: (r["x"], r["e"]))
    cum = peak = dd = 0.0
    days = defaultdict(float)
    for r in ts:
        cum += r["pnl"]; peak = max(peak, cum); dd = max(dd, peak - cum)
        days[session_day(r["x"])] += r["pnl"]
    return len(ts), cum, dd, (min(days.values()) if days else 0.0)


def fmt_med(v):
    return f"{statistics.median(v):.1f}" if v else "-"


def fmt_mean(v):
    return f"{statistics.mean(v):.1f}" if v else "-"


def run_lifecycle(a, sets, daylist, bars, day_idx, emit):
    plan = PLANS[a.plan]
    o = dict(mode=a.payout_mode, nopay=a.no_payout, dll=a.dll, req=DEFAULT_REQUEST.get(a.plan, 1000.0), goal=CYCLE_GOALS.get(a.plan))
    if o["goal"] is None and a.payout_mode == "lucid":
        sys.exit("no sourced cycle profit goal for this plan")
    iso = lambda sd: d.date.fromordinal(sd).isoformat()
    emit(f"lifecycle: plan {a.plan}, payout-mode {a.payout_mode}{' (NO PAYOUTS)' if a.no_payout else ''}, request {o['req']:,.0f}, "
         f"buffer {plan['lock_bal']:,.0f}, split {SPLIT}, fee/eval {a.fee:,.0f}")
    for sname, S in sets.items():
        recs = S["recs"]
        if not recs: continue
        last_di = len(daylist) - 1
        r = life_run(S, recs[0][2], last_di, plan, o, daylist, bars, day_idx)
        L = []
        def out(s=""):
            print(s); L.append(s)
        out(f"=== set {sname}: lifecycle from {iso(r['t0'])} ===")
        for dt, msg in r["ev"]:
            out(f" {dt}  {msg}")
        out()
        out(f"Evaluations bought : {r['bought']}  (passed {r['passes']}, eval fails {r['efail']}, funded breaches {r['fbreach']})")
        out(f"Payouts            : {len(r['pay'])}  gross withdrawn ${r['gross']:,.0f}  -> ${r['net']:,.0f} after split")
        out(f"Net after fees     : ${r['net'] - a.fee * r['bought']:+,.0f}  (fee {a.fee:,.0f} x {r['bought']} = {a.fee * r['bought']:,.0f})")
        out(f"First eval passed  : {iso(r['first_pass']) if r['first_pass'] else '-'}")
        if r["pay"]:
            out(f"Days eval#1 start -> first payout: {r['pay'][0][0] - r['t0']} calendar days (first payout {iso(r['pay'][0][0])})")
        for k, f in enumerate(r["runs"], 1):
            out(f"Funded #{k}: start {iso(f['start'])} end {iso(f['end']) if f['end'] else '-'} {f['status']:<8} payouts {len(f['pay'])} "
                f"withdrawn ${sum(x for _, x in f['pay']):,.0f} peak ${f['peak']:,.0f} end ${f['end_bal']:,.0f} session days {f['days']}")
        out(f"Ending state       : {r['state']}")
        with open(os.path.join(a.out, f"lifecycle_{sname}.txt"), "w") as fh:
            fh.write("\n".join(L) + "\n")
        if a.rolling_days:
            first, lastsd, N = daylist[recs[0][2]], recs[-1][1], a.rolling_days
            rows, w0 = [], first
            while w0 + N <= lastsd:
                sdi, edi = bisect.bisect_left(daylist, w0), bisect.bisect_left(daylist, w0 + N) - 1
                x = life_run(S, sdi, edi, plan, o, daylist, bars, day_idx)
                rows.append((iso(w0), x["bought"], x["passes"], x["efail"], x["fbreach"], len(x["pay"]), x["gross"], x["net"],
                             (x["pay"][0][0] - x["t0"]) if x["pay"] else ""))
                w0 += 30
            with open(os.path.join(a.out, f"rolling_{sname}.csv"), "w", newline="") as fh:
                w = csv.writer(fh)
                w.writerow(["window_start", "evals_bought", "passes", "eval_fails", "funded_breaches", "payouts", "gross", "net_after_split", "days_to_first_payout"])
                w.writerows(rows)
            nets = [x[7] for x in rows]; bo = [x[1] for x in rows]; dd = [x[8] for x in rows if x[8] != ""]
            R = []
            def o2(s):
                print(s); R.append(s)
            o2(f"Rolling {N}-day windows (every 30 days), set {sname}: n={len(rows)}")
            if rows:
                o2(f"  evals bought  median {statistics.median(bo):.0f}  range {min(bo)}-{max(bo)}")
                o2(f"  net payouts after split  median ${statistics.median(nets):,.0f}  mean ${statistics.mean(nets):,.0f}  p10 ${pctile(nets, 10):,.0f}  p90 ${pctile(nets, 90):,.0f}  min ${min(nets):,.0f}  max ${max(nets):,.0f}")
                o2(f"  windows with >=1 payout: {sum(x[5] > 0 for x in rows)}/{len(rows)}")
                o2(f"  windows where net payouts > fee x evals bought ({a.fee:,.0f}): {sum(x[7] > a.fee * x[1] for x in rows)}/{len(rows)}")
                o2(f"  median days to first payout: {statistics.median(dd):.0f}" if dd else "  median days to first payout: -")
            with open(os.path.join(a.out, f"lifecycle_{sname}.txt"), "a") as fh:
                fh.write("\n" + "\n".join(R) + "\n")


def main():
    ap =argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--trades", action="append", required=True, metavar="NAME=PATH")
    ap.add_argument("--csv", default="analysis/data/NQ_1m_latest.csv")
    ap.add_argument("--mult", type=float, default=2.0)
    ap.add_argument("--qty", type=int, default=5)
    ap.add_argument("--sets", default="s1,s2,s3,s1+s3,s1+s2+s3")
    ap.add_argument("--plans", default="25k,50k,100k")
    ap.add_argument("--from", dest="d_from", default="")
    ap.add_argument("--to", dest="d_to", default="")
    ap.add_argument("--expiry-days", type=int, default=0)
    ap.add_argument("--dll", type=float, default=0.0)
    ap.add_argument("--csv-for", action="append", default=[], metavar="NAME=PATH")
    ap.add_argument("--mult-for", action="append", default=[], metavar="NAME=X")
    ap.add_argument("--qty-for", action="append", default=[], metavar="NAME=N")
    ap.add_argument("--hold-cols", action="store_true")
    ap.add_argument("--lifecycle", action="store_true", help="eval -> funded -> payouts lifecycle per set (instead of the evaluation tables)")
    ap.add_argument("--plan", default="25k", help="plan for --lifecycle")
    ap.add_argument("--payout-mode", choices=["lucid", "doubling"], default="lucid")
    ap.add_argument("--fee", type=float, default=0.0, help="cost per evaluation bought")
    ap.add_argument("--no-payout", action="store_true")
    ap.add_argument("--rolling-days", type=int, default=0, help="lifecycle: also independent windows of N calendar days every 30 days")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    t0 = time.time()
    os.makedirs(a.out, exist_ok=True)

    sources, ms_used = {}, False
    for spec in a.trades:
        n, p = spec.split("=", 1)
        sources[n] = load_trades(n, p, a.d_from, a.d_to)
        with open(p, newline="") as fh:
            ms_used = ms_used or "entry_t" in fh.readline().strip().split(",")
    ovr = {}
    for key, flag, cast in (("csv", a.csv_for, str), ("mult", a.mult_for, float), ("qty", a.qty_for, int)):
        ovr[key] = {}
        for spec in flag:
            n, v = spec.split("=", 1)
            if n not in sources: sys.exit(f"--{key}-for: unknown strategy {n}")
            ovr[key][n] = cast(v)
    hold_cols = a.hold_cols or ms_used or any(ovr[k] for k in ovr)
    allt = []
    for n, v in sources.items():
        for r in v:
            r["bk"], r["m"], r["q"] = ovr["csv"].get(n, a.csv), ovr["mult"].get(n, a.mult), ovr["qty"].get(n, a.qty)
        allt += v
    if not allt:
        sys.exit("no trades")
    bars = {}
    for bk in sorted({r["bk"] for r in allt}):
        rs = [r for r in allt if r["bk"] == bk]
        bars[bk] = load_bars(bk, min(r["e"] for r in rs) - DAY_MS, max(r["x"] for r in rs) + DAY_MS)
    daylist = sorted({fast_sess_ord(t) for B in bars.values() for t in B[0]})
    day_idx = {o: i for i, o in enumerate(daylist)}
    nbars = sum(len(B[0]) for B in bars.values())

    lines = []
    def emit(s=""):
        print(s); lines.append(s)
    emit(f"prop_multi: trades {', '.join(f'{n}={len(v)}' for n, v in sources.items())}; bars {nbars:,}; session days {len(daylist):,}; "
         f"mult {a.mult} qty {a.qty}; expiry-days {a.expiry_days}; dll {a.dll}; window {a.d_from or 'start'}..{a.d_to or 'end'}")

    sets = {}
    for s in [x.strip() for x in a.sets.split(",") if x.strip()]:
        names = s.split("+")
        if any(n not in sources for n in names):
            emit(f"WARNING: set {s} skipped (unknown strategy name)"); continue
        tr = [r for n in names for r in sources[n]]
        recs, maxc, mism = build_records(tr, bars, day_idx, daylist)
        if mism: emit(f"WARNING: set {s}: {mism} record minutes where lucid_replay.session_day != fast session day")
        rc = recs if a.dll else compress(recs)
        sets[s] = dict(trades=tr, recs=rc, maxc=maxc, stats=stats_for(tr, a.mult, a.qty), win={},
                       hold=max((r["x"] - r["e"]) / H_MS for r in tr) if tr else 0.0,
                       worst=min((r["pnl"] for r in tr), default=0.0),
                       RT=[r[0] for r in rc], SDs=[r[1] for r in rc])

    if a.lifecycle:
        run_lifecycle(a, sets, daylist, bars, day_idx, emit)
        return

    for pk in [x.strip() for x in a.plans.split(",") if x.strip()]:
        plan = PLANS[pk]
        emit(); emit(f"=== Plan {pk}: start {plan['start']:,.0f}, target +{plan['target']:,.0f}, max loss {plan['allow']:,.0f} EOD trailing, max {plan['max_micros']} micros ===")
        hdr = (f"{'set':<10}{'trades':>7}{'net':>10}{'maxDD':>9}{'worstDay':>10}{'maxCtr':>7} | "
               f"{'CH pass':>7}{'fail':>5}{'exp':>4}{'open':>5} | {'ROLL n':>7}{'open':>5}{'pass%':>7}{'fail%':>7}{'exp%':>6}{'medPass':>8}{'meanPass':>9}{'medFail':>8}" + (f"{'maxHold_h':>10}{'worstTr':>9}" if hold_cols else ""))
        emit(hdr); emit("-" * len(hdr))
        for sname, S in sets.items():
            recs = S["recs"]
            n, net, dd, wd = S["stats"]
            flag = "!" if S["maxc"] > plan["max_micros"] else " "
            # CHAIN
            chain, di, last_di = [], (recs[0][2] if recs else 0), len(daylist) - 1
            while recs and di <= last_di:
                arec, si = attempt_recs(S, di, daylist, bars, day_idx, a.dll)
                if si >= len(arec): break
                res, esd, edi, days, ntr, pnl, _ni = sim(arec, si, di, plan, a.expiry_days, a.dll, last_di)
                chain.append((daylist[di], esd, res, days, ntr, pnl))
                if res == "OPEN": break
                di = (di + a.expiry_days) if res == "EXPIRED" else edi + 1
            with open(os.path.join(a.out, f"attempts_{pk}_{sname}.csv"), "w", newline="") as fh:
                w = csv.writer(fh); w.writerow(["start_date", "end_date", "result", "trading_days", "trades", "end_pnl"])
                for sdt, esd, res, days, ntr, pnl in chain:
                    w.writerow([d.date.fromordinal(sdt).isoformat(), d.date.fromordinal(esd).isoformat() if esd else "", res, days, ntr, f"{pnl:.2f}"])
            cp = sum(c[2] == "PASS" for c in chain); cf = sum(c[2] == "FAIL" for c in chain)
            ce = sum(c[2] == "EXPIRED" for c in chain); co = sum(c[2] == "OPEN" for c in chain)
            # ROLLING
            rp, rf, re_, ro, dp, df = 0, 0, 0, 0, [], []
            if recs:
                for di in range(recs[0][2], recs[-1][2] + 1):
                    arec, si = attempt_recs(S, di, daylist, bars, day_idx, a.dll)
                    if si >= len(arec) or arec[si][1] > daylist[di] + 60: continue
                    res, esd, edi, days, ntr, pnl, _ni = sim(arec, si, di, plan, a.expiry_days, a.dll, last_di)
                    if res == "PASS": rp += 1; dp.append(days)
                    elif res == "FAIL": rf += 1; df.append(days)
                    elif res == "EXPIRED": re_ += 1
                    else: ro += 1
            nc = rp + rf + re_
            pct = lambda x: f"{100.0 * x / nc:.1f}" if nc else "-"
            emit(f"{sname:<10}{n:>7}{net:>10,.0f}{dd:>9,.0f}{wd:>10,.0f}{S['maxc']:>6}{flag} | "
                 f"{cp:>7}{cf:>5}{ce:>4}{co:>5} | {nc + ro:>7}{ro:>5}{pct(rp):>7}{pct(rf):>7}{pct(re_):>6}{fmt_med(dp):>8}{fmt_mean(dp):>9}{fmt_med(df):>8}" + (f"{S['hold']:>10.1f}{S['worst']:>9,.0f}" if hold_cols else ""))
        emit(f"('!' after maxCtr = exceeds the plan's {plan['max_micros']} micros; rolling rates exclude OPEN attempts)")
    emit(); emit(f"runtime {time.time() - t0:.1f}s")
    with open(os.path.join(a.out, "summary.txt"), "w") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
