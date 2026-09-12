# Decisions

Closed architectural decisions, one per question, each with a date and a
link to the evidence behind it. Same convention as FLOW's and motivewave's
`decisions.md` — current best answer, not final; expect these to get
reopened as the seven open questions in `README.md` get tested empirically.

These decisions are about **this system**, not about the MotiveWave
platform itself — platform-level decisions/findings live in
[[../../motivewave/docs/dynamic/decisions.md]] and stay there.

- **D-01** (2026-09-12) — FLOW_V2 is **the real system**, not another
  scoping/experiments repo. `../motivewave` keeps its role as the
  experiments lab (SDK reference docs, throwaway diagnostic studies,
  platform-level findings) and is not superseded — its docs/static and
  findings are not copied here, only referenced. Rationale: keeps exactly
  one place each kind of fact lives — platform facts in motivewave, system
  facts here — rather than two repos slowly drifting out of sync. See
  README.md's "Relationship to the two sibling repos" section.

- **D-02** (2026-09-12) — **All-Java, in-platform runtime.** Data
  ingestion, feature computation, strategy execution and journaling all run
  inside a single deployed MotiveWave Strategy — no external process, no
  IPC. Rationale: lowest latency, direct `OrderContext`/`DataContext`
  access, one runtime to reason about instead of two failure domains. Cost
  accepted: no Python/pandas in the hot path, and strategy iteration means
  a recompile+redeploy cycle rather than editing a script. A bridge to an
  external process was explicitly considered and rejected for now (see
  README.md's "Runtime" discussion) — revisit only if a real strategy needs
  something Java can't reasonably do.

- **D-03** (2026-09-12) — **One host Strategy; the specific strategy is a
  setting, not a separate deployment.** A single `@StudyHeader` Strategy
  ("the runtime") discovers registered `FlowStrategy` implementations and
  the active one is picked via a dropdown in the study settings, with that
  strategy's own params exposed through the same panel. Rationale: adding a
  strategy means one new class + one registry entry — no new StudyHeader,
  no new deployment target, no repeated boilerplate per strategy. Rejected
  alternative: one MotiveWave Strategy class per strategy idea (more
  "native" to the platform, but every strategy repeats deployment
  boilerplate) — may still be worth it later for a strategy that's
  graduated to being a long-term keeper, but not the default.

- **D-04** (2026-09-12) — **Strategies emit `Intent`; only the runtime
  holds `OrderContext`.** A `FlowStrategy` receives a read-only
  `MarketState` snapshot and returns an `Intent` (direction, size, stop,
  target, reason). The runtime translates intent into orders, sizing,
  brackets, session-end flattening, and journaling. Rationale, in order of
  weight: (1) safety — a strategy plug-in cannot place an order even by
  accident, since it's never handed the capability, which structurally
  rules out a repeat of the 2026-09-11 `onEnterNow` incident recorded in
  `../motivewave/docs/dynamic/findings.md`; (2) comparability — every
  strategy shares one execution path, so a difference in results is a
  difference in signal, not in bespoke order handling; (3) testability — an
  `Intent` from a synthetic `MarketState` is assertable in a plain unit
  test with no platform running. An escape-hatch interface for direct order
  control (scaling in/out, bracket ladders) is deliberately not built yet —
  add it only once a real strategy needs it, per an explicit decision at
  that time.

- **D-05** (2026-09-12) — **Strategies must not import `com.motivewave.*`.**
  Mirrors FLOW's "no Nautilus import in `signal.py`" rule (D-04-equivalent
  there). If a strategy can't be written without reaching into the SDK
  directly, that's a missing feature in `core/`/`price/`/`flow/`, not a
  reason to let the platform leak into strategy code. Keeps strategies
  unit-testable in isolation and insulated from platform changes.

- **D-06** (2026-09-12) — **No backtesting for now; forward test only**
  (dry-run → sim → live, gated in that order). Matches MotiveWave's own
  documented recommendation to forward-test before trusting a backtest (see
  `../motivewave/docs/dynamic/findings.md`, `[DOC]` entries on Backtesting
  Limitations) and avoids depending on bar-granularity replay for
  order-flow logic that's inherently tick/DOM-level. Backtest is future
  scope, not rejected outright — see D-07.

- **D-07** (2026-09-12) — **Record now, replay later, with bounded raw
  retention.** The journal records every input event the runtime sees
  (ticks, DOM, bar closes), not just decisions, so a future replay harness
  is a straight-line project rather than something bolted on after the
  fact. Raw tick/DOM-level journal data is retained for a **short rolling
  window only — last 2–3 days**, exact number still TBD once real MBO data
  volume is known; higher-level records (intents, orders, fills, bar
  summaries) are cheap and can be kept longer. Rationale: MBO-granularity
  order-flow data is large enough that unbounded retention is a real
  disk-space decision, not a free default, and forward-testing (this
  project's actual current goal) doesn't need the raw stream kept forever.

- **D-08** (2026-09-12) — **External/manual inputs (GEX levels, daily
  bias, key levels, etc.) are a first-class pluggable input**, not
  computed by this system. Held in a small file-based store, hand-edited
  by the user only (same write discipline as `.env`), loaded at session
  start and exposed to strategies as a read-only field on `MarketState`
  alongside live-feed features — a strategy doesn't distinguish where a
  number came from. The journal records which external input was active at
  every decision point so staleness (e.g. a three-day-old GEX level) is
  traceable after the fact rather than silently assumed current. Exact
  file format deferred until a first strategy actually needs one.

## Open questions (not yet decisions)

See README.md's "Open questions" section for the full list with reasoning:
bar/tick ordering guarantees, settings-UI limits on conditional param
visibility, which instrument(s)/timeframe(s) to forward-test first, session
model (RTH vs 24h, flatten-on-close default), sizing/risk defaults and the
daily-loss kill switch, whether bar-close-only decisions are sufficient for
the order-flow half, and how faithfully the Simulated account fills orders.
Each becomes an experiment, then an entry here.
