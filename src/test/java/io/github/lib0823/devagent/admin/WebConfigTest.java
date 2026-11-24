package io.github.lib0823.devagent.admin;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigTest {

    @Test
    void localHostsAreAllowedWithAnyPort() {
        assertTrue(WebConfig.hostAllowed("localhost:8777", Set.of()));
        assertTrue(WebConfig.hostAllowed("127.0.0.1:8777", Set.of()));
        assertTrue(WebConfig.hostAllowed("LOCALHOST", Set.of()));
        assertTrue(WebConfig.hostAllowed("[::1]:8777", Set.of()));
        assertTrue(WebConfig.hostAllowed(null, Set.of()));               // Host 없는 로컬 도구
    }

    @Test
    void otherHostsAreRejectedUnlessListed() {
        // 다른 사이트가 자기 도메인을 127.0.0.1 로 돌려 접속하는 경우(DNS rebinding): Host 는 그 도메인이다
        assertFalse(WebConfig.hostAllowed("evil.example:8777", Set.of()));
        assertFalse(WebConfig.hostAllowed("127.0.0.1.evil.example", Set.of()));
        assertFalse(WebConfig.hostAllowed("localhost.evil.example:8777", Set.of()));
        assertTrue(WebConfig.hostAllowed("dev-agent.internal:443", Set.of("dev-agent.internal")));
        assertFalse(WebConfig.hostAllowed("other.internal", Set.of("dev-agent.internal")));
    }
}
