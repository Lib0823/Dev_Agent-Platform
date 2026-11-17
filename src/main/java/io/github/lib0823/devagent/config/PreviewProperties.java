package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로컬 프리뷰(화면 검증) 설정 (prefix: preview).
 * <p>
 * 이슈 에이전트가 수정한 소스를 Maven 으로 WAR 빌드 → 봇 호스트의 Tomcat(CATALINA_BASE 인스턴스)에 배포 → Playwright MCP 로
 * 실제 화면을 열어 스크린샷·검증한다. 도구 설치는 scripts/setup-preview.ps1 (tools/ 아래, git 제외).
 */
@ConfigurationProperties(prefix = "preview")
public record PreviewProperties(
        boolean enabled,
        String javaHome,            // JDK 21 (tools/jdk-21)
        String mavenHome,           // tools/maven
        String catalinaHome,        // tools/tomcat
        String catalinaBase,        // tools/tomcat-preview (server.xml 포트 8790)
        int port,                   // 프리뷰 HTTP 포트
        String contextPath,         // /app
        String warName,             // 배포 파일명(app.war)
        String mavenArgs,           // 추가 Maven 인자(예: -s tools/m2-settings.xml)
        String overlayDirs,         // WAR 에 구워진 localhost 설정 위에 덮어쓸 src/main/resources 하위 폴더(쉼표, 예: db,opensearch)
        long buildTimeoutMs,
        long startTimeoutMs,
        String loginId,             // 프리뷰 로그인 테스트 계정(선택, 나중에 받으면 .env 에)
        String loginPw,
        String playwrightVersion,   // @playwright/mcp 버전 고정
        String browser,             // msedge | chrome | chromium
        boolean headless
) {

    public String url() {
        String cp = contextPath == null || contextPath.isBlank() ? "" : (contextPath.startsWith("/") ? contextPath : "/" + contextPath);
        return "http://localhost:" + port + cp + "/";
    }

    public boolean hasLogin() {
        return loginId != null && !loginId.isBlank() && loginPw != null && !loginPw.isBlank();
    }
}
