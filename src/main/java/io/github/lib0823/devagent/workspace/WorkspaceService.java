package io.github.lib0823.devagent.workspace;

import jakarta.annotation.PostConstruct;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.config.WorkspaceProperties;
import io.github.lib0823.devagent.gitlab.IssueEvent;
import io.github.lib0823.devagent.gitlab.IssueEventBus;
import io.github.lib0823.devagent.llm.UsageService;
import io.github.lib0823.devagent.mcp.McpConfigService;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.notify.NotificationService;
import io.github.lib0823.devagent.skill.AgentSkill;
import io.github.lib0823.devagent.skill.SkillService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
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
    public static final List<String> MODES = List.of(Workspace.BYPASS, Workspace.ASK);
    /** 확인 대기 상한: 에이전트 무응답 상한(45분)보다 짧아야 답이 없을 때 세션이 죽지 않고 "거부"로 이어진다. */
    private static final long ASK_WAIT_MS = 40 * 60_000L;
    /** 「확인 후 실행」에서 묻지 않는 도구: 폴더 안을 읽고 찾는 것만(웹 접근·수정·실행은 확인을 거친다). */
    private static final List<String> READ_TOOLS = List.of("Read", "Grep", "Glob", "LS");
    /** 요청 길이 상한. 리서치 결과를 붙여 넘기는 경우가 있어 넉넉히 둔다(프롬프트는 표준 입력으로 넘어가 명령행 제한과 무관). */
    private static final int MAX_QUESTION = 24_000;
    private static final int MAX_WORKSPACES = 50;

    private final WorkspaceProperties props;
    private final WorkspaceMapper mapper;
    private final CodingAgent agent;
    private final IssueEventBus events;
    private final SettingService settings;
    private final UsageService usage;
    private final NotificationService notifications;
    private final McpConfigService mcp;
    private final SkillService skills;
    private final WorkspaceApprovals approvals;
    private final int adminPort;
    /** 「확인 후 실행」 차례의 확인 도구 설정 파일(차례 id → 임시 파일). 끝나면 지운다. */
    private final Map<Long, Path> askFiles = new ConcurrentHashMap<>();
    /** 확인 필요 알림을 이미 남긴 차례. */
    private final Set<Long> askNotified = ConcurrentHashMap.newKeySet();

    private final ExecutorService worker;
    /** 지금 실행 중인 차례 id(작업 스레드가 잡은 것). */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Set<Long> abortRequested = ConcurrentHashMap.newKeySet();
    /** 실행 직전의 폴더 스냅샷(차례 id → 경로별 수정 시각·크기). 끝날 때 비교해 바뀐 파일을 남긴다. */
    private final Map<Long, Map<String, long[]>> snapshots = new ConcurrentHashMap<>();

    /** 차례별 되돌리기 지점(바뀐 파일의 요청 전·후 내용). */
    private final WorkspaceCheckpoints checkpoints;
    /** 되돌리기 지점을 준비한 차례(사본을 맞추는 데 성공한 것만 끝날 때 떼어 둔다). */
    private final Set<Long> checkpointed = ConcurrentHashMap.newKeySet();
    private final MessengerService messenger;

    public WorkspaceService(WorkspaceProperties props, WorkspaceMapper mapper, CodingAgent agent, IssueEventBus events,
                            SettingService settings, UsageService usage, NotificationService notifications, McpConfigService mcp,
                            SkillService skills, WorkspaceApprovals approvals, MessengerService messenger,
                            @Value("${server.port:8777}") int adminPort, @Value("${gitlab.work-dir:work}") String workDir) {
        this.messenger = messenger;
        this.checkpoints = new WorkspaceCheckpoints(Path.of(workDir == null || workDir.isBlank() ? "work" : workDir.strip())
                .toAbsolutePath().resolve("workspace-ckpt"));
        this.skills = skills;
        this.approvals = approvals;
        this.adminPort = adminPort;
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
        mapper.ensureChangedColumn();
        mapper.ensureSkillsColumn();
        mapper.migrateModes();
        mapper.ensureOriginColumn();
        mapper.ensureRevertedColumn();
        events.ensureTable();                        // GitLab 이 꺼져 있어도 이벤트 테이블은 필요하다(멱등)
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

    // ---------------------------------------------------------------- 권한: 묻지 않고 실행(bypass) / 확인 후 실행(ask)

    /**
     * 「확인 후 실행」에서 묻지 않고 허용하는 도구(읽기·검색). 그 밖의 도구(파일 수정·명령 실행 등)는 CLI 가 확인 도구를 불러 사용자 답을 기다린다.
     * 「묻지 않고 실행」은 허용 목록과 무관하게 전부 실행된다.
     */
    static List<String> askFreeTools() {
        return READ_TOOLS;
    }

    /** 확인 창에 보여 줄 한 줄 요약: 명령이면 명령, 파일 도구면 경로, 그 밖에는 입력 앞부분. */
    static String summarize(String tool, JsonNode input) {
        if (input == null || input.isMissingNode()) {
            return "";
        }
        for (String k : List.of("command", "file_path", "notebook_path", "path", "url", "query", "pattern", "description", "prompt")) {
            if (input.hasNonNull(k)) {
                return abbreviate(input.get(k).asText().replaceAll("\\s+", " "), 300);
            }
        }
        return abbreviate(input.toString(), 300);
    }

    /** 확인 창의 상세: 바꾸려는 내용(이전 → 이후)이나 쓰려는 내용, 그 밖에는 입력 전체. */
    static String detail(String tool, JsonNode input) {
        if (input == null || input.isMissingNode()) {
            return "";
        }
        String d;
        if (input.hasNonNull("old_string") || input.hasNonNull("new_string")) {
            d = "[이전]\n" + input.path("old_string").asText("") + "\n\n[이후]\n" + input.path("new_string").asText("");
        } else if (input.hasNonNull("content")) {
            d = input.get("content").asText();
        } else if (input.hasNonNull("command")) {
            d = input.get("command").asText() + (input.hasNonNull("description") ? "\n\n# " + input.get("description").asText() : "");
        } else {
            d = input.toPrettyString();
        }
        return d.length() <= 6000 ? d : d.substring(0, 6000) + "\n…(이하 생략)";
    }

    /**
     * 확인 도구가 부른다: 사용자에게 묻고 답을 기다린다(최대 {@link #ASK_WAIT_MS} — 에이전트 무응답 상한 45분보다 짧게).
     * 활동 로그에 대기·결과를 남겨 화면이 알 수 있게 한다.
     */
    public WorkspaceApprovals.Decision askUser(String token, String tool, JsonNode input) {
        Long turnId = approvals.turnOf(token);
        String sum = summarize(tool, input);
        // 작업 공간 안의 경로는 상대 경로로 줄여 보여 준다(긴 절대 경로에 파일 이름이 묻힌다)
        WorkspaceTurn turn = turnId == null ? null : mapper.find(turnId);
        Workspace ws = turn == null ? null : mapper.findWorkspace(turn.getWorkspaceId());
        if (ws != null && sum.length() > ws.getPath().length() + 1
                && sum.regionMatches(true, 0, ws.getPath(), 0, ws.getPath().length())
                && "\\/".indexOf(sum.charAt(ws.getPath().length())) >= 0) {
            sum = sum.substring(ws.getPath().length() + 1).replace('\\', '/');
        }
        if (turnId != null) {
            events.emit(eventId(turnId), IssueEvent.INFO, "확인 대기 — " + tool + (sum.isEmpty() ? "" : ": " + abbreviate(sum, 120)));
            // 다른 탭을 보고 있어도 알 수 있게 알림함에 남긴다(요청 하나에 한 번만 — 확인마다 남기면 시끄럽다)
            if (ws != null && askNotified.add(turnId)) {
                notifications.add(SOURCE, "warn", ws.getName() + " 확인 필요: " + tool + (sum.isEmpty() ? "" : " " + abbreviate(sum, 60)),
                        "실행 전 확인을 기다리고 있습니다. 답이 없으면 40분 뒤 거부로 처리됩니다.",
                        "/workspace.html#w=" + ws.getId() + "&t=" + turn.getThreadId());
            }
            // 메신저로 시킨 일이면 확인도 메신저로 묻는다(확인마다 — 답해야 다음으로 넘어간다)
            if (ws != null && ORIGIN_MESSENGER.equals(turn.getOrigin())) {
                try {
                    messenger.notifySelf(MessengerService.NOTICE_WORKSPACE, PFX + ws.getName() + " 실행 전 확인: " + tool
                            + (sum.isEmpty() ? "" : "\n" + abbreviate(sum, 400)) + "\n→ @AI 작업 허용  /  @AI 작업 거부 <이유>  (40분 안에 답이 없으면 거부)");
                } catch (RuntimeException e) {
                    log.warn("확인 요청 메신저 알림 실패: {}", e.toString());
                }
            }
        }
        WorkspaceApprovals.Decision d = approvals.await(token, tool, sum, detail(tool, input), ASK_WAIT_MS);
        if (turnId != null) {
            events.emit(eventId(turnId), IssueEvent.REPLY, (d.allow() ? "허용 — " : "거부 — ") + tool
                    + (d.message() == null ? "" : " (" + abbreviate(d.message(), 120) + ")"));
        }
        return d;
    }

    /** 화면의 허용/거부. */
    public boolean decide(long approvalId, boolean allow, boolean remember, String message) {
        return approvals.decide(approvalId, allow, remember, message);
    }

    // ---------------------------------------------------------------- 폴더 찾아보기(등록할 폴더 고르기)

    /** 찾아보기를 시작할 위치: 설정한 기본 폴더 → 사용자 홈. */
    Path browseStart() {
        if (props.baseDir() != null && !props.baseDir().isBlank()) {
            try {
                Path p = Path.of(props.baseDir().strip());
                if (Files.isDirectory(p)) {
                    return p;
                }
            } catch (InvalidPathException ignore) {
                // 잘못된 설정은 무시하고 홈에서 시작
            }
        }
        return Path.of(System.getProperty("user.home", "."));
    }

    /** 폴더 한 단계(하위 폴더 이름만) + 바로 가기(기본 폴더·홈·바탕 화면·문서·이미 등록한 작업 공간의 상위 폴더). */
    public Map<String, Object> browse(String dir) {
        requireEnabled();
        Map<String, Object> out = WorkspaceFiles.browse(dir, browseStart());
        Map<String, String> sc = new LinkedHashMap<>();
        Path home = Path.of(System.getProperty("user.home", "."));
        Path start = browseStart();
        if (!start.equals(home)) {
            sc.put(start.toString(), "기본 폴더");
        }
        sc.put(home.toString(), "홈");
        for (String[] n : new String[][]{{"Desktop", "바탕 화면"}, {"Documents", "문서"}, {"Downloads", "다운로드"}}) {
            Path p = home.resolve(n[0]);
            if (Files.isDirectory(p)) {
                sc.putIfAbsent(p.toString(), n[1]);
            }
        }
        for (Workspace w : mapper.workspaces()) {
            try {
                Path parent = Path.of(w.getPath()).getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    sc.putIfAbsent(parent.toString(), parent.getFileName() == null ? parent.toString() : parent.getFileName().toString());
                }
            } catch (InvalidPathException ignore) {
                // 건너뜀
            }
        }
        List<Map<String, String>> list = new ArrayList<>();
        sc.forEach((p, n) -> list.add(Map.of("name", n, "path", p)));
        out.put("shortcuts", list.size() > 8 ? list.subList(0, 8) : list);
        // 지금 위치를 그대로 등록할 수 있는가(루트·홈 자체는 안 된다)
        try {
            checkPath((String) out.get("dir"), home);
            out.put("selectable", true);
        } catch (IllegalArgumentException e) {
            out.put("selectable", false);
            out.put("reason", e.getMessage());
        }
        String cur = (String) out.get("dir");
        for (Workspace w : mapper.workspaces()) {
            if (w.getPath().equalsIgnoreCase(cur)) {
                out.put("selectable", false);
                out.put("reason", "이미 등록된 폴더입니다: " + w.getName());
            }
        }
        return out;
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
    public synchronized Workspace save(Long id, String name, String path, String mode, boolean opsMcp, String skillNames) {
        requireEnabled();
        Path real = checkPath(path, Path.of(System.getProperty("user.home", "")));
        String m = mode == null || mode.isBlank() ? Workspace.BYPASS : mode.strip().toLowerCase(Locale.ROOT);
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
        // 고른 스킬: 지금 등록돼 있는 이름만 남긴다(지워진 스킬은 조용히 빠진다)
        Set<String> known = new java.util.HashSet<>();
        skills.list().forEach(s -> known.add(s.getName()));
        Workspace picked = new Workspace();
        picked.setSkills(skillNames);
        w.setSkills(String.join(",", picked.skillList().stream().filter(known::contains).toList()));
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
        // 대화 기록을 지운다고 안내하므로 활동 로그(요청·도구 상세·답변이 들어 있다)도 함께 지운다
        for (Long turnId : mapper.turnIds(id)) {
            events.clear(eventId(turnId));
        }
        mapper.deleteTurns(id);
        mapper.deleteWorkspace(id);
        checkpoints.drop(id);                         // 되돌리기 지점(파일 사본)도 남기지 않는다
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
        return submit(workspaceId, question, threadId, model, null);
    }

    /** {@code origin} = 접수 경로({@link #ORIGIN_MESSENGER} 면 끝났을 때·확인이 필요할 때 메신저로 알린다, 화면은 null). */
    public synchronized WorkspaceTurn submit(long workspaceId, String question, Long threadId, String model, String origin) {
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
        t.setOrigin(origin);
        mapper.insert(t);
        if (thread == 0) {
            mapper.fixThread(t.getId());
            t.setThreadId(t.getId());
        }
        events.emit(eventId(t.getId()), IssueEvent.STATE, "작업 공간 요청 접수 → NEW", WorkspaceTurn.NEW);
        log.info("작업 공간 요청 #{} ws={} thread={} model={}", t.getId(), workspaceId, t.getThreadId(), t.getModel() == null ? "(기본)" : t.getModel());
        final long id = t.getId();
        running.add(id);                              // 줄 서 있는 동안에도 "바쁨"으로 센다(재시작이 대기 중인 요청을 실패로 만들지 않게)
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
        WorkspaceTurn t = null;
        Workspace w = null;
        try {
            t = mapper.find(id);
            if (t == null || !WorkspaceTurn.NEW.equals(t.getState())) {
                return;
            }
            w = mapper.findWorkspace(t.getWorkspaceId());
            if (w == null) {
                finish(t, null, WorkspaceTurn.FAILED, null, "작업 공간이 등록 해제되었습니다.");
                return;
            }
            run(t, w);
        } catch (RuntimeException e) {
            log.warn("작업 공간 요청 #{} 처리 오류: {}", id, e.toString());
            if (t != null) {
                try {
                    finish(t, w, WorkspaceTurn.FAILED, null, abbreviate(e.getMessage(), 500));
                } catch (RuntimeException again) {
                    log.warn("작업 공간 요청 #{} 실패 기록 오류: {}", id, again.toString());
                }
            }
        } finally {
            // 어떤 경로로 끝났든 진행 중 상태로 남기지 않는다(남으면 그 작업 공간에 새 요청을 못 보낸다)
            try {
                WorkspaceTurn now = mapper.find(id);
                if (now != null && now.isActive()) {
                    now.setState(WorkspaceTurn.FAILED);
                    now.setLastError("처리 중 오류로 끝났습니다. 다시 보내 주세요.");
                    now.setFinishedAt(OffsetDateTime.now());
                    mapper.update(now);
                }
            } catch (RuntimeException e) {
                log.warn("작업 공간 요청 #{} 마무리 오류: {}", id, e.toString());
            }
            running.remove(id);
            abortRequested.remove(id);
            approvals.close(id);                      // 남은 확인 대기는 거부로 닫는다
            askNotified.remove(id);
            snapshots.remove(id);                     // finish 를 거치지 않고 끝난 경우에도 스냅샷을 남기지 않는다
            checkpointed.remove(id);
            Path askFile = askFiles.remove(id);
            if (askFile != null) {
                try {
                    Files.deleteIfExists(askFile);
                } catch (IOException ignore) {
                    // 임시 파일
                }
            }
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
        Map<String, long[]> before = WorkspaceFiles.snapshot(dir);
        if (before != null) {
            snapshots.put(t.getId(), before);
            // 되돌리기 지점: 폴더의 사본을 지금 상태와 맞춰 둔다(달라진 파일만 복사 — 처음 한 번만 오래 걸린다). 큰 폴더는 건너뛴다
            long t0 = System.currentTimeMillis();
            if (checkpoints.sync(w.getId(), dir, before)) {
                checkpointed.add(t.getId());
                long ms = System.currentTimeMillis() - t0;
                if (ms > 3000) {
                    events.emit(eid, IssueEvent.INFO, "되돌리기 지점 준비 " + ms / 1000 + "초(파일 " + before.size() + "개)");
                }
            }
        }
        // 화면에서 앞선 요청의 변경을 되돌렸으면 세션은 그 사실을 모른다 — 이번 요청 앞에 알려 준다
        String question = t.getQuestion();
        List<String> undone = revertedSince(t);
        if (!undone.isEmpty()) {
            question = "(참고: 사용자가 화면에서 앞선 요청의 파일 변경을 요청 전 상태로 되돌렸습니다 — " + String.join(" / ", undone)
                    + ". 기억하고 있는 파일 내용이 지금과 다를 수 있으니 필요한 파일은 다시 읽고 진행하세요.)\n\n" + question;
        }
        long bashMin = Math.max(10, Math.min(50, (props.timeoutOr() - 60_000L) / 60_000L));
        String sys = WorkspacePrompts.system(w, dir, bashMin);
        boolean ask = Workspace.ASK.equals(w.getMode());
        List<String> tools = askFreeTools();          // bypass 에서는 의미 없다(전부 실행). ask 에서는 이 목록만 묻지 않고 허용
        CodingAgent.Access access;
        if (ask) {
            // 이 차례 전용 확인 창구: 토큰이 든 주소의 MCP 서버 설정을 임시 파일로 넘긴다
            String token = approvals.open(t.getId());
            String cfg = "{\"mcpServers\":{\"approval\":{\"type\":\"http\",\"url\":\"http://127.0.0.1:" + adminPort + "/mcp/approval/" + token + "\"}}}";
            try {
                Path f = Files.createTempFile("dev-agent-approval-", ".json");
                Files.writeString(f, cfg, StandardCharsets.UTF_8);
                askFiles.put(t.getId(), f);
                access = new CodingAgent.Access(CodingAgent.Permission.ASK, w.isOpsMcp(), w.skillList(), f, "mcp__approval__ask");
            } catch (IOException e) {
                throw new IllegalStateException("확인 도구 설정 파일을 만들지 못했습니다: " + e.getMessage(), e);
            }
        } else {
            access = new CodingAgent.Access(CodingAgent.Permission.BYPASS, w.isOpsMcp(), w.skillList(), null, null);
        }
        CodingAgent.Overrides ov = new CodingAgent.Overrides(t.getModel(), props.effort(), props.timeoutOr(), props.maxTurnsOr(), SOURCE, access);
        CodingAgent.Listener listener = (kind, message, detail) -> events.emit(eid, kind, message, detail);
        events.emit(eid, IssueEvent.CLAUDE, "▶ 작업 공간 「" + w.getName() + "」 세션 " + (resume ? "이어서 " : "") + "시작 (상한 "
                + props.timeoutOr() / 60_000 + "분/" + props.maxTurnsOr() + "턴)", t.getQuestion());
        if (abortRequested.contains(t.getId())) {     // 준비(스냅샷 등)하는 사이에 중단을 눌렀다: 세션을 띄우지 않는다
            finish(t, w, WorkspaceTurn.ABORTED, null, "사용자 중단(시작 전)");
            return;
        }
        try {
            CodingAgent.Result r;
            try {
                r = agent.run(dir, sid, resume, tools, sys, question, listener, null, CodingAgent.Stage.FIX, false, ov);
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
                r = agent.run(dir, sid, false, tools, sys, question, listener, null, CodingAgent.Stage.FIX, false, ov);
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
        // 이번 차례에 바뀐 파일: 실행 직전 스냅샷과 지금을 비교(셸로 바꾼 것도 잡힌다). 실패·중단이어도 남긴다
        Map<String, long[]> before = snapshots.remove(t.getId());
        int changedCount = 0;
        if (before != null && w != null) {
            Path root = Path.of(w.getPath());
            Map<String, long[]> after = WorkspaceFiles.snapshot(root);
            List<String> ch = WorkspaceFiles.changes(before, after);
            t.setChanged(ch.isEmpty() ? null : String.join("\n", ch));
            changedCount = ch.size();
            if (!ch.isEmpty() && checkpointed.remove(t.getId())) {
                // 바뀐 파일의 요청 전·후 내용을 떼어 둔다(그 차례의 변경분 보기·되돌리기). 목록은 잘리지 않은 전체로
                checkpoints.capture(w.getId(), t.getId(), root, WorkspaceFiles.changes(before, after, Integer.MAX_VALUE));
            }
        }
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
        // 메신저로 들어온 요청은 답도 메신저로(자리를 비운 채 시킨 일의 결과). 길면 앞부분만 — 전체는 화면에
        if (ORIGIN_MESSENGER.equals(t.getOrigin())) {
            try {
                messenger.notifySelf(MessengerService.NOTICE_WORKSPACE, messengerResult(t, w, changedCount));
            } catch (RuntimeException e) {
                log.warn("작업 공간 요청 #{} 메신저 알림 실패: {}", t.getId(), e.toString());
            }
        }
    }

    /** 메신저로 보내는 결과 글: 상태 한 줄 + 답변 앞부분 + 화면 주소. */
    String messengerResult(WorkspaceTurn t, Workspace w, int changedCount) {
        boolean done = WorkspaceTurn.DONE.equals(t.getState());
        StringBuilder sb = new StringBuilder(PFX).append(w == null ? "작업 공간" : w.getName()).append(' ')
                .append(done ? "완료" : WorkspaceTurn.ABORTED.equals(t.getState()) ? "중단" : "실패");
        if (t.getElapsedMs() > 0) {
            sb.append(" · ").append(Math.max(1, t.getElapsedMs() / 60_000)).append("분");
        }
        if (changedCount > 0) {
            sb.append(" · 바뀐 파일 ").append(changedCount);
        }
        if (t.getLastError() != null && !t.getLastError().isBlank()) {
            sb.append("\n").append(abbreviate(t.getLastError(), 300));
        }
        if (t.getAnswerMd() != null && !t.getAnswerMd().isBlank()) {
            sb.append("\n\n").append(abbreviate(t.getAnswerMd(), MESSENGER_ANSWER_CHARS));
        }
        sb.append("\n\n전체: http://localhost:").append(adminPort).append("/workspace.html#w=").append(t.getWorkspaceId()).append("&t=").append(t.getThreadId());
        return sb.toString();
    }

    // ---------------------------------------------------------------- 메신저 명령(@AI 작업 …): 자리를 비운 채 작업 공간에 일을 시킨다

    public static final String ORIGIN_MESSENGER = "messenger";
    /** 메신저로 보내는 글의 접두어(수집에서 봇이 보낸 글로 걸러진다 — 바꾸면 ChatMessageMapper·CollectService 도). */
    public static final String PFX = "[작업] ";
    private static final int MESSENGER_ANSWER_CHARS = 1800;
    private static final String USAGE = """
            사용법
            · @AI 작업 <작업 공간>: <요청> — 그 작업 공간의 마지막 대화에 이어서 요청(작업 공간이 하나면 이름 생략 가능)
            · @AI 새작업 <작업 공간>: <요청> — 새 대화로 요청
            · @AI 작업 상태 — 진행 중인 요청과 실행 전 확인 대기
            · @AI 작업 허용 / @AI 작업 거부 <이유> — 가장 오래 기다린 실행 전 확인에 답하기
            · @AI 작업 중단 [작업 공간] — 진행 중인 요청 중단""";

    /**
     * 메신저 명령 처리(자기방에서만 불린다). {@code text} 는 "작업"/"새작업" 뒤의 글, {@code fresh} 는 새 대화로 시작할지.
     * 돌려주는 글을 그대로 자기방에 답한다.
     */
    public String messengerCommand(String text, boolean fresh) {
        if (!enabled()) {
            return "작업 공간이 꺼져 있습니다.";
        }
        String t = text == null ? "" : text.strip();
        List<Workspace> all = mapper.workspaces();
        if (t.isEmpty() || t.equals("목록") || t.equals("도움말")) {
            return (all.isEmpty() ? "등록된 작업 공간이 없습니다. 화면의 「작업 공간」 탭에서 폴더를 등록하세요."
                    : "작업 공간: " + String.join(", ", all.stream().map(Workspace::getName).toList())) + "\n" + USAGE;
        }
        if (t.equals("상태")) {
            return messengerStatus();
        }
        if (t.equals("허용") || t.equals("거부") || t.startsWith("거부 ")) {
            boolean allow = t.equals("허용");
            WorkspaceApprovals.Pending p = approvals.oldest();
            if (p == null) {
                return "기다리는 실행 전 확인이 없습니다.";
            }
            String why = allow ? null : t.substring(2).strip();
            approvals.decide(p.id(), allow, false, why);
            int left = approvals.waitingTurns().values().stream().mapToInt(Integer::intValue).sum();
            return (allow ? "허용했습니다 — " : "거부했습니다 — ") + p.tool() + (p.summary().isEmpty() ? "" : ": " + abbreviate(p.summary(), 120))
                    + (left > 0 ? "\n아직 " + left + "건이 더 기다립니다(@AI 작업 상태)." : "");
        }
        if (t.equals("중단") || t.startsWith("중단 ")) {
            List<WorkspaceTurn> active = mapper.findActive();
            String name = t.substring(2).strip();
            if (!name.isEmpty()) {
                Workspace w = match(all, name);
                active = w == null ? List.of() : active.stream().filter(x -> x.getWorkspaceId() == w.getId()).toList();
            }
            if (active.isEmpty()) {
                return "진행 중인 요청이 없습니다.";
            }
            if (active.size() > 1) {
                return "진행 중인 요청이 " + active.size() + "건입니다. 작업 공간을 적어 주세요: @AI 작업 중단 <작업 공간>";
            }
            return abort(active.get(0).getId());
        }
        int colon = indexOfColon(t);
        Workspace w;
        String q;
        if (colon < 0) {
            if (all.size() != 1) {
                return "어느 작업 공간인지 적어 주세요: @AI 작업 <작업 공간>: <요청>\n작업 공간: "
                        + String.join(", ", all.stream().map(Workspace::getName).toList());
            }
            w = all.get(0);
            q = t;
        } else {
            String name = t.substring(0, colon).strip();
            q = t.substring(colon + 1).strip();
            w = name.isEmpty() && all.size() == 1 ? all.get(0) : match(all, name);
            if (w == null && all.size() == 1) {       // 이름이 아니라 요청 글 안의 콜론이었다
                w = all.get(0);
                q = t;
            }
            if (w == null) {
                return "작업 공간을 찾을 수 없습니다: " + name + "\n작업 공간: " + String.join(", ", all.stream().map(Workspace::getName).toList());
            }
        }
        if (q.isEmpty()) {
            return USAGE;
        }
        try {
            Long thread = fresh ? null : mapper.lastThread(w.getId());
            WorkspaceTurn turn = submit(w.getId(), q, thread, null, ORIGIN_MESSENGER);
            return "「" + w.getName() + "」 접수 (" + (thread == null ? "새 대화" : "이어서") + " · " + (Workspace.ASK.equals(w.getMode())
                    ? "확인 후 실행 — 실행 전 확인이 필요하면 여기로 묻습니다" : "바로 실행") + "). 끝나면 알려 드립니다.\n진행: http://localhost:"
                    + adminPort + "/workspace.html#w=" + w.getId() + "&t=" + turn.getThreadId();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private String messengerStatus() {
        List<WorkspaceTurn> active = mapper.findActive();
        if (active.isEmpty()) {
            return "진행 중인 요청이 없습니다.";
        }
        StringBuilder sb = new StringBuilder();
        for (WorkspaceTurn a : active) {
            Workspace w = mapper.findWorkspace(a.getWorkspaceId());
            sb.append(sb.length() > 0 ? "\n" : "").append("· ").append(w == null ? "#" + a.getWorkspaceId() : w.getName()).append(" — ")
                    .append(WorkspaceTurn.NEW.equals(a.getState()) ? "대기" : "작업 중").append(": ").append(abbreviate(a.getQuestion(), 60));
            for (WorkspaceApprovals.Pending p : approvals.pending(a.getId())) {
                sb.append("\n   확인 대기 ").append(p.tool()).append(p.summary().isEmpty() ? "" : ": " + abbreviate(p.summary(), 100));
            }
        }
        return sb.toString();
    }

    /** 요청 글에서 작업 공간 이름과 요청을 가르는 첫 콜론(반각·전각). 주소의 "://"와 드라이브 문자("C:\")는 건너뛴다. */
    static int indexOfColon(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '：') {
                return i;
            }
            if (c == ':') {
                boolean url = t.startsWith("://", i);
                boolean drive = i == 1 && i + 1 < t.length() && (t.charAt(2) == '\\' || t.charAt(2) == '/');
                if (!url && !drive) {
                    return i;
                }
                if (url) {
                    return -1;                        // 이름 자리에 주소가 왔다: 이름 지정이 없는 요청으로 본다
                }
            }
        }
        return -1;
    }

    /** 이름으로 작업 공간 찾기: 정확히 같은 이름 → 그 글자로 시작하는 유일한 것 → 그 글자를 포함하는 유일한 것. */
    static Workspace match(List<Workspace> all, String name) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) {
            return null;
        }
        for (Workspace w : all) {
            if (w.getName().toLowerCase(Locale.ROOT).equals(n)) {
                return w;
            }
        }
        List<Workspace> pre = all.stream().filter(w -> w.getName().toLowerCase(Locale.ROOT).startsWith(n)).toList();
        if (pre.size() == 1) {
            return pre.get(0);
        }
        List<Workspace> has = all.stream().filter(w -> w.getName().toLowerCase(Locale.ROOT).contains(n)).toList();
        return has.size() == 1 ? has.get(0) : null;
    }

    // ---------------------------------------------------------------- 차례별 되돌리기

    /** 이 대화에서, 세션이 마지막으로 돈 뒤에 사용자가 되돌린 차례들(요청 앞부분). 세션에 알려 줄 것이 없으면 빈 목록. */
    private List<String> revertedSince(WorkspaceTurn t) {
        List<WorkspaceTurn> thread = mapper.findThread(t.getThreadId());
        OffsetDateTime lastRun = null;
        for (WorkspaceTurn x : thread) {
            if (x.getId() < t.getId() && x.getFinishedAt() != null) {
                lastRun = x.getFinishedAt();
            }
        }
        List<String> out = new ArrayList<>();
        for (WorkspaceTurn x : thread) {
            if (x.getId() < t.getId() && x.getRevertedAt() != null && lastRun != null && !x.getRevertedAt().isBefore(lastRun)) {
                out.add("「" + abbreviate(x.getQuestion(), 60) + "」");
            }
        }
        return out;
    }

    /**
     * 그 차례가 바꾼 파일을 요청 전 상태로 되돌린다(그 차례가 남긴 상태 그대로인 파일만 — 이후에 또 바뀐 파일은 건너뛴다).
     * 그 작업 공간에서 요청을 처리하는 중에는 하지 않는다.
     */
    public synchronized String revert(long turnId) {
        requireEnabled();
        WorkspaceTurn t = mapper.find(turnId);
        if (t == null) {
            throw new IllegalArgumentException("요청을 찾을 수 없습니다: #" + turnId);
        }
        if (!mapper.findActiveIn(t.getWorkspaceId()).isEmpty()) {
            throw new IllegalArgumentException("이 작업 공간에서 요청을 처리하는 중입니다. 끝난 뒤 되돌리세요.");
        }
        Path root = root(t.getWorkspaceId());
        WorkspaceCheckpoints.Reverted r = checkpoints.revert(t.getWorkspaceId(), turnId, root);
        if (!r.restored().isEmpty()) {
            mapper.markReverted(turnId);
        }
        events.emit(eventId(turnId), IssueEvent.INFO, "되돌리기 — " + r.restored().size() + "개 되돌림, " + r.skipped().size() + "개 건너뜀");
        log.info("작업 공간 요청 #{} 되돌리기: {}개 되돌림, {}개 건너뜀", turnId, r.restored().size(), r.skipped().size());
        StringBuilder sb = new StringBuilder(r.restored().isEmpty() ? "되돌린 파일이 없습니다." : "파일 " + r.restored().size() + "개를 요청 전 상태로 되돌렸습니다.");
        if (!r.skipped().isEmpty()) {
            sb.append("\n건너뜀 ").append(r.skipped().size()).append("개: ")
                    .append(String.join(", ", r.skipped().size() > 5 ? r.skipped().subList(0, 5) : r.skipped()))
                    .append(r.skipped().size() > 5 ? " …" : "");
        }
        return sb.toString();
    }

    /** 그 차례에서 이 파일이 바뀐 내용(요청 전 → 요청 후). 되돌리기 지점이 없으면 error. */
    public Map<String, Object> turnDiff(long turnId, String path) {
        requireEnabled();
        WorkspaceTurn t = mapper.find(turnId);
        Path[] sides = t == null ? null : checkpoints.sides(t.getWorkspaceId(), turnId, path == null ? "" : path.strip().replace('\\', '/'));
        if (sides == null) {
            throw new IllegalArgumentException("이 요청에서 바뀐 내용을 보관하고 있지 않습니다(오래되었거나 큰 폴더).");
        }
        if (sides[0] == null && sides[1] == null) {
            throw new IllegalArgumentException("큰 파일이라 내용을 보관하지 않았습니다.");
        }
        Map<String, Object> out = WorkspaceFiles.fileDiff(sides[0], sides[1]);
        out.put("path", path);
        out.put("turn", turnId);
        out.put("kind", sides[0] == null ? "A" : sides[1] == null ? "D" : "M");
        return out;
    }

    /** 폴더 전체의 파일 경로(화면의 「파일 찾기」용). */
    public Map<String, Object> names(long workspaceId) {
        return WorkspaceFiles.names(root(workspaceId));
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
        approvals.close(id);                          // 확인을 기다리던 실행은 거부로 풀어 준다
        boolean killed = t.getClaudeSessionId() != null && agent.kill(t.getClaudeSessionId());
        if (killed) {
            events.emit(eventId(id), IssueEvent.INFO, "중단 요청 — 실행 중인 Claude 프로세스 종료");
        } else {
            // 세션 프로세스가 막 뜨는 중이면 아직 죽일 것이 없다: 잠깐 동안 다시 시도한다(안 그러면 끝까지 돌고 나서야 중단으로 표시된다)
            Thread retry = new Thread(() -> {
                for (int i = 0; i < 15; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    WorkspaceTurn now = mapper.find(id);
                    if (now == null || !now.isActive()) {
                        return;
                    }
                    if (now.getClaudeSessionId() != null && agent.kill(now.getClaudeSessionId())) {
                        events.emit(eventId(id), IssueEvent.INFO, "중단 요청 — 실행 중인 Claude 프로세스 종료");
                        return;
                    }
                }
            }, "workspace-abort-" + id);
            retry.setDaemon(true);
            retry.start();
        }
        return "중단을 요청했습니다. 그때까지 고친 내용은 폴더에 남습니다.";
    }

    // ---------------------------------------------------------------- 조회

    /** 홈 화면용: 모든 작업 공간의 최근 대화. */
    public List<WorkspaceMapper.ThreadRow> recent(int limit) {
        return enabled() ? mapper.recentThreads(Math.max(1, Math.min(limit, 50))) : List.of();
    }

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
        Map<Long, Integer> waitingNow = approvals.waitingTurns();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Workspace w : mapper.workspaces()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("name", w.getName());
            m.put("path", w.getPath());
            m.put("mode", w.getMode());
            m.put("opsMcp", w.isOpsMcp());
            m.put("skills", w.skillList());
            m.put("exists", Files.isDirectory(Path.of(w.getPath())));
            WorkspaceTurn a = active.get(w.getId());
            m.put("runningThread", a == null ? null : a.getThreadId());
            m.put("waiting", a == null ? 0 : waitingNow.getOrDefault(a.getId(), 0));   // 사용자 확인을 기다리는 건수
            list.add(m);
        }
        out.put("workspaces", list);
        out.put("models", MODELS);
        SettingService.AgentModels am = settings.getAgentModels();
        out.put("defaultModel", props.model() == null || props.model().isBlank() ? am.devModel() : props.model().strip());
        out.put("mcpAvailable", mcp.agentEnabled());
        // 고를 수 있는 플랫폼 스킬. auto = 적용 단계가 '작업 공간'이라 모든 작업 공간에 실리는 것
        List<Map<String, Object>> sk = new ArrayList<>();
        for (AgentSkill s : skills.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("description", s.getDescription());
            m.put("auto", s.appliesTo(SOURCE));
            sk.add(m);
        }
        out.put("skills", sk);
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
        for (WorkspaceTurn x : items) {
            x.setCheckpoint(x.getChanged() != null && checkpoints.has(x.getWorkspaceId(), x.getId()));
        }
        out.put("turns", items);
        out.put("liveId", last.getId());
        out.put("events", events.events(eventId(last.getId()), afterEventId, 500));
        out.put("pending", last.isActive() ? approvals.pending(last.getId()) : List.of());   // 사용자 확인을 기다리는 도구 실행
        CodingAgent.RunInfo ri = last.isActive() ? agent.runInfo(last.getClaudeSessionId()) : null;
        if (ri != null) {
            out.put("running", Map.of("model", ri.model(), "effort", ri.effort(), "startedAt", ri.startedAt()));
        }
        return out;
    }

    // ---------------------------------------------------------------- 폴더 보기(읽기 전용)

    /** 등록된 작업 공간의 폴더(없으면 IllegalArgumentException). 화면의 파일 패널이 이 루트 안만 본다. */
    public Path root(long workspaceId) {
        requireEnabled();
        Workspace w = mapper.findWorkspace(workspaceId);
        if (w == null) {
            throw new IllegalArgumentException("작업 공간을 찾을 수 없습니다: #" + workspaceId);
        }
        Path dir = Path.of(w.getPath());
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("폴더가 없습니다: " + w.getPath());
        }
        return dir;
    }

    public Map<String, Object> tree(long workspaceId, String dir) {
        return WorkspaceFiles.list(root(workspaceId), dir);
    }

    public Map<String, Object> file(long workspaceId, String path, long ifMtime) {
        return WorkspaceFiles.read(root(workspaceId), path, ifMtime);
    }

    public Map<String, Object> diff(long workspaceId, String path) {
        return WorkspaceFiles.gitDiff(root(workspaceId), path);
    }

    /** 그림 파일의 실제 경로(그림 형식이 아니거나 너무 크면 IllegalArgumentException). */
    public Path image(long workspaceId, String path) {
        Path p = WorkspaceFiles.resolveInside(root(workspaceId), path);
        try {
            if (!Files.isRegularFile(p) || WorkspaceFiles.imageType(p.getFileName().toString()) == null
                    || Files.size(p) > WorkspaceFiles.MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("볼 수 있는 그림 파일이 아닙니다: " + path);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("파일을 읽을 수 없습니다: " + path);
        }
        return p;
    }

    public static String imageType(String name) {
        return WorkspaceFiles.imageType(name);
    }

    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
