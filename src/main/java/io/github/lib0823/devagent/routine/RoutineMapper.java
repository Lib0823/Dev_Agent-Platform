package io.github.lib0823.devagent.routine;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RoutineMapper {

    /** 기동 시 멱등 생성 (db/schema.sql 과 동일 정의). */
    @Update("""
            CREATE TABLE IF NOT EXISTS routine (
                id           BIGSERIAL    PRIMARY KEY,
                name         VARCHAR(80)  NOT NULL,
                question     TEXT         NOT NULL,
                projects     TEXT         NOT NULL,
                model        VARCHAR(16),
                effort       VARCHAR(16),
                verify       BOOLEAN,
                days         VARCHAR(40),
                run_at       VARCHAR(5)   NOT NULL,
                notify       BOOLEAN      NOT NULL DEFAULT false,
                enabled      BOOLEAN      NOT NULL DEFAULT true,
                last_run_at  TIMESTAMPTZ,
                last_job_id  BIGINT,
                created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTable();

    @Select("SELECT * FROM routine ORDER BY id")
    List<Routine> findAll();

    @Select("SELECT * FROM routine WHERE id = #{id}")
    Routine find(@Param("id") long id);

    @Insert("""
            INSERT INTO routine (name, question, projects, model, effort, verify, days, run_at, notify, enabled)
            VALUES (#{name}, #{question}, #{projects}, #{model}, #{effort}, #{verify}, #{days}, #{runAt}, #{notify}, #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Routine r);

    /** 내용 수정 — updated_at 을 갱신해 "수정 직후 지난 시각이 바로 실행되는" 일을 막는다. */
    @Update("""
            UPDATE routine SET name = #{name}, question = #{question}, projects = #{projects}, model = #{model}, effort = #{effort},
                verify = #{verify}, days = #{days}, run_at = #{runAt}, notify = #{notify}, enabled = #{enabled}, updated_at = now()
            WHERE id = #{id}
            """)
    int update(Routine r);

    @Update("UPDATE routine SET last_run_at = now(), last_job_id = #{jobId} WHERE id = #{id}")
    int markRun(@Param("id") long id, @Param("jobId") Long jobId);

    @Delete("DELETE FROM routine WHERE id = #{id}")
    int delete(@Param("id") long id);
}
