package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: Jira → 봇 webhook 인증/트리거 설정. record + @ConfigurationProperties 로 타입 안전 바인딩.
//        notifyOn enum 으로 운영 중 yml 한 줄로 트리거 정책을 전환할 수 있게 한다.
@ConfigurationProperties(prefix = "jira.webhook")
public record JiraWebhookProperties(
        boolean enabled,
        String secret,
        NotifyTrigger notifyOn
) {
    public enum NotifyTrigger {
        STATUS,
        STATUS_CATEGORY,
        DONE_ONLY,
        STATUS_AND_ASSIGNEE
    }
}
