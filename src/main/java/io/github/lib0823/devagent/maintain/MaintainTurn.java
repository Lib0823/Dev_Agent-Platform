package io.github.lib0823.devagent.maintain;

import java.time.OffsetDateTime;

/**
 * 플랫폼 관리 대화의 한 차례(요청 + 답변). 같은 대화(thread_id = 첫 차례의 id)의 차례들은 하나의 Claude 세션을 {@code --resume} 으로 이어간다.
 * 상태: NEW → RUNNING → DONE / FAILED / ABORTED.
 */
public class MaintainTurn {

    public static final String NEW = "NEW";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String ABORTED = "ABORTED";

    private long id;
    private long threadId;
    private String question;
    private String answerMd;
    private String state;
    private String claudeSessionId;
    private String model;             // 비어 있으면 에이전트 설정의 '개발' 모델
    private String lastError;
    private int turns;
    private long inputTokens;
    private long outputTokens;
    private long elapsedMs;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime updatedAt;

    public boolean isActive() {
        return NEW.equals(state) || RUNNING.equals(state);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getThreadId() { return threadId; }
    public void setThreadId(long threadId) { this.threadId = threadId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswerMd() { return answerMd; }
    public void setAnswerMd(String answerMd) { this.answerMd = answerMd; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public int getTurns() { return turns; }
    public void setTurns(int turns) { this.turns = turns; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
