package io.github.lib0823.devagent.collect;

import io.github.lib0823.devagent.config.BotProperties;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.messenger.Room;
import io.github.lib0823.devagent.ops.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 주기적 폴링. 세션 확인 → 대상 방 수집. 방 단위 실패는 격리하고, 폴링 전체 실패 시 세션을 무효화한다.
 */
@Component
public class CollectScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectScheduler.class);

    private final MessengerService client;
    private final CollectService collectService;
    private final BotProperties bot;
    private final AlertService alert;
    private static final int FULL_SWEEP_EVERY = 15;
    private long polls;

    public CollectScheduler(MessengerService client, CollectService collectService, BotProperties bot, AlertService alert) {
        this.client = client;
        this.collectService = collectService;
        this.bot = bot;
        this.alert = alert;
    }

    @Scheduled(fixedDelayString = "${bot.collect.poll-interval-ms}", initialDelay = 2000)
    public void poll() {
        if (!bot.collect().enabled()) {
            return;
        }
        try {
            client.connect();
            List<Room> targets = collectService.resolveTargets();
            log.debug("폴링 시작 — 대상 방 {}개 (mode={})", targets.size(), bot.collect().mode());
            // 변동 없는 방은 조회를 건너뛰되(방 목록의 마지막 메시지 id == 커서), FULL_SWEEP_EVERY 번째 폴링은 전부 조회해 판단을 검증한다
            boolean fullSweep = (++polls % FULL_SWEEP_EVERY) == 0;
            java.util.Map<String, RoomCursor> cursors = collectService.loadCursors();
            int total = 0;
            for (Room room : targets) {
                try {
                    total += collectService.collectRoom(room, !fullSweep, cursors.get(room.key()));
                } catch (Exception e) {
                    log.warn("방 수집 실패 '{}': {}", room.name(), e.toString());
                }
            }
            if (total > 0) {
                log.info("폴링 완료 — 신규 {}건", total);
            }
        } catch (Exception e) {
            log.error("폴링 오류 — 세션 무효화 후 다음 주기 재시도: {}", e.toString());
            client.reset();
            alert.alert("수집 폴링 실패", e);
        }
    }
}
