package io.github.lib0823.devagent.admin;

import jakarta.servlet.http.HttpServletRequest;
import io.github.lib0823.devagent.workspace.Workspace;
import io.github.lib0823.devagent.workspace.WorkspaceMapper;
import io.github.lib0823.devagent.workspace.WorkspaceService;
import io.github.lib0823.devagent.workspace.WorkspaceTurn;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 작업 공간(「작업 공간」 탭) API. 요청은 사용자의 폴더에서 파일을 고치고 명령을 돌리는 Claude 세션으로 이어지므로
 * 상태를 바꾸는 호출은 모두 Origin 을 확인한다(다른 사이트의 페이지가 브라우저를 통해 폴더를 등록하거나 지시를 밀어 넣지 못하게).
 */
@RestController
public class WorkspaceController {

    private static final String FOREIGN = "이 화면에서만 요청할 수 있습니다.";

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    /** 작업 공간 목록·실행 중 여부·모델 선택지(가볍다). */
    @GetMapping("/api/workspaces")
    public Map<String, Object> overview() {
        return workspaces.overview();
    }

    /** 모든 작업 공간의 최근 대화(홈 화면 요약). */
    @GetMapping("/api/workspaces/recent")
    public List<WorkspaceMapper.ThreadRow> recent(@RequestParam(defaultValue = "6") int limit) {
        return workspaces.recent(limit);
    }

    @GetMapping("/api/workspaces/{id}/threads")
    public List<WorkspaceMapper.ThreadRow> threads(@PathVariable long id, @RequestParam(defaultValue = "100") int limit) {
        return workspaces.threads(id, limit);
    }

    /** 대화 상세. afterId 는 활동 로그 증분 조회용. */
    @GetMapping("/api/workspaces/threads/{threadId}")
    public ResponseEntity<Map<String, Object>> thread(@PathVariable long threadId, @RequestParam(defaultValue = "0") long afterId) {
        Map<String, Object> t = workspaces.thread(threadId, afterId);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    // ---------------------------------------------------------------- 폴더 보기(읽기 전용, 작업 공간 루트 안만)

    /** 폴더 한 단계 목록. dir 은 루트 기준 상대 경로(비우면 루트). */
    @GetMapping("/api/workspaces/{id}/tree")
    public ResponseEntity<Map<String, Object>> tree(@PathVariable long id, @RequestParam(defaultValue = "") String dir) {
        return view(() -> workspaces.tree(id, dir));
    }

    /** 파일 내용(글·그림 여부·크기). ifMtime 이 지금 수정 시각과 같으면 내용 없이 unchanged. */
    @GetMapping("/api/workspaces/{id}/file")
    public ResponseEntity<Map<String, Object>> file(@PathVariable long id, @RequestParam String path,
                                                    @RequestParam(defaultValue = "0") long ifMtime) {
        return view(() -> workspaces.file(id, path, ifMtime));
    }

    /** 마지막 커밋 대비 변경분(git 저장소일 때). */
    @GetMapping("/api/workspaces/{id}/diff")
    public ResponseEntity<Map<String, Object>> diff(@PathVariable long id, @RequestParam String path) {
        return view(() -> workspaces.diff(id, path));
    }

    /** 폴더 전체의 파일 경로(「파일 찾기」가 한 번 받아 이름으로 거른다). */
    @GetMapping("/api/workspaces/{id}/names")
    public ResponseEntity<Map<String, Object>> names(@PathVariable long id) {
        return view(() -> workspaces.names(id));
    }

    /** 그 차례에서 이 파일이 바뀐 내용(요청 전 → 요청 후, 되돌리기 지점에서). */
    @GetMapping("/api/workspaces/turns/{id}/diff")
    public ResponseEntity<Map<String, Object>> turnDiff(@PathVariable long id, @RequestParam String path) {
        return view(() -> workspaces.turnDiff(id, path));
    }

    /** 그 차례가 바꾼 파일을 요청 전 상태로 되돌린다(이후에 또 바뀐 파일은 건너뛴다). */
    @PostMapping("/api/workspaces/turns/{id}/revert")
    public Map<String, Object> revert(@PathVariable long id, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 파일을 덮어쓰는 일이라 화면에서 온 요청만 받는다(세션이 로컬 API 로 스스로 부르지 못하게)
        if (SystemController.notFromPage(req)) {
            return fail(out, FOREIGN);
        }
        try {
            out.put("ok", true);
            out.put("result", workspaces.revert(id));
        } catch (IllegalArgumentException e) {
            fail(out, e.getMessage());
        }
        return out;
    }

    /** 그림 파일 바이트(png·jpg·gif·webp 만 — 문서 미리보기의 그림용). */
    @GetMapping("/api/workspaces/{id}/raw")
    public ResponseEntity<Resource> raw(@PathVariable long id, @RequestParam String path) {
        try {
            Path p = workspaces.image(id, path);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, WorkspaceService.imageType(p.getFileName().toString()))
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(new FileSystemResource(p));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private static ResponseEntity<Map<String, Object>> view(Supplier<Map<String, Object>> call) {
        try {
            return ResponseEntity.ok(call.get());
        } catch (IllegalArgumentException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(out);
        }
    }

    /** 등록(id 없음) 또는 수정. */
    @PostMapping("/api/workspaces")
    public Map<String, Object> save(@RequestParam(required = false) Long id,
                                    @RequestParam(required = false) String name,
                                    @RequestParam String path,
                                    @RequestParam(required = false) String mode,
                                    @RequestParam(defaultValue = "false") boolean opsMcp,
                                    @RequestParam(required = false) String skills,
                                    HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (SystemController.foreign(req)) {
            return fail(out, FOREIGN);
        }
        try {
            Workspace w = workspaces.save(id, name, path, mode, opsMcp, skills);
            out.put("ok", true);
            out.put("id", w.getId());
            out.put("result", "「" + w.getName() + "」 작업 공간을 저장했습니다.");
        } catch (IllegalArgumentException e) {
            fail(out, e.getMessage());
        }
        return out;
    }

    /** 등록 해제(대화 기록만 지운다 — 폴더는 그대로). */
    @PostMapping("/api/workspaces/{id}/delete")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (SystemController.foreign(req)) {
            return fail(out, FOREIGN);
        }
        try {
            out.put("ok", true);
            out.put("result", workspaces.delete(id));
        } catch (IllegalArgumentException e) {
            fail(out, e.getMessage());
        }
        return out;
    }

    /** 요청 접수. threadId 가 있으면 그 대화에 이어 붙인다. */
    @PostMapping("/api/workspaces/{id}/ask")
    public Map<String, Object> ask(@PathVariable long id,
                                   @RequestParam String question,
                                   @RequestParam(required = false) Long threadId,
                                   @RequestParam(required = false) String model,
                                   HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (SystemController.foreign(req)) {
            return fail(out, FOREIGN);
        }
        try {
            WorkspaceTurn t = workspaces.submit(id, question, threadId, model);
            out.put("ok", true);
            out.put("id", t.getId());
            out.put("threadId", t.getThreadId());
        } catch (IllegalArgumentException e) {
            fail(out, e.getMessage());
        }
        return out;
    }

    /**
     * 등록할 폴더 고르기: 이 PC 의 폴더 한 단계(하위 폴더 이름만). 다른 사이트의 페이지가 폴더 구조를 읽어 가지 못하게 Origin 을 확인한다.
     */
    @GetMapping("/api/workspaces/browse")
    public ResponseEntity<Map<String, Object>> browse(@RequestParam(defaultValue = "") String dir, HttpServletRequest req) {
        if (SystemController.foreign(req)) {
            return ResponseEntity.status(403).body(fail(new LinkedHashMap<>(), FOREIGN));
        }
        return view(() -> workspaces.browse(dir));
    }

    /** 「확인 후 실행」: 대기 중인 도구 실행을 허용/거부한다. remember = 같은 도구는 이번 요청이 끝날 때까지 다시 묻지 않음. */
    @PostMapping("/api/workspaces/approvals/{id}")
    public Map<String, Object> decide(@PathVariable long id,
                                      @RequestParam boolean allow,
                                      @RequestParam(defaultValue = "false") boolean remember,
                                      @RequestParam(required = false) String message,
                                      HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 화면에서 온 요청만: Origin 없는 호출(세션이 돌리는 curl)로 자기 확인을 스스로 허용하지 못하게 한다
        if (SystemController.notFromPage(req)) {
            return fail(out, FOREIGN);
        }
        boolean ok = workspaces.decide(id, allow, remember, message);
        out.put("ok", ok);
        out.put("result", ok ? (allow ? "허용했습니다." : "거부했습니다.") : "이미 처리되었거나 끝난 확인입니다.");
        return out;
    }

    @PostMapping("/api/workspaces/turns/{id}/abort")
    public Map<String, Object> abort(@PathVariable long id, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (SystemController.foreign(req)) {
            return fail(out, FOREIGN);
        }
        out.put("ok", true);
        out.put("result", workspaces.abort(id));
        return out;
    }

    private static Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("ok", false);
        out.put("result", message);
        return out;
    }
}
