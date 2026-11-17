package io.github.lib0823.devagent.routine;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 루틴(예약 질의): 정해 둔 요일·시각마다 같은 질문을 질의 워크플로우에 접수한다.
 * 예) 매주 월요일 09:00 "지난주 변경된 배치 잡 정리". 결과는 질의 탭에 쌓이고, 알림을 켜면 자기방으로도 간다.
 */
public class Routine {

    /** 요일 키(저장 형식). 비우면 매일. */
    public static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private Long id;
    private String name;
    private String question;
    private String projects;      // 쉼표 구분 group/repo[@branch]
    private String model;         // 비우면 질의 기본 모델
    private String effort;
    private Boolean verify;       // null 이면 질의 기본값
    private String days;          // 쉼표 구분 요일 키. 비우면 매일
    private String runAt;         // HH:mm (Asia/Seoul)
    private boolean notify;       // 완료 시 자기방 알림
    private boolean enabled = true;
    private OffsetDateTime lastRunAt;
    private Long lastJobId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public List<DayOfWeek> dayList() {
        List<DayOfWeek> out = new ArrayList<>();
        if (days != null) {
            for (String d : days.split("[,\s]+")) {
                int i = DAYS.indexOf(d.strip().toUpperCase(Locale.ROOT));
                if (i >= 0 && !out.contains(DayOfWeek.of(i + 1))) {
                    out.add(DayOfWeek.of(i + 1));
                }
            }
        }
        return out;
    }

    public List<String> projectList() {
        List<String> out = new ArrayList<>();
        if (projects != null) {
            for (String p : projects.split(",")) {
                if (!p.isBlank()) {
                    out.add(p.strip());
                }
            }
        }
        return out;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getProjects() { return projects; }
    public void setProjects(String projects) { this.projects = projects; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }
    public Boolean getVerify() { return verify; }
    public void setVerify(Boolean verify) { this.verify = verify; }
    public String getDays() { return days; }
    public void setDays(String days) { this.days = days; }
    public String getRunAt() { return runAt; }
    public void setRunAt(String runAt) { this.runAt = runAt; }
    public boolean isNotify() { return notify; }
    public void setNotify(boolean notify) { this.notify = notify; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(OffsetDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public Long getLastJobId() { return lastJobId; }
    public void setLastJobId(Long lastJobId) { this.lastJobId = lastJobId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
