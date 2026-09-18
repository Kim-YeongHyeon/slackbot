package com.jirabot.slack.config;

import com.jirabot.slack.filter.CachedBodyFilter;
import com.jirabot.slack.filter.SlackSignatureFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// STUDY: @EnableWebSecurity 는 Spring Security 필터 체인 자동 구성을 활성화. 6.x 부터는 람다 DSL 이 기본.
// SessionCreationPolicy.STATELESS — 서버가 HttpSession 을 생성/사용하지 않음. Slack webhook 처럼
// 매 요청이 독립적인 API 에 적합. Basic 인증도 매 요청 헤더로 오므로 세션 불필요.
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    // 대시보드 로그인 계정 (v0.0.73 — 전 경로 로그인 필수). Go 터널 프록시와 같은 .env 값을 쓰므로
    // 터널 경유 시 브라우저가 이미 보낸 Authorization 헤더가 그대로 통과해 이중 입력이 없다.
    // STUDY: {noop} — DelegatingPasswordEncoder 의 평문 비교 접두사. 단일 내부 계정 + .env 평문
    //        저장이라 해시 인코딩의 실익이 없어 채택. 계정이 늘면 BCrypt 로 전환할 것.
    @Bean
    public InMemoryUserDetailsManager dashboardUser(
            @Value("${dashboard.user}") String user,
            @Value("${dashboard.password}") String password) {
        return new InMemoryUserDetailsManager(
                User.withUsername(user).password("{noop}" + password).roles("DASHBOARD").build());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CachedBodyFilter cachedBodyFilter,
            SlackSignatureFilter slackSignatureFilter) throws Exception {

        http
                // STUDY: CSRF 미적용 + Basic 인증 조합의 알려진 한계 — 브라우저가 자격증명을 자동 첨부하므로
                // 타 사이트발 form POST 가 이론상 실행될 수 있다. JSON @RequestBody 엔드포인트는 415 로
                // 무해하고 내부 도구라 수용 (기존 무인증 LAN 모델보다 엄격히 개선).
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // v0.0.73: 대시보드 전 경로 로그인 필수 — 터널이든 사내망 직접(:8080)이든 동일하게 요구.
                .httpBasic(basic -> basic.realmName("sol dashboard"))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // STUDY: ERROR 디스패치 허용 — 미존재 경로가 /error 로 forward 될 때 denyAll 에 걸려
                        // 404 가 403 으로 둔갑하는 것을 막는다 (Spring Security 6 는 에러 디스패치도 인가 대상).
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        // 헬스는 무인증 유지 — start.sh/jdk-watchdog/봇상태 카드가 자격증명 없이 호출.
                        .requestMatchers("/health", "/actuator/health", "/actuator/info").permitAll()
                        // 대시보드 전체 (정적 UI·통계·관리 API·아티팩트 갤러리+뷰어) — 로그인 필수 (v0.0.73).
                        // 뷰어(/artifacts/view/**)도 사용자 결정으로 포함: 무인증 링크 공유 기능은 중단.
                        // 뷰어 응답의 CSP sandbox 는 유지 — 로그인해도 저장형 XSS 방어는 필요 (ArtifactController).
                        .requestMatchers(
                                "/dashboard/**", "/api/dashboard/**",
                                "/api/user-mappings/**", "/api/feature-requests/**",
                                "/api/github-mappings/**",
                                "/api/artifacts/**", "/artifacts/view/**").authenticated()
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
