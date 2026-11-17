package io.github.lib0823.devagent.summarize;

import io.github.lib0823.devagent.config.BotProperties;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.ops.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 요약 배치 스케줄러. cron 주기로 요약을 수행한다.
 * bot.summary.run-on-startup=true 이면 기동 직후 1회 실행(테스트용).
 */
@Component
public class SummaryScheduler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SummaryScheduler.class);

    private final SummaryService summaryService;
    private final MessengerService client;
    private final BotProperties bot;
    private final AlertService alert;

    public SummaryScheduler(SummaryService summaryService, MessengerService client, BotProperties bot, AlertService alert) {
        this.summaryService = summaryService;
        this.client = client;
        this.bot = bot;
        this.alert = alert;
    }

    @Scheduled(cron = "${bot.summary.cron}")
    public void scheduled() {
        if (!bot.summary().enabled()) {
            return;
        }
        run("cron");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bot.summary().enabled() && bot.summary().runOnStartup()) {
            log.info("요약 run-on-startup 실행");
            run("startup");
        }
    }

    private void run(String trigger) {
        try {
            client.connect();
            summaryService.summarizeAll();
        } catch (Exception e) {
            log.error("요약 배치 오류 ({}) — 세션 무효화: {}", trigger, e.toString());
            client.reset();
            alert.alert("요약 배치 실패(" + trigger + ")", e);
        }
    }
}
