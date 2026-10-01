package com.jirabot.slack.controller;

import com.jirabot.slack.config.DashboardUserDetailsService;
import com.jirabot.slack.entity.DashboardUserEntity;
import com.jirabot.slack.repository.DashboardUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// STUDY: 대시보드 회원 관리 (v0.0.79). /api/admin/** 는 SecurityConfig 에서 hasRole("ADMIN") —
//        일반 사용자는 이 컨트롤러에 도달하기 전에 403. /api/dashboard/me 는 로그인한 누구나.
//        FeatureRequestController 처럼 서비스 레이어 없이 repo 직접 사용.
@RestController
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);
    static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,30}$");
    static final int MIN_PASSWORD = 4;
    static final int MAX_PASSWORD_BYTES = 72; // BCrypt 는 72바이트 이후를 무시한다
    static final int MAX_DISPLAY_NAME = 100;

    // 응답 DTO — 엔티티를 그대로 내보내면 passwordHash 가 직렬화되므로 반드시 변환한다.
    record UserView(Long id, String username, String displayName, boolean enabled, Instant createdAt) {
        static UserView of(DashboardUserEntity u) {
            return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.isEnabled(), u.getCreatedAt());
        }
    }

    static class BadRequest extends RuntimeException {
        BadRequest(String message) { super(message); }
    }

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<Object> onBadRequest(BadRequest e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private final DashboardUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final DashboardUserDetailsService userDetailsService;

    public AdminUserController(DashboardUserRepository repository, PasswordEncoder passwordEncoder,
                               DashboardUserDetailsService userDetailsService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.userDetailsService = userDetailsService;
    }

    // 현재 로그인 사용자 — 헤더 이름 표시 + 회원 관리 탭 노출 판단용.
    // STUDY: 컨트롤러 파라미터에 Authentication 을 선언하면 Spring MVC 가 SecurityContext 의 현재 인증을 주입한다.
    @GetMapping("/api/dashboard/me")
    public Map<String, Object> me(Authentication auth) {
        return Map.of("username", auth.getName(),
                "displayName", userDetailsService.displayNameOf(auth),
                "admin", DashboardUserDetailsService.isAdmin(auth));
    }

    @GetMapping("/api/admin/users")
    public List<UserView> list() {
        return repository.findAllByOrderByCreatedAtAsc().stream().map(UserView::of).toList();
    }

    @PostMapping("/api/admin/users")
    public UserView create(@RequestBody Map<String, Object> body) {
        String username = str(body.get("username"));
        username = username == null ? null : username.strip();
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new BadRequest("아이디는 영문 소문자·숫자·. _ - 조합 3~30자로 입력해주세요.");
        }
        if (username.equalsIgnoreCase(userDetailsService.adminUsername())) {
            throw new BadRequest("관리자 아이디는 사용할 수 없습니다.");
        }
        if (repository.existsByUsername(username)) {
            throw new BadRequest("이미 사용 중인 아이디입니다: " + username);
        }
        String displayName = requireDisplayName(str(body.get("displayName")));
        String password = requirePassword(str(body.get("password")));

        DashboardUserEntity saved = repository.save(
                new DashboardUserEntity(username, displayName, passwordEncoder.encode(password)));
        log.info("Dashboard user created username={} displayName={}", username, displayName);
        return UserView.of(saved);
    }

    // 부분 수정: password(초기화) / enabled(비활성·활성) / displayName. 보낸 필드만 반영.
    @PatchMapping("/api/admin/users/{id}")
    public ResponseEntity<Object> patch(@PathVariable long id, @RequestBody Map<String, Object> body) {
        DashboardUserEntity user = repository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.containsKey("password")) {
            user.setPasswordHash(passwordEncoder.encode(requirePassword(str(body.get("password")))));
            log.info("Dashboard user password reset username={}", user.getUsername());
        }
        if (body.get("enabled") instanceof Boolean enabled) {
            user.setEnabled(enabled);
            log.info("Dashboard user {} username={}", enabled ? "enabled" : "disabled", user.getUsername());
        }
        if (body.containsKey("displayName")) {
            user.setDisplayName(requireDisplayName(str(body.get("displayName"))));
        }
        return ResponseEntity.ok(UserView.of(repository.save(user)));
    }

    @DeleteMapping("/api/admin/users/{id}")
    public ResponseEntity<Object> delete(@PathVariable long id) {
        DashboardUserEntity user = repository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        repository.delete(user);
        log.info("Dashboard user deleted username={}", user.getUsername());
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    private static String requireDisplayName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > MAX_DISPLAY_NAME) {
            throw new BadRequest("이름을 1~" + MAX_DISPLAY_NAME + "자로 입력해주세요.");
        }
        return n;
    }

    private static String requirePassword(String pw) {
        if (pw == null || pw.length() < MIN_PASSWORD) {
            throw new BadRequest("비밀번호는 " + MIN_PASSWORD + "자 이상이어야 합니다.");
        }
        if (pw.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadRequest("비밀번호가 너무 깁니다 (72바이트 이내).");
        }
        return pw;
    }

    private static String str(Object v) {
        return v instanceof String s ? s : null;
    }
}
