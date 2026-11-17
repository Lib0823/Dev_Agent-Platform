package io.github.lib0823.devagent.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AdminMapper {

    @Select("SELECT count(*) FROM chat_message")
    long countMessages();

    @Select("SELECT count(*) FROM chat_summary")
    long countSummaries();

    @Select("SELECT count(*) FROM mention_reply")
    long countMentionReplies();

    @Select("""
            SELECT room_id, room_type, message_count, summary, created_at
            FROM chat_summary ORDER BY created_at DESC LIMIT #{n}
            """)
    List<Map<String, Object>> recentSummaries(@Param("n") int n);

    @Select("""
            SELECT message_seq, room_id, mode, reply_text, skip_reason, created_at
            FROM mention_reply ORDER BY created_at DESC LIMIT #{n}
            """)
    List<Map<String, Object>> recentMentions(@Param("n") int n);
}
