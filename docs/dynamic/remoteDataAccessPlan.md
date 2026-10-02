# Remote access to reports & recorded data — Google Drive sync (plan, not yet built)

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
  construct-data audit, 2026-10-02) so the 100GB quota doesn't fill in a few weeks. `rclone sync --exclude` handles
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
3. **Pick a Drive folder layout**, e.g. `FLOW_V2_backup/reports/`, `FLOW_V2_backup/logs_decisions/`,
   `FLOW_V2_backup/data/`, `FLOW_V2_backup/liquidity_map/` (separate, so its slower cadence is obvious from the
   folder alone).
4. **Write `ops/sync_to_gdrive.ps1`** (bash equivalent fine too, matching `ops/` conventions), roughly:
   ```
   rclone sync reports/                    gdrive:FLOW_V2_backup/reports        --fast-list
   rclone sync logs/                       gdrive:FLOW_V2_backup/logs_decisions --include "*/decisions.jsonl" --fast-list
   rclone sync data/ --exclude "liquidity_map/**" gdrive:FLOW_V2_backup/data    --fast-list
   ```
   A second script or a flag (`-IncludeLiquidityMap`) runs the `liquidity_map` sync separately on its own, slower
   schedule.
5. **Idempotent, safe to re-run** — `rclone sync` only transfers changed/new files; running it twice in a row does
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
