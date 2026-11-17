package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.preview.PreviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 로컬 프리뷰 API (localhost 전용). 이슈 에이전트는 worktree 에서 scripts/bin/preview 로 호출한다.
 * 응답은 모델이 그대로 읽는 평문.
 */
@RestController
public class PreviewController {

    private final PreviewService preview;

    public PreviewController(PreviewService preview) {
        this.preview = preview;
    }

    @GetMapping(value = "/api/preview/status", produces = "text/plain;charset=UTF-8")
    public String status() {
        Map<String, Object> s = preview.status();
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(s.get("enabled")).append(" running=").append(s.get("running")).append(" url=").append(s.get("url")).append('\n');
        PreviewService.Deployment d = preview.current();
        if (d != null) {
            sb.append("deployed dir=").append(d.dir()).append(" (build ").append(d.buildMs() / 1000).append("s, start ").append(d.startMs() / 1000).append("s)\n");
        }
        sb.append("tools: java=").append(ok(s, "javaHome")).append(" maven=").append(ok(s, "maven"))
          .append(" tomcat=").append(ok(s, "catalinaHome")).append(" base=").append(ok(s, "catalinaBase"))
          .append(" login=").append(s.get("hasLogin")).append(" busy=").append(s.get("busy"));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String ok(Map<String, Object> s, String key) {
        Object v = s.get(key);
        return v instanceof Map<?, ?> m ? String.valueOf(((Map<String, Object>) m).get("ok")) : "?";
    }

    /** 빌드·배포·기동(블로킹, 수 분). dir = worktree 절대경로. build=false 면 기존 WAR 재배포. */
    @PostMapping(value = "/api/preview/deploy", produces = "text/plain;charset=UTF-8")
    public String deploy(@RequestParam String dir, @RequestParam(defaultValue = "true") boolean build) {
        PreviewService.Result r = preview.deploy(Path.of(dir.strip()).toAbsolutePath().normalize(), build);
        return (r.ok() ? "OK " : "FAIL ") + r.message();
    }

    /**
     * 핫 동기화(수 초): 실행 중인 프리뷰에 src/main/webapp 변경(JSP/JS/CSS)만 복사. files 는 줄바꿈/쉼표 구분(비면 스냅샷 비교로 자동 감지).
     */
    @PostMapping(value = "/api/preview/sync", produces = "text/plain;charset=UTF-8")
    public String sync(@RequestParam String dir, @RequestParam(defaultValue = "") String files) {
        List<String> list = files.isBlank() ? List.of() : Arrays.stream(files.split("[\\r\\n,]+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        PreviewService.Result r = preview.sync(Path.of(dir.strip()).toAbsolutePath().normalize(), list);
        return (r.ok() ? "OK " : "FAIL ") + r.message();
    }

    @PostMapping(value = "/api/preview/stop", produces = "text/plain;charset=UTF-8")
    public String stop() {
        return preview.stop().message();
    }

    @GetMapping(value = "/api/preview/log", produces = "text/plain;charset=UTF-8")
    public String log(@RequestParam(defaultValue = "80") int lines) {
        String t = preview.logTail(Math.max(5, Math.min(lines, 1000)));
        return t.isBlank() ? "(로그 없음)" : t;
    }

    /** JSON 상태(관리 페이지 카드용). */
    @GetMapping("/api/preview/status.json")
    public Map<String, Object> statusJson() {
        return preview.status();
    }
}
