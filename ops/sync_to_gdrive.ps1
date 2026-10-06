# Backs up this machine's reports, decision journals and recorded data to Google Drive via rclone
# (docs/dynamic/remoteDataAccessPlan.md). The files are first arranged into one folder per trading day
# (ops/stage_by_date.py), then copied to <Root>/by_date/<YYYY-MM-DD>/... on Drive.
# Never deletes anything on Drive: `copy`, not `sync`, because data/ rolls off locally after 7 trading days
# and that must not erase the off-machine copy.
# Requires rclone on PATH and a one-time `rclone config` (remote "gdrive") done by the user; the OAuth token
# lives in rclone's own config file, never in this repo.
#
#   powershell -ExecutionPolicy Bypass -File ops\sync_to_gdrive.ps1 [-Mode nightly|liquidity] [-DryRun]
param(
  [ValidateSet("nightly", "liquidity")] [string]$Mode = "nightly",
  [string]$Remote = "gdrive",
  [string]$Root = "FLOW_V2_backup",
  [string]$FlowHome = $(if ($env:FLOW_HOME) { $env:FLOW_HOME } else { Split-Path -Parent $PSScriptRoot }),
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
if (-not (Get-Command rclone -ErrorAction SilentlyContinue)) {
  Write-Error "rclone is not on PATH. Install it, then run: rclone config (create remote '$Remote')."
  exit 2
}

Set-Location $FlowHome
$staging = "gdrive_staging_$Mode"
# --min-age 1h: skip files changed in the last hour (the session still recording is always being appended to, and
# copying it fails the checksum check); a later run picks them up once they have been quiet for an hour.
$rcArgs = @("copy", $staging, "$Remote`:$Root/by_date", "--fast-list", "--transfers", "4", "--min-age", "1h")
if ($DryRun) { $rcArgs += "--dry-run" }
$logPath = Join-Path $FlowHome "logs/sync_gdrive.log"
New-Item -ItemType Directory -Force -Path (Join-Path $FlowHome "logs") | Out-Null
$logLine = "{0} mode={1} dryRun={2}" -f (Get-Date -Format "s"), $Mode, [bool]$DryRun

try {
  & python (Join-Path $PSScriptRoot "stage_by_date.py") --flow-home $FlowHome --mode $Mode
  if ($LASTEXITCODE -ne 0) { throw "stage_by_date.py failed (exit $LASTEXITCODE)" }
  Write-Host ("rclone " + ($rcArgs -join " "))
  & rclone @rcArgs
  if ($LASTEXITCODE -ne 0) { throw "rclone copy failed (exit $LASTEXITCODE)" }
  Add-Content -Path $logPath -Value "$logLine OK"
} catch {
  Add-Content -Path $logPath -Value "$logLine FAILED: $($_.Exception.Message)"
  Write-Error $_
  exit 1
}
