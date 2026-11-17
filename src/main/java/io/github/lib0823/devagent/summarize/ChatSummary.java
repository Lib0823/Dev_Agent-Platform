package io.github.lib0823.devagent.summarize;

import java.time.OffsetDateTime;

/**
 * chat_summary 행.
 */
public class ChatSummary {

    private Long id;
    private String roomId;
    private String roomType;
    private OffsetDateTime fromAt;
    private OffsetDateTime toAt;
    private int messageCount;
    private String summary;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getRoomType() {
        return roomType;
    }

    public void setRoomType(String roomType) {
        this.roomType = roomType;
    }

    public OffsetDateTime getFromAt() {
        return fromAt;
    }

    public void setFromAt(OffsetDateTime fromAt) {
        this.fromAt = fromAt;
    }

    public OffsetDateTime getToAt() {
        return toAt;
    }

    public void setToAt(OffsetDateTime toAt) {
        this.toAt = toAt;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
