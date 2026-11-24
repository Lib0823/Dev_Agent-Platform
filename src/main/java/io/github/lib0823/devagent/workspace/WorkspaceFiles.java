package io.github.lib0823.devagent.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 작업 공간 폴더 보기(읽기 전용): 폴더 한 단계 목록, 파일 내용, git 변경분, 그리고 요청 전후의 파일 스냅샷 비교(이번 차례에 바뀐 파일).
 * 모든 경로는 작업 공간 루트 기준 상대 경로('/' 구분)로 받고, {@link #resolveInside} 가 루트 밖으로 나가는 경로(.. · 절대 경로 · 바깥을 가리키는 링크)를 막는다.
 */
final class WorkspaceFiles {

    /** 스냅샷에서 건너뛰는 폴더: 저장소 내부·의존성·빌드 산출물(파일이 많고 사람이 보는 변경이 아니다). 폴더 목록에서는 .git 만 숨긴다. */
    static final Set<String> SNAPSHOT_SKIP = Set.of(".git", "node_modules", ".venv", "venv", "__pycache__", ".gradle", ".idea",
            "target", "build", "dist", ".mypy_cache", ".pytest_cache", ".ipynb_checkpoints");
    static final int MAX_SNAPSHOT_FILES = 30_000;
    static final int MAX_LIST = 1_000;
    static final int MAX_CHANGED = 200;
    static final long MAX_TEXT_BYTES = 1_500_000L;
    static final long MAX_IMAGE_BYTES = 20_000_000L;
    private static final int MAX_DIFF_CHARS = 400_000;
    private static final long GIT_TIMEOUT_MS = 15_000L;

    private WorkspaceFiles() {
    }

    // ---------------------------------------------------------------- 경로

    /** 루트 안의 경로로 풀어 준다. 루트를 벗어나면 IllegalArgumentException. 빈 값은 루트 자신. */
    static Path resolveInside(Path root, String rel) {
        String r = rel == null ? "" : rel.strip().replace('\\', '/');
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        Path base = root.toAbsolutePath().normalize();
        Path p;
        try {
            Path sub = Path.of(r);
            if (sub.isAbsolute() || r.contains(":")) {
                throw new IllegalArgumentException("작업 공간 안의 상대 경로만 볼 수 있습니다.");
            }
            p = base.resolve(sub).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("경로를 읽을 수 없습니다: " + rel);
        }
        if (!p.startsWith(base)) {
            throw new IllegalArgumentException("작업 공간 밖의 경로입니다.");
        }
        if (Files.exists(p)) {
            try {
                if (!p.toRealPath().startsWith(base.toRealPath())) {      // 바깥을 가리키는 심볼릭 링크·정션
                    throw new IllegalArgumentException("작업 공간 밖을 가리키는 링크입니다.");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("경로를 열 수 없습니다: " + rel);
            }
        }
        return p;
    }

    static String relative(Path root, Path p) {
        return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // ---------------------------------------------------------------- 폴더 찾아보기(작업 공간을 등록할 때: 이 PC 의 폴더를 고른다)

    /**
     * 등록할 폴더를 고르기 위한 한 단계 목록: 하위 폴더 이름만 보여 준다(파일 내용·이름은 싣지 않는다). 숨김 폴더(.으로 시작·$ 로 시작)는 뺀다.
     * {@code dir} 이 비었거나 없는 경로면 {@code start}(기본 위치)에서 시작한다.
     *
     * @return dir(지금 위치) · parent(없으면 null) · crumbs(위치를 이루는 조각: name·path) · items(name·path·git) · roots(드라이브) · git · claudeMd
     */
    static Map<String, Object> browse(String dir, Path start) {
        Path p = null;
        String d = dir == null ? "" : dir.strip();
        if (d.length() >= 2 && d.startsWith("\"") && d.endsWith("\"")) {
            d = d.substring(1, d.length() - 1).strip();
        }
        if (!d.isEmpty()) {
            try {
                Path c = Path.of(d);
                if (c.isAbsolute() && Files.isDirectory(c)) {
                    p = c.toRealPath();
                }
            } catch (InvalidPathException | IOException e) {
                p = null;
            }
        }
        boolean missing = !d.isEmpty() && p == null;
        if (p == null) {
            try {
                p = start.toRealPath();
            } catch (IOException | RuntimeException e) {
                p = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        boolean more = false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, Files::isDirectory)) {
            for (Path c : ds) {
                String name = c.getFileName().toString();
                if (name.startsWith(".") || name.startsWith("$")) {
                    continue;
                }
                try {
                    if (Files.isHidden(c)) {
                        continue;
                    }
                } catch (IOException e) {
                    continue;
                }
                if (items.size() >= MAX_LIST) {
                    more = true;
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("path", c.toString());
                m.put("git", Files.exists(c.resolve(".git")));
                items.add(m);
            }
        } catch (IOException | RuntimeException e) {
            // 읽을 수 없는 폴더(권한): 빈 목록으로 보여 준다
        }
        items.sort(Comparator.comparing(m -> ((String) m.get("name")).toLowerCase(Locale.ROOT)));
        List<Map<String, Object>> crumbs = new ArrayList<>();
        for (Path c = p; c != null; c = c.getParent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getFileName() == null ? c.toString() : c.getFileName().toString());
            m.put("path", c.toString());
            crumbs.add(0, m);
        }
        List<String> roots = new ArrayList<>();
        for (Path r : p.getFileSystem().getRootDirectories()) {
            roots.add(r.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", p.toString());
        out.put("parent", p.getParent() == null ? null : p.getParent().toString());
        out.put("crumbs", crumbs);
        out.put("items", items);
        out.put("more", more);
        out.put("roots", roots);
        out.put("git", Files.exists(p.resolve(".git")));
        out.put("claudeMd", Files.isRegularFile(p.resolve("CLAUDE.md")));
        if (missing) {
            out.put("missing", d);
        }
        return out;
    }

    // ---------------------------------------------------------------- 폴더 목록(한 단계)

    /** 폴더 한 단계: 폴더 먼저, 이름순. {@code .git} 은 숨긴다. 항목 = name · path · dir · size · mtime. */
    static Map<String, Object> list(Path root, String rel) {
        Path dir = resolveInside(root, rel);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("폴더가 없습니다: " + rel);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        boolean more = false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (".git".equals(name)) {
                    continue;
                }
                if (items.size() >= MAX_LIST) {
                    more = true;
                    break;
                }
                BasicFileAttributes a;
                try {
                    a = Files.readAttributes(p, BasicFileAttributes.class);
                } catch (IOException e) {
                    continue;                                             // 깨진 링크 등
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("path", relative(root, p));
                m.put("dir", a.isDirectory());
                m.put("size", a.isDirectory() ? 0 : a.size());
                m.put("mtime", a.lastModifiedTime().toMillis());
                items.add(m);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("폴더를 읽을 수 없습니다: " + rel);
        }
        items.sort(Comparator.<Map<String, Object>, Boolean>comparing(m -> !(Boolean) m.get("dir"))
                .thenComparing(m -> ((String) m.get("name")).toLowerCase(Locale.ROOT)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", relative(root, dir));
        out.put("items", items);
        out.put("more", more);
        return out;
    }

    // ---------------------------------------------------------------- 파일 내용

    /** 화면에 그림으로 보여 줄 수 있는 형식의 MIME(아니면 null). svg 는 스크립트를 담을 수 있어 그림으로 내보내지 않는다(글로 본다). */
    static String imageType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }

    /**
     * 파일 내용. 글이면 {@code text}, 그림이면 {@code image=true}(바이트는 raw 로), 너무 크면 {@code tooBig}, 글이 아니면 {@code binary}.
     * {@code ifMtime} 이 지금 수정 시각과 같으면 내용을 싣지 않고 {@code unchanged=true}(화면의 주기 갱신용).
     */
    static Map<String, Object> read(Path root, String rel, long ifMtime) {
        Path p = resolveInside(root, rel);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("파일이 없습니다: " + rel);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            long mtime = a.lastModifiedTime().toMillis();
            out.put("path", relative(root, p));
            out.put("name", p.getFileName().toString());
            out.put("size", a.size());
            out.put("mtime", mtime);
            if (ifMtime > 0 && ifMtime == mtime) {
                out.put("unchanged", true);
                return out;
            }
            if (imageType(p.getFileName().toString()) != null) {
                out.put("image", true);
                return out;
            }
            if (a.size() > MAX_TEXT_BYTES) {
                out.put("tooBig", true);
                return out;
            }
            byte[] bytes = Files.readAllBytes(p);
            if (isBinary(bytes)) {
                out.put("binary", true);
                return out;
            }
            out.put("text", new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("파일을 읽을 수 없습니다: " + rel);
        }
        return out;
    }

    /** 앞머리에 NUL 바이트가 있으면 글이 아니다. */
    static boolean isBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 8000);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 스냅샷(이번 차례에 바뀐 파일)

    /**
     * 폴더 전체의 (상대 경로 → 수정 시각·크기). 파일이 {@link #MAX_SNAPSHOT_FILES} 를 넘거나 읽지 못하면 null(변경 표시를 생략한다).
     * 파일마다 stat 을 따로 부르지 않도록 walkFileTree 가 주는 속성을 쓴다(파일 수가 많으면 stat 을 따로 부르는 쪽이 수십 배 느리다).
     */
    static Map<String, long[]> snapshot(Path root) {
        Path base = root.toAbsolutePath().normalize();
        Map<String, long[]> out = new HashMap<>();
        boolean[] over = {false};
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(base) && SNAPSHOT_SKIP.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        if (out.size() >= MAX_SNAPSHOT_FILES) {
                            over[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        out.put(base.relativize(file).toString().replace('\\', '/'), new long[]{attrs.lastModifiedTime().toMillis(), attrs.size()});
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return over[0] ? null : out;
    }

    /** 두 스냅샷의 차이: "A 경로"(새 파일) · "M 경로"(수정) · "D 경로"(삭제), 경로순, 최대 {@link #MAX_CHANGED} 줄. */
    static List<String> changes(Map<String, long[]> before, Map<String, long[]> after) {
        if (before == null || after == null) {
            return List.of();
        }
        TreeSet<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        List<String> out = new ArrayList<>();
        for (String n : names) {
            long[] b = before.get(n);
            long[] a = after.get(n);
            String kind = b == null ? "A" : a == null ? "D" : (a[0] != b[0] || a[1] != b[1]) ? "M" : null;
            if (kind != null) {
                if (out.size() >= MAX_CHANGED) {
                    out.add("… 그 밖에 더 있음");
                    break;
                }
                out.add(kind + " " + n);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- git 변경분

    /**
     * 마지막 커밋 대비 이 파일의 변경분(작업트리 diff). git 저장소가 아니거나 커밋이 없으면 {@code git=false}.
     * 커밋된 적 없는 새 파일은 diff 가 비므로 {@code untracked=true} 로 알린다.
     */
    static Map<String, Object> gitDiff(Path root, String rel) {
        Path p = resolveInside(root, rel);
        String r = relative(root, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", r);
        try {
            // git 을 한 번만 부른다(Windows 에서는 git 실행이 건당 1초를 넘기도 한다): 저장소가 아니거나 커밋이 없으면 이 diff 가 실패한다
            String[] d = git(root, "-c", "core.quotepath=false", "diff", "--no-color", "HEAD", "--", r);
            if (d == null) {
                out.put("git", false);
                return out;
            }
            out.put("git", true);
            String diff = d[0];
            if (diff.isBlank()) {
                String[] st = git(root, "-c", "core.quotepath=false", "status", "--porcelain", "--", r);
                out.put("untracked", st != null && st[0].startsWith("??"));
            }
            if (diff.length() > MAX_DIFF_CHARS) {
                diff = diff.substring(0, MAX_DIFF_CHARS);
                out.put("truncated", true);
            }
            out.put("diff", diff);
        } catch (RuntimeException e) {
            out.put("git", false);
        }
        return out;
    }

    /** git 실행. 성공하면 {표준 출력}, 실패(저장소 아님·git 없음·시간 초과)면 null. */
    private static String[] git(Path root, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.longpaths=true"));
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(false);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_OPTIONAL_LOCKS", "0");            // 조회가 저장소의 index.lock 을 잡지 않게(세션이 git 을 쓰는 중일 수 있다)
            Process p = pb.start();
            p.getOutputStream().close();
            CompletableFuture<String> out = slurp(p.getInputStream());
            CompletableFuture<String> err = slurp(p.getErrorStream());
            if (!p.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            err.join();
            return p.exitValue() == 0 ? new String[]{out.join()} : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static CompletableFuture<String> slurp(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}
