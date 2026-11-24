package io.github.lib0823.devagent.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lib0823.devagent.workspace.WorkspaceApprovals;
import io.github.lib0823.devagent.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「확인 후 실행」 작업 공간의 확인 도구 — 아주 작은 MCP 서버(HTTP, JSON-RPC 한 번에 한 응답).
 * Claude CLI 가 {@code --permission-prompt-tool mcp__approval__ask} 로 이 도구를 부르면, 사용자가 화면에서 허용/거부할 때까지 응답을 미룬다.
 * 주소의 토큰이 어느 차례의 요청인지 알려 준다(차례마다 새 토큰, 끝나면 폐기 — {@link WorkspaceApprovals}).
 * 도구 하나({@code ask})만 있고, 답은 text 블록 안의 JSON({@code {"behavior":"allow","updatedInput":…}} / {@code {"behavior":"deny","message":…}}).
 */
@RestController
public class WorkspaceAskController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAskController.class);
    public static final String SERVER = "approval";
    public static final String TOOL = "ask";

    private final WorkspaceApprovals approvals;
    private final WorkspaceService workspaces;
    private final ObjectMapper json;

    public WorkspaceAskController(WorkspaceApprovals approvals, WorkspaceService workspaces, ObjectMapper json) {
        this.approvals = approvals;
        this.workspaces = workspaces;
        this.json = json;
    }

    @PostMapping(value = "/mcp/approval/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rpc(@PathVariable String token, @RequestBody String body) {
        JsonNode req;
        try {
            req = json.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error\"}}");
        }
        String method = req.path("method").asText("");
        JsonNode id = req.get("id");
        if (id == null || id.isNull()) {
            return ResponseEntity.accepted().build();           // 알림(notifications/initialized 등): 본문 없음
        }
        if (approvals.turnOf(token) == null) {
            return ResponseEntity.ok(error(id, -32000, "approval window closed"));
        }
        ObjectNode result = json.createObjectNode();
        switch (method) {
            case "initialize" -> {
                result.put("protocolVersion", req.path("params").path("protocolVersion").asText("2025-03-26"));
                result.putObject("capabilities").putObject("tools");
                ObjectNode info = result.putObject("serverInfo");
                info.put("name", "dev-agent-approval");
                info.put("version", "1");
            }
            case "ping" -> {
                // 빈 결과
            }
            case "tools/list" -> {
                ObjectNode tool = result.putArray("tools").addObject();
                tool.put("name", TOOL);
                tool.put("description", "Ask the user to approve or deny a tool use.");
                ObjectNode schema = tool.putObject("inputSchema");
                schema.put("type", "object");
                ObjectNode props = schema.putObject("properties");
                props.putObject("tool_name").put("type", "string");
                props.putObject("input").put("type", "object");
                props.putObject("tool_use_id").put("type", "string");
            }
            case "tools/call" -> {
                JsonNode args = req.path("params").path("arguments");
                String tool = args.path("tool_name").asText(args.path("toolName").asText("?"));
                JsonNode input = args.has("input") ? args.get("input") : json.createObjectNode();
                log.debug("확인 요청 tool={} keys={}", tool, args.fieldNames());
                WorkspaceApprovals.Decision d = workspaces.askUser(token, tool, input);
                ObjectNode answer = json.createObjectNode();
                if (d.allow()) {
                    answer.put("behavior", "allow");
                    answer.set("updatedInput", input);
                } else {
                    answer.put("behavior", "deny");
                    answer.put("message", d.message() == null ? "사용자가 이 작업을 허용하지 않았습니다." : "사용자가 허용하지 않았습니다: " + d.message());
                }
                ObjectNode text = result.putArray("content").addObject();
                text.put("type", "text");
                text.put("text", answer.toString());
            }
            default -> {
                return ResponseEntity.ok(error(id, -32601, "method not found: " + method));
            }
        }
        ObjectNode res = json.createObjectNode();
        res.put("jsonrpc", "2.0");
        res.set("id", id);
        res.set("result", result);
        return ResponseEntity.ok(res.toString());
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode res = json.createObjectNode();
        res.put("jsonrpc", "2.0");
        res.set("id", id);
        ObjectNode e = res.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return res.toString();
    }
}
