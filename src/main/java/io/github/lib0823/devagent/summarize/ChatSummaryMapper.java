package io.github.lib0823.devagent.summarize;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;

@Mapper
public interface ChatSummaryMapper {

    @Insert("""
            INSERT INTO chat_summary (room_id, room_type, from_at, to_at, message_count, summary, created_at)
            VALUES (#{roomId}, #{roomType}, #{fromAt}, #{toAt}, #{messageCount}, #{summary}, now())
            """)
    void insert(ChatSummary summary);

    /** 해당 방의 마지막 요약 끝 시각. 없으면 null. */
    @Select("SELECT max(to_at) FROM chat_summary WHERE room_id = #{roomId} AND room_type = #{roomType}")
    OffsetDateTime selectLastTo(@Param("roomId") String roomId, @Param("roomType") String roomType);
}
