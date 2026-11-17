package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사내 개발문서 RAG 설정 (prefix: docs).
 * MkDocs Material 사이트가 빌드해 둔 search_index.json 을 주기적으로 받아 Postgres(doc_chunk)에
 * 섹션 단위로 적재하고, @AI 명령 처리 시 질문과 관련된 섹션만 검색해 프롬프트에 주입한다(RAG).
 * <p>
 * 속도 영향: 적재는 핫패스 밖(스케줄/기동 1회), 검색은 DB 1쿼리(~수십 ms). 관련 섹션이 없으면
 * ({@code min-score} 미만) 주입을 건너뛰어 일반 응답 속도를 그대로 유지한다.
 */
@ConfigurationProperties(prefix = "docs")
public record DocsProperties(
        boolean enabled,
        String searchIndexUrl,   // MkDocs search_index.json 절대 URL
        String siteBaseUrl,      // 딥링크 기준 사이트 base (끝에 / 포함)
        String refreshCron,      // 주기 적재 cron
        boolean refreshOnStartup,// 기동 직후 1회 적재
        int fetchTimeoutMs,      // 인덱스 다운로드 타임아웃
        int topK,                // 주입할 최대 섹션 수
        int minScore,            // 게이트 임계치 — 최고 점수가 이 미만이면 문서 주입 안 함
        int maxContextChars,     // 주입 컨텍스트 총 길이 상한(토큰/지연 방어)
        int maxBodyChars,        // 섹션 1개당 본문 상한
        boolean useInMentions    // 남의 멘션 초안/AUTO 회신에도 관련 문서 참고(게이트 통과 시에만)
) {
}
