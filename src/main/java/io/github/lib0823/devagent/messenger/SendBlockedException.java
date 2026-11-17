package io.github.lib0823.devagent.messenger;

/**
 * 안전정책에 의해 전송이 차단될 때 던진다. (예: 그룹방, 미허용 1:1)
 */
public class SendBlockedException extends RuntimeException {
    public SendBlockedException(String message) {
        super(message);
    }
}
