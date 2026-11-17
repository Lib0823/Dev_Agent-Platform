# Register the "dev-agent" scheduled task (replaces the old Startup-folder VBS).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File install-autostart.ps1
# ASCII-only on purpose (Windows PowerShell 5 reads BOM-less files in the ANSI codepage).
#
# - Starts run.cmd (supervisor) at logon, without a console window (conhost --headless).
# - Re-fires every 5 minutes: if standby/sleep killed the whole process tree, the bot
#   comes back on its own. run.cmd holds bot.lock, so a re-fire while it is alive exits.
# - stop.cmd disables the task (so it stays stopped); restart.cmd re-enables it.
$ErrorActionPreference = 'Stop'
$TaskName = 'dev-agent'
$Project  = $PSScriptRoot
$RunCmd   = Join-Path $Project 'run.cmd'
$User     = "$env:USERDOMAIN\$env:USERNAME"

$action = New-ScheduledTaskAction -Execute "$env:WINDIR\System32\conhost.exe" `
    -Argument "--headless cmd.exe /c `"$RunCmd`"" -WorkingDirectory $Project

$logon = New-ScheduledTaskTrigger -AtLogOn -User $User
$logon.Delay = 'PT30S'   # give Docker Desktop a head start
$watchdog = New-ScheduledTaskTrigger -Once -At (Get-Date) `
    -RepetitionInterval (New-TimeSpan -Minutes 5)

$settings = New-ScheduledTaskSettingsSet `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)

$principal = New-ScheduledTaskPrincipal -UserId $User -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger @($logon, $watchdog) `
    -Settings $settings -Principal $principal -Force | Out-Null

# The old Startup-folder launcher is superseded by this task.
$oldVbs = Join-Path ([Environment]::GetFolderPath('Startup')) 'dev-agent-autostart.vbs'
if (Test-Path $oldVbs) { Remove-Item $oldVbs -Force; Write-Host "[autostart] removed old $oldVbs" }

Write-Host "[autostart] task '$TaskName' registered. Start now with: schtasks /run /tn $TaskName"
