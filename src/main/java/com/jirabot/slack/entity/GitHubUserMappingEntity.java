package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// GitHub 로그인 → Jira 사용자(accountId/displayName) 명시 매핑. PR import 의 보고자/담당자 해결용.
@Entity
@Table(name = "github_user_mappings")
public class GitHubUserMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String githubLogin;

    @Column(nullable = false)
    private String jiraAccountId;

    @Column(nullable = false)
    private String jiraDisplayName;

    @Column(nullable = false)
    private Instant createdAt;

    protected GitHubUserMappingEntity() {}

    public GitHubUserMappingEntity(String githubLogin, String jiraAccountId, String jiraDisplayName) {
        this.githubLogin = githubLogin;
        this.jiraAccountId = jiraAccountId;
        this.jiraDisplayName = jiraDisplayName;
        this.createdAt = Instant.now();
    }

    public void update(String jiraAccountId, String jiraDisplayName) {
        this.jiraAccountId = jiraAccountId;
        this.jiraDisplayName = jiraDisplayName;
    }

    public Long getId() { return id; }
    public String getGithubLogin() { return githubLogin; }
    public String getJiraAccountId() { return jiraAccountId; }
    public String getJiraDisplayName() { return jiraDisplayName; }
    public Instant getCreatedAt() { return createdAt; }
}
