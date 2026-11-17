package io.github.lib0823.devagent.mcp;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface McpServerMapper {

    /** 기동 시 멱등 생성 (db/schema.sql 과 동일 정의). */
    @Update("""
            CREATE TABLE IF NOT EXISTS mcp_server (
                name         VARCHAR(32)  PRIMARY KEY,
                type         VARCHAR(8)   NOT NULL,
                url          TEXT,
                command      TEXT,
                args         TEXT,
                headers      TEXT,
                tools        TEXT,
                description  TEXT,
                enabled      BOOLEAN      NOT NULL DEFAULT true,
                updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTable();

    @Select("SELECT * FROM mcp_server ORDER BY name")
    List<McpServer> findAll();

    @Select("SELECT * FROM mcp_server WHERE name = #{name}")
    McpServer find(@Param("name") String name);

    @Insert("""
            INSERT INTO mcp_server (name, type, url, command, args, headers, tools, description, enabled)
            VALUES (#{name}, #{type}, #{url}, #{command}, #{args}, #{headers}, #{tools}, #{description}, #{enabled})
            ON CONFLICT (name) DO UPDATE SET type = EXCLUDED.type, url = EXCLUDED.url, command = EXCLUDED.command, args = EXCLUDED.args,
                headers = EXCLUDED.headers, tools = EXCLUDED.tools, description = EXCLUDED.description, enabled = EXCLUDED.enabled,
                updated_at = now()
            """)
    int upsert(McpServer s);

    @Delete("DELETE FROM mcp_server WHERE name = #{name}")
    int delete(@Param("name") String name);
}
