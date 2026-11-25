package io.github.lib0823.devagent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void askModeLetsOnlyReadingToolsThroughWithoutAsking() {
        List<String> free = WorkspaceService.askFreeTools();
        assertTrue(free.containsAll(List.of("Read", "Grep", "Glob")));
        // 파일 수정·명령 실행은 허용 목록에 없어야 확인 도구로 간다
        assertFalse(free.contains("Edit"));
        assertFalse(free.contains("Write"));
        assertFalse(free.contains("Bash"));
        assertEquals(List.of(Workspace.BYPASS, Workspace.ASK), WorkspaceService.MODES);
    }

    @Test
    void approvalSummaryShowsCommandOrFile() throws Exception {
        assertEquals("npm test", WorkspaceService.summarize("Bash", JSON.readTree("{\"command\":\"npm   test\",\"description\":\"run\"}")));
        assertEquals("C:/p/a.md", WorkspaceService.summarize("Edit", JSON.readTree("{\"file_path\":\"C:/p/a.md\",\"old_string\":\"a\",\"new_string\":\"b\"}")));
        String d = WorkspaceService.detail("Edit", JSON.readTree("{\"file_path\":\"a.md\",\"old_string\":\"old\",\"new_string\":\"new\"}"));
        assertTrue(d.contains("[이전]") && d.contains("old") && d.contains("[이후]") && d.contains("new"));
        assertEquals("hello", WorkspaceService.detail("Write", JSON.readTree("{\"file_path\":\"a.md\",\"content\":\"hello\"}")));
        assertEquals("", WorkspaceService.summarize("X", null));
    }

    @Test
    void approvalsWaitForTheUserAndRememberPerTurn() throws Exception {
        WorkspaceApprovals ap = new WorkspaceApprovals();
        // 모르는 토큰은 거부
        assertFalse(ap.await("nope", "Bash", "x", "x", 100).allow());
        String token = ap.open(7);
        // 답이 없으면 시간 초과 = 거부
        assertFalse(ap.await(token, "Bash", "ls", "ls", 50).allow());
        // 허용 + 기억: 같은 도구는 다시 묻지 않는다
        java.util.concurrent.CompletableFuture<WorkspaceApprovals.Decision> f =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> ap.await(token, "Edit", "a.md", "…", 5000));
        long id = waitForPending(ap, 7);
        assertEquals("Edit", ap.pending(7).get(0).tool());
        assertEquals(1, ap.waitingTurns().get(7L));
        assertTrue(ap.decide(id, true, true, null));
        assertTrue(f.get().allow());
        assertTrue(ap.await(token, "Edit", "b.md", "…", 50).allow());      // 기억됨: 기다리지 않고 허용
        assertFalse(ap.await(token, "Bash", "rm", "rm", 50).allow());      // 다른 도구는 여전히 묻는다
        // 거부 + 사유
        java.util.concurrent.CompletableFuture<WorkspaceApprovals.Decision> g =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> ap.await(token, "Bash", "rm -rf x", "…", 5000));
        long id2 = waitForPending(ap, 7);
        ap.decide(id2, false, false, "지우지 마");
        assertFalse(g.get().allow());
        assertEquals("지우지 마", g.get().message());
        assertFalse(ap.decide(id2, true, false, null));                    // 이미 끝난 확인
        // 차례가 끝나면 토큰이 닫힌다
        ap.close(7);
        assertNull(ap.turnOf(token));
        assertFalse(ap.await(token, "Edit", "c.md", "…", 50).allow());
    }

    private static long waitForPending(WorkspaceApprovals ap, long turnId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (!ap.pending(turnId).isEmpty()) {
                return ap.pending(turnId).get(0).id();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("확인 대기가 생기지 않음");
    }

    @Test
    void workspaceSkillListIsTrimmedAndDistinct() {
        Workspace w = new Workspace();
        w.setSkills(" a, b ,,a ");
        assertEquals(List.of("a", "b"), w.skillList());
        w.setSkills(null);
        assertTrue(w.skillList().isEmpty());
    }

    @Test
    void browseListsOnlyVisibleFoldersAndFallsBackToStart(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("beta/.git"));
        Files.createDirectories(dir.resolve("Alpha"));
        Files.createDirectories(dir.resolve(".hidden"));
        Files.writeString(dir.resolve("file.txt"), "x");
        Files.writeString(dir.resolve("CLAUDE.md"), "x");
        java.util.Map<String, Object> out = WorkspaceFiles.browse(dir.toString(), dir);
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> items = (List<java.util.Map<String, Object>>) out.get("items");
        assertEquals(List.of("Alpha", "beta"), items.stream().map(m -> (String) m.get("name")).toList());   // 파일·숨김 폴더 제외
        assertEquals(true, items.get(1).get("git"));
        assertEquals(true, out.get("claudeMd"));
        assertEquals(dir.toRealPath().toString(), out.get("dir"));
        // 없는 경로·상대 경로·빈 값은 시작 위치로
        assertEquals(dir.toRealPath().toString(), WorkspaceFiles.browse(dir.resolve("nope").toString(), dir).get("dir"));
        assertEquals(dir.resolve("nope").toString(), WorkspaceFiles.browse(dir.resolve("nope").toString(), dir).get("missing"));
        assertEquals(dir.toRealPath().toString(), WorkspaceFiles.browse("", dir).get("dir"));
        assertEquals(dir.toRealPath().toString(), WorkspaceFiles.browse("rel/path", dir).get("dir"));
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
        w.setMode(Workspace.ASK);
        assertTrue(WorkspacePrompts.system(w, Path.of("x").toAbsolutePath(), 50).contains("사용자가 화면에서 확인"));
        w.setMode(Workspace.BYPASS);
        assertTrue(WorkspacePrompts.system(w, Path.of("x").toAbsolutePath(), 50).contains("확인 없이 바로"));
    }

    @Test
    void messengerCommandSplitsWorkspaceNameFromRequest() {
        assertEquals(5, WorkspaceService.indexOfColon("paper: 3장 표를 다시 채워 줘"));
        assertEquals(2, WorkspaceService.indexOfColon("논문： 서론 다듬어 줘"));                    // 전각 콜론
        assertEquals(-1, WorkspaceService.indexOfColon("서버 상태 확인해 줘"));
        assertEquals(-1, WorkspaceService.indexOfColon("https://example.com 문서 읽고 정리해 줘"));   // 주소의 콜론은 이름 구분이 아니다
        assertEquals(12, WorkspaceService.indexOfColon("C:\\work\\a.md: 고쳐 줘"));                  // 드라이브 문자도

        Workspace paper = ws("paper-2026");
        Workspace infra = ws("infra");
        Workspace paperOld = ws("paper-2025");
        List<Workspace> all = List.of(paper, infra, paperOld);
        assertEquals(infra, WorkspaceService.match(all, "INFRA"));
        assertEquals(infra, WorkspaceService.match(all, "inf"));
        assertEquals(paper, WorkspaceService.match(all, "2026"));
        assertNull(WorkspaceService.match(all, "paper"));          // 둘에 걸리면 고르지 않는다(엉뚱한 폴더에서 일하지 않게)
        assertNull(WorkspaceService.match(all, ""));
    }

    private static Workspace ws(String name) {
        Workspace w = new Workspace();
        w.setName(name);
        return w;
    }
}
