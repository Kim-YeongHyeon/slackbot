package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: @ConfigurationProperties는 application.yml의 prefix 섹션을 타입 안전 빈으로 바인딩.
// STUDY: record 의 compact constructor — 바인딩된 값이 null/blank 일 때 안전 디폴트 주입.
@ConfigurationProperties(prefix = "claude")
public record ClaudeProperties(
        String cliPath,
        String model,
        // STUDY: 단순 변환성 작업(브랜치 슬러그 등)용 경량 모델. 품질이 중요한 분류/검색/요약은 model(Sonnet),
        //        형식이 단순하고 지연이 중요한 작업은 fastModel(Haiku) — 작업별 모델 계층화.
        String fastModel,
        int timeoutSeconds,
        String permissionMode,
        int maxTurns
) {
    public ClaudeProperties {
        if (cliPath == null || cliPath.isBlank()) {
            cliPath = "claude";
        }
        if (fastModel == null || fastModel.isBlank()) {
            fastModel = "claude-haiku-4-5";
        }
        if (permissionMode == null || permissionMode.isBlank()) {
            permissionMode = "plan";
        }
        if (maxTurns <= 0) {
            maxTurns = 1;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }
    }
}
