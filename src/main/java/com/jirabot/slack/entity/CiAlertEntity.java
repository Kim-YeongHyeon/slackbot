package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

// #sol-sbom-ci 알림 처리 기록 (v0.0.91, V10). 처리 상태·분석 요약·답글 ts 를 남겨
// 반복 알림 판단(fingerprint)과 이전 분석 링크(reply_ts)에 쓴다.
@Entity
// STUDY: uniqueConstraints 는 운영(Flyway V10)과 같은 제약을 테스트(H2 create-drop)에도 만들기 위해 엔티티에도 적는다.
@Table(name = "ci_alerts", uniqueConstraints = @UniqueConstraint(
        name = "uq_ci_alerts_message", columnNames = {"channel", "message_ts"}))
public class CiAlertEntity {

    public enum Status { PROCESSING, DONE, REPEAT, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String channel;

    @Column(name = "message_ts", nullable = false, length = 40)
    private String messageTs;

    @Column(length = 100)
    private String repo;

    @Column(name = "run_id")
    private Long runId;

    @Column(length = 20)
    private String kind;

    // STUDY: @Enumerated(STRING) — 순서(ORDINAL)가 아니라 이름으로 저장해서 enum 순서를 바꿔도 데이터가 깨지지 않는다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 64)
    private String fingerprint;

    @Column(length = 20)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "reply_ts", length = 40)
    private String replyTs;

    @Column(name = "vuln_ids", columnDefinition = "TEXT")
    private String vulnIds;

    @Column(name = "pr_urls", columnDefinition = "TEXT")
    private String prUrls;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CiAlertEntity() {}

    public CiAlertEntity(String channel, String messageTs) {
        this.channel = channel;
        this.messageTs = messageTs;
        this.status = Status.PROCESSING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void restart() {
        this.status = Status.PROCESSING;
        this.error = null;
        touch();
    }

    public void identify(String repo, long runId, String kind) {
        this.repo = repo;
        this.runId = runId;
        this.kind = kind;
        touch();
    }

    public void finish(Status status, String fingerprint, String category, String summary, String replyTs) {
        this.status = status;
        this.fingerprint = fingerprint;
        this.category = category;
        this.summary = summary;
        this.replyTs = replyTs;
        touch();
    }

    // SBOM 알림에서 찾은 취약점 ID (콤마 구분) — 3단계 수정 PR 중복 확인에 쓴다.
    public void recordVulns(String vulnIds) {
        this.vulnIds = vulnIds;
        touch();
    }

    public void fail(Status status, String error) {
        this.status = status;
        this.error = error;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getChannel() { return channel; }
    public String getMessageTs() { return messageTs; }
    public String getRepo() { return repo; }
    public Long getRunId() { return runId; }
    public String getKind() { return kind; }
    public Status getStatus() { return status; }
    public String getFingerprint() { return fingerprint; }
    public String getCategory() { return category; }
    public String getSummary() { return summary; }
    public String getReplyTs() { return replyTs; }
    public String getVulnIds() { return vulnIds; }
    public String getPrUrls() { return prUrls; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
