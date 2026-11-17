package io.github.lib0823.devagent.collect;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ChatMessageMapper {

    /** 같은 방의 같은 message_id 는 무시. 반환 1=삽입, 0=중복. */
    @Insert("""
            INSERT INTO chat_message
                (message_id, room_id, room_type, sender_id, sender_name, body, sent_at, mentioned_me, raw_json, collected_at)
            VALUES
                (#{messageId}, #{roomId}, #{roomType}, #{senderId}, #{senderName}, #{body}, #{sentAt}, #{mentionedMe}, #{rawJson}::jsonb, now())
            ON CONFLICT (room_type, room_id, message_id) DO NOTHING
            """)
    int insertIgnore(ChatMessageRow row);

    @Select("SELECT count(*) FROM chat_message")
    long countAll();

    @Select("SELECT count(*) FROM chat_message WHERE mentioned_me = TRUE")
    long countMentioned();

    /** 요약 대상 조회: 특정 방에서 sinceAt 이후 메시지(오름차순, 최대 limit건). */
    @Select("""
            SELECT seq, message_id, room_id, room_type, sender_id, sender_name, body, sent_at, mentioned_me
            FROM chat_message
            WHERE room_id = #{roomId} AND room_type = #{roomType} AND sent_at > #{sinceAt}
              AND body NOT LIKE '[방 요약]%'      -- 봇이 보낸 요약 재요약 방지(루프)
              AND body NOT LIKE '[자동응답]%'     -- 봇이 보낸 자동응답 제외
              AND body NOT LIKE '[멘션 초안]%'    -- 봇이 보낸 멘션 초안 제외
              AND body NOT LIKE '[AI]%'           -- 봇이 보낸 AI 답변 제외
              AND body NOT LIKE '[이슈]%'
              AND body NOT LIKE '[질의]%'         -- 봇이 보낸 이슈 에이전트 메시지 제외
            ORDER BY sent_at ASC
            LIMIT #{limit}
            """)
    List<ChatMessageRow> selectSince(@Param("roomId") String roomId,
                                     @Param("roomType") String roomType,
                                     @Param("sinceAt") OffsetDateTime sinceAt,
                                     @Param("limit") int limit);

    /** 아직 처리되지 않은 멘션 메시지(멘션 플래그 + mention_reply 없음 + 봇 메시지 제외), 오래된 순. */
    @Select("""
            SELECT m.seq, m.message_id, m.room_id, m.room_type, m.sender_id, m.sender_name, m.body, m.sent_at, m.mentioned_me
            FROM chat_message m
            LEFT JOIN mention_reply r ON r.message_seq = m.seq
            WHERE m.mentioned_me = TRUE
              AND r.message_seq IS NULL
              AND m.body NOT LIKE '[자동응답]%'
              AND m.body NOT LIKE '[방 요약]%'
              AND m.body NOT LIKE '[멘션 초안]%'
              AND m.body NOT LIKE '[AI]%'
              AND m.body NOT LIKE '[이슈]%'
            ORDER BY m.sent_at ASC
            LIMIT #{limit}
            """)
    List<ChatMessageRow> selectUnrepliedMentions(@Param("limit") int limit);

    /** 멘션 응답 맥락: 해당 방에서 기준 시각까지의 최근 메시지(최신→과거, limit건). 봇 메시지 제외. */
    @Select("""
            SELECT seq, message_id, room_id, room_type, sender_id, sender_name, body, sent_at, mentioned_me
            FROM chat_message
            WHERE room_id = #{roomId} AND room_type = #{roomType} AND sent_at <= #{uptoAt}
              AND body NOT LIKE '[자동응답]%'
              AND body NOT LIKE '[방 요약]%'
              AND body NOT LIKE '[멘션 초안]%'
              AND body NOT LIKE '[AI]%'
              AND body NOT LIKE '[이슈]%'
              AND body NOT LIKE '[질의]%'
            ORDER BY sent_at DESC
            LIMIT #{limit}
            """)
    List<ChatMessageRow> selectContext(@Param("roomId") String roomId,
                                       @Param("roomType") String roomType,
                                       @Param("uptoAt") OffsetDateTime uptoAt,
                                       @Param("limit") int limit);

    /** 자기방의 최신 적재 순번(seq, 없으면 0). 이슈 에이전트가 "이 이후의 메시지를 답변으로" 기준점으로 쓴다. */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM chat_message WHERE room_type = 'self'")
    long maxSelfSeq();

    /** 자기방에서 afterSeq(적재 순번) 이후에 내가 쓴 메시지(봇 생성물 제외), 오래된 순. 이슈 에이전트 답변 수신용. */
    @Select("""
            SELECT seq, message_id, room_id, room_type, sender_id, sender_name, body, sent_at, mentioned_me
            FROM chat_message
            WHERE room_type = 'self' AND seq > #{afterSeq}
              AND body NOT LIKE '[자동응답]%'
              AND body NOT LIKE '[방 요약]%'
              AND body NOT LIKE '[멘션 초안]%'
              AND body NOT LIKE '[AI]%'
              AND body NOT LIKE '[이슈]%'
              AND body NOT LIKE '[질의]%'
              AND body NOT LIKE '[봇%'          -- [봇] 시작됨 / [봇 오류] (재시작·알림이 답변으로 오인되던 버그)
            ORDER BY seq ASC
            LIMIT 20
            """)
    List<ChatMessageRow> selectSelfAfter(@Param("afterSeq") long afterSeq);
}
