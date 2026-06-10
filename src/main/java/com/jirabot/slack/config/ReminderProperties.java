package com.jirabot.slack.config;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: 리마인더 설정.
//        enabled 는 전역 비상 차단용으로만 사용한다. Boolean 으로 두어 yml 누락(null) 과 명시적 false 를 구분한다.
//        - null  → effectivelyEnabled()=true (yml 에 reminder 블록 자체가 없는 경우의 기본 동작은 ON)
//        - true  → ON
//        - false → 명시적 OFF (비상 차단)
//        개별 사용자 opt-in 은 Slack 명령 `@봇더지라 리마인더 on` 으로 처리하며 UserMappingEntity.reminderEnabled 에 저장.
//        cron / zone / biweekly 도 record 의 compact constructor 에서 기본값을 적용해 yml 누락 시 동작한다.
//        - cron          : 일일(현재 스프린트) 리마인더. 평일 09:00 KST.
//        - biweeklyCron  : 격주(전체 미해결) 리마인더가 점화되는 cron. 매주 월 09:30 — 실제 격주 여부는 anchor parity 로 판단.
//        - biweeklyAnchor: 격주 기준 월요일(ISO date). 이 주를 0주차로 보고 짝수 주차 월요일에만 전체 리마인더 발송.
@ConfigurationProperties(prefix = "reminder")
public record ReminderProperties(
        Boolean enabled,
        String cron,
        String zone,
        String biweeklyCron,
        String biweeklyAnchor,
        Integer staleDays
) {
    public ReminderProperties {
        if (cron == null || cron.isBlank()) {
            cron = "0 0 9 * * MON-FRI";
        }
        if (zone == null || zone.isBlank()) {
            zone = "Asia/Seoul";
        }
        if (biweeklyCron == null || biweeklyCron.isBlank()) {
            biweeklyCron = "0 30 9 * * MON";
        }
        if (biweeklyAnchor == null || biweeklyAnchor.isBlank()) {
            biweeklyAnchor = "2026-06-22";
        }
        // STUDY: 일일 리마인더에서 "진행 중" 상태로 이 일수 이상 정체된 이슈를 ⚠️ 태그. 기본 7일.
        if (staleDays == null || staleDays <= 0) {
            staleDays = 7;
        }
    }

    public boolean effectivelyEnabled() {
        return enabled == null || enabled;
    }

    // STUDY: biweeklyAnchor 문자열을 LocalDate 로 파싱. anchor 는 격주 계산의 기준 월요일.
    public LocalDate anchor() {
        return LocalDate.parse(biweeklyAnchor);
    }
}
