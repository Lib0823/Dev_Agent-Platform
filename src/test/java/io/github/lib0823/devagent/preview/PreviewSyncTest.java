package io.github.lib0823.devagent.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** preview sync(핫 동기화)의 변경 감지·분류 로직. Tomcat 없이 순수 파일 기준. */
class PreviewSyncTest {

    @TempDir
    Path wt;

    private Path write(String rel, String body) throws IOException {
        Path p = wt.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        return p;
    }

    @Test
    void snapshotDiffFindsAddedModifiedDeleted() throws Exception {
        write("src/main/webapp/WEB-INF/views/a.jsp", "a");
        write("src/main/webapp/resources/js/x.js", "x");
        write("src/main/java/Foo.java", "class Foo {}");
        write("pom.xml", "<project/>");
        Map<String, Long> before = PreviewService.snapshot(wt);
        assertEquals(4, before.size());

        Path a = write("src/main/webapp/WEB-INF/views/a.jsp", "a-changed!!");         // 수정(크기 변경)
        a.toFile().setLastModified(a.toFile().lastModified() + 10_000);
        write("src/main/webapp/resources/css/new.css", "body{}");                   // 추가
        Files.delete(wt.resolve("src/main/webapp/resources/js/x.js"));              // 삭제
        Map<String, Long> after = PreviewService.snapshot(wt);

        List<String> changed = PreviewService.changed(before, after);
        assertEquals(List.of("src/main/webapp/WEB-INF/views/a.jsp", "src/main/webapp/resources/css/new.css", "src/main/webapp/resources/js/x.js"), changed);

        PreviewService.SyncPlan plan = PreviewService.classify(wt, changed);
        assertEquals(List.of("src/main/webapp/WEB-INF/views/a.jsp", "src/main/webapp/resources/css/new.css"), plan.webapp());
        assertEquals(List.of("src/main/webapp/resources/js/x.js"), plan.deleted());
        assertTrue(plan.rebuild().isEmpty());
    }

    @Test
    void javaChangeRequiresRebuild() throws Exception {
        write("src/main/java/Foo.java", "class Foo {}");
        write("src/main/resources/db/x.properties", "k=v");
        write("src/main/webapp/resources/js/x.js", "x");
        write("README.md", "doc");
        PreviewService.SyncPlan plan = PreviewService.classify(wt, List.of(
                "src/main/java/Foo.java", "src/main/resources/db/x.properties", "src/main/webapp/resources/js/x.js", "pom.xml", "README.md"));
        assertEquals(List.of("src/main/webapp/resources/js/x.js"), plan.webapp());
        assertEquals(List.of("src/main/java/Foo.java", "src/main/resources/db/x.properties", "pom.xml"), plan.rebuild());
        assertEquals(List.of("README.md"), plan.ignored());
    }

    @Test
    void normalizeAcceptsAbsoluteBackslashAndDotSlash() {
        List<String> n = PreviewService.normalize(wt, List.of(
                wt.resolve("src\\main\\webapp\\a.jsp").toString(),
                "./src/main/webapp/b.jsp",
                "src/main/webapp/b.jsp",                 // 중복
                " ",
                "C:/elsewhere/outside.jsp"));            // worktree 밖은 무시
        assertEquals(List.of("src/main/webapp/a.jsp", "src/main/webapp/b.jsp"), n);
    }
}
