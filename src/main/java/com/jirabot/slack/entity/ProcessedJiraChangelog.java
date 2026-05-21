package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// STUDY: Jira webhook 의 changelog.id 를 보존해 동일 변경이 두 번 통보되는 경우를 차단한다.
//        Jira 가 5xx 또는 네트워크 지연 시 같은 페이로드를 재전송하는 패턴에 대비.
//        @Id 자체를 String 으로 두어 JPA save 시 자연스럽게 멱등 동작.
@Entity
@Table(name = "processed_jira_changelog")
public class ProcessedJiraChangelog {

    @Id
    @Column(nullable = false, length = 64)
    private String changelogId;

    @Column(nullable = false)
    private Instant processedAt;

    protected ProcessedJiraChangelog() {}

    public ProcessedJiraChangelog(String changelogId) {
        this.changelogId = changelogId;
        this.processedAt = Instant.now();
    }

    public String getChangelogId() { return changelogId; }
    public Instant getProcessedAt() { return processedAt; }
}
