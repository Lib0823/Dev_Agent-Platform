package io.github.lib0823.devagent.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceServiceTest {

    @Test
    void readModeAllowsNoEditOrShellAndDeniesThemExplicitly() {
        List<String> allow = WorkspaceService.allowTools(Workspace.READ);
        assertTrue(allow.contains("Read"));
        assertFalse(allow.contains("Edit"));
        assertFalse(allow.contains("Bash"));
        // 편집 자동 승인 모드에서는 허용 목록에 없어도 편집이 되므로 금지 목록에 있어야 한다
        List<String> deny = WorkspaceService.denyTools(Workspace.READ);
        assertTrue(deny.containsAll(List.of("Edit", "Write", "MultiEdit", "NotebookEdit", "Bash")));
    }

    @Test
    void editModeAllowsEditsButNotShell() {
        assertTrue(WorkspaceService.allowTools(Workspace.EDIT).containsAll(List.of("Read", "Edit", "Write")));
        assertFalse(WorkspaceService.allowTools(Workspace.EDIT).contains("Bash"));
        assertEquals(List.of("Bash"), WorkspaceService.denyTools(Workspace.EDIT));
    }

    @Test
    void fullModeAllowsShellAndDeniesNothing() {
        assertTrue(WorkspaceService.allowTools(Workspace.FULL).containsAll(List.of("Read", "Edit", "Bash")));
        assertTrue(WorkspaceService.denyTools(Workspace.FULL).isEmpty());
    }

    @Test
    void acceptsExistingFolderAndStripsQuotes(@TempDir Path dir) throws Exception {
        Path sub = Files.createDirectories(dir.resolve("paper"));
        assertEquals(sub.toRealPath(), WorkspaceService.checkPath(sub.toString(), null));
        assertEquals(sub.toRealPath(), WorkspaceService.checkPath("  \"" + sub + "\"  ", null));   // 탐색기 「경로로 복사」
    }

    @Test
    void rejectsBlankRelativeMissingAndFilePaths(@TempDir Path dir) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceService.checkPath("  ", null));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceService.checkPath(null, null));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceService.checkPath("some/relative", null));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceService.checkPath(dir.resolve("nope").toString(), null));
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        assertThrows(IllegalArgumentException.class, () -> WorkspaceService.checkPath(file.toString(), null));
    }

    @Test
    void rejectsDriveRootAndHomeItself(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath().getRoot();
        assertThrows(IllegalArgumentException.class, () -> WorkspaceService.checkPath(root.toString(), null));
        // 홈 자체는 거부, 그 안의 폴더는 허용
        assertThrows(IllegalArgumentException.class, () -> WorkspaceService.checkPath(dir.toString(), dir));
        Path inside = Files.createDirectories(dir.resolve("work"));
        assertEquals(inside.toRealPath(), WorkspaceService.checkPath(inside.toString(), dir));
    }

    @Test
    void recognisesMissingSessionError() {
        assertTrue(WorkspaceService.sessionMissing("Error: No conversation found with session ID abc"));
        assertFalse(WorkspaceService.sessionMissing("timeout"));
        assertFalse(WorkspaceService.sessionMissing(null));
    }

    @Test
    void promptStatesThePermissionScope() {
        Workspace w = new Workspace();
        w.setName("논문");
        w.setMode(Workspace.READ);
        assertTrue(WorkspacePrompts.system(w, Path.of("x").toAbsolutePath(), 50).contains("읽기 전용"));
        w.setMode(Workspace.EDIT);
        assertTrue(WorkspacePrompts.system(w, Path.of("x").toAbsolutePath(), 50).contains("명령 실행(셸)은 막혀"));
        w.setMode(Workspace.FULL);
        assertTrue(WorkspacePrompts.system(w, Path.of("x").toAbsolutePath(), 50).contains("셸 명령"));
    }
}
