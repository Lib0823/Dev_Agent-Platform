package io.github.lib0823.devagent.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 첫 화면은 홈(전체 현황). 예전 관리 화면 주소(/index.html, /admin.html)는 메신저 화면으로 보낸다 — 플랫폼 설정은 /platform.html.
 * <p>
 * 그리고 이 서버를 브라우저의 다른 사이트로부터 지키는 공통 관문 둘:
 * <ul>
 *   <li><b>Host 확인(모든 요청)</b>: 주소의 호스트가 이 PC(localhost·127.0.0.1)가 아니면 거부한다. 다른 사이트가 자기 도메인을 127.0.0.1 로 돌려
 *       같은 출처인 척 API 를 읽어 가는 수법(DNS rebinding)을 막는다 — 파일 내용·폴더 구조를 돌려주는 GET 이 있으므로 필요하다.</li>
 *   <li><b>Origin 확인(상태를 바꾸는 /api 요청 전부)</b>: 다른 사이트의 페이지가 브라우저를 통해 보낸 POST 를 거부한다
 *       ({@link SystemController#foreign}). 컨트롤러마다 빠뜨리지 않도록 여기서 한 번에 건다. Origin 이 없는 로컬 도구(curl)는 그대로 통과한다.</li>
 * </ul>
 * 다른 이름으로 접속해야 하면(리버스 프록시·사설망 주소) {@code ADMIN_ALLOWED_HOSTS} 에 호스트 이름을 쉼표로 적는다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final Set<String> allowedHosts = new HashSet<>();

    public WebConfig(@Value("${admin.allowed-hosts:}") String extraHosts) {
        Arrays.stream((extraHosts == null ? "" : extraHosts).split(","))
                .map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).forEach(allowedHosts::add);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/home.html");
        registry.addRedirectViewController("/index.html", "/messenger.html");
        registry.addRedirectViewController("/admin.html", "/messenger.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
                if (!hostAllowed(req.getHeader("Host"), allowedHosts)) {
                    return reject(res, "이 주소로는 접속할 수 없습니다(허용되지 않은 호스트).");
                }
                String m = req.getMethod();
                boolean writes = !("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m));
                if (writes && req.getRequestURI().startsWith("/api/") && SystemController.foreign(req)) {
                    return reject(res, "이 화면에서만 요청할 수 있습니다.");
                }
                return true;
            }
        });
    }

    /** Host 머리글의 호스트 이름(포트 제외)이 이 PC 이거나 허용 목록에 있는가. 머리글이 없으면(HTTP/1.0 도구) 통과. */
    static boolean hostAllowed(String host, Set<String> extra) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end > 0 ? h.substring(0, end + 1) : h;
        } else {
            int colon = h.lastIndexOf(':');
            if (colon > 0) {
                h = h.substring(0, colon);
            }
        }
        return LOCAL_HOSTS.contains(h) || (extra != null && extra.contains(h));
    }

    private static boolean reject(HttpServletResponse res, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        res.getOutputStream().write(("{\"ok\":false,\"result\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
        return false;
    }
}
