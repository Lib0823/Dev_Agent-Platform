package io.github.lib0823.devagent.mcp;

import io.github.lib0823.devagent.config.McpProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRoutingTest {

    private static final McpProperties.Chat CHAT = new McpProperties.Chat(true, "sonnet", "medium", 1000, 5,
            List.of("운영", "ops", "조회"), List.of("k3s", "파드", "인덱스", "쿼리"), true);

    private static McpProperties props(String k3sToken) {
        return new McpProperties(true, "http://os/mcp", "http://pg/sse", "http://k3s/mcp", k3sToken,
                List.of("get_nodes", "get_pods", "delete_pod", "exec_command", "restart_deployment"), true, CHAT);
    }

    @Test
    void prefixOrKeywordRoutesToOps() {
        assertTrue(OpsQueryService.matches(CHAT, "운영 노드 상태 알려줘"));
        assertTrue(OpsQueryService.matches(CHAT, "OPS what pods are failing"));
        assertTrue(OpsQueryService.matches(CHAT, "오늘 analyze 인덱스 문서 수?"));
        assertTrue(OpsQueryService.matches(CHAT, "K3S 노드 몇 개야"));
        assertFalse(OpsQueryService.matches(CHAT, "운영진 회식 일정 정리해줘"));   // "운영 " 접두어 아님, 키워드 없음
        assertFalse(OpsQueryService.matches(CHAT, "내일 회의록 초안 써줘"));
        assertFalse(OpsQueryService.matches(null, "운영 x"));
    }

    @Test
    void k3sMutatingToolsAreNeverAllowed() {
        List<String> tools = McpConfigService.buildTools(props("tok"));
        assertEquals(List.of("mcp__opensearch", "mcp__postgres", "mcp__k3s-ops__get_nodes", "mcp__k3s-ops__get_pods"), tools);
    }

    @Test
    void k3sExcludedWithoutToken() {
        McpProperties p = props("");
        assertFalse(p.hasK3s());
        assertEquals(List.of("mcp__opensearch", "mcp__postgres"), McpConfigService.buildTools(p));
        assertTrue(p.anyServer());
    }
}
