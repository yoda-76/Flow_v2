#!/usr/bin/env python3
"""Offline journal summary/comparison tool.

README's own "offline journal comparison tooling" item (§5 of
docs/dynamic/todo.md, D-20's plan: "Comparison across strategies is done
offline, from the journals... Analysis tooling there can be Python").

Reads a FLOW_V2 session directory's decisions.jsonl -- the human-
readable, long-retention tier (D-15) -- and prints:
  - the session header (strategy, symbol, git-adjacent config)
  - a count of every record type seen
  - a chronological list of every NON-heartbeat record, rendered
    compactly per known type (falls back to raw JSON for anything new)
  - a health summary (any DISARM/GAP_MARKER, first/last heartbeat gap)

Deliberately scoped to decisions.jsonl only -- not raw.jsonl (tick-level,
not meant for human reading, D-15's own split) and not the various
diagnostic *_feature.log files (their own separate, throwaway
validation logs, a different thing from the real two-tier journal this
tool is about).

Usage:
  python analysis/journal_summary.py <session_dir>              # single-session summary
  python analysis/journal_summary.py <session_dir_a> <session_dir_b>  # side-by-side comparison
"""

import json
import sys
from collections import Counter
from pathlib import Path


def read_records(session_dir: Path):
    path = session_dir / "decisions.jsonl"
    if not path.exists():
        raise FileNotFoundError(f"no decisions.jsonl under {session_dir}")
    records = []
    with path.open(encoding="utf-8") as f:
        for lineno, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as e:
                print(f"  ! PARSE ERROR at {path}:{lineno}: {e}", file=sys.stderr)
    return records


# One-line renderers for record types worth summarizing specially.
# Anything not listed here falls back to a compact raw-JSON dump.
def render_intent_changed(r):
    return (f"intent_changed  strategy={r.get('strategyId')} target={r.get('targetPosition')} "
            f"stop={r.get('stopPriceTicks')} target={r.get('targetPriceTicks')} reason={r.get('reason')!r}")


def render_risk_verdict(r):
    allowed = r.get("allowed")
    tag = "ALLOWED" if allowed else "BLOCKED"
    if allowed:
        return f"risk_verdict    {tag}"
    verdicts = r.get("verdicts", [])
    blocked = next((v for v in verdicts if not v.get("allowed")), None)
    reason = f"{blocked.get('filter')}: {blocked.get('reason')}" if blocked else "?"
    return f"risk_verdict    {tag}  ({reason})"


def render_disarm(r):
    return f"DISARM          reason={r.get('reason')!r}"


def render_gap_marker(r):
    return f"GAP_MARKER      seq {r.get('fromSeq')}..{r.get('toSeq')} lost"


def render_level_trace(r):
    return (f"level_trace     {r.get('featureId')} {r.get('levelName')} {r.get('kind')} "
            f"price={r.get('priceDecimal')}")


def render_zone_trace(r):
    return (f"zone_trace      {r.get('featureId')} {r.get('zoneKind')} {r.get('kind')} "
            f"[{r.get('zoneLowDecimal')},{r.get('zoneHighDecimal')}]")


def render_reconcile(r):
    return f"reconcile_dry_run {json.dumps({k: v for k, v in r.items() if k != 'type'})}"


RENDERERS = {
    "intent_changed": render_intent_changed,
    "risk_verdict": render_risk_verdict,
    "DISARM": render_disarm,
    "GAP_MARKER": render_gap_marker,
    "level_trace": render_level_trace,
    "zone_trace": render_zone_trace,
    "reconcile_dry_run": render_reconcile,
}

NOISY_TYPES = {"heartbeat"}  # excluded from the chronological "notable events" list, still counted


def render(r):
    t = r.get("type", "?")
    fn = RENDERERS.get(t)
    if fn:
        try:
            return fn(r)
        except Exception:
            pass
    return f"{t:<15} {json.dumps({k: v for k, v in r.items() if k != 'type'})}"


def summarize(session_dir: Path):
    records = read_records(session_dir)
    counts = Counter(r.get("type", "?") for r in records)

    print(f"=== {session_dir.name} ===")
    header = next((r for r in records if r.get("type") == "session_header"), None)
    if header:
        print(f"strategy={header.get('strategyId')} symbol={header.get('symbol')} "
              f"tickSize={header.get('tickSize')} mode={header.get('mode')} "
              f"instance={header.get('instanceId')}")
    else:
        print("(no session_header record found)")

    print(f"\n{len(records)} decisions-tier records total:")
    for t, c in counts.most_common():
        print(f"  {c:>6}  {t}")

    disarms = [r for r in records if r.get("type") == "DISARM"]
    gaps = [r for r in records if r.get("type") == "GAP_MARKER"]
    if disarms or gaps:
        print(f"\n!! health: {len(disarms)} DISARM(s), {len(gaps)} GAP_MARKER(s) -- see below")
    else:
        print("\nhealth: clean -- no DISARM or GAP_MARKER records")

    notable = [r for r in records if r.get("type") not in NOISY_TYPES]
    print(f"\n{len(notable)} notable (non-heartbeat) record(s), in order:")
    for r in notable:
        print(f"  seq={r.get('seq', '-'):<8} {render(r)}")

    return records, counts


def compare(dir_a: Path, dir_b: Path):
    print(f"Comparing:\n  A: {dir_a}\n  B: {dir_b}\n")
    records_a, counts_a = summarize(dir_a)
    print()
    records_b, counts_b = summarize(dir_b)

    print(f"\n=== record-type count diff (A vs B) ===")
    all_types = sorted(set(counts_a) | set(counts_b))
    for t in all_types:
        ca, cb = counts_a.get(t, 0), counts_b.get(t, 0)
        marker = "" if ca == cb else "  <-- differs"
        print(f"  {t:<25} A={ca:<6} B={cb:<6}{marker}")

    intents_a = [render_intent_changed(r) for r in records_a if r.get("type") == "intent_changed"]
    intents_b = [render_intent_changed(r) for r in records_b if r.get("type") == "intent_changed"]
    if intents_a != intents_b:
        print(f"\n!! intent_changed sequences differ (A has {len(intents_a)}, B has {len(intents_b)})")
    else:
        print(f"\nintent_changed sequences are identical ({len(intents_a)} each)")


def main():
    if len(sys.argv) not in (2, 3):
        print(__doc__)
        sys.exit(2)
    if len(sys.argv) == 2:
        summarize(Path(sys.argv[1]))
    else:
        compare(Path(sys.argv[1]), Path(sys.argv[2]))


if __name__ == "__main__":
    main()
