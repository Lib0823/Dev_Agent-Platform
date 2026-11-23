package io.github.lib0823.devagent.maintain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintainServiceTest {

    @Test
    void parsesBranchUpstreamAndCounts() {
        MaintainService.RepoState s = MaintainService.parseStatus(
                "## main...origin/main [ahead 2, behind 1]\n M src/A.java\n?? docs/new file.md\nA  b.txt\n");
        assertEquals("main", s.branch());
        assertEquals("origin/main", s.upstream());
        assertEquals(2, s.ahead());
        assertEquals(1, s.behind());
        assertEquals(List.of("src/A.java", "docs/new file.md", "b.txt"), s.files());
    }

    @Test
    void parsesCleanAndUpstreamlessBranches() {
        MaintainService.RepoState clean = MaintainService.parseStatus("## main...origin/main\n");
        assertEquals("main", clean.branch());
        assertEquals(0, clean.ahead());
        assertTrue(clean.files().isEmpty());

        MaintainService.RepoState local = MaintainService.parseStatus("## feature/1234\n");
        assertEquals("feature/1234", local.branch());
        assertNull(local.upstream());

        MaintainService.RepoState ahead = MaintainService.parseStatus("## fix/7...origin/fix/7 [ahead 3]");
        assertEquals("fix/7", ahead.branch());
        assertEquals("origin/fix/7", ahead.upstream());
        assertEquals(3, ahead.ahead());
        assertEquals(0, ahead.behind());
    }

    @Test
    void detachedHeadIsRecognisable() {
        assertTrue(MaintainService.parseStatus("## HEAD (no branch)\n").branch().startsWith("HEAD"));
    }

    @Test
    void sourceRepoNeedsGitGradleAndSources(@TempDir Path dir) throws Exception {
        assertFalse(MaintainService.isSourceRepo(dir));
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve("build.gradle"), "");
        assertFalse(MaintainService.isSourceRepo(dir));   // 배포판 홈처럼 소스가 없는 곳
        Files.createDirectories(dir.resolve("src/main/java"));
        assertTrue(MaintainService.isSourceRepo(dir));
        assertFalse(MaintainService.isSourceRepo(null));
    }

    @Test
    void detectsMissingSessionError() {
        assertTrue(MaintainService.sessionMissing("claude(agent) 실패 (exit=1): No conversation found with session ID: abc"));
        assertFalse(MaintainService.sessionMissing("claude(agent) 타임아웃"));
        assertFalse(MaintainService.sessionMissing(null));
    }

    @Test
    void systemPromptKeepsTheSessionAwayFromPushRestartAndSecrets() {
        String sys = MaintainPrompts.system(Path.of("repo"), 8777);
        assertTrue(sys.contains("http://127.0.0.1:8777/api/skills"));
        assertTrue(sys.contains("git push 는 하지 않습니다"));
        assertTrue(sys.contains("재시작 적용"));
        assertTrue(sys.contains(".env 는 읽지도"));
    }
}
