package io.github.lib0823.devagent.workspace;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface WorkspaceMapper {

    // ---------------------------------------------------------------- 테이블 (db/schema.sql 과 동일 정의, 기동 시 멱등 생성)

    @Update("""
            CREATE TABLE IF NOT EXISTS workspace (
                id          BIGSERIAL    PRIMARY KEY,
                name        VARCHAR(80)  NOT NULL,
                path        TEXT         NOT NULL,
                mode        VARCHAR(8)   NOT NULL DEFAULT 'bypass',
                ops_mcp     BOOLEAN      NOT NULL DEFAULT false,
                skills      TEXT,
                created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
                updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS workspace_turn (
                id                 BIGSERIAL    PRIMARY KEY,
                workspace_id       BIGINT       NOT NULL,
                thread_id          BIGINT       NOT NULL DEFAULT 0,
                question           TEXT         NOT NULL,
                answer_md          TEXT,
                state              VARCHAR(16)  NOT NULL,
                claude_session_id  TEXT,
                model              VARCHAR(32),
                last_error         TEXT,
                turns              INT          NOT NULL DEFAULT 0,
                input_tokens       BIGINT       NOT NULL DEFAULT 0,
                output_tokens      BIGINT       NOT NULL DEFAULT 0,
                elapsed_ms         BIGINT       NOT NULL DEFAULT 0,
                changed            TEXT,
                origin             VARCHAR(16),
                reverted_at        TIMESTAMPTZ,
                created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
                started_at         TIMESTAMPTZ,
                finished_at        TIMESTAMPTZ,
                updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTurnTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_workspace_turn_thread ON workspace_turn (workspace_id, thread_id, id)")
    void ensureIndex();

    /** 1.16.0: 작업 공간마다 고른 스킬. */
    @Update("ALTER TABLE workspace ADD COLUMN IF NOT EXISTS skills TEXT")
    void ensureSkillsColumn();

    /** 1.16.0: 권한이 read/edit/full 세 단계에서 ask/bypass 둘로 바뀌었다 — full 은 bypass, 나머지는 ask 로 옮긴다(멱등). */
    @Update("UPDATE workspace SET mode = CASE WHEN mode = 'full' THEN 'bypass' ELSE 'ask' END WHERE mode NOT IN ('bypass', 'ask')")
    int migrateModes();

    /** 1.15.0: 이 차례에 바뀐 파일 목록. */
    @Update("ALTER TABLE workspace_turn ADD COLUMN IF NOT EXISTS changed TEXT")
    void ensureChangedColumn();

    /** 1.18.0: 접수 경로(메신저)와 되돌린 시각. */
    @Update("ALTER TABLE workspace_turn ADD COLUMN IF NOT EXISTS origin VARCHAR(16)")
    void ensureOriginColumn();

    @Update("ALTER TABLE workspace_turn ADD COLUMN IF NOT EXISTS reverted_at TIMESTAMPTZ")
    void ensureRevertedColumn();

    // ---------------------------------------------------------------- 작업 공간

    @Insert("INSERT INTO workspace (name, path, mode, ops_mcp, skills) VALUES (#{name}, #{path}, #{mode}, #{opsMcp}, #{skills})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertWorkspace(Workspace w);

    @Update("UPDATE workspace SET name = #{name}, path = #{path}, mode = #{mode}, ops_mcp = #{opsMcp}, skills = #{skills}, updated_at = now() WHERE id = #{id}")
    int updateWorkspace(Workspace w);

    @Select("SELECT * FROM workspace WHERE id = #{id}")
    Workspace findWorkspace(@Param("id") long id);

    @Select("SELECT * FROM workspace ORDER BY name ASC, id ASC")
    List<Workspace> workspaces();

    @Delete("DELETE FROM workspace WHERE id = #{id}")
    int deleteWorkspace(@Param("id") long id);

    @Select("SELECT id FROM workspace_turn WHERE workspace_id = #{id}")
    List<Long> turnIds(@Param("id") long id);

    @Delete("DELETE FROM workspace_turn WHERE workspace_id = #{id}")
    int deleteTurns(@Param("id") long id);

    // ---------------------------------------------------------------- 차례

    @Insert("""
            INSERT INTO workspace_turn (workspace_id, thread_id, question, state, model, origin)
            VALUES (#{workspaceId}, #{threadId}, #{question}, #{state}, #{model}, #{origin})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(WorkspaceTurn t);

    /** 새 대화의 첫 차례는 삽입 뒤 thread_id = id 로 맞춘다. */
    @Update("UPDATE workspace_turn SET thread_id = id WHERE id = #{id} AND thread_id = 0")
    int fixThread(@Param("id") long id);

    @Update("""
            UPDATE workspace_turn SET state = #{state}, answer_md = #{answerMd}, claude_session_id = #{claudeSessionId},
                last_error = #{lastError}, turns = #{turns}, input_tokens = #{inputTokens}, output_tokens = #{outputTokens},
                elapsed_ms = #{elapsedMs}, changed = #{changed}, started_at = #{startedAt}, finished_at = #{finishedAt}, updated_at = now()
            WHERE id = #{id}
            """)
    int update(WorkspaceTurn t);

    @Update("UPDATE workspace_turn SET reverted_at = now(), updated_at = now() WHERE id = #{id}")
    int markReverted(@Param("id") long id);

    /** 작업 공간에서 가장 최근에 쓴 대화(메신저에서 「이어서」 보낼 때). */
    @Select("SELECT thread_id FROM workspace_turn WHERE workspace_id = #{workspaceId} ORDER BY id DESC LIMIT 1")
    Long lastThread(@Param("workspaceId") long workspaceId);

    @Select("SELECT * FROM workspace_turn WHERE id = #{id}")
    WorkspaceTurn find(@Param("id") long id);

    @Select("SELECT * FROM workspace_turn WHERE thread_id = #{threadId} ORDER BY id ASC")
    List<WorkspaceTurn> findThread(@Param("threadId") long threadId);

    @Select("SELECT * FROM workspace_turn WHERE state IN ('NEW', 'RUNNING') ORDER BY id ASC")
    List<WorkspaceTurn> findActive();

    @Select("SELECT * FROM workspace_turn WHERE workspace_id = #{workspaceId} AND state IN ('NEW', 'RUNNING') ORDER BY id ASC")
    List<WorkspaceTurn> findActiveIn(@Param("workspaceId") long workspaceId);

    /** 이 대화에서 앞선 차례가 실제로 연 Claude 세션(없으면 null → 새 세션). */
    @Select("""
            SELECT claude_session_id FROM workspace_turn
            WHERE thread_id = #{threadId} AND id < #{beforeId} AND claude_session_id IS NOT NULL AND started_at IS NOT NULL
            ORDER BY id DESC LIMIT 1
            """)
    String lastSession(@Param("threadId") long threadId, @Param("beforeId") long beforeId);

    /** 작업 공간의 대화 목록(최근 순): 첫 요청을 제목으로, 마지막 차례의 상태를 대화 상태로. */
    @Select("""
            SELECT t.thread_id AS id, COUNT(*) AS turns, MAX(t.updated_at) AS updated_at,
                   (SELECT f.question FROM workspace_turn f WHERE f.id = t.thread_id) AS title,
                   (SELECT l.state FROM workspace_turn l WHERE l.thread_id = t.thread_id ORDER BY l.id DESC LIMIT 1) AS state
            FROM workspace_turn t WHERE t.workspace_id = #{workspaceId}
            GROUP BY t.thread_id ORDER BY MAX(t.id) DESC LIMIT #{limit}
            """)
    List<ThreadRow> threads(@Param("workspaceId") long workspaceId, @Param("limit") int limit);

    /** 모든 작업 공간의 최근 대화(홈 화면 요약용). */
    @Select("""
            SELECT t.thread_id AS id, t.workspace_id AS workspace_id, w.name AS workspace, COUNT(*) AS turns, MAX(t.updated_at) AS updated_at,
                   (SELECT f.question FROM workspace_turn f WHERE f.id = t.thread_id) AS title,
                   (SELECT l.state FROM workspace_turn l WHERE l.thread_id = t.thread_id ORDER BY l.id DESC LIMIT 1) AS state
            FROM workspace_turn t JOIN workspace w ON w.id = t.workspace_id
            GROUP BY t.thread_id, t.workspace_id, w.name ORDER BY MAX(t.id) DESC LIMIT #{limit}
            """)
    List<ThreadRow> recentThreads(@Param("limit") int limit);

    class ThreadRow {
        private long workspaceId;
        private String workspace;
        public long getWorkspaceId() { return workspaceId; }
        public void setWorkspaceId(long workspaceId) { this.workspaceId = workspaceId; }
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }
        private long id;
        private int turns;
        private String title;
        private String state;
        private OffsetDateTime updatedAt;
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public int getTurns() { return turns; }
        public void setTurns(int turns) { this.turns = turns; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public OffsetDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    }
}
