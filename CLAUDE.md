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
└── app/               the actual system — stays empty until told otherwise
```

(`app/`'s internal layout is sketched in README.md and is explicitly
tentative — don't treat it as decided until code exists and a second
strategy has tested the boundaries.)

- **`docs/dynamic/`** — `decisions.md` (closed answers, one per question,
  dated, with a rationale and a link to supporting evidence) and
  `findings.md` (the empirical evidence behind them, tagged `[DOC]`,
  `[LIVE]`, or `[CODE]`). Same convention as both siblings. Decisions here
  are the current best answer, not final — expect the seven open questions
  in README.md to turn into decisions one at a time as they're tested, and
  expect some of those decisions to get reopened later.
- **`app/`** — stays empty until told otherwise. We're still resolving the
  open questions in README.md; building starts on explicit instruction, not
  by inference from how much of the rest of the work is done.

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
  can change between sessions.

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
final. The seven open questions listed in README.md (bar/tick ordering,
settings UI limits, instruments/timeframes, session model, sizing/risk
defaults, intrabar decisions, simulated-account fidelity) are expected to
reshape parts of the design as they get tested. When writing code in
`app/`: keep the layer boundaries from README.md's diagram
(feed → ingest → features → market state → strategy → execution → journal)
real, not just diagrammed — a strategy plug-in should be swappable without
touching anything left of it, and nothing right of the strategy boundary
should need to change per strategy. Don't over-abstract ahead of a second
real strategy existing; extract to a shared module only once two strategies
actually need the same thing (same rule FLOW's `flow/backtest/common/`
follows — see its README).

No other standing rules recorded yet.
