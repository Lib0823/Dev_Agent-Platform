package io.github.lib0823.devagent.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCheckpointsTest {

    @TempDir
    Path tmp;

    /** 수정 시각을 확실히 다르게(같은 밀리초 안의 수정이 스냅샷에서 빠지지 않게). */
    private static void write(Path p, String text, long at) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        Files.setLastModifiedTime(p, FileTime.fromMillis(at));
    }

    /** 한 차례를 흉내 낸다: 요청 전 스냅샷·사본 맞춤 → 바꾸기 → 변경 목록 → 떼어 두기. */
    private List<String> turn(WorkspaceCheckpoints ck, Path root, long turnId, ThrowingRunnable edit) throws IOException {
        Map<String, long[]> before = WorkspaceFiles.snapshot(root);
        assertTrue(ck.sync(1, root, before));
        edit.run();
        List<String> ch = WorkspaceFiles.changes(before, WorkspaceFiles.snapshot(root), Integer.MAX_VALUE);
        ck.capture(1, turnId, root, ch);
        return ch;
    }

    interface ThrowingRunnable {
        void run() throws IOException;
    }

    @Test
    void 차례가_바꾼_파일을_요청_전_상태로_되돌린다() throws IOException {
        Path root = tmp.resolve("ws");
        WorkspaceCheckpoints ck = new WorkspaceCheckpoints(tmp.resolve("ckpt"));
        write(root.resolve("a.md"), "처음", 1_000_000);
        write(root.resolve("sub/b.txt"), "지울 것", 1_000_000);
        write(root.resolve("keep.txt"), "그대로", 1_000_000);

        List<String> ch = turn(ck, root, 7, () -> {
            write(root.resolve("a.md"), "고친 내용", 2_000_000);
            Files.delete(root.resolve("sub/b.txt"));
            write(root.resolve("new/c.txt"), "새 파일", 2_000_000);
        });
        assertEquals(List.of("M a.md", "A new/c.txt", "D sub/b.txt"), ch);
        assertTrue(ck.has(1, 7));

        // 그 차례의 요청 전·후 내용
        Path[] a = ck.sides(1, 7, "a.md");
        assertEquals("처음", Files.readString(a[0]));
        assertEquals("고친 내용", Files.readString(a[1]));
        assertNull(ck.sides(1, 7, "new/c.txt")[0]);
        assertNull(ck.sides(1, 7, "sub/b.txt")[1]);
        assertNull(ck.sides(1, 7, "keep.txt"));                   // 바뀌지 않은 파일
        assertNull(ck.sides(1, 7, "../ckpt/1/t7/manifest"));      // 목록에 없는 경로

        WorkspaceCheckpoints.Reverted r = ck.revert(1, 7, root);
        assertEquals(3, r.restored().size());
        assertTrue(r.skipped().isEmpty());
        assertEquals("처음", Files.readString(root.resolve("a.md")));
        assertEquals("지울 것", Files.readString(root.resolve("sub/b.txt")));
        assertFalse(Files.exists(root.resolve("new/c.txt")));
        assertEquals("그대로", Files.readString(root.resolve("keep.txt")));
    }

    @Test
    void 이후에_다시_바뀐_파일은_덮어쓰지_않고_건너뛴다() throws IOException {
        Path root = tmp.resolve("ws");
        WorkspaceCheckpoints ck = new WorkspaceCheckpoints(tmp.resolve("ckpt"));
        write(root.resolve("a.md"), "처음", 1_000_000);
        write(root.resolve("b.md"), "처음 b", 1_000_000);

        turn(ck, root, 1, () -> {
            write(root.resolve("a.md"), "첫 요청", 2_000_000);
            write(root.resolve("b.md"), "첫 요청 b", 2_000_000);
            write(root.resolve("c.md"), "새 파일", 2_000_000);
        });
        // 다음 요청이 a 를 또 고치고, 사용자가 c 를 손으로 고쳤다
        turn(ck, root, 2, () -> write(root.resolve("a.md"), "둘째 요청", 3_000_000));
        write(root.resolve("c.md"), "손으로 고침", 4_000_000);

        WorkspaceCheckpoints.Reverted r = ck.revert(1, 1, root);
        assertEquals(List.of("b.md"), r.restored());
        assertEquals(2, r.skipped().size());
        assertEquals("둘째 요청", Files.readString(root.resolve("a.md")));       // 건드리지 않았다
        assertEquals("처음 b", Files.readString(root.resolve("b.md")));
        assertEquals("손으로 고침", Files.readString(root.resolve("c.md")));

        // 둘째 요청은 그대로 되돌릴 수 있다(첫 요청이 남긴 내용으로)
        assertEquals(List.of("a.md"), ck.revert(1, 2, root).restored());
        assertEquals("첫 요청", Files.readString(root.resolve("a.md")));
    }

    @Test
    void 지점이_없으면_되돌리기를_거부하고_등록_해제하면_모두_지운다() throws IOException {
        Path root = tmp.resolve("ws");
        WorkspaceCheckpoints ck = new WorkspaceCheckpoints(tmp.resolve("ckpt"));
        write(root.resolve("a.md"), "처음", 1_000_000);
        assertThrows(IllegalArgumentException.class, () -> ck.revert(1, 99, root));
        assertFalse(ck.sync(1, root, null));                       // 스냅샷을 못 뜬 폴더(파일이 너무 많음)

        turn(ck, root, 3, () -> write(root.resolve("a.md"), "고침", 2_000_000));
        assertTrue(ck.has(1, 3));
        ck.drop(1);
        assertFalse(ck.has(1, 3));
        assertFalse(Files.exists(tmp.resolve("ckpt/1")));
    }

    @Test
    void 파일_이름_목록과_두_파일의_차이() throws IOException {
        Path root = tmp.resolve("ws");
        write(root.resolve("paper/3_method.md"), "a\nb\n", 1_000_000);
        write(root.resolve("node_modules/x/index.js"), "x", 1_000_000);
        write(root.resolve("README.md"), "r", 1_000_000);
        Map<String, Object> n = WorkspaceFiles.names(root);
        assertEquals(List.of("paper/3_method.md", "README.md"), n.get("files"));   // 의존성 폴더는 뺀다
        assertEquals(false, n.get("more"));

        Path before = tmp.resolve("b.txt");
        Path after = tmp.resolve("a.txt");
        Files.writeString(before, "하나\n둘\n");
        Files.writeString(after, "하나\n셋\n");
        Map<String, Object> d = WorkspaceFiles.fileDiff(before, after);
        if (d.get("error") == null) {                              // git 이 없는 환경에서는 error 로 알린다
            String diff = (String) d.get("diff");
            assertNotNull(diff);
            assertTrue(diff.contains("-둘") && diff.contains("+셋"), diff);
            assertTrue(((String) WorkspaceFiles.fileDiff(null, after).get("diff")).contains("+셋"));
        }
    }
}
