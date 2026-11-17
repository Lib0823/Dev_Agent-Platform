package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.notify.Notification;
import io.github.lib0823.devagent.notify.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 알림함(상단 종 버튼)의 플랫폼 알림 목록. 화면이 주기적으로 부르므로 메모리 캐시만 읽는다. */
@RestController
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/notifications")
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Notification n : notifications.recent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("kind", n.getKind());
            m.put("level", n.getLevel());
            m.put("title", n.getTitle());
            m.put("body", n.getBody());
            m.put("link", n.getLink());
            m.put("at", n.getCreatedAt() == null ? null : n.getCreatedAt().toInstant().toEpochMilli());
            items.add(m);
        }
        return Map.of("items", items);
    }
}
