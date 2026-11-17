package io.github.lib0823.devagent.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 첫 화면은 홈(전체 현황). 예전 관리 화면 주소(/index.html, /admin.html)는 메신저 화면으로 보낸다 — 플랫폼 설정은 /platform.html. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/home.html");
        registry.addRedirectViewController("/index.html", "/messenger.html");
        registry.addRedirectViewController("/admin.html", "/messenger.html");
    }
}
