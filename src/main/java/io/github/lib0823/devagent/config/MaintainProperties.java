package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 플랫폼 관리(자기 유지보수) 설정 (prefix: maintain).
 * 상단바 렌치 버튼의 채팅 창에서 받은 요청을, 이 플랫폼의 소스 저장소에서 도는 Claude 세션이 처리한다
 * (버그 확인·수정·테스트/빌드·커밋·스킬 등록). 푸시와 재시작은 세션이 아니라 화면 버튼이 한다.
 */
@ConfigurationProperties(prefix = "maintain")
public record MaintainProperties(
        boolean enabled,
        String repoDir,          // 플랫폼 소스 저장소. 비우면 실행 디렉터리가 소스 저장소일 때만 켜진다(배포판은 소스가 없어 꺼짐)
        String model,            // 기본 모델(비우면 에이전트 설정의 '개발' 모델). 창에서 대화마다 고를 수 있다
        String effort,           // 기본 노력(비우면 '개발' 노력)
        long timeoutMs,          // 요청 1건 실행 상한(기본 2시간)
        int maxTurns,            // 요청 1건 턴 상한(기본 300)
        List<String> tools,      // --allowedTools (git push 는 넣어도 구현체가 금지한다)
        int maxAnswerChars       // 답변 저장 상한
) {
    public long timeoutOr() {
        return timeoutMs > 0 ? timeoutMs : 2 * 60 * 60_000L;
    }

    public int maxTurnsOr() {
        return maxTurns > 0 ? maxTurns : 300;
    }

    public int maxAnswerCharsOr() {
        return maxAnswerChars > 0 ? maxAnswerChars : 40_000;
    }

    public List<String> toolsOr() {
        return tools == null ? List.of() : tools;
    }
}
