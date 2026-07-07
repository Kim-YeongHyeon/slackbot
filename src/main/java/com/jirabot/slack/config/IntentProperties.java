package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: @ConfigurationProperties로 application.yml의 claude.intent 섹션을 바인딩.
//        record로 선언하면 불변 + 생성자 바인딩.
@ConfigurationProperties(prefix = "claude.intent")
public record IntentProperties(
        String model,
        int timeoutSeconds,
        String promptFile
) {
    public IntentProperties {
        if (model == null || model.isBlank()) model = "claude-haiku-4-5";
        // thinking 차단(v0.0.58) 후 정상 호출은 2~4s — 15s 면 outlier 커버 + 재시도 3회 예산 최악 45s.
        // (thinking 켜져 있던 시절엔 6~24s+ outlier 라 40s 였음)
        if (timeoutSeconds <= 0) timeoutSeconds = 15;
        if (promptFile == null || promptFile.isBlank()) promptFile = "prompts/haiku-classifier.md";
    }

    public String cliPath() {
        return "claude";
    }
}
