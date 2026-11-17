package io.github.lib0823.devagent.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Claude Code CLI 실행 파일 찾기(설정 {@code CLAUDE_EXE_COMMAND} 를 비웠을 때). 호스트마다 설치 위치가 달라 경로를 설정에 박아 두지 않는다.
 * 순서: PATH 의 실행 파일 → npm 전역 설치 위치 → 네이티브 설치 위치({@code ~/.local/bin}) → Homebrew.
 * Windows 에서는 {@code .cmd} 래퍼가 아니라 {@code claude.exe} 를 찾는다(cmd.exe 를 거치면 한글 인자가 코드페이지로 깨진다).
 */
public final class ClaudeCliLocator {

    private static volatile String cached;

    private ClaudeCliLocator() {
    }

    /** 찾은 절대 경로. 못 찾으면 {@code fallback}(보통 "claude" — PATH 에 맡긴다). */
    public static String find(String fallback) {
        String c = cached;
        if (c == null) {
            c = locate(System.getProperty("os.name", ""), System.getProperty("user.home", ""), System.getenv("PATH"), System.getenv("APPDATA"));
            cached = c == null ? "" : c;
        }
        return c == null || c.isEmpty() ? fallback : c;
    }

    static String locate(String osName, String home, String path, String appData) {
        boolean win = osName.toLowerCase().contains("win");
        String exe = win ? "claude.exe" : "claude";
        List<Path> candidates = new ArrayList<>();
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    candidates.add(Path.of(dir.strip(), exe));
                }
            }
        }
        if (win) {
            if (appData != null && !appData.isBlank()) {
                candidates.add(Path.of(appData, "npm", "node_modules", "@anthropic-ai", "claude-code", "bin", exe));
            }
            candidates.add(Path.of(home, "AppData", "Roaming", "npm", "node_modules", "@anthropic-ai", "claude-code", "bin", exe));
        } else {
            candidates.add(Path.of("/opt/homebrew/bin", exe));
            candidates.add(Path.of("/usr/local/bin", exe));
            candidates.add(Path.of(home, ".claude", "local", exe));
        }
        candidates.add(Path.of(home, ".local", "bin", exe));
        for (Path p : candidates) {
            try {
                if (Files.isRegularFile(p)) {
                    return p.toAbsolutePath().toString();
                }
            } catch (RuntimeException ignored) {
                // PATH 에 잘못된 항목이 있어도 계속
            }
        }
        return null;
    }
}
