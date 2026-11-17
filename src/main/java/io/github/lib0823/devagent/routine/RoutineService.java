package io.github.lib0823.devagent.routine;

import jakarta.annotation.PostConstruct;
import io.github.lib0823.devagent.research.ResearchJob;
import io.github.lib0823.devagent.research.ResearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 루틴(예약 질의) 실행기. 1분마다 "실행할 때가 된" 루틴을 질의 워크플로우에 접수한다 — 실제 실행·검증·알림은 {@link ResearchService} 가 한다.
 * 놓친 실행(그 시각에 앱이 꺼져 있었음)은 같은 날 안에 켜지면 한 번 따라잡고, 날이 바뀌면 건너뛴다.
 */
@Service
public class RoutineService {

    private static final Logger log = LoggerFactory.getLogger(RoutineService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    static final int MAX_ROUTINES = 50;

    private final RoutineMapper mapper;
    private final ResearchService research;
    private volatile boolean ready;

    public RoutineService(RoutineMapper mapper, ResearchService research) {
        this.mapper = mapper;
        this.research = research;
    }

    @PostConstruct
    void init() {
        try {
            mapper.ensureTable();
            ready = true;
        } catch (RuntimeException e) {
            log.warn("루틴 테이블 준비 실패(루틴 없이 진행): {}", e.toString());
        }
    }

    public List<Routine> list() {
        return ready ? mapper.findAll() : List.of();
    }

    /** 등록(id 없음)·수정. 검증 실패는 IllegalArgumentException. */
    public Routine save(Routine r) {
        String err = validate(r);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        if (r.getId() == null) {
            if (mapper.findAll().size() >= MAX_ROUTINES) {
                throw new IllegalArgumentException("루틴은 " + MAX_ROUTINES + "개까지 등록할 수 있습니다.");
            }
            mapper.insert(r);
        } else if (mapper.update(r) == 0) {
            throw new IllegalArgumentException("루틴 #" + r.getId() + " 이 없습니다.");
        }
        return mapper.find(r.getId());
    }

    public boolean delete(long id) {
        return mapper.delete(id) > 0;
    }

    /** 지금 한 번 실행(예약과 무관). 접수된 질의를 돌려준다. */
    public ResearchJob runNow(long id) {
        Routine r = mapper.find(id);
        if (r == null) {
            throw new IllegalArgumentException("루틴 #" + id + " 이 없습니다.");
        }
        return submit(r);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    void tick() {
        if (!ready || !research.enabled()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        for (Routine r : mapper.findAll()) {
            if (!isDue(r, now)) {
                continue;
            }
            try {
                submit(r);
            } catch (RuntimeException e) {
                // 접수 실패(프로젝트 삭제 등)여도 오늘 치는 소진한다 — 1분마다 같은 오류를 반복하지 않게
                mapper.markRun(r.getId(), null);
                log.warn("루틴 '{}' 접수 실패: {}", r.getName(), e.getMessage());
            }
        }
    }

    private ResearchJob submit(Routine r) {
        ResearchJob job = research.submit(r.getQuestion(), r.projectList(), blankToNull(r.getModel()), blankToNull(r.getEffort()), null,
                r.isNotify(), "routine", r.getVerify());
        mapper.markRun(r.getId(), job.getId());
        log.info("루틴 '{}' 실행 → 질의 #{}", r.getName(), job.getId());
        return job;
    }

    /**
     * 실행할 때인가: 오늘이 대상 요일이고, 오늘의 예약 시각이 지났고, 그 시각 이후로 실행·수정된 적이 없을 때.
     * (수정 시각을 함께 보는 이유: 오후에 "09:00" 루틴을 새로 만들었을 때 곧바로 실행되지 않게.)
     */
    static boolean isDue(Routine r, ZonedDateTime now) {
        if (!r.isEnabled() || r.getRunAt() == null || !TIME.matcher(r.getRunAt()).matches()) {
            return false;
        }
        List<DayOfWeek> days = r.dayList();
        if (!days.isEmpty() && !days.contains(now.getDayOfWeek())) {
            return false;
        }
        ZonedDateTime slot = now.toLocalDate().atTime(LocalTime.parse(r.getRunAt())).atZone(now.getZone());
        if (now.isBefore(slot)) {
            return false;
        }
        return before(r.getLastRunAt(), slot) && before(r.getUpdatedAt(), slot);
    }

    /** 다음 실행 시각(화면 표시용). 꺼져 있거나 시각이 잘못됐으면 null. */
    static ZonedDateTime nextRun(Routine r, ZonedDateTime now) {
        if (!r.isEnabled() || r.getRunAt() == null || !TIME.matcher(r.getRunAt()).matches()) {
            return null;
        }
        List<DayOfWeek> days = r.dayList();
        LocalTime at = LocalTime.parse(r.getRunAt());
        for (int i = 0; i <= 7; i++) {
            LocalDate d = now.toLocalDate().plusDays(i);
            ZonedDateTime slot = d.atTime(at).atZone(now.getZone());
            if ((days.isEmpty() || days.contains(d.getDayOfWeek())) && (slot.isAfter(now) || i == 0 && isDue(r, now))) {
                return slot;
            }
        }
        return null;
    }

    public ZonedDateTime nextRun(Routine r) {
        return nextRun(r, ZonedDateTime.now(ZONE));
    }

    private static boolean before(OffsetDateTime t, ZonedDateTime slot) {
        return t == null || t.toInstant().isBefore(slot.toInstant());
    }

    static String validate(Routine r) {
        if (r.getName() == null || r.getName().isBlank() || r.getName().strip().length() > 80) {
            return "이름을 1~80자로 입력하세요.";
        }
        if (r.getQuestion() == null || r.getQuestion().isBlank()) {
            return "질문을 입력하세요.";
        }
        if (r.getQuestion().length() > 4000) {
            return "질문은 4,000자 이하로 입력하세요.";
        }
        if (r.projectList().isEmpty()) {
            return "대상 프로젝트를 하나 이상 고르세요.";
        }
        if (r.getRunAt() == null || !TIME.matcher(r.getRunAt().strip()).matches()) {
            return "실행 시각은 HH:mm 형식(예: 09:00)으로 입력하세요.";
        }
        if (r.getModel() != null && !r.getModel().isBlank() && !ResearchService.MODELS.contains(r.getModel().strip())) {
            return "모델은 " + ResearchService.MODELS + " 중 하나여야 합니다.";
        }
        if (r.getEffort() != null && !r.getEffort().isBlank() && !ResearchService.EFFORTS.contains(r.getEffort().strip())) {
            return "노력은 " + ResearchService.EFFORTS + " 중 하나여야 합니다.";
        }
        r.setName(r.getName().strip());
        r.setQuestion(r.getQuestion().strip());
        r.setRunAt(r.getRunAt().strip());
        r.setProjects(String.join(",", r.projectList()));
        List<DayOfWeek> days = r.dayList();
        r.setDays(days.isEmpty() || days.size() == 7 ? "" : String.join(",", days.stream().map(d -> Routine.DAYS.get(d.getValue() - 1)).toList()));
        r.setModel(blankToNull(r.getModel()));
        r.setEffort(blankToNull(r.getEffort()));
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
