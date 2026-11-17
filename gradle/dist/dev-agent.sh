#!/bin/sh
# Dev Agent 실행(macOS / Linux). Java 17 이상이 필요합니다(예: brew install temurin@17).
# 설정·로그·데이터는 ~/.dev-agent 에 놓입니다(DEV_AGENT_HOME 으로 바꿀 수 있음). 이미 떠 있으면 브라우저만 엽니다.
DIR="$(cd "$(dirname "$0")" && pwd)"
if ! command -v java >/dev/null 2>&1; then
  echo "Java 17 이상이 필요합니다. 설치 후 다시 실행하세요." >&2
  exit 1
fi
exec java -Xmx48m -XX:+UseSerialGC -Dfile.encoding=UTF-8 -Dapple.awt.UIElement=true -jar "$DIR/dev-agent.jar" --launch
