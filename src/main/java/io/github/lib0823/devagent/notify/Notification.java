package io.github.lib0823.devagent.notify;

import java.time.OffsetDateTime;

/**
 * 플랫폼 알림 1건(이슈 에이전트·질의·시스템). 화면 상단 알림함이 읽는다.
 * 메신저로도 같은 내용이 가지만 알림함은 메신저 연결 여부와 무관하게 동작한다.
 */
public class Notification {

    private Long id;
    private String kind;      // issue | research | system
    private String level;     // info | ok | wait | warn | err
    private String title;
    private String body;      // 없을 수 있음
    private String link;      // 눌렀을 때 갈 화면
    private OffsetDateTime createdAt;

    public Notification() {
    }

    public Notification(String kind, String level, String title, String body, String link) {
        this.kind = kind;
        this.level = level;
        this.title = title;
        this.body = body;
        this.link = link;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
