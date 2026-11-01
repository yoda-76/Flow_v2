"""Extract strategy decision-log records into one CSV per event type (stdlib only, read-only over its input).

    python analysis/strategy_log.py SRC --out DIR [--counts] [--strategy ID]

SRC is a live session folder (logs/<strategyId>_<ms>_inst<N>/ -> its decisions.jsonl, rotated parts too), a
decisions.jsonl file, or a BarReplay `--log` file. Only lines with "type":"strategy_log" are used; both sources have the
same shape: {"type":"strategy_log","strategyId":..,"t":<epoch ms>,"event":..,"ct":"YYYY-MM-DD HH:MM",...fields}.

Writes DIR/<strategyId>__<event>.csv (columns: the union of that event's fields, header order = first appearance).
--counts additionally prints the record count per event type per CT day (the volume check).
"""
import argparse
import csv
import json
import os
import sys
from collections import Counter, OrderedDict, defaultdict

HEAD = ["strategyId", "t", "ct", "event"]


def _files(src):
    if os.path.isfile(src):
        return [src]
    out = []
    for root, _dirs, names in os.walk(src):
        for n in sorted(names):
            if n.startswith("decisions") and (n.endswith(".jsonl") or ".jsonl" in n):
                out.append(os.path.join(root, n))
    return out


def records(src, strategy=None):
    for path in _files(src):
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                if '"strategy_log"' not in line:
                    continue
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                if r.get("type") != "strategy_log":
                    continue
                if strategy and r.get("strategyId") != strategy:
                    continue
                yield r


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src")
    ap.add_argument("--out", required=True)
    ap.add_argument("--strategy", default=None, help="only this strategyId")
    ap.add_argument("--counts", action="store_true", help="print record counts per event type per CT day")
    a = ap.parse_args(argv)

    groups = defaultdict(list)
    per_day = defaultdict(Counter)
    n = 0
    for r in records(a.src, a.strategy):
        n += 1
        groups[(r.get("strategyId", "?"), r.get("event", "?"))].append(r)
        day = (r.get("ct") or "no-time")[:10]
        per_day[day][r.get("event", "?")] += 1
    if n == 0:
        print("no strategy_log records found in " + a.src, file=sys.stderr)
        return 1
    os.makedirs(a.out, exist_ok=True)
    for (sid, ev), rows in sorted(groups.items()):
        cols = OrderedDict((k, None) for k in HEAD)
        for r in rows:
            for k in r:
                if k != "type":
                    cols.setdefault(k, None)
        path = os.path.join(a.out, "%s__%s.csv" % (sid, ev))
        with open(path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(cols), extrasaction="ignore")
            w.writeheader()
            for r in rows:
                w.writerow(r)
    print("%d records -> %d csv files in %s" % (n, len(groups), a.out))
    if a.counts:
        events = sorted({e for c in per_day.values() for e in c})
        print("day," + ",".join(events) + ",total")
        for day in sorted(per_day):
            c = per_day[day]
            print(day + "," + ",".join(str(c.get(e, 0)) for e in events) + "," + str(sum(c.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
