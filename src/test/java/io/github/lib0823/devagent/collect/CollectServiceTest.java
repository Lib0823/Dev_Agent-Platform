package io.github.lib0823.devagent.collect;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.BotProperties;
import io.github.lib0823.devagent.messenger.IncomingMessage;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.messenger.Room;
import io.github.lib0823.devagent.messenger.RoomKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 변동 없는 방 건너뛰기(방 목록의 마지막 메시지 id == 커서)와 전체 조회 때의 자가 보정, 문자열 id 커서.
 * id 는 Slack 식(채널 {@code C07}, 메시지 ts {@code 1700000100.000100})으로 둔다 — 코어가 id 를 숫자로 다루지 않는지 함께 확인한다.
 */
class CollectServiceTest {

    private static final String ROOM = "C07";

    private final MessengerService messenger = mock(MessengerService.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final RoomCursorMapper cursors = mock(RoomCursorMapper.class);
    private final BotProperties bot = mock(BotProperties.class, RETURNS_DEEP_STUBS);
    private final CollectService svc = new CollectService(messenger, messages, cursors, bot, mock(SettingService.class));

    private static String ts(int n) {
        return "17000001" + String.format("%02d", n) + ".000100";
    }

    private static RoomCursor cursor(String last) {
        RoomCursor c = new RoomCursor();
        c.setLastMessageId(last);
        return c;
    }

    private static IncomingMessage msg(String id) {
        return new IncomingMessage(id, "U01", "u", "hello", OffsetDateTime.now(), null);
    }

    private static Room room(String lastMessageId) {
        return new Room(RoomKind.GROUP, ROOM, "g", 3, lastMessageId);
    }

    @Test
    void skipsFetchWhenRoomListShowsNoNewMessage() {
        when(cursors.find(ROOM, "group")).thenReturn(cursor(ts(0)));
        assertEquals(0, svc.collectRoom(room(ts(0)), true));
        verify(messenger, never()).after(any(), anyString(), anyInt());
    }

    @Test
    void fetchesWhenRoomListIsAheadOrUnknown() {
        when(bot.collect().pageSize()).thenReturn(50);
        when(bot.collect().maxPagesPerPoll()).thenReturn(3);
        when(cursors.find(ROOM, "group")).thenReturn(cursor(ts(0)));
        when(messenger.after(any(), anyString(), anyInt())).thenReturn(List.of(msg(ts(0)), msg(ts(1))));   // 기준 메시지가 섞여 와도 제외
        when(messages.insertIgnore(any())).thenReturn(1);
        assertEquals(1, svc.collectRoom(room(ts(1)), true));
        assertEquals(1, svc.collectRoom(room(null), true));   // 마지막 id 를 모르면 항상 조회
    }

    @Test
    void fullSweepDisablesSkippingOnceItProvesWrong() {
        when(bot.collect().pageSize()).thenReturn(50);
        when(bot.collect().maxPagesPerPoll()).thenReturn(3);
        when(cursors.find(ROOM, "group")).thenReturn(cursor(ts(0)));
        when(messenger.after(any(), anyString(), anyInt())).thenReturn(List.of(msg(ts(1))));
        when(messages.insertIgnore(any())).thenReturn(1);
        Room stale = room(ts(0));                                    // 목록은 변동 없음이라는데 실제로는 새 메시지가 있다
        assertEquals(1, svc.collectRoom(stale, false));              // 전체 조회에서 어긋남 발견 → 건너뛰기 끔
        assertEquals(1, svc.collectRoom(stale, true));               // 이후로는 allowSkip 이어도 조회
        verify(messenger, times(2)).after(any(), anyString(), anyInt());
    }

    @Test
    void firstCollectStoresLastMessageAsCursor() {
        when(bot.collect().initialWindow()).thenReturn(30);
        when(cursors.find(ROOM, "group")).thenReturn(null);
        when(messenger.latest(any(), anyInt())).thenReturn(List.of(msg(ts(1)), msg(ts(2))));
        when(messages.insertIgnore(any())).thenReturn(1);
        assertEquals(2, svc.collectRoom(room(null), true));
        assertEquals(ts(2), savedCursor().getLastMessageId());
    }

    @Test
    void pagesUntilShortPageAndCursorIsLastReceived() {
        when(bot.collect().pageSize()).thenReturn(2);
        when(bot.collect().maxPagesPerPoll()).thenReturn(5);
        when(cursors.find(ROOM, "group")).thenReturn(cursor(ts(0)));
        when(messenger.after(any(), eq(ts(0)), anyInt())).thenReturn(List.of(msg(ts(1)), msg(ts(2))));
        when(messenger.after(any(), eq(ts(2)), anyInt())).thenReturn(List.of(msg(ts(2)), msg(ts(3))));   // 기준 포함 2건 → 가득 찬 페이지
        when(messenger.after(any(), eq(ts(3)), anyInt())).thenReturn(List.of());
        when(messages.insertIgnore(any())).thenReturn(1);
        assertEquals(3, svc.collectRoom(room(null), true));
        RoomCursor saved = savedCursor();
        assertEquals(ts(3), saved.getLastMessageId());
        assertEquals(ROOM, saved.getRoomId());
    }

    @Test
    void noNewMessageLeavesCursorAlone() {
        when(bot.collect().pageSize()).thenReturn(50);
        when(bot.collect().maxPagesPerPoll()).thenReturn(3);
        when(cursors.find(ROOM, "group")).thenReturn(cursor(ts(0)));
        when(messenger.after(any(), anyString(), anyInt())).thenReturn(List.of(msg(ts(0))));
        assertEquals(0, svc.collectRoom(room(null), true));
        verify(cursors, never()).upsert(any());
    }

    private RoomCursor savedCursor() {
        ArgumentCaptor<RoomCursor> cap = ArgumentCaptor.forClass(RoomCursor.class);
        verify(cursors).upsert(cap.capture());
        return cap.getValue();
    }
}
