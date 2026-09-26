#!/usr/bin/env python3
"""Trade viewer: one page of market context around ONE trade (todo.md Phase 2).

The daily report (analysis/daily_report.py) says THAT a trade happened. This
shows what the market looked like around it, from the per-second data the
recorder keeps under data/ (footprint, VWAP, liquidity map, and -- once they
have been recorded -- big trades and market structure), so "why did it fail
here?" starts from a page instead of raw JSONL.

Trade numbers are the report's own (`#` column): run the report, pick a
number, then

  python analysis/trade_view.py --date 2026-09-29            # list that day's trades
  python analysis/trade_view.py --date 2026-09-29 --trade 3  # the page for trade 3
  python analysis/trade_view.py --trade 3 --before 600 --after 600 --ladder-ticks 15

A page has: the trade; how far it went for and against you (MFE/MAE) against
its target and stop; a time table (price, volume, delta, VWAP); the order flow
in the minute before entry and during the trade as a price ladder; the resting
liquidity at entry (and how much sat in the way of the target / behind the
stop); big trades and market-structure changes if recorded; and an explicit
note for every construct that has no data, rather than a silent blank.

Reading the flow: `ask` = volume traded AT the ask (buyers lifting offers),
`bid` = volume traded AT the bid (sellers hitting), `delta` = ask - bid
(positive = buyers were the aggressors).

Needs a trade with fill times (journals from D-94 onward). Prices are in
points (@GC: 1 tick = 0.1). Same time conventions as the report: US Central,
plus the India-clock time on the trade header.
"""

import argparse
import json
import re
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import daily_report as dr  # noqa: E402

_T_RE = re.compile(r'^\{"t":(\d+)')
BAR_WIDTH = 20


# ---------------------------------------------------------------------------
# Loading recorded data
# ---------------------------------------------------------------------------


def session_ids(t0, t1):
    """Data files are named by the session (17:00 CT start) they belong to; a window can span two."""
    a = dr.session_id_for_day(dr.trading_day_of(t0))
    b = dr.session_id_for_day(dr.trading_day_of(t1))
    return sorted({a, b})


def load_construct(data_root: Path, name: str, t0: int, t1: int):
    """Lines of data/<name>/<sid>.jsonl with t in [t0, t1], in order. Streams the file, parses only lines
    in range (the liquidity map is hundreds of MB a day) and stops at the first line past t1."""
    out, found = [], False
    d = data_root / name
    for sid in session_ids(t0, t1):
        f = d / f"{sid}.jsonl"
        if not f.exists():
            continue
        found = True
        with f.open(encoding="utf-8") as fh:
            for line in fh:
                m = _T_RE.match(line)
                if not m:
                    continue  # the header, or a malformed line
                t = int(m.group(1))
                if t < t0:
                    continue
                if t > t1:
                    break
                try:
                    out.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return out, found


def last_at_or_before(lines, t):
    best = None
    for o in lines:
        if o["t"] <= t:
            best = o
        else:
            break
    return best


# ---------------------------------------------------------------------------
# Analysis
# ---------------------------------------------------------------------------


def tick_of(px, tick):
    return round(px / tick)


def flow_totals(candles):
    ask = sum(r["a"] for c in candles for r in c["rows"])
    bid = sum(r["b"] for c in candles for r in c["rows"])
    return {"vol": ask + bid, "ask": ask, "bid": bid, "delta": ask - bid}


def ladder(candles, tick):
    """Aggregate footprint rows over candles: {tick_index: [ask, bid]}."""
    rows = {}
    for c in candles:
        for r in c["rows"]:
            k = tick_of(r["p"], tick)
            cell = rows.setdefault(k, [0, 0])
            cell[0] += r["a"]
            cell[1] += r["b"]
    return rows


def excursions(trade, candles):
    """MFE/MAE (points) between entry and exit from candle highs/lows, with when each happened."""
    if not candles or trade["entry_px"] is None:
        return None
    d = 1 if trade["side"] == "LONG" else -1
    entry = trade["entry_px"]
    best = worst = None
    for c in candles:
        fav = (c["h"] - entry) if d == 1 else (entry - c["l"])
        adv = (entry - c["l"]) if d == 1 else (c["h"] - entry)
        if best is None or fav > best[0]:
            best = (fav, c["t"])
        if worst is None or adv > worst[0]:
            worst = (adv, c["t"])
    res = {"mfe": max(best[0], 0.0), "mfe_t": best[1], "mae": max(worst[0], 0.0), "mae_t": worst[1]}
    if trade["target"] is not None:
        dist = (trade["target"] - entry) * d
        res["target_dist"] = dist
        res["target_pct"] = None if dist <= 0 else 100.0 * res["mfe"] / dist
    if trade["stop"] is not None:
        dist = (entry - trade["stop"]) * d
        res["stop_dist"] = dist
        res["stop_pct"] = None if dist <= 0 else 100.0 * res["mae"] / dist
    return res


def book_in_the_way(snap, trade, tick):
    """Resting size between entry and target (opposing side) and between stop and entry (supporting side)."""
    if snap is None or trade["entry_px"] is None:
        return None
    d = 1 if trade["side"] == "LONG" else -1
    e = tick_of(trade["entry_px"], tick)
    opposing = snap["asks"] if d == 1 else snap["bids"]   # what a long has to trade through
    supporting = snap["bids"] if d == 1 else snap["asks"]
    out = {"target_blocking": None, "stop_support": None}
    if trade["target"] is not None:
        tg = tick_of(trade["target"], tick)
        lo, hi = sorted((e, tg))
        out["target_blocking"] = sum(r["s"] for r in opposing if lo <= tick_of(r["p"], tick) <= hi)
    if trade["stop"] is not None:
        sp = tick_of(trade["stop"], tick)
        lo, hi = sorted((e, sp))
        out["stop_support"] = sum(r["s"] for r in supporting if lo <= tick_of(r["p"], tick) <= hi)
    return out


def pick_bucket(span_ms):
    for b in (10, 15, 30, 60, 120, 300, 600):
        if span_ms / 1000 / b <= 40:
            return b * 1000
    return 900_000


def bucketize(candles, vwap_lines, bucket_ms, t0):
    out = {}
    for c in candles:
        k = (c["t"] - t0) // bucket_ms
        b = out.setdefault(k, {"t": t0 + k * bucket_ms, "o": c["o"], "h": c["h"], "l": c["l"], "c": c["c"], "ask": 0, "bid": 0})
        b["h"] = max(b["h"], c["h"])
        b["l"] = min(b["l"], c["l"])
        b["c"] = c["c"]
        for r in c["rows"]:
            b["ask"] += r["a"]
            b["bid"] += r["b"]
    rows = []
    for k in sorted(out):
        b = out[k]
        v = last_at_or_before(vwap_lines, b["t"] + bucket_ms - 1)
        b["vwap"] = v["vwap"] if v else None
        rows.append(b)
    return rows


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------


def _bar(x, mx):
    return "█" * (0 if mx <= 0 else max(1 if x > 0 else 0, round(BAR_WIDTH * x / mx)))


def _mark(price, trade, tick):
    tags = []
    k = tick_of(price, tick)
    if trade["entry_px"] is not None and k == tick_of(trade["entry_px"], tick):
        tags.append("ENTRY")
    if trade.get("exit_px") is not None and k == tick_of(trade["exit_px"], tick):
        tags.append("EXIT")
    if trade["stop"] is not None and k == tick_of(trade["stop"], tick):
        tags.append("stop")
    if trade["target"] is not None and k == tick_of(trade["target"], tick):
        tags.append("target")
    return " ◀ " + "/".join(tags) if tags else ""


def render_ladder(rows, trade, tick, lo_k, hi_k, title):
    L = [title, ""]
    if not rows:
        return L + ["No trades printed in this period.", ""]
    mx = max(a + b for a, b in rows.values())
    # Trim the empty rows at both ends: keep rows that traded plus the trade's own price lines, +1 tick margin.
    keep = [k for k in rows if lo_k <= k <= hi_k and sum(rows[k]) > 0]
    for px in (trade["entry_px"], trade.get("exit_px"), trade["stop"], trade["target"]):
        if px is not None and lo_k <= tick_of(px, tick) <= hi_k:
            keep.append(tick_of(px, tick))
    if keep:
        lo_k, hi_k = max(lo_k, min(keep) - 1), min(hi_k, max(keep) + 1)
    L.append("| price | bid (sold) | ask (bought) | delta | volume |  |")
    L.append("|--:|--:|--:|--:|---|---|")
    for k in range(hi_k, lo_k - 1, -1):
        a, b = rows.get(k, [0, 0])
        px = k * tick
        L.append(f"| {px:.1f} | {b:g} | {a:g} | {a - b:+g} | {_bar(a + b, mx)} |{_mark(px, trade, tick)} |")
    L.append("")
    return L


def render(trade, num, day, ctx, opts):
    """ctx: dict with candles, vwap, liq, big, bars, ms (lists) + found flags."""
    tick = opts["tick"]
    e_t, x_t = trade["entry_t"], trade["exit_t"]
    d = 1 if trade["side"] == "LONG" else -1
    L = []
    L.append(f"# Trade {num} — {trade['side']} {trade['qty']} on {day.isoformat()}")
    L.append("")
    exit_txt = "still open" if x_t is None else f"{dr.fmt_ct(x_t)} @ {dr._num(trade['exit_px'])} ({trade['exit_via']})"
    L.append(f"- **Entry:** {dr.fmt_ct(e_t)} ({dr.fmt_ist(e_t)}) @ {dr._num(trade['entry_px'])} — {trade['reason']}")
    L.append(f"- **Exit:** {exit_txt}")
    if trade["stop"] is not None:
        L.append(f"- **Bracket:** stop {dr._num(trade['stop'])} · target {dr._num(trade['target'])}")
    if trade["points"] is not None:
        L.append(f"- **Result:** {trade['points']:+.1f} pts, gross {dr._money(trade['gross'])} "
                 f"(held {dr.fmt_dur(x_t - e_t)})")
    L.append("")

    candles = [c for c in ctx["footprint"] if e_t <= c["t"] <= (x_t if x_t else 10**18)]
    pre = [c for c in ctx["footprint"] if e_t - opts["flow_secs"] * 1000 <= c["t"] < e_t]
    post = [c for c in ctx["footprint"] if x_t and x_t < c["t"] <= x_t + opts["flow_secs"] * 1000]

    # ---- how the trade travelled
    L.append("## How the trade travelled")
    L.append("")
    ex = excursions(trade, candles)
    if ex is None:
        L.append("No footprint candles between entry and exit — cannot measure the excursion "
                 "(recorder off, or no trades printed).")
    else:
        L.append(f"- **Best it got (MFE):** {ex['mfe']:+.1f} pts in your favour at {dr.fmt_ct(ex['mfe_t'])} "
                 f"({dr.fmt_dur(ex['mfe_t'] - e_t)} in)")
        L.append(f"- **Worst (MAE):** {ex['mae']:.1f} pts against you at {dr.fmt_ct(ex['mae_t'])} "
                 f"({dr.fmt_dur(ex['mae_t'] - e_t)} in)")
        if ex.get("target_pct") is not None:
            L.append(f"- Went **{ex['target_pct']:.0f}%** of the way to the target ({ex['target_dist']:.1f} pts away).")
        if ex.get("stop_pct") is not None:
            L.append(f"- Went **{ex['stop_pct']:.0f}%** of the way to the stop ({ex['stop_dist']:.1f} pts away).")
        L.append("- Measured from 1-second candle highs/lows, so it is accurate to about a second.")
    v_in = last_at_or_before(ctx["vwap"], e_t)
    if v_in and trade["entry_px"] is not None:
        L.append(f"- **Entry vs VWAP:** {trade['entry_px'] - v_in['vwap']:+.1f} pts (VWAP {v_in['vwap']:.2f}) — "
                 f"{'above' if trade['entry_px'] > v_in['vwap'] else 'below'} VWAP, trade was "
                 f"{'with' if (trade['entry_px'] > v_in['vwap']) == (d == 1) else 'against'} that side.")
    elif not ctx["found"]["vwap"]:
        L.append("- No VWAP data recorded for this period.")
    L.append("")

    # ---- flow summary
    L.append("## Order flow: before, during, after")
    L.append("")
    if not ctx["found"]["footprint"]:
        L.append("**No footprint data was recorded for this period** (data/footprint has no file for this session).")
        L.append("")
    else:
        L.append("| period | volume | bought (ask) | sold (bid) | delta |")
        L.append("|---|--:|--:|--:|--:|")
        for label, cs in ((f"{opts['flow_secs']}s before entry", pre), ("during the trade", candles),
                          (f"{opts['flow_secs']}s after exit", post)):
            if cs:
                f = flow_totals(cs)
                L.append(f"| {label} | {f['vol']:g} | {f['ask']:g} | {f['bid']:g} | {f['delta']:+g} |")
            else:
                L.append(f"| {label} | — | — | — | — |")
        L.append("")
        L.append("Delta in your direction means the aggressors were on your side; against it means the trade "
                 f"was entered into pressure. For this **{trade['side'].lower()}**, positive delta is "
                 f"{'good' if d == 1 else 'bad'}.")
        L.append("")

    # ---- time table
    L.append("## Price over time")
    L.append("")
    w0 = e_t - opts["before"] * 1000
    w1 = (x_t if x_t else e_t) + opts["after"] * 1000
    win = [c for c in ctx["footprint"] if w0 <= c["t"] <= w1]
    if not win:
        L.append("No footprint candles in the window.")
    else:
        bucket = pick_bucket(w1 - w0)
        L.append(f"{dr.fmt_dur(bucket)} buckets from {dr.fmt_ct(w0)}; buckets with no trades are skipped "
                 f"(the recorder writes a candle only for a second that had trades).")
        L.append("")
        L.append("| time (CT) | open | high | low | close | volume | delta | VWAP |  |")
        L.append("|---|--:|--:|--:|--:|--:|--:|--:|---|")
        for b in bucketize(win, ctx["vwap"], bucket, w0):
            note = []
            if b["t"] <= e_t < b["t"] + bucket:
                note.append("ENTRY")
            if x_t and b["t"] <= x_t < b["t"] + bucket:
                note.append("EXIT")
            vw = "—" if b["vwap"] is None else f"{b['vwap']:.2f}"
            L.append(f"| {dr.fmt_ct(b['t'])[6:]} | {b['o']:.1f} | {b['h']:.1f} | {b['l']:.1f} | {b['c']:.1f} | "
                     f"{b['ask'] + b['bid']:g} | {b['ask'] - b['bid']:+g} | {vw} | {'◀ ' + '/'.join(note) if note else ''} |")
    L.append("")

    # ---- ladders
    if ctx["found"]["footprint"] and trade["entry_px"] is not None:
        e_k = tick_of(trade["entry_px"], tick)
        n = opts["ladder_ticks"]
        L += render_ladder(ladder(pre, tick), trade, tick, e_k - n, e_k + n,
                           f"## Footprint ladder — the {opts['flow_secs']}s before entry")
        if candles:
            rows = ladder(candles, tick)
            lo = min([e_k - 2] + list(rows)) if rows else e_k - n
            hi = max([e_k + 2] + list(rows)) if rows else e_k + n
            lo, hi = max(lo, e_k - 3 * n), min(hi, e_k + 3 * n)
            L += render_ladder(rows, trade, tick, lo, hi, "## Footprint ladder — during the trade")

    # ---- liquidity
    L.append("## Resting liquidity at entry")
    L.append("")
    snap = last_at_or_before(ctx["liquidity_map"], e_t)
    if not ctx["found"]["liquidity_map"]:
        L.append("**No liquidity-map data was recorded for this period.**")
        L.append("")
    elif snap is None:
        L.append("No liquidity snapshot at or before the entry in the window.")
        L.append("")
    else:
        age = e_t - snap["t"]
        L.append(f"Snapshot {dr.fmt_ct(snap['t'])} ({dr.fmt_dur(age)} before entry). Best bid "
                 f"{dr._num(snap.get('bid'))}, best ask {dr._num(snap.get('ask'))}. Sizes are resting lots.")
        L.append("")
        w = book_in_the_way(snap, trade, tick)
        if w:
            if w["target_blocking"] is not None:
                L.append(f"- **In the way of the target:** {w['target_blocking']:g} lots resting between entry and target "
                         f"on the side you have to trade through.")
            if w["stop_support"] is not None:
                L.append(f"- **Behind the stop:** {w['stop_support']:g} lots resting between entry and stop on your side "
                         f"(support that has to be eaten before the stop is reached).")
            L.append("")
        if trade["entry_px"] is not None:
            e_k = tick_of(trade["entry_px"], tick)
            n = opts["ladder_ticks"]
            bids = {tick_of(r["p"], tick): r["s"] for r in snap["bids"]}
            asks = {tick_of(r["p"], tick): r["s"] for r in snap["asks"]}
            mx = max([1] + [bids.get(k, 0) + asks.get(k, 0) for k in range(e_k - n, e_k + n + 1)])
            L.append("| price | bids | asks |  |")
            L.append("|--:|--:|--:|---|")
            for k in range(e_k + n, e_k - n - 1, -1):
                b, a = bids.get(k), asks.get(k)
                L.append(f"| {k * tick:.1f} | {'' if b is None else f'{b:g}'} | {'' if a is None else f'{a:g}'} | "
                         f"{_bar((b or 0) + (a or 0), mx)}{_mark(k * tick, trade, tick)} |")
            L.append("")
            big_b = sorted(snap["bids"], key=lambda r: -r["s"])[:3]
            big_a = sorted(snap["asks"], key=lambda r: -r["s"])[:3]
            L.append("Largest walls in the recorded window: bids " + ", ".join(f"{r['p']:.1f} ({r['s']:g})" for r in big_b)
                     + "; asks " + ", ".join(f"{r['p']:.1f} ({r['s']:g})" for r in big_a) + ".")
            L.append("")

    # ---- optional constructs
    L.append("## Big trades during the trade")
    L.append("")
    if not ctx["found"]["big_trades"]:
        L.append("No big-trade data recorded for this period (the recorder writes it only once that construct "
                 "has been deployed and a session has run).")
    else:
        rows = [t for rec in ctx["big_trades"] for t in rec["trades"] if e_t - opts["before"] * 1000 <= t["ts"] <= (x_t or e_t) + opts["after"] * 1000]
        if not rows:
            L.append("None in the window.")
        for t in rows[:40]:
            tag = "  ◀ during trade" if e_t <= t["ts"] <= (x_t or 10**18) else ""
            L.append(f"- {dr.fmt_ct(t['ts'])[6:]} {'BUY ' if t['ask'] else 'SELL'} {t['size']:g} @ {t['p']:.1f}{tag}")
        if len(rows) > 40:
            L.append(f"- … {len(rows) - 40} more")
    L.append("")
    L.append("## Market structure")
    L.append("")
    if not ctx["found"]["market_structure"]:
        L.append("No market-structure data recorded for this period.")
    else:
        rows = [r for r in ctx["market_structure"] if r.get("type") != "warm_start"]
        if not rows:
            L.append("No state changes in the window.")
        for r in rows:
            L.append(f"- {dr.fmt_ct(r['t'])[6:]} @ {r.get('price')}: {'; '.join(r.get('changes', []))} "
                     f"(trend {r.get('trend')}, pullback {r.get('pullback')})")
    L.append("")
    return "\n".join(L)


# ---------------------------------------------------------------------------


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--logs", default="logs")
    ap.add_argument("--data", default="data")
    ap.add_argument("--date", help="trading day YYYY-MM-DD (default: the day of the newest journal record)")
    ap.add_argument("--trade", type=int, help="trade number from the daily report (omit to list the day's trades)")
    ap.add_argument("--before", type=int, default=300, help="seconds of price history before entry (default 300)")
    ap.add_argument("--after", type=int, default=300, help="seconds after exit (default 300)")
    ap.add_argument("--flow-secs", type=int, default=60, help="window for the before/after order-flow totals (default 60)")
    ap.add_argument("--ladder-ticks", type=int, default=12, help="ticks each side of entry in the ladders (default 12)")
    ap.add_argument("--tick", type=float, default=0.1, help="tick size in points (default 0.1, @GC)")
    ap.add_argument("--out")
    ap.add_argument("--no-file", action="store_true")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    logs = Path(args.logs)
    if not logs.exists():
        print(f"no logs directory at {logs}", file=sys.stderr)
        return 2
    sessions = dr.load_sessions(logs)
    if args.date:
        day = date.fromisoformat(args.date)
    else:
        nt = dr.newest_time(sessions)
        if nt is None:
            print("no timestamped journal records found", file=sys.stderr)
            return 2
        day = dr.trading_day_of(nt)
    trades = dr.build_model(sessions, day, Path(args.data))["trades"]

    if args.trade is None:
        print(f"Trades on trading day {day.isoformat()} (numbers match the daily report):\n")
        if not trades:
            print("  none")
        for i, t in enumerate(trades, 1):
            pts = "open" if t["points"] is None else f"{t['points']:+.1f} pts"
            print(f"  {i}. {dr.fmt_ct(t['entry_t'])}  {t['side']:5} @ {dr._num(t['entry_px'])}  → {t['exit_via']}  {pts}")
        print("\nAdd --trade N for the page.")
        return 0

    if not 1 <= args.trade <= len(trades):
        print(f"no trade {args.trade} on {day.isoformat()} (there are {len(trades)}); run without --trade to list them",
              file=sys.stderr)
        return 2
    trade = trades[args.trade - 1]
    if not trade["entry_t"] or trade["entry_px"] is None:
        print("this trade has no fill time/price (its journal predates the D-94 order_fill records), "
              "so there is nothing to anchor the market data to", file=sys.stderr)
        return 2

    e_t = trade["entry_t"]
    x_t = trade["exit_t"]
    span_end = (x_t if x_t else e_t) + max(args.after, args.flow_secs) * 1000
    span_start = e_t - max(args.before, args.flow_secs) * 1000
    data = Path(args.data)
    ctx = {"found": {}}
    for name in ("footprint", "vwap", "liquidity_map", "big_trades", "market_structure"):
        lines, found = load_construct(data, name, span_start, span_end)
        ctx[name] = lines
        ctx["found"][name] = found
    opts = {"before": args.before, "after": args.after, "flow_secs": args.flow_secs,
            "ladder_ticks": args.ladder_ticks, "tick": args.tick}
    text = render(trade, args.trade, day, ctx, opts)
    print(text)
    if not args.no_file:
        out = Path(args.out) if args.out else Path("reports") / f"trade_{day.isoformat()}_{args.trade}.md"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
        print(f"\n(written to {out})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
