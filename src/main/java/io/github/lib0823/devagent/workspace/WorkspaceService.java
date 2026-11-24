package io.github.lib0823.devagent.workspace;

import jakarta.annotation.PostConstruct;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.config.WorkspaceProperties;
import io.github.lib0823.devagent.gitlab.IssueEvent;
import io.github.lib0823.devagent.gitlab.IssueEventBus;
import io.github.lib0823.devagent.llm.UsageService;
import io.github.lib0823.devagent.mcp.McpConfigService;
import io.github.lib0823.devagent.notify.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 작업 공간: 사용자가 등록한 폴더를 작업 디렉터리로 한 Claude 세션과 대화하며 일한다 — 파일 수정, 명령·실험 실행, 질문.
 * 이슈(GitLab)를 만들지 않고 바로 소통하며 처리하는 길이고, 대상은 코드 저장소가 아니어도 된다(논문·문서·실험 폴더).
 * <ul>
 *   <li>저장소: worktree 를 만들지 않고 등록한 폴더에서 직접 일한다. 그래서 같은 작업 공간은 한 번에 한 요청, 서로 다른 작업 공간은
 *       {@code workspace.concurrency} 만큼 동시에.</li>
 *   <li>세션: 대화(스레드)마다 Claude 세션 하나, 이어지는 요청은 {@code --resume}. 재시작 뒤에도 같은 세션으로 이어진다.</li>
 *   <li>권한: 작업 공간마다 read / edit / full({@link #allowTools}·{@link #denyTools}). 헤드리스 실행이라 건별 승인 창이 없으므로
 *       등록할 때 고른 범위가 곧 권한이다.</li>
 *   <li>이벤트: 이슈 이벤트 버스를 쓰되 세션 id 공간을 {@link #EVENT_BASE} 만큼 띄운다(질의 10억, 플랫폼 관리 20억, 여기는 30억).</li>
 *   <li>플랫폼 관리({@code maintain})와 엔진 구조가 같다 — 그쪽은 "이 플랫폼의 소스 저장소" 하나에 푸시·재시작 버튼이 붙은 특수한 경우다.</li>
 * </ul>
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    /** issue_event.session_id 공간 분리: 차례 id + 30억. */
    public static final long EVENT_BASE = 3_000_000_000L;
    public static final String SOURCE = "workspace";
    public static final List<String> MODELS = List.of("fable", "opus", "sonnet", "haiku");
    public static final List<String> MODES = List.of(Workspace.READ, Workspace.EDIT, Workspace.FULL);
    private static final List<String> READ_TOOLS = List.of("Read", "Grep", "Glob", "LS", "WebSearch", "WebFetch");
    private static final List<String> EDIT_TOOLS = List.of("Edit", "Write", "MultiEdit", "NotebookEdit");
    private static final int MAX_QUESTION = 8000;
    private static final int MAX_WORKSPACES = 50;

    private final WorkspaceProperties props;
    private final WorkspaceMapper mapper;
    private final CodingAgent agent;
    private final IssueEventBus events;
    private final SettingService settings;
    private final UsageService usage;
    private final NotificationService notifications;
    private final McpConfigService mcp;

    private final ExecutorService worker;
    /** 지금 실행 중인 차례 id(작업 스레드가 잡은 것). */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Set<Long> abortRequested = ConcurrentHashMap.newKeySet();

    public WorkspaceService(WorkspaceProperties props, WorkspaceMapper mapper, CodingAgent agent, IssueEventBus events,
                            SettingService settings, UsageService usage, NotificationService notifications, McpConfigService mcp) {
        this.props = props;
        this.mapper = mapper;
        this.agent = agent;
        this.events = events;
        this.settings = settings;
        this.usage = usage;
        this.notifications = notifications;
        this.mcp = mcp;
        AtomicInteger seq = new AtomicInteger();
        this.worker = Executors.newFixedThreadPool(props.concurrencyOr(), r -> {
            Thread t = new Thread(r, "workspace-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    void init() {
        if (!enabled()) {
            log.info("작업 공간 비활성(WORKSPACE_ENABLED=false)");
            return;
        }
        mapper.ensureTable();
        mapper.ensureTurnTable();
        mapper.ensureIndex();
        events.ensureTable();                         // GitLab 이 꺼져 있어도 이벤트 테이블은 필요하다(멱등)
        // 재기동 시 진행 중이던 요청: 중간 상태를 알 수 없으므로 실패로 닫는다. 폴더의 변경과 세션은 남아 있어 이어서 요청할 수 있다
        for (WorkspaceTurn t : mapper.findActive()) {
            boolean started = WorkspaceTurn.RUNNING.equals(t.getState());
            t.setState(WorkspaceTurn.FAILED);
            t.setLastError(started ? "플랫폼 재시작으로 중단되었습니다. 그때까지 고친 내용은 폴더에 남아 있으니 「이어서 진행해 줘」라고 보내면 같은 세션으로 계속합니다."
                    : "플랫폼 재시작으로 시작하지 못했습니다. 다시 보내 주세요.");
            t.setFinishedAt(OffsetDateTime.now());
            mapper.update(t);
            events.emit(eventId(t.getId()), IssueEvent.ERROR, "플랫폼 재시작으로 중단됨");
        }
        log.info("작업 공간 활성: 등록 {}개, 동시 {}건", mapper.workspaces().size(), props.concurrencyOr());
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** 실행 중인 요청 수(재시작 가능 여부 판단용). */
    public int busyCount() {
        return running.size();
    }

    public static long eventId(long turnId) {
        return EVENT_BASE + turnId;
    }

    // ---------------------------------------------------------------- 권한(도구)

    /** 권한 범위에 따른 허용 도구. full 의 Bash 는 접두 제한 없이 전부(사용자가 자기 폴더에 준 권한). */
    static List<String> allowTools(String mode) {
        List<String> t = new ArrayList<>(READ_TOOLS);
        if (!Workspace.READ.equals(mode)) {
            t.addAll(EDIT_TOOLS);
        }
        if (Workspace.FULL.equals(mode)) {
            t.add("Bash");
        }
        return t;
    }

    /** 권한 범위에 따른 금지 도구. 편집 자동 승인 모드에서는 허용 목록에 없어도 편집이 되므로 읽기 전용은 명시적으로 막아야 한다. */
    static List<String> denyTools(String mode) {
        List<String> t = new ArrayList<>();
        if (Workspace.READ.equals(mode)) {
            t.addAll(EDIT_TOOLS);
        }
        if (!Workspace.FULL.equals(mode)) {
            t.add("Bash");
        }
        return t;
    }

    // ---------------------------------------------------------------- 작업 공간 등록

    /**
     * 등록할 폴더 경로 확인: 절대 경로의 실제 디렉터리여야 하고, 드라이브 루트·사용자 홈 자체는 받지 않는다(범위가 너무 넓다).
     * 돌려주는 값은 심볼릭 링크·대소문자를 푼 실제 경로. 문제가 있으면 IllegalArgumentException(메시지를 그대로 사용자에게).
     */
    static Path checkPath(String raw, Path home) {
        String s = raw == null ? "" : raw.strip();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).strip();          // 탐색기 「경로로 복사」는 따옴표를 붙인다
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("폴더 경로를 입력하세요.");
        }
        Path p;
        try {
            p = Path.of(s);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("경로를 읽을 수 없습니다: " + s);
        }
        if (!p.isAbsolute()) {
            throw new IllegalArgumentException("전체 경로(절대 경로)로 입력하세요: " + s);
        }
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("폴더가 없습니다: " + p);
        }
        Path real;
        try {
            real = p.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("폴더를 열 수 없습니다: " + p);
        }
        if (real.getParent() == null) {
            throw new IllegalArgumentException("드라이브 전체(루트)는 등록할 수 없습니다. 작업할 폴더를 지정하세요.");
        }
        if (home != null) {
            try {
                if (Files.isDirectory(home) && real.equals(home.toRealPath())) {
                    throw new IllegalArgumentException("사용자 홈 폴더 전체는 등록할 수 없습니다. 그 안의 작업 폴더를 지정하세요.");
                }
            } catch (IOException ignore) {
                // 홈을 확인할 수 없으면 이 검사는 건너뛴다
            }
        }
        return real;
    }

    /** 등록(id 없음) 또는 수정. 실행 중인 작업 공간은 경로·권한을 바꿀 수 없다. */
    public synchronized Workspace save(Long id, String name, String path, String mode, boolean opsMcp) {
        requireEnabled();
        Path real = checkPath(path, Path.of(System.getProperty("user.home", "")));
        String m = mode == null || mode.isBlank() ? Workspace.FULL : mode.strip().toLowerCase(Locale.ROOT);
        if (!MODES.contains(m)) {
            throw new IllegalArgumentException("권한은 " + MODES + " 중 하나여야 합니다: " + mode);
        }
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            n = real.getFileName().toString();
        }
        if (n.length() > 80) {
            throw new IllegalArgumentException("이름이 너무 깁니다(80자 이내).");
        }
        List<Workspace> all = mapper.workspaces();
        for (Workspace o : all) {
            if ((id == null || o.getId() != id) && samePath(o.getPath(), real)) {
                throw new IllegalArgumentException("이미 등록된 폴더입니다: " + o.getName());
            }
        }
        Workspace w;
        if (id == null) {
            if (all.size() >= MAX_WORKSPACES) {
                throw new IllegalArgumentException("작업 공간은 " + MAX_WORKSPACES + "개까지 등록할 수 있습니다.");
            }
            w = new Workspace();
        } else {
            w = mapper.findWorkspace(id);
            if (w == null) {
                throw new IllegalArgumentException("작업 공간을 찾을 수 없습니다: #" + id);
            }
            if (!mapper.findActiveIn(id).isEmpty() && (!samePath(w.getPath(), real) || !m.equals(w.getMode()) || opsMcp != w.isOpsMcp())) {
                throw new IllegalArgumentException("요청을 처리하는 중에는 폴더·권한을 바꿀 수 없습니다. 끝난 뒤 바꾸세요.");
            }
        }
        w.setName(n);
        w.setPath(real.toString());
        w.setMode(m);
        w.setOpsMcp(opsMcp);
        if (id == null) {
            mapper.insertWorkspace(w);
            log.info("작업 공간 등록 #{} {} ({}, {})", w.getId(), w.getName(), w.getPath(), w.getMode());
        } else {
            mapper.updateWorkspace(w);
        }
        return w;
    }

    private static boolean samePath(String stored, Path real) {
        try {
            return Path.of(stored).equals(real) || stored.equalsIgnoreCase(real.toString()) && isWindows();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 등록 해제: 대화 기록만 지우고 폴더는 건드리지 않는다. */
    public synchronized String delete(long id) {
        requireEnabled();
        Workspace w = mapper.findWorkspace(id);
        if (w == null) {
            throw new IllegalArgumentException("작업 공간을 찾을 수 없습니다: #" + id);
        }
        if (!mapper.findActiveIn(id).isEmpty()) {
            throw new IllegalArgumentException("요청을 처리하는 중입니다. 끝난 뒤(또는 중단한 뒤) 지우세요.");
        }
        mapper.deleteTurns(id);
        mapper.deleteWorkspace(id);
        log.info("작업 공간 등록 해제 #{} {}", id, w.getName());
        return "「" + w.getName() + "」 등록을 해제했습니다. 폴더와 파일은 그대로 있습니다.";
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new IllegalArgumentException("작업 공간이 꺼져 있습니다(WORKSPACE_ENABLED=false).");
        }
    }

    // ---------------------------------------------------------------- 접수

    /**
     * 요청 접수. {@code threadId} 가 있으면 그 대화에 이어 붙인다(같은 Claude 세션). 폴더를 직접 고치므로 작업 공간마다 한 번에 한 요청.
     * 검증 실패는 IllegalArgumentException(메시지를 그대로 사용자에게).
     */
    public synchronized WorkspaceTurn submit(long workspaceId, String question, Long threadId, String model) {
        requireEnabled();
        Workspace w = mapper.findWorkspace(workspaceId);
        if (w == null) {
            throw new IllegalArgumentException("작업 공간을 찾을 수 없습니다: #" + workspaceId);
        }
        if (!Files.isDirectory(Path.of(w.getPath()))) {
            throw new IllegalArgumentException("폴더가 없습니다: " + w.getPath() + " — 옮겼다면 작업 공간 설정에서 경로를 고치세요.");
        }
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("요청을 입력하세요.");
        }
        if (q.length() > MAX_QUESTION) {
            throw new IllegalArgumentException("요청이 너무 깁니다(" + MAX_QUESTION + "자 이내).");
        }
        if (!mapper.findActiveIn(workspaceId).isEmpty()) {
            throw new IllegalArgumentException("이 작업 공간에서 앞선 요청을 처리하는 중입니다. 끝난 뒤(또는 중단한 뒤) 보내 주세요.");
        }
        if (usage.isLimited()) {
            throw new IllegalArgumentException("Claude 사용량 한도에 걸려 있습니다. 리셋(" + usage.limitUntilText() + ") 뒤에 보내 주세요.");
        }
        long thread = 0;
        if (threadId != null) {
            WorkspaceTurn first = mapper.find(threadId);
            if (first == null || first.getWorkspaceId() != workspaceId) {
                throw new IllegalArgumentException("이어갈 대화를 찾을 수 없습니다: #" + threadId);
            }
            thread = first.getThreadId();
        }
        WorkspaceTurn t = new WorkspaceTurn();
        t.setWorkspaceId(workspaceId);
        t.setQuestion(q);
        t.setState(WorkspaceTurn.NEW);
        t.setModel(normalizeModel(model));
        t.setThreadId(thread);
        mapper.insert(t);
        if (thread == 0) {
            mapper.fixThread(t.getId());
            t.setThreadId(t.getId());
        }
        events.emit(eventId(t.getId()), IssueEvent.STATE, "작업 공간 요청 접수 → NEW", WorkspaceTurn.NEW);
        log.info("작업 공간 요청 #{} ws={} thread={} model={}", t.getId(), workspaceId, t.getThreadId(), t.getModel() == null ? "(기본)" : t.getModel());
        final long id = t.getId();
        worker.submit(() -> runTurn(id));
        return t;
    }

    /** 빈 값이면 null(= 에이전트 설정의 '개발' 모델). */
    String normalizeModel(String model) {
        String m = model == null ? "" : model.strip().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) {
            return props.model() == null || props.model().isBlank() ? null : props.model().strip();
        }
        if (!MODELS.contains(m)) {
            throw new IllegalArgumentException("모델은 " + MODELS + " 중 하나여야 합니다: " + model);
        }
        return m;
    }

    // ---------------------------------------------------------------- 실행

    private void runTurn(long id) {
        WorkspaceTurn t = mapper.find(id);
        if (t == null || !WorkspaceTurn.NEW.equals(t.getState())) {
            return;
        }
        Workspace w = mapper.findWorkspace(t.getWorkspaceId());
        running.add(id);
        try {
            if (w == null) {
                finish(t, null, WorkspaceTurn.FAILED, null, "작업 공간이 등록 해제되었습니다.");
                return;
            }
            run(t, w);
        } catch (RuntimeException e) {
            log.warn("작업 공간 요청 #{} 처리 오류: {}", id, e.toString());
            finish(t, w, WorkspaceTurn.FAILED, null, abbreviate(e.getMessage(), 500));
        } finally {
            running.remove(id);
            abortRequested.remove(id);
        }
    }

    private void run(WorkspaceTurn t, Workspace w) {
        final long eid = eventId(t.getId());
        if (abortRequested.contains(t.getId())) {
            finish(t, w, WorkspaceTurn.ABORTED, null, "사용자 중단(시작 전)");
            return;
        }
        Path dir = Path.of(w.getPath());
        if (!Files.isDirectory(dir)) {
            finish(t, w, WorkspaceTurn.FAILED, null, "폴더가 없습니다: " + w.getPath());
            return;
        }
        String prev = mapper.lastSession(t.getThreadId(), t.getId());
        boolean resume = prev != null && !prev.isBlank();
        String sid = resume ? prev : UUID.randomUUID().toString();
        t.setState(WorkspaceTurn.RUNNING);
        t.setStartedAt(OffsetDateTime.now());
        t.setClaudeSessionId(sid);
        mapper.update(t);
        events.emit(eid, IssueEvent.STATE, "NEW → RUNNING", WorkspaceTurn.RUNNING);
        long bashMin = Math.max(10, Math.min(50, (props.timeoutOr() - 60_000L) / 60_000L));
        String sys = WorkspacePrompts.system(w, dir, bashMin);
        List<String> tools = allowTools(w.getMode());
        CodingAgent.Overrides ov = new CodingAgent.Overrides(t.getModel(), props.effort(), props.timeoutOr(), props.maxTurnsOr(), SOURCE,
                w.isOpsMcp(), denyTools(w.getMode()));
        CodingAgent.Listener listener = (kind, message, detail) -> events.emit(eid, kind, message, detail);
        events.emit(eid, IssueEvent.CLAUDE, "▶ 작업 공간 「" + w.getName() + "」 세션 " + (resume ? "이어서 " : "") + "시작 (상한 "
                + props.timeoutOr() / 60_000 + "분/" + props.maxTurnsOr() + "턴)", t.getQuestion());
        try {
            CodingAgent.Result r;
            try {
                r = agent.run(dir, sid, resume, tools, sys, t.getQuestion(), listener, null, CodingAgent.Stage.FIX, false, ov);
            } catch (CodingAgent.UsageLimitException | CodingAgent.AgentTimeoutException e) {
                throw e;
            } catch (RuntimeException e) {
                if (!resume || abortRequested.contains(t.getId()) || !sessionMissing(e.getMessage())) {
                    throw e;
                }
                // 세션 파일이 없어졌다(정리됨·폴더 이동): 맥락 없이 새 세션으로 한 번 더
                sid = UUID.randomUUID().toString();
                t.setClaudeSessionId(sid);
                mapper.update(t);
                events.emit(eid, IssueEvent.INFO, "이전 대화 세션을 찾지 못해 새 세션으로 시작합니다(앞선 대화 맥락 없음)");
                r = agent.run(dir, sid, false, tools, sys, t.getQuestion(), listener, null, CodingAgent.Stage.FIX, false, ov);
            }
            t.setTurns(r.turns());
            t.setInputTokens(r.inputTotal());
            t.setOutputTokens(r.outputTokens());
            t.setElapsedMs(r.elapsedMs());
            events.emit(eid, IssueEvent.CLAUDE, String.format("■ 완료 · %d턴 · %s · %ds%s", r.turns(), r.tokens(), r.elapsedMs() / 1000,
                    r.error() ? " · 오류" : ""), r.text());
            if (abortRequested.contains(t.getId())) {
                finish(t, w, WorkspaceTurn.ABORTED, r.text(), "사용자 중단");
                return;
            }
            String text = r.text() == null ? "" : r.text().strip();
            if (text.length() > props.maxAnswerCharsOr()) {
                text = text.substring(0, props.maxAnswerCharsOr()) + "\n\n…(답변이 길어 " + props.maxAnswerCharsOr() + "자에서 잘랐습니다)";
            }
            if (r.error()) {
                // 턴 상한 등: 작업은 폴더에 남아 있다
                finish(t, w, WorkspaceTurn.FAILED, text.isEmpty() ? null : text, "Claude 가 오류로 끝났습니다(턴 상한일 수 있음). 「이어서 진행해 줘」로 계속할 수 있습니다.");
                return;
            }
            finish(t, w, WorkspaceTurn.DONE, text, null);
        } catch (CodingAgent.UsageLimitException e) {
            finish(t, w, WorkspaceTurn.FAILED, null, "Claude 사용량 한도에 걸렸습니다(리셋 " + usage.limitUntilText()
                    + "). 그때까지 고친 내용은 폴더에 남아 있으니 리셋 뒤 「이어서 진행해 줘」라고 보내세요.");
        } catch (CodingAgent.AgentTimeoutException e) {
            finish(t, w, WorkspaceTurn.FAILED, null, e.getMessage() + " — 그때까지 고친 내용은 폴더에 남아 있습니다. 「이어서 진행해 줘」로 계속할 수 있습니다.");
        } catch (RuntimeException e) {
            if (abortRequested.contains(t.getId())) {
                finish(t, w, WorkspaceTurn.ABORTED, null, "사용자 중단 — 그때까지 고친 내용은 폴더에 남아 있습니다.");
                return;
            }
            log.warn("작업 공간 요청 #{} 실패: {}", t.getId(), e.toString());
            finish(t, w, WorkspaceTurn.FAILED, null, abbreviate(e.getMessage(), 500));
        }
    }

    /** {@code --resume} 대상 세션이 없다는 CLI 오류인가. */
    static boolean sessionMissing(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("no conversation found");
    }

    private void finish(WorkspaceTurn t, Workspace w, String state, String answer, String error) {
        t.setState(state);
        t.setAnswerMd(answer);
        t.setLastError(error);
        t.setFinishedAt(OffsetDateTime.now());
        mapper.update(t);
        long eid = eventId(t.getId());
        if (error != null) {
            events.emit(eid, IssueEvent.ERROR, error);
        }
        events.emit(eid, IssueEvent.STATE, "→ " + state, state);
        boolean done = WorkspaceTurn.DONE.equals(state);
        boolean aborted = WorkspaceTurn.ABORTED.equals(state);
        // 메신저와 무관하게 알림함에 남긴다(다른 탭을 보며 기다리는 경우). 링크는 그 대화로
        notifications.add(SOURCE, done ? "ok" : aborted ? "warn" : "err",
                (w == null ? "작업 공간" : w.getName()) + " " + (done ? "완료" : aborted ? "중단" : "실패") + ": " + abbreviate(t.getQuestion(), 80),
                error, "/workspace.html#w=" + t.getWorkspaceId() + "&t=" + t.getThreadId());
    }

    public String abort(long id) {
        WorkspaceTurn t = mapper.find(id);
        if (t == null) {
            return "요청을 찾을 수 없습니다: #" + id;
        }
        if (!t.isActive()) {
            return "이미 끝난 요청입니다 (" + t.getState() + ").";
        }
        abortRequested.add(id);
        boolean killed = agent.kill(t.getClaudeSessionId());
        if (killed) {
            events.emit(eventId(id), IssueEvent.INFO, "중단 요청 — 실행 중인 Claude 프로세스 종료");
        }
        return "중단을 요청했습니다." + (killed ? " 그때까지 고친 내용은 폴더에 남습니다." : "");
    }

    // ---------------------------------------------------------------- 조회

    /** 화면의 기본 정보: 작업 공간 목록(실행 중 여부·폴더 존재)과 모델 선택지. 가볍다(프로세스 호출 없음). */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        if (!enabled()) {
            out.put("reason", "꺼져 있습니다(WORKSPACE_ENABLED=false).");
            return out;
        }
        Map<Long, WorkspaceTurn> active = new LinkedHashMap<>();
        for (WorkspaceTurn t : mapper.findActive()) {
            active.put(t.getWorkspaceId(), t);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Workspace w : mapper.workspaces()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("name", w.getName());
            m.put("path", w.getPath());
            m.put("mode", w.getMode());
            m.put("opsMcp", w.isOpsMcp());
            m.put("exists", Files.isDirectory(Path.of(w.getPath())));
            WorkspaceTurn a = active.get(w.getId());
            m.put("runningThread", a == null ? null : a.getThreadId());
            list.add(m);
        }
        out.put("workspaces", list);
        out.put("models", MODELS);
        SettingService.AgentModels am = settings.getAgentModels();
        out.put("defaultModel", props.model() == null || props.model().isBlank() ? am.devModel() : props.model().strip());
        out.put("mcpAvailable", mcp.agentEnabled());
        out.put("limited", usage.isLimited());
        out.put("limitUntil", usage.isLimited() ? usage.limitUntilText() : null);
        return out;
    }

    /** 작업 공간의 대화 목록. */
    public List<WorkspaceMapper.ThreadRow> threads(long workspaceId, int limit) {
        return enabled() ? mapper.threads(workspaceId, Math.max(1, Math.min(limit, 200))) : List.of();
    }

    /** 대화 상세: 차례 전부(답변 포함) + 마지막 차례의 활동 로그(afterEventId 이후, 본문 제외). */
    public Map<String, Object> thread(long threadId, long afterEventId) {
        if (!enabled()) {
            return null;
        }
        List<WorkspaceTurn> items = mapper.findThread(threadId);
        if (items.isEmpty()) {
            return null;
        }
        WorkspaceTurn last = items.get(items.size() - 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", threadId);
        out.put("workspaceId", last.getWorkspaceId());
        out.put("turns", items);
        out.put("liveId", last.getId());
        out.put("events", events.events(eventId(last.getId()), afterEventId, 500));
        CodingAgent.RunInfo ri = last.isActive() ? agent.runInfo(last.getClaudeSessionId()) : null;
        if (ri != null) {
            out.put("running", Map.of("model", ri.model(), "effort", ri.effort(), "startedAt", ri.startedAt()));
        }
        return out;
    }

    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
