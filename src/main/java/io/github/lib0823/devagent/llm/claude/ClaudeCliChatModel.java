package io.github.lib0823.devagent.llm.claude;

import io.github.lib0823.devagent.llm.ChatModel;
import io.github.lib0823.devagent.platform.CallStats;
import io.github.lib0823.devagent.platform.PortHealth;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import io.github.lib0823.devagent.config.ClaudeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Claude Code CLI(headless)를 통한 LLM 호출.
 * Enterprise 구독 인증을 그대로 사용하므로 별도 API 키가 필요 없다.
 * <p>
 * {@code claude.warm=true}면 상주(warm) stream-json 프로세스({@link ClaudeWarmSession})를 재사용해
 * 콜드스타트를 제거한다(2번째 요청부터 ~1s). warm 호출이 실패하면 one-shot {@code claude -p} 로 폴백한다.
 */
@Component
@ConditionalOnProperty(prefix = "platform", name = "chat-model", havingValue = "claude-cli", matchIfMissing = true)
public class ClaudeCliChatModel implements ChatModel {

    @Override
    public String provider() {
        return "claude-cli";
    }

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliChatModel.class);

    private final ClaudeProperties props;
    private final ClaudeWarmSession warmSession;

    // 서킷브레이커: warm 연속 실패가 임계치를 넘으면 쿨다운 동안 one-shot 전용으로 돌리고,
    // 쿨다운 경과 후 1회 반(半)개방 재시도로 warm 복구를 시험한다.
    private final AtomicInteger warmFailures = new AtomicInteger(0);
    private volatile long breakerOpenedAtMs = 0L;

    public ClaudeCliChatModel(ClaudeProperties props, ClaudeWarmSession warmSession) {
        this.props = props;
        this.warmSession = warmSession;
    }

    /** 프롬프트를 보내고 모델의 텍스트 응답을 반환한다. warm 우선, 실패 시 one-shot 폴백(서킷브레이커 적용). */
    private final CallStats stats = new CallStats();

    @Override
    public PortHealth health() {
        boolean breakerOpen = props.warm() && !warmAllowed();
        boolean warmAlive = warmSession.alive();
        String summary = !props.warm() ? "one-shot 모드(warm 꺼짐)"
                : breakerOpen ? "warm 서킷 열림 — one-shot 으로 동작 중"
                : warmAlive ? "warm 세션 상주 중" : "warm 세션 대기(다음 요청 때 기동)";
        return stats.health(provider(), breakerOpen ? PortHealth.WARN : PortHealth.OK, summary)
                .with("model", props.model() == null || props.model().isBlank() ? "기본" : props.model())
                .with("effort", props.effort())
                .with("warm", props.warm())
                .with("warmAlive", warmAlive)
                .with("warmFailures", warmFailures.get());
    }

    @Override
    public String complete(String prompt) {
        return stats.track(() -> doComplete(prompt));
    }

    private String doComplete(String prompt) {
        if (props.warm() && warmAllowed()) {
            try {
                String r = warmSession.complete(prompt);
                onWarmSuccess();
                return r;
            } catch (RuntimeException e) {
                onWarmFailure();
                log.warn("warm Claude 호출 실패 → one-shot 폴백 (연속 {}회): {}", warmFailures.get(), e.toString());
            }
        }
        return completeOneShot(prompt);
    }

    /** warm 시도 허용 여부. 서킷 닫힘이면 허용, 열렸어도 쿨다운 지났으면 반개방 1회 허용. */
    private boolean warmAllowed() {
        int threshold = props.warmFailureThreshold();
        if (threshold <= 0 || warmFailures.get() < threshold) {
            return true;
        }
        long cooldown = props.warmBreakerCooldownMs();
        return cooldown <= 0 || (System.currentTimeMillis() - breakerOpenedAtMs) >= cooldown;
    }

    private void onWarmSuccess() {
        if (warmFailures.getAndSet(0) != 0) {
            log.info("warm Claude 복구됨 — 서킷 닫힘");
        }
    }

    private void onWarmFailure() {
        int n = warmFailures.incrementAndGet();
        int threshold = props.warmFailureThreshold();
        if (threshold > 0 && n == threshold) {
            breakerOpenedAtMs = System.currentTimeMillis();
            log.warn("warm Claude 연속 {}회 실패 — 서킷 오픈({}ms 동안 one-shot 전용)", n, props.warmBreakerCooldownMs());
        } else if (threshold > 0 && n > threshold) {
            breakerOpenedAtMs = System.currentTimeMillis(); // 반개방 재시도도 실패 → 쿨다운 연장
        }
    }

    /** one-shot: 호출마다 {@code claude -p} 새 프로세스. warm 비활성/폴백 경로. */
    private String completeOneShot(String prompt) {
        List<String> command = buildCommand();
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("claude CLI 실행 실패 (" + props.oneShotCommand() + "): " + e.getMessage(), e);
        }

        // stdout/stderr 를 백그라운드로 비동기 수집 (파이프 버퍼 블로킹 방지)
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());

        // 프롬프트를 stdin 으로 전달
        try (OutputStream os = process.getOutputStream()) {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            process.destroyForcibly();
            throw new IllegalStateException("claude CLI stdin 쓰기 실패: " + e.getMessage(), e);
        }

        boolean finished;
        try {
            finished = process.waitFor(props.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("claude CLI 대기 중 인터럽트", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("claude CLI 타임아웃 (" + props.timeoutMs() + "ms)");
        }

        String out = stdout.join();
        int exit = process.exitValue();
        if (exit != 0) {
            throw new IllegalStateException("claude CLI 실패 (exit=" + exit + "): " + stderr.join().strip());
        }
        return out.strip();
    }

    /**
     * CLI 커맨드/플래그 조립. 콜드스타트 단축을 위해 MCP/스킬 로딩을 끄고,
     * 모델/추론강도/시스템프롬프트를 설정값으로 지정한다 (빈 값은 미지정 → CLI 기본값).
     */
    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.oneShotCommand());
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("text");
        if (props.strictMcpConfig()) {
            cmd.add("--strict-mcp-config");
        }
        if (props.disableSlashCommands()) {
            cmd.add("--disable-slash-commands");
        }
        if (props.model() != null && !props.model().isBlank()) {
            cmd.add("--model");
            cmd.add(props.model().strip());
        }
        if (props.effort() != null && !props.effort().isBlank()) {
            cmd.add("--effort");
            cmd.add(props.effort().strip());
        }
        if (props.systemPrompt() != null && !props.systemPrompt().isBlank()) {
            cmd.add("--system-prompt");
            cmd.add(props.systemPrompt().strip());
        }
        return cmd;
    }

    private CompletableFuture<String> readAsync(java.io.InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("claude CLI 출력 읽기 실패: {}", e.toString());
                return "";
            }
        });
    }
}
