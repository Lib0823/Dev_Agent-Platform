package io.github.lib0823.devagent.llm.claude;

import io.github.lib0823.devagent.llm.UsageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import io.github.lib0823.devagent.config.ClaudeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 상주(warm) Claude Code CLI 프로세스 매니저.
 * <p>
 * {@code claude -p --input-format stream-json --output-format stream-json --verbose ...} 프로세스를
 * 1개 장수명으로 띄워두고, 요청마다 stdin 으로 user 메시지(JSON 한 줄)를 전송하고 stdout 의 {@code result}
 * 이벤트를 읽는다. Node 부팅/인증/세션초기화(콜드스타트 ~8s)를 프로세스당 1회만 내므로 2번째 요청부터는
 * 추론시간(~1s)만 든다.
 * <p>
 * 단일 세션이라 대화 맥락이 누적된다 → {@code warmResetTurns} 턴마다 프로세스를 재시작해 리셋한다.
 * 요청은 {@link ReentrantLock}으로 직렬화한다(한 번에 한 턴).
 * 한글 시스템프롬프트 인자가 cmd.exe 코드페이지에서 깨지지 않도록 {@code claude.exe}를 직접 실행한다.
 */
@Component
@ConditionalOnProperty(prefix = "platform", name = "chat-model", havingValue = "claude-cli", matchIfMissing = true)
public class ClaudeWarmSession {

    private static final Logger log = LoggerFactory.getLogger(ClaudeWarmSession.class);

    private final ClaudeProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();
    /** 프리웜 작업을 핫패스 밖에서 돌리는 단일 스레드. */
    private final ExecutorService primeExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "claude-warm-prime");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean primeQueued = new AtomicBoolean(false);

    private Process process;
    private BufferedWriter stdin;
    private int turnCount;
    /** 현재 진행 중인 턴의 결과를 reader 스레드 → complete() 로 넘기는 1칸 큐. */
    private volatile BlockingQueue<Outcome> pending;

    public ClaudeWarmSession(ClaudeProperties props) {
        this.props = props;
    }

    /** 토큰 사용량 기록(선택 의존 — 테스트/부트스트랩에서 없을 수 있다). */
    private volatile UsageService usage;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUsage(UsageService usage) {
        this.usage = usage;
    }

    private void recordUsage(JsonNode ev) {
        UsageService u = usage;
        if (u == null) {
            return;
        }
        JsonNode us = ev.path("usage");
        String text = ev.path("result").asText("");
        u.record("chat", null, props.model() == null || props.model().isBlank() ? "default" : props.model(), "warm",
                us.path("input_tokens").asLong(0), us.path("output_tokens").asLong(0),
                us.path("cache_read_input_tokens").asLong(0), us.path("cache_creation_input_tokens").asLong(0),
                UsageService.isLimitText(text));
    }

    /** 기동 직후 백그라운드로 선(先)데움 → 첫 요청의 콜드스타트(~8s) 제거. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (props.warm() && props.warmPrewarm()) {
            submitPrime();
        }
    }

    /** 결과(성공 텍스트) 또는 오류를 담는 전달 객체. */
    private record Outcome(String text, String error) {
        static Outcome ok(String t) { return new Outcome(t, null); }
        static Outcome err(String e) { return new Outcome(null, e); }
        boolean isError() { return error != null; }
    }

    /** 프롬프트를 상주 프로세스에 보내고 응답 텍스트를 반환한다. */
    public String complete(String prompt) {
        lock.lock();
        try {
            ensureAlive();
            BlockingQueue<Outcome> q = new ArrayBlockingQueue<>(1);
            this.pending = q;
            try {
                writeUserMessage(prompt);
            } catch (IOException e) {
                restart();
                throw new IllegalStateException("warm 프로세스 stdin 쓰기 실패: " + e.getMessage(), e);
            }

            Outcome outcome;
            try {
                outcome = q.poll(props.timeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                restart();
                throw new IllegalStateException("warm 응답 대기 중 인터럽트", e);
            } finally {
                this.pending = null;
            }

            if (outcome == null) {
                restart();
                throw new IllegalStateException("warm 응답 타임아웃 (" + props.timeoutMs() + "ms)");
            }
            if (outcome.isError()) {
                restart();
                throw new IllegalStateException("warm 응답 오류: " + outcome.error());
            }
            turnCount++;
            // 선제 프리웜: 리셋 임계에 도달하면 다음 요청이 오기 전에 백그라운드로 재시작+데움
            if (props.warmPrewarm() && props.warmResetTurns() > 0 && turnCount >= props.warmResetTurns()) {
                submitPrime();
            }
            return outcome.text() == null ? "" : outcome.text().strip();
        } finally {
            lock.unlock();
        }
    }

    /** 프리웜 작업을 중복 없이 1건 예약한다. */
    private void submitPrime() {
        if (primeQueued.compareAndSet(false, true)) {
            try {
                primeExec.submit(this::primeQuietly);
            } catch (RuntimeException e) {
                primeQueued.set(false); // executor 종료 등
            }
        }
    }

    /** 프로세스를 재시작하고 더미 1왕복으로 콜드스타트를 미리 치른다(결과는 버림). 예외는 삼킨다. */
    private void primeQuietly() {
        lock.lock();
        try {
            restart(); // turnCount=0 으로 리셋
            BlockingQueue<Outcome> q = new ArrayBlockingQueue<>(1);
            this.pending = q;
            try {
                writeUserMessage("ping");
            } catch (IOException e) {
                return;
            }
            Outcome o = q.poll(props.timeoutMs(), TimeUnit.MILLISECONDS);
            this.pending = null;
            if (o != null && !o.isError()) {
                turnCount = 0; // 프리웜 왕복은 사용자 턴으로 치지 않음
                log.info("warm 프리웜 완료 — 다음 요청은 콜드스타트 없이 처리");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException re) {
            log.debug("warm 프리웜 실패(무시): {}", re.toString());
        } finally {
            this.pending = null;
            primeQueued.set(false);
            lock.unlock();
        }
    }

    /** warm 프로세스가 지금 떠 있는지(상태 표시용, 잠금 없이 읽는다). */
    public boolean alive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** 프로세스가 죽었거나 리셋 임계턴을 넘겼으면 (재)기동한다. 호출은 lock 보유 상태. */
    private void ensureAlive() {
        boolean needStart = process == null
                || !process.isAlive()
                || (props.warmResetTurns() > 0 && turnCount >= props.warmResetTurns());
        if (needStart) {
            restart();
        }
    }

    /** 기존 프로세스를 정리하고 새 상주 프로세스를 띄운다. 호출은 lock 보유 상태. */
    private void restart() {
        stopQuietly();
        List<String> command = buildCommand();
        log.info("warm Claude 프로세스 시작: {} (reset={}턴)", props.warmCommand(), props.warmResetTurns());
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new IllegalStateException("warm Claude 프로세스 실행 실패 (" + props.warmCommand() + "): " + e.getMessage(), e);
        }
        this.process = p;
        this.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        this.turnCount = 0;

        Thread reader = new Thread(() -> readLoop(p, p.getInputStream()), "claude-warm-reader");
        reader.setDaemon(true);
        reader.start();
        Thread errReader = new Thread(() -> drainStderr(p.getErrorStream()), "claude-warm-stderr");
        errReader.setDaemon(true);
        errReader.start();
    }

    private void stopQuietly() {
        if (stdin != null) {
            try { stdin.close(); } catch (IOException ignore) { }
            stdin = null;
        }
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.warmCommand());
        cmd.add("-p");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.addAll(props.commonFlags());
        return cmd;
    }

    /** user 메시지를 stream-json 한 줄로 stdin 에 쓴다. */
    private void writeUserMessage(String prompt) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "user");
        ObjectNode msg = root.putObject("message");
        msg.put("role", "user");
        msg.put("content", prompt);
        stdin.write(mapper.writeValueAsString(root));
        stdin.write("\n");
        stdin.flush();
    }

    /** stdout 의 stream-json 라인을 읽어 result 이벤트를 현재 턴의 큐로 넘긴다. */
    private void readLoop(Process p, InputStream in) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                JsonNode ev;
                try {
                    ev = mapper.readTree(line);
                } catch (IOException parse) {
                    continue; // 부분/비JSON 라인 무시
                }
                if (!"result".equals(ev.path("type").asText())) {
                    continue; // init/assistant 스트리밍 이벤트는 무시, 최종 result 만 사용
                }
                String subtype = ev.path("subtype").asText("");
                Outcome outcome = "success".equals(subtype)
                        ? Outcome.ok(ev.path("result").asText(""))
                        : Outcome.err("subtype=" + subtype + " " + ev.path("result").asText(""));
                try {
                    recordUsage(ev);
                } catch (RuntimeException e) {
                    log.debug("warm 사용량 기록 실패(무시): {}", e.toString());
                }
                handoff(outcome);
            }
        } catch (IOException e) {
            log.debug("warm reader 종료: {}", e.toString());
        }
        // 스트림 EOF = 프로세스 종료. 대기 중인 턴이 있으면 오류로 깨운다.
        handoff(Outcome.err("warm 프로세스가 응답 전에 종료됨"));
    }

    private void handoff(Outcome outcome) {
        BlockingQueue<Outcome> q = this.pending;
        if (q != null) {
            q.offer(outcome); // 1칸 큐: 첫 결과만 수용, 이후 중복 result 는 버림
        }
    }

    private void drainStderr(InputStream err) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) {
                    log.debug("warm stderr: {}", line.strip());
                }
            }
        } catch (IOException ignore) {
            // 종료 시 스트림 닫힘
        }
    }

    @PreDestroy
    public void shutdown() {
        primeExec.shutdownNow();
        lock.lock();
        try {
            stopQuietly();
        } finally {
            lock.unlock();
        }
    }
}
