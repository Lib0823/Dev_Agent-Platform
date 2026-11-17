package io.github.lib0823.devagent.mention;

/**
 * mention_reply 행. message_seq(chat_message.seq) PK 로 1회만 처리(중복 응답 방지).
 */
public class MentionReply {

    private long messageSeq;
    private String roomId;
    private String mode;        // DRAFT | AUTO | SKIPPED
    private String replyText;
    private String skipReason;

    public long getMessageSeq() {
        return messageSeq;
    }

    public void setMessageSeq(long messageSeq) {
        this.messageSeq = messageSeq;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getReplyText() {
        return replyText;
    }

    public void setReplyText(String replyText) {
        this.replyText = replyText;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }
}
