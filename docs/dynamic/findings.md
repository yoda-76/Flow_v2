# Findings

Empirical evidence behind `decisions.md`, tagged `[DOC]` (read from
MotiveWave's docs/Javadoc), `[LIVE]` (tested against the real running
platform/feed), or `[CODE]` (verified by reading `mwave_sdk.jar`/Javadoc
directly). Same convention as FLOW's and motivewave's `findings.md`.

This file is for evidence about **this system** — decisions we make about
our own architecture, journal format, strategy contract, etc. Evidence
about **the MotiveWave platform itself** (what an SDK method actually does,
what the live feed actually delivers) belongs in
[[../../motivewave/docs/dynamic/findings.md]], not here — see that file
for everything the initial architecture in README.md and this repo's
`decisions.md` already leans on (confirmed MBO depth on the live
Rithmic/CQG feed, the strategy lifecycle, the `onEnterNow` incident and its
fix, etc.). Referenced from here rather than duplicated so each fact has
exactly one home.

Most evidence about this system lives with the decision it supports, in
`docs/dynamic/decisions.md` (each decision carries its own evidence).
`docs/dynamic/codeReview.md` holds the 2026-09-27 code review of what has
been built. Entries below are findings that are not (yet) a decision — the
first one came from the spare-laptop trial. The open questions tracked in
`README.md` / `decisions.md` are the current queue of things still to test.

## Entries

### F-1 (2026-09-27, laptop trial Phase 1) — `[LIVE]` Recorded data files are keyed by trading day only, not by instrument: two instruments on one day land in the same file

**Seen:** on the spare laptop (MotiveWave 7.1.1), the FLOW Runtime study was first added by mistake to an
**ESZ6** chart (22:18 IST), removed (22:21, `DEACTIVATE` → `DESTROY`), then added to an **`@GC`** chart (22:22).
Both sessions wrote to the **same** files:

- `data/liquidity_map/20722.jsonl` — lines 1-171 are ES (bid ~7805.75), a second `header` line at 172, gold
  from 173 on (bid 4312.1). No field on the data lines says which instrument they are.
- `data/market_structure/20722.jsonl` — ES `header` + `warm_start` (lines 1-2), then gold `header` +
  `warm_start` (lines 3-4).

**Why (from the code):** `DataRecorder.write()` names the file `data/<construct>/<sessionId>.jsonl`, and
`sessionId` is `SessionBoundary.sessionIdFor(eventTimeMs)` — the trading-day number only. The symbol is in the
session's journal (`session_header.symbol`), but not in the file name, the `header` line, or the data lines.
The second session wrote its own `header` because `headerWritten` is per study instance.

**Consequence:** anything that reads a day's data file (replay, `trade_view.py`, the report's "Recorded data"
table) sees the two instruments' prices interleaved as if they were one market. With one instrument per machine
all day this never happens. It does happen whenever the chart's instrument changes during a trading day — a
wrong chart, a contract roll to a new explicit contract, or a later second strategy on another instrument.

**Status: FIXED 2026-09-27 (D-112)** — the user chose the symbol in the path: files are now
`data/<construct>/<symbol>/<sessionId>.jsonl` (`@GC` → `GC`), the header line names the symbol, pruning reaches
the symbol folders, and the three analysis tools read the new layout first and the old flat files as a fallback.
Also, only gold may now be traded (`InstrumentPolicy`), so a wrong chart can no longer be armed.
*Original status:* flagged, **not fixed** (working agreements §3 — awaiting the user's go). Open question for the fix:
put the symbol in the path (`data/<construct>/<symbol>/<sessionId>.jsonl`) or in the `header` line, and whether
the readers must handle the old layout. `todo.md` has the checkbox. Today's mixed files were left as they are.
