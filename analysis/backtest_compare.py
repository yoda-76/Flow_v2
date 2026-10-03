#!/usr/bin/env python3
"""One comparison table across several already-run BacktestRunner outputs -- the user's own instruction,
2026-10-03: "multi-strategy/parameter-sweep comparison tooling can use the same reports instead of doing the
backtest again for each strategy." Reads each run's .jsonl file (never re-runs the engine), same convention as
`working-agreements.md` 5: "report headline numbers... plus a running comparison table." Formalizes what D-80
did by hand (a markdown table typed in by hand into decisions.md) into a repeatable script.

Run:  python analysis/backtest_compare.py run1.jsonl run2.jsonl ... [--out path] [--no-file]
  or: python analysis/backtest_compare.py --glob "analysis/data/backtest_runs/*.jsonl" [--out path]
"""

import argparse
import glob
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backtest_report as br  # noqa: E402


def _pct(n, d):
    return 0.0 if d == 0 else 100.0 * n / d


def render_comparison(runs: list[dict]) -> str:
    lines = []
    lines.append("# Backtest comparison")
    lines.append("")
    lines.append(f"{len(runs)} run(s) compared, newest-first by generation time.")
    lines.append("")
    lines.append("| Strategy | Params | Bars | Trades | Win% | totalR | avgR | maxDrawdownR | Denials (loss/rev/rate/dwell) | KillSwitch |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|")
    ordered = sorted(runs, key=lambda r: r["header"].get("generatedAtMs", 0), reverse=True)
    for run in ordered:
        h, s = run["header"], run["summary"]
        params = h.get("params") or {}
        params_str = ", ".join(f"{k}={v}" for k, v in params.items()) or "(defaults)"
        wins, losses = s.get("wins", 0), s.get("losses", 0)
        lines.append(
            f"| {h.get('strategyId')} | {params_str} | {h.get('barCount')} | {s.get('trades', 0)} "
            f"| {_pct(wins, wins + losses):.1f}% | {s.get('totalR', 0):.2f} | {s.get('avgR', 0):.2f} "
            f"| {s.get('maxDrawdownR', 0):.2f} "
            f"| {s.get('deniedDailyLoss', 0)}/{s.get('deniedMaxReversals', 0)}/{s.get('deniedRateLimit', 0)}/{s.get('deniedMinDwell', 0)} "
            f"| {s.get('killSwitchTrips', 0)} |")
    lines.append("")
    lines.append("Each row's full trade list and config is in its own report "
                  "(`python analysis/backtest_report.py <the .jsonl file>`).")
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("runs", nargs="*", help="BacktestRunner .jsonl files to compare")
    ap.add_argument("--glob", help="shell-style glob instead of listing files one by one")
    ap.add_argument("--out", help="write here too (default reports/backtest/comparison.md)")
    ap.add_argument("--no-file", action="store_true", help="print only, write no file")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    paths = [Path(p) for p in args.runs]
    if args.glob:
        paths += [Path(p) for p in glob.glob(args.glob)]
    if not paths:
        print("no run files given -- pass paths or --glob", file=sys.stderr)
        return 2

    runs = []
    for p in paths:
        if not p.is_file():
            print(f"skipping missing file: {p}", file=sys.stderr)
            continue
        try:
            runs.append(br.load_run(p))
        except ValueError as e:
            print(f"skipping {p}: {e}", file=sys.stderr)
    if not runs:
        print("no valid run files found", file=sys.stderr)
        return 2

    text = render_comparison(runs)
    print(text)
    if not args.no_file:
        out = Path(args.out) if args.out else Path("reports/backtest/comparison.md")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
        print(f"\n(written to {out})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
