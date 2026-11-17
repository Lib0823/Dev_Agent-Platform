package io.github.lib0823.devagent.platform;

import java.util.ArrayList;
import java.util.List;

/** OS 차이를 한곳에 모은다(Windows 가 주 대상, macOS/Linux 는 같은 jar 로 동작하도록). */
public final class Os {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private Os() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    /** 표시용 이름: Windows / macOS / Linux. */
    public static String name() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return WINDOWS ? "Windows" : os.contains("mac") ? "macOS" : "Linux";
    }

    /** 셸을 거쳐 실행할 명령(배치 파일·PATH 탐색용). Windows: {@code cmd /c 인자…}, 그 외: 인자 그대로. */
    public static List<String> viaShell(String... args) {
        List<String> cmd = new ArrayList<>();
        if (WINDOWS) {
            cmd.add("cmd");
            cmd.add("/c");
        }
        cmd.addAll(List.of(args));
        return cmd;
    }

    /** 한 줄 명령 문자열 실행. Windows: {@code cmd /c 줄}, 그 외: {@code sh -c 줄}. */
    public static List<String> shellLine(String line) {
        return WINDOWS ? List.of("cmd", "/c", line) : List.of("sh", "-c", line);
    }
}
