package io.github.lib0823.devagent.llm;

import io.github.lib0823.devagent.platform.PortHealth;

/**
 * 채팅용 LLM 포트(전략): 프롬프트 1건 → 텍스트 응답 1건. 요약·멘션 초안·{@code @AI} 답변이 쓴다.
 * 구현체는 {@code platform.chat-model} 값으로 하나만 활성화된다(현재 {@code claude-cli} = Claude Code CLI warm 세션).
 * Claude API·다른 LLM API 는 이 인터페이스를 구현한 빈을 추가하면 된다. 사용량은 구현체가 {@link UsageService#record} 로 남긴다.
 */
public interface ChatModel {

    /** 구현체 이름(로그·관리 화면 표시). 예: claude-cli */
    String provider();

    /** 연결 상태(즉시 응답, LLM 호출 없음). 실제 호출 검증은 {@link #complete} 로 한다. */
    PortHealth health();

    /** 프롬프트를 보내고 모델의 텍스트 응답을 반환한다(블로킹). 실패는 RuntimeException. */
    String complete(String prompt);
}
