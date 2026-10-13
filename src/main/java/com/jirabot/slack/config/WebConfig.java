package com.jirabot.slack.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// STUDY: 정적 리소스 핸들러는 디렉터리 경로(/dashboard/)를 index.html 로 자동 매핑하지 않는다
//        (welcome-page 는 루트 / 전용). /dashboard/ 는 forward 로 index.html 에 연결한다.
// STUDY: forward 와 redirect 의 차이 — forward 는 서버 내부에서만 넘겨 주소창 URL 이 그대로 남고,
//        redirect 는 브라우저에 새 주소로 다시 요청하게 한다. index.html 은 style.css·app.js 를
//        상대 경로로 부르므로 주소가 /dashboard(슬래시 없음)로 남으면 /style.css·/app.js 로 풀려
//        404 → 화면이 통째로 깨진다(v0.0.84 실사고). 그래서 /dashboard 는 /dashboard/ 로 redirect.
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/dashboard", "/dashboard/");
        registry.addViewController("/dashboard/").setViewName("forward:/dashboard/index.html");
    }
}
