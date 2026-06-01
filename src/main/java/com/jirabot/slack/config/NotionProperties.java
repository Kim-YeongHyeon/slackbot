package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: Notion 연동 설정. token 이 비어 있으면 기능 자체를 비활성(effectivelyEnabled=false) 하여
//        토큰 미설정 환경에서도 앱이 정상 기동하도록 한다(웹훅 secret 패턴과 동일).
//        - resolutionDbId: "버그 해결 기록" DB (원인/해결방법 상세, 완료 시 자동 적재)
//        - statusDbId    : "버그 현황" DB (전체 버그 + 상태(해결/미해결), 백필/동기화)
@ConfigurationProperties(prefix = "notion")
public record NotionProperties(
        Boolean enabled,
        String token,
        String version,
        String parentPageId,
        String resolutionDbId,
        String statusDbId
) {
    public NotionProperties {
        if (version == null || version.isBlank()) {
            version = "2022-06-28";
        }
    }

    public boolean effectivelyEnabled() {
        return (enabled == null || enabled) && token != null && !token.isBlank();
    }
}
