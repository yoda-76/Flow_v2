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

This file stays empty by convention, not because nothing has been tested:
evidence about this system now lives with the decision it supports, in
`docs/dynamic/decisions.md` (each decision carries its own evidence rather
than a separate entry here). `docs/dynamic/codeReview.md` holds the
2026-09-27 code review of what's been built so far. The open questions
tracked in `README.md` / `decisions.md` are the current queue of things
still to test.
