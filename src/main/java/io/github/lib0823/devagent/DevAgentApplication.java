package io.github.lib0823.devagent;

import io.github.lib0823.devagent.boot.Bootstrap;
import io.github.lib0823.devagent.boot.Launcher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DevAgentApplication {

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--launch")) {
            Launcher.main(args);      // 실행 파일 모드: 감시자 + 트레이. 앱은 자식 JVM 으로 뜬다
            return;
        }
        Bootstrap.prepare();          // .env 틀 · 메신저 없음 모드 · 내장 DB · 초기 스키마
        SpringApplication.run(DevAgentApplication.class, args);
    }
}
