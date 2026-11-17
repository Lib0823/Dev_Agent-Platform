package io.github.lib0823.devagent.collect;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.BotProperties;
import io.github.lib0823.devagent.messenger.IncomingMessage;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.messenger.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 방별 증분 수집. 커서(last_message_id) 이후 메시지를 조회해 적재하고 커서를 갱신한다.
 * (방, message_id) 유일 제약 + ON CONFLICT DO NOTHING 으로 재시작/중복 폴링에도 중복 적재가 없다.
 * 메신저는 {@link MessengerService}(포트) 뒤에 있어 어댑터 종류와 무관하다.
 */
@Service
public class CollectService {

    private static final Logger log = LoggerFactory.getLogger(CollectService.class);

    private final MessengerService messenger;
    private final ChatMessageMapper messageMapper;
    private final RoomCursorMapper cursorMapper;
    private final BotProperties bot;
    private final SettingService settings;
    /** 방 목록의 마지막 메시지 id 로 "변동 없음" 을 판단해도 되는지. 전체 조회에서 어긋남이 한 번이라도 보이면 꺼진다(자가 보정). */
    private volatile boolean skipTrusted = true;

    public CollectService(MessengerService messenger, ChatMessageMapper messageMapper, RoomCursorMapper cursorMapper,
                          BotProperties bot, SettingService settings) {
        this.messenger = messenger;
        this.messageMapper = messageMapper;
        this.cursorMapper = cursorMapper;
        this.bot = bot;
        this.settings = settings;
    }

    /**
     * 수집 대상 방 결정. 기본: 자기방 + 관리화면에서 지정한 수집목록 + 화이트리스트(자동응답 방은 수집 필수).
     * mode=ALL 이면 (제외 외) 전부. 모두 DB 값이라 재시작 없이 반영된다.
     */
    public List<Room> resolveTargets() {
        List<Room> all = messenger.rooms();
        if (bot.collect().mode() == BotProperties.Collect.Mode.ALL) {
            Set<String> excl = Set.copyOf(bot.collect().excludedRoomKeys() == null ? List.of() : bot.collect().excludedRoomKeys());
            return all.stream().filter(r -> !excl.contains(r.key())).toList();
        }
        Set<String> wanted = new LinkedHashSet<>(settings.getCollectRoomKeys());
        wanted.addAll(settings.getAutoRoomKeys()); // 자동응답 방은 반드시 수집
        return all.stream().filter(r -> wanted.contains(r.key())).toList();
    }

    public int collectRoom(Room room) {
        return collectRoom(room, false);
    }

    /**
     * 한 방을 수집한다. 반환=신규 적재 건수.
     *
     * @param allowSkip true 면 방 목록의 마지막 메시지 id 가 커서와 같을 때 메시지 조회(HTTP)를 건너뛴다.
     *                  false(주기적 전체 조회)에서 "건너뛰었을 방" 에 새 메시지가 나오면 그 판단을 더는 믿지 않는다.
     */
    public int collectRoom(Room room, boolean allowSkip) {
        return collectRoom(room, allowSkip, cursorMapper.find(room.id(), room.type()));
    }

    /** 폴링 한 번에 쓸 커서 전체(방 키 → 커서). 방마다 조회하지 않고 한 번에 읽는다. */
    public java.util.Map<String, RoomCursor> loadCursors() {
        java.util.Map<String, RoomCursor> m = new java.util.HashMap<>();
        for (RoomCursor c : cursorMapper.listAll()) {
            m.put(c.getRoomType() + ":" + c.getRoomId(), c);
        }
        return m;
    }

    /** @param cursor 이 방의 현재 커서(없으면 null — 최초 수집) */
    public int collectRoom(Room room, boolean allowSkip, RoomCursor cursor) {
        String id = room.id();
        String type = room.type();                    // self | single | group

        // 메시지 id 는 어댑터가 정하는 문자열이라 크기를 비교하지 않는다. 순서는 포트 규약(시간순 반환)을 믿고
        // "받은 목록의 마지막 id" 를 커서로 삼는다.
        String since = cursor != null ? cursor.getLastMessageId() : null;

        boolean unchanged = since != null && since.equals(room.lastMessageId());
        if (unchanged && allowSkip && skipTrusted) {
            return 0;
        }

        List<IncomingMessage> fresh = new ArrayList<>();
        String last = since;

        if (since == null) {
            // 최초 수집: 최신 window 건
            for (IncomingMessage m : messenger.latest(room, bot.collect().initialWindow())) {
                fresh.add(m);
                last = m.id();
            }
        } else {
            // 증분: 커서 뒤를 페이지 반복 조회. 어댑터가 기준 메시지를 포함해 돌려줘도 되므로 그것은 제외한다
            String pivot = since;
            int pageSize = bot.collect().pageSize();
            for (int page = 0; page < bot.collect().maxPagesPerPoll(); page++) {
                List<IncomingMessage> arr = messenger.after(room, pivot, pageSize);
                String pageLast = pivot;
                for (IncomingMessage m : arr) {
                    if (m.id().equals(since) || m.id().equals(pivot)) {
                        continue;
                    }
                    fresh.add(m);
                    pageLast = m.id();
                }
                if (arr.size() < pageSize || pageLast.equals(pivot)) {
                    break; // 마지막 페이지 또는 진전 없음
                }
                pivot = pageLast;
                last = pageLast;
            }
            if (!fresh.isEmpty()) {
                last = fresh.get(fresh.size() - 1).id();
            }
        }

        int inserted = 0;
        for (IncomingMessage m : fresh) {
            inserted += messageMapper.insertIgnore(toRow(m, id, type));
        }

        if (unchanged && inserted > 0 && skipTrusted) {
            skipTrusted = false;
            log.warn("[수집] '{}' 방 목록의 마지막 메시지 id({})가 커서({})와 같은데 새 메시지 {}건 — 변동 없음 건너뛰기를 끕니다",
                    room.name(), room.lastMessageId(), since, inserted);
        }

        // 커서 갱신 (최초이거나 진전된 경우)
        if (last != null && !last.equals(since)) {
            RoomCursor nc = new RoomCursor();
            nc.setRoomId(id);
            nc.setRoomType(type);
            nc.setRoomName(room.name());
            nc.setLastMessageId(last);
            cursorMapper.upsert(nc);
        }

        if (inserted > 0) {
            log.info("[수집] {} '{}' +{}건 (cursor={})", type, room.name(), inserted, last);
        }
        return inserted;
    }

    private ChatMessageRow toRow(IncomingMessage m, String roomId, String type) {
        ChatMessageRow row = new ChatMessageRow();
        row.setMessageId(m.id());
        row.setRoomId(roomId);
        row.setRoomType(type);
        row.setSenderId(m.senderId());
        row.setSenderName(m.senderName());
        String body = m.body() == null ? "" : m.body();
        row.setBody(body);
        row.setSentAt(m.sentAt());
        // 멘션 플래그: 이름호출(@홍길동 … 남이) 또는 명령호출(@AI 내가) 포함 && 봇 생성물 아님(루프 방지)
        row.setMentionedMe(!isBotGenerated(body) && (containsName(body) || containsCommand(body)));
        row.setRawJson(m.rawJson());
        return row;
    }

    private boolean containsName(String body) {
        List<String> kws = bot.mention().keywords();
        return kws != null && kws.stream().anyMatch(k -> k != null && !k.isBlank() && body.contains(k));
    }

    private boolean containsCommand(String body) {
        String cmd = bot.mention().commandKeyword();
        return cmd != null && !cmd.isBlank() && body.contains(cmd);
    }

    /** 봇이 전달한 메시지(요약/멘션 초안/자동응답/AI 답변)인지 — 수집·멘션·요약에서 제외해 루프를 막는다. */
    public static boolean isBotGenerated(String body) {
        if (body == null) {
            return false;
        }
        return body.startsWith("[멘션 초안]") || body.startsWith("[방 요약]")
                || body.startsWith("[자동응답]") || body.startsWith("[AI]") || body.startsWith("[이슈]") || body.startsWith("[질의]")
                || body.startsWith("[봇");
    }
}
