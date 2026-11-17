package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 운영 MCP 서버 연동 설정 (prefix: mcp). 근거 문서: mcp-integration-guide.md (A안: Claude Code headless + --mcp-config).
 * <p>
 * 세 서버(opensearch/postgres/k3s-ops)를 Claude Code CLI 에 {@code --mcp-config} 로 붙여
 * ① 자기방 {@code @AI} 운영 질의, ② 이슈 에이전트의 분석/수정 단계에서 <b>읽기 전용 도구만</b> 쓰게 한다.
 * k3s-ops 는 변경 도구(restart/scale/delete/apply/exec)가 있으므로 조회 도구만 화이트리스트로 허용한다.
 */
@ConfigurationProperties(prefix = "mcp")
public record McpProperties(
        boolean enabled,
        String opensearchUrl,          // Streamable HTTP, 예: http://mcp.example.com:9900/mcp (비면 미사용)
        String postgresUrl,            // SSE, 예: http://mcp.example.com:8000/sse (비면 미사용)
        String k3sUrl,                 // Streamable HTTP, 예: http://mcp.example.com:8001/mcp (토큰 없으면 미사용)
        String k3sToken,               // Bearer 토큰 — 설정 파일엔 ${K3S_MCP_TOKEN} 자리표시자만 쓰고 프로세스 env 로 주입
        List<String> k3sReadTools,     // 허용할 k3s-ops 조회 도구명 (get_pods 등). 변경 도구는 절대 넣지 않는다
        boolean agentEnabled,          // 이슈 에이전트(분석/수정)에 MCP 읽기 도구 제공
        Chat chat
) {

    /** 자기방 @AI 운영 질의(one-shot claude -p + MCP). */
    public record Chat(
            boolean enabled,
            String model,              // 예: sonnet
            String effort,             // 예: medium
            long timeoutMs,            // 1회 상한
            int maxTurns,
            List<String> prefixes,     // 명시적 라우팅 접두어: "운영", "ops", "조회"
            List<String> keywords,     // 휴리스틱 라우팅 키워드(소문자 비교): k3s, pod, 파드, 인덱스 …
            boolean allowAutoRooms     // 자기방 외에 '방에 직접 답' 화이트리스트 방에서도 운영 질의 허용
    ) {
    }

    public boolean hasOpensearch() {
        return enabled && opensearchUrl != null && !opensearchUrl.isBlank();
    }

    public boolean hasPostgres() {
        return enabled && postgresUrl != null && !postgresUrl.isBlank();
    }

    public boolean hasK3s() {
        return enabled && k3sUrl != null && !k3sUrl.isBlank() && k3sToken != null && !k3sToken.isBlank();
    }

    public boolean anyServer() {
        return hasOpensearch() || hasPostgres() || hasK3s();
    }
}
