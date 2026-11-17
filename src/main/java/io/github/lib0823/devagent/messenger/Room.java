package io.github.lib0823.devagent.messenger;

import java.util.Locale;

/**
 * 메신저 중립 방 모델.
 *
 * @param kind          SELF(나에게 보내기) / SINGLE(1:1) / GROUP
 * @param id            전송·조회에 쓰는 방 id (어댑터가 정하는 문자열. 예: Slack 채널 id)
 * @param lastMessageId 방 목록이 알려 주는 마지막 메시지 id(없으면 null) — 변동 없는 방의 수집 호출을 건너뛰는 데 쓴다
 */
public record Room(RoomKind kind, String id, String name, Integer memberCount, String lastMessageId) {

    /** 저장·설정용 종류 라벨: self | single | group */
    public String type() {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** 방 식별 키 "{type}:{id}". 예) self:U01AB, single:D02CD, group:C03EF */
    public String key() {
        return type() + ":" + id;
    }

    public static RoomKind kindOf(String type) {
        return RoomKind.valueOf(type.strip().toUpperCase(Locale.ROOT));
    }
}
