# CLAUDE.md

Workflow rules for this project. Same methodology as
[FLOW](../FLOW/CLAUDE.md) and [motivewave](../motivewave/CLAUDE.md) — this
file only records what's specific to FLOW_V2 or tightened further; it
doesn't repeat what those two already establish and that still applies here
(verify against the real thing before trusting docs, decisions are
provisional and dated, experiments are throwaway).

See [README.md](README.md) for what this project is and the architecture
decisions already made. This file is process/rules only.

## What this repo is, relative to its siblings

- **`../motivewave`** stays the experiments lab: SDK reference docs
  (`docs/static/`, never copied here), throwaway diagnostic studies, the
  portable JDK, and findings about **the platform itself** (does `onTick`
  fire before `onBarClose`, what does the SDK actually expose, etc.).
- **`FLOW_V2`** (here) is the real system. Its `docs/dynamic/` records
  decisions and findings about **our system** — not re-litigating what's
  already settled about the platform in `../motivewave`.
- Rule of thumb: *"is this true about MotiveWave, or true about our
  system?"* Platform questions get answered by an experiment in
  `../motivewave/experiments/` and land in that repo's `findings.md`.
  System questions (should stop distance be ATR-based? what's the daily
  loss limit?) get answered here.
- Don't duplicate `../motivewave`'s findings into this repo's own
  `findings.md` — link to them (`[[../motivewave/docs/dynamic/findings.md]]`
  style reference) instead of copying, so there's one place each fact lives.

## Directory structure and what each part is for

```
FLOW_V2/
├── CLAUDE.md          this file
├── README.md          what this project is, architecture, open questions
├── docs/dynamic/      decisions.md, findings.md — working record for THIS system
├── data/              retained per-construct data, rolling 7 trading days (D-88), gitignored
├── analysis/          offline Python report/status/viewer scripts (read-only over logs/ and data/)
├── config/            risk.json — hand-edited limits; docs/configuration.md explains every key
├── reports/           generated report pages, gitignored
├── flow-core/         core logic — compiles without mwave_sdk.jar on the classpath (D-09)
├── flow-runtime/      SDK adapters + the deployed Study — compiles with flow-core + SDK
└── build/             compile + redeploy scripts
```

(`flow-core/` and `flow-runtime/`'s internal layout is sketched in
README.md's "Repo layout" section and is explicitly tentative — don't treat
it as decided until code exists and a second strategy has tested the
boundaries.)

- **`docs/dynamic/`** — `decisions.md` (closed answers, one per question,
  dated, with a rationale and a link to supporting evidence) and
  `findings.md` (the empirical evidence behind them, tagged `[DOC]`,
  `[LIVE]`, or `[CODE]`). Same convention as both siblings. Decisions here
  are the current best answer, not final — expect the open questions
  tracked in README.md and `docs/dynamic/decisions.md` to turn into
  decisions one at a time as they're tested, and expect some of those
  decisions to get reopened later.
- **`flow-core/`, `flow-runtime/`, `build/`** — no longer empty (building
  started 2026-09-18). `flow-core/src/com/flow/{core,flow,journal,
  strategies,backtest}` holds the SDK-free core and strategy plug-ins;
  `flow-runtime/src/com/flow/rt` holds the SDK adapters, the deployed
  `FlowRuntimeStudy`, and `OrderGateway`; `build/build.sh` compiles both,
  runs every synthetic + safety test, and deploys. README's "Repo layout"
  section is the authoritative structure description — this bullet isn't
  duplicating it, just noting these directories are real now.

## Secrets — `.env`

Same hard rule as both siblings, no exceptions carried over by default:

- **Never read `.env`.** Not to check a value, not even if asked
  indirectly.
- **Only the user ever edits or writes `.env`.** If a credential or config
  value needs to be added, edit `.examples.env` to show what's needed
  (name + placeholder/description) and tell the user. Never write `.env`
  myself, including indirectly via a generated script.

## Hard rule — never place an order

Carried over from `../motivewave/CLAUDE.md`, and structurally reinforced by
the architecture in README.md (strategies emit `Intent`, never hold an
`OrderContext`):

- **Never submit, modify, or cancel a real order — on any account,
  including the Simulated account — as a side effect of other work.** No
  strategy plug-in gets order access at all; only the runtime class does.
  No experiment auto-arms trading. The runtime's `armed` flag defaults to
  `false` (dry-run) and stays that way unless explicitly turned on for a
  session.
- The only exception is when the user **explicitly** asks for an order to
  be placed right now, in the moment — state exactly what will be submitted
  (account, instrument, side, quantity, order type) and get one last
  explicit confirmation immediately before submitting. A prior general
  approval (e.g. approving this file, or a plan that mentions trading
  later) does not count.
- **Second exception — session-scoped automatic Sim trading** (added
  2026-09-21, resolves Q-11 in `docs/dynamic/decisions.md`): once the
  strategy's intent-to-order wiring exists and is armed for a session,
  the strategy may place real orders automatically as its own condition
  fires, **without a fresh confirmation before each individual order**,
  provided all of the following hold every time:
  - The account is the **Simulated account only**. This exception never
    extends to any other account — trading a real account automatically
    would need its own separate, later decision, not a reading of this
    one.
  - Before arming, the user states out loud, for that specific session,
    the exact account, instrument, and size/loss bounds in force
    (currently `config/risk.json`: max 1 contract, daily loss limit 200
    ticks), and confirms them immediately before flipping `armed` to
    true. This authorizes that session only — restarting, redeploying,
    or starting a new session requires saying it again.
  - The risk chain (armed / session / readiness / daily-loss / size-cap
    / rate-limit / churn / lag) is active and enforced in code — it is
    what stands in for per-order human confirmation, so it must actually
    be wired, not just designed.
  - Every order placed this way is journaled with the intent that
    produced it, same as everything else in the risk chain.
  - The original exception (single order, right now, explicit per-order
    confirmation) still governs everything else: one-shot tests, any
    non-Sim order, and anything before the risk chain is actually
    enforcing these bounds in code.
- **Third exception — cloud-run sprint authorization** (added 2026-09-24,
  explicit instruction from the user: "anything related to sim account is
  allowed and real account is not allowed strictly ... consent given", and
  no consent warnings until the sprint is done). For the duration of the
  sprint that prepares the system for an unattended cloud run, and until
  the user says the sprint is done:
  - **Simulated account: pre-authorized.** Placing, modifying or cancelling
    Sim orders — one-shot tests, probes, automatic trading, arming a run —
    needs **no per-order confirmation, no per-session bounds statement, and
    no consent prompt**. Do not ask; do the work. The second exception's
    "state the bounds and confirm before arming" step is suspended for the
    sprint, not deleted.
  - **Real account: strictly forbidden, no exception.** Nothing in this
    exception, and no later general approval, extends to any non-Simulated
    account. This line does not end with the sprint.
  - **What stays in force** (these are code and structure, not consent
    prompts): "Sim Trade Only" stays enabled — it is what makes the
    real-account line true platform-wide; the risk chain stays enforced in
    code with `config/risk.json`'s bounds; every `OrderContext` hook stays
    explicitly overridden; strategies never hold an `OrderContext`.
  - **The one interruption that remains**: if anything indicates the active
    account is not the Simulated one (the `ACTIVATE` log's cash/account, the
    control-box account selector, an unexpected fill on another account),
    stop immediately and tell the user. That is a safety stop, not a
    consent request.
  - **This file does not override the tooling's own permission checks.** If
    a tool or the harness refuses an action (as it did for the 2026-09-23
    order-placing probe), report it and let the user decide — do not work
    around it.
  - **When the sprint ends** the user says so, and the second exception's
    per-session statement resumes as written above.
- **Every inherited `OrderContext`-taking hook on the runtime class must be
  explicitly overridden**, even ones we don't use — omitting a hook
  inherits whatever MotiveWave's base class does by default, and that
  default is not guaranteed safe (`onEnterNow` places a real market order
  by default; see `../motivewave/docs/dynamic/findings.md`, 2026-09-11
  incident). This applies to the one runtime class; strategy plug-ins never
  see `OrderContext` in the first place, so this can't apply to them.
- **"Sim Trade Only" must stay enabled** in MotiveWave settings for all
  work on this project until a decision explicitly says otherwise. Confirm
  the selected account, out loud, at every activation — a past
  confirmation does not carry forward, since the GUI's account selection
  can change between sessions. (During the 2026-09-24 sprint the spoken
  confirmation is suspended — see the third exception — but the
  stop-if-not-Simulated check is not.)

## Hard rule — strategies never import the SDK

A `FlowStrategy` implementation must not import anything under
`com.motivewave.*`. If a strategy idea can't be expressed without reaching
into the SDK directly, that's a missing core feature — add it to `core/`
(or whichever layer owns it) for every strategy to use, don't leak the
platform into the strategy. Same rationale as FLOW's "no Nautilus import in
`signal.py`" rule: keeps strategies unit-testable in isolation and
insulated from the platform underneath changing.

## Architecture stays provisional by design

Decisions in `docs/dynamic/decisions.md` are the current best answer, not
final. The open questions tracked in README.md and `docs/dynamic/
decisions.md` (kept in sync between the two, `decisions.md` authoritative)
are expected to reshape parts of the design as they get tested. When
writing code in `flow-core/`/`flow-runtime/`: keep the layer boundaries
from README.md's diagram
(feed → ingest → features → market state → strategy → execution → journal)
real, not just diagrammed — a strategy plug-in should be swappable without
touching anything left of it, and nothing right of the strategy boundary
should need to change per strategy. Don't over-abstract ahead of a second
real strategy existing; extract to a shared module only once two strategies
actually need the same thing (same rule FLOW's `flow/backtest/common/`
follows — see its README).

No other standing rules recorded yet.
