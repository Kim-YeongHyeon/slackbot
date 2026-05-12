package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: 봇 응답 메시지의 알림 정책. mention 형식이 워크플로 알림 피로도에 직결되므로 yml 토글 노출.
//        minIntervalSeconds 는 §C 단계의 자리. 1차에는 0 = disabled 로 동작.
@ConfigurationProperties(prefix = "notify")
public record NotifyProperties(
        MentionMode mention,
        int minIntervalSeconds
) {
    public enum MentionMode {
        MENTION,
        PLAIN
    }
}
