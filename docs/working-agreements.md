# Working agreements — how the user likes work done on this project

Written 2026-09-26 by moving everything that previously lived only in Claude's per-machine
memory into the repo, so **any** Claude session on **any** machine (e.g. the spare-laptop trial) starts with the
same habits. `CLAUDE.md` holds the *hard rules* (orders, secrets, the SDK boundary); this file holds *how we work*.
When a habit here conflicts with a hard rule in `CLAUDE.md`, the hard rule wins.

## 1. Where context lives (so nobody has to re-explain)

- **Handoff → `docs/dynamic/todo.md`, top of the file.** What the current sprint is, what is authorised, what is
  deployed vs only built, the next concrete step. Update it **before a session ends** or when the user says a work
  session is done, together with the relevant `decisions.md` entries. The user's own words (2026-09-24): *"I don't
  want to explain the sprint again due to chat closure."*
- **Decisions → `docs/dynamic/decisions.md`** (dated, one per question, with the evidence). **Evidence about
  *this system* → `findings.md`**; evidence about **MotiveWave itself** belongs in the sibling repo
  `../motivewave/docs/dynamic/findings.md` (see `CLAUDE.md`; that repo may not exist on a second machine).
- **Standing rules → `CLAUDE.md`**, never only in a chat or in memory.
- **How to run/set up → `docs/runbook.md`; every setting → `docs/configuration.md`.**

## 2. Tone of the record: honest about what is verified

- Tag claims **verified / built-not-deployed / not verified live / unknown**. "Built and unit-tested" is not
  "works". Say which tests mutation-checked what.
- **Never report a number you have not just checked.** Test counts, sizes and versions have been wrong in drafts
  and were only caught by running the thing. Re-derive before writing them into a doc.
- Check a tool's claims against a **real journal**, not only synthetic ones (that is how the heartbeat-clock bug
  — `exchangeTimeMs` vs `localTimeMs` — was found).
- Report outcomes faithfully: failing tests, skipped steps and known gaps are stated plainly, not smoothed over.

## 3. Flag, don't silently fix

When building or testing surfaces a defect **outside the task in hand** (a gap in existing logic, a wrong
assumption, especially anything in safety/risk code): describe it precisely (what triggers it, the consequence,
where), record it in `decisions.md` and a `todo.md` checkbox, and **ask** before changing it. Do not fix it as a
drive-by, even when the fix looks small — the user is often mid-way through that area and wants to choose the
sequencing. Examples that were flagged this way: `RiskChain.checkDailyLoss()` able to block a de-risking exit;
market structure's first CHOCH flip not calling its listener. (Findings that are *inside* the task are fixed as
part of it, and recorded.)

## 4. Review-then-rework for ambiguous rule systems

For anything hand-specified and ambiguous (trading rules, execution logic):
1. Distill the rules into a `docs/dynamic/*.md` file — numbered, plain prose, no implementation vocabulary.
2. Mark **every** under-specified point `⚠️ AMBIGUOUS`; never guess an answer into the distillation.
3. **Stop and wait.** The user reviews on their own time, possibly in several rounds (answers can raise new
   ambiguities).
4. Fold each answer back into the **same** document, marked resolved, replacing the ⚠️ block — one source of
   truth, zero unresolved markers at the end; no parallel "answers" doc.
5. Only then change code, and diff the resolved rules against what the existing code assumed, listing every
   disagreement (no quiet patching).
Used for `marketStructureRules.md` (10 points, 3 rounds) and pending for `orderFlowExecutionRules.md` (the 15
points waiting on the user since 2026-09-20).

## 5. Experiments and parameter sweeps

Rapid "try X instead" runs: report 3-5 headline numbers and one blunt read (positive/negative) in chat plus a
running comparison table; save full per-trade output to a file under `analysis/data/` and give the path; keep one
growing entry in `decisions.md` per sweep, not one per run. Do not claim a cause when a run changed two things;
say when a run does not isolate a variable — but still report it.

## 6. Verification habits (they have caught real bugs)

- **Mutation-test every new piece**: copy the source to a scratch tree, break one behaviour, and a test must fail.
  Survivors are either **equivalent mutants** (say so, with the reason) or a **missing test** (add it). It found
  bracket levels attached to the wrong trade in the report, a report that said "never armed" across the 17:00 CT
  boundary, the stale-cancel-callback case in `LiveOrderTracker`, and weak tests of its own author.
  Put the counts in the decision entry.
- **Extract before you test**: code that cannot run outside MotiveWave (it needs a real `Instrument`, or its
  logging path initialises MotiveWave's UI) is moved **verbatim** into flow-core (or a package-private class) so a
  test can run the real code — never re-implement it in the test. Precedents: `LiveOrderTracker` (D-91),
  `VolumeProfileMath` (D-104).
- **Commit/push only when the user asks**, and never publish a wider claim than the evidence
  ("not deployed / not verified live" goes in the commit message and the decision entry). *(2026-09-26: the user
  explicitly authorised the commit + push of the laptop-preparation work; that does not extend to later work.)*
- **Test in a clean clone** before trusting that the repo is distributable (`git clone` into a temp folder, no
  sibling repos, only the documented environment variables). The first such run found a build-breaking assumption.

## 7. This project's machine quirks (Windows, Git Bash)

- **Write scripts with the file-write tool, then run them.** The Bash tool here mangles inline heredocs
  containing quotes/apostrophes and turns `\n` inside Python `'''` strings into real newlines. When you `grep`
  test/mutation output, also confirm the script actually ran (a hidden SyntaxError looks like "no failures").
- **`build/build.sh` deploys** and wipes `MotiveWave Extensions/dev`. Never run it while a session is running
  (newest `logs/*/decisions.jsonl` modified in the last minute). To run every gate **without** touching MotiveWave:
  `MOTIVEWAVE_EXT_DIR=$(mktemp -d) bash build/build.sh` (D-100). Deploying with the **market closed is still
  worth doing** — it tests start-up/initialisation without ticks (D-102).
- **Inside a `-cp "a;b"` list, use `C:/...` paths**, not `/c/...` — Git Bash does not convert paths inside a
  list, and `javac`/`java` then silently see an empty classpath. In `PATH` (colon-separated) use the POSIX form.
- **This Windows Python has no tz database** (`zoneinfo` cannot find `America/Chicago`): the report computes
  US Central time by hand. Do not `pip install` a package for that. (A second machine may differ — the tools do
  not need one either way.)
- **Source files are a mix of LF and CRLF** (git normalises; the working copy may be CRLF). Edit scripts should
  preserve each file's endings.
- **MotiveWave's own log** (where `FLOW_HOME root=…`, `ACTIVATE …`, `DATA_RECORDER_ON`, `LOG_RETENTION` appear):
  `%APPDATA%\MotiveWave\output\output (<date time>).txt` — the newest file. **Claude cannot drive the MotiveWave
  GUI**: the user adds/removes/activates the study; Claude reads the log and the journal afterwards.
- `.env` is never read or written by Claude (`CLAUDE.md`). Nothing in FLOW_V2 uses it today.
