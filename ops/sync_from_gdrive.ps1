# Pulls the Google Drive backup (ops/sync_to_gdrive.ps1, folders <Root>/by_date/<YYYY-MM-DD>/) down to this
# machine, into gdrive_mirror/<YYYY-MM-DD>/ under FLOW_HOME. Never deletes anything and never writes into
# logs/ or data/, so the live working folders are untouched.
#
#   powershell -ExecutionPolicy Bypass -File ops\sync_from_gdrive.ps1                       # everything
#   powershell -ExecutionPolicy Bypass -File ops\sync_from_gdrive.ps1 -From 2026-09-28 -To 2026-10-02
#
# Analysis tools read one day from the mirror through their --logs/--data flags, e.g.:
#   python analysis/daily_report.py --logs gdrive_mirror/2026-09-29/logs --data gdrive_mirror/2026-09-29/data
param(
  [string]$Remote = "gdrive",
  [string]$Root = "FLOW_V2_backup",
  [string]$FlowHome = $(if ($env:FLOW_HOME) { $env:FLOW_HOME } else { Split-Path -Parent $PSScriptRoot }),
  [string]$From,
  [string]$To,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
if (-not (Get-Command rclone -ErrorAction SilentlyContinue)) {
  Write-Error "rclone is not on PATH."
  exit 2
}
if (($From -and -not $To) -or ($To -and -not $From)) {
  Write-Error "Give both -From and -To, or neither."
  exit 2
}

Set-Location $FlowHome
New-Item -ItemType Directory -Force -Path (Join-Path $FlowHome "logs") | Out-Null
$rcArgs = @("copy", "$Remote`:$Root/by_date", "gdrive_mirror", "--fast-list", "--transfers", "4")

if ($From) {
  $day = [datetime]::ParseExact($From, "yyyy-MM-dd", $null)
  $end = [datetime]::ParseExact($To, "yyyy-MM-dd", $null)
  while ($day -le $end) {
    $rcArgs += @("--include", "$($day.ToString('yyyy-MM-dd'))/**")
    $day = $day.AddDays(1)
  }
}
if ($DryRun) { $rcArgs += "--dry-run" }

Write-Host ("rclone " + ($rcArgs -join " "))
& rclone @rcArgs
$code = $LASTEXITCODE
$status = if ($code -eq 0) { "OK" } else { "FAILED (exit $code)" }
$range = if ($From) { "$From..$To" } else { "all" }
Add-Content -Path (Join-Path $FlowHome "logs/sync_from_gdrive.log") -Value ("{0} range={1} dryRun={2} {3}" -f (Get-Date -Format "s"), $range, [bool]$DryRun, $status)
exit $code
