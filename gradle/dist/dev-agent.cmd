@echo off
REM Dev Agent launcher (portable bundle). Requires Java 17+ on PATH. ASCII-only.
start "" javaw -Xmx48m -XX:+UseSerialGC -Dfile.encoding=UTF-8 -jar "%~dp0dev-agent.jar" --launch
