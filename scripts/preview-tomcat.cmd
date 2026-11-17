@echo off
REM Preview Tomcat controller for the `shop` WAR (CATALINA_BASE = tools\tomcat-preview).
REM Keep this file ASCII-only (cmd.exe reads .cmd in the OEM codepage).
REM Usage: preview-tomcat.cmd start|stop|status|deploy ^<project-root^>
REM   start   - launches Tomcat in a separate minimized console; stdout/stderr -> logs\catalina.out
REM   stop    - sends SHUTDOWN, waits ~20s for the JVM to exit, then force-kills it.
REM             (the shop app leaves non-daemon threads behind, so the JVM never exits
REM              on its own after a clean Tomcat shutdown - the force kill is expected.)
REM   status  - prints RUNNING/STOPPED; exit code 0 = JVM running, 1 = stopped
REM   deploy  - (Tomcat must be stopped) copies <project-root>\target\app (exploded WAR
REM             built by `mvn package`) to webapps\app and then overlays
REM             <project-root>\src\main\resources\{db,opensearch}\*.properties, because the
REM             WAR bakes in deploy\standard\webapp\WEB-INF\classes (127.0.0.1 localhost config)
REM             on top of src\main\resources (dev-server config).
REM The JVM is identified by "tomcat-preview" in its command line (catalina.base), not by port,
REM so a JVM that is still starting or hung after shutdown is detected as well.
setlocal
set "ROOT=%~dp0.."
for %%I in ("%ROOT%") do set "ROOT=%%~fI"
set "JAVA_HOME=%ROOT%\tools\jdk-21"
set "CATALINA_HOME=%ROOT%\tools\tomcat"
set "CATALINA_BASE=%ROOT%\tools\tomcat-preview"
set "HTTP_PORT=8790"
set "APP_URL=http://localhost:%HTTP_PORT%/app/"
set "OUT=%CATALINA_BASE%\logs\catalina.out"
set "TITLE=shop-preview-tomcat"
set "SYS=%SystemRoot%\System32"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [preview] JAVA_HOME missing: %JAVA_HOME% - run scripts\setup-preview.ps1 first
  exit /b 2
)
if not exist "%CATALINA_BASE%\conf\server.xml" (
  echo [preview] CATALINA_BASE missing: %CATALINA_BASE% - run scripts\setup-preview.ps1 first
  exit /b 2
)

if /i "%~1"=="start"  goto start
if /i "%~1"=="stop"   goto stop
if /i "%~1"=="status" goto status
if /i "%~1"=="deploy" goto deploy
echo Usage: %~nx0 start^|stop^|status^|deploy ^<project-root^>
exit /b 2

:findpid
REM sets PID to the java.exe whose command line contains tomcat-preview (empty if none)
set "PID="
for /f "usebackq" %%p in (`%SYS%\WindowsPowerShell\v1.0\powershell.exe -NoProfile -Command "(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*tomcat-preview*' } | Select-Object -First 1).ProcessId"`) do set "PID=%%p"
goto :eof

:findport
REM sets LISTENING=1 if something listens on HTTP_PORT
set "LISTENING="
for /f "tokens=5" %%p in ('%SYS%\netstat.exe -ano -p tcp ^| %SYS%\findstr.exe /r /c:":%HTTP_PORT% .*LISTENING"') do set "LISTENING=1"
goto :eof

:alive
REM sets ALIVE=1 if process PID exists
set "ALIVE="
for /f "usebackq" %%a in (`%SYS%\tasklist.exe /fi "PID eq %PID%" /nh /fo csv ^| %SYS%\findstr.exe /c:"%PID%"`) do set "ALIVE=1"
goto :eof

:start
call :findpid
if defined PID (
  echo [preview] already running pid %PID%
  exit /b 0
)
call :findport
if defined LISTENING (
  echo [preview] port %HTTP_PORT% is in use by another process - refusing to start
  exit /b 1
)
if not exist "%CATALINA_BASE%\logs" mkdir "%CATALINA_BASE%\logs"
echo [preview] starting Tomcat - CATALINA_BASE=%CATALINA_BASE% port %HTTP_PORT%
start "%TITLE%" /min cmd /c ""%CATALINA_HOME%\bin\catalina.bat" run >> "%OUT%" 2>&1"
echo [preview] launched; stdout -^> %OUT%
echo [preview] app url: %APP_URL%
exit /b 0

:stop
call :findpid
if not defined PID (
  echo [preview] not running
  exit /b 0
)
echo [preview] stopping Tomcat pid %PID% ...
call "%CATALINA_HOME%\bin\catalina.bat" stop >nul 2>&1
set /a N=0
:stopwait
call :alive
if not defined ALIVE goto stopped
set /a N+=1
if %N% geq 10 goto stopkill
"%SYS%\ping.exe" -n 2 127.0.0.1 >nul
goto stopwait
:stopkill
echo [preview] JVM still alive after ~20s - force killing pid %PID%
"%SYS%\taskkill.exe" /f /t /pid %PID% >nul 2>&1
"%SYS%\ping.exe" -n 3 127.0.0.1 >nul
call :alive
if defined ALIVE (
  echo [preview] FAILED to kill pid %PID%
  exit /b 1
)
:stopped
call :findport
if defined LISTENING (
  echo [preview] WARNING: JVM exited but port %HTTP_PORT% still listening
  exit /b 1
)
echo [preview] stopped; port %HTTP_PORT% released
exit /b 0

:status
call :findpid
if not defined PID (
  echo [preview] STOPPED
  exit /b 1
)
set "CODE=000"
for /f %%c in ('%SYS%\curl.exe -s -o NUL -w "%%{http_code}" --max-time 5 "%APP_URL%" 2^>nul') do set "CODE=%%c"
if "%CODE%"=="000" (
  echo [preview] RUNNING pid %PID% - http not ready yet on port %HTTP_PORT%
) else (
  echo [preview] RUNNING pid %PID% http=%CODE% %APP_URL%
)
exit /b 0

:deploy
set "PROJ=%~2"
if "%PROJ%"=="" (
  echo [preview] deploy needs the project root: %~nx0 deploy ^<project-root^>
  exit /b 2
)
for %%I in ("%PROJ%") do set "PROJ=%%~fI"
set "EXPLODED=%PROJ%\target\app"
if not exist "%EXPLODED%\WEB-INF\classes" (
  echo [preview] exploded WAR not found: %EXPLODED% - run mvn package first
  exit /b 1
)
call :findpid
if defined PID (
  echo [preview] Tomcat is running pid %PID% - stop it before deploy
  exit /b 1
)
set "DEST=%CATALINA_BASE%\webapps\app"
echo [preview] deploying %EXPLODED% -^> %DEST%
if exist "%CATALINA_BASE%\webapps\app.war" del /q "%CATALINA_BASE%\webapps\app.war"
if exist "%CATALINA_BASE%\work\Catalina\localhost\app" rmdir /s /q "%CATALINA_BASE%\work\Catalina\localhost\app"
"%SYS%\robocopy.exe" "%EXPLODED%" "%DEST%" /MIR /MT:16 /R:2 /W:2 /NFL /NDL /NJH /NP >nul
if errorlevel 8 (
  echo [preview] robocopy failed with code %errorlevel%
  exit /b 1
)
REM overlay dev-server properties (src\main\resources) over the baked-in localhost ones
copy /y "%PROJ%\src\main\resources\db\postgresql.properties" "%DEST%\WEB-INF\classes\db\postgresql.properties" >nul || exit /b 1
copy /y "%PROJ%\src\main\resources\opensearch\opensearch.properties" "%DEST%\WEB-INF\classes\opensearch\opensearch.properties" >nul || exit /b 1
echo [preview] deployed; db/opensearch properties overlaid from src\main\resources
exit /b 0
