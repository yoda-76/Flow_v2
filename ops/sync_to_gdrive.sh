#!/usr/bin/env bash
# Linux counterpart of ops/sync_to_gdrive.ps1: backs up this machine's reports, decision journals and recorded
# data to Google Drive via rclone (docs/dynamic/remoteDataAccessPlan.md). Files are first arranged into one folder
# per trading day (ops/stage_by_date.py), then copied to <Root>/by_date/<YYYY-MM-DD>/... on Drive.
# Never deletes anything on Drive: `copy`, not `sync`, because data/ rolls off locally and that must not erase the
# off-machine copy.
# Requires rclone on PATH and a one-time `rclone config` (remote "gdrive") done by the user; the OAuth token lives in
# rclone's own config file (~/.config/rclone/rclone.conf), never in this repo.
#
#   bash ops/sync_to_gdrive.sh [--mode nightly|liquidity] [--dry-run]
set -euo pipefail

MODE=nightly
DRY_RUN=0
REMOTE=gdrive
ROOT=FLOW_V2_backup
PYTHON=${PYTHON:-python3}
FLOW_HOME=${FLOW_HOME:-$(cd "$(dirname "$0")/.." && pwd)}

while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE=$2; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$MODE" in nightly|liquidity) ;; *) echo "--mode must be nightly or liquidity" >&2; exit 2 ;; esac

if ! command -v rclone >/dev/null 2>&1; then
  echo "rclone is not on PATH. Install it, then run: rclone config (create remote '$REMOTE')." >&2
  exit 2
fi

cd "$FLOW_HOME"
mkdir -p logs
LOG=logs/sync_gdrive.log
STAMP="$(date -Iseconds) mode=$MODE dryRun=$([ $DRY_RUN -eq 1 ] && echo true || echo false)"

# One run at a time: a second start (e.g. a manual run during the scheduled one) exits instead of racing.
exec 9>logs/.sync_gdrive.lock
if ! flock -n 9; then
  echo "$STAMP SKIPPED: another run is in progress" >> "$LOG"
  exit 0
fi

fail() {
  echo "$STAMP FAILED: $1" >> "$LOG"
  echo "$1" >&2
  exit 1
}

STAGING="gdrive_staging_$MODE"
"$PYTHON" ops/stage_by_date.py --flow-home "$FLOW_HOME" --mode "$MODE" || fail "stage_by_date.py failed"

RC_ARGS=(copy "$STAGING" "$REMOTE:$ROOT/by_date" --fast-list --transfers 4)
[ $DRY_RUN -eq 1 ] && RC_ARGS+=(--dry-run)
echo "rclone ${RC_ARGS[*]}"
rclone "${RC_ARGS[@]}" || fail "rclone copy failed"

echo "$STAMP OK" >> "$LOG"
