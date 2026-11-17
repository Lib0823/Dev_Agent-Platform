package io.github.lib0823.devagent.collect;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoomCursorMapper {

    @Select("""
            SELECT room_id, room_type, room_name, last_message_id, updated_at
            FROM chat_room_cursor
            WHERE room_id = #{roomId} AND room_type = #{roomType}
            """)
    RoomCursor find(@Param("roomId") String roomId, @Param("roomType") String roomType);

    @Select("SELECT room_id, room_type, room_name, last_message_id, updated_at FROM chat_room_cursor")
    List<RoomCursor> listAll();

    @Insert("""
            INSERT INTO chat_room_cursor (room_id, room_type, room_name, last_message_id, updated_at)
            VALUES (#{roomId}, #{roomType}, #{roomName}, #{lastMessageId}, now())
            ON CONFLICT (room_id, room_type) DO UPDATE SET
                room_name = EXCLUDED.room_name,
                last_message_id = EXCLUDED.last_message_id,
                updated_at = now()
            """)
    void upsert(RoomCursor cursor);
}
