package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// 대시보드 일반 사용자 계정 (v0.0.79). 관리자가 회원 관리 탭에서 생성한다.
// passwordHash 는 "{bcrypt}..." 형식 — 응답으로 절대 내보내지 않는다 (AdminUserController 는 DTO 로 변환).
@Entity
@Table(name = "dashboard_users")
public class DashboardUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DashboardUserEntity() {}

    public DashboardUserEntity(String username, String displayName, String passwordHash) {
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
