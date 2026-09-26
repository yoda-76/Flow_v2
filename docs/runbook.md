# Runbook — setting FLOW_V2 up and running it on a machine

**DRAFT, written 2026-09-26** for the move to a rented cloud machine (todo Phase 4).
Everything here is either checked on **this** machine or explicitly marked as not.
It is a how-to, not a decision record: *why* things are the way they are lives in
`docs/dynamic/decisions.md`; every setting is in [`configuration.md`](configuration.md).

**Tags used throughout** — read them; they are the point of this document:

| Tag | Meaning |
|---|---|
| **[VERIFIED]** | Done or observed on this machine, on the date given. |
| **[FROM CODE/DOCS]** | True according to the code or MotiveWave's documentation; not exercised on a second machine. |
| **[UNKNOWN]** | Nobody has checked. Do not assume — test it before relying on it. |
| **[YOU]** | Only you can decide or supply this (credentials, provider, alert channel…). Left blank on purpose. |

**Not yet done by anyone: a full setup on a second machine.** Every "install"
step below is reconstructed from this machine and the scripts; the first real
cloud setup is the test of this runbook, so expect to correct it.

---

## 0. What runs where

- **MotiveWave** (a desktop GUI application) hosts the **FLOW Runtime** study on a
  chart. That is the whole system: it reads ticks/depth from MotiveWave, journals
  them, decides, and — only on the Simulated account, only when armed — orders.
- **Python scripts** (`analysis/*.py`) read the files the runtime wrote under
  `logs/` and `data/`. They never talk to MotiveWave or the broker.
- Nothing else is a service. There is no server, database or daemon **[VERIFIED —
  the repo has none]**. "The system is running" means: MotiveWave is open, a chart
  has the study on it, and it is activated.

The consequence for a cloud machine: it needs a **desktop session that stays
logged in** (MotiveWave is a GUI app). See §9.

## 1. What this machine looks like today (the reference)

| Item | Value | Status |
|---|---|---|
| OS | Windows 10 Home 10.0.19045 | [VERIFIED] |
| MotiveWave | **7.0.28**, Java 26 (64-bit), edition **ORDER_FLOW** (from its own startup log) | [VERIFIED] |
| MotiveWave's bundled runtime | Java **26** (`jre/release`: `JAVA_VERSION="26"`) | [VERIFIED] |
| JDK used to build | Temurin **26.0.2.1** (portable, in the sibling repo `../motivewave/tools/jdk-26.0.2.1+1`) | [VERIFIED] |
| SDK jar | `C:\Program Files (x86)\MotiveWave\lib\mwave_sdk.jar` | [VERIFIED] |
| Extensions folder | `C:\Users\MSI\MotiveWave Extensions` (build deploys to its `dev\` subfolder) | [VERIFIED] |
| Python | 3.14, **standard library only** (no packages to install) | [VERIFIED] |
| Shell for the build | Git Bash (`bash build/build.sh`) | [VERIFIED] |
| Broker/data | Rithmic, via MotiveWave; instrument `@GC` (COMEX gold) | [VERIFIED] |
| Repo remote | `origin` on GitHub (`Flow_v2`) | [VERIFIED] |

**Version coupling that matters — [FROM CODE/DOCS]:** the classes are compiled by
the JDK and loaded by MotiveWave's own bundled runtime. `build.sh` passes no
`--release` flag, so the build JDK must **not be newer than MotiveWave's bundled
Java** (a class file built by JDK 27 will not load on Java 26). Today both are 26.
Also, `mwave_sdk.jar` is compiled against, so a MotiveWave update that changes it
means **rebuild and re-check** (see §10, auto-update).

## 2. Install, step by step

Order matters; each step says what to check.

1. **Install MotiveWave** (same major version as the reference, or expect to
   re-verify — §1). Enable the **Order Flow** edition on the licence **[YOU:
   licence — whether it can be moved to the cloud machine, and any per-machine
   limit, is unknown]**. Start it once so it creates its own folders.
   **[UNKNOWN]:** whether it creates `%USERPROFILE%\MotiveWave Extensions`
   by itself; `build.sh` refuses to run if that folder is missing, and says so
   before compiling anything (D-100).
2. **Get the code**: clone the FLOW_V2 repo. `config/risk.json` and
   `docs/configuration.md` come with it; `logs/`, `data/`, `reports/` are
   git-ignored and start empty.
3. **A JDK 26.** Either place a portable Temurin 26 next to the repo as
   `../motivewave/tools/jdk-26.0.2.1+1` (this machine's layout) **or** put it
   anywhere and set `FLOW_JDK_BIN` to its `bin` folder (§3). Check:
   `<bin>/java -version` says 26.
4. **Git Bash and Python 3** on `PATH`. Check `python --version`.
5. **Tell the scripts where MotiveWave is**, only if it isn't in the default
   places: `MWAVE_SDK_JAR`, `MOTIVEWAVE_EXT_DIR` (§3).
6. **Cloud machine only — log retention.** Create `config/risk.local.json` containing
   `{ "logRetentionHours": 48 }` (git-ignored; the tracked `risk.json` stays identical everywhere). The dev
   machine has no such file and keeps every log. After activating, the MotiveWave log must show
   `RISK_LOCAL_CONFIG … overrides=[logRetentionHours]` and `LOG_RETENTION hours=48` — if it shows
   `RISK_LOCAL_CONFIG_IGNORED` the file is malformed and **nothing is being pruned**.
7. **Dry-run the build without touching MotiveWave**:
   `MOTIVEWAVE_EXT_DIR=$(mktemp -d) bash build/build.sh` — runs every gate
   (24 Java + 3 Python + the build-environment test) and deploys into a scratch
   folder **[VERIFIED on this machine, 2026-09-26]**. It must end `exit 0`.
   On a non-Windows machine the `:` classpath separator and the `PATH`
   fall-back for the JDK have only been tested by `build/env_test.sh`, never by a
   real Linux build **[UNKNOWN]** — and whether MotiveWave runs at all on the cloud
   machine's OS, and how this study behaves there, is untested.
8. **Real build + deploy**: only when no session is running (§7): `bash build/build.sh`.
   It **wipes `MotiveWave Extensions/dev`** and copies the compiled classes in.

## 3. Where FLOW_V2 finds things (all optional overrides)

| Variable | Read by | Default | Details |
|---|---|---|---|
| `FLOW_HOME` | the runtime (JVM) and the Python tools | runtime: `C:/yadvendra/trading/FLOW_V2`; tools: the current directory | project root: `logs/`, `data/`, `config/risk.json`, `reports/`. Runtime reads it **once at MotiveWave start** — restart MotiveWave to change it, and set an environment variable *before* MotiveWave launches. (`-Dflow.home=…` on MotiveWave's JVM also works; how to pass JVM options to MotiveWave is **[UNKNOWN]**, so prefer the environment variable.) |
| *(file)* `config/risk.local.json` | the runtime | none — optional, git-ignored | **per-machine override** of any `risk.json` key. On the **cloud machine create it with `{ "logRetentionHours": 48 }`**; on the dev machine leave it out (keep all logs). Details and failure behaviour: `configuration.md` ("Log retention", "Per-machine overrides"). |
| `FLOW_JDK_BIN` | `build/*.sh` | `../motivewave/tools/jdk-26.0.2.1+1/bin`, then `PATH` | set-but-wrong is an error, never a silent fall-back |
| `MWAVE_SDK_JAR` | `build.sh` | `C:/Program Files (x86)/MotiveWave/lib/mwave_sdk.jar` | |
| `MOTIVEWAVE_EXT_DIR` | `build.sh` | `/c/Users/MSI/MotiveWave Extensions` | deploy target's parent |

Full detail and the reasoning: `configuration.md` ("Where FLOW_V2 lives…", "Where
the build finds…"), D-98, D-100, D-101. **A new machine with `FLOW_HOME` unset
would use `C:/yadvendra/trading/FLOW_V2` for the runtime — set it, or the
runtime will try to write to a path that does not exist there.** (`logs/` is
created if missing; `config/risk.json` is *not* — a missing file means every
default, with `RISK_CONFIG_MISSING` in MotiveWave's log.)

## 4. MotiveWave set-up (one-time, in the GUI)

I cannot drive the MotiveWave GUI; these are things **you** do. Items already
seen working are marked.

1. **Connect Rithmic** **[YOU]** — credentials live in MotiveWave's own connection
   settings. They never go in this repo, in `.env` (nothing in FLOW_V2 reads
   `.env`), in a chat, or in a doc. Never paste them anywhere Claude can read.
   MotiveWave's startup log shows a Rithmic alert
   `get_order_book … permission denied` for the contract's *historical* order book.
   That is MotiveWave's own backfill, is harmless, and does not affect live depth
   **[VERIFIED — the live DOM subscription and liquidity-map recording worked
   alongside it, 2026-09-26; see the motivewave sibling repo's findings]**. Whether
   your data tier on the new machine has depth permissions is **[UNKNOWN]**.
2. **Enable the Simulated Account and "Sim Trade Only"** — *Configure → Settings →
   General → Simulated Account tab → Enabled*, and the **Sim Trade Only**
   checkbox on that same panel **[FROM MOTIVEWAVE DOCS; seen enabled and confirmed
   via the account selector reading "simulated", 2026-09-12]**. This is the
   platform-wide guarantee that no order can reach a real account
   (`CLAUDE.md`, README "Safety"). **Confirm it on every new machine and every
   activation**; a past confirmation does not carry over. If the account shown is
   anything other than the Simulated one, **stop** — that is a safety stop, not a
   preference.
   **On a fresh install, do this FIRST — before connecting Rithmic and before adding
   the study.** The setting is **per installation**; a new machine must be assumed to
   have it **OFF**. **There is no code-level backstop:** the runtime cannot tell which
   account is active (the SDK has an `Account` type with `getName()` but nothing in
   the SDK hands one out — searched 2026-09-26), so this checkbox plus a human reading
   the selector is the *entire* guard against a real-account order. Do not arm anything
   until both are confirmed on that machine.
3. **Open an `@GC` (continuous gold) 1-minute chart.** The market-structure feature
   warm-starts from **100 historical bars** of that chart at activation
   (`FLOW_MS_WARMSTART_BARS`), so the chart must have loaded at least that many.
4. **Add the study**: on the chart, **FLOW menu → FLOW Runtime**; open its
   **Runtime** tab. Settings and what each does: `configuration.md` §2.
   For a first start: Strategy Id `level_zone_observer`, **Mode `DRY_RUN`,
   Armed unchecked**, then **Activate**. Two habits from the incident history:
   - Removing and re-adding a study **resets its settings to the code's defaults**
     (an already-added study keeps its saved values; that is also how to pick up
     a changed default).
   - Look at the study's **Trading Options** tab once. The 2026-09-11 incident (a
     real order placed while exploring a strategy's control box) came from an
     inherited default, and that experiment saw re-adding a study silently reset
     Trading Options to their defaults. The runtime overrides every order-capable
     hook and a test enforces it, so this is a belt-and-braces glance, not a fix.
5. **Big-trade Min Size** is currently **1** (a test value, D-90); the intended
   value is **10**. Change it in the study's Runtime tab (or re-add the study).
   **Do this before any long recording** — at 1, nearly every tick is a "big trade".

## 5. First-start checklist (what "initialised correctly" looks like)

Verified on this machine 2026-09-26 (market closed — the order book and the clock
still flow, so start-up is testable without ticks; D-102). After activating, the
newest file in `C:\Users\MSI\AppData\Roaming\MotiveWave\output\` (`output (<date time>).txt`)
must show, in order:

```
FLOW_HOME root=<your path> source=<built-in default | environment variable FLOW_HOME | system property flow.home>
[RISK_LOCAL_CONFIG path=… overrides=[…]]          <- only if config/risk.local.json exists
LOG_RETENTION hours=<0 = keep everything | 48> rawJsonlDeleted=<n> featureLogsDeleted=<n>
DATA_RECORDER_ON root=<…>\data dataIntervalSec=1 liquidityIntervalSec=1 keepTradingDays=7
SUBSCRIBED_DOM symbol=@GC
SESSION_START strategyId=level_zone_observer symbol=@GC
ACTIVATE pos=0 cash=<balance> -- CONFIRM: is this the Simulated account? …
```

- The two lines `RISK_LOCAL_CONFIG` / `LOG_RETENTION` are **new (D-106) and not yet seen live** — the change is
  built and tested but was not deployed when this was written. **`RISK_LOCAL_CONFIG_IGNORED` is a problem**: your
  override file is malformed and is being ignored (nothing is pruned).
- **The `ACTIVATE` line shows a cash balance, not an account name.** Confirm the
  account with your own eyes in the Strategy Control Box (must read "simulated").
- **No `RISK_CONFIG_MISSING`**, no Java exception in that file.
- A new folder `logs/level_zone_observer_<ms>_inst<id>/` with `decisions.jsonl`
  and `raw.jsonl`. `python analysis/status.py` should print `ALIVE | … | armed: no
  DRY_RUN (as of Ns ago) | … | data: ok` (exit code 0).
- `decisions.jsonl` holds `session_header` (real `mode`, `armedSetting`),
  `risk_config_loaded` (with `flattenLeadMinutes`/`noEntryLeadMinutes`),
  `price_anchor`, `arming_state`, and heartbeats every ~10 s carrying `armed` and
  `runtime`.
- `data/liquidity_map/<session>.jsonl` grows one line per second;
  `data/market_structure/<session>.jsonl` has its `warm_start` line.
- **Expected and fine on a weekend:** a `SESSION_FLATTEN_DUE` record (the weekend
  is inside the flatten window — D-92; in DRY_RUN it sends no order), and **no**
  `data/vwap/`, `footprint/`, `big_trades/`, `bars/` files until something trades /
  a bar closes. The status line does not treat a missing `vwap` file as a fault
  (D-103).

**Not verifiable without ticks or orders — still unverified** on any machine: the
footprint / VWAP / big-trade / OHLCV recorders and everything about orders
(order tracker, flatten, `order_fill`, the partial-fill rule). The market-open
batch in `todo.md` §2 is the plan for those.

## 6. Daily routine

- **Start of the day.** Trading days run 17:00 CT → 17:00 CT (Sunday 17:00 CT is
  Monday's session). Check the **exchange calendar yourself** — holidays and early
  closes are not modelled (D-93). `python analysis/status.py`.
- **Arming.** The default is unarmed / `DRY_RUN`. What is allowed and what you must
  state before arming is in `CLAUDE.md` (the "Hard rule — never place an order"
  section; the 2026-09-24 sprint authorization there covers **Simulated account
  only**; the **real account is forbidden**). To trade on Sim: Mode `SIM_LIVE`
  **and** Armed checked, within the bounds in `config/risk.json` (1 contract, daily
  loss limit 200 ticks). Read the bounds in `configuration.md` §1.
- **Window edges (all America/Chicago, all `config/risk.json`):** entries stop at
  15:45 CT, open positions are flattened from 15:55 CT until the 17:00 CT reopen,
  and from Friday 15:55 CT until Sunday 17:00 CT. In India time that is roughly
  02:15 / 02:25 IST (03:15 / 03:25 in US winter time).
- **Check-ins:** `python analysis/status.py` — one line, exit code 0 alive / 1
  stale-or-stopped / 2 down. `heartbeat Ns ago` is the runtime's own clock, so a
  growing number means the *system* stopped, not a quiet market.
- **Evening review (1–2 h):** `python analysis/daily_report.py`, then
  `python analysis/trade_view.py --date D --trade N` for any trade needing a "why".
- **A position is open when MotiveWave/the machine restarts:** the runtime **refuses to
  arm** and journals what it found; it does not adopt or flatten it. You clear it by
  hand (README "Restart with a live position"). Whether `onActivate` sees the live
  position immediately after a restart is **[UNKNOWN]** (`plumbingEdgeCases.md`
  §11) — after any restart, look at the account before arming.

## 7. Deploying a code change

1. **Confirm no session is running**: the newest `logs/*/decisions.jsonl` was not
   modified in the last minute (`status.py` shows `STOPPED`/`DOWN`, not `ALIVE`).
   Deploying while a study runs is the same as pulling files out from under it.
2. Test first without deploying: `MOTIVEWAVE_EXT_DIR=$(mktemp -d) bash build/build.sh`.
3. Deploy: `bash build/build.sh`. It refuses to deploy if any gate fails.
4. **Remove and re-add the study** on the chart (a running study keeps the old
   class). Re-run the §5 checklist.
5. Read the `todo.md` "PENDING LIVE TEST" notes for what the change owes.

Side effects to remember: the deploy also removes experiment studies deployed from
the sibling `motivewave` repo (both repos deploy into the same `dev` folder and
each wipes it).

## 8. Time zones — [VERIFIED in code and live]

The machine's time zone does **not** change any behaviour: the session boundary and
trading window are computed in `America/Chicago` explicitly (`SessionBoundary`,
`TradingWindow`), on the JVM's UTC clock. That this works inside MotiveWave's own
bundled runtime was seen live (the weekend flatten window fired at startup on a
Saturday). The Python tools compute Central time by hand (this Windows Python has
no tz database) and print an India clock as a fixed `UTC+05:30` label — cosmetic,
fine anywhere. What *does* depend on the machine: **its clock being correct** —
see §9. **[YOU]:** whether you want the chart/UI in a particular zone.

## 9. Unattended running — almost all of this is [UNKNOWN]

Nothing in this section has been tried. Do not rely on any of it until it has been.

- **A desktop session that stays logged in.** MotiveWave is a GUI app and needs one.
  How a cloud provider keeps it (auto-login, a disconnected-but-active RDP session,
  a dedicated user) **[YOU / UNKNOWN]**.
- **Auto-start MotiveWave at boot/login, reconnect Rithmic, reopen the workspace,
  and re-activate the study.** Today, after any MotiveWave or PC restart **the study
  must be re-activated by hand** (todo Phase 1, "restart / auto-recovery policy").
  Whether a saved workspace re-activates a study on its own is unknown.
- **Machine settings:** no sleep/hibernate; no forced Windows-update restarts;
  a running time-sync service (the system trusts the local clock). **[YOU]**
- **Soak:** nothing has run longer than one session; memory over days is unmeasured
  (todo Phase 3). The first 24/7 run *is* the soak test — watch memory.
- **Feed loss / Rithmic disconnects:** deliberately not watched (D-93). A position
  open through a disconnect is protected only by its resting stop/target legs.
- **Remote monitoring** **[YOU]**: how you look at the machine (remote desktop, a
  VPN, a shared folder, …) is undecided. What exists: `status.py` and the report,
  which read files. Running them *on* the machine and looking at the output is the
  only supported way today.
- **Alerts** **[YOU]**: nothing tells you when the system disarms itself, trips the
  daily-loss kill switch, loses the feed, or the disk fills. That needs a delivery
  channel (email, phone push, chat) chosen first — todo §4. Until then the daily
  report's "Needs attention" section and the status line are the only signals.

## 10. Things that will bite an unattended run (known, not fixed)

| Risk | Detail | Status |
|---|---|---|
| **MotiveWave auto-update** | The startup log shows `Check updates, interval: 7`, then it downloaded `Release Notes v7.1.1.pdf` and logged `Auto Update: Download complete!` while 7.0.28 is installed (what exactly was downloaded, and whether it installs itself, is **[UNKNOWN]**). An update can change `mwave_sdk.jar` or the bundled Java. | **Decided (2026-09-26, D-106): leave it exactly as is for now.** The user will install the new version **during a live market** so it can be tested immediately; until then nothing is changed or pinned. After installing: re-run §2 step 7 and §5 (and check the JDK/`mwave_sdk.jar` pairing in §1) before trusting anything. |
| **Feature logs** | `logs/*_feature*.log` (`liquidity_map_`, `volume_profile_`, `vwap_`, `footprint_`, `big_trade_`, `order_repeat_`, `market_structure_`) used to be single files appended forever. **Fixed (D-106):** they now roll **daily** (`vwap_feature_<yyyy-MM-dd>.log`) and are pruned by the `logRetentionHours` setting together with the past sessions' `raw.jsonl`. **Dev machine: 0 = keep everything. Cloud machine: 48**, set in `config/risk.local.json` (§3, `configuration.md`). | Built and unit-tested; **not yet run inside MotiveWave** (deploy pending) — the first cloud run is also the first real test of pruning. A busy 24/7 day's log volume is still **unmeasured**. |
| **`decisions.jsonl` kept forever** | ~57 KB in 19 min of an idle market (~180 KB/h); on a live market far more — the 87 session folders here total ~390 MB and the largest single `decisions.jsonl` is **~20 MB** (a long session on 2026-09-22). The bulk is the **old 10 s `liquidity_snapshot` record** (D-58), which `todo.md` already plans to drop once `data/` is proven (`data/liquidity_map/` records the book far better). `raw.jsonl` pruning worked at the old fixed 48 h (only today's remained); it is now the `logRetentionHours` setting. | By design (D-15). Plan disk on ~20 MB/session × sessions, or drop the old snapshot first. |
| **`data/` is bounded** | rolling 7 trading days; the liquidity map dominates (≈ 4–5 KB/s ≈ 3 GB per 7 days, measured 2026-09-24). | Sized by `dataKeepTradingDays`. |
| **Partial fills** | A partial that neither completes nor cancels leaves an unbracketed position (no timeout). Unreachable while `maxContracts` = 1. | D-99, provisional. |
| **Holidays / early closes** | Not modelled. | D-93 — your calendar check. |
| **Untested order code on the real platform** | Order tracker extraction, session-end flatten, `order_fill` records, partial-fill rule: all built and unit-tested, none seen live. | `todo.md` §2 — do the market-open batch **before** trusting an unattended Sim run. |
| **No dense candles** | A second with no trade writes no footprint candle. | D-90, by design. |

## 11. What to copy / not copy when moving machines

- **In git (comes with a clone):** all code, `config/risk.json`, docs, the fixtures.
- **Not in git, recreated:** `logs/`, `data/`, `reports/` — the machine's own
  history. **The nightly report needs the journals of the days it covers**, so if
  you want continuity across a move, copy `logs/*/decisions.jsonl` (small, kept
  forever) — the `raw.jsonl` files are pruned at 48 h anyway and `data/` is rolling.
- **Not in git, yours:** MotiveWave's own settings and workspace
  (`%APPDATA%\MotiveWave\`), the Rithmic credentials, the licence. **[YOU]**

## 12. Left for you to fill in

| Decision / value | Your answer |
|---|---|
| Cloud provider and machine size (RAM matters: MotiveWave + a 24/7 study) | |
| OS of the cloud machine (the whole repo has only run on Windows 10) | |
| How you reach it (remote desktop / VPN / …) | |
| How the desktop session stays logged in and MotiveWave auto-starts | |
| Alert channel (email / push / chat) and what triggers it | |
| MotiveWave auto-update policy | **Decided 2026-09-26:** leave as is; install the new version in a live market to test it immediately (see §10). |
| Whether the MotiveWave licence and Rithmic login can run there | |
| Disk plan (`logs/` retention) | **Decided 2026-09-26:** dev machine keeps everything (`logRetentionHours` 0); cloud machine **48 h** (`config/risk.local.json`). Total disk size for the cloud machine: |
| Trading days/hours you want it armed vs recording-only | |
| Laptop trial: which MotiveWave version to install (dev machine is **7.0.28**; a fresh download is probably newer and untested with this code) | |
| Laptop trial: can the Rithmic login be used on two machines at once? (**[UNKNOWN]** — do not run both at the same time until checked) | |
| Big-trade Min Size for the first live take: keep **1** (capture test, noisy) or set **10** | |

## 13. Trial run on a second machine (the spare laptop)

The point: prove the repo is **distributable** — that someone with only a clone and the prerequisites can get to
a working, verified start-up. This runbook is the script; **every place it is wrong, unclear or missing something
is the result you want.** Write the corrections down as you go (or paste them to Claude afterwards).

**Already checked from this machine, without a second machine (2026-09-26)** — a *fresh local clone in a temp
folder with no sibling `motivewave` repo*, building with only `FLOW_JDK_BIN`/`MOTIVEWAVE_EXT_DIR` set: every gate
passed and it deployed to a scratch folder. That test **found and fixed a real bug** (the build's own environment
test hard-coded this machine's layout and would have stopped the build on any other machine). It cannot check: a
different Windows install, a different MotiveWave install, a different JDK layout, the `:`-separator/non-Windows
paths, or anything that needs MotiveWave running.

**Before you start**
1. **Push the commits.** The laptop pulls from GitHub, and this machine's recent work (D-98 … D-106) is
   committed locally but **not pushed** — `git status` says "ahead of origin". Claude will not push unless you
   ask. Until pushed, the laptop would get an old, broken-on-a-new-machine version.
2. Decide what the laptop is for: a **build-and-deploy trial with MotiveWave** (needs the licence/login you
   are willing to put there — **[YOU]**), or a **build-only trial** (no MotiveWave: JDK + Git Bash + Python only,
   `MOTIVEWAVE_EXT_DIR=$(mktemp -d)` — proves everything except the MotiveWave parts).
3. Keep the **Simulated-account / "Sim Trade Only"** rule exactly as on this machine (§4.2). The laptop is *not*
   a place to arm anything real; use `DRY_RUN`, Armed unchecked, for the whole trial.

**Steps** (follow the sections in order; note where you had to guess)
1. §1: write down the laptop's OS, MotiveWave version (if any), Java, Python — the differences are the finding.
2. §2 steps 1-5, then step 7 (the scratch-folder build). It must end `exit 0` — **if it does not, that is the
   most valuable thing you can report** (paste the last 30 lines).
3. §3: set what you need (`FLOW_JDK_BIN`, `FLOW_HOME`, …). Note which one you forgot and what it said.
4. If trialling with MotiveWave: §4, then §2 step 8 (real build/deploy), then the §5 first-start checklist —
   with the market closed you can still verify start-up (D-102).
5. Run `python analysis/status.py` and `python analysis/daily_report.py` (§5, §6) on the laptop's own journal.
6. **For a trial that must be identical to the dev machine, do NOT create `config/risk.local.json`** (the dev
   machine has none and keeps every log). Only if you want to test log retention there: create `config/risk.local.json` with `{ "logRetentionHours": 48 }`
   (§2 step 6) and confirm the `LOG_RETENTION hours=48` line.

**Phase 2 — trading hours (the user arms and runs `lvn_fade_test` on the Simulated account).** The full
checklist, what "as expected" looks like, the stop conditions and what the Claude on that machine must *not* do
are in the **"LAPTOP TRIAL HANDOFF"** block at the top of `docs/dynamic/todo.md`. Two facts to hold onto: (1) the
order code was **live-verified on Sim on 2026-09-21–23 but has changed since** (D-91/D-92/D-94/D-97/D-99) and none
of that has run with ticks or orders — that first live session is a test on either machine; (2) Phase 1 (below) is
safe by construction (`DRY_RUN`, Armed unchecked), Phase 2 is the only time an order can exist.

**What a pass looks like:** the scratch build exits 0; (with MotiveWave) the §5 lines appear and
`status.py` prints `ALIVE … data: ok`; nothing needed a path or file that only exists on this machine.

## 14. Sources

`docs/configuration.md` (settings) · `README.md` (architecture, "Commands you run",
"Safety") · `CLAUDE.md` (rules incl. the order-placement exceptions) ·
`docs/dynamic/decisions.md` D-92 (flatten), D-93 (no watchdog), D-98/D-100/D-101
(paths), D-99 (partial fills), D-102/D-103 (first deploy, startup check) ·
`docs/dynamic/todo.md` (the plan; §2 market-open batch) · `docs/dynamic/
plumbingEdgeCases.md` §11 (restart with a position) · the sibling repo
`../motivewave/docs/dynamic/findings.md` (platform facts: Sim Trade Only, the
2026-09-11 incident, the Rithmic depth alert).
