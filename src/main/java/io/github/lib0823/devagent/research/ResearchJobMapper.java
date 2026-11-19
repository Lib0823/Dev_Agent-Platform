package io.github.lib0823.devagent.research;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ResearchJobMapper {

    /** 기동 시 멱등 생성 (db/schema.sql 과 동일 정의). */
    @Update("""
            CREATE TABLE IF NOT EXISTS research_job (
                id                 BIGSERIAL    PRIMARY KEY,
                parent_id          BIGINT,
                root_id            BIGINT       NOT NULL DEFAULT 0,
                question           TEXT         NOT NULL,
                projects           TEXT         NOT NULL,
                model              VARCHAR(32),
                effort             VARCHAR(16),
                state              VARCHAR(16)  NOT NULL,
                claude_session_id  TEXT,
                result_md          TEXT,
                last_error         TEXT,
                turns              INT          NOT NULL DEFAULT 0,
                input_tokens       BIGINT       NOT NULL DEFAULT 0,
                output_tokens      BIGINT       NOT NULL DEFAULT 0,
                elapsed_ms         BIGINT       NOT NULL DEFAULT 0,
                notify             BOOLEAN      NOT NULL DEFAULT false,
                requester          VARCHAR(32),
                created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
                started_at         TIMESTAMPTZ,
                finished_at        TIMESTAMPTZ,
                updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_research_job_root ON research_job (root_id, id)")
    void ensureIndex();

    /** 2차 검증·결과 형식 컬럼(멱등). */
    @Update("""
            ALTER TABLE research_job
                ADD COLUMN IF NOT EXISTS verify BOOLEAN NOT NULL DEFAULT false,
                ADD COLUMN IF NOT EXISTS verify_state VARCHAR(16),
                ADD COLUMN IF NOT EXISTS verify_md TEXT,
                ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'doc'
            """)
    void ensureColumns();

    @Insert("""
            INSERT INTO research_job (parent_id, root_id, question, projects, model, effort, state, claude_session_id, notify, requester, verify, kind)
            VALUES (#{parentId}, #{rootId}, #{question}, #{projects}, #{model}, #{effort}, #{state}, #{claudeSessionId}, #{notify}, #{requester}, #{verify}, #{kind})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ResearchJob j);

    /** 루트는 삽입 뒤 root_id = id 로 맞춘다. */
    @Update("UPDATE research_job SET root_id = id WHERE id = #{id} AND root_id = 0")
    int fixRoot(@Param("id") long id);

    @Update("""
            UPDATE research_job SET state = #{state}, claude_session_id = #{claudeSessionId}, result_md = #{resultMd},
                last_error = #{lastError}, turns = #{turns}, input_tokens = #{inputTokens}, output_tokens = #{outputTokens},
                elapsed_ms = #{elapsedMs}, started_at = #{startedAt}, finished_at = #{finishedAt},
                verify_state = #{verifyState}, verify_md = #{verifyMd}, updated_at = now()
            WHERE id = #{id}
            """)
    int update(ResearchJob j);

    @Select("SELECT * FROM research_job WHERE id = #{id}")
    ResearchJob find(@Param("id") long id);

    /** 루트 목록(최신 순). 목록엔 결과 본문을 싣지 않는다. */
    @Select("""
            SELECT id, parent_id, root_id, question, projects, model, effort, state, claude_session_id, NULL AS result_md,
                   last_error, turns, input_tokens, output_tokens, elapsed_ms, notify, requester, verify, verify_state, NULL AS verify_md, kind,
                   created_at, started_at, finished_at, updated_at
            FROM research_job WHERE parent_id IS NULL ORDER BY id DESC LIMIT #{limit}
            """)
    List<ResearchJob> findRoots(@Param("limit") int limit);

    /** 스레드 전체(루트 + 후속), 오래된 순. */
    @Select("SELECT * FROM research_job WHERE root_id = #{rootId} ORDER BY id ASC")
    List<ResearchJob> findThread(@Param("rootId") long rootId);

    @Select("SELECT * FROM research_job WHERE state = #{state} ORDER BY id ASC")
    List<ResearchJob> findByState(@Param("state") String state);

    @Select("SELECT * FROM research_job WHERE state = 'NEW' ORDER BY id ASC LIMIT 1")
    ResearchJob findNextNew();

    /** 스레드별 상태 요약용: 루트의 자식 수와 마지막 항목 상태. */
    @Select("""
            <script>
            SELECT root_id AS id, COUNT(*) - 1 AS followups,
                   (SELECT state FROM research_job r2 WHERE r2.root_id = r.root_id ORDER BY id DESC LIMIT 1) AS state,
                   MAX(updated_at) AS updated_at
            FROM research_job r WHERE root_id IN
            <foreach collection="rootIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
            GROUP BY root_id
            </script>
            """)
    List<ThreadSummary> summaries(@Param("rootIds") List<Long> rootIds);

    class ThreadSummary {
        private long id;
        private int followups;
        private String state;
        private java.time.OffsetDateTime updatedAt;
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public int getFollowups() { return followups; }
        public void setFollowups(int followups) { this.followups = followups; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public java.time.OffsetDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(java.time.OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    }
}
