# Runbook — running FLOW on an AWS EC2 Windows instance (24/7 Simulated-account run)

Written 2026-09-28. **Nothing here has been done on EC2 yet.** It is assembled from what this repo has measured
(`runbook.md` §9–§11a, the 2026-09-28 live sessions) plus general AWS/Windows knowledge; every line marked
**[CHECK]** is a fact to verify on the real instance and then correct here. The rules of `CLAUDE.md` apply unchanged:
**Simulated account only, real account forbidden, the user arms and runs the session** (Claude reads logs and builds; it
does not arm or place orders on a second machine), **"Sim Trade Only" enabled before Rithmic is connected**, only gold.

Read with: `runbook.md` (what the system is, first-start checklist §5, sizing §11a), `configuration.md` (every setting),
`docs/dynamic/todo.md` ("BEFORE THE 24/7 RUN").

## 1. Decisions to make first

| Decision | Recommendation | Why / note |
|---|---|---|
| Instance size | **4 vCPU / 16 GB** (e.g. an `m6i.xlarge`-class general-purpose instance) | `runbook.md` §11a: min 2 vCPU / 8 GB, recommended 4 / 16. Avoid burstable (`t3`) types for a 24/7 load: CPU credits run out. **[CHECK]** current prices. |
| Disk | **250 GB gp3 SSD** (EBS) | §11a: `data/` ≈ 0.45 GB per trading day, raw journals ≈ 0.7 GB/day before pruning, Windows + MotiveWave ≈ 40 GB. |
| Region | **us-east-2 (Ohio)** or us-east-1 | Rithmic's server for this account is "Lucid Trading (Chicago)", CME is in Aurora, IL; AWS has no Chicago region (there is a Chicago *Local Zone* with fewer instance types). Latency matters little for a Sim plumbing run. |
| OS | **Windows Server 2022 or 2025, Desktop Experience** | Not a Core install (MotiveWave is a GUI app). MotiveWave on a *server* OS and on a GPU-less VM is **[CHECK]**; fall back to Windows 11 Pro on a bare-metal-ish AMI only if it misbehaves. |
| Access | **AWS Systems Manager (Fleet Manager / Session Manager)** for RDP, or RDP restricted to your IP | Don't expose 3389 to the world. |
| Hours | 24/7 (the halt 16:00–17:00 CT is handled by the system: flatten 15:55 CT, quiet, reopen 17:00 CT) | Stopping the instance daily saves money but breaks the "soak" purpose and the auto-start path is untested. |

## 2. AWS side

1. **Security group:** inbound **only** RDP 3389 from your IP (or none, if you use SSM). Outbound: allow all — Rithmic
   uses several TCP ports (65000, 56000, 64100, 45454 and others seen in MotiveWave's log); don't try to enumerate them.
2. **IAM role for the instance:** `AmazonSSMManagedInstanceCore` (SSM), `CloudWatchAgentServerPolicy` (optional metrics), and
   a policy limited to **one S3 bucket** for the nightly backup (§7). No secrets in the role, none in the AMI.
3. **Elastic IP** (free while attached) if you RDP directly; otherwise the public IP changes on stop/start.
4. **CloudWatch alarm** on `StatusCheckFailed_System` with the **Recover** action (the instance is moved and restarted on
   other hardware). `StatusCheckFailed_Instance` → alarm/notify (Windows hung).
5. **EBS snapshots** on a daily schedule (Data Lifecycle Manager) — the whole disk, including `logs/` and `data/`.
6. **Billing alarm.** A forgotten instance is the classic cost surprise.

## 3. Windows preparation (RDP in as Administrator)

1. Run `ops\harden_windows.ps1` (dry run first, then `-Apply` elevated): no sleep/hibernate, High-performance plan, no
   automatic reboot while someone is logged on, screensaver off. **The reboots Windows Update still asks for are yours to
   schedule — do them inside the daily halt (16:00–17:00 CT = 02:30–03:30 IST) with the account flat.**
2. **Time:** EC2 Windows AMIs use the Amazon Time Sync Service by default — verify: `w32tm /query /status` shows a real
   source and a recent sync (**not** "Local CMOS Clock"). Every risk rule (entry window, flatten, daily reset, dwell) runs
   on this clock (`codeReview.md` E4). The dev machine's Windows Time service appeared not to be running; the laptop was
   ~3 s off.
3. **Keep the desktop session alive.** MotiveWave needs a logged-on desktop. Options: (a) *disconnect* the RDP window,
   never *sign out* — the session keeps running; (b) enable **auto-logon** (Sysinternals *Autologon* — stores the password
   in the registry, so use a dedicated low-privilege user) so a reboot brings the desktop back by itself. Whether
   MotiveWave renders/behaves in a *disconnected* session is **[CHECK]** — test it before trusting it; if charts freeze
   only visually that is fine (recording does not depend on drawing), if the platform stops delivering ticks it is not.
4. Install: **Git for Windows** (provides bash — `build/build.sh` is a bash script), **Python 3** (analysis tools and the
   watchdog; standard library only, no packages), a **JDK 26** (the build's compiler; the repo expects a JDK — set
   `FLOW_JDK_BIN`, see `build/env.sh`), and **MotiveWave** — the *same version* you tested with (7.0.28 on the dev machine,
   7.1.1 on the laptop) and **switch its auto-update off** (an update can change `mwave_sdk.jar`; the startup log showed
   `Check updates, interval: 7`). **[CHECK]** the MotiveWave licence terms for a second/cloud machine (edition
   `ORDER_FLOW` is required by the volume-profile/footprint features).
5. **One Rithmic login at a time.** Close MotiveWave on the dev machine and the laptop before connecting EC2 (a second
   login disconnects the first — laptop finding B3).

## 4. MotiveWave set-up (in the GUI)

Follow `runbook.md` §4 in this order — **Sim Trade Only comes first**:

1. *Configure → Settings → General → Simulated Account tab* → **Enabled** and **Sim Trade Only** ticked. Confirm the
   account selector reads **"simulated"**. Only then connect Rithmic.
2. Connect to Rithmic ("Lucid Trading (Chicago)"). Look for a **connect-on-startup / auto-connect** option in the connection
   settings so a restart reconnects by itself **[CHECK: where it is]**.
3. `%APPDATA%\MotiveWave\startup.ini`: set `MAX_HEAP=4096` (default is ~¼ of RAM; §11a) and restart MotiveWave.
4. Open the `@GC` chart, **20-second bars**; add **FLOW Runtime**. Settings to review: Strategy Id, **Mode `SIM_LIVE`**, **Armed**
   (the user's decision, every time), **Big Trades → Min Size** (default 1 = every trade; raise it — it is a study setting,
   not in `risk.json`), and **turn the "Draw on Chart" options off** (heatmap, footprint, market structure, big trades,
   entry arrows) — nobody watches an unattended chart and drawing runs on MotiveWave's tick thread every second.
5. Save the workspace so it reopens with the chart and the study.

## 5. FLOW install on the instance

```text
git clone <the repo>            # branch main (D-121, 2026-09-29: distribution-test was merged into main — main is current)
setx FLOW_HOME "D:\flow\FLOW_V2"  # ONLY if the clone is not at C:/yadvendra/trading/FLOW_V2 (the built-in default)
setx FLOW_JDK_BIN "<path to the JDK's bin>"     # build/env.sh explains the variables
setx MOTIVEWAVE_EXT_DIR "C:\Users\<this machine's Windows user>\MotiveWave Extensions"  # the hard-coded default is
                                                 # the dev machine's user (MSI) and will be wrong here — build.sh
                                                 # fails loudly and names this variable if it's missing (configuration.md)
setx MWAVE_SDK_JAR "C:\Program Files (x86)\MotiveWave\lib\mwave_sdk.jar"  # only if MotiveWave installs elsewhere on this instance
bash build/build.sh             # runs every test, then deploys to "MotiveWave Extensions\dev"
```
(`setx` only takes effect in a **new** shell — close and reopen the terminal, or restart Git Bash, before running `build.sh`.)

- After **any** deploy: remove and re-add the study (a running study keeps the old classes) and re-check `runbook.md` §5.
- **Per-machine config** goes in the git-ignored `config/risk.local.json` — never in `risk.json`. A cloud file for a long run:
  `{ "logRetentionHours": 48, "dataKeepTradingDays": 7 }`; the daily-loss limit, reversal cap and rate limit stay at the
  defaults unless you decide otherwise (`configuration.md`; changes apply at the next activation). Leave unset =
  `raw.jsonl` kept forever (~0.7–0.8 GB/day).
- **Secrets:** `.env` (git-ignored, **you** create it, nothing else writes it) for the Telegram bot — names in
  `.examples.env`: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`. Never put it in an AMI or a snapshot you share.

## 6. Alerts and the watchdog (built 2026-09-28, D-117/D-118)

Two layers:

1. **Inside the runtime:** `logs/alerts.log` gets a line for the kill switch, disarms, `FEED_STALE`, a non-simulated
   account, a lost bracket leg, position disagreements, an unconfirmed flatten, and study start/stop.
2. **Outside MotiveWave — `ops\watchdog.py`:** run every minute. Alerts when the journal goes silent (MotiveWave or the study
   crashed/froze), the study is stopped, the runtime is DISARMED, the feed is not LIVE, no MotiveWave process exists, or the
   disk is low; **forwards new `alerts.log` lines to Telegram**; appends `logs\watchdog_metrics.csv` (journal age, feed state,
   MotiveWave memory, free disk — the soak-test record).

Set up:

```text
python ops\watchdog.py --dry-run          # prints "OK" or the problems; writes and sends nothing
python ops\watchdog.py --test-alert       # after .env has the bot: one test message
powershell -ExecutionPolicy Bypass -File ops\register_tasks.ps1     # FLOW-Watchdog (every minute) + FLOW-Start-MotiveWave (at logon)
```

Until the bot exists the watchdog still writes its alerts to `logs\alerts.log`; read that file. **Not covered:** the whole
instance being down (the watchdog dies with it) — that is the CloudWatch alarm's job (§2 item 4).

## 7. Backups

`logs\*\decisions.jsonl` (kept forever) and `data\` are the research record. Nightly copy to S3, e.g. a Task Scheduler entry
at 03:40 IST (after the reopen) running `aws s3 sync "%FLOW_HOME%\data" s3://<bucket>/flow/data` and the same for
`logs` with `--exclude "*raw.jsonl"` (raw journals are pruned anyway). **[not built]** — only the EBS snapshot (§2) exists
by default.

## 8. After a restart (the restart policy)

What is meant to happen, and what is **untested**:

1. Windows reboots → auto-logon → `FLOW-Start-MotiveWave` starts MotiveWave 30 s later if it is not running.
2. MotiveWave reconnects to Rithmic **only if** auto-connect is on **[CHECK]**.
3. The saved workspace reopens the `@GC` chart with the FLOW Runtime study. **Whether MotiveWave re-activates the study by
   itself, and with `Armed` still ticked, is [UNKNOWN — TEST IT]** (`runbook.md` §9). If it comes back armed, the
   runtime's own guards still apply: it **refuses to arm over an existing position or resting orders** (D-24), the
   daily-loss baseline restarts, and any kill-switch/safety disarm from before the restart is gone (the study is a fresh
   instance — so look at `alerts.log` and the account first).
4. The watchdog tells you meanwhile: `WATCHDOG_DOWN` while the journal is silent, `WATCHDOG_MW_NOT_RUNNING`, then
   `WATCHDOG_STOPPED` if the study never comes back.

**Test it on purpose, once, when flat:** reboot the instance and write down exactly what came back (MotiveWave? Rithmic?
chart? study? armed?) at 1, 3 and 10 minutes. Put the answer here.

**Note (2026-09-29, dev machine, not EC2):** an unattended overnight run (no reboot, just left alone) survived 120
unscheduled feed drops with no intervention — the vast majority self-recovered inside MotiveWave with no manual
Rithmic reconnect; only the harder/longer breaks needed one. Evidence for §11's "internet problem won't be there on
EC2" expectation, not proof of it (this was still a home connection).

**Manual recovery checklist** (RDP/SSM in): (1) Is the account flat and nothing resting? (Sim account window.) (2) Is
Rithmic connected? If the chart is frozen, use MotiveWave's Disconnect then Connect (2026-09-28: a Wi-Fi drop left market
data stuck until this was done). (3) Is the study on the chart? If not, add it; if a safety disarm is suspected, remove and
re-add it anyway. (4) `python analysis\status.py` → `ALIVE`. (5) Read the newest `ACTIVATE` line (`pos=0 accountPos=0`) and the
account name in the first fill (`accountId":"simulated"`).

## 9. What to expect in a day (US Central; IST = CT + 10:30 in US summer time)

| CT | IST | Event |
|---|---|---|
| 15:45 | 02:15 | no new entries |
| 15:55 | 02:25 | open positions flattened; retried every 5 s until flat |
| 16:00–17:00 | 02:30–03:30 | CME halt: no ticks. The feed watchdog stays quiet (`FLATTEN` phase); Rithmic may disconnect for maintenance — **untested** |
| 17:00 | 03:30 | reopen; **day roll**: risk baseline re-based, VWAP reset, new data file, retention prune — **verified live on the dev machine 2026-09-29 (D-119): zero false `FEED_STALE` through the halt, clean reopen, new day's data file. Not yet seen on EC2 itself.** |
| Fri 15:55 → Sun 17:00 | Sat 02:25 → Mon 03:30 | weekend: flat, quiet |

Not modelled: exchange holidays and early closes (no ticks → `FEED_STALE` will ALERT inside a window the system thinks is
open — expected noise until a calendar exists), and the **December gold contract roll** (`GCZ6` expires; check the roll date
in November).

## 10. First 24 hours on EC2 — checklist

- [ ] Before connecting: Sim Trade Only on; selector reads "simulated"; laptop and dev machine closed.
- [ ] `w32tm /query /status` sane; `harden_windows.ps1 -Apply` done; auto-logon set.
- [ ] `bash build/build.sh` exits 0; study added; `runbook.md` §5 lines all present; `status.py` → ALIVE.
- [ ] `python ops\watchdog.py --dry-run` → OK; `--test-alert` reaches Telegram; tasks registered.
- [ ] Disconnect the RDP window (do not sign out); 10 minutes later the journal is still being written.
- [ ] Watch through one full halt + reopen (02:15 → 03:45 IST): flatten, quiet, reconnect, `feed_live`, first entry after.
- [ ] Reboot test (§8) when flat.
- [ ] After 24 h: read `logs\watchdog_metrics.csv` (memory trend, disk), `du -sh logs data`, the daily report
      (`python analysis\daily_report.py`), and fill in the "Measured" table in `runbook.md` §11a.

## 11. Known unknowns (fix this list as they are answered)

MotiveWave on Windows Server / a GPU-less VM; behaviour in a disconnected RDP session; auto-connect and study
re-activation after a restart; whether one MotiveWave licence covers the cloud machine; memory over several days;
Rithmic maintenance disconnects; **whether the market-data-stuck-after-a-network-drop problem (todo F-24) also happens on
EC2** — the user expects not; if it does, revisit the recovery plan (the watchdog already alerts on it).
