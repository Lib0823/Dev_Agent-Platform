package io.github.lib0823.devagent.routine;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineServiceTest {

    private static final ZoneId Z = ZoneId.of("Asia/Seoul");

    /** 2026-11-16 은 월요일. */
    private static ZonedDateTime at(int day, int hour, int min) {
        return ZonedDateTime.of(2026, 11, day, hour, min, 0, 0, Z);
    }

    private static Routine routine(String days, String runAt) {
        Routine r = new Routine();
        r.setName("주간 정리");
        r.setQuestion("배치 잡 목록 정리");
        r.setProjects("grp/batch");
        r.setDays(days);
        r.setRunAt(runAt);
        r.setUpdatedAt(at(10, 8, 0).toOffsetDateTime());
        return r;
    }

    @Test
    void dueOnlyAfterSlotOnSelectedDay() {
        Routine r = routine("MON,WED", "09:00");
        assertFalse(RoutineService.isDue(r, at(16, 8, 59)), "예약 시각 전");
        assertTrue(RoutineService.isDue(r, at(16, 9, 0)));
        assertTrue(RoutineService.isDue(r, at(16, 15, 30)), "놓친 실행은 같은 날 따라잡는다");
        assertFalse(RoutineService.isDue(r, at(17, 9, 0)), "화요일은 대상 아님");
        assertTrue(RoutineService.isDue(r, at(18, 9, 1)));
    }

    @Test
    void notDueTwiceAndNotRightAfterEdit() {
        Routine r = routine("", "09:00");
        r.setLastRunAt(at(16, 9, 0).plusSeconds(20).toOffsetDateTime());
        assertFalse(RoutineService.isDue(r, at(16, 9, 5)), "오늘 이미 실행");
        assertTrue(RoutineService.isDue(r, at(17, 9, 0)), "다음 날은 다시");

        Routine edited = routine("", "09:00");
        edited.setUpdatedAt(at(16, 14, 0).toOffsetDateTime());
        assertFalse(RoutineService.isDue(edited, at(16, 14, 1)), "오후에 만든 09:00 루틴이 곧바로 돌지 않는다");
        assertTrue(RoutineService.isDue(edited, at(17, 9, 0)));
    }

    @Test
    void disabledOrBadTimeNeverDue() {
        Routine off = routine("", "09:00");
        off.setEnabled(false);
        assertFalse(RoutineService.isDue(off, at(16, 10, 0)));
        assertFalse(RoutineService.isDue(routine("", "9시"), at(16, 10, 0)));
        assertNull(RoutineService.nextRun(off, at(16, 10, 0)));
    }

    @Test
    void nextRunSkipsToNextSelectedDay() {
        Routine r = routine("MON,WED", "09:00");
        r.setLastRunAt(at(16, 9, 0).plusSeconds(5).toOffsetDateTime());
        assertEquals(at(18, 9, 0), RoutineService.nextRun(r, at(16, 10, 0)));
        assertEquals(at(16, 9, 0), RoutineService.nextRun(routine("MON", "09:00"), at(16, 7, 0)));
    }

    @Test
    void validateNormalizes() {
        Routine r = routine("mon, FRI ,xyz", " 09:30 ");
        r.setProjects(" grp/batch , grp/api@develop ");
        r.setModel(" ");
        assertNull(RoutineService.validate(r));
        assertEquals("MON,FRI", r.getDays());
        assertEquals("09:30", r.getRunAt());
        assertEquals("grp/batch,grp/api@develop", r.getProjects());
        assertNull(r.getModel());

        Routine all = routine("MON,TUE,WED,THU,FRI,SAT,SUN", "09:00");
        assertNull(RoutineService.validate(all));
        assertEquals("", all.getDays(), "7일 모두는 매일과 같다");

        assertNotNull(RoutineService.validate(routine("", "25:00")));
        Routine noProj = routine("", "09:00");
        noProj.setProjects(" ");
        assertNotNull(RoutineService.validate(noProj));
        Routine badModel = routine("", "09:00");
        badModel.setModel("gpt");
        assertNotNull(RoutineService.validate(badModel));
    }
}
