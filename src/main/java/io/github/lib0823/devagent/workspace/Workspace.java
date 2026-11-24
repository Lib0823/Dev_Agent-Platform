package io.github.lib0823.devagent.workspace;

import java.time.OffsetDateTime;

/**
 * 작업 공간: 사용자가 등록한 로컬 폴더 하나. 이 폴더를 작업 디렉터리로 한 Claude 세션과 대화한다.
 * 권한({@code mode}): bypass(묻지 않고 전부 실행) / ask(파일 수정·명령 실행 전에 화면에서 확인).
 */
public class Workspace {

    public static final String BYPASS = "bypass";
    public static final String ASK = "ask";

    private long id;
    private String name;
    private String path;
    private String mode;
    private boolean opsMcp;           // 플랫폼에 등록된 MCP 도구(운영 데이터 조회 등)를 이 작업 공간 세션에 실을지
    private String skills;            // 이 작업 공간에 실을 플랫폼 스킬 이름(쉼표 구분). 적용 단계가 '작업 공간'인 스킬은 여기 없어도 실린다
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isOpsMcp() { return opsMcp; }
    public void setOpsMcp(boolean opsMcp) { this.opsMcp = opsMcp; }
    public String getSkills() { return skills; }
    public void setSkills(String skills) { this.skills = skills; }

    /** 고른 스킬 이름 목록(빈 항목 제외). */
    public java.util.List<String> skillList() {
        return skills == null || skills.isBlank() ? java.util.List.of()
                : java.util.Arrays.stream(skills.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
