<#
.SYNOPSIS
  Registers the two Windows scheduled tasks a 24/7 FLOW run needs. UNTESTED on a fresh machine (written 2026-09-28) --
  read it, run it once by hand in an elevated PowerShell, then check `Get-ScheduledTask FLOW-*`.

.DESCRIPTION
  FLOW-Watchdog        every minute: python ops\watchdog.py   (alerts if MotiveWave/the study dies, the feed goes stale,
                       the runtime disarms, the disk fills; forwards logs\alerts.log to Telegram when .env has the bot)
  FLOW-Start-MotiveWave  at logon (after 30 s): starts MotiveWave if it is not running, so a reboot does not leave the
                       machine idle. It does NOT add or arm the study -- see docs/runbook-ec2.md "After a restart".

  Nothing here places an order, arms anything or touches the account. Both tasks run as the CURRENT user, only while
  that user is logged on (MotiveWave is a GUI program and needs the desktop session anyway).

.PARAMETER PythonExe     full path of python.exe (default: the one on PATH)
.PARAMETER MotiveWaveExe full path of MotiveWave.exe
.PARAMETER Remove        unregister both tasks instead
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File ops\register_tasks.ps1
#>
param(
  [string]$PythonExe = (Get-Command python -ErrorAction SilentlyContinue).Source,
  [string]$MotiveWaveExe = "C:\Program Files (x86)\MotiveWave\MotiveWave.exe",
  [switch]$Remove
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$names = @("FLOW-Watchdog", "FLOW-Start-MotiveWave")

if ($Remove) {
  foreach ($n in $names) { Unregister-ScheduledTask -TaskName $n -Confirm:$false -ErrorAction SilentlyContinue; Write-Host "removed $n" }
  return
}
if (-not $PythonExe -or -not (Test-Path $PythonExe)) { throw "python.exe not found -- pass -PythonExe <path>" }
if (-not (Test-Path $MotiveWaveExe)) { throw "MotiveWave.exe not found at $MotiveWaveExe -- pass -MotiveWaveExe <path>" }

$user = "$env:USERDOMAIN\$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
  -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 5)

# 1. watchdog: at logon, then every minute, forever
$watchAction = New-ScheduledTaskAction -Execute $PythonExe -Argument "`"$repo\ops\watchdog.py`"" -WorkingDirectory $repo
$watchTrigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$watchTrigger.Repetition = (New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 1)).Repetition
Register-ScheduledTask -TaskName "FLOW-Watchdog" -Action $watchAction -Trigger $watchTrigger -Principal $principal -Settings $settings -Force | Out-Null
Write-Host "registered FLOW-Watchdog  (python $repo\ops\watchdog.py, every minute)"

# 2. MotiveWave at logon, only if not already running
$startCmd = "if (-not (Get-Process -Name MotiveWave -ErrorAction SilentlyContinue)) { Start-Sleep -Seconds 30; Start-Process -FilePath '$MotiveWaveExe' }"
$startAction = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -WindowStyle Hidden -Command `"$startCmd`""
$startTrigger = New-ScheduledTaskTrigger -AtLogOn -User $user
Register-ScheduledTask -TaskName "FLOW-Start-MotiveWave" -Action $startAction -Trigger $startTrigger -Principal $principal `
  -Settings (New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 1)) -Force | Out-Null
Write-Host "registered FLOW-Start-MotiveWave  (starts $MotiveWaveExe at logon if it is not running)"

Write-Host ""
Write-Host "Next: run 'python ops\watchdog.py --test-alert' to check the alert channel, and read docs\runbook-ec2.md."
