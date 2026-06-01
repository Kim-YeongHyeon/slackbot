package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: 일일 리마인더 설정.
//        enabled 는 전역 비상 차단용으로만 사용한다. Boolean 으로 두어 yml 누락(null) 과 명시적 false 를 구분한다.
//        - null  → effectivelyEnabled()=true (yml 에 reminder 블록 자체가 없는 경우의 기본 동작은 ON)
//        - true  → ON
//        - false → 명시적 OFF (비상 차단)
//        개별 사용자 opt-in 은 Slack 명령 `@봇더지라 리마인더 on` 으로 처리하며 UserMappingEntity.reminderEnabled 에 저장.
//        cron / zone 도 record 의 compact constructor 에서 기본값을 적용해 yml 누락 시 평일 09:00 KST 로 동작한다.
@ConfigurationProperties(prefix = "reminder")
public record ReminderProperties(
        Boolean enabled,
        String cron,
        String zone
) {
    public ReminderProperties {
        if (cron == null || cron.isBlank()) {
            cron = "0 0 9 * * MON-FRI";
        }
        if (zone == null || zone.isBlank()) {
            zone = "Asia/Seoul";
        }
    }

    public boolean effectivelyEnabled() {
        return enabled == null || enabled;
    }
}
