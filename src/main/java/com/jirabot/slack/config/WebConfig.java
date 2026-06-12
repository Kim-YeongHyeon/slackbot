package com.jirabot.slack.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// STUDY: 정적 리소스 핸들러는 디렉터리 경로(/dashboard/)를 index.html 로 자동 매핑하지 않는다
//        (welcome-page 는 루트 / 전용). /dashboard 진입 경로를 forward 로 연결한다.
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/dashboard").setViewName("forward:/dashboard/index.html");
        registry.addViewController("/dashboard/").setViewName("forward:/dashboard/index.html");
    }
}
