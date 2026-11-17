package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM(요약/응답) 호출 설정 (prefix: claude).
 * 현재는 Claude Code CLI(headless)를 통해 호출한다 — Enterprise 구독 사용, 별도 API 키 불필요.
 */
@ConfigurationProperties(prefix = "claude")
public record ClaudeProperties(
        String cliCommand,            // claude 실행 경로 (Windows: claude.cmd 절대경로) — one-shot 폴백용
        long timeoutMs,
        String model,                 // --model (예: sonnet). 비면 기본 모델(opus) 사용
        String effort,                // --effort (low/medium/high). 비면 미지정
        boolean strictMcpConfig,      // --strict-mcp-config : MCP 서버 로딩 차단(콜드스타트 단축)
        boolean disableSlashCommands, // --disable-slash-commands : 스킬 로딩 차단
        String systemPrompt,          // --system-prompt : 거대 기본 시스템프롬프트 대체(비면 미지정)
        boolean warm,                 // true면 상주(warm) stream-json 프로세스 재사용 (2번째 요청부터 ~1s)
        int warmResetTurns,           // warm 프로세스를 N턴마다 재시작 (맥락 누적 리셋 + 인증 갱신)
        String exeCommand,            // warm용 claude.exe 절대경로 (cmd.exe 코드페이지 우회, 한글 인자 안전). 비면 cliCommand 사용
        boolean warmPrewarm,          // 기동/리셋 시 백그라운드로 선(先)데움 → 다음 요청의 콜드스타트 체감 제거
        int warmFailureThreshold,     // warm 연속 실패 N회면 서킷 오픈(일시적으로 one-shot 전용)
        long warmBreakerCooldownMs    // 서킷 오픈 후 이 시간이 지나면 warm 반(半)개방으로 재시도
) {

    /** one-shot/warm 양쪽이 공유하는 공통 플래그(모델/추론강도/MCP·스킬 차단/시스템프롬프트). */
    public List<String> commonFlags() {
        List<String> flags = new ArrayList<>();
        if (strictMcpConfig) {
            flags.add("--strict-mcp-config");
        }
        if (disableSlashCommands) {
            flags.add("--disable-slash-commands");
        }
        if (model != null && !model.isBlank()) {
            flags.add("--model");
            flags.add(model.strip());
        }
        if (effort != null && !effort.isBlank()) {
            flags.add("--effort");
            flags.add(effort.strip());
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            flags.add("--system-prompt");
            flags.add(systemPrompt.strip());
        }
        return flags;
    }

    /** warm 세션이 실행할 바이너리 (exeCommand 우선, 없으면 cliCommand). */
    public String warmCommand() {
        return (exeCommand != null && !exeCommand.isBlank()) ? exeCommand.strip() : ClaudeCliLocator.find(cliCommand);
    }

    /** one-shot 호출용 명령. 기본값("claude")이면 자동 탐색한 실행 파일을 쓴다(Windows 는 확장자 없는 이름으로 .cmd 를 못 띄운다). */
    public String oneShotCommand() {
        return cliCommand == null || cliCommand.isBlank() || "claude".equals(cliCommand.strip()) ? warmCommand() : cliCommand.strip();
    }
}
