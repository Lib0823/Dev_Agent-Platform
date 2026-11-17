package io.github.lib0823.devagent.notify;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NotificationMapper {

    /** 기동 시 멱등 생성 (db/schema.sql 과 동일 정의). */
    @Update("""
            CREATE TABLE IF NOT EXISTS notification (
                id          BIGSERIAL    PRIMARY KEY,
                kind        VARCHAR(16)  NOT NULL,
                level       VARCHAR(8)   NOT NULL,
                title       VARCHAR(200) NOT NULL,
                body        TEXT,
                link        VARCHAR(200),
                created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTable();

    @Insert("INSERT INTO notification (kind, level, title, body, link) VALUES (#{kind}, #{level}, #{title}, #{body}, #{link})")
    int insert(Notification n);

    @Select("SELECT * FROM notification ORDER BY id DESC LIMIT #{limit}")
    List<Notification> recent(@Param("limit") int limit);

    /** 최근 keep 건만 남긴다. */
    @Delete("DELETE FROM notification WHERE id <= (SELECT id FROM notification ORDER BY id DESC OFFSET #{keep} LIMIT 1)")
    int prune(@Param("keep") int keep);
}
