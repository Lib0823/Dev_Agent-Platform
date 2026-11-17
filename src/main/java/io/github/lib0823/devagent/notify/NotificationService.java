package io.github.lib0823.devagent.notify;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 플랫폼 알림함: 이슈 에이전트·질의·시스템이 알릴 일을 DB 에 남기고 화면 상단 종 버튼이 읽는다.
 * 메신저와 독립이다 — 메신저 전송이 실패하거나 메신저가 없어도 알림은 여기에 남는다.
 * 가볍게: 최근 목록을 메모리에 두고(화면이 주기적으로 묻는다, 추가 때만 다시 읽음) 최근 {@link #KEEP}건만 보관한다.
 * 읽음 여부는 서버가 아니라 브라우저가 기억한다.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final int KEEP = 200;
    private static final int SHOW = 60;
    private static final int PRUNE_EVERY = 50;
    private static final int TITLE_MAX = 160;
    private static final int BODY_MAX = 600;

    private final NotificationMapper mapper;
    private volatile boolean ready;
    private volatile List<Notification> cache;
    private int sinceInsert;

    public NotificationService(NotificationMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        try {
            mapper.ensureTable();
            ready = true;
        } catch (RuntimeException e) {
            log.warn("알림 테이블 준비 실패(알림함 없이 진행): {}", e.toString());
        }
    }

    /** 알림 추가. 저장에 실패해도 예외를 던지지 않는다(알림 때문에 본 작업이 멈추면 안 된다). */
    public void add(String kind, String level, String title, String body, String link) {
        add(new Notification(kind, level, title, body, link));
    }

    /** 이슈 에이전트의 안내문(첫 줄 = 제목)을 알림으로 남긴다. 명령에 대한 되받아치기 같은 것은 건너뛴다. */
    public void addIssue(String text) {
        add(issue(text));
    }

    private void add(Notification n) {
        if (!ready || n == null || n.getTitle() == null || n.getTitle().isBlank()) {
            return;
        }
        try {
            n.setTitle(cut(n.getTitle().strip(), TITLE_MAX));
            n.setBody(n.getBody() == null || n.getBody().isBlank() ? null : cut(n.getBody().strip(), BODY_MAX));
            mapper.insert(n);
            cache = null;
            synchronized (this) {
                if (++sinceInsert >= PRUNE_EVERY) {
                    sinceInsert = 0;
                    mapper.prune(KEEP);
                }
            }
        } catch (RuntimeException e) {
            log.warn("알림 기록 실패(무시): {}", e.toString());
        }
    }

    /** 최근 알림(최신순). */
    public List<Notification> recent() {
        if (!ready) {
            return List.of();
        }
        List<Notification> c = cache;
        if (c == null) {
            try {
                c = mapper.recent(SHOW);
                cache = c;
            } catch (RuntimeException e) {
                log.warn("알림 조회 실패: {}", e.toString());
                return List.of();
            }
        }
        return c;
    }

    // ---------------------------------------------------------------- 이슈 안내문 분류(순수 함수)

    static Notification issue(String text) {
        if (text == null) {
            return null;
        }
        String rest = text.strip();
        if (rest.startsWith("[이슈]")) {
            rest = rest.substring(4).strip();
        }
        int nl = rest.indexOf('\n');
        String title = (nl < 0 ? rest : rest.substring(0, nl)).strip();
        if (title.isEmpty() || title.startsWith("(관리 페이지 답변)") || title.startsWith("(계속 ")) {
            return null;
        }
        return new Notification("issue", issueLevel(title), title, nl < 0 ? null : rest.substring(nl + 1), "/agent.html");
    }

    static String issueLevel(String title) {
        if (title.contains("실패")) {
            return "err";
        }
        if (title.contains("⚠") || title.contains("한도") || title.contains("중단") || title.contains("않았습니다")) {
            return "warn";
        }
        if (title.startsWith("설계안") || title.contains("기다리고") || title.contains("선분석 완료")
                || (title.startsWith("수정 완료 ") && !title.startsWith("수정 완료 —"))) {
            return "wait";   // 사용자의 확인을 기다리는 알림
        }
        return title.startsWith("완료 ") ? "ok" : "info";
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
