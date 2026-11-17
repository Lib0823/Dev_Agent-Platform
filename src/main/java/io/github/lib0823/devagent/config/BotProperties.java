package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 봇 동작/안전 설정 (prefix: bot).
 */
@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        Poc poc,
        Collect collect,
        Summary summary,
        Mention mention,
        Ops ops,
        Safety safety
) {
    /**
     * 운영 설정. 시작/오류 알림을 "나에게 보내기" 로 전달.
     */
    public record Ops(
            boolean notifyOnStartup,
            int alertCooldownMinutes
    ) {
    }

    /**
     * 멘션 자동응답 설정. 1차는 DRAFT(초안을 나에게 전달)만.
     */
    public record Mention(
            boolean enabled,
            int contextSize,
            int cooldownMinutes,
            long pollIntervalMs,
            boolean allowSelfSender,           // 테스트용: 내가 보낸 멘션도 응답 (운영 false)
            List<String> keywords,             // 남이 나를 부르는 키워드들(@홍길동, @길동 … 접미사가 붙은 "@길동BE" 도 포함)
            String commandKeyword,             // 내가 봇을 직접 호출하는 키워드(예 @AI). 내 메시지여도 응답
            List<String> sensitiveKeywords,
            List<String> autoRoomKeys,         // 이 방들에서만 AUTO(실제 회신). 그 외엔 DRAFT. 형식 "self:440","group:17617"
            boolean progressAck                // @AI 방 응답 중 문서 참고 등 느린 턴에 "답변 생성 중" 안내 선전송(기본 off)
    ) {
    }

    /**
     * 요약 배치 설정. 방별로 마지막 요약 이후 메시지를 모아 요약하고 나에게 전달한다.
     */
    public record Summary(
            boolean enabled,
            String cron,
            int minMessages,
            int maxMessages,
            boolean runOnStartup
    ) {
    }

    public record Poc(
            boolean enabled,
            boolean sendTestToSelf
    ) {
    }

    /**
     * 수집기 설정.
     * mode: SELF_ONLY(자기방만) | LIST(room-keys 지정 방) | ALL(제외 외 전부)
     * room-key 형식: "{roomType}:{id}" 예) "self:440", "single:586", "group:15428"
     */
    public record Collect(
            boolean enabled,
            long pollIntervalMs,
            Mode mode,
            List<String> roomKeys,
            List<String> excludedRoomKeys,
            int initialWindow,
            int pageSize,
            int maxPagesPerPoll
    ) {
        public enum Mode {SELF_ONLY, LIST, ALL}
    }

    /**
     * 전송 안전장치. 현재 단계에서는 그룹방 전송을 영구 차단하고,
     * 1:1 전송은 허용 목록(buddySeq)에 있는 상대에게만 허용한다.
     */
    public record Safety(
            boolean blockGroupSend,
            List<String> allowedSingleRoomIds
    ) {
    }
}
