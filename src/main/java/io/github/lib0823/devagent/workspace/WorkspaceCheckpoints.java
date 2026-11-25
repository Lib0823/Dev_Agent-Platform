package io.github.lib0823.devagent.workspace;

import java.io.IOException;
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
import java.util.stream.Stream;

/**
 * 차례별 되돌리기 지점: 요청 한 건이 바꾼 파일의 "요청 전 내용"과 "요청 후 내용"을 플랫폼 작업 디렉터리에 따로 보관한다.
 * git 저장소가 아닌 폴더(논문·문서·실험)에서도 그 차례의 변경분을 보고, 그 차례만 되돌릴 수 있게 하려는 것이다.
 * <pre>
 *   &lt;base&gt;/&lt;작업 공간 id&gt;/mirror/…          폴더의 사본(마지막으로 맞춘 상태). 요청 직전에 지금 폴더와 맞춘다(달라진 파일만 복사)
 *   &lt;base&gt;/&lt;작업 공간 id&gt;/t&lt;차례 id&gt;/before/…  그 차례가 고치거나 지운 파일의 요청 전 내용
 *   &lt;base&gt;/&lt;작업 공간 id&gt;/t&lt;차례 id&gt;/after/…   그 차례가 만들거나 고친 파일의 요청 후 내용(이후에 또 바뀌었는지 확인·변경분 비교용)
 *   &lt;base&gt;/&lt;작업 공간 id&gt;/t&lt;차례 id&gt;/manifest   줄마다 "A|M|D 경로"(잘리지 않은 전체 목록)
 * </pre>
 * 사본을 통째로 다시 뜨지 않는 이유: 요청마다 수천 개 파일을 복사하면 시작이 늦어진다. 사본은 한 번 만들고 달라진 것만 따라간다.
 * 큰 폴더는 다루지 않는다({@link #MAX_FILES}·{@link #MAX_TOTAL_BYTES} 초과면 되돌리기 지점 없이 진행) — 큰 파일({@link #MAX_FILE_BYTES} 초과)은
 * 보관하지 않고 되돌릴 때 건너뛴다. 의존성·빌드 폴더는 스냅샷과 같은 기준으로 보지 않는다.
 */
final class WorkspaceCheckpoints {

    static final long MAX_FILE_BYTES = 2_000_000L;
    static final int MAX_FILES = 8_000;
    static final long MAX_TOTAL_BYTES = 300_000_000L;
    /** 작업 공간마다 남겨 두는 되돌리기 지점 수(오래된 것부터 지운다). */
    static final int KEEP_TURNS = 30;

    private final Path base;

    WorkspaceCheckpoints(Path base) {
        this.base = base.toAbsolutePath().normalize();
    }

    private Path wsDir(long workspaceId) {
        return base.resolve(Long.toString(workspaceId));
    }

    private Path mirror(long workspaceId) {
        return wsDir(workspaceId).resolve("mirror");
    }

    private Path turnDir(long workspaceId, long turnId) {
        return wsDir(workspaceId).resolve("t" + turnId);
    }

    /** 지점이 있는지의 기억(대화 조회가 2초마다 묻는다 — 파일을 매번 확인하지 않게). 지점이 생기거나 지워지면 비운다. */
    private final Map<Long, Boolean> known = new java.util.concurrent.ConcurrentHashMap<>();

    boolean has(long workspaceId, long turnId) {
        return known.computeIfAbsent(turnId, k -> Files.isRegularFile(turnDir(workspaceId, turnId).resolve("manifest")));
    }

    // ---------------------------------------------------------------- 요청 직전: 사본을 지금 폴더와 맞춘다

    /**
     * 사본을 지금 폴더({@code snap} = 경로 → 수정 시각·크기)와 맞춘다. 달라진 파일만 복사하고 없어진 파일은 사본에서도 지운다.
     *
     * @return 맞췄으면 true. 폴더가 너무 크거나 복사에 실패하면 false(이 차례는 되돌리기 지점 없이 진행한다)
     */
    boolean sync(long workspaceId, Path root, Map<String, long[]> snap) {
        if (snap == null) {
            return false;
        }
        long total = 0;
        int count = 0;
        for (long[] v : snap.values()) {
            if (v[1] <= MAX_FILE_BYTES) {
                count++;
                total += v[1];
            }
        }
        Path mir = mirror(workspaceId);
        if (count > MAX_FILES || total > MAX_TOTAL_BYTES) {
            deleteTree(mir);                          // 예전에 뜬 사본이 남아 있으면 어긋난 채로 쓰이지 않게 지운다
            return false;
        }
        try {
            Files.createDirectories(mir);
            Map<String, long[]> have = WorkspaceFiles.snapshot(mir);
            if (have == null) {
                have = new HashMap<>();
            }
            Path src = root.toAbsolutePath().normalize();
            for (Map.Entry<String, long[]> e : snap.entrySet()) {
                long[] v = e.getValue();
                long[] h = have.remove(e.getKey());
                if (v[1] > MAX_FILE_BYTES) {
                    if (h != null) {
                        Files.deleteIfExists(mir.resolve(e.getKey()));
                    }
                    continue;
                }
                if (h == null || h[0] != v[0] || h[1] != v[1]) {
                    copy(src.resolve(e.getKey()), mir.resolve(e.getKey()));
                }
            }
            for (String gone : have.keySet()) {
                Files.deleteIfExists(mir.resolve(gone));
            }
            return true;
        } catch (IOException | RuntimeException e) {
            deleteTree(mir);
            return false;
        }
    }

    // ---------------------------------------------------------------- 요청 직후: 바뀐 파일의 전·후 내용을 떼어 둔다

    /**
     * 이번 차례에 바뀐 파일({@code changes}: "A|M|D 경로")의 요청 전 내용(사본에서 옮김)과 요청 후 내용을 보관하고, 사본을 지금 상태로 맞춘다.
     * {@link #sync} 가 true 를 돌려준 차례에만 부른다.
     */
    void capture(long workspaceId, long turnId, Path root, List<String> changes) {
        known.clear();
        List<String[]> items = parse(changes);
        if (items.isEmpty()) {
            return;
        }
        Path mir = mirror(workspaceId);
        Path dir = turnDir(workspaceId, turnId);
        Path src = root.toAbsolutePath().normalize();
        try {
            deleteTree(dir);
            Files.createDirectories(dir);
            StringBuilder manifest = new StringBuilder();
            for (String[] it : items) {
                String kind = it[0];
                String rel = it[1];
                Path m = inside(mir, rel);
                Path cur = inside(src, rel);
                if (m == null || cur == null) {
                    continue;
                }
                if (!"A".equals(kind) && Files.isRegularFile(m)) {
                    Path b = dir.resolve("before").resolve(rel);
                    Files.createDirectories(b.getParent());
                    Files.move(m, b, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!"D".equals(kind) && Files.isRegularFile(cur) && Files.size(cur) <= MAX_FILE_BYTES) {
                    Path a = dir.resolve("after").resolve(rel);
                    copy(cur, a);
                    copy(a, m);
                } else {
                    Files.deleteIfExists(m);
                }
                manifest.append(kind).append(' ').append(rel).append('\n');
            }
            Files.writeString(dir.resolve("manifest"), manifest.toString(), StandardCharsets.UTF_8);
            prune(workspaceId);
            known.clear();
        } catch (IOException | RuntimeException e) {
            deleteTree(dir);                          // 반쯤 뜬 지점으로 되돌리게 두지 않는다
            deleteTree(mir);                          // 사본이 어긋났을 수 있다 — 다음 요청 때 다시 뜬다
        }
    }

    // ---------------------------------------------------------------- 되돌리기

    /** 되돌린 결과: 되돌린 파일과 건너뛴 파일(사유 포함). */
    record Reverted(List<String> restored, List<String> skipped) {
    }

    /**
     * 그 차례가 바꾼 파일을 요청 전 상태로 되돌린다. 안전을 위해 <b>그 차례가 남긴 상태 그대로인 파일만</b> 되돌린다 —
     * 이후에 또 바뀐 파일(다른 요청·사용자의 손 편집)은 덮어쓰지 않고 건너뛴다.
     */
    Reverted revert(long workspaceId, long turnId, Path root) {
        Path dir = turnDir(workspaceId, turnId);
        List<String[]> items = manifest(workspaceId, turnId);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("이 요청의 되돌리기 지점이 없습니다(오래되어 지워졌거나 폴더가 커서 만들지 않았습니다).");
        }
        Path src = root.toAbsolutePath().normalize();
        List<String> restored = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String[] it : items) {
            String kind = it[0];
            String rel = it[1];
            Path cur = inside(src, rel);
            Path before = dir.resolve("before").resolve(rel);
            Path after = dir.resolve("after").resolve(rel);
            try {
                if (cur == null) {
                    skipped.add(rel + " (경로를 열 수 없음)");
                } else if ("A".equals(kind)) {
                    if (!Files.exists(cur)) {
                        restored.add(rel);                                  // 이미 없다
                    } else if (!Files.isRegularFile(after)) {
                        skipped.add(rel + " (큰 파일이라 보관하지 않음)");
                    } else if (same(cur, after)) {
                        Files.delete(cur);
                        restored.add(rel);
                    } else {
                        skipped.add(rel + " (이후에 다시 바뀜)");
                    }
                } else if (!Files.isRegularFile(before)) {
                    skipped.add(rel + " (큰 파일이라 보관하지 않음)");
                } else if ("D".equals(kind)) {
                    if (Files.exists(cur)) {
                        skipped.add(rel + " (같은 이름의 파일이 다시 생김)");
                    } else {
                        copy(before, cur);
                        restored.add(rel);
                    }
                } else if (Files.isRegularFile(cur) && Files.isRegularFile(after) && same(cur, after)) {
                    copy(before, cur);
                    restored.add(rel);
                } else if (Files.isRegularFile(cur) && same(cur, before)) {
                    restored.add(rel);                                      // 이미 요청 전 내용이다
                } else {
                    skipped.add(rel + (Files.exists(cur) ? " (이후에 다시 바뀜)" : " (이후에 지워짐)"));
                }
            } catch (IOException | RuntimeException e) {
                skipped.add(rel + " (오류: " + e.getMessage() + ")");
            }
        }
        return new Reverted(restored, skipped);
    }

    // ---------------------------------------------------------------- 조회

    /** 그 차례의 변경 목록(전체): {종류, 경로}. 지점이 없으면 빈 목록. */
    List<String[]> manifest(long workspaceId, long turnId) {
        Path f = turnDir(workspaceId, turnId).resolve("manifest");
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            return parse(Files.readAllLines(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 그 차례에서 이 파일의 {요청 전 파일, 요청 후 파일}. 없는 쪽은 null(새 파일은 전이 없고 지운 파일은 후가 없다).
     * 목록에 없는 경로면 null — 경로를 그대로 믿지 않고 목록과 맞춰 본다.
     */
    Path[] sides(long workspaceId, long turnId, String rel) {
        for (String[] it : manifest(workspaceId, turnId)) {
            if (it[1].equals(rel)) {
                Path dir = turnDir(workspaceId, turnId);
                Path b = dir.resolve("before").resolve(rel);
                Path a = dir.resolve("after").resolve(rel);
                return new Path[]{Files.isRegularFile(b) ? b : null, Files.isRegularFile(a) ? a : null};
            }
        }
        return null;
    }

    /** 작업 공간 등록 해제: 사본과 지점을 모두 지운다. */
    void drop(long workspaceId) {
        known.clear();
        deleteTree(wsDir(workspaceId));
    }

    // ---------------------------------------------------------------- 내부

    private void prune(long workspaceId) throws IOException {
        List<Long> ids = new ArrayList<>();
        try (Stream<Path> s = Files.list(wsDir(workspaceId))) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.startsWith("t") && n.length() > 1 && n.chars().skip(1).allMatch(Character::isDigit)) {
                    ids.add(Long.parseLong(n.substring(1)));
                }
            });
        }
        ids.sort(Comparator.reverseOrder());
        for (int i = KEEP_TURNS; i < ids.size(); i++) {
            deleteTree(turnDir(workspaceId, ids.get(i)));
        }
    }

    private static List<String[]> parse(List<String> lines) {
        List<String[]> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String l : lines) {
            if (l.length() > 2 && l.charAt(1) == ' ' && "AMD".indexOf(l.charAt(0)) >= 0 && seen.put(l.substring(2), true) == null) {
                out.add(new String[]{l.substring(0, 1), l.substring(2)});
            }
        }
        return out;
    }

    /** 루트 안의 경로(벗어나면 null). */
    private static Path inside(Path root, String rel) {
        try {
            Path p = root.resolve(rel).normalize();
            return p.startsWith(root) && !p.equals(root) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static boolean same(Path a, Path b) throws IOException {
        return Files.size(a) == Files.size(b) && Files.mismatch(a, b) == -1;
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // 남은 것은 다음 정리 때 지운다
        }
    }
}
