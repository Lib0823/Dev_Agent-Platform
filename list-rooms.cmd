@echo off
REM Lists chat rooms with their room-key. Use the key in .env BOT_AUTO_ROOMS.
REM ASCII-only (cmd reads .cmd in OEM codepage).
chcp 65001 >nul
setlocal
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17"
set "PROJECT=%~dp0."
cd /d "%PROJECT%"
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -jar "%PROJECT%\build\libs\dev-agent.jar" --list-rooms
endlocal
pause
