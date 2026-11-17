package io.github.lib0823.devagent.platform;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 어댑터 호출의 최근 성공/실패 기록(메모리, 잠금 없음). {@link PortHealth} 를 만들 때 "마지막으로 언제 됐고, 마지막 오류가 무엇인지" 를 채운다.
 */
public final class CallStats {

    private volatile long lastOkAt;
    private volatile long lastErrorAt;
    private volatile long lastElapsedMs = -1;
    private volatile String lastError;

    public <T> T track(Supplier<T> call) {
        long t0 = System.currentTimeMillis();
        try {
            T r = call.get();
            ok(System.currentTimeMillis() - t0);
            return r;
        } catch (RuntimeException e) {
            error(e);
            throw e;
        }
    }

    public void track(Runnable call) {
        track(() -> {
            call.run();
            return null;
        });
    }

    public void ok(long elapsedMs) {
        lastOkAt = System.currentTimeMillis();
        lastElapsedMs = elapsedMs;
    }

    public void error(Throwable e) {
        lastErrorAt = System.currentTimeMillis();
        String m = e.getClass().getSimpleName() + ": " + e.getMessage();
        lastError = m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }

    /** 마지막 호출이 실패였는지(성공 이력이 없고 실패만 있어도 true). */
    public boolean failing() {
        return lastErrorAt > 0 && lastErrorAt >= lastOkAt;
    }

    public boolean everCalled() {
        return lastOkAt > 0 || lastErrorAt > 0;
    }

    /** 상태 요약을 채운다: 실패 중이면 down, 아니면 주어진 기본 상태. */
    public PortHealth health(String provider, String okStatus, String okSummary) {
        PortHealth h = failing()
                ? PortHealth.of(provider, PortHealth.DOWN, "최근 호출 실패: " + lastError)
                : PortHealth.of(provider, okStatus, okSummary);
        if (lastOkAt > 0) {
            h.with("lastOkAt", Instant.ofEpochMilli(lastOkAt).toString());
            h.with("lastElapsedMs", lastElapsedMs);
        }
        if (lastErrorAt > 0) {
            h.with("lastErrorAt", Instant.ofEpochMilli(lastErrorAt).toString());
            h.with("lastError", lastError);
        }
        return h;
    }
}
