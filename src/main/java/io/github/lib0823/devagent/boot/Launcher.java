package io.github.lib0823.devagent.boot;

import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 실행 파일 모드({@code --launch}): 앱을 자식 JVM 으로 띄우고 지켜보는 작은 감시자 + 트레이 아이콘.
 * Windows 의 {@code run.cmd}(감시 루프)를 OS 중립으로 옮긴 것으로, 패키지(Dev Agent.exe / .app)와 {@code dev-agent.sh} 가 이 모드로 시작한다.
 * <ul>
 *   <li>홈: {@code DEV_AGENT_HOME} 또는 {@code ~/.dev-agent} — 설정(.env)·로그·내장 DB·작업 디렉터리가 모두 여기 놓인다.</li>
 *   <li>이미 떠 있으면 브라우저만 열고 끝난다(중복 실행 방지).</li>
 *   <li>자식이 비정상 종료하면 다시 띄운다. 종료 코드 {@value #EXIT_RESTART} 은 화면의 "재시작" 요청.</li>
 * </ul>
 * Spring 을 쓰지 않는다(이 JVM 은 가볍게 유지: 힙 32MB 로 충분).
 */
public final class Launcher {

    /** 앱이 "재시작해 달라"며 끝날 때의 종료 코드. */
    public static final int EXIT_RESTART = 10;
    /** 자식에게 "감시자 아래서 돈다"고 알리는 환경변수. */
    public static final String ENV_FLAG = "DEV_AGENT_LAUNCHER";
    /** 자동 실행 레지스트리 값 이름. */
    private static final String AUTOSTART_NAME = "Dev Agent";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final Path home;
    private final Path jar;
    private final int port;
    private volatile Process child;
    private volatile boolean quitting;
    private TrayIcon tray;

    private Launcher(Path home, Path jar, int port) {
        this.home = home;
        this.jar = jar;
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        String h = System.getenv("DEV_AGENT_HOME");
        Path home = (h == null || h.isBlank() ? Path.of(System.getProperty("user.home"), ".dev-agent") : Path.of(h)).toAbsolutePath();
        Files.createDirectories(home.resolve("logs"));
        Path jar = selfJar();
        syncScripts(jar.getParent(), home);
        Map<String, String> env = Bootstrap.readEnv(home.resolve(".env"));
        int port = 8777;
        try {
            String p = Bootstrap.val(env, "ADMIN_PORT");
            port = Bootstrap.blank(p) ? port : Integer.parseInt(p.strip());
        } catch (NumberFormatException ignored) {
            // 기본 포트
        }
        Launcher l = new Launcher(home, jar, port);
        if (l.alive()) {
            l.openBrowser();          // 이미 실행 중 — 화면만 연다
            System.exit(0);
        }
        l.run();
    }

    private void run() throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopChild, "launcher-stop"));
        installTray();
        Deque<Long> starts = new ArrayDeque<>();
        boolean first = true;
        while (!quitting) {
            long now = System.currentTimeMillis();
            starts.addLast(now);
            while (!starts.isEmpty() && now - starts.peekFirst() > 180_000) {
                starts.removeFirst();
            }
            if (starts.size() > 5) {
                notifyUser("앱이 계속 종료됩니다. 로그를 확인하세요: " + home.resolve("logs"), true);
                break;
            }
            child = startChild();
            if (first) {
                first = false;
                Thread t = new Thread(() -> {
                    if (waitUp(180)) {
                        openBrowser();
                    }
                }, "launcher-open");
                t.setDaemon(true);
                t.start();
            }
            int code = child.waitFor();
            if (quitting || code == 0) {
                break;                    // 트레이 "종료" 또는 화면·API 의 정상 종료 요청
            }
            if (code != EXIT_RESTART) {
                Thread.sleep(3000);       // 비정상 종료 — 잠깐 쉬고 다시
            }
        }
        if (tray != null) {
            SystemTray.getSystemTray().remove(tray);
        }
        System.exit(0);
    }

    private Process startChild() throws IOException {
        boolean win = isWindows();
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path java = win && Files.isRegularFile(bin.resolve("javaw.exe")) ? bin.resolve("javaw.exe") : bin.resolve(win ? "java.exe" : "java");
        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        String opts = System.getenv("DEV_AGENT_JAVA_OPTS");
        // 로컬 1인용: 직렬 GC, 작은 힙, C1 컴파일러만(run.cmd 의 BOT_JAVA_OPTS 와 같은 값)
        for (String o : (opts == null || opts.isBlank() ? "-XX:+UseSerialGC -Xms32m -Xmx512m -XX:TieredStopAtLevel=1" : opts).trim().split("\\s+")) {
            cmd.add(o);
        }
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-jar");
        cmd.add(jar.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(home.toFile()).redirectErrorStream(true)
                .redirectOutput(home.resolve("logs").resolve("console.log").toFile());
        pb.environment().put(ENV_FLAG, "1");
        return pb.start();
    }

    /** 정상 종료를 요청하고(내장 DB 까지 닫히도록) 안 끝나면 프로세스 트리를 강제 종료한다. */
    private void stopChild() {
        quitting = true;
        Process p = child;
        if (p == null || !p.isAlive()) {
            return;
        }
        try {
            post("/api/system/shutdown");
            if (p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                return;
            }
        } catch (Exception ignored) {
            // 아래에서 강제 종료
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    // ---------------------------------------------------------------- 트레이

    private void installTray() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return;       // 트레이가 없는 환경(서버·일부 리눅스): 감시만 한다. Ctrl+C 로 종료
        }
        try {
            PopupMenu menu = new PopupMenu();
            menu.add(item("Dev Agent 열기", this::openBrowser));
            menu.add(item("재시작", () -> {
                String r = post("/api/system/restart");
                if (r == null || !r.contains("\"ok\":true")) {
                    notifyUser("지금은 재시작할 수 없습니다(작업 중인 세션이 있거나 응답 없음).", false);
                }
            }));
            menu.add(item("설정 폴더 열기", () -> open(home.toFile())));
            // 패키지 실행 파일(jpackage 가 경로를 알려 준다)로 떴을 때만: 로그인 시 자동 실행 토글
            String exe = System.getProperty("jpackage.app-path");
            if (exe != null && Autostart.supported()) {
                CheckboxMenuItem auto = new CheckboxMenuItem("로그인 시 자동 실행", Autostart.isEnabled(AUTOSTART_NAME));
                auto.addItemListener(e -> {
                    boolean ok = auto.getState() ? Autostart.enable(AUTOSTART_NAME, exe) : Autostart.disable(AUTOSTART_NAME);
                    if (!ok) {
                        auto.setState(Autostart.isEnabled(AUTOSTART_NAME));
                        notifyUser("자동 실행 설정을 바꾸지 못했습니다.", true);
                    }
                });
                menu.add(auto);
            }
            menu.addSeparator();
            menu.add(item("종료", () -> {
                stopChild();
                System.exit(0);
            }));
            tray = new TrayIcon(icon(), "Dev Agent", menu);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> openBrowser());
            SystemTray.getSystemTray().add(tray);
        } catch (Exception e) {
            tray = null;
        }
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem m = new MenuItem(label);
        m.addActionListener(e -> {
            Thread t = new Thread(action, "launcher-menu");   // 메뉴 동작이 AWT 스레드를 붙잡지 않게
            t.setDaemon(true);
            t.start();
        });
        return m;
    }

    private static BufferedImage icon() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x4F, 0x46, 0xE5));
        g.fillRoundRect(2, 2, 60, 60, 18, 18);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        int w = g.getFontMetrics().stringWidth("D");
        g.drawString("D", (64 - w) / 2, 47);
        g.dispose();
        return img;
    }

    private void notifyUser(String msg, boolean error) {
        if (tray != null) {
            tray.displayMessage("Dev Agent", msg, error ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.INFO);
        } else {
            System.err.println("[launcher] " + msg);
        }
    }

    // ---------------------------------------------------------------- 도우미

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private boolean alive() {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/api/system/status"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitUp(int seconds) {
        for (int i = 0; i < seconds && !quitting; i++) {
            if (alive()) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private String post(String path) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return null;
        }
    }

    private void openBrowser() {
        String url = base() + "/";
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // 아래 명령으로 다시 시도
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            List<String> cmd = isWindows() ? List.of("cmd", "/c", "start", "", url) : os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url);
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            System.err.println("[launcher] 브라우저를 열지 못했습니다. 직접 여세요: " + url);
        }
    }

    private static void open(File f) {
        try {
            Desktop.getDesktop().open(f);
        } catch (Exception ignored) {
            // 열지 못해도 동작에는 영향 없음
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 실행 중인 jar({@code -jar} 로 준 파일). 패키지에서는 {@code <앱>/app/dev-agent.jar}. */
    private static Path selfJar() {
        for (String e : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (e.toLowerCase().endsWith(".jar")) {
                return Path.of(e).toAbsolutePath();
            }
        }
        throw new IllegalStateException("--launch 는 jar 로 실행할 때만 쓸 수 있습니다(java -jar dev-agent.jar --launch).");
    }

    /** jar 옆의 {@code scripts/}(에이전트가 쓰는 preview·jscheck·jspcheck)를 홈으로 복사한다 — 앱이 홈 기준 상대 경로로 찾는다. */
    private static void syncScripts(Path appDir, Path home) {
        Path src = appDir.resolve("scripts");
        if (!Files.isDirectory(src) || appDir.equals(home)) {
            return;
        }
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(home.resolve("scripts").resolve(src.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path to = home.resolve("scripts").resolve(src.relativize(file).toString());
                    if (!Files.exists(to) || Files.size(to) != attrs.size()
                            || Files.getLastModifiedTime(to).toMillis() < attrs.lastModifiedTime().toMillis()) {
                        Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[launcher] scripts 복사 실패: " + e.getMessage());
        }
    }
}
