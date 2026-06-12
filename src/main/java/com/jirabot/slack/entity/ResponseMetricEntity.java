package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// STUDY: 응답 시간 계측 레코드. totalMs 는 Slack 메시지 ts 기준 end-to-end(사용자 체감),
//        단계별 *Ms 는 Spring 내부 구간(분류/중복감지/Jira/DB/알림)이라 둘의 차이가
//        "Spring 밖" 구간(Haiku 의도분류 + Go봇/터널 전달 + async 큐 대기)이다.
@Entity
@Table(name = "response_metrics")
public class ResponseMetricEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String action;

    private String issueKey;

    private String slackUserId;

    private String slackChannel;

    @Column(nullable = false)
    private boolean success;

    @Column(nullable = false)
    private long totalMs;

    private Long classifyMs;

    private Long duplicateMs;

    private Long jiraMs;

    private Long dbMs;

    private Long notifyMs;

    private String errorType;

    @Column(nullable = false)
    private Instant startedAt;

    protected ResponseMetricEntity() {}

    public ResponseMetricEntity(String action, String issueKey, String slackUserId,
                                String slackChannel, boolean success, long totalMs,
                                Long classifyMs, Long duplicateMs, Long jiraMs,
                                Long dbMs, Long notifyMs, String errorType,
                                Instant startedAt) {
        this.action = action;
        this.issueKey = issueKey;
        this.slackUserId = slackUserId;
        this.slackChannel = slackChannel;
        this.success = success;
        this.totalMs = totalMs;
        this.classifyMs = classifyMs;
        this.duplicateMs = duplicateMs;
        this.jiraMs = jiraMs;
        this.dbMs = dbMs;
        this.notifyMs = notifyMs;
        this.errorType = errorType;
        this.startedAt = startedAt;
    }

    public Long getId() { return id; }
    public String getAction() { return action; }
    public String getIssueKey() { return issueKey; }
    public String getSlackUserId() { return slackUserId; }
    public String getSlackChannel() { return slackChannel; }
    public boolean isSuccess() { return success; }
    public long getTotalMs() { return totalMs; }
    public Long getClassifyMs() { return classifyMs; }
    public Long getDuplicateMs() { return duplicateMs; }
    public Long getJiraMs() { return jiraMs; }
    public Long getDbMs() { return dbMs; }
    public Long getNotifyMs() { return notifyMs; }
    public String getErrorType() { return errorType; }
    public Instant getStartedAt() { return startedAt; }
}
