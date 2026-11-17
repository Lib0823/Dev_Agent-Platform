package io.github.lib0823.devagent.messenger;

import io.github.lib0823.devagent.platform.PortHealth;

import java.util.List;

/**
 * 메신저 아웃바운드 포트(헥사고날). 코어(수집·멘션·요약·에이전트 알림)는 이 인터페이스만 알고,
 * 실제 메신저(Slack·Discord·사내 메신저 등)는 어댑터가 구현한다. 어댑터는 {@code platform.messenger} 값으로 하나만 활성화된다.
 * <p>
 * 규약
 * <ul>
 *   <li>방·사용자·메시지 id 는 전부 어댑터가 정하는 문자열이다(숫자, Slack 의 {@code C0123ABC}·{@code 1700000000.000100},
 *       Discord snowflake 등 그대로). 코어는 id 를 같은지만 비교하고 크기·순서를 해석하지 않는다. 64자 이하, {@code :} 는 쓰지 않는다.</li>
 *   <li>메시지 순서는 어댑터가 보장한다: {@link #latest}·{@link #after} 는 오래된 것부터(시간순) 돌려준다.
 *       코어는 받은 목록의 마지막 메시지 id 를 다음 {@link #after} 의 기준(커서)으로 쓴다.</li>
 *   <li>{@link #send} 는 정책 없는 원시 전송이다. 화이트리스트·그룹 차단 같은 안전 정책은 코어의 {@link MessengerService} 가 적용한다.</li>
 *   <li>실패는 RuntimeException. 호출측이 {@link #reset()} 뒤 다음 주기에 {@link #connect()} 로 복구한다.</li>
 * </ul>
 */
public interface MessengerPort {

    /** 어댑터 이름(로그·관리 화면 표시). 예: slack, discord */
    String provider();

    /** 연결 상태(즉시 응답 — 네트워크 호출 없이 세션 유무 등 어댑터가 아는 것만). */
    PortHealth health();

    /** 봇 계정 자신의 사용자 id(자기방 식별, "내가 보낸 메시지" 판별). */
    String selfId();

    /** 세션 보장(없으면 로그인). 이미 연결돼 있으면 아무 일도 하지 않는다. */
    void connect();

    /** 세션 무효화 — 다음 {@link #connect()} 에서 다시 인증. */
    void reset();

    /** 봇 계정이 속한 방 목록. */
    List<Room> rooms();

    /** 최신 {@code size} 건(시간순 — 마지막 원소가 가장 최근). 최초 수집용. */
    List<IncomingMessage> latest(Room room, int size);

    /**
     * {@code sinceMessageId} 뒤에 온 메시지를 시간순으로 최대 {@code size} 건.
     * since 자신이 포함돼도 된다(호출측이 제외). 더 없으면 빈 목록 또는 {@code size} 미만.
     */
    List<IncomingMessage> after(Room room, String sinceMessageId, int size);

    /** 방에 텍스트 전송(정책 검사 없음). */
    void send(RoomKind kind, String roomId, String text);
}
