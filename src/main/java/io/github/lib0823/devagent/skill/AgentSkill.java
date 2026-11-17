package io.github.lib0823.devagent.skill;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 플랫폼 스킬: 에이전트에게 주는 재사용 가능한 작업 지침(이름 + 한 줄 설명 + 본문).
 * 적용 단계({@link #STAGES})를 골라 두면 그 단계의 시스템 프롬프트에 실린다. 짧은 본문은 그대로, 긴 본문은 설명만 싣고
 * 필요할 때 파일({@code work/skills/<name>/SKILL.md})을 읽게 한다(점진적 공개).
 */
public class AgentSkill {

    /** 적용 단계 키: analyze(이슈 분석·설계) / fix(코드 수정) / verify(독립 리뷰) / research(질의). */
    public static final List<String> STAGES = List.of("analyze", "fix", "verify", "research");

    private String name;
    private String description;
    private String body;
    private String stages;        // 쉼표 구분 단계 키. 비우면 전 단계
    private boolean enabled = true;
    private OffsetDateTime updatedAt;

    public List<String> stageList() {
        List<String> out = new ArrayList<>();
        if (stages != null) {
            for (String s : stages.split("[,\\s]+")) {
                String k = s.strip().toLowerCase(Locale.ROOT);
                if (STAGES.contains(k) && !out.contains(k)) {
                    out.add(k);
                }
            }
        }
        return out.isEmpty() ? STAGES : out;
    }

    public boolean appliesTo(String stageKey) {
        return enabled && stageList().contains(stageKey);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getStages() { return stages; }
    public void setStages(String stages) { this.stages = stages; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
