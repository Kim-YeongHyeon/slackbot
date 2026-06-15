package com.jirabot.slack.config;

import com.jirabot.slack.filter.CachedBodyFilter;
import com.jirabot.slack.filter.SlackSignatureFilter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// STUDY: @EnableWebSecurity 는 Spring Security 필터 체인 자동 구성을 활성화. 6.x 부터는 람다 DSL 이 기본.
// SessionCreationPolicy.STATELESS — 서버가 HttpSession 을 생성/사용하지 않음. Slack webhook 처럼
// 매 요청이 독립적인 API 에 적합 (CSRF 토큰/세션 쿠키 불필요).
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CachedBodyFilter cachedBodyFilter,
            SlackSignatureFilter slackSignatureFilter) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // STUDY: ERROR 디스패치 허용 — 미존재 경로가 /error 로 forward 될 때 denyAll 에 걸려
                        // 404 가 403 으로 둔갑하는 것을 막는다 (Spring Security 6 는 에러 디스패치도 인가 대상).
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/health", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/user-mappings/**").permitAll()
                        // 기능요청 게시판 — 대시보드와 동일 신뢰 경계 (사내망 or Go봇 Basic Auth 터널).
                        .requestMatchers("/api/feature-requests/**").permitAll()
                        // GitHub↔Jira 매핑 관리 (PR import 보고자/담당자 해결). 대시보드와 동일 신뢰 경계.
                        .requestMatchers("/api/github-mappings/**").permitAll()
                        // STUDY: 웹 대시보드(정적 UI + 통계 API) — 사내망 전용 가정으로 permitAll.
                        // ngrok 터널은 Go봇(:3000)만 노출하므로 외부 인터넷에서는 이 경로에 도달 불가.
                        // 판매/외부 노출 시 이 지점에 인증(예: 토큰 필터)을 추가할 것.
                        .requestMatchers("/dashboard/**", "/api/dashboard/**").permitAll()
                        // STUDY: /api/slack/** 는 SlackSignatureFilter 에서 HMAC 검증으로 이미 신원을 확인했으므로
                        // Spring Security 의 authorization 단계에서는 permitAll. 실패 시 필터에서 403 으로 이미 차단됨.
                        .requestMatchers("/api/slack/**").permitAll()
                        // STUDY: /api/jira/** 는 JiraWebhookController 안에서 ?token=... 검증으로 신원 확인.
                        // SlackSignatureFilter 는 "/api/slack/" 프리픽스에서만 동작하므로 간섭 없음.
                        .requestMatchers("/api/jira/**").permitAll()
                        .anyRequest().denyAll()
                )
                // STUDY: addFilterBefore(A, B.class) 는 체인에서 A 를 B 보다 앞에 둔다.
                // 둘 다 같은 기준점(UsernamePasswordAuthenticationFilter) 앞에 두되,
                // SlackSignatureFilter 를 CachedBodyFilter 뒤에 체이닝해 body 가 먼저 캐시되도록 한다.
                .addFilterBefore(slackSignatureFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(cachedBodyFilter, SlackSignatureFilter.class);

        return http.build();
    }
}
