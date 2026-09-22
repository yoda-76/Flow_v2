# Replay-equivalence fixture — `null_strategy`

Synthetic, not cut from a real session: 13 hand-authored events (ticks,
two bar closes, one clock event) run once through a real `Pipeline` +
`JournalWriter` + `NullStrategy` to produce a genuine `raw.jsonl`/
`decisions.jsonl` pair (not hand-written JSON, so it can't drift from
`RawEventCodec`'s actual format). The one-off generator that produced
these two files is not committed — regenerate by wiring `NullStrategy`
through `Pipeline`/`JournalWriter` directly if this ever needs to change.

Used by `build/build.sh`'s `ReplayEquivalenceTest` gate.

**Known limitation, not a bug**: `NullStrategy` always returns
`Intent.none()`, so this fixture's `decisions.jsonl` has zero
`intent_changed` records — the replay comparison is a **0-vs-0 match**.
That proves the record→replay→compare *machinery* runs end to end
(encode/decode round-trips, `JournalWriter`'s dual-tier output, the
harness itself doesn't throw) but does **not** exercise real
intent-change equivalence. That's not fixable with a better fixture: every
currently-registered strategy that *can* change its intent
(`market_structure_lvn_reversal`, `lvn_fade_test`) requires
`VolumeProfileView`, which only has an SDK-backed implementation
(`SdkVolumeProfileFeature`, `flow-runtime`) — replay runs under a plain
JDK with no SDK on the classpath, so that feature can never be
reconstructed here. This is D-43's already-recorded gap
("replay-inside-MotiveWave," `docs/dynamic/todo.md` §4), not something
newly discovered by this fixture — `ReplayEquivalenceTest` prints the
same caveat itself at every run.
