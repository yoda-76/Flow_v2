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
├── ops/               watchdog.py (alerts from OUTSIDE MotiveWave) + Windows scheduled-task / hardening scripts (docs/runbook-ec2.md)
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

## Working on a second machine (added 2026-09-26)

This repo is meant to be cloned onto other machines (a cloud machine; the spare-laptop trial). A Claude session
there starts with **no memory of the first machine's chats** — everything it needs is in the repo.

- **For a rented cloud VM (EC2 or similar) — the current target:** read, in this order: `docs/working-agreements.md`
  (how the user works), `docs/runbook-ec2.md` (the whole setup — decisions to make, AWS/Windows prep, MotiveWave
  and FLOW install, alerts/watchdog, restart policy, the first-24-hours checklist), `docs/runbook.md` (general
  first-start checklist §5, sizing §11a) and `docs/configuration.md` (every setting). Then check
  `docs/dynamic/todo.md`'s most recent dated entries (search for "Session handoff" / "merged into `main`") for
  what is still outstanding — do **not** start from the **"LAPTOP TRIAL HANDOFF"** block below; that one is the
  spare-laptop trial specifically and `todo.md` itself now calls that trial moot ("the user is moving to EC2
  instead of a second physical machine").
- **For the spare-laptop trial specifically** (historical, 2026-09-26/27, superseded by the move to EC2): the
  **"LAPTOP TRIAL HANDOFF"** block at the top of `docs/dynamic/todo.md`, then `docs/runbook.md` (§13 is the trial
  script) and `docs/configuration.md`.

- **Sibling repos may not exist.** `../motivewave` and `../FLOW` are not needed to build or run (only a JDK 26 is:
  set `FLOW_JDK_BIN`). Links into `../motivewave/docs/…` are then dead; do not re-derive platform facts from
  memory — tell the user which finding you need and ask them to fetch that repo
  (`github.com/yoda-76/motivewave`).
- **The order rules above apply unchanged, and on a second machine the *user* arms and runs the trading
  session.** Claude reads logs and journals, runs the `analysis/` scripts, builds and tests — it does **not** check
  *Armed*, switch *Mode* to `SIM_LIVE`, or place any order on its own initiative there. The Simulated-account-only /
  real-account-forbidden line is identical everywhere.
- **Update 2026-09-28 (D-115): there is now a second, code-level layer.** Every order/fill carries `getAccountId()`
  (always `"simulated"` on Sim); an order or fill naming any other account is refused/ignored, disarms the runtime and
  locks all order sending. The statement below that there is *no code-level check* is therefore no longer the whole
  story — but the checkbox and the human check still stand, and the layer fails open on a missing id.
- **"Sim Trade Only" is a per-installation MotiveWave setting and a fresh install must be assumed to have it OFF**
  (it was off on the first machine's fresh install). **There is no code-level check that the active account is the
  Simulated one** — the SDK exposes an `Account` type but no accessor to it (searched 2026-09-26) — so this single
  platform checkbox, plus the human confirming the account selector reads "simulated", is the entire guard against
  a real-account order. On any new machine it must be enabled **before the study is added**, and confirmed at every
  activation. If anything indicates a non-Simulated account, **stop and tell the user** (safety stop, not a
  consent request).
- **Only gold (`@GC` / a `GC` contract) may be traded, for now** (user, 2026-09-27). The runtime enforces it
  (`InstrumentPolicy`: `REFUSE_TO_ARM` on any other chart, and no order of any kind there).
- **"Sim Trade Only" stays enabled at all times, on every machine, until the user explicitly says otherwise**
  (user, 2026-09-27) — enable it before Rithmic is connected (`docs/runbook.md` §4).
- **Per-machine differences go in `config/risk.local.json`** (git-ignored, optional; D-106), never in the tracked
  `config/risk.json`. For an "identical to the first machine" trial, leave it absent.
- **Claude cannot drive MotiveWave's GUI.** The user adds/removes/activates the study; Claude reads MotiveWave's log
  (`%APPDATA%\MotiveWave\output\`) and the journal afterwards.
- Push/commit only when the user asks (`docs/working-agreements.md` §6).

## Model mode toggle (added 2026-09-27)

**CURRENT MODE: `REGULAR`**   ← the switch. Change this one word (`REGULAR` or `SUPER`) to change how work is split.
The user picks the session's own model with `/model`; this section says who does *which work*.
**The toggle only chooses models. It never changes what is authorized** — the Sim pre-authorization (third
exception) and every hard rule stay exactly as written, whatever the mode. "Regular mode" / "super mode" in
the user's words means this toggle *(2026-09-27: a session on the laptop misread "regular mode" as ending the
sprint authorization; it did not)*.

| Mode | Who works | Rule |
|---|---|---|
| **`REGULAR`** | **Sonnet 5 only** | One model does everything. No delegating to other models. |
| **`SUPER`** | **Opus 5.5 decides; Sonnet 5 does the grunt work; Haiku 4.5 does the trivial calls** | Below. |

**In `SUPER` mode — the session runs on Opus 5.5, and Opus is *not* used for everything.**
- **Opus 5.5 (the main session) keeps:** decisions and design, deciding *what* to change and why, reviewing
  findings, anything touching safety or order handling (`RiskChain`, `Pipeline`, `LiveOrderTracker`, `OrderGateway`,
  the kill switch, the flatten, the Sim-only guard), reading a subagent's result critically, writing decision
  entries, and the final say before any commit.
- **Sonnet 5 (`Agent` with `model: "sonnet"`) gets** simple code changes and other grunt work once Opus has said
  exactly what to do: mechanical edits and renames, writing tests from a stated spec, routine doc updates,
  running/mutation-sweeping tests and reporting results, refactors with a precise description, log/journal digging.
- **Haiku 4.5 (`Agent` with `model: "haiku"`) gets** genuinely trivial calls: reading one file, listing or counting
  things, a single grep, calling an MCP tool, fetching a value — anything a person would call "just look it up".
- **Do not** hand a whole open-ended task to a cheaper model; delegate the *bounded* piece. **Do not** run
  everything through Opus either — if a step is simple enough to describe in a sentence and check in a glance, it is
  not Opus's job.

**Guardrails that do not change with the mode**
- **Every hard rule in this file applies to every model and every subagent, unchanged** — no orders (real or Sim)
  as a side effect, never touch `.env`, Sim-only, strategies never import the SDK. Subagents are told these rules in
  their prompt; a subagent never places, modifies or cancels an order, and never runs `build.sh` against the real
  MotiveWave folder.
- **Delegation is not a review.** A subagent starts cold and reports what it *believes* it did: Opus reads the actual
  diff / output before relying on it, and re-runs the tests itself. Safety-critical code changes are never accepted
  on a subagent's word.
- **Give the subagent everything it needs** (files, the exact change, the constraint, how to verify) — it has none of
  this conversation.
- Commit/push only when the user asks (`docs/working-agreements.md` §6); a subagent never commits.
- **If the mode is `SUPER` but the session is not running on Opus 5.5** (or is `REGULAR` on something other than
  Sonnet 5), say so once at the start and carry on in the mode as far as the current model allows.
- *Naming:* the user wrote "Sonnet 5.5"; the models available here are Sonnet 5, Opus 5.5 and Haiku 4.5, so the
  middle tier means **Sonnet 5**.

No other standing rules recorded yet.
