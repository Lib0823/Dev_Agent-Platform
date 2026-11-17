package io.github.lib0823.devagent.messenger;

/**
 * 채팅방 종류. 전송 안전정책의 기준이 된다.
 * <ul>
 *   <li>SELF   — "나에게 보내기"(봇 계정 자신과의 방. Slack 이면 자기 DM)</li>
 *   <li>SINGLE — 1:1 방</li>
 *   <li>GROUP  — 다자 방(채널)</li>
 * </ul>
 */
public enum RoomKind {
    SELF,
    SINGLE,
    GROUP
}
