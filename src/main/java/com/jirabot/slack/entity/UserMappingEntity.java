package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// STUDY: Slack 유저 ID와 Jira displayName 매핑 테이블.
//        Slack API로 자동 조회한 이름과 Jira 이름이 다를 경우 수동 등록으로 오버라이드.
@Entity
@Table(name = "user_mappings")
public class UserMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slackUserId;

    private String slackDisplayName;

    @Column(nullable = false)
    private String jiraDisplayName;

    private String jiraAccountId;

    // STUDY: 일일 리마인더 DM 수신 여부. 기본 false 로 두고 사용자가 직접 Slack 명령 `리마인더 on` 으로 토글한다.
    //        ddl-auto=update 환경에서 컬럼이 자동 추가되며, 기존 row 는 false 가 적용된다 (Hibernate boolean → 0/false).
    @Column(nullable = false)
    private boolean reminderEnabled = false;

    protected UserMappingEntity() {}

    public UserMappingEntity(String slackUserId, String slackDisplayName, String jiraDisplayName) {
        this.slackUserId = slackUserId;
        this.slackDisplayName = slackDisplayName;
        this.jiraDisplayName = jiraDisplayName;
    }

    public UserMappingEntity(String slackUserId, String slackDisplayName, String jiraDisplayName,
                             String jiraAccountId) {
        this.slackUserId = slackUserId;
        this.slackDisplayName = slackDisplayName;
        this.jiraDisplayName = jiraDisplayName;
        this.jiraAccountId = jiraAccountId;
    }

    public Long getId() { return id; }
    public String getSlackUserId() { return slackUserId; }
    public String getSlackDisplayName() { return slackDisplayName; }
    public String getJiraDisplayName() { return jiraDisplayName; }
    public String getJiraAccountId() { return jiraAccountId; }
    public boolean isReminderEnabled() { return reminderEnabled; }

    public void setJiraDisplayName(String jiraDisplayName) {
        this.jiraDisplayName = jiraDisplayName;
    }

    public void setSlackDisplayName(String slackDisplayName) {
        this.slackDisplayName = slackDisplayName;
    }

    public void setJiraAccountId(String jiraAccountId) {
        this.jiraAccountId = jiraAccountId;
    }

    public void setReminderEnabled(boolean reminderEnabled) {
        this.reminderEnabled = reminderEnabled;
    }
}
