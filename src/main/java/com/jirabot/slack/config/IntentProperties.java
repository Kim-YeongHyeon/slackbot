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
        // Haiku 정상 호출은 6~8s 이나 일부 입력 (캐시 미스 / 긴 출력) 에서 20s+ outlier 관찰됨.
        // 15s 는 outlier 를 잡지 못해 conf=0 fallback 으로 떨어지므로 25s 로 여유 확보.
        if (timeoutSeconds <= 0) timeoutSeconds = 25;
        if (promptFile == null || promptFile.isBlank()) promptFile = "prompts/haiku-classifier.md";
    }

    public String cliPath() {
        return "claude";
    }
}
