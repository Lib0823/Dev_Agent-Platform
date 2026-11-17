package io.github.lib0823.devagent.boot;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 내장 PostgreSQL(실제 서버 바이너리를 자식 프로세스로 실행). Docker 없이 실행 파일만으로 돌리기 위한 것으로,
 * {@code DB_URL} 을 비워 두면 쓰인다. 데이터는 홈의 {@code pgdata-embedded/}, 바이너리는 {@code pg-bin/} 에 풀린다(최초 1회).
 * 127.0.0.1 에서만 듣고 로컬 trust 인증이다(로컬 1인 사용 전제).
 */
final class EmbeddedDb {

    private static EmbeddedPostgres server;   // 종료 훅이 닫을 수 있게 참조 유지

    private EmbeddedDb() {
    }

    static synchronized void start(Path dataDir, Path binDir, int port) {
        if (server != null) {
            return;
        }
        if (portOpen(port)) {
            // 앱이 강제 종료돼 서버만 남은 경우 — 같은 데이터 디렉터리의 서버이므로 그대로 붙는다
            Bootstrap.say("내장 DB 가 이미 떠 있어 그대로 사용합니다(포트 " + port + ").");
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(binDir);
            server = EmbeddedPostgres.builder()
                    .setDataDirectory(dataDir)
                    .setCleanDataDirectory(false)              // 재시작해도 데이터 유지
                    .setOverrideWorkingDirectory(binDir.toFile())
                    .setPort(port)
                    .setLocaleConfig("encoding", "UTF8")
                    .setLocaleConfig("locale", "C")
                    .setServerConfig("max_connections", "20")  // 앱 풀 5 + 여유. 프로세스·메모리를 작게
                    .setServerConfig("shared_buffers", "32MB")
                    .setPGStartupWait(Duration.ofSeconds(90))
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("내장 DB 를 시작하지 못했습니다(" + dataDir + "): " + e.getMessage(), e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedDb::stop, "embedded-db-stop"));
        Bootstrap.say("내장 DB 시작(포트 " + port + ", " + (System.currentTimeMillis() - t0) + "ms): " + dataDir);
    }

    private static synchronized void stop() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 종료 중 — 무시
            }
            server = null;
        }
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
