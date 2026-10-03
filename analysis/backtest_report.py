#!/usr/bin/env python3
"""Renders a report from a single BacktestRunner run (flow-core's com.flow.backtest.BacktestRunner), in the
same spirit as daily_report.py's live-session report -- headline numbers, a denials/"what was blocked and why"
table, a trade sample, and (2026-10-03, the user's own requirement) daily/weekly/monthly/yearly PnL so a question
like "how did this do in July 2023" is answered by looking the row up, not by calculating it.

Reads the JSONL file BacktestRunner writes: one "backtest_run" header line, one "backtest_trade" line per
trade, one "backtest_summary" line. Does not run the engine itself and never will -- this script only reads
files, same rule every analysis/ script already follows (README "Commands you run").

Writes THREE files (2026-10-03, the user asked for csv as well as md): `<stem>.md` (the human report: config,
summary, denials, a trade sample, full yearly+monthly PnL tables -- daily/weekly are left to the CSV below, a
~1800-row daily table over 5 years is not something markdown should render inline), `<stem>_trades.csv` (every
trade, no truncation), `<stem>_periods.csv` (every day/week/month/year bucket, one `period_type` column selects
which).

Period grouping uses each trade's EXIT time, bucketed into a CT 17:00-rollover "trading day" the exact same way
daily_report.py already does for live sessions (`trading_day_of`, reused directly here rather than
reimplemented -- this Windows Python has no tz database, daily_report.py's hand-rolled DST-correct CT math is
the one place that logic should live, see working-agreements.md). Week = that trading day's Monday-start ISO
week; month/year = the trading day's own month/year. PnL in both totalR (risk-normalized, the number this whole
tool otherwise reports in) and totalPnlPoints (raw entry/exit price distance, signed by direction, in the
instrument's own price units -- no $-per-tick multiplier is modeled anywhere in this engine, so this is points/
ticks-scale, not a real dollar P&L).

Run:  python analysis/backtest_report.py <run.jsonl> [--out path] [--no-file]
"""

import argparse
import csv
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import daily_report as dr  # noqa: E402 -- reused only for its DST-correct CT trading_day_of()

UTC = timezone.utc


def load_run(path: Path) -> dict:
    header = None
    trades = []
    summary = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            t = rec.get("type")
            if t == "backtest_run":
                header = rec
            elif t == "backtest_trade":
                trades.append(rec)
            elif t == "backtest_summary":
                summary = rec
    if header is None or summary is None:
        raise ValueError(f"{path}: missing a backtest_run header or backtest_summary line -- not a BacktestRunner file")
    return {"header": header, "trades": trades, "summary": summary}


def _fmt_ms(ms):
    if ms is None:
        return "?"
    return datetime.fromtimestamp(ms / 1000, tz=UTC).strftime("%Y-%m-%d %H:%M UTC")


def _pct(n, d):
    return 0.0 if d == 0 else 100.0 * n / d


def _trade_pnl_points(t):
    return (t.get("exitPrice", 0.0) - t.get("entryPrice", 0.0)) * t.get("direction", 1)


def period_breakdown(trades: list, period: str) -> list:
    """period: 'day' | 'week' | 'month' | 'year'. One row per bucket, sorted chronologically."""
    buckets = {}
    for t in trades:
        tday = dr.trading_day_of(t["exitTimeMs"])
        if period == "day":
            key = tday.isoformat()
        elif period == "week":
            key = (tday - timedelta(days=tday.weekday())).isoformat()  # that trading day's Monday
        elif period == "month":
            key = f"{tday.year:04d}-{tday.month:02d}"
        elif period == "year":
            key = f"{tday.year:04d}"
        else:
            raise ValueError(f"unknown period {period!r}")
        b = buckets.setdefault(key, {"trades": 0, "wins": 0, "losses": 0, "totalR": 0.0, "totalPnlPoints": 0.0})
        b["trades"] += 1
        r = t.get("rMultiple", 0.0)
        if r > 0:
            b["wins"] += 1
        elif r < 0:
            b["losses"] += 1
        b["totalR"] += r
        b["totalPnlPoints"] += _trade_pnl_points(t)

    rows = []
    for key in sorted(buckets):
        b = buckets[key]
        rows.append({
            "period_type": period,
            "period": key,
            "trades": b["trades"],
            "wins": b["wins"],
            "losses": b["losses"],
            "winPct": round(_pct(b["wins"], b["wins"] + b["losses"]), 1),
            "totalR": round(b["totalR"], 4),
            "avgR": round(b["totalR"] / b["trades"], 4) if b["trades"] else 0.0,
            "totalPnlPoints": round(b["totalPnlPoints"], 4),
        })
    return rows


def _render_period_table(rows: list, title: str) -> list:
    lines = [f"## {title}", ""]
    if not rows:
        lines += ["*(no trades)*", ""]
        return lines
    lines.append("| Period | Trades | Wins | Losses | Win% | totalR | avgR | totalPnlPoints |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for r in rows:
        lines.append(f"| {r['period']} | {r['trades']} | {r['wins']} | {r['losses']} | {r['winPct']:.1f}% "
                      f"| {r['totalR']:.2f} | {r['avgR']:.2f} | {r['totalPnlPoints']:.2f} |")
    lines.append("")
    return lines


def render(run: dict) -> str:
    h, s, trades = run["header"], run["summary"], run["trades"]
    lines = []
    lines.append(f"# Backtest report -- {h.get('strategyId')}")
    lines.append("")
    lines.append(f"Source: `{h.get('csvPath')}` ({h.get('barCount')} bars, tickSize={h.get('tickSize')}), "
                  f"{_fmt_ms(h.get('firstBarMs'))} -> {_fmt_ms(h.get('lastBarMs'))}.")
    lines.append(f"Generated {_fmt_ms(h.get('generatedAtMs'))}.")
    lines.append("")
    lines.append("**Not a live/forward-test result** -- 1-minute OHLC only, no order-flow execution modeled "
                  "(backtestEnginePlan.md's resolved scope, 2026-10-03). A screening read, not a PnL estimate.")
    lines.append("")

    params = h.get("params") or {}
    risk = h.get("risk") or {}
    lines.append("## Config in force")
    lines.append("")
    lines.append("| Strategy param | Value |")
    lines.append("|---|---|")
    if not params:
        lines.append("| *(none -- defaults)* | |")
    for k, v in params.items():
        lines.append(f"| `{k}` | {v} |")
    lines.append("")
    lines.append("| Risk param | Value |")
    lines.append("|---|---|")
    for k in ("fixedContracts", "maxContracts", "dailyLossLimitTicks", "rateLimitPerMinute", "minDwellMs", "maxReversalsPerSession"):
        if k in risk:
            lines.append(f"| `{k}` | {risk[k]} |")
    lines.append("")

    n = s.get("trades", 0)
    wins = s.get("wins", 0)
    losses = s.get("losses", 0)
    lines.append("## Summary")
    lines.append("")
    lines.append("| Trades | Wins | Losses | Win% | totalR | avgR | maxDrawdownR |")
    lines.append("|---|---|---|---|---|---|---|")
    lines.append(f"| {n} | {wins} | {losses} | {_pct(wins, wins + losses):.1f}% "
                  f"| {s.get('totalR', 0):.2f} | {s.get('avgR', 0):.2f} | {s.get('maxDrawdownR', 0):.2f} |")
    lines.append("")

    lines.append("## Risk chain -- what was blocked, and the kill switch")
    lines.append("")
    lines.append("| Denied: daily loss | Denied: max reversals | Denied: rate limit | Denied: min dwell | Kill switch trips |")
    lines.append("|---|---|---|---|---|")
    lines.append(f"| {s.get('deniedDailyLoss', 0)} | {s.get('deniedMaxReversals', 0)} | {s.get('deniedRateLimit', 0)} "
                  f"| {s.get('deniedMinDwell', 0)} | {s.get('killSwitchTrips', 0)} |")
    lines.append("")

    lines += _render_period_table(period_breakdown(trades, "year"), "Yearly PnL")
    lines += _render_period_table(period_breakdown(trades, "month"), "Monthly PnL")
    lines.append("*(Daily and weekly PnL are in the companion `_periods.csv` file, not inlined here -- too many "
                  "rows for a readable markdown table over a multi-year run. Filter that file's `period_type` "
                  "column for `day` or `week`.)*")
    lines.append("")

    lines.append("## Trades")
    lines.append("")
    lines.append("*(Every trade is in the companion `_trades.csv` file. This is a sample.)*" if n > 100 else "")
    lines.append("")
    if not trades:
        lines.append("*(none)*")
    else:
        sample_cap = 50
        shown = trades if len(trades) <= 2 * sample_cap else trades[:sample_cap] + trades[-sample_cap:]
        truncated = len(trades) > 2 * sample_cap
        lines.append("| # | Entry time | Dir | Entry | Stop | Target | Exit time | Exit | Reason | R |")
        lines.append("|---|---|---|---|---|---|---|---|---|---|")
        shown_count = 0
        for i, t in enumerate(shown):
            idx = i + 1 if not truncated or i < sample_cap else n - (len(shown) - i) + 1
            dirn = "LONG" if t.get("direction", 0) > 0 else "SHORT"
            lines.append(f"| {idx} | {_fmt_ms(t.get('entryTimeMs'))} | {dirn} | {t.get('entryPrice'):.2f} "
                          f"| {t.get('stopPrice'):.2f} | {t.get('targetPrice'):.2f} | {_fmt_ms(t.get('exitTimeMs'))} "
                          f"| {t.get('exitPrice'):.2f} | {t.get('exitReason')} | {t.get('rMultiple'):+.2f} |")
            shown_count += 1
            if truncated and i == sample_cap - 1:
                lines.append(f"| ... | *({n - 2 * sample_cap} trades omitted)* | | | | | | | | |")
        lines.append("")
        lines.append(f"({shown_count} of {n} trades shown.)" if truncated else f"({n} trades.)")

    return "\n".join(lines) + "\n"


def write_trades_csv(trades: list, out: Path):
    out.parent.mkdir(parents=True, exist_ok=True)
    fields = ["entryTimeMs", "entryTime", "direction", "entryPrice", "stopPrice", "targetPrice",
              "exitTimeMs", "exitTime", "exitPrice", "exitReason", "rMultiple", "pnlPoints"]
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for t in trades:
            w.writerow({
                "entryTimeMs": t.get("entryTimeMs"), "entryTime": _fmt_ms(t.get("entryTimeMs")),
                "direction": t.get("direction"), "entryPrice": t.get("entryPrice"),
                "stopPrice": t.get("stopPrice"), "targetPrice": t.get("targetPrice"),
                "exitTimeMs": t.get("exitTimeMs"), "exitTime": _fmt_ms(t.get("exitTimeMs")),
                "exitPrice": t.get("exitPrice"), "exitReason": t.get("exitReason"),
                "rMultiple": t.get("rMultiple"), "pnlPoints": round(_trade_pnl_points(t), 4),
            })


def write_periods_csv(trades: list, out: Path):
    out.parent.mkdir(parents=True, exist_ok=True)
    fields = ["period_type", "period", "trades", "wins", "losses", "winPct", "totalR", "avgR", "totalPnlPoints"]
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for period in ("day", "week", "month", "year"):
            for row in period_breakdown(trades, period):
                w.writerow(row)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("run", help="path to a BacktestRunner .jsonl output file")
    ap.add_argument("--out", help="write the .md here too (default reports/backtest/<same stem>.md); "
                                   "_trades.csv/_periods.csv are written alongside it, same stem")
    ap.add_argument("--no-file", action="store_true", help="print only, write no files")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    run_path = Path(args.run)
    if not run_path.is_file():
        print(f"no such file: {run_path}", file=sys.stderr)
        return 2
    run = load_run(run_path)
    text = render(run)
    print(text)
    if not args.no_file:
        out_md = Path(args.out) if args.out else Path("reports/backtest") / f"{run_path.stem}.md"
        out_md.parent.mkdir(parents=True, exist_ok=True)
        out_md.write_text(text, encoding="utf-8")
        stem_dir, stem = out_md.parent, out_md.stem
        out_trades = stem_dir / f"{stem}_trades.csv"
        out_periods = stem_dir / f"{stem}_periods.csv"
        write_trades_csv(run["trades"], out_trades)
        write_periods_csv(run["trades"], out_periods)
        print(f"\n(written to {out_md}, {out_trades}, {out_periods})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
