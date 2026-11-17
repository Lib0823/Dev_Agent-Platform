package io.github.lib0823.devagent.admin;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.IOException;

/**
 * 브라우저가 SSE·응답 도중 연결을 끊었을 때(탭 닫기·새로고침) 나는 IOException 을 조용히 넘긴다.
 * 처리하지 않으면 Tomcat 이 끊길 때마다 스택 트레이스를 ERROR 로 남긴다(11-16 실측: 하루 수십 건, 실제 오류를 가림).
 * 응답이 이미 나가기 시작한 뒤의 IOException 만 끊김으로 본다 — 그 전의 것은 진짜 오류이므로 다시 던진다.
 */
@ControllerAdvice
public class ClientAbortAdvice {

    private static final Logger log = LoggerFactory.getLogger(ClientAbortAdvice.class);

    @ExceptionHandler(IOException.class)
    public void clientGone(IOException e, HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) {
            throw e;
        }
        log.debug("클라이언트 연결 종료: {}", e.getMessage());
    }
}
