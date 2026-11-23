package io.github.lib0823.devagent.summarize;

import io.github.lib0823.devagent.collect.ChatMessageMapper;
import io.github.lib0823.devagent.collect.ChatMessageRow;
import io.github.lib0823.devagent.collect.RoomCursor;
import io.github.lib0823.devagent.collect.RoomCursorMapper;
import io.github.lib0823.devagent.config.BotProperties;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.llm.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 방별 요약 배치. 마지막 요약 이후 메시지를 모아 Claude 로 요약하고, 결과를 저장한 뒤 "나에게 보내기" 로 전달한다.
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final OffsetDateTime EPOCH = OffsetDateTime.parse("1970-01-01T00:00:00Z");

    private static final String SYSTEM = """
            당신은 사내 메신저 대화를 요약하는 비서입니다. 주어진 대화를 아래 형식으로 한국어로 간결히 정리하세요.

            ## 결정사항
            ## 나에게 요청된 일
            ## 미해결 이슈

            규칙:
            - 각 항목에 해당 내용이 없으면 '없음' 이라고만 쓰세요.
            - 대화에 실제로 근거한 내용만 쓰고 추측하지 마세요.
            - 각 항목은 핵심만 불릿으로 짧게.
            """;

    private final MessengerService client;
    private final ChatModel claude;
    private final ChatMessageMapper messageMapper;
    private final ChatSummaryMapper summaryMapper;
    private final RoomCursorMapper cursorMapper;
    private final BotProperties bot;

    public SummaryService(MessengerService client, ChatModel claude, ChatMessageMapper messageMapper,
                          ChatSummaryMapper summaryMapper, RoomCursorMapper cursorMapper, BotProperties bot) {
        this.client = client;
        this.claude = claude;
        this.messageMapper = messageMapper;
        this.summaryMapper = summaryMapper;
        this.cursorMapper = cursorMapper;
        this.bot = bot;
    }

    /** 수집 중인 모든 방을 요약한다. */
    public void summarizeAll() {
        List<RoomCursor> rooms = cursorMapper.listAll();
        log.info("요약 배치 시작 — 방 {}개", rooms.size());
        int done = 0;
        for (RoomCursor room : rooms) {
            try {
                if (summarizeRoom(room)) {
                    done++;
                }
            } catch (Exception e) {
                log.warn("방 요약 실패 '{}': {}", room.getRoomName(), e.toString());
            }
        }
        log.info("요약 배치 완료 — {}개 방 요약", done);
    }

    /** 반환: 실제로 요약했으면 true (건수 미달이면 false). */
    public boolean summarizeRoom(RoomCursor room) {
        OffsetDateTime since = summaryMapper.selectLastTo(room.getRoomId(), room.getRoomType());
        if (since == null) {
            since = EPOCH;
        }
        List<ChatMessageRow> msgs = messageMapper.selectSince(
                room.getRoomId(), room.getRoomType(), since, bot.summary().maxMessages());

        if (msgs.size() < bot.summary().minMessages()) {
            log.debug("요약 skip '{}' — 신규 {}건 (최소 {}건)", room.getRoomName(), msgs.size(), bot.summary().minMessages());
            return false;
        }

        String prompt = buildPrompt(room.getRoomName(), msgs);
        String summaryText = claude.complete(prompt);

        ChatSummary s = new ChatSummary();
        s.setRoomId(room.getRoomId());
        s.setRoomType(room.getRoomType());
        s.setFromAt(msgs.get(0).getSentAt());
        s.setToAt(msgs.get(msgs.size() - 1).getSentAt());
        s.setMessageCount(msgs.size());
        s.setSummary(summaryText);
        summaryMapper.insert(s);

        String header = "[방 요약] " + room.getRoomName() + " (" + msgs.size() + "건)";
        boolean sent = client.notifySelf(MessengerService.NOTICE_SUMMARY, header + "\n" + summaryText);

        log.info("요약 완료 '{}' — {}건, {}", room.getRoomName(), msgs.size(), sent ? "전달됨" : "메신저 알림 꺼짐(화면에만)");
        return true;
    }

    private String buildPrompt(String roomName, List<ChatMessageRow> msgs) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM).append("\n\n--- '").append(roomName).append("' 대화 ---\n");
        for (ChatMessageRow m : msgs) {
            String time = m.getSentAt() == null ? "--" : m.getSentAt().atZoneSameInstant(KST).format(TS);
            String sender = m.getSenderName() == null ? "?" : m.getSenderName();
            String body = m.getBody() == null ? "" : m.getBody();
            sb.append('[').append(time).append("] ").append(sender).append(": ").append(body).append('\n');
        }
        return sb.toString();
    }
}
