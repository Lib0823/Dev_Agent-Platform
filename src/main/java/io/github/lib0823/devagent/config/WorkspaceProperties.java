package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 작업 공간 설정 (prefix: workspace).
 * 사용자가 등록한 폴더를 작업 디렉터리로 한 Claude 세션과 대화하며 파일을 고치고 명령을 돌린다(이슈·GitLab 없이 바로).
 * 폴더·권한은 화면에서 등록하고(DB), 여기에는 실행 상한 같은 공통 값만 둔다.
 */
@ConfigurationProperties(prefix = "workspace")
public record WorkspaceProperties(
        boolean enabled,
        String baseDir,          // 폴더 찾아보기를 시작할 위치(비우면 사용자 홈). 프로젝트를 모아 두는 폴더를 지정하면 고르기 쉽다
        String model,           // 기본 모델(비우면 에이전트 설정의 '개발' 모델). 요청마다 화면에서 고를 수 있다
        String effort,           // 기본 노력(비우면 '개발' 노력)
        long timeoutMs,          // 요청 1건 실행 상한(기본 2시간)
        int maxTurns,            // 요청 1건 턴 상한(기본 300)
        int concurrency,         // 동시에 도는 요청 수(작업 공간이 달라야 함, 기본 2)
        int maxAnswerChars       // 답변 저장 상한
) {
    public long timeoutOr() {
        return timeoutMs > 0 ? timeoutMs : 2 * 60 * 60_000L;
    }

    public int maxTurnsOr() {
        return maxTurns > 0 ? maxTurns : 300;
    }

    public int concurrencyOr() {
        return concurrency > 0 ? Math.min(concurrency, 6) : 2;
    }

    public int maxAnswerCharsOr() {
        return maxAnswerChars > 0 ? maxAnswerChars : 40_000;
    }
}
