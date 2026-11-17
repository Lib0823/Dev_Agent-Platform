package io.github.lib0823.devagent.mention;

import io.github.lib0823.devagent.config.BotProperties;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.ops.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 멘션 처리 폴링. 수집기가 적재·플래그한 멘션을 주기적으로 처리한다.
 */
@Component
public class MentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MentionScheduler.class);

    private final MessengerService client;
    private final MentionService mentionService;
    private final BotProperties bot;
    private final AlertService alert;

    public MentionScheduler(MessengerService client, MentionService mentionService, BotProperties bot, AlertService alert) {
        this.client = client;
        this.mentionService = mentionService;
        this.bot = bot;
        this.alert = alert;
    }

    @Scheduled(fixedDelayString = "${bot.mention.poll-interval-ms}", initialDelay = 5000)
    public void poll() {
        if (!bot.mention().enabled()) {
            return;
        }
        try {
            client.connect();
            mentionService.process();
        } catch (Exception e) {
            log.error("멘션 폴링 오류 — 세션 무효화: {}", e.toString());
            client.reset();
            alert.alert("멘션 폴링 실패", e);
        }
    }
}
