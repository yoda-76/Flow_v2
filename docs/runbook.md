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

**Done once on a second machine: the spare laptop, Phase 1, 2026-09-27 (D-110).** It went from a fresh clone to
a verified start-up. The corrections it produced are folded in below and marked *(laptop, D-110)*. The cloud
machine is still untested, so expect more corrections.

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
   It creates `%USERPROFILE%\MotiveWave Extensions` by itself (empty) **[VERIFIED, laptop, D-110]**;
   `build.sh` refuses to run if that folder is missing, and says so before compiling
   anything (D-100).
2. **Get the code**: clone the FLOW_V2 repo. `config/risk.json` and
   `docs/configuration.md` come with it; `logs/`, `data/`, `reports/` are
   git-ignored and start empty.
3. **A JDK 26.** Either place a portable Temurin 26 next to the repo as
   `../motivewave/tools/jdk-26.0.2.1+1` (this machine's layout) **or** put it
   anywhere and set `FLOW_JDK_BIN` to its `bin` folder (§3). Check:
   `<bin>/java -version` says 26.
   A fresh machine has **no** JDK. What worked *(laptop, D-110)*, in Git Bash, from the folder that holds the
   clone:
   `mkdir -p motivewave/tools && cd motivewave/tools && curl -sSL -o jdk26.zip "https://api.adoptium.net/v3/binary/version/jdk-26.0.2.1%2B1/windows/x64/jdk/hotspot/normal/eclipse" && unzip -q jdk26.zip && rm jdk26.zip`
   This puts the JDK in the default location next to the clone, so no variable is needed. A JDK
   **older** than MotiveWave's bundled Java is fine: JDK 26 classes load in 7.1.1's Java 27 **[VERIFIED, laptop]**.
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
| `FLOW_HOME` | the runtime (JVM) and the Python tools | runtime: `C:/yadvendra/trading/FLOW_V2`; tools: the current directory | project root: `logs/`, `data/`, `config/risk.json`, `reports/`. Runtime reads it **once at MotiveWave start** — restart MotiveWave to change it, and set an environment variable *before* MotiveWave launches. What worked *(laptop, D-110)*: `setx FLOW_HOME "D:\yadvendra\FLOW_V2"` (PowerShell or cmd), then **quit MotiveWave completely and start it again from the Start menu**. The log then says `source=environment variable FLOW_HOME`. (`-Dflow.home=…` on MotiveWave's JVM also works; how to pass JVM options to MotiveWave is **[UNKNOWN]**, so prefer the environment variable.) |
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

> **Do these in this order on every new machine — the order is the safety guard** *(reordered 2026-09-27 after
> the laptop trial connected Rithmic before enabling Sim Trade Only)*:
> 1. Install and start MotiveWave **without** connecting Rithmic.
> 2. **Enable the Simulated Account and "Sim Trade Only"** (step 1 below) and check the account selector reads
>    "simulated".
> 3. Only then connect Rithmic (step 2), open the `@GC` chart (step 3) and add the study (step 4).
>
> **"Sim Trade Only" stays enabled at all times, on every machine, until the user explicitly says otherwise**
> (user, 2026-09-27). It is never switched off for a test, a check or a restart.

1. **Enable the Simulated Account and "Sim Trade Only"** — *Configure → Settings →
   General → Simulated Account tab → Enabled*, and the **Sim Trade Only**
   checkbox on that same panel **[FROM MOTIVEWAVE DOCS; seen enabled and confirmed
   via the account selector reading "simulated", 2026-09-12; again on the laptop, 2026-09-27]**. This is the
   platform-wide guarantee that no order can reach a real account
   (`CLAUDE.md`, README "Safety"). **Confirm it on every new machine and every
   activation**; a past confirmation does not carry over. If the account shown is
   anything other than the Simulated one, **stop** — that is a safety stop, not a
   preference.
   The setting is **per installation**; a new machine must be assumed to
   have it **OFF**. **There is no code-level backstop for the account:** the runtime cannot tell which
   account is active (the SDK has an `Account` type with `getName()` but nothing in
   the SDK hands one out — searched 2026-09-26), so this checkbox plus a human reading
   the selector is the *entire* guard against a real-account order. Do not arm anything
   until both are confirmed on that machine.
2. **Connect Rithmic** **[YOU]** — credentials live in MotiveWave's own connection
   settings. They never go in this repo, in `.env` (nothing in FLOW_V2 reads
   `.env`), in a chat, or in a doc. Never paste them anywhere Claude can read.
   **One Rithmic login runs on one machine at a time** — exit MotiveWave on the other machine first
   **[VERIFIED, laptop, D-110]**.
   MotiveWave's startup log shows a Rithmic alert
   `get_order_book … permission denied` for the contract's *historical* order book.
   That is MotiveWave's own backfill, is harmless, and does not affect live depth
   **[VERIFIED — the live DOM subscription and liquidity-map recording worked
   alongside it, 2026-09-26 and on the laptop 2026-09-27]**.
3. **Open an `@GC` (continuous gold) chart with 20-second bars.** *(2026-09-27, user: 20-second bars while the
   plumbing is being tested — more bars, faster. Use the same bar size on every machine so their market-structure
   output is comparable; the dev machine's older sessions used 1 minute.)* The market-structure feature
   warm-starts from **100 historical bars** of that chart at activation
   (`FLOW_MS_WARMSTART_BARS`) — about 33 minutes at 20 s — so the chart must have loaded at least that many.
   **Only gold may be traded** *(2026-09-27, user: "for now only GC is allowed")*: on any other chart the runtime
   logs `REFUSE_TO_ARM instrument … is not allowed` and never arms or sends an order, even if *Armed* is ticked
   (`InstrumentPolicy`; `@GC` and explicit gold contracts such as `GCZ6` are allowed). It still records, into that
   instrument's own data folder (`data/<construct>/<symbol>/…`, findings F-1). After activating, check the log says
   `SUBSCRIBED_DOM symbol=@GC` and has no `REFUSE_TO_ARM`.
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
newest file in `%APPDATA%\MotiveWave\output\` (`output (<date time>).txt`)
must show, in order:

```
FLOW_HOME root=<your path> source=<built-in default | environment variable FLOW_HOME | system property flow.home>
[RISK_LOCAL_CONFIG path=… overrides=[…]]          <- only if config/risk.local.json exists
LOG_RETENTION hours=<0 = keep everything | 48> rawJsonlDeleted=<n> featureLogsDeleted=<n>
DATA_RECORDER_ON root=<…>\data symbolDir=GC dataIntervalSec=1 liquidityIntervalSec=1 keepTradingDays=7
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

## 11a. Cloud machine sizing — system requirements (worked out 2026-09-27)

Written so the numbers don't have to be re-derived. **Measured** figures are from the dev machine; everything else is
an estimate and says so. Re-measure during the first live sessions (see "How to get the real numbers" below) and
update this section.

### Measured on the dev machine

| What | Value | Source |
|---|---|---|
| Dev machine | Intel i5-11400H, 6 cores / 12 threads, 7.7 GB RAM, Windows 10 Home | [VERIFIED 2026-09-27] |
| MotiveWave Java heap cap | **1,974 MB** — the default (≈ ¼ of RAM) because `MAX_HEAP=` is empty in `%APPDATA%\MotiveWave\startup.ini` | startup log line `Max Memory: 1974 MB` |
| MotiveWave memory, study running, **market closed** | ~175 MB working set, **768 MB peak** | `Get-Process MotiveWave` |
| MotiveWave memory, **live market, ~45 min trading + recording** (2026-09-28, dev machine) | **605 MB working set, 747 MB peak**, 448 s CPU over ~1 h 45 min; 0 lag blocks, heartbeat gaps ≤ 10 s; liquidity map ≈ 13 MB/h | `Get-Process MotiveWave`, `liveTest-2026-09-28.md` |
| MotiveWave memory, **busy live market, multi-day** | **not measured** (no soak test yet) — the volume-profile rotation (E-3) was sized to keep its own peak ~150–180 MB | — |
| Raw tick journal (`raw.jsonl`, ticks + top-of-book) | **~28 MB/hour** ≈ 0.7 GB/day | `decisions.md` (raw-tier measurement) |
| Liquidity map recording (`data/liquidity_map/`, 1 s, ±100 ticks) | **~4–5 KB/s** ≈ **0.4 GB per trading day** — most of `data/` | D-88 measurement, 2026-09-24 |
| Other recorded constructs (footprint, VWAP, big trades, bars, market structure) | small next to the liquidity map | D-88 |
| `decisions.jsonl` (kept forever) | up to **~20 MB** for a long busy session (mostly the old 10 s `liquidity_snapshot`) | §10 |
| Full per-order DOM (NOT recorded, for reference) | ~31.5 GB/hour raw | D-35 — why it is not stored |

### Recommendation

| | Minimum | **Recommended for 24/7 recording + forward testing** |
|---|---|---|
| CPU | 2 vCPU | **4 vCPU** |
| RAM | 8 GB | **16 GB**, and set `MAX_HEAP=4096` in MotiveWave's `startup.ini` (restart MotiveWave after) |
| Disk | 60 GB SSD | **250 GB SSD** (see the retention table) |
| OS | Windows with a desktop | **Windows Server 2022 (Desktop Experience) or Windows 11 Pro** — not Home (forced update restarts). Whether MotiveWave behaves on a *server* OS is **[UNKNOWN]**. |
| Region | any | **US Central (Chicago)** — closest to Rithmic / CME |
| GPU | not needed | not needed — MotiveWave would fall back to software rendering on a GPU-less VM **[UNKNOWN] how well** |
| Network | stable broadband | the full-depth feed is ~33 updates/s of the whole book — probably a few Mbps, **not measured** |

### Disk for research (how long to keep recorded data)

`data/` keeps **7 trading days** by default (`dataKeepTradingDays`). To build a large research dataset on the cloud
machine, raise it in that machine's git-ignored `config/risk.local.json`. At ≈ 0.45 GB per trading day:

| Keep `data/` for | `dataKeepTradingDays` | Disk for `data/` |
|---|---|---|
| 1 week (default) | 7 | ~3 GB |
| 1 month | ~21 | ~10 GB |
| 6 months | ~126 | ~60 GB |
| 1 year | ~252 | ~115 GB |

Plus: Windows + MotiveWave + its history (~30–40 GB), `logs/` (with `logRetentionHours: 48` raw journals stay ~1.5 GB;
`decisions.jsonl` grows ~0.5 GB/month), headroom. **250 GB ≈ a year of recordings with room to spare.** Back `data/`
up off the machine (e.g. a nightly copy to cloud storage) — a disk failure would otherwise take the research data
with it.

Example cloud `config/risk.local.json` for research + 24/7:

```json
{ "logRetentionHours": 48, "dataKeepTradingDays": 252 }
```

### Settings for an unattended machine

- **Turn chart drawing off** in the study's settings (heatmap, footprint, market structure, big trades, entry
  arrows): nobody watches the chart, and drawing runs on MotiveWave's tick thread every second (codeReview.md E3).
  Recording and trading do not depend on drawing.
- **Keep Windows time sync on.** Since D-111 every risk rule (entry window, flatten, daily reset, dwell) runs on the
  machine's own clock; the dev machine's clock was ~2.3 s off the exchange (codeReview.md E4).
- Power/updates/auto-login: §9.

### How to get the real numbers (do this in the first live sessions)

Every hour or so during a live session, in PowerShell:

```powershell
Get-Process MotiveWave | Select @{n='MB';e={[int]($_.WorkingSet64/1MB)}}, @{n='PeakMB';e={[int]($_.PeakWorkingSet64/1MB)}}, CPU
```

and at the end of a trading day: `du -sh data/* logs` (Git Bash). Write the results into the "Measured" table above
with the date. If the peak approaches the heap cap (1,974 MB by default), raise `MAX_HEAP` before going 24/7.

### Still unknown (check before paying for a long rental)

- Memory and CPU on a busy market over several days (soak test, todo Phase 3).
- MotiveWave on Windows Server and on a GPU-less VM.
- Whether the MotiveWave licence and the Rithmic login may run on a cloud machine (and on two machines at once).
- The feed's real bandwidth.

## 12. Left for you to fill in

| Decision / value | Your answer |
|---|---|
| Cloud provider and machine size | Sizing worked out in **§11a** (recommended: 4 vCPU, 16 GB RAM, 250 GB SSD, US Central). Provider: |
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

**The complete test sheet — every doubt, how to test it, what a pass looks like — is §13.1 below. Work through it; the steps above are the outline, the sheet is the checklist.**

**What a pass looks like:** the scratch build exits 0; (with MotiveWave) the §5 lines appear and
`status.py` prints `ALIVE … data: ok`; nothing needed a path or file that only exists on this machine.

**Result — Phase 1 on the laptop, 2026-09-27: PASS** (D-110 has the filled-in sheet). Still open after it: A9
(whether a first deploy needs a MotiveWave restart), all of section D (Phase 2), and findings F-1 (data files
keyed by day only). **B3 answered: one Rithmic login cannot be used on two machines at once** — exit MotiveWave on
one before starting the other. **D6**: check the power plan with `powercfg /q SCHEME_CURRENT SUB_SLEEP STANDBYIDLE`;
the plugged-in value must be `0x00000000` (never). The laptop's first setting was 5 min, and the next was 45 min.

### 13.1 Doubts and needs this trial must answer (the test sheet)

Everything below is something **nobody has checked on a second machine**, or a difference from the dev machine
that could matter. Run it as a checklist; write **PASS / FAIL / not-tried + what you saw** next to each, and hand the
sheet back (it becomes a `decisions.md` entry). "Phase" = when it can be tested: **1** = off-market, **2** = trading
hours. Rows marked **Already checked** are here so the laptop's Claude does not redo them.

**A. Layout, install and build (Phase 1)**

| # | Doubt / need | How to test | Pass looks like |
|---|---|---|---|
| A1 | **Where the laptop's clone lives.** The runtime's default `FLOW_HOME` is `C:/yadvendra/trading/FLOW_V2`; the Python tools default to the current directory. A clone anywhere else + no `FLOW_HOME` → the runtime silently creates `C:\yadvendra\trading\FLOW_V2\logs\…` while `status.py` looks in the clone and says `DOWN` (a confusing split). | **Recommended for an identical trial:** clone to exactly `C:\yadvendra\trading\FLOW_V2` — no variable needed. Otherwise set the `FLOW_HOME` environment variable **before launching MotiveWave** (restart it after setting). | The `FLOW_HOME root=… source=…` line names the folder you expect; the journal appears under *that* folder's `logs/`. |
| A2 | **Windows user name in the default extensions path.** `build.sh` defaults to `/c/Users/MSI/MotiveWave Extensions`. The laptop's user is different. | Expect the **first** build to stop at once with `MotiveWave extensions folder not found … set MOTIVEWAVE_EXT_DIR`; set it (e.g. `export MOTIVEWAVE_EXT_DIR="/c/Users/<you>/MotiveWave Extensions"`). Also check the SDK jar: `MWAVE_SDK_JAR` if MotiveWave is not under `C:/Program Files (x86)/`. | Message names the variable; after setting it the build runs. |
| A3 | **JDK layout.** Default is `../motivewave/tools/jdk-26.0.2.1+1/bin` (dev layout). | Either put a Temurin 26 at `C:\yadvendra\trading\motivewave\tools\jdk-26.0.2.1+1` (identical layout) **or** set `FLOW_JDK_BIN`. `java -version` must say 26. | `env_test.sh` prints PASS (dev-layout checks print SKIP on a different layout — fine). |
| A4 | **MotiveWave's bundled Java = the JDK that built the classes.** `build.sh` has no `--release` flag; a class built by a JDK newer than MotiveWave's Java will not load. | Read `<MotiveWave install>\jre\release` → `JAVA_VERSION="…"`. Dev machine: 26 (MotiveWave 7.0.28). | Same major version as the JDK you build with (or older JDK). |
| A5 | **MotiveWave version.** Dev machine: **7.0.28**. A fresh download is probably newer; **no newer version has ever been tried with this code.** | Record the laptop's exact version (startup log line `Version: … Java Version: …`). If it differs, treat *everything* below as also testing that version. The user plans to install the newer version on the dev machine during a live market anyway — do not mix the two experiments up. | Recorded. If the SDK changed, `SafetyHookReflectionTest` may fail because a **new order-capable hook is not overridden — that is a designed safety stop; do not bypass it**, report it. |
| A6 | **Full build passes on the laptop.** | §2 step 7 (`MOTIVEWAVE_EXT_DIR=$(mktemp -d) bash build/build.sh`). | `exit 0`. *Already checked from the dev machine:* a fresh clone with no sibling repos passes; a clone converted to CRLF by Windows' default `core.autocrlf=true` (scripts, sources **and** replay fixtures) passes with Git Bash. **Not checked:** a shell other than Git Bash (WSL etc.), non-Windows. |
| A7 | **Python.** Dev machine 3.14, standard library only; the **minimum** version the tools need was never determined. | `python --version`; run the three suites (`python analysis/test_*.py`). | All pass; record the version. |
| A8 | **Disk space.** `data/` ≈ 3 GB per 7 trading days (liquidity map dominates), plus `logs/` which now **keeps everything** by default (~3 MB/h of `raw.jsonl` idle, a `decisions.jsonl` up to ~20 MB per long session). | Check free space before starting. | ≥ ~15 GB free for a first week; note the actual growth after the trading session. |
| A9 | **First deploy to a brand-new `dev` folder.** Does MotiveWave load the classes without a restart, or must it be restarted (or only started after the folder exists)? Does the **FLOW menu → FLOW Runtime** entry appear? Unknown. | Deploy, then look for the menu; if missing, restart MotiveWave. **Write down which it was.** | The FLOW Runtime study can be added to a chart. |
| A10 | **Antivirus/Defender** scanning the `dev` folder or the journal writes (slows or blocks class loading / file appends). | Note any Defender prompts; if `logs/` grows slowly or MotiveWave stalls, try excluding the repo and `MotiveWave Extensions` folders **[YOU — your call]**. | No prompts, no stalls. |

**B. MotiveWave, broker and safety set-up (Phase 1)**

| # | Doubt / need | How to test | Pass looks like |
|---|---|---|---|
| B1 | **"Sim Trade Only" is per installation and assumed OFF on a fresh install.** No code-level backstop exists. | Enable **before** Rithmic is connected and before the study is added (runbook §4.2). | Setting checked; the account selector reads "simulated". **Do not arm anything until this is confirmed on that machine.** |
| B2 | **Licence** — Order Flow edition on the laptop; is it allowed on a second machine? | Startup log: `License Edition: ORDER_FLOW`. | Same edition as the dev machine. |
| B3 | **Rithmic login on two machines at once.** A second login may disconnect the first. | **Do not run both machines at once until answered.** Check Rithmic/MotiveWave terms, or test with the dev machine idle and MotiveWave closed. | Recorded: allowed / not allowed / unknown. |
| B4 | **Live depth permission on the laptop's Rithmic tier.** `SUBSCRIBED_DOM` in the log does **not** prove depth data arrives (the historical-order-book permission alert on the dev machine is unrelated and harmless). | After activation, look at `data/liquidity_map/<session>.jsonl`. | Lines contain **non-empty** `bids` and `asks` (dev machine: yes). |
| B5 | **Instrument / contract.** The chart must be **`@GC`** 1-minute (continuous gold). The dev machine's chart resolved to `GCZ6`; a different contract (roll, expiry) changes the data. | `session_header.symbol` in `decisions.jsonl` must read `@GC`; check the contract month in MotiveWave. | `@GC`; tick size 0.1. |
| B6 | **Simulated account state.** The runtime refuses to arm with an open position or resting orders. Starting balance differs by install (dev: `cash=98910.0`). | Read the `ACTIVATE pos=… cash=…` line. | `pos=0`; a balance that looks like a *simulated* one. **The line shows no account name — confirm in the control box.** |
| B7 | **Trading Options tab on a fresh install.** A re-added study resets Trading Options to defaults (seen in the 2026-09-11 incident's experiment). | Look at it once before activating. | Nothing that could enter on activation; activation itself places no order (the old one-shot test-trade path is stripped). |
| B8 | **Clock.** The system trusts the local clock; skew corrupts session boundaries and the flatten window. | Compare the laptop clock with a reference; in a fresh journal compare a heartbeat's `localTimeMs` with the first records' exchange times. | Within a couple of seconds. Time-sync service running. |

**C. Start-up check (Phase 1) — the D-102 list, on the laptop**

| # | Doubt / need | How to test | Pass looks like |
|---|---|---|---|
| C1 | The expected MotiveWave-log lines, in order (runbook §5), including the **new-since-D-102** ones: `LOG_RETENTION hours=0 (keep everything) …` (and `RISK_LOCAL_CONFIG …` only if you created an override). | Read the newest `%APPDATA%\MotiveWave\output\output (…).txt`. | All present; **no `RISK_LOCAL_CONFIG_IGNORED`, no `RISK_CONFIG_MISSING`, no Java exception.** |
| C2 | The journal records (D-89/D-97): `session_header` with the real `mode`/`armedSetting`, `risk_config_loaded` (now with `logRetentionHours`, `localOverrideKeys`), `price_anchor`, `arming_state`, heartbeats with `armed`/`runtime`. | Open the new `logs/<strategy>_<ms>_inst<id>/decisions.jsonl`. | Present. |
| C3 | **Feature logs are now daily files.** `logs/<feature>_feature_<yyyy-MM-dd>.log` (not the old single `*_feature.log`). | `ls logs`. | Dated files appear; nothing else named `*_feature.log` is created. |
| C4 | The analysis tools on a real (closed-market) journal. | `python analysis/status.py` → `ALIVE … armed: no DRY_RUN … data: ok`, exit 0; `python analysis/daily_report.py --no-file`. | As on the dev machine. Weekend: `SESSION_FLATTEN_DUE` is expected. |
| C5 | **`status.py` after a clean stop.** MotiveWave logs `DEACTIVATE` and then `DESTROY` when a study is removed. (Fixed 2026-09-27, D-109 — before that a normal removal printed a false `DOWN … exit 2`.) | Remove the study, run `python analysis/status.py` a minute later. | `STOPPED | … | stopped Nm ago | … | data: n/a (not running)`, exit code 1. `DOWN` (exit 2) now genuinely means the journal went silent **without** a stop — a crash, a frozen MotiveWave, a sleeping laptop. |

**D. Trading session (Phase 2) — the user arms `lvn_fade_test` on the Simulated account**

| # | Doubt / need | How to test | Pass looks like |
|---|---|---|---|
| D1 | **First live run of the changed order code** (D-91 tracker, D-92 flatten, D-94 `order_fill`, D-97 arming records, D-99 partial-fill rule). Only the *old* form was live-verified (2026-09-21–23). | The watch-list in the `todo.md` "LAPTOP TRIAL HANDOFF" (Phase 2). | Every entry: submitted → filled → bracket → one leg fills → sibling cancelled → flat, nothing resting, no mismatch, no unexpected disarm; **none** of the three D-99 log lines. |
| D2 | **What the fill numbers really mean** (`getAvgFillPrice()`, `getLastFillTime()`, `cash Δ`, `sdkTotalRealizedPnL`) — the report assumes; nobody has seen real ones. | `daily_report.py`, `trade_view.py --trade 1` after the session. | Prices/times plausible against the chart; note what `cash Δ`/`sdkTotalRealizedPnL` equal. |
| D3 | **The recorders with ticks:** footprint, VWAP, big trades, OHLCV `bars/`; volume profile after the D-104 refactor (`volume_profile_feature_<date>.log` lines should look like the old ones). | `ls data/`; `status.py` `data: ok`; the report's "Recorded data" table. | All four files appear once trades/bars occur; no `**missing**` for liquidity/market-structure. |
| D4 | **Big-trade Min Size is still the test value 1** → nearly every tick is a "big trade" (noisy chart, large `data/big_trades/`). | The user chooses 1 (capture test) or 10 before starting. | Decision recorded. |
| D5 | **Laptop performance.** The risk chain **blocks new entries** (a `lag` verdict, not a disarm) while the event queue is backed up (depth ≥ 1000, `lagQueueDepthThreshold`) or one event took ≥ 2 s (`lagProcessingMsThreshold`); a slower machine may trigger it and the strategy would silently skip entries. | Watch the report's *What the risk chain blocked* section for `lag` blocks, the heartbeat gaps, and MotiveWave's memory during the session. | No `lag` blocks; the longest heartbeat gap is ~10 s. |
| D6 | **Laptop power/sleep.** Lid close, sleep, hibernate, battery saver, Wi-Fi power saving or a Windows-Update restart will stop the study mid-session — possibly **with a position open**. | Before the session: plugged in, power plan "never sleep", **lid-close action = do nothing**, Wi-Fi power saving off (or wired), updates paused. | The session runs uninterrupted; `status.py` stays `ALIVE`. If it is interrupted with a position open: the runtime **refuses to arm** on restart and does not adopt or flatten it (README) — clear it by hand. |
| D7 | **Entry-time window.** `lvn_fade_test` has a 2-minute warm-up after the study starts, then entries stop 15:45 CT (02:15 IST) and open positions flatten from 15:55 CT (02:25 IST). | Plan the session inside that window. | No entries before the warm-up ends. |
| D8 | **Optional flatten test in a watched window (D-92)** using a git-ignored `config/risk.local.json` (`flattenLeadMinutes` 400, `noEntryLeadMinutes` 405); delete the override afterwards. | `todo.md` handoff, Phase 2. | `SESSION_FLATTEN_DUE`, flat account, **no disarm**; override removed. |
| D9 | **Stop conditions** — an account that is not the Simulated one anywhere; an unexplained fill/position; kill switch; repeated disarms; a stuck resting order. | Watch throughout. | None occur; if one does, stop and tell the user. |

**E. After the trial**

| # | Need | Note |
|---|---|---|
| E1 | Hand back the filled-in sheet (this table with PASS/FAIL/notes) and every place the runbook was wrong, unclear or incomplete. | Becomes a `decisions.md` entry and runbook corrections. The runbook is a **draft** until this has been done once. |
| E2 | Compare the two machines' journals for the same market period **only if** both ran (see B3 — not simultaneously on one Rithmic login unless confirmed). | Differences are findings. |

## 14. Sources

`docs/configuration.md` (settings) · `README.md` (architecture, "Commands you run",
"Safety") · `CLAUDE.md` (rules incl. the order-placement exceptions) ·
`docs/dynamic/decisions.md` D-92 (flatten), D-93 (no watchdog), D-98/D-100/D-101
(paths), D-99 (partial fills), D-102/D-103 (first deploy, startup check) ·
`docs/dynamic/todo.md` (the plan; §2 market-open batch) · `docs/dynamic/
plumbingEdgeCases.md` §11 (restart with a position) · the sibling repo
`../motivewave/docs/dynamic/findings.md` (platform facts: Sim Trade Only, the
2026-09-11 incident, the Rithmic depth alert).
