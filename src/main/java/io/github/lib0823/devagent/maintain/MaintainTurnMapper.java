package io.github.lib0823.devagent.maintain;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface MaintainTurnMapper {

    /** 기동 시 멱등 생성 (db/schema.sql 과 동일 정의). */
    @Update("""
            CREATE TABLE IF NOT EXISTS maintain_turn (
                id                 BIGSERIAL    PRIMARY KEY,
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
                created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
                started_at         TIMESTAMPTZ,
                finished_at        TIMESTAMPTZ,
                updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_maintain_turn_thread ON maintain_turn (thread_id, id)")
    void ensureIndex();

    @Insert("""
            INSERT INTO maintain_turn (thread_id, question, state, model)
            VALUES (#{threadId}, #{question}, #{state}, #{model})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(MaintainTurn t);

    /** 새 대화의 첫 차례는 삽입 뒤 thread_id = id 로 맞춘다. */
    @Update("UPDATE maintain_turn SET thread_id = id WHERE id = #{id} AND thread_id = 0")
    int fixThread(@Param("id") long id);

    @Update("""
            UPDATE maintain_turn SET state = #{state}, answer_md = #{answerMd}, claude_session_id = #{claudeSessionId},
                last_error = #{lastError}, turns = #{turns}, input_tokens = #{inputTokens}, output_tokens = #{outputTokens},
                elapsed_ms = #{elapsedMs}, started_at = #{startedAt}, finished_at = #{finishedAt}, updated_at = now()
            WHERE id = #{id}
            """)
    int update(MaintainTurn t);

    @Select("SELECT * FROM maintain_turn WHERE id = #{id}")
    MaintainTurn find(@Param("id") long id);

    @Select("SELECT * FROM maintain_turn WHERE thread_id = #{threadId} ORDER BY id ASC")
    List<MaintainTurn> findThread(@Param("threadId") long threadId);

    @Select("SELECT * FROM maintain_turn WHERE state IN ('NEW', 'RUNNING') ORDER BY id ASC")
    List<MaintainTurn> findActive();

    /** 이 대화에서 앞선 차례가 실제로 연 Claude 세션(없으면 null → 새 세션). */
    @Select("""
            SELECT claude_session_id FROM maintain_turn
            WHERE thread_id = #{threadId} AND id < #{beforeId} AND claude_session_id IS NOT NULL AND started_at IS NOT NULL
            ORDER BY id DESC LIMIT 1
            """)
    String lastSession(@Param("threadId") long threadId, @Param("beforeId") long beforeId);

    /** 대화 목록(최근 순): 첫 요청을 제목으로, 마지막 차례의 상태를 대화 상태로. */
    @Select("""
            SELECT t.thread_id AS id, COUNT(*) AS turns, MAX(t.updated_at) AS updated_at,
                   (SELECT f.question FROM maintain_turn f WHERE f.id = t.thread_id) AS title,
                   (SELECT l.state FROM maintain_turn l WHERE l.thread_id = t.thread_id ORDER BY l.id DESC LIMIT 1) AS state
            FROM maintain_turn t GROUP BY t.thread_id ORDER BY MAX(t.id) DESC LIMIT #{limit}
            """)
    List<ThreadRow> threads(@Param("limit") int limit);

    class ThreadRow {
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
