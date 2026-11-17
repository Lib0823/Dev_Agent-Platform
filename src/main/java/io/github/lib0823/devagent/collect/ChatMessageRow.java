package io.github.lib0823.devagent.collect;

import java.time.OffsetDateTime;

/**
 * chat_message 적재 행. MyBatis 호환을 위해 JavaBean 형태.
 */
public class ChatMessageRow {

    private long seq;            // 로컬 적재 순번(정렬·참조용). 메신저의 id 와 무관
    private String messageId;    // 메신저가 준 메시지 id(문자열, 방 안에서 고유)
    private String roomId;
    private String roomType;     // self | single | group
    private String senderId;
    private String senderName;
    private String body;
    private OffsetDateTime sentAt;
    private boolean mentionedMe;
    private String rawJson;

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
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

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(OffsetDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public boolean isMentionedMe() {
        return mentionedMe;
    }

    public void setMentionedMe(boolean mentionedMe) {
        this.mentionedMe = mentionedMe;
    }

    public String getRawJson() {
        return rawJson;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }
}
