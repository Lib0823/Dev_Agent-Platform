package io.github.lib0823.devagent.llm;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 사용량 한도 메시지 감지와 리셋 시각 파싱(실측 문구: "You've hit your session limit · resets 2:20pm (Asia/Seoul)"). */
class UsageServiceTest {

    @Test
    void detectsLimitText() {
        assertTrue(UsageService.isLimitText("You've hit your session limit · resets 2:20pm (Asia/Seoul)"));
        assertTrue(UsageService.isLimitText("You've hit your usage limit. Your limit will reset at 9am"));
        assertFalse(UsageService.isLimitText("## 변경 요약\n- 파일 3개 수정"));
        assertFalse(UsageService.isLimitText(null));
    }

    @Test
    void parsesResetTimeTodayOrTomorrow() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Instant now = ZonedDateTime.of(2026, 11, 12, 12, 1, 0, 0, seoul).toInstant();
        Instant until = UsageService.parseReset("You've hit your session limit · resets 2:20pm (Asia/Seoul)", now);
        assertEquals(ZonedDateTime.of(2026, 11, 12, 14, 20, 0, 0, seoul).toInstant(), until);

        Instant late = ZonedDateTime.of(2026, 11, 12, 15, 0, 0, 0, seoul).toInstant();
        assertEquals(ZonedDateTime.of(2026, 11, 13, 14, 20, 0, 0, seoul).toInstant(),
                UsageService.parseReset("resets 2:20pm (Asia/Seoul)", late), "이미 지난 시각이면 내일");

        assertEquals(ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, seoul).toInstant(),
                UsageService.parseReset("limit will reset at 9am", late));
        assertNull(UsageService.parseReset("no time here", now));
    }
}
