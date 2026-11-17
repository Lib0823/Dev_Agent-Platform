package io.github.lib0823.devagent.llm.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lib0823.devagent.config.ClaudeProperties;
import io.github.lib0823.devagent.llm.ToolChatModel;
import io.github.lib0823.devagent.llm.UsageService;
import io.github.lib0823.devagent.platform.CallStats;
import io.github.lib0823.devagent.platform.PortHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link ToolChatModel} 의 Claude Code CLI 구현: {@code claude -p --output-format json --mcp-config …} one-shot.
 * 프롬프트는 stdin(한글 인자 깨짐 방지), 토큰류는 env 로만 넘긴다.
 */
@Component
@ConditionalOnProperty(prefix = "platform", name = "chat-model", havingValue = "claude-cli", matchIfMissing = true)
public class ClaudeCliToolChat implements ToolChatModel {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliToolChat.class);

    private final ClaudeProperties claude;
    private final UsageService usage;
    private final ObjectMapper json = new ObjectMapper();

    public ClaudeCliToolChat(ClaudeProperties claude, UsageService usage) {
        this.claude = claude;
        this.usage = usage;
    }

    @Override
    public String provider() {
        return "claude-cli";
    }

    private final CallStats stats = new CallStats();

    @Override
    public PortHealth health() {
        return stats.health(provider(), PortHealth.OK, stats.everCalled() ? "최근 질의 정상" : "대기(아직 호출 없음)")
                .with("cli", claude.warmCommand());
    }

    @Override
    public PortHealth.TestResult selfTest() {
        return ClaudeCliProbe.version(claude.warmCommand());
    }

    @Override
    public Response ask(Request rq) {
        long t0 = System.currentTimeMillis();
        Response r = doAsk(rq);
        if (r.error()) {
            stats.error(new IllegalStateException(r.timedOut() ? "시간 초과" : r.text()));
        } else {
            stats.ok(System.currentTimeMillis() - t0);
        }
        return r;
    }

    private Response doAsk(Request rq) {
        List<String> cmd = new ArrayList<>();
        cmd.add(claude.warmCommand());
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");
        cmd.add("--strict-mcp-config");
        cmd.add("--disable-slash-commands");
        cmd.add("--mcp-config");
        cmd.add(rq.mcpConfig().toString());
        cmd.add("--permission-mode");
        cmd.add("acceptEdits");
        if (rq.model() != null && !rq.model().isBlank()) {
            cmd.add("--model");
            cmd.add(rq.model().strip());
        }
        if (rq.effort() != null && !rq.effort().isBlank()) {
            cmd.add("--effort");
            cmd.add(rq.effort().strip());
        }
        cmd.add("--max-turns");
        cmd.add(String.valueOf(rq.maxTurns()));
        cmd.add("--allowedTools");
        cmd.addAll(rq.allowedTools());
        // 시스템 프롬프트는 파일로(본문의 큰따옴표가 Windows 인자 파싱을 깨서 뒤쪽이 잘리는 문제 — ClaudeCliAgent 와 같은 이유)
        java.nio.file.Path sysFile = ClaudeCliProbe.writePromptFile(rq.systemPrompt());
        cmd.add("--append-system-prompt-file");
        cmd.add(sysFile.toString());
        try {
            return run(rq, cmd);
        } finally {
            ClaudeCliProbe.deleteQuietly(sysFile);
        }
    }

    private Response run(Request rq, List<String> cmd) {
        long t0 = System.currentTimeMillis();
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(rq.mcpConfig().getParent().toFile());
            if (rq.env() != null) {
                pb.environment().putAll(rq.env());
            }
            p = pb.start();
        } catch (IOException e) {
            return fail("실행 실패: " + e.getMessage(), 0);
        }
        CompletableFuture<String> out = read(p.getInputStream());
        CompletableFuture<String> err = read(p.getErrorStream());
        try (OutputStream os = p.getOutputStream()) {
            os.write(rq.prompt().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            p.destroyForcibly();
            return fail("실행 실패(stdin): " + e.getMessage(), 0);
        }
        try {
            if (!p.waitFor(rq.timeoutMs(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new Response("", 0, 0, rq.timeoutMs(), true, true, 0, 0);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return fail("대기 인터럽트", 0);
        }
        long elapsed = System.currentTimeMillis() - t0;
        String stdout = out.join();
        try {
            JsonNode n = json.readTree(stdout);
            boolean error = n.path("is_error").asBoolean(false) || !"success".equals(n.path("subtype").asText("success"));
            String text = n.path("result").asText("").strip();
            if (text.isBlank()) {
                text = error ? "실패: " + n.path("subtype").asText("") : "(응답 없음)";
            }
            JsonNode u = n.path("usage");
            long in = u.path("input_tokens").asLong(0) + u.path("cache_read_input_tokens").asLong(0) + u.path("cache_creation_input_tokens").asLong(0);
            usage.record("ops", null, rq.model() == null || rq.model().isBlank() ? "default" : rq.model(), "chat",
                    u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0),
                    u.path("cache_read_input_tokens").asLong(0), u.path("cache_creation_input_tokens").asLong(0),
                    UsageService.isLimitText(text));
            return new Response(text, n.path("num_turns").asInt(0), n.path("total_cost_usd").asDouble(0), elapsed, error, false,
                    in, u.path("output_tokens").asLong(0));
        } catch (IOException e) {
            String se = safeJoin(err);
            log.warn("도구 질의 출력 파싱 실패: {} / stderr: {}", abbreviate(stdout, 200), abbreviate(se, 300));
            return fail("결과를 해석하지 못했습니다: " + abbreviate(se.isBlank() ? stdout : se, 300), elapsed);
        }
    }

    private static Response fail(String text, long elapsed) {
        return new Response(text, 0, 0, elapsed, true, false, 0, 0);
    }

    private static CompletableFuture<String> read(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String safeJoin(CompletableFuture<String> f) {
        try {
            return f.get(3, TimeUnit.SECONDS).strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static String abbreviate(String s, int n) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
