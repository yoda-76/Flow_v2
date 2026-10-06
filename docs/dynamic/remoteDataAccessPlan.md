# Remote access to reports & recorded data — Google Drive sync (script written 2026-10-05, not yet run for real)

Written 2026-10-02. **This is an implementation plan for the cloud Claude to build, not built yet.** The user is on
the EC2 VM 24/7, has very limited hands-on MotiveWave access going forward (multiple personal devices, one 24/7
cloud machine), and wants to pull `reports/` and recorded `data/` without RDPing in every time. First instinct was a
small API server (Django/Flask); this doc proposes something smaller instead.

## Decision: rclone → Google Drive, not S3, not a custom API server

**Rejected: a custom download API server (Django/Flask/FastAPI).** The EC2 security group (`runbook-ec2.md` §2.1)
currently allows **zero inbound except RDP/SSM**. A download API means a new open port plus auth (API keys/TLS/rate
limiting) to build and keep secure on a machine that otherwise talks to nothing. It also duplicates what off-the-shelf
sync already does better, and is one more process that can crash and take "can I get my reports" down with it.

**Rejected: AWS S3.** Was the original plan (`runbook-ec2.md` §2.2/§7 already provisioned an IAM role scoped to one
S3 bucket for this, marked "[not built]") — but the user wants to spend Google One's already-paid-for 100GB instead
of AWS spend. This doc **supersedes `runbook-ec2.md` §7's S3 backup plan** with the Google Drive version below; when
this is built, update §7 to point here instead of describing its own separate S3 flow.

**Chosen: `rclone` syncing to the user's Google Drive (Google One, 100GB).** Reasoning:
- No new inbound port, no server process, no auth system to build — rclone runs as a scheduled/on-demand script,
  same shape as everything else in `ops/`.
- Google Drive is reachable from **any device** (web, phone, desktop) with zero client setup on those devices — no
  AWS CLI, no credentials to distribute, just the Drive app/website the user already has.
- `rclone` (not Google Drive for Desktop) specifically because Drive for Desktop mirrors whole folders with no
  filtering, and we need to **exclude `raw.jsonl`** (0.7–0.8 GB/day per `runbook-ec2.md` §5 — a short-rolling tier by
  design, not meant to be kept long-term anyway) and think twice about `liquidity_map/` (measured 113–177 MB/day per
  construct-data audit, 2026-10-02) so the 100GB quota doesn't fill in a few weeks. `rclone copy --exclude` handles
  this; Drive for Desktop cannot.

## What gets synced, and what doesn't

| Path | Sync? | Why |
|---|---|---|
| `reports/<date>.md`, `reports/trade_*.md` | **Yes, every run** | Small, highest-value for remote nightly review — the whole point of this. |
| `logs/<session>/decisions.jsonl` | **Yes** | Human-readable decision record, kept forever locally anyway (per README's Journal section) — small relative to raw. |
| `logs/<session>/raw.jsonl` | **No, excluded** | Large (0.7–0.8 GB/day), short-rolling by design (retention is `logRetentionHours`, not meant as a long-term archive) — see "Open question" below if this needs to change for the backtest plan. |
| `data/<construct>/GC/*.jsonl` except `liquidity_map` | **Yes** | Rolling 7-trading-day window (D-88) already; small per day (bars/market_structure = KB, footprint/vwap/big_trades = low MB). |
| `data/liquidity_map/GC/*.jsonl` | **Weekly or on-demand only, not nightly** | 113–177 MB/day measured 2026-10-02 — nightly for a year would be ~40+ GB, nearly half the quota, for data mainly useful for deep order-flow debugging rather than routine review. |
| `logs/<session>/replay/` | **No** | Regenerated test/debug output (`ReplayHarness`'s own output dir), not a deliverable — same reasoning the repo's own `.gitignore` already applies. |

⚠️ **AMBIGUOUS — confirm before building**: if the backtest engine plan (`backtestEnginePlan.md`) ends up needing
long-horizon `raw.jsonl` history (it's the only tick/DOM-level source we have), excluding it from the off-machine
backup means a disk failure or an aggressive `logRetentionHours` prune on the VM could lose it for good — the EBS
snapshot (`runbook-ec2.md` §2.4) is the only other copy. Decide: (a) keep raw.jsonl local+EBS-snapshot only (current
plan here), or (b) also sync it to Drive on a slower cadence (e.g. weekly) accepting the quota cost, or (c) size
`logRetentionHours` to never prune and rely on EBS snapshots. Needs the user's call once the backtest plan's data
needs are concrete.

## Setup steps (for the cloud Claude to execute)

1. **Install `rclone`** on the VM (single executable, no admin rights needed beyond PATH).
2. **One-time OAuth setup — the user does this, not Claude**, same spirit as the `.env` rule: run `rclone config`
   interactively during an RDP/SSM session, create a remote named `gdrive`, authorize via the browser OAuth flow
   against the user's own Google account. The resulting token lives in `%APPDATA%\rclone\rclone.conf` on the VM only
   — never in this repo, never committed, never read by Claude (it's a credential, same category as `.env`).
3. **Drive layout (as built 2026-10-06): one folder per trading day** — `FLOW_V2_backup/by_date/<YYYY-MM-DD>/` with `reports/`, `logs/<session>/decisions.jsonl` and `data/<construct>/GC/...` inside. The day is the trading day daily_report.py uses (17:00 CT rollover), so a date range downloads with `ops/sync_from_gdrive.ps1 -From -To` into `gdrive_mirror/<YYYY-MM-DD>/`. Staging is done by `ops/stage_by_date.py` (hard links, no extra disk).
4. **`ops/sync_to_gdrive.ps1` — written 2026-10-05** (parse-checked; the no-rclone failure path tested; a real run needs rclone + the user's one-time `rclone config`). Uses **`rclone copy`, not `sync`**: `sync` would delete Drive files that were pruned locally, and `data/` rolls off after 7 trading days, which would erase the off-machine copy. Modes: `nightly` (reports, decisions journals, data minus liquidity_map) and `liquidity` (weekly). Logs each run to `logs/sync_gdrive.log`. Roughly:
   ```
   rclone copy reports/                    gdrive:FLOW_V2_backup/reports        --fast-list
   rclone copy logs/                       gdrive:FLOW_V2_backup/logs_decisions --include "**/decisions.jsonl" --fast-list
   rclone copy data/ --exclude "liquidity_map/**" gdrive:FLOW_V2_backup/data    --fast-list
   ```
   A second script or a flag (`-IncludeLiquidityMap`) runs the `liquidity_map` sync separately on its own, slower
   schedule.
5. **Idempotent, safe to re-run** — `rclone copy` only transfers changed/new files; running it twice in a row does
   nothing the second time. No state to track beyond what's already in Drive.
6. **Two trigger modes**, both via Windows Task Scheduler (reuse the `ops/register_tasks.ps1` pattern, D-118):
   - **Nightly**, after the day roll (~03:40 IST, matching the timing `runbook-ec2.md` §7 originally proposed for
     S3) — `reports/`, `logs_decisions/`, `data/` (excl. liquidity_map).
   - **Weekly**, e.g. Sunday — the `liquidity_map` sync.
   - **On-demand**: the user (or a future watchdog hook) runs the script manually over RDP/SSM any time for "give me
     the latest right now" instead of waiting for the nightly run.
7. **Verify**: run once manually after setup, confirm the files land in Drive (check from a second device, e.g. the
   phone Drive app, not just the VM's own view), confirm a second run with no new files does nothing (idempotency),
   confirm `raw.jsonl` and (outside its own schedule) `liquidity_map` are genuinely absent from the main sync.
8. **Update docs**: `docs/runbook-ec2.md` §7 (replace its S3-specific text with a pointer here), `docs/configuration.md`
   (add a row for wherever the Drive folder name / any new config lives — plain text, not a secret, so it can go in
   `config/risk.local.json` per-machine if it ever needs to be configurable, per D-106's existing pattern).

## What this does not cover

Not a backup-everything solution — `raw.jsonl` long-term retention is a separate, currently-open question (see the
⚠️ above). Not a dashboard — this is file sync; reading a report remotely still means opening the `.md` file from
Drive (fine for now; a nicer viewer is a separate, later idea if wanted).

## Pipeline as built (2026-10-06)

Two directions, one Drive folder (`FLOW_V2_backup/by_date/<YYYY-MM-DD>/`), copy-only so nothing is ever deleted:

1. **Upload, on the VM — `ops/sync_to_gdrive.ps1`** (Windows) or **`ops/sync_to_gdrive.sh`** (Linux), daily at
   **04:00 IST**. Stages the files per trading day (`ops/stage_by_date.py`) and copies them up with
   **`--min-age 1h`**, which skips any file changed in the last hour. This is what makes the upload safe: the 17:00 CT
   rollover closes one session but opens the next, and the new session's files (and any running instance's journal)
   are written every second. Copying them fails the checksum check. The 04:00 time alone does not avoid this. It was
   learned the hard way: the first live run (2026-10-05, 14:50 CDT) failed on 3 files of session 20730, and the first
   scheduled run (2026-10-05 22:30 UTC) failed on the new session 20731 and the running instance's journal. The skipped
   files are picked up by a later run once they have been quiet for an hour. `-Mode liquidity` does the large
   `liquidity_map` weekly (not scheduled yet). On this Linux VM the schedule is a systemd user timer
   (`~/.config/systemd/user/flow-sync-gdrive.timer`, `OnCalendar=… 04:00:00 Asia/Kolkata`, lingering enabled so it
   runs without a login); a run is logged to `logs/sync_gdrive.log` and to the journal.
2. **Download, on this machine — `ops/sync_from_gdrive.ps1`**, daily at **08:00 IST**, or by hand. Pulls every day
   not yet in `gdrive_mirror/`; `-From/-To` pulls a range. Never writes into `logs/` or `data/`.

Scheduling: `ops\register_gdrive_tasks.ps1 -Role upload` on the VM, `-Role download` here. Triggers are IST
converted to each machine's clock. Requirements: rclone and Python on the PATH of the user the task runs as; the
user is logged on (same as the watchdog). Not yet registered on any machine.

Verified 2026-10-06 on this machine: the dated upload matches the staging folder (125 files, 0 differences); a
two-day range download returns the right folders; the daily report runs on a downloaded day.

## Setup — step by step

Do the Google part once, then the VM and this machine. Never paste the rclone config, its token or the client
secret into chat or a commit: they live only in `%APPDATA%\rclone\rclone.conf`, outside the repo.

### A. Google Cloud (once, from any browser)
1. Google Cloud Console → create a project → enable the **Google Drive API**.
2. OAuth consent screen → User type **External** → app name, support and developer emails → add your Gmail as a
   **test user**. Then fill **Branding** (homepage and privacy-policy URLs) and click **Publish app** so the
   sign-in doesn't expire after 7 days. For your own account you'll see an "unverified app" warning; click past it.
3. Credentials → Create → **OAuth client ID** → application type **Desktop app**. Keep the Client ID and Client secret
   for step B. Leave "This client will be used by an AI-powered agent" unchecked.

### B. rclone on each machine (VM and this machine)
1. Download rclone for Windows, extract it, and add its folder to the **user** PATH (Windows environment variables,
   permanent). Open a new terminal and check `rclone version`.
2. `rclone config` → `n` → name **`gdrive`** → type **`drive`** → client_id and client_secret from step A →
   scope **1** → advanced `n` → auto config `y` → sign in with your Gmail → Shared Drive `n` → save `y`.
3. Check: `rclone lsd gdrive:` lists your Drive folders.

### C. The VM (uploads)
1. Install **Python 3** and **Git for Windows** if the runbook install didn't already. Python must be on PATH.
2. `git pull` the repo. Do the rclone steps (B) on the VM as the same user that runs the tasks.
3. Test by hand, from the repo root:
   ```
   powershell -ExecutionPolicy Bypass -File ops\sync_to_gdrive.ps1 -DryRun
   powershell -ExecutionPolicy Bypass -File ops\sync_to_gdrive.ps1
   ```
   `logs\sync_gdrive.log` should end with `OK` for the real run.
4. Schedule daily 04:00 IST (elevated not required; the script's default is already 04:00):
   `powershell -ExecutionPolicy Bypass -File ops\register_gdrive_tasks.ps1 -Role upload`
5. Optional, weekly `liquidity_map`: `powershell -ExecutionPolicy Bypass -File ops\sync_to_gdrive.ps1 -Mode liquidity`
   (schedule it yourself with Task Scheduler if wanted; not registered by the script).

### D. This machine (downloads)
1. Steps B (rclone) and the repo as usual. Python must be on PATH for the analysis tools.
2. Pull everything not yet here, by hand:
   `powershell -ExecutionPolicy Bypass -File ops\sync_from_gdrive.ps1`
   or one range: `... -From 2026-09-28 -To 2026-10-02`.
3. Daily 08:00 IST automatically:
   `powershell -ExecutionPolicy Bypass -File ops\register_gdrive_tasks.ps1 -Role download`
4. Read a downloaded day: `python analysis\daily_report.py --logs gdrive_mirror\<YYYY-MM-DD>\logs --data gdrive_mirror\<YYYY-MM-DD>\data`

### E. Removing or changing a schedule
`register_gdrive_tasks.ps1 -Role upload -Remove` (or `download`). Change the time with `-AtIst HH:mm`.

### Checks
- `logs\sync_gdrive.log` (VM) and `logs\sync_from_gdrive.log` (this machine) end with `OK` on success.
- `rclone size gdrive:FLOW_V2_backup/by_date` shows the total on Drive.
- A failed run logs `FAILED` with the reason; the next day's run retries everything.
