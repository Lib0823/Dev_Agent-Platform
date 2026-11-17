@echo off
REM Restart the bot: stop cleanly (no auto-restart), then start fresh via the
REM "dev-agent" scheduled task (see install-autostart.ps1).
REM Use after rebuilding the jar or editing .env. ASCII-only.
setlocal
set "PROJECT=%~dp0."
echo [restart] stopping existing bot...
echo stop> "%PROJECT%\bot-stop.flag"
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter 'Name=''java.exe''' | Where-Object { $_.CommandLine -like '*dev-agent.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
REM give the old supervisor a moment to see the flag and exit (releases bot.lock)
ping -n 3 127.0.0.1 >nul
echo [restart] starting bot...
schtasks /change /tn "dev-agent" /enable >nul 2>&1
schtasks /run /tn "dev-agent" >nul 2>&1
if errorlevel 1 (
  echo [restart] scheduled task not found - run install-autostart.ps1 first. starting headless directly.
  start "" conhost.exe --headless cmd.exe /c "%PROJECT%\run.cmd"
)
echo [restart] done. (check logs\dev-agent.log)
endlocal
