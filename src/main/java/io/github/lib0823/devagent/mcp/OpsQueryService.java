package io.github.lib0823.devagent.mcp;

import io.github.lib0823.devagent.config.McpProperties;
import io.github.lib0823.devagent.llm.ToolChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 자기방 {@code @AI} 운영 질의: 운영 MCP(opensearch/postgres/k3s-ops 읽기 도구)를 붙인 one-shot 도구 LLM({@link ToolChatModel})으로 답한다.
 * <p>
 * 채팅용 warm 세션은 콜드스타트 단축을 위해 MCP 를 끈 채 상주하므로(--strict-mcp-config), 운영 질의만 별도 프로세스로 돈다.
 * 라우팅: 명시 접두어("운영 …", "ops …", "조회 …") 또는 키워드 휴리스틱. 자기방에서만 호출된다(호출측 제한).
 */
@Service
public class OpsQueryService {

    private static final Logger log = LoggerFactory.getLogger(OpsQueryService.class);

    private final McpProperties props;
    private final McpConfigService mcp;
    private final ToolChatModel model;

    public OpsQueryService(McpProperties props, McpConfigService mcp, ToolChatModel model) {
        this.props = props;
        this.mcp = mcp;
        this.model = model;
    }

    public boolean enabled() {
        return mcp.available() && props.chat() != null && props.chat().enabled();
    }

    /** '방에 직접 답' 화이트리스트 방에서도 운영 질의를 받을지. */
    public boolean allowAutoRooms() {
        return props.chat() != null && props.chat().allowAutoRooms();
    }

    /** 운영 질의로 볼지. 접두어가 있으면 확정, 아니면 키워드 휴리스틱. */
    public boolean isOpsQuery(String text) {
        return enabled() && matches(props.chat(), text);
    }

    static boolean matches(McpProperties.Chat c, String text) {
        if (c == null || text == null) {
            return false;
        }
        String lower = text.strip().toLowerCase(Locale.ROOT);
        if (c.prefixes() != null) {
            for (String p : c.prefixes()) {
                if (p != null && !p.isBlank() && (lower.startsWith(p.toLowerCase(Locale.ROOT) + " ") || lower.equals(p.toLowerCase(Locale.ROOT)))) {
                    return true;
                }
            }
        }
        if (c.keywords() != null) {
            for (String k : c.keywords()) {
                if (k != null && !k.isBlank() && lower.contains(k.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 접두어("운영 ", "ops ") 제거한 본문. */
    public String stripPrefix(String text) {
        String t = text == null ? "" : text.strip();
        if (props.chat() == null || props.chat().prefixes() == null) {
            return t;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        for (String p : props.chat().prefixes()) {
            if (p == null || p.isBlank()) {
                continue;
            }
            String pl = p.toLowerCase(Locale.ROOT);
            if (lower.startsWith(pl + " ")) {
                return t.substring(p.length()).strip();
            }
            if (lower.equals(pl)) {
                return "";
            }
        }
        return t;
    }

    public record Answer(String text, int turns, double costUsd, long elapsedMs, boolean error, long inputTokens, long outputTokens) {
        public Answer(String text, int turns, double costUsd, long elapsedMs, boolean error) {
            this(text, turns, costUsd, elapsedMs, error, 0, 0);
        }
    }

    /** 운영 질의 실행. 예외 대신 error=true 로 돌려 호출측이 메시지로 안내하게 한다. LLM 호출은 {@link ToolChatModel}(포트) 뒤에 있다. */
    public Answer ask(String question, String context) {
        McpProperties.Chat c = props.chat();
        String prompt = (context == null || context.isBlank() ? "" : context + "\n\n") + "--- 요청 ---\n" + question;
        long timeout = c.timeoutMs() > 0 ? c.timeoutMs() : 120000;
        ToolChatModel.Response r = model.ask(new ToolChatModel.Request(systemPrompt(), prompt, mcp.configPath(), mcp.tools(), mcp.env(),
                c.model(), c.effort(), c.maxTurns() > 0 ? c.maxTurns() : 12, timeout));
        if (r.timedOut()) {
            return new Answer("운영 조회가 " + timeout / 1000 + "초 안에 끝나지 않아 중단했습니다. 질문 범위를 좁혀 다시 시도해 주세요.", 0, 0, timeout, true);
        }
        String text = r.error() && !r.text().startsWith("운영") ? "운영 조회 " + r.text() : r.text();
        log.info("운영 질의 완료 turns={} tokens in={} out={} {}ms error={}", r.turns(), r.inputTokens(), r.outputTokens(), r.elapsedMs(), r.error());
        return new Answer(text, r.turns(), r.costUsd(), r.elapsedMs(), r.error(), r.inputTokens(), r.outputTokens());
    }

    private String systemPrompt() {
        return "당신은 사내 운영 데이터(OpenSearch 로그/분석 인덱스, PostgreSQL, k3s 클러스터)를 조회해 질문에 답하는 운영 보조자입니다. "
                + "한국어로 간결하게, 핵심 수치·이름·상태를 먼저 쓰고 근거(어떤 도구로 무엇을 조회했는지)를 한 줄로 덧붙이세요. "
                + "출력은 사내 메신저로 그대로 전달되므로 마크다운 서식(**굵게**, 표, 코드블록)을 쓰지 말고 '- ' 줄 단위 평문으로 1500자 이내로 쓰세요.\n"
                + mcp.usageHint();
    }
}
