package io.github.lib0823.devagent.docs;

import io.github.lib0823.devagent.config.DocsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.lib0823.devagent.platform.PortHealth;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 질문과 관련된 사내 문서 섹션을 검색해 프롬프트에 주입할 컨텍스트를 만든다(RAG 조회부).
 * 호출부(멘션 @AI 경로)는 이 서비스가 null 을 주면 문서 없이 평소대로 답한다.
 * <p>
 * 게이트: 토큰이 없거나 최고 점수가 {@code docs.min-score} 미만이면 null 을 반환해 주입을 건너뛴다
 * → 문서와 무관한 일반 대화의 응답 속도를 그대로 유지한다. 검색 자체는 DB 1쿼리(~수십 ms).
 */
@Service
@ConditionalOnProperty(prefix = "platform", name = "knowledge", havingValue = "mkdocs-keyword", matchIfMissing = true)
public class DocRetrievalService implements KnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(DocRetrievalService.class);

    /** 한국어 조사/어미(뒤에서 떼어 어근 추출). 긴 것부터 시도. */
    private static final String[] PARTICLES = {
            "에서", "으로", "에게", "까지", "부터", "보다", "처럼", "라고", "이라", "에는", "에서는",
            "은", "는", "이", "가", "을", "를", "의", "에", "로", "과", "와", "도", "만", "님", "들"
    };

    /** 검색 신호가 없는 흔한 호출/질문 어구. */
    private static final Set<String> STOP = Set.of(
            "알려줘", "알려", "해줘", "해줄래", "말해줘", "설명해줘", "뭐야", "무엇", "무엇인가",
            "어떻게", "어떤", "있나", "있어", "있는지", "인가요", "인가", "좀", "그리고", "관련",
            "대해", "대해서", "정리해줘", "요약해줘", "ai", "AI"
    );

    private final DocsProperties props;
    private final DocChunkMapper mapper;

    private final ObjectProvider<DocIngestService> ingest;

    public DocRetrievalService(DocsProperties props, DocChunkMapper mapper, ObjectProvider<DocIngestService> ingest) {
        this.props = props;
        this.mapper = mapper;
        this.ingest = ingest;
    }

    @Override
    public String provider() {
        return "mkdocs-keyword";
    }

    @Override
    public PortHealth health() {
        if (!props.enabled()) {
            return PortHealth.of(provider(), PortHealth.WARN, "비활성(DOCS_ENABLED=false)");
        }
        DocIngestService in = ingest.getIfAvailable();
        long count = in == null ? -1 : in.count();
        String last = in == null ? null : in.getLastResult();
        boolean failed = last != null && last.contains("실패");
        PortHealth h = count <= 0
                ? PortHealth.of(provider(), PortHealth.DOWN, "적재된 문서 없음")
                : PortHealth.of(provider(), failed ? PortHealth.WARN : PortHealth.OK,
                        failed ? "마지막 적재 실패 — 이전에 적재한 " + count + "개 섹션으로 검색 중" : count + "개 섹션 적재됨(키워드 검색)");
        return h.with("sections", count).with("lastIngest", last)
                .with("lastIngestAt", in == null || in.getLastRefreshAt() == null ? null : in.getLastRefreshAt().toString())
                .with("source", props.searchIndexUrl());
    }

    /**
     * 질문 텍스트로 관련 섹션을 찾아 주입용 컨텍스트 블록을 만든다. 관련 문서가 없으면 null.
     * 어떤 예외가 나도 호출부 응답을 막지 않도록 null 로 흡수한다.
     */
    @Override
    public String retrieveContext(String query) {
        if (!props.enabled() || query == null || query.isBlank()) {
            return null;
        }
        try {
            List<String> terms = tokenize(query);
            if (terms.isEmpty()) {
                return null;
            }
            // ko/en 중복 제거 여유를 위해 topK의 2배를 뽑고 build()에서 중복제거+개수 상한 적용
            List<DocChunk> hits = mapper.search(String.join(",", terms), Math.max(1, props.topK()) * 2);
            if (hits.isEmpty() || hits.get(0).getScore() < props.minScore()) {
                log.debug("문서 게이트 미달 — 주입 생략 (terms={}, top={})",
                        terms, hits.isEmpty() ? 0 : hits.get(0).getScore());
                return null;
            }
            return build(hits);
        } catch (Exception e) {
            log.warn("문서 검색 실패(무시하고 일반 응답): {}", e.toString());
            return null;
        }
    }

    private String build(List<DocChunk> hits) {
        StringBuilder sb = new StringBuilder();
        // 지시: 과도한 추론을 억제하고 길이를 제한 → RAG 턴 지연의 주원인(모델 thinking) 단축(실측 18s→~7s)
        sb.append("--- 참고 사내문서 (아래에서 바로 발췌해 핵심만 간결히 답하라. 불필요하게 깊이 추론하지 말고 5문장 이내로. "
                + "실제로 사용한 항목의 [출처] URL을 끝에 밝히고, 관련 내용이 없으면 억지로 인용하지 말 것) ---\n");
        int budget = props.maxContextChars();
        int used = 0;
        int n = 0;
        Set<String> seenCanonical = new LinkedHashSet<>(); // 같은 섹션의 ko/en 중복 주입 방지(점수순으로 먼저 온 것 유지)
        int maxSources = Math.max(1, props.topK());
        for (DocChunk c : hits) {
            if (n >= maxSources) {
                break;
            }
            String canonical = c.getLocation().startsWith("ko/") ? c.getLocation().substring(3) : c.getLocation();
            if (!seenCanonical.add(canonical)) {
                continue;
            }
            String head = "[출처" + (n + 1) + "] " + c.getTitle() + "  (" + c.getUrl() + ")\n";
            String body = c.getBody();
            if (used + head.length() + body.length() > budget) {
                int room = budget - used - head.length();
                if (room < 120) {
                    break; // 남은 예산이 너무 작으면 중단
                }
                body = body.substring(0, room) + "…";
            }
            sb.append(head).append(body).append("\n\n");
            used += head.length() + body.length();
            n++;
        }
        if (n == 0) {
            return null;
        }
        sb.append("--- 참고문서 끝 ---\n");
        log.info("문서 {}개 주입 (약 {}자)", n, used);
        return sb.toString();
    }

    /** 관리 페이지 디버그: 토큰·게이트 통과 여부·상위 히트(점수/제목/URL)를 보여준다. */
    @Override
    public Map<String, Object> debug(String query, int k) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> terms = tokenize(query == null ? "" : query);
        m.put("terms", terms);
        List<DocChunk> hits = terms.isEmpty() ? List.of()
                : mapper.search(String.join(",", terms), Math.max(1, k));
        int top = hits.isEmpty() ? 0 : hits.get(0).getScore();
        m.put("minScore", props.minScore());
        m.put("gatePassed", top >= props.minScore());
        List<Map<String, Object>> list = new ArrayList<>();
        for (DocChunk c : hits) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("score", c.getScore());
            h.put("title", c.getTitle());
            h.put("url", c.getUrl());
            list.add(h);
        }
        m.put("hits", list);
        return m;
    }

    /** 질문 → 검색 토큰. 분리 → 조사/어미 제거 → 불용어/짧은 토큰 제거 → 중복 제거(최대 8개). */
    List<String> tokenize(String query) {
        String[] rawTokens = query.split("[\\s,./()\\[\\]{}:;!?\"'`~…·\\-_=+*<>|@#%\\\\]+");
        Set<String> out = new LinkedHashSet<>();
        for (String raw : rawTokens) {
            String t = raw.trim();
            if (t.isEmpty() || STOP.contains(t) || STOP.contains(t.toLowerCase())) {
                continue;
            }
            t = stripParticle(t);
            if (t.length() < 2) {
                continue;
            }
            out.add(t);
            if (out.size() >= 8) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    /** 끝에 붙은 조사/어미를 떼되, 어근이 2자 미만으로 줄면 원형을 유지한다. */
    private static String stripParticle(String token) {
        for (String p : PARTICLES) {
            if (token.length() > p.length() && token.endsWith(p)
                    && token.length() - p.length() >= 2) {
                return token.substring(0, token.length() - p.length());
            }
        }
        return token;
    }
}
