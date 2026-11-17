package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.research.ResearchJob;
import io.github.lib0823.devagent.routine.Routine;
import io.github.lib0823.devagent.routine.RoutineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 루틴(예약 질의) 등록·수정·삭제·즉시 실행. 화면은 질의 탭의 "루틴" 패널. */
@RestController
public class RoutineController {

    private final RoutineService routines;

    public RoutineController(RoutineService routines) {
        this.routines = routines;
    }

    @GetMapping("/api/routines")
    public Map<String, Object> list() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Routine r : routines.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("question", r.getQuestion());
            m.put("projects", r.projectList());
            m.put("model", r.getModel());
            m.put("effort", r.getEffort());
            m.put("verify", r.getVerify());
            m.put("days", r.dayList().stream().map(d -> Routine.DAYS.get(d.getValue() - 1)).toList());
            m.put("runAt", r.getRunAt());
            m.put("notify", r.isNotify());
            m.put("enabled", r.isEnabled());
            m.put("lastRunAt", r.getLastRunAt());
            m.put("lastJobId", r.getLastJobId());
            ZonedDateTime next = routines.nextRun(r);
            m.put("nextRunAt", next == null ? null : next.toOffsetDateTime());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", Routine.DAYS);
        out.put("routines", list);
        return out;
    }

    /** 등록(id 없음)·수정. projects·days 는 쉼표 구분. */
    @PostMapping("/api/routines")
    public Map<String, Object> save(@RequestParam(required = false) Long id, @RequestParam String name, @RequestParam String question,
                                    @RequestParam String projects, @RequestParam String runAt,
                                    @RequestParam(required = false) String days, @RequestParam(required = false) String model,
                                    @RequestParam(required = false) String effort, @RequestParam(required = false) Boolean verify,
                                    @RequestParam(defaultValue = "false") boolean notify, @RequestParam(defaultValue = "true") boolean enabled) {
        Routine r = new Routine();
        r.setId(id);
        r.setName(name);
        r.setQuestion(question);
        r.setProjects(projects);
        r.setRunAt(runAt);
        r.setDays(days);
        r.setModel(model);
        r.setEffort(effort);
        r.setVerify(verify);
        r.setNotify(notify);
        r.setEnabled(enabled);
        try {
            Routine saved = routines.save(r);
            return result(true, "루틴 '" + saved.getName() + "' 저장");
        } catch (IllegalArgumentException e) {
            return result(false, e.getMessage());
        }
    }

    @PostMapping("/api/routines/delete")
    public Map<String, Object> delete(@RequestParam long id) {
        boolean removed = routines.delete(id);
        return result(removed, removed ? "루틴을 삭제했습니다" : "루틴 #" + id + " 이 없습니다");
    }

    /** 지금 한 번 실행 — 접수된 질의 id 를 돌려준다. */
    @PostMapping("/api/routines/run")
    public Map<String, Object> run(@RequestParam long id) {
        try {
            ResearchJob job = routines.runNow(id);
            Map<String, Object> m = result(true, "질의 #" + job.getId() + " 로 접수했습니다");
            m.put("jobId", job.getId());
            return m;
        } catch (IllegalArgumentException e) {
            return result(false, e.getMessage());
        }
    }

    private static Map<String, Object> result(boolean ok, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("result", message);
        return m;
    }
}
