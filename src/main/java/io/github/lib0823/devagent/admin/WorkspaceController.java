package io.github.lib0823.devagent.admin;

import jakarta.servlet.http.HttpServletRequest;
import io.github.lib0823.devagent.workspace.Workspace;
import io.github.lib0823.devagent.workspace.WorkspaceMapper;
import io.github.lib0823.devagent.workspace.WorkspaceService;
import io.github.lib0823.devagent.workspace.WorkspaceTurn;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 등록(id 없음) 또는 수정. */
    @PostMapping("/api/workspaces")
    public Map<String, Object> save(@RequestParam(required = false) Long id,
                                    @RequestParam(required = false) String name,
                                    @RequestParam String path,
                                    @RequestParam(required = false) String mode,
                                    @RequestParam(defaultValue = "false") boolean opsMcp,
                                    HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (SystemController.foreign(req)) {
            return fail(out, FOREIGN);
        }
        try {
            Workspace w = workspaces.save(id, name, path, mode, opsMcp);
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
