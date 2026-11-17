package io.github.lib0823.devagent.admin;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SettingMapper {

    @Select("SELECT value FROM bot_setting WHERE key = #{key}")
    String get(@Param("key") String key);

    @Insert("""
            INSERT INTO bot_setting (key, value, updated_at) VALUES (#{key}, #{value}, now())
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
            """)
    void upsert(@Param("key") String key, @Param("value") String value);
}
