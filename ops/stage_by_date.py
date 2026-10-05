#!/usr/bin/env python3
"""Arranges the files to back up into one folder per TRADING DAY (YYYY-MM-DD, same label daily_report.py uses),
so the Google Drive backup can be pulled by date range. Built as hard links where the OS allows it, so staging
costs no extra disk space; the originals are never moved or changed.

Layout produced (under --staging, default gdrive_staging/):
  <YYYY-MM-DD>/reports/<file>                  daily reports (reports/YYYY-MM-DD.md)
  <YYYY-MM-DD>/logs/<session>/decisions.jsonl  decision journals, dated by the session's own start time
  <YYYY-MM-DD>/data/<construct>/<symbol>/<sessionId>.jsonl   recorded data
  _undated/...                                 anything without a date (e.g. reports/backtest/)

Run:  python ops/stage_by_date.py [--flow-home .] [--mode nightly|liquidity] [--staging DIR]
"""

import argparse
import os
import re
import shutil
import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "analysis"))
import daily_report as dr  # noqa: E402 -- the one place the 17:00 CT trading-day rule lives

SESSION_MS = re.compile(r"_(\d{13})_inst")
DATE_STEM = re.compile(r"^\d{4}-\d{2}-\d{2}$")
EPOCH = date(1970, 1, 1)


def session_label(session_name: str):
    m = SESSION_MS.search(session_name)
    return dr.trading_day_of(int(m.group(1))).isoformat() if m else None


def data_label(session_id: int) -> str:
    # data files are named by sessionId = the calendar date a session STARTED (17:00 CT); the trading day that
    # session ends on is the next day, which is the label daily_report.py uses for the same session.
    return (EPOCH + timedelta(days=session_id) + timedelta(days=1)).isoformat()


def _place(src: Path, dst: Path):
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def plan(flow_home: Path, mode: str):
    """Yields (source_path, path_relative_to_staging). mode=nightly: everything except liquidity_map;
    mode=liquidity: only liquidity_map (large, weekly)."""
    reports = flow_home / "reports"
    if mode == "nightly" and reports.is_dir():
        for f in sorted(reports.iterdir()):
            if f.is_file():
                label = f.stem if DATE_STEM.match(f.stem) else "_undated"
                yield f, Path(label) / "reports" / f.name
        backtest = reports / "backtest"
        if backtest.is_dir():
            for f in sorted(backtest.rglob("*")):
                if f.is_file():
                    yield f, Path("_undated") / "reports" / "backtest" / f.relative_to(backtest)

    logs = flow_home / "logs"
    if mode == "nightly" and logs.is_dir():
        for session in sorted(p for p in logs.iterdir() if p.is_dir()):
            dec = session / "decisions.jsonl"
            if not dec.is_file():
                continue
            label = session_label(session.name) or "_undated"
            yield dec, Path(label) / "logs" / session.name / "decisions.jsonl"

    data = flow_home / "data"
    if data.is_dir():
        for f in sorted(data.rglob("*.jsonl")):
            rel = f.relative_to(data)
            is_liquidity = bool(rel.parts) and rel.parts[0] == "liquidity_map"
            if is_liquidity != (mode == "liquidity"):
                continue
            m = re.fullmatch(r"(-?\d+)\.jsonl", f.name)
            label = data_label(int(m.group(1))) if m else "_undated"
            yield f, Path(label) / "data" / rel


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--flow-home", default=".")
    ap.add_argument("--mode", choices=["nightly", "liquidity"], default="nightly")
    ap.add_argument("--staging", help="default gdrive_staging_<mode>/")
    args = ap.parse_args(argv)

    flow_home = Path(args.flow_home).resolve()
    staging = flow_home / (args.staging or f"gdrive_staging_{args.mode}")
    if staging.exists():
        shutil.rmtree(staging)  # removes only the links/copies in staging, never the originals
    staging.mkdir(parents=True)

    count = 0
    for src, rel in plan(flow_home, args.mode):
        _place(src, staging / rel)
        count += 1
    print(f"staged {count} files into {staging}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
