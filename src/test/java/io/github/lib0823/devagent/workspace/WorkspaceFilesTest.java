package io.github.lib0823.devagent.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFilesTest {

    @Test
    void resolvesInsideAndRejectsEscapes(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.createDirectories(root.resolve("a/b"));
        Files.writeString(dir.resolve("outside.txt"), "x");
        assertEquals(root.toAbsolutePath().normalize(), WorkspaceFiles.resolveInside(root, ""));
        assertEquals(root.toAbsolutePath().normalize(), WorkspaceFiles.resolveInside(root, null));
        assertEquals(root.resolve("a/b").toAbsolutePath().normalize(), WorkspaceFiles.resolveInside(root, "a/b"));
        assertEquals(root.resolve("a/b").toAbsolutePath().normalize(), WorkspaceFiles.resolveInside(root, "/a\\b"));
        assertEquals(root.resolve("a").toAbsolutePath().normalize(), WorkspaceFiles.resolveInside(root, "a/b/.."));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFiles.resolveInside(root, "../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFiles.resolveInside(root, "a/../../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFiles.resolveInside(root, "C:/Windows/win.ini"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFiles.resolveInside(root, dir.resolve("outside.txt").toString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsFoldersFirstAndHidesGit(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("b.md"), "b");
        Files.writeString(root.resolve("A.md"), "a");
        Map<String, Object> out = WorkspaceFiles.list(root, "");
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals(List.of("src", "A.md", "b.md"), items.stream().map(m -> (String) m.get("name")).toList());
        assertEquals(true, items.get(0).get("dir"));
        assertEquals("A.md", items.get(1).get("path"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFiles.list(root, "nope"));
    }

    @Test
    void readsTextAndFlagsBinaryImageAndUnchanged(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("notes.md"), "# 제목\n본문");
        Files.write(root.resolve("data.bin"), new byte[]{1, 2, 0, 3});
        Files.write(root.resolve("fig.PNG"), new byte[]{1, 2, 3});
        Map<String, Object> t = WorkspaceFiles.read(root, "notes.md", 0);
        assertEquals("# 제목\n본문", t.get("text"));
        assertEquals(true, WorkspaceFiles.read(root, "notes.md", (Long) t.get("mtime")).get("unchanged"));
        assertNull(WorkspaceFiles.read(root, "notes.md", (Long) t.get("mtime")).get("text"));
        assertEquals(true, WorkspaceFiles.read(root, "data.bin", 0).get("binary"));
        assertEquals(true, WorkspaceFiles.read(root, "fig.PNG", 0).get("image"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFiles.read(root, "none.txt", 0));
        assertEquals("image/jpeg", WorkspaceFiles.imageType("a.JPEG"));
        assertNull(WorkspaceFiles.imageType("a.svg"));                     // 스크립트를 담을 수 있어 그림으로 내보내지 않는다
    }

    @Test
    void snapshotDiffReportsAddedModifiedDeleted(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("keep.md"), "same");
        Files.writeString(root.resolve("edit.md"), "old");
        Files.writeString(root.resolve("gone.md"), "bye");
        Files.createDirectories(root.resolve("node_modules/x"));
        Files.writeString(root.resolve("node_modules/x/i.js"), "1");
        Map<String, long[]> before = WorkspaceFiles.snapshot(root);
        assertFalse(before.containsKey("node_modules/x/i.js"));           // 의존성 폴더는 보지 않는다
        Files.writeString(root.resolve("edit.md"), "new content");
        Files.setLastModifiedTime(root.resolve("edit.md"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Files.delete(root.resolve("gone.md"));
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/new.md"), "hi");
        Files.writeString(root.resolve("node_modules/x/j.js"), "2");
        List<String> ch = WorkspaceFiles.changes(before, WorkspaceFiles.snapshot(root));
        assertEquals(List.of("M edit.md", "D gone.md", "A sub/new.md"), ch);
        assertTrue(WorkspaceFiles.changes(before, before).isEmpty());
        assertTrue(WorkspaceFiles.changes(null, before).isEmpty());        // 스냅샷을 못 뜬 경우(파일이 너무 많음)
    }

    @Test
    void diffOutsideGitRepoSaysNoGit(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.md"), "x");
        Map<String, Object> d = WorkspaceFiles.gitDiff(root, "a.md");
        // 임시 폴더가 어떤 git 저장소 안에 있지 않다면 git=false. (안에 있더라도 예외 없이 답해야 한다)
        assertTrue(d.containsKey("git"));
        assertEquals("a.md", d.get("path"));
    }
}
