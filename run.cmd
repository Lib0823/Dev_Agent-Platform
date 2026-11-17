@echo off
REM dev-agent launcher + supervisor (host long-running).
REM NOTE: keep this file ASCII-only. cmd.exe reads .cmd in the OEM codepage,
REM so Korean/UTF-8 text here breaks parsing. English only.
REM Waits for Postgres (Docker), then runs the bot in a restart loop:
REM if java exits unexpectedly (crash), it is restarted automatically.
REM To stop cleanly, use stop.cmd / restart.cmd (they drop a stop flag first).
REM Single instance: the supervisor holds bot.lock open while it runs, so a
REM second run.cmd (e.g. the scheduled task re-firing) exits immediately.
setlocal
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17"
set "PROJECT=%~dp0."
set "DOCKER=C:\Program Files\Docker\Docker\resources\bin\docker.exe"
set "STOPFLAG=%PROJECT%\bot-stop.flag"
set "LOCKFILE=%PROJECT%\bot.lock"
REM tells the app it runs under this supervisor (enables restart from the UI)
set "DEV_AGENT_SUPERVISED=1"
cd /d "%PROJECT%"

call :main 9>"%LOCKFILE%" || (
  echo [run] another supervisor is already running. exiting.
  exit /b 1
)
exit /b 0

:main
REM clear any stale stop flag from a previous run
if exist "%STOPFLAG%" del "%STOPFLAG%"

echo [run] waiting for Postgres...
:waitdb
"%DOCKER%" exec dev-agent-postgres pg_isready -U dev_agent -d dev_agent >nul 2>&1
if errorlevel 1 (
  ping -n 4 127.0.0.1 >nul
  goto waitdb
)
echo [run] Postgres ready.

REM Light JVM for a local, IO-bound bot: serial GC, small bounded heap, C1 compiler only.
REM Override by setting BOT_JAVA_OPTS before starting the supervisor.
if not defined BOT_JAVA_OPTS set BOT_JAVA_OPTS=-XX:+UseSerialGC -Xms32m -Xmx512m -XX:TieredStopAtLevel=1

:runloop
REM Run a private copy of the jar. Gradle rewrites build\libs\*.jar on every build;
REM a running JVM that lazily loads classes from a rewritten jar dies with
REM NoClassDefFoundError (seen 2026-11-10). The copy is refreshed on each (re)start.
if not exist "%PROJECT%\run" mkdir "%PROJECT%\run"
copy /y "%PROJECT%\build\libs\dev-agent.jar" "%PROJECT%\run\dev-agent.jar" >nul
if errorlevel 1 echo [run] WARNING: could not refresh run\dev-agent.jar, using previous copy
echo [run] starting bot...
"%JAVA_HOME%\bin\java.exe" %BOT_JAVA_OPTS% -Dfile.encoding=UTF-8 -jar "%PROJECT%\run\dev-agent.jar"
REM java exited. If a stop was requested, exit; otherwise auto-restart (crash recovery).
if exist "%STOPFLAG%" (
  del "%STOPFLAG%"
  echo [run] stop requested. exiting supervisor.
  exit /b 0
)
echo [run] bot exited unexpectedly. restarting in 5s...
ping -n 6 127.0.0.1 >nul
goto runloop
