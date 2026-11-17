package io.github.lib0823.devagent.boot;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 로그인 시 자동 실행(Windows): 현재 사용자의 Run 레지스트리 키에 실행 파일 경로를 넣고 뺀다.
 * 관리자 권한이 필요 없고(HKCU) 설치 프로그램 없이도 동작한다. 실행 파일 모드의 트레이 메뉴가 쓴다.
 * macOS/Linux 는 지원하지 않는다({@link #supported()} 가 false — 메뉴에 나타나지 않음).
 */
public final class Autostart {

    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private Autostart() {
    }

    public static boolean supported() {
        return Launcher.isWindows();
    }

    public static boolean isEnabled(String name) {
        return supported() && reg("query", RUN_KEY, "/v", name) == 0;
    }

    /** 등록. 경로에 공백이 있을 수 있어 따옴표로 감싼 값을 넣는다. */
    public static boolean enable(String name, String exePath) {
        return supported() && reg("add", RUN_KEY, "/v", name, "/t", "REG_SZ", "/d", "\"" + exePath + "\"", "/f") == 0;
    }

    public static boolean disable(String name) {
        return supported() && reg("delete", RUN_KEY, "/v", name, "/f") == 0;
    }

    private static int reg(String... args) {
        try {
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add("reg");
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }
}
