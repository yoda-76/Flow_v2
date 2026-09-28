<#
.SYNOPSIS
  Machine settings a 24/7 run wants (no sleep, no forced update reboot, time sync). UNTESTED (written 2026-09-28).
  DEFAULT IS A DRY RUN: it only prints what it would change and what it found. Add -Apply, in an ELEVATED PowerShell,
  to make the changes. Nothing here touches MotiveWave, FLOW, an order or the account.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File ops\harden_windows.ps1            # look
  powershell -ExecutionPolicy Bypass -File ops\harden_windows.ps1 -Apply     # do it (admin)
#>
param([switch]$Apply)
$ErrorActionPreference = "Continue"

function Step($what, [scriptblock]$do) {
  if ($Apply) { Write-Host "APPLY  $what"; & $do } else { Write-Host "WOULD  $what" }
}

Write-Host "== power: never sleep, never turn the disk off, no hibernate =="
Step "powercfg standby-timeout-ac 0"  { powercfg /change standby-timeout-ac 0 }
Step "powercfg standby-timeout-dc 0"  { powercfg /change standby-timeout-dc 0 }
Step "powercfg hibernate-timeout-ac 0" { powercfg /change hibernate-timeout-ac 0 }
Step "powercfg disk-timeout-ac 0"     { powercfg /change disk-timeout-ac 0 }
Step "powercfg hibernate off"         { powercfg /hibernate off }
Step "power plan = High performance"  { powercfg /setactive SCHEME_MIN }

Write-Host "== Windows Update: never reboot on its own while someone is logged on =="
$au = "HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU"
Step "$au NoAutoRebootWithLoggedOnUsers=1" {
  New-Item -Path $au -Force | Out-Null
  Set-ItemProperty -Path $au -Name NoAutoRebootWithLoggedOnUsers -Value 1 -Type DWord
}
Write-Host "   (updates still install; the reboot they ask for is YOURS to schedule, ideally inside the 16:00-17:00 CT halt)"

Write-Host "== time: the runtime trusts this machine's clock =="
Write-Host "   current source / last sync:"
w32tm /query /status | Select-String "Source|Last Successful Sync Time|Leap Indicator"
Step "w32tm /resync /force" { w32tm /resync /force }
Write-Host "   On EC2 the default source is the Amazon Time Sync Service (169.254.169.123) and is already good;"
Write-Host "   on a home PC 'Local CMOS Clock' / 'not synchronized' is the bad state (the laptop was ~3 s off)."

Write-Host "== screen lock / screensaver: a locked session can stop GUI apps from rendering =="
Step "disable the lock screen timeout (no screensaver)" {
  Set-ItemProperty -Path "HKCU:\Control Panel\Desktop" -Name ScreenSaveActive -Value 0
}
Write-Host "done. $(if (-not $Apply) { 'This was a dry run -- add -Apply (elevated) to change things.' })"
