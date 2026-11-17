package io.github.lib0823.devagent.preview;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lib0823.devagent.config.PreviewProperties;
import io.github.lib0823.devagent.mcp.McpConfigService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewConfigTest {

    private static PreviewProperties props(String loginId, String loginPw, boolean headless) {
        return new PreviewProperties(true, "C:/t/jdk-21", "C:/t/maven", "C:/t/tomcat", "C:/t/tomcat-preview",
                8790, "/app", "app.war", "", "db,opensearch", 1000, 1000, loginId, loginPw, "0.0.85", "msedge", headless);
    }

    @Test
    void urlAndLoginFlags() {
        assertEquals("http://localhost:8790/app/", props("", "", true).url());
        assertFalse(props("", "", true).hasLogin());
        assertTrue(props("tester", "pw", true).hasLogin());
        PreviewProperties noCtx = new PreviewProperties(true, "", "", "", "", 8790, "", "", "", "", 0, 0, null, null, null, null, true);
        assertEquals("http://localhost:8790/", noCtx.url());
    }

    @Test
    void playwrightNodeWrapsNpxForWindowsAndPinsVersion() {
        ObjectNode n = McpConfigService.playwrightNode(props("", "", true), Path.of("C:/t/work/preview/shots/7"));
        assertEquals("cmd", n.get("command").asText());
        List<String> args = new ArrayList<>();
        n.get("args").forEach(a -> args.add(a.asText()));
        assertEquals("/c", args.get(0));
        assertEquals("npx", args.get(1));
        assertTrue(args.contains("@playwright/mcp@0.0.85"), args.toString());
        assertTrue(args.contains("--headless"));
        assertTrue(args.contains("--isolated"));
        int i = args.indexOf("--output-dir");
        assertTrue(i > 0 && args.get(i + 1).endsWith("7"), args.toString());
        // headless=false 면 --headless 가 빠진다
        ObjectNode headed = McpConfigService.playwrightNode(props("", "", false), Path.of("C:/t/s"));
        List<String> a2 = new ArrayList<>();
        headed.get("args").forEach(a -> a2.add(a.asText()));
        assertFalse(a2.contains("--headless"));
    }
}
