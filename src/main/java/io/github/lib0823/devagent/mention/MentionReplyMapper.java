package io.github.lib0823.devagent.mention;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;

@Mapper
public interface MentionReplyMapper {

    @Insert("""
            INSERT INTO mention_reply (message_seq, room_id, mode, reply_text, skip_reason, created_at)
            VALUES (#{messageSeq}, #{roomId}, #{mode}, #{replyText}, #{skipReason}, now())
            ON CONFLICT (message_seq) DO NOTHING
            """)
    int insertIgnore(MentionReply reply);

    /** 해당 방의 마지막 처리 시각 (쿨다운 판단용). 없으면 null. */
    @Select("SELECT max(created_at) FROM mention_reply WHERE room_id = #{roomId} AND mode IN ('DRAFT','AUTO')")
    OffsetDateTime selectLastCreatedAt(@Param("roomId") String roomId);
}
