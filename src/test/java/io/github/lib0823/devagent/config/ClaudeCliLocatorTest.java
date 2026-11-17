package io.github.lib0823.devagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClaudeCliLocatorTest {

    @Test
    void findsOnPathFirstThenNpmThenLocalBin(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path pathDir = Files.createDirectories(dir.resolve("bin"));
        Path appData = Files.createDirectories(dir.resolve("appdata"));
        String path = dir.resolve("nothing") + File.pathSeparator + pathDir;

        assertNull(ClaudeCliLocator.locate("Windows 11", home.toString(), path, appData.toString()));

        Path local = Files.createDirectories(home.resolve(".local").resolve("bin")).resolve("claude.exe");
        Files.createFile(local);
        assertEquals(local.toAbsolutePath().toString(), ClaudeCliLocator.locate("Windows 11", home.toString(), path, appData.toString()));

        Path npm = Files.createDirectories(appData.resolve("npm").resolve("node_modules").resolve("@anthropic-ai").resolve("claude-code").resolve("bin"))
                .resolve("claude.exe");
        Files.createFile(npm);
        assertEquals(npm.toAbsolutePath().toString(), ClaudeCliLocator.locate("Windows 11", home.toString(), path, appData.toString()));

        Path onPath = Files.createFile(pathDir.resolve("claude.exe"));
        assertEquals(onPath.toAbsolutePath().toString(), ClaudeCliLocator.locate("Windows 11", home.toString(), path, appData.toString()));
    }

    @Test
    void unixLooksForPlainName(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.createFile(Files.createDirectories(home.resolve(".local").resolve("bin")).resolve("claude.exe"));
        assertNull(ClaudeCliLocator.locate("Linux", home.toString(), "", null), "유닉스에서는 .exe 를 찾지 않는다");
        Path bin = Files.createFile(home.resolve(".local").resolve("bin").resolve("claude"));
        assertEquals(bin.toAbsolutePath().toString(), ClaudeCliLocator.locate("Linux", home.toString(), "", null));
    }
}
