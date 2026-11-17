Dev Agent
=========

실행
  Windows : "Dev Agent.exe" 를 더블클릭합니다.
  macOS   : "Dev Agent.app" 을 열거나, 묶음(portable)이면 터미널에서 ./dev-agent.sh
  잠시 뒤 브라우저에 http://127.0.0.1:8777 화면이 열리고, 트레이(메뉴 막대)에 아이콘이 생깁니다.
  다시 실행하면 이미 떠 있는 화면만 엽니다. 끝낼 때는 트레이 아이콘 > 종료.
  PC 를 켤 때마다 자동으로 띄우려면 트레이 아이콘 > 로그인 시 자동 실행 (Windows).

먼저 준비할 것
  1. Claude Code 설치 후 터미널에서 claude 를 한 번 실행해 로그인 (구독 계정)
  2. Git 설치 (Windows 는 Git for Windows — Git Bash 포함)
  데이터베이스와 Java 는 따로 설치하지 않아도 됩니다(내장).

설정
  처음 실행하면 홈 폴더에 설정 파일이 만들어집니다.
    Windows : C:\Users\<사용자>\.dev-agent\.env
    macOS   : ~/.dev-agent/.env
  GitLab 주소·토큰 등을 채운 뒤 트레이 아이콘 > 재시작.
  무엇이 빠졌는지는 화면의 "플랫폼 > 설치 점검" 에서 볼 수 있습니다.
  (트레이 아이콘 > 설정 폴더 열기 로 바로 갈 수 있습니다.)

이 프로그램은 이 PC 에서만 접속할 수 있습니다(127.0.0.1). 계정·토큰은 .env 밖으로 나가지 않습니다.
