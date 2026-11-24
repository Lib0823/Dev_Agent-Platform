package io.github.lib0823.devagent.workspace;

import java.time.OffsetDateTime;

/**
 * 작업 공간: 사용자가 등록한 로컬 폴더 하나. 이 폴더를 작업 디렉터리로 한 Claude 세션과 대화한다.
 * 권한({@code mode}): read(읽기만) / edit(파일 편집까지) / full(편집 + 명령 실행).
 */
public class Workspace {

    public static final String READ = "read";
    public static final String EDIT = "edit";
    public static final String FULL = "full";

    private long id;
    private String name;
    private String path;
    private String mode;
    private boolean opsMcp;           // 운영 MCP(읽기 전용 도구)를 이 작업 공간 세션에 실을지
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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
