package com.jirabot.slack.config;

import com.jirabot.slack.repository.DashboardUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

// STUDY: UserDetailsService — Spring Security 가 인증 시 "아이디 → 계정 정보(해시·권한·활성 여부)" 를
//        묻는 단일 진입점. DaoAuthenticationProvider 가 이걸로 계정을 찾고 PasswordEncoder 로 비밀번호를 대조한다.
//        관리자(sol)는 .env 계정(ROLE_ADMIN), 그 외는 DB 사용자(ROLE_USER, v0.0.79).
//        Basic 인증이라 매 요청 호출된다 — DB 조회는 username UNIQUE 인덱스라 저렴하고, 덕분에
//        비활성화/삭제가 다음 요청부터 즉시 반영된다.
@Component
public class DashboardUserDetailsService implements UserDetailsService {

    private final DashboardUserRepository repository;
    private final String adminUser;
    private final String adminPassword;
    private final String adminDisplayName;

    public DashboardUserDetailsService(DashboardUserRepository repository,
                                       @Value("${dashboard.user}") String adminUser,
                                       @Value("${dashboard.password}") String adminPassword,
                                       @Value("${dashboard.admin-name:관리자}") String adminDisplayName) {
        this.repository = repository;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.adminDisplayName = adminDisplayName == null || adminDisplayName.isBlank() ? "관리자" : adminDisplayName;
    }

    public String adminUsername() {
        return adminUser;
    }

    // 헤더에 표시할 관리자 이름 (DASHBOARD_ADMIN_NAME, 기본 "관리자").
    public String adminDisplayName() {
        return adminDisplayName;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // STUDY: {noop} / {bcrypt} — DelegatingPasswordEncoder 가 접두사로 알고리즘을 고른다.
        //        관리자 비번은 .env 평문이라 {noop}, DB 사용자는 저장된 "{bcrypt}..." 그대로.
        if (adminUser.equals(username)) {
            return User.withUsername(adminUser).password("{noop}" + adminPassword).roles("ADMIN").build();
        }
        return repository.findByUsername(username)
                .map(u -> User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .roles("USER")
                        .disabled(!u.isEnabled())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
    }
}
