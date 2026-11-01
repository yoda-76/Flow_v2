"""NQ Value-Area Break Momentum -- Python port of the rq_sdk strategy `VaBreakMomentum` (two versions), backtested on 1m NQ.

  v2 = the first source the user gave (bare strategy: score-scaled sizing, 1-contract floor, no exits beyond SL/TP)
  v3 = the third source (v1's idea with prop-account limits, fixed contracts, a keep-alive dummy trade and a win-memory gate)
  v1 = the second source (adds entry window, max trades/day, daily loss stop, max stop in ATR, min R:R, partial exit,
       breakeven stop, flatten before the close, honest risk sizing with no 1-contract floor)
Both run through the same engine; v2 is simply v1's features switched off (P_V2 below).

A line-for-line port: same state machine, same order of updates inside `on_bar`, same defaults. Where the Rust source
leaves the engine's behaviour open, the assumption is listed in ASSUMPTIONS below and in the summary file.
Pure standard library. Read-only over the CSV; writes only under --out. Places no orders.

    python va_break_momentum.py [--csv analysis/data/NQ_1m_latest.csv] [--days 365] [--version v1|v2|both] [--out reports/vab_nq]
"""
import sys, os, math, bisect, argparse, collections, datetime, time
from datetime import timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gb_backtest import build_et_transitions, stats, H_MS, DAY_MS   # same ET/DST rules as the Gianluca backtest

ASSUMPTIONS = """\
ASSUMPTIONS (the Rust source does not say; chosen to match the earlier Gianluca NQ backtest)
 1. Bars are 15 minutes, built from 1m (the source never names a timeframe). bar.time = bar open; ET hhmm/date from it.
 2. Market orders (entry, partial exit, flatten) fill at the bar close +/- 1 tick (adverse). A stop fills at the stop (or
    the 1m open if it gapped through) -/+ 1 tick; a target fills only once price trades 1 tick through it. Stop is
    checked before target inside the same 1m bar. Commission 4.60 USD round trip per contract (partial contracts too),
    NQ multiplier 20.
 3. SL/TP are watched on the 1m bars; strategy logic (partial, breakeven, flatten, new entries) runs at 15m bar closes,
    as on_bar does. A stop moved to breakeven takes effect from the next 1m bar.
 4. ATR is Wilder's (seed = SMA of the first 14 true ranges). Equity = starting equity + realised net P&L.
 5. on_trade(PositionClosed) fires once, when the whole position is flat, with the TOTAL net P&L of the trade (partial
    exit included). It drives prev_win/prev2_win and the daily P&L. R = total net P&L / (stop distance x multiplier x
    initial contracts).
 6. v2 has no exit besides SL/TP, so a position is held through the night/weekend (or to the end of the data).
 7. Source quirks kept on purpose: (a) swing pivot at bar(slen) is checked against bars 1..2*slen except slen, so only
    2 newer bars are compared but 3 older ones; (b) armed_bull re-arms (and clears pulled_back) on EVERY bar that closes
    above the last swing high; (c) the pullback test is 'bar overlaps the previous value area' (low <= VAH and
    high >= VAL), not 'closed back inside'; (d) the developing profile is recomputed every session bar and, overnight,
    dev_* still holds the last completed session; (e) v1's breakeven re-tests 'this bar's high >= entry + 1R', not
    'price has ever been there'; (f) v1's flatten test is hhmm >= 1550 on the bar OPEN time, so the first bar that
    triggers it is the 16:00 bar and the flatten fills at its close (16:15 ET).
"""

# v2: exactly the first source. The v1-only switches are present but turned off.
P_V2 = dict(
    session_start=930, session_end=1600, va_pct=0.70, bucket_points=10.0, vol_ma_period=20,
    atr_period=14, atr_window=200, threshold=6.0, swing_len=3, rr_target=2.0, allow_shorts=True,
    news_filter=False, risk_pct=0.5, vol_pctile_max=50.0, regime_age_max=30, best_start=930, best_end=1130,
    f5=0.50, f4=0.25, f3=0.20, f2=0.167, f1=0.05, f0=0.02,
    # v1 features, off:
    entry_start=0, entry_end=2400, max_trades_per_day=10 ** 9, daily_loss_pct=0.0, max_stop_atr=0.0, min_rr=0.0,
    max_contracts=0, max_leverage=0.0, flatten_at_end=False, flat_hhmm=1550, be_at_r=0.0, partial_at_r=0.0, partial_frac=0.5,
    use_score_scaling=True, qty_floor_one=True,
    # v3 features, off (sizing "risk_pct" = v1/v2 equity-risk sizing; "fixed" = v3 contracts / dollar budget):
    sizing="risk_pct", qty=1, risk_usd=0.0, size_by_score=False,
    dummy_enable=False, dummy_gap_days=26, dummy_hour=1000, dummy_qty=1, dummy_bracket=5.0,
    daily_loss_stop=0.0, daily_profit_stop=0.0, trail_stop_usd=0.0, max_stop_points=0.0, tp_cap_min_r=0.0,
    mem_enable=False, mem_window=100, mem_min_n=30, mem_min_after=5, mem_strength_min=3.0, mem_scale=0.16, mem_sign=1,
    fresh_account=False, reset_on_breach=False,
)
# v1: the second source's defaults
P_V1 = dict(
    P_V2, entry_start=930, entry_end=1530, max_trades_per_day=2, daily_loss_pct=1.5, max_stop_atr=1.5, min_rr=1.0,
    max_contracts=0, flatten_at_end=True, flat_hhmm=1550, be_at_r=1.0, partial_at_r=1.0, partial_frac=0.5,
    use_score_scaling=False, qty_floor_one=False,
)
# v3: the third source -- prop-account (Lucid 50K) controls, fixed contracts, keep-alive dummy trade, win-memory gate
P_V3 = dict(
    P_V2, sizing="fixed", qty=1, risk_usd=0.0, size_by_score=False,
    dummy_enable=True, dummy_gap_days=26, dummy_hour=1000, dummy_qty=1, dummy_bracket=5.0,
    entry_start=945, entry_end=1530, max_trades_per_day=3, daily_loss_stop=900.0, daily_profit_stop=0.0,
    trail_stop_usd=1750.0, max_stop_points=60.0, tp_cap_min_r=1.0, flatten_at_end=True, flat_hhmm=1555,
    mem_enable=True, mem_window=100, mem_min_n=30, mem_min_after=5, mem_strength_min=3.0, mem_scale=0.16, mem_sign=1,
    use_score_scaling=False, qty_floor_one=False,
    fresh_account=True,     # backtest convention: the prop account (equity, floor, memory, dummy clock) starts at the window start
)
P = P_V2
COST =dict(tick=0.25, slip_ticks=1, comm_rt=4.60, mult=20.0, start_equity=100_000.0)
TF_MS = 15 * 60_000


# ---- session value area (exact port of value_area()) ---------------------------------------------------------------
def value_area(bars, bucket, va_pct):
    """bars: list of (high, low, volume). Returns (vah, val, poc) or None."""
    if not bars:
        return None
    total, lo_px, hi_px = 0.0, math.inf, -math.inf
    for h, l, v in bars:
        total += v
        lo_px = min(lo_px, l)
        hi_px = max(hi_px, h)
    if total <= 0.0 or not math.isfinite(lo_px) or not math.isfinite(hi_px) or hi_px <= lo_px:
        return None
    b = max(bucket, 0.01)
    span = hi_px - lo_px
    n = min(max(math.ceil(span / b) + 1, 1), 4000)
    bsz = span / n
    vol = [0.0] * n
    for h, l, v in bars:
        i0 = min(max(math.floor((l - lo_px) / bsz), 0), n - 1)
        i1 = min(max(math.floor((h - lo_px) / bsz), 0), n - 1)
        cnt = i1 - i0 + 1
        for i in range(i0, i1 + 1):
            vol[i] += v / cnt
    poc = 0
    for i in range(1, n):
        if vol[i] > vol[poc]:
            poc = i
    target = total * va_pct
    acc, lo, hi = vol[poc], poc, poc
    while acc < target and (lo > 0 or hi + 1 < n):
        up = vol[hi + 1] if hi + 1 < n else -1.0
        dn = vol[lo - 1] if lo > 0 else -1.0
        if up >= dn:
            if hi + 1 < n:
                hi += 1
                acc += vol[hi]
            else:
                break
        else:
            lo -= 1
            acc += vol[lo]
    return lo_px + (hi + 1.0) * bsz, lo_px + lo * bsz, lo_px + (poc + 0.5) * bsz


# ---- data ----------------------------------------------------------------------------------------------------------
def load_window(path, t_from_ms):
    """1m rows with timestamp >= t_from_ms, sorted and de-duplicated. Returns parallel lists t,o,h,l,c,v."""
    rows = {}
    with open(path) as fh:
        fh.readline()
        for line in fh:
            p = line.split(",")
            t = int(p[0])
            if t >= t_from_ms:
                rows[t] = (float(p[1]), float(p[2]), float(p[3]), float(p[4]), float(p[5]))
    ts = sorted(rows)
    return ts, [rows[t][0] for t in ts], [rows[t][1] for t in ts], [rows[t][2] for t in ts], \
        [rows[t][3] for t in ts], [rows[t][4] for t in ts]


def make_bars(t, o, h, l, c, v):
    """15m bars aligned to the UTC epoch (= ET quarter hours). Each: dict(t0,o,h,l,c,v,i0,i1)."""
    bars, i, n = [], 0, len(t)
    while i < n:
        slot = t[i] // TF_MS
        j = i
        while j + 1 < n and t[j + 1] // TF_MS == slot:
            j += 1
        bars.append(dict(t0=slot * TF_MS, o=o[i], h=max(h[i:j + 1]), l=min(l[i:j + 1]), c=c[j], v=sum(v[i:j + 1]),
                         i0=i, i1=j))
        i = j + 1
    return bars


class EtClock:
    def __init__(self):
        self.tt, self.to = build_et_transitions()

    def hhmm_date(self, t_ms):
        x = t_ms + self.to[bisect.bisect_right(self.tt, t_ms) - 1]
        m = (x % DAY_MS) // 60000
        return (m // 60) * 100 + m % 60, x // DAY_MS


# ---- exits ---------------------------------------------------------------------------------------------------------
def scan_range(side, stop, tp, i_from, i_to, m1, tick, slip):
    """Scan 1m bars i_from..i_to inclusive. Returns (exit_px, index, reason) on the first hit, else None.
    The stop is checked before the target in the same 1m bar; a target needs 1 tick of trade-through."""
    t, o, h, l = m1[0], m1[1], m1[2], m1[3]
    i = i_from
    while i <= i_to:
        if side > 0:
            if l[i] <= stop:
                return min(stop, o[i]) - slip, i, "stop"
            if h[i] >= tp + tick:
                return tp, i, "target"
        else:
            if h[i] >= stop:
                return max(stop, o[i]) + slip, i, "stop"
            if l[i] <= tp - tick:
                return tp, i, "target"
        i += 1
    return None


def simulate_exit(side, stop, tp, i_from, m1, tick, slip):
    """scan_range to the end of the data; the last close if nothing is hit."""
    n = len(m1[0])
    r = scan_range(side, stop, tp, i_from, n - 1, m1, tick, slip)
    if r:
        return r
    c = m1[4][n - 1]
    return (c - slip if side > 0 else c + slip), n - 1, "data_end"


def atr_series(bars, n):
    """Wilder ATR per bar (None until n bars)."""
    out, atr = [], None
    tr = []
    for k, b in enumerate(bars):
        tr.append(b["h"] - b["l"] if k == 0 else max(b["h"] - b["l"], abs(b["h"] - bars[k - 1]["c"]), abs(b["l"] - bars[k - 1]["c"])))
        if k == n - 1:
            atr = sum(tr[:n]) / n
        elif k >= n:
            atr = (atr * (n - 1) + tr[k]) / n
        out.append(atr)
    return out


# ---- an open position ----------------------------------------------------------------------------------------------
class Position:
    """Open position with its SL/TP bracket and the v1 management block (partial, breakeven, flatten)."""

    def __init__(self, side, qty, entry, sl, tp, dist, mult, comm_rt, meta):
        self.side, self.qty0, self.qty = side, qty, qty
        self.entry, self.sl, self.tp, self.dist = entry, sl, tp, dist
        self.mult, self.comm_rt = mult, comm_rt
        self.gross = self.comm = 0.0
        self.partial_done = self.be_done = False
        self.reason, self.exit_t = None, None
        self.meta = meta

    def reduce(self, n, px):
        self.gross += self.side * (px - self.entry) * self.mult * n
        self.comm += self.comm_rt * n
        self.qty -= n

    def manage(self, bar, hhmm, p, slip):
        """Bar-close management, mirroring the source's block. Returns 'flat' when the position was closed."""
        side, closed_something = self.side, False
        if self.dist > 0:
            if p["partial_at_r"] > 0 and not self.partial_done and self.qty >= 2:
                trig = p["partial_at_r"] * self.dist
                hit = bar["h"] >= self.entry + trig if side > 0 else bar["l"] <= self.entry - trig
                if hit:
                    n = max(int(math.floor(self.qty * p["partial_frac"])), 1)
                    if n < self.qty:
                        self.reduce(n, bar["c"] - side * slip)
                        self.partial_done = closed_something = True
            if p["be_at_r"] > 0 and not self.be_done and not closed_something:
                trig = p["be_at_r"] * self.dist
                hit = bar["h"] >= self.entry + trig if side > 0 else bar["l"] <= self.entry - trig
                if hit:
                    self.sl = self.entry
                    self.be_done = True
        if p["flatten_at_end"] and hhmm >= p["flat_hhmm"]:
            self.reduce(self.qty, bar["c"] - side * slip)
            self.reason = "flatten"
            return "flat"
        return None

    def trade(self):
        risk_usd = self.dist * self.mult * self.qty0
        pnl = self.gross - self.comm
        t = dict(self.meta, side=self.side, qty=self.qty0, partial=self.partial_done, entry=self.entry,
                 stop=self.meta["stop0"], target=self.meta["target0"], dist=self.dist, reason=self.reason,
                 exit_t=self.exit_t, pnl_usd=pnl, r_gross=self.gross / risk_usd, r_net=pnl / risk_usd)
        return t


def memory_strength(outcomes, p):
    """(pw, pww, strength 0-10) over the closed-trade outcomes -- port of VaBreakMomentum::memory().
    pw = overall win rate; pww = win rate of trades right after a win; strength = 10 x direction x (pww - pw) / scale."""
    n = len(outcomes)
    if not p["mem_enable"] or n < max(p["mem_min_n"], 1):
        return 0.0, 0.0, 0.0
    pw = sum(outcomes) / n
    after = [outcomes[i] for i in range(1, n) if outcomes[i - 1] == 1]
    if len(after) < max(p["mem_min_after"], 1):
        return pw, 0.0, 0.0
    pww = sum(after) / len(after)
    d = 1.0 if p["mem_sign"] >= 0 else -1.0
    return pw, pww, min(max(10.0 * d * (pww - pw) / max(p["mem_scale"], 0.01), 0.0), 10.0)


# ---- the strategy --------------------------------------------------------------------------------------------------
def run(bars, m1, p=P_V2, cost=COST, funnel=None, fresh_at=None):
    """Returns (trades, final_equity). trades hold every closed trade, warm-up included (filter by entry_t).
    `funnel` (a Counter) collects how many setups reached the final gates and why they were turned away."""
    funnel = collections.Counter() if funnel is None else funnel
    clock = EtClock()
    tick, slip, mult = cost["tick"], cost["slip_ticks"] * cost["tick"], cost["mult"]
    m1t = m1[0]
    atrs = atr_series(bars, p["atr_period"])
    vol_win, atr_win = collections.deque(), collections.deque()
    sess_date, sess_bars = 0, []
    prev_vah = prev_val = dev_vah = dev_val = 0.0
    has_prev = has_dev = False
    swing_high = swing_low = ms_low = ms_high = None
    armed_bull = armed_bear = pulled_back = False
    regime, regime_age = 0, 0
    prev_win = prev2_win = False
    equity = cost["start_equity"]
    day_date, day_equity_start, day_pnl, day_trades = 0, equity, 0.0, 0
    pos = None
    trades = []
    slen = max(p["swing_len"], 1)
    fac_of = {5: p["f5"], 4: p["f4"], 3: p["f3"], 2: p["f2"], 1: p["f1"]}

    def finish(ps):
        nonlocal equity, prev_win, prev2_win, day_pnl, dummy_pending
        tr = ps.trade()
        equity += tr["pnl_usd"]
        trades.append(tr)
        if tr["dummy"]:                 # keep-alive noise never feeds the memory estimator or the win flags
            dummy_pending = False
            return
        day_pnl += tr["pnl_usd"]
        win = tr["pnl_usd"] > 0.0       # on_trade(PositionClosed)
        prev2_win, prev_win = prev_win, win
        outcomes.append(1 if win else 0)
        while len(outcomes) > max(p["mem_window"], 20):
            outcomes.pop(0)

    outcomes = []                       # v3 win-memory: closed-trade outcomes (1 win / 0 loss), dummy trades excluded
    init_eq, peak_eod_eq = 0.0, 0.0     # v3 trailing floor
    dummy_pending, last_trade_t = False, 0

    def memory():
        return memory_strength(outcomes, p)

    def risk_gate(eq):
        """Prop gate (port of risk_gate()): daily loss, trailing floor on EOD equity, optional daily profit stop."""
        nonlocal init_eq, peak_eod_eq, day_equity_start
        day_pnl_ = eq - day_equity_start
        floor = -math.inf
        if p["trail_stop_usd"] > 0 and init_eq > 0:
            floor = init_eq if peak_eod_eq >= init_eq + p["trail_stop_usd"] else peak_eod_eq - p["trail_stop_usd"]
        loss_limit = p["daily_loss_stop"] if p["daily_loss_stop"] > 0 else math.inf
        if p["trail_stop_usd"] > 0:
            if eq <= floor:
                if not p["reset_on_breach"]:
                    return False
                # analysis option: the evaluation is lost -> start a fresh one at the current equity and carry on
                funnel["account breaches (trailing floor hit, account reset)"] += 1
                init_eq = peak_eod_eq = day_equity_start = eq
                floor = init_eq - p["trail_stop_usd"]
            loss_limit = min(loss_limit, max(day_equity_start - floor, 0.0))
        if day_pnl_ <= -loss_limit:
            return False
        if p["daily_profit_stop"] > 0 and day_pnl_ >= p["daily_profit_stop"]:
            return False
        return True

    for bi, bar in enumerate(bars):
        close_t = bar["t0"] + TF_MS
        if last_trade_t <= 0:
            last_trade_t = bar["t0"]
        if fresh_at is not None and bar["t0"] >= fresh_at and pos is None:
            fresh_at = None                       # the prop account starts here; the strategy state stays warm
            equity = cost["start_equity"]
            init_eq = peak_eod_eq = 0.0
            day_date = 0
            outcomes.clear()
            prev_win = prev2_win = False
            last_trade_t = bar["t0"]
        # --- SL/TP inside this bar (1m resolution): the position may be gone before the bar's close
        if pos is not None:
            hit = scan_range(pos.side, pos.sl, pos.tp, bar["i0"], bar["i1"], m1, tick, slip)
            if hit:
                px, ei, why = hit
                pos.reduce(pos.qty, px)
                pos.reason = "be_stop" if (why == "stop" and pos.be_done) else why
                pos.exit_t = m1t[ei] + 60000
                finish(pos)
                pos = None
        hhmm, dnum = clock.hhmm_date(bar["t0"])
        prev = bars[bi - 1] if bi >= 1 else None

        # per-day prop state
        if day_date != dnum:
            # source quirk kept: the peak takes the OLD day's OPENING equity, not its closing equity
            if day_equity_start > 0.0 and day_equity_start > peak_eod_eq and day_date != 0:
                peak_eod_eq = day_equity_start
            day_date, day_equity_start, day_pnl, day_trades = dnum, equity, 0.0, 0
        if init_eq <= 0.0:
            init_eq = peak_eod_eq = equity

        # volume MA
        vol_win.append(bar["v"])
        cap = max(p["vol_ma_period"], 2)
        while len(vol_win) > cap:
            vol_win.popleft()
        vol_ma = sum(vol_win) / cap if len(vol_win) >= cap else None

        # ATR percentile + regime age
        atr_now, atr_pctile = 0.0, 50.0
        a = atrs[bi]
        if a is not None:
            atr_now = a
            if a > 0:
                atr_win.append(a)
                while len(atr_win) > max(p["atr_window"], 10):
                    atr_win.popleft()
                if len(atr_win) >= 20:
                    atr_pctile = 100.0 * sum(1 for x in atr_win if x <= a) / len(atr_win)
        bucket_now = 0 if atr_pctile < 33.0 else (2 if atr_pctile > 66.0 else 1)
        if bucket_now == regime:
            regime_age += 1
        else:
            regime, regime_age = bucket_now, 0

        # session volume profile
        if p["session_start"] <= hhmm < p["session_end"]:
            if sess_date != 0 and sess_date != dnum:
                va = value_area(sess_bars, p["bucket_points"], p["va_pct"])
                if va:
                    prev_vah, prev_val, _ = va
                    has_prev = True
                sess_bars = []
                pulled_back = False
            sess_date = dnum
            sess_bars.append((bar["h"], bar["l"], bar["v"]))
            va = value_area(sess_bars, p["bucket_points"], p["va_pct"])
            if va:
                dev_vah, dev_val, _ = va
                has_dev = True

        # swing pivots / break of structure
        if bi > 2 * slen:
            c_ = bars[bi - slen]
            is_high = is_low = True
            for k in range(1, 2 * slen + 1):
                if k != slen:
                    o_ = bars[bi - k]
                    if o_["h"] >= c_["h"]:
                        is_high = False
                    if o_["l"] <= c_["l"]:
                        is_low = False
            if is_high:
                swing_high = c_["h"]
            if is_low:
                swing_low = c_["l"]
        if swing_high is not None and bar["c"] > swing_high:
            armed_bull, armed_bear = True, False
            ms_low = swing_low
            pulled_back = False
        if swing_low is not None and bar["c"] < swing_low:
            armed_bear, armed_bull = True, False
            ms_high = swing_high
            pulled_back = False

        # pullback into the previous session's value area
        if has_prev and bar["l"] <= prev_vah and bar["h"] >= prev_val:
            pulled_back = True

        # invalidation
        if armed_bull and ms_low is not None and bar["l"] <= ms_low:
            armed_bull = False
        if armed_bear and ms_high is not None and bar["h"] >= ms_high:
            armed_bear = False

        # position management: partial, breakeven, session flatten
        if pos is not None and pos.manage(bar, hhmm, p, slip) == "flat":
            pos.exit_t = close_t
            finish(pos)
            pos = None

        # prop day gates
        daily_hit = (p["daily_loss_pct"] > 0.0 and day_equity_start > 0.0
                     and day_pnl <= -(day_equity_start * p["daily_loss_pct"] / 100.0))
        trades_hit = day_trades >= max(p["max_trades_per_day"], 1)
        in_window = p["entry_start"] <= hhmm < p["entry_end"]
        risk_ok = risk_gate(equity) if (p["trail_stop_usd"] > 0 or p["daily_loss_stop"] > 0
                                        or p["daily_profit_stop"] > 0) else True

        # keep-alive dummy: after dummy_gap_days without a fill, one token round trip (bracket +/- dummy_bracket)
        if (p["dummy_enable"] and not dummy_pending and last_trade_t > 0 and pos is None
                and p["dummy_hour"] <= hhmm < p["dummy_hour"] + 100
                and bar["t0"] - last_trade_t >= max(p["dummy_gap_days"], 1) * DAY_MS):
            q = max(p["dummy_qty"], 1)
            br = max(p["dummy_bracket"], 0.25)
            dummy_pending, last_trade_t = True, bar["t0"]
            pos = Position(1, q, bar["c"] + slip, bar["c"] - br, bar["c"] + br, br, mult, cost["comm_rt"],
                           dict(entry_t=close_t, signal=bar["c"], stop0=bar["c"] - br, target0=bar["c"] + br, score=0,
                                factor=1.0, hhmm=hhmm, atr_pctile=atr_pctile, regime_age=regime_age, vol_reduce=False,
                                prev_vah=prev_vah, prev_val=prev_val, equity_before=equity, dummy=True))

        # entries
        if prev is None:
            continue
        thr = p["threshold"]
        body = abs(bar["c"] - bar["o"])
        up_wick = bar["h"] - max(bar["c"], bar["o"])
        dn_wick = min(bar["c"], bar["o"]) - bar["l"]
        valid_candle = body > max(up_wick, dn_wick)
        vol_reduce = bar["v"] < prev["v"]
        vol_cond = vol_reduce or (vol_ma is not None and bar["v"] < vol_ma)
        news_block = p["news_filter"] and 815 <= hhmm < 845

        if (pos is None and not dummy_pending and not news_block and not daily_hit and not trades_hit and in_window
                and risk_ok and valid_candle and vol_cond and has_prev and has_dev and pulled_back):
            side = 0
            if (armed_bull and dev_vah > prev_vah and dev_val > prev_val
                    and bar["c"] >= prev_vah + thr and bar["c"] >= prev["h"] + thr):
                dist = bar["c"] - bar["l"]
                if dist > 0:
                    side, stop = 1, bar["l"]
                    tp = bar["c"] + p["rr_target"] * dist
                    if (swing_high is not None and bar["c"] < swing_high < tp
                            and (swing_high - bar["c"]) / dist >= p["tp_cap_min_r"]):
                        tp = swing_high
                    rr = (tp - bar["c"]) / dist
            if (side == 0 and p["allow_shorts"] and armed_bear and dev_vah < prev_vah and dev_val < prev_val
                    and bar["c"] <= prev_val - thr and bar["c"] <= prev["l"] - thr):
                dist = bar["h"] - bar["c"]
                if dist > 0:
                    side, stop = -1, bar["h"]
                    tp = bar["c"] - p["rr_target"] * dist
                    if (swing_low is not None and tp < swing_low < bar["c"]
                            and (bar["c"] - swing_low) / dist >= p["tp_cap_min_r"]):
                        tp = swing_low
                    rr = (bar["c"] - tp) / dist
            if side != 0:
                funnel["signal"] += 1
                stop_ok = p["max_stop_atr"] <= 0.0 or (atr_now > 0.0 and dist <= p["max_stop_atr"] * atr_now)
                rr_ok = p["min_rr"] <= 0.0 or rr >= p["min_rr"]
                width_ok = p["max_stop_points"] <= 0.0 or dist <= p["max_stop_points"]
                if not stop_ok:
                    funnel["blocked: stop wider than max_stop_atr x ATR"] += 1
                elif not rr_ok:
                    funnel["blocked: R:R below min_rr"] += 1
                elif not width_ok:
                    funnel["blocked: stop wider than max_stop_points"] += 1
                else:
                    score = (int(atr_pctile <= p["vol_pctile_max"]) + int(prev_win and not prev2_win)
                             + int(regime_age <= p["regime_age_max"]) + int(vol_reduce)
                             + int(p["best_start"] <= hhmm < p["best_end"]))
                    if p["sizing"] == "fixed":                      # v3: fixed contracts, or a dollar risk budget
                        fac = fac_of.get(score, p["f0"]) if p["size_by_score"] else 1.0
                        base_q = max(int(math.floor(max(p["qty"], 1) * fac + 0.5)), 1)
                        if p["risk_usd"] <= 0:
                            qty = max(base_q, 1)
                        elif dist <= 0 or mult <= 0:
                            qty = 0
                        else:
                            qty = max(int(math.floor(p["risk_usd"] / (dist * mult))), 0)
                    else:                                           # v1/v2: risk_pct of equity
                        fac = fac_of.get(score, p["f0"]) if p["use_score_scaling"] else 1.0
                        risk_usd = equity * (p["risk_pct"] / 100.0) * fac
                        qty = int(math.floor(risk_usd / (dist * mult)))
                        if p["max_contracts"] > 0:
                            qty = min(qty, p["max_contracts"])
                        if p["qty_floor_one"]:
                            qty = max(qty, 1)
                    lev_blocked = False
                    if p["max_leverage"] > 0 and qty >= 1:          # notional cap: qty * price * mult <= equity * leverage
                        lev_cap = int(math.floor(equity * p["max_leverage"] / (bar["c"] * mult)))
                        if lev_cap < 1:
                            lev_blocked, qty = True, 0
                        else:
                            qty = min(qty, lev_cap)
                    # win-memory gate: with real serial dependence, skip the trade right after the 'cold' turn
                    _, _, strength = memory()
                    cold = (prev2_win and not prev_win) if p["mem_sign"] >= 0 else (prev_win and not prev2_win)
                    skip_turn = p["mem_enable"] and strength >= p["mem_strength_min"] and cold
                    if qty < 1:
                        funnel["blocked: 1 contract exceeds the leverage cap" if lev_blocked
                               else "blocked: risk budget buys < 1 contract"] += 1
                    elif skip_turn:
                        funnel["skipped by the win-memory gate"] += 1
                        prev2_win = prev_win = False                # the skipped setup consumes the pattern
                    else:
                        funnel["entered"] += 1
                        pos = Position(side, qty, bar["c"] + side * slip, stop, tp, dist, mult, cost["comm_rt"],
                                       dict(entry_t=close_t, signal=bar["c"], stop0=stop, target0=tp, score=score,
                                            factor=fac, hhmm=hhmm, atr_pctile=atr_pctile, regime_age=regime_age,
                                            vol_reduce=vol_reduce, prev_vah=prev_vah, prev_val=prev_val,
                                            equity_before=equity, dummy=False))
                        day_trades += 1
                        last_trade_t = bar["t0"]
    if pos is not None:                      # data ended with the position open
        c = bars[-1]["c"]
        pos.reduce(pos.qty, c - pos.side * slip)
        pos.reason, pos.exit_t = "data_end", bars[-1]["t0"] + TF_MS
        finish(pos)
    return trades, equity


# ---- reporting -----------------------------------------------------------------------------------------------------
def iso(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M")


def report(trades, days, equity_start, mult):
    out = []
    P_ = out.append
    P_(stats([t["r_net"] for t in trades], "ALL (net)"))
    P_(stats([t["r_gross"] for t in trades], "ALL (gross)"))
    P_(stats([t["r_net"] for t in trades if t["side"] > 0], "long"))
    P_(stats([t["r_net"] for t in trades if t["side"] < 0], "short"))
    P_("--- by exit reason")
    for rsn in ("target", "stop", "be_stop", "flatten", "data_end"):
        P_(stats([t["r_net"] for t in trades if t["reason"] == rsn], rsn))
    P_(stats([t["r_net"] for t in trades if t["partial"]], "with partial exit"))
    P_("--- by score (0..5)")
    for sc in range(6):
        P_(stats([t["r_net"] for t in trades if t["score"] == sc], f"score {sc}"))
    P_("--- by session (ET entry)")
    P_(stats([t["r_net"] for t in trades if 930 <= t["hhmm"] < 1600], "RTH 09:30-16:00"))
    P_(stats([t["r_net"] for t in trades if not 930 <= t["hhmm"] < 1600], "outside RTH"))
    P_("--- by quarter of the window")
    if trades:
        t0, t1 = trades[0]["entry_t"], trades[-1]["entry_t"] + 1
        for q in range(4):
            a, b = t0 + (t1 - t0) * q // 4, t0 + (t1 - t0) * (q + 1) // 4
            P_(stats([t["r_net"] for t in trades if a <= t["entry_t"] < b], f"{iso(a)[:10]}..{iso(b)[:10]}"))
    usd = sum(t["pnl_usd"] for t in trades)
    qs = [t["qty"] for t in trades] or [0]
    P_("--- dollars (actual sizing)")
    P_(f"net P&L ${usd:+,.0f} on ${equity_start:,.0f} start ({usd / equity_start:+.1%}); contracts per trade "
       f"{min(qs)}..{max(qs)} (mean {sum(qs) / len(qs):.2f})")
    eq, peak, mdd = equity_start, equity_start, 0.0
    for t in trades:
        eq += t["pnl_usd"]; peak = max(peak, eq); mdd = max(mdd, 1 - eq / peak)
    clock = EtClock()
    day_pnl = collections.defaultdict(float)
    for t in trades:
        day_pnl[clock.hhmm_date(t["exit_t"] - 1)[1]] += t["pnl_usd"]
    eq, peak, mdd_usd = 0.0, 0.0, 0.0
    for t in trades:
        eq += t["pnl_usd"]; peak = max(peak, eq); mdd_usd = max(mdd_usd, peak - eq)
    if day_pnl:
        P_(f"prop view: max drawdown ${mdd_usd:,.0f}; worst day ${min(day_pnl.values()):+,.0f}; best day "
           f"${max(day_pnl.values()):+,.0f}; {len(day_pnl)} trading days with an exit; "
           f"{sum(1 for v in day_pnl.values() if v <= -900)} days at/below -$900")
    rk = [t["dist"] * mult * t["qty"] / t["equity_before"] for t in trades] or [0]
    P_(f"max drawdown on the actual-size equity curve {mdd:.1%}; trades {len(trades)} in {days} days; "
       f"initial risk per trade {min(rk):.2%}..{max(rk):.2%} of equity (mean {sum(rk) / len(rk):.2%})")
    return "\n".join(out)


def write_trades(path, trades):
    cols = ["entry_t", "exit_t", "side", "dummy", "score", "factor", "qty", "partial", "signal", "entry", "stop", "target",
            "reason", "dist", "r_gross", "r_net", "pnl_usd", "hhmm", "atr_pctile", "regime_age", "vol_reduce",
            "prev_vah", "prev_val"]
    with open(path, "w") as fh:
        fh.write(",".join(cols) + "\n")
        for t in trades:
            fh.write(",".join(str(round(t[c], 4)) if isinstance(t[c], float) else str(int(t[c]) if isinstance(t[c], bool) else t[c])
                              for c in cols) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="analysis/data/NQ_1m_latest.csv")
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--tf", type=int, default=15, help="bar timeframe in minutes (the source never says; 15 by default)")
    ap.add_argument("--from", dest="from_date", default=None, help="window start YYYY-MM-DD (UTC midnight); overrides --days")
    ap.add_argument("--warmup-days", type=int, default=30)
    ap.add_argument("--version", choices=["v1", "v2", "v3", "all", "both"], default="all")
    ap.add_argument("--no-mem", action="store_true", help="v3 ablation: switch the win-memory gate off")
    ap.add_argument("--reset-on-breach", action="store_true", help="v3: restart the prop account when the trailing floor is hit")
    ap.add_argument("--no-trail", action="store_true", help="v3 ablation: trailing floor off (daily loss and caps stay)")
    ap.add_argument("--out", default="reports/vab_nq")
    ap.add_argument("--mult", type=float, default=COST["mult"], help="point value: 20 = NQ, 2 = MNQ")
    ap.add_argument("--equity", type=float, default=COST["start_equity"])
    ap.add_argument("--comm", type=float, default=COST["comm_rt"], help="commission per contract, round trip (USD)")
    ap.add_argument("--max-contracts", type=int, default=0, help="hard cap on contracts per trade (0 = strategy default)")
    ap.add_argument("--leverage", type=float, default=0.0, help="max notional / equity (1 = no leverage, 0 = no cap)")
    a = ap.parse_args()
    COST.update(mult=a.mult, start_equity=a.equity, comm_rt=a.comm)
    t0 = time.time()
    with open(a.csv, "rb") as fh:        # last timestamp, cheaply
        fh.seek(0, 2); fh.seek(max(fh.tell() - 4096, 0))
        last_ms = int(fh.read().decode().strip().splitlines()[-1].split(",")[0])
    global TF_MS
    TF_MS = a.tf * 60_000
    rep_from = last_ms - a.days * DAY_MS
    if a.from_date:
        rep_from = int(datetime.datetime.strptime(a.from_date, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp() * 1000)
    m1 = load_window(a.csv, rep_from - a.warmup_days * DAY_MS)
    print(f"loaded {len(m1[0])} 1m bars {iso(m1[0][0])} -> {iso(m1[0][-1])} UTC ({time.time() - t0:.0f}s)", flush=True)
    bars = make_bars(*m1)
    for name, p in (("v2", P_V2), ("v1", P_V1), ("v3", P_V3)):
        if a.version not in (name, "all") and not (a.version == "both" and name != "v3"):
            continue
        if name == "v3":
            if a.reset_on_breach:
                p, name = dict(p, reset_on_breach=True), name + "_reset"
            if a.no_trail:
                p, name = dict(p, trail_stop_usd=0.0), name + "_notrail"
            if a.no_mem:
                p, name = dict(p, mem_enable=False), name + "_nomem"
        p = dict(p, max_leverage=a.leverage, **({"max_contracts": a.max_contracts} if a.max_contracts else {}))
        funnel = collections.Counter()
        trades, _ = run(bars, m1, p, COST, funnel, rep_from if p["fresh_account"] else None)
        rep_all = [t for t in trades if t["entry_t"] >= rep_from]
        rep = [t for t in rep_all if not t["dummy"]]
        dummies = [t for t in rep_all if t["dummy"]]
        out = os.path.join(a.out, name)
        os.makedirs(out, exist_ok=True)
        write_trades(os.path.join(out, "trades.csv"), rep_all)
        head = (f"VaBreakMomentum {name} on NQ, last {a.days} days ({iso(rep_from)} -> {iso(m1[0][-1])} UTC), "
                f"{len(bars)} {a.tf}m bars incl. {a.warmup_days}d warm-up ({len(trades) - len(rep)} warm-up trades not counted)\n"
                f"params {p}\ncosts {COST}\n")
        body = report(rep, a.days, COST["start_equity"], COST["mult"])
        if p["dummy_enable"]:
            body += (f"\nkeep-alive dummy trades in the window: {len(dummies)} "
                     f"(P&L ${sum(t['pnl_usd'] for t in dummies):+,.0f}, kept out of the stats above)")
        body += "\n--- setup funnel (whole run incl. warm-up; signal = every entry condition passed)\n" + \
            "\n".join(f"{k:<46} {v}" for k, v in sorted(funnel.items(), key=lambda kv: -kv[1]))
        with open(os.path.join(out, "summary.txt"), "w", encoding="utf-8") as fh:
            fh.write(head + body + "\n\n" + ASSUMPTIONS)
        print("\n" + head + body)


if __name__ == "__main__":
    main()
