package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: 평일 아침 "오늘의 부탁" 채널 메시지 설정 (v0.0.78).
//        - enabled     : 비상 차단용. null/true → ON, 명시적 false → OFF.
//        - channel     : 발송 채널 ID. 비어 있으면 발송 생략 (설정 누락이 엉뚱한 곳 발송으로 이어지지 않게).
//        - cron / zone : 기본 평일 09:30 KST.
//        - messageFile : 문구 파일 경로(선택). 비우면 classpath messages/daily-prompt.txt 사용 —
//                        파일 경로를 지정하면 재빌드 없이 문구를 바꾸고 재기동만 하면 된다.
@ConfigurationProperties(prefix = "daily-prompt")
public record DailyPromptProperties(
        Boolean enabled,
        String channel,
        String cron,
        String zone,
        String messageFile
) {
    public DailyPromptProperties {
        if (cron == null || cron.isBlank()) {
            cron = "0 30 9 * * MON-FRI";
        }
        if (zone == null || zone.isBlank()) {
            zone = "Asia/Seoul";
        }
    }

    public boolean effectivelyEnabled() {
        return enabled == null || enabled;
    }
}
