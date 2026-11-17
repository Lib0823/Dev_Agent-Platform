@echo off
REM Stop the bot cleanly (supervisor will NOT auto-restart).
REM Disables the scheduled task (so its 5-min watchdog won't revive the bot),
REM drops a stop flag so run.cmd's restart loop exits, then kills java.
REM ASCII-only (cmd reads OEM codepage).
setlocal
set "PROJECT=%~dp0."
schtasks /change /tn "dev-agent" /disable >nul 2>&1
echo stop> "%PROJECT%\bot-stop.flag"
echo [stop] stopping bot...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter 'Name=''java.exe''' | Where-Object { $_.CommandLine -like '*dev-agent.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
echo [stop] done. (start again with restart.cmd)
endlocal
