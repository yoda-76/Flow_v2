<#
.SYNOPSIS
  Registers the scheduled task that keeps the Gianluca Bruni curiosity backtest running unattended on the VM.
  UNTESTED on the VM (written 2026-10-13) -- read it, run it once by hand, then check `Get-ScheduledTask FLOW-GB-Backtest`.

.DESCRIPTION
  FLOW-GB-Backtest   at logon, and re-tried every 30 min: python ops\gb_backtest_supervisor.py --loop
                     -MultipleInstances IgnoreNew + no time limit: one supervisor runs forever; if it ever dies, the next
                     30-min repetition starts it again. The supervisor only restarts the backtest runner
                     (BELOW_NORMAL priority); it never starts Claude -- see docs/dynamic/gianlucaBruniBacktest_plan_v1.md §10.

  Nothing here places an order, arms anything, touches MotiveWave or reads .env. Runs as the CURRENT user, only while
  that user is logged on.

.PARAMETER PythonExe  full path of python.exe (default: the one on PATH)
.PARAMETER Remove     unregister the task (does not stop an already running supervisor: see the plan §10.7)
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File ops\register_gb_backtest_task.ps1
#>
param(
  [string]$PythonExe = (Get-Command python -ErrorAction SilentlyContinue).Source,
  [switch]$Remove
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$name = "FLOW-GB-Backtest"

if ($Remove) {
  Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
  Write-Host "removed $name"
  return
}
if (-not $PythonExe -or -not (Test-Path $PythonExe)) { throw "python.exe not found -- pass -PythonExe <path>" }

$user = "$env:USERDOMAIN\$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
  -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Seconds 0)
$action = New-ScheduledTaskAction -Execute $PythonExe `
  -Argument "`"$repo\ops\gb_backtest_supervisor.py`" --loop" -WorkingDirectory $repo
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$trigger.Repetition = (New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 30)).Repetition
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Write-Host "registered $name  (python $repo\ops\gb_backtest_supervisor.py --loop)"
Write-Host "Start it now:  Start-ScheduledTask $name      Status:  python ops\gb_backtest_supervisor.py --status"
