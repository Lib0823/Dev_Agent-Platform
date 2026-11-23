package io.github.lib0823.devagent.skill;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentSkillMapper {

    /** 기동 시 멱등 생성 (db/schema.sql 과 동일 정의). */
    @Update("""
            CREATE TABLE IF NOT EXISTS agent_skill (
                name         VARCHAR(48)  PRIMARY KEY,
                description  TEXT         NOT NULL,
                body         TEXT         NOT NULL,
                stages       TEXT,
                enabled      BOOLEAN      NOT NULL DEFAULT true,
                updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTable();

    @Select("SELECT * FROM agent_skill ORDER BY name")
    List<AgentSkill> findAll();

    @Insert("""
            INSERT INTO agent_skill (name, description, body, stages, enabled)
            VALUES (#{name}, #{description}, #{body}, #{stages}, #{enabled})
            ON CONFLICT (name) DO UPDATE SET description = EXCLUDED.description, body = EXCLUDED.body, stages = EXCLUDED.stages,
                enabled = EXCLUDED.enabled, updated_at = now()
            """)
    int upsert(AgentSkill s);

    /** 같은 이름이 없을 때만 넣는다(기본 스킬 심기용 — 사용자가 고친 스킬을 덮어쓰지 않는다). */
    @Insert("""
            INSERT INTO agent_skill (name, description, body, stages, enabled)
            VALUES (#{name}, #{description}, #{body}, #{stages}, #{enabled})
            ON CONFLICT (name) DO NOTHING
            """)
    int insertIfAbsent(AgentSkill s);

    @Delete("DELETE FROM agent_skill WHERE name = #{name}")
    int delete(@Param("name") String name);
}
