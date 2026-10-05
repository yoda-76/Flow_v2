<#
.SYNOPSIS
  Registers the daily Google Drive backup task for the machine it runs on.
    -Role upload    (the VM):           FLOW-GDrive-Upload    daily 03:00 IST -> ops\sync_to_gdrive.ps1
    -Role download  (this/dev machine): FLOW-GDrive-Download  daily 08:00 IST -> ops\sync_from_gdrive.ps1
  Times are IST; each trigger is converted to this machine's own clock, so it fires at the right moment whatever
  timezone Windows is set to. 03:00 IST falls inside the daily CME halt (02:30-03:30 IST), so the trading day's data
  is complete and nothing else is competing for the machine.

  Both tasks run as the CURRENT user, only while that user is logged on (same as ops\register_tasks.ps1).
  rclone must be on the PATH of that user (set it permanently in Windows environment variables, not per window).

.PARAMETER Role        upload | download
.PARAMETER AtIst       trigger time as HH:mm in IST (default 03:00 for upload, 08:00 for download)
.PARAMETER Remove      unregister the task for this role instead
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File ops\register_gdrive_tasks.ps1 -Role upload
#>
param(
  [Parameter(Mandatory)] [ValidateSet("upload", "download")] [string]$Role,
  [string]$AtIst,
  [switch]$Remove
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

$taskName = if ($Role -eq "upload") { "FLOW-GDrive-Upload" } else { "FLOW-GDrive-Download" }
$script = if ($Role -eq "upload") { "ops\sync_to_gdrive.ps1" } else { "ops\sync_from_gdrive.ps1" }
if (-not $AtIst) { $AtIst = if ($Role -eq "upload") { "03:00" } else { "08:00" } }

if ($Remove) {
  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
  Write-Host "removed $taskName"
  return
}
if (-not (Get-Command rclone -ErrorAction SilentlyContinue)) {
  throw "rclone is not on PATH for this user. Add its folder to the user's PATH first."
}

# Convert the IST trigger time to this machine's local clock.
$ist = [TimeZoneInfo]::FindSystemTimeZoneById("India Standard Time")
$hm = [datetime]::ParseExact($AtIst, "HH:mm", $null)
$istWallClock = [datetime]::SpecifyKind((Get-Date).Date.AddHours($hm.Hour).AddMinutes($hm.Minute), [DateTimeKind]::Unspecified)
$localTime = [TimeZoneInfo]::ConvertTime($istWallClock, $ist, [TimeZoneInfo]::Local)
$localHm = $localTime.ToString("HH:mm")
Write-Host ("{0} {1} IST = {2} on this machine's clock" -f $taskName, $AtIst, $localHm)

$user = "$env:USERDOMAIN\$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
  -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2)
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
  -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$repo\$script`"" -WorkingDirectory $repo
$trigger = New-ScheduledTaskTrigger -Daily -At $localHm

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Write-Host "registered $taskName -> $script (daily $AtIst IST, $localHm local)"
