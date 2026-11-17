package io.github.lib0823.devagent.llm;

import io.github.lib0823.devagent.platform.PortHealth;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 도구(MCP)를 쓰는 one-shot LLM 포트(전략): 질문 1건을 받아 읽기 전용 도구를 몇 턴 돌리고 답 1건을 낸다. 운영 질의({@code @AI 운영 …})가 쓴다.
 * 구현체는 {@code platform.chat-model} 값으로 활성화된다(현재 {@code claude-cli}).
 */
public interface ToolChatModel {

    /**
     * @param mcpConfig    MCP 서버 설정 파일(Claude Code {@code --mcp-config} 형식)
     * @param allowedTools 허용 도구 이름
     * @param env          자식 프로세스/클라이언트에 넘길 환경(토큰 등, 파일에 쓰지 않는 값)
     * @param model        모델(비우면 구현체 기본)
     * @param effort       노력 단계(비우면 기본)
     */
    record Request(String systemPrompt, String prompt, Path mcpConfig, List<String> allowedTools, Map<String, String> env,
                   String model, String effort, int maxTurns, long timeoutMs) {
    }

    /** error=true 면 text 는 사용자에게 보여 줄 실패 사유. timedOut 이면 상한 초과. */
    record Response(String text, int turns, double costUsd, long elapsedMs, boolean error, boolean timedOut,
                    long inputTokens, long outputTokens) {
    }

    String provider();

    /** 연결 상태(즉시 응답, LLM 호출 없음). */
    PortHealth health();

    /** 연결 테스트(토큰을 쓰지 않는 가벼운 확인 — CLI 실행 가능 여부, API 키 유효성 등). */
    PortHealth.TestResult selfTest();

    /** 예외 대신 {@code error=true} 로 돌려 호출측이 메시지로 안내하게 한다. */
    Response ask(Request request);
}
