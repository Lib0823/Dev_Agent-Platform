package io.github.lib0823.devagent.preview;

import jakarta.annotation.PreDestroy;
import io.github.lib0823.devagent.config.PreviewProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 로컬 프리뷰: worktree → Maven WAR 빌드 → 봇 호스트 Tomcat(CATALINA_BASE 인스턴스) 배포·기동 → URL.
 * <p>
 * 동시에 1개 프리뷰만 띄운다(포트 1개). Tomcat 은 {@code catalina.bat run} 자식 프로세스로 띄워 핸들을 쥐고 있다가
 * 재배포/중지/봇 종료 시 프로세스 트리를 내린다. 이슈 에이전트는 worktree 에서 {@code preview deploy}(scripts/bin/preview)로 호출한다.
 */
@Service
public class PreviewService {

    private static final Logger log = LoggerFactory.getLogger(PreviewService.class);

    /** 핫 동기화 대상(worktree 상대 경로 접두): 여기 아래 파일은 실행 중인 Tomcat 전개 디렉터리에 복사만 하면 반영된다. */
    static final String WEBAPP = "src/main/webapp/";
    /** 변경 시 전체 재배포(preview deploy)가 필요한 소스 트리. */
    static final List<String> REBUILD_ROOTS = List.of("src/main/java/", "src/main/resources/", "pom.xml");

    private final PreviewProperties props;
    private final ReentrantLock lock = new ReentrantLock();
    /** 프리뷰 1개 공유: 현재 배포된 디렉터리를 쓰는 세션이 작업 중(수정/검증)이면 true 를 돌려주는 콜백(IssueAgentService 가 설정). */
    private volatile java.util.function.Predicate<String> ownerBusy = dir -> false;
    private static final long SHARE_WAIT_MS = 25 * 60 * 1000L;

    public void setOwnerBusy(java.util.function.Predicate<String> ownerBusy) {
        this.ownerBusy = ownerBusy == null ? dir -> false : ownerBusy;
    }
    private volatile Process tomcat;
    private volatile Deployment current;
    /** 마지막 배포/동기화 시점의 소스 스냅샷(상대경로 → mtime^size). sync 가 무엇이 바뀌었는지 찾는 기준. */
    private volatile Map<String, Long> snapshot = Map.of();

    public record Deployment(String dir, String url, String warPath, long buildMs, long startMs, long deployedAt) {
    }

    public record Result(boolean ok, String message, Deployment deployment, String logTail) {
    }

    public PreviewService(PreviewProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.enabled() && props.catalinaHome() != null && !props.catalinaHome().isBlank();
    }

    public boolean isRunning() {
        Process p = tomcat;
        return p != null && p.isAlive();
    }

    public Deployment current() {
        return isRunning() ? current : null;
    }

    /** 설치 상태 점검(관리 페이지/상태 API). */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled());
        m.put("running", isRunning());
        m.put("url", props.url());
        m.put("deployment", current());
        m.put("javaHome", exists(props.javaHome(), "bin/java.exe"));
        m.put("maven", exists(props.mavenHome(), "bin/mvn.cmd"));
        m.put("catalinaHome", exists(props.catalinaHome(), "bin/catalina.bat"));
        m.put("catalinaBase", exists(props.catalinaBase(), "conf/server.xml"));
        m.put("hasLogin", props.hasLogin());
        m.put("busy", lock.isLocked());
        return m;
    }

    private static Map<String, Object> exists(String home, String probe) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", home);
        m.put("ok", home != null && !home.isBlank() && Files.exists(Path.of(home).resolve(probe)));
        return m;
    }

    public Result deploy(Path worktree) {
        return deploy(worktree, true);
    }

    /**
     * (빌드 +) 배포 + 기동(블로킹, 수 분). build=false 면 target/ 의 기존 WAR 를 그대로 재배포한다(빌드 14분을 아끼는 재시도용).
     * 포트를 다른 프로세스가 쥐고 있으면 시작하지 않는다(봇이 띄운 인스턴스만 관리).
     */
    public Result deploy(Path worktree, boolean build) {
        if (!enabled()) {
            return new Result(false, "프리뷰가 비활성이거나 Tomcat 경로가 설정되지 않았습니다(preview.*).", null, "");
        }
        if (!Files.isRegularFile(worktree.resolve("pom.xml"))) {
            return new Result(false, "pom.xml 이 없습니다: " + worktree, null, "");
        }
        // 프리뷰 Tomcat 은 1개를 여러 세션이 순서대로 쓴다: 다른 작업트리가 올라가 있고 그 세션이 아직 수정/검증 중이면 끝날 때까지 기다린다
        long waited = waitForShare(worktree);
        if (waited < 0) {
            return new Result(false, "다른 세션이 프리뷰를 " + SHARE_WAIT_MS / 60000 + "분 넘게 쓰고 있어 배포하지 못했습니다. 잠시 후 `preview deploy` 를 다시 실행하세요.", current(), "");
        }
        if (!lock.tryLock()) {
            return new Result(false, "다른 프리뷰 배포가 진행 중입니다. 잠시 후 다시 시도하세요.", current(), "");
        }
        try {
            long t0 = System.currentTimeMillis();
            String buildOut = build ? runMaven(worktree) : "(빌드 생략 — 기존 WAR 재사용)";
            long buildMs = System.currentTimeMillis() - t0;
            Path exploded = findExploded(worktree);
            if (exploded == null) {
                return new Result(false, "빌드 산출물(target/<war>/WEB-INF/classes)을 찾지 못했습니다.\n" + tail(buildOut, 40), null, "");
            }
            stopInternal();
            String holder = portHolder(props.port());
            if (holder != null) {
                return new Result(false, "포트 " + props.port() + " 를 다른 프로세스가 사용 중입니다(" + holder + "). 그 프로세스를 내린 뒤 다시 시도하세요.", null, "");
            }
            Path base = Path.of(props.catalinaBase());
            Path webapps = base.resolve("webapps");
            Files.createDirectories(webapps);
            String name = props.warName() == null || props.warName().isBlank() ? exploded.getFileName() + ".war" : props.warName();
            String ctx = name.endsWith(".war") ? name.substring(0, name.length() - 4) : name;
            Path dest = webapps.resolve(ctx);
            Files.deleteIfExists(webapps.resolve(ctx + ".war"));      // WAR 가 남아 있으면 Tomcat 이 다시 전개해 덮어쓴다
            deleteRecursively(base.resolve("work").resolve("Catalina"));
            mirror(exploded, dest);                                   // 전개된 디렉터리를 그대로 배포(350MB WAR 재전개 생략)
            String overlaid = overlayConfig(worktree, dest);
            Path war = exploded;
            long t1 = System.currentTimeMillis();
            long logMark = logSize(base);
            startTomcat(base);
            String err = waitUntilUp(props.startTimeoutMs(), base, logMark);
            long startMs = System.currentTimeMillis() - t1;
            if (err != null) {
                String lt = logTail(60);
                stopInternal();
                return new Result(false, "프리뷰 기동 실패: " + err + "\n--- tomcat log ---\n" + lt, null, lt);
            }
            current = new Deployment(worktree.toString(), props.url(), war.toString(), buildMs, startMs, System.currentTimeMillis());
            snapshot = snapshot(worktree);
            log.info("프리뷰 배포 완료 {} build={}s start={}s overlay={}", props.url(), buildMs / 1000, startMs / 1000, overlaid);
            return new Result(true, "프리뷰 준비됨: " + props.url() + " (빌드 " + buildMs / 1000 + "s, 기동 " + startMs / 1000 + "s"
                    + (waited > 0 ? ", 다른 세션 대기 " + waited / 1000 + "s" : "") + ")"
                    + "\n설정 덮어쓰기(개발서버): " + overlaid
                    + "\n로그 오류 여부: " + errorSummary()
                    + "\n이후 JSP/JS/CSS 만 고쳤다면 `preview sync`(수 초)로 반영됩니다. Java/resources 변경은 `preview deploy`.", current, "");
        } catch (Exception e) {
            log.warn("프리뷰 배포 실패: {}", e.toString());
            return new Result(false, "프리뷰 배포 실패: " + e.getMessage(), null, logTail(40));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 다른 작업트리의 프리뷰를 작업 중인 세션이 쓰고 있으면 풀릴 때까지 대기(10초 간격, 최대 SHARE_WAIT_MS).
     * 반환: 기다린 ms, 시간 초과면 -1. 같은 작업트리거나 비어 있거나 주인이 확인 대기/종료 상태면 0.
     */
    private long waitForShare(Path worktree) {
        long t0 = System.currentTimeMillis();
        String mine = worktree.toAbsolutePath().normalize().toString();
        while (true) {
            Deployment d = current();
            if (d == null || Path.of(d.dir()).toAbsolutePath().normalize().toString().equalsIgnoreCase(mine) || !ownerBusy.test(d.dir())) {
                return System.currentTimeMillis() - t0;
            }
            if (System.currentTimeMillis() - t0 > SHARE_WAIT_MS) {
                return -1;
            }
            if (System.currentTimeMillis() - t0 < 1000) {
                log.info("프리뷰 공유 대기: {} 가 {} 를 쓰는 중 → {} 대기", d.dir(), props.url(), mine);
            }
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    public Result stop() {
        lock.lock();
        try {
            boolean was = isRunning();
            stopInternal();
            return new Result(true, was ? "프리뷰를 중지했습니다." : "실행 중인 프리뷰가 없습니다.", null, "");
        } finally {
            lock.unlock();
        }
    }

    /** sync 가 분류한 변경 목록. */
    public record SyncPlan(List<String> webapp, List<String> deleted, List<String> rebuild, List<String> ignored) {
        public boolean empty() {
            return webapp.isEmpty() && deleted.isEmpty() && rebuild.isEmpty();
        }
    }

    /**
     * 핫 동기화(수 초): 실행 중인 프리뷰(같은 worktree)에 src/main/webapp 아래 변경(JSP/JS/CSS/이미지)만 복사·삭제한다.
     * JSP 는 Jasper 개발 모드가 다음 요청 때 재컴파일(최대 4초)하고 정적 파일은 그대로 서빙된다.
     * files 가 비면 마지막 배포/동기화 스냅샷과 비교해 변경을 스스로 찾는다. Java/resources/pom 변경이 섞여 있으면
     * 복사는 하되 결과에 '전체 재배포 필요' 로 알린다(모델이 preview deploy 로 이어가도록).
     */
    public Result sync(Path worktree, List<String> files) {
        Deployment d = current();
        if (d == null) {
            return new Result(false, "실행 중인 프리뷰가 없습니다. 먼저 `preview deploy` 로 띄우세요.", null, "");
        }
        if (!Path.of(d.dir()).toAbsolutePath().normalize().equals(worktree.toAbsolutePath().normalize())) {
            return new Result(false, "실행 중인 프리뷰는 다른 디렉터리(" + d.dir() + ")를 배포한 것입니다. `preview deploy` 로 다시 배포하세요.", d, "");
        }
        if (!lock.tryLock()) {
            return new Result(false, "다른 프리뷰 작업이 진행 중입니다. 잠시 후 다시 시도하세요.", d, "");
        }
        try {
            Map<String, Long> now = snapshot(worktree);
            List<String> rel = files == null || files.isEmpty() ? changed(snapshot, now) : normalize(worktree, files);
            SyncPlan plan = classify(worktree, rel);
            if (plan.empty()) {
                return new Result(true, "변경된 파일이 없습니다(마지막 배포/동기화 이후 src/main/webapp·java·resources 기준).", d, "");
            }
            Path dest = Path.of(props.catalinaBase()).resolve("webapps").resolve(contextName());
            int copied = 0, removed = 0;
            List<String> failed = new ArrayList<>();
            for (String r : plan.webapp()) {
                Path from = worktree.resolve(r);
                Path to = dest.resolve(r.substring(WEBAPP.length()));
                try {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);   // mtime 이 새로워져 Jasper 가 재컴파일
                    copied++;
                } catch (IOException e) {
                    failed.add(r + " (" + e.getMessage() + ")");
                }
            }
            for (String r : plan.deleted()) {
                Path to = dest.resolve(r.substring(WEBAPP.length()));
                try {
                    if (Files.deleteIfExists(to)) {
                        removed++;
                    }
                } catch (IOException e) {
                    failed.add(r + " (" + e.getMessage() + ")");
                }
            }
            snapshot = now;
            StringBuilder sb = new StringBuilder();
            boolean ok = failed.isEmpty();
            sb.append("핫 동기화: 복사 ").append(copied).append("개, 삭제 ").append(removed).append("개 → ").append(props.url());
            if (copied + removed > 0) {
                sb.append("\n반영: ").append(String.join(", ", shorten(plan.webapp(), plan.deleted())));
                sb.append("\nJSP 는 다음 요청 때 재컴파일됩니다(최대 4초). 브라우저는 페이지를 다시 열어(browser_navigate) 확인하세요.");
            }
            if (!plan.rebuild().isEmpty()) {
                ok = false;
                sb.append("\n전체 재배포 필요(Java/resources/pom 변경은 핫 동기화로 반영되지 않음): ")
                  .append(String.join(", ", shorten(plan.rebuild(), List.of())))
                  .append("\n→ `preview deploy` 를 실행하세요.");
            }
            if (!failed.isEmpty()) {
                sb.append("\n실패: ").append(String.join("; ", failed));
            }
            log.info("프리뷰 핫 동기화 copied={} removed={} rebuild={} failed={}", copied, removed, plan.rebuild().size(), failed.size());
            return new Result(ok, sb.toString(), d, "");
        } catch (Exception e) {
            log.warn("프리뷰 핫 동기화 실패: {}", e.toString());
            return new Result(false, "핫 동기화 실패: " + e.getMessage(), d, "");
        } finally {
            lock.unlock();
        }
    }

    /** 상대경로 목록을 webapp 복사 / 삭제 / 전체 재배포 필요 / 무시 로 분류. (단위 테스트용으로 분리) */
    static SyncPlan classify(Path worktree, List<String> rel) {
        List<String> webapp = new ArrayList<>(), deleted = new ArrayList<>(), rebuild = new ArrayList<>(), ignored = new ArrayList<>();
        for (String r : rel) {
            if (r.startsWith(WEBAPP)) {
                if (Files.isRegularFile(worktree.resolve(r))) {
                    webapp.add(r);
                } else {
                    deleted.add(r);
                }
            } else if (REBUILD_ROOTS.stream().anyMatch(r::startsWith)) {
                rebuild.add(r);
            } else {
                ignored.add(r);
            }
        }
        return new SyncPlan(webapp, deleted, rebuild, ignored);
    }

    /** 전달받은 파일 경로(절대/상대, 역슬래시 포함)를 worktree 기준 상대경로('/' 구분)로. */
    static List<String> normalize(Path worktree, List<String> files) {
        Path root = worktree.toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        for (String f : files) {
            String s = f == null ? "" : f.strip().replace('\\', '/');
            if (s.isEmpty()) {
                continue;
            }
            Path p = Path.of(s);
            if (p.isAbsolute()) {
                Path abs = p.normalize();
                if (!abs.startsWith(root)) {
                    continue;
                }
                s = root.relativize(abs).toString().replace('\\', '/');
            } else if (s.startsWith("./")) {
                s = s.substring(2);
            }
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 두 스냅샷의 차이(추가·수정·삭제된 상대경로). */
    static List<String> changed(Map<String, Long> before, Map<String, Long> after) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : after.entrySet()) {
            if (!e.getValue().equals(before.get(e.getKey()))) {
                out.add(e.getKey());
            }
        }
        for (String k : before.keySet()) {
            if (!after.containsKey(k)) {
                out.add(k);
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * src/main/{webapp,java,resources} + pom.xml 의 상대경로 → mtime*31+size.
     * walkFileTree 는 디렉터리 열거 때 받은 속성을 visitFile 에 넘겨주므로 파일마다 stat 을 다시 하지 않는다
     * (이 호스트 실측: 4,700개 파일에 Files.walk+toFile() 17초 vs walkFileTree 0.4초).
     */
    static Map<String, Long> snapshot(Path worktree) throws IOException {
        Map<String, Long> m = new HashMap<>();
        List<String> roots = new ArrayList<>(REBUILD_ROOTS);
        roots.add(WEBAPP);
        for (String r : roots) {
            Path p = worktree.resolve(r);
            if (!Files.exists(p)) {
                continue;
            }
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes at) {
                    if (at.isRegularFile()) {
                        m.put(worktree.relativize(f).toString().replace('\\', '/'), at.lastModifiedTime().toMillis() * 31 + at.size());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;   // 잠긴 파일 등은 건너뛴다
                }
            });
        }
        return m;
    }

    private static List<String> shorten(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        for (String s : a) {
            out.add(s.startsWith(WEBAPP) ? s.substring(WEBAPP.length()) : s);
        }
        for (String s : b) {
            out.add((s.startsWith(WEBAPP) ? s.substring(WEBAPP.length()) : s) + "(삭제)");
        }
        if (out.size() > 20) {
            List<String> cut = new ArrayList<>(out.subList(0, 20));
            cut.add("… 외 " + (out.size() - 20) + "개");
            return cut;
        }
        return out;
    }

    private String contextName() {
        String name = props.warName() == null || props.warName().isBlank() ? "ROOT.war" : props.warName();
        return name.endsWith(".war") ? name.substring(0, name.length() - 4) : name;
    }

    /** Tomcat 로그 마지막 N줄(catalina.*.log + localhost.*.log 중 최신). */
    public String logTail(int lines) {
        if (props.catalinaBase() == null) {
            return "";
        }
        Path logs = Path.of(props.catalinaBase()).resolve("logs");
        if (!Files.isDirectory(logs)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String prefix : List.of("catalina.", "localhost.")) {
            Path latest = latest(logs, prefix);
            if (latest != null) {
                try {
                    List<String> all = Files.readAllLines(latest, StandardCharsets.UTF_8);
                    int from = Math.max(0, all.size() - lines);
                    sb.append("### ").append(latest.getFileName()).append('\n');
                    sb.append(String.join("\n", all.subList(from, all.size()))).append('\n');
                } catch (IOException e) {
                    sb.append("(로그 읽기 실패 ").append(latest.getFileName()).append(": ").append(e.getMessage()).append(")\n");
                }
            }
        }
        return sb.toString();
    }

    private String errorSummary() {
        String t = logTail(200);
        long n = t.lines().filter(l -> l.contains("SEVERE") || l.contains("ERROR")).count();
        return n == 0 ? "없음" : n + "줄의 SEVERE/ERROR 가 있습니다 — 'preview log 120' 으로 확인";
    }

    // ---------------------------------------------------------------- 내부

    /** 오프라인(-o)으로 먼저 돌려 원격 저장소 확인 시간을 아끼고, 의존성이 없어 실패하면 온라인으로 한 번 더. */
    private String runMaven(Path worktree) throws IOException, InterruptedException {
        try {
            return runMaven(worktree, true);
        } catch (IllegalStateException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("offline") || m.contains("Could not resolve") || m.contains("Cannot access") || m.contains("has not been downloaded")) {
                log.info("프리뷰 Maven 오프라인 빌드 실패 → 온라인 재시도");
                return runMaven(worktree, false);
            }
            throw e;
        }
    }

    private String runMaven(Path worktree, boolean offline) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("cmd");
        cmd.add("/c");
        cmd.add(Path.of(props.mavenHome()).resolve("bin").resolve("mvn.cmd").toString());
        cmd.add("-q");
        cmd.add("-B");
        if (offline) {
            cmd.add("-o");
        }
        cmd.add("-DskipTests");
        if (props.mavenArgs() != null && !props.mavenArgs().isBlank()) {
            for (String a : props.mavenArgs().strip().split("\\s+")) {
                cmd.add(a);
            }
        }
        cmd.add("package");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(worktree.toFile()).redirectErrorStream(true);
        pb.environment().put("JAVA_HOME", props.javaHome());
        pb.environment().put("MAVEN_HOME", props.mavenHome());
        pb.environment().put("PATH", Path.of(props.javaHome()).resolve("bin") + ";" + pb.environment().getOrDefault("PATH", ""));
        Process p = pb.start();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        long timeout = props.buildTimeoutMs() > 0 ? props.buildTimeoutMs() : 600000;
        if (!p.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            killTree(p);
            throw new IllegalStateException("Maven 빌드 타임아웃(" + timeout / 1000 + "s)");
        }
        String o = out.join();
        if (p.exitValue() != 0) {
            throw new IllegalStateException("Maven 빌드 실패(exit=" + p.exitValue() + ")\n" + tail(o, 60));
        }
        return o;
    }

    /** maven-war-plugin 이 만든 전개 디렉터리(target/<finalName>/WEB-INF/classes 가 있는 곳) 중 가장 최근 것. */
    private static Path findExploded(Path worktree) throws IOException {
        Path target = worktree.resolve("target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        // web.xml 은 없을 수 있다(Spring WebApplicationInitializer 방식) → WEB-INF/classes + WEB-INF/lib 로 판별
        try (Stream<Path> s = Files.list(target)) {
            return s.filter(d -> Files.isDirectory(d.resolve("WEB-INF").resolve("classes")) && Files.isDirectory(d.resolve("WEB-INF").resolve("lib")))
                    .max(Comparator.comparingLong(d -> d.resolve("WEB-INF").resolve("classes").toFile().lastModified())).orElse(null);
        }
    }

    /** robocopy /MIR 로 전개 디렉터리를 webapps 에 미러링(수백 MB, 수 초). exit code 8 이상이 실패. */
    private static void mirror(Path src, Path dest) throws IOException, InterruptedException {
        Files.createDirectories(dest.getParent());
        Process p = new ProcessBuilder("robocopy", src.toString(), dest.toString(), "/MIR", "/MT:16", "/R:2", "/W:2", "/NFL", "/NDL", "/NJH", "/NP")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("robocopy 타임아웃");
        }
        if (p.exitValue() >= 8) {
            throw new IllegalStateException("robocopy 실패(exit=" + p.exitValue() + "): " + tail(out, 10));
        }
    }

    /**
     * 번들 설정 덮어쓰기: WAR 는 deploy/standard/webapp 의 localhost 설정을 WEB-INF/classes 에 굽는다.
     * src/main/resources/<overlayDirs>/*.properties 를 그 위에 복사해 개발서버 DB/OpenSearch 를 보게 한다. 반환: 복사한 파일 목록.
     */
    private String overlayConfig(Path worktree, Path dest) throws IOException {
        if (props.overlayDirs() == null || props.overlayDirs().isBlank()) {
            return "(없음)";
        }
        List<String> done = new ArrayList<>();
        Path classes = dest.resolve("WEB-INF").resolve("classes");
        for (String d : props.overlayDirs().split(",")) {
            String dir = d.strip();
            if (dir.isEmpty()) {
                continue;
            }
            Path srcDir = worktree.resolve("src").resolve("main").resolve("resources").resolve(dir);
            if (!Files.isDirectory(srcDir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(srcDir)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    Path to = classes.resolve(dir).resolve(f.getFileName().toString());
                    Files.createDirectories(to.getParent());
                    Files.copy(f, to, StandardCopyOption.REPLACE_EXISTING);
                    done.add(dir + "/" + f.getFileName());
                }
            }
        }
        return done.isEmpty() ? "(없음)" : String.join(", ", done);
    }

    private void startTomcat(Path base) throws IOException {
        Path home = Path.of(props.catalinaHome());
        List<String> cmd = List.of("cmd", "/c", home.resolve("bin").resolve("catalina.bat").toString(), "run");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(base.toFile()).redirectErrorStream(true)
                .redirectOutput(base.resolve("logs").resolve("console.out").toFile());
        Files.createDirectories(base.resolve("logs"));
        pb.environment().put("JAVA_HOME", props.javaHome());
        pb.environment().put("JRE_HOME", props.javaHome());
        pb.environment().put("CATALINA_HOME", home.toString());
        pb.environment().put("CATALINA_BASE", base.toString());
        pb.environment().remove("JAVA_OPTS");
        tomcat = pb.start();
    }

    /**
     * 컨텍스트가 실제로 응답(2xx/3xx)할 때까지 대기. 404 는 WAR 전개/Spring 초기화 중(수 분)이므로 계속 기다린다.
     * 포트 바인드 실패(다른 Tomcat)는 로그에서 바로 감지해 실패로 돌린다. 실패 사유 문자열, 성공이면 null.
     */
    private String waitUntilUp(long timeoutMs, Path base, long logMark) {
        long deadline = System.currentTimeMillis() + (timeoutMs > 0 ? timeoutMs : 480000);
        String last = "응답 없음";
        while (System.currentTimeMillis() < deadline) {
            Process p = tomcat;
            if (p == null || !p.isAlive()) {
                return "Tomcat 프로세스가 종료됨(exit=" + (p == null ? "?" : p.exitValue()) + ")";
            }
            String fresh = logSince(base, logMark);
            if (fresh.contains("Address already in use") || fresh.contains("BindException")) {
                return "포트 " + props.port() + " 바인드 실패(다른 프로세스가 사용 중)";
            }
            if (fresh.contains("startup failed due to previous errors")) {
                return "웹앱 컨텍스트 기동 실패(로그 확인)";
            }
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(props.url()).toURL().openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(2000);
                c.setReadTimeout(5000);
                int code = c.getResponseCode();
                c.disconnect();
                if (code >= 200 && code < 400) {
                    return null;
                }
                last = "HTTP " + code + (code == 404 ? "(컨텍스트 초기화 중)" : "");
            } catch (IOException e) {
                last = e.getClass().getSimpleName();
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "인터럽트";
            }
        }
        return "타임아웃(" + (timeoutMs / 1000) + "s, 마지막 " + last + ")";
    }

    /** 포트를 쥔 프로세스(PID) — netstat 으로 확인. 없으면 null. */
    static String portHolder(int port) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "netstat -ano -p TCP").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            for (String line : out.split("\\r?\\n")) {
                if (line.contains(":" + port + " ") && line.contains("LISTENING")) {
                    String[] parts = line.trim().split("\\s+");
                    return "PID " + parts[parts.length - 1];
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static long logSize(Path base) {
        Path f = latest(base.resolve("logs"), "catalina.");
        return f == null ? 0 : f.toFile().length();
    }

    private static String logSince(Path base, long mark) {
        Path f = latest(base.resolve("logs"), "catalina.");
        if (f == null) {
            return "";
        }
        try {
            byte[] all = Files.readAllBytes(f);
            int from = (int) Math.min(mark, all.length);
            return new String(all, from, all.length - from, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void stopInternal() {
        Process p = tomcat;
        tomcat = null;
        current = null;
        if (p == null) {
            return;
        }
        if (p.isAlive()) {
            killTree(p);
            try {
                p.waitFor(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void killTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignore) {
            // 권한 문제 등은 무시
        }
        p.destroyForcibly();
    }

    private static Path latest(Path dir, String prefix) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().startsWith(prefix))
                    .max(Comparator.comparingLong(f -> f.toFile().lastModified())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignore) {
                    // 잠긴 파일은 Tomcat 이 내려간 뒤 다음 배포에서 다시 시도
                }
            });
        } catch (IOException e) {
            log.debug("삭제 실패 {}: {}", dir, e.toString());
        }
    }

    private static String tail(String s, int lines) {
        if (s == null) {
            return "";
        }
        List<String> all = s.lines().toList();
        int from = Math.max(0, all.size() - lines);
        return String.join("\n", all.subList(from, all.size()));
    }

    @PreDestroy
    void shutdown() {
        stopInternal();
    }
}
