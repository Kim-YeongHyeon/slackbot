package com.jirabot.slack.cialert;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: 연속 실패 자동 수정 (v0.0.100) 설정. ci-alert.code-fix.* — 같은 워크플로가 minStreak 번 이상 연속 실패하면
//        Opus 에이전트가 레포를 보며 원인을 다시 분석하고, 고칠 수 있으면 수정 PR 을 올린다.
//        OAuth 토큰·작업 폴더·에이전트 이미지는 SBOM 수정(CiFixProperties, ci-alert.fix.*)과 같이 쓴다.
@ConfigurationProperties(prefix = "ci-alert.code-fix")
public record CiCodeFixProperties(
        Boolean enabled,
        int minStreak,
        String model,
        // claude --effort (low | medium | high | xhigh | max). 비면 플래그를 주지 않는다.
        String effort,
        int timeoutMinutes,
        int maxTurns,
        String promptFile,
        // 넘으면 PR 을 만들지 않는다 (go.sum 제외 변경 줄 수, 변경 파일 수)
        int maxDiffLines,
        int maxFiles,
        // 같은 워크플로·브랜치에 수정 단계를 다시 돌리기까지의 시간
        int cooldownHours,
        // 에이전트에게 보여 줄 열린 PR 수 (최근 갱신 순)
        int maxOpenPrs
) {
    public CiCodeFixProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (minStreak <= 0) {
            minStreak = 3;
        }
        if (model == null || model.isBlank()) {
            model = "claude-opus-5-5";
        }
        if (effort == null) {
            effort = "high";
        }
        if (timeoutMinutes <= 0) {
            timeoutMinutes = 30;
        }
        if (maxTurns <= 0) {
            maxTurns = 100;
        }
        if (promptFile == null || promptFile.isBlank()) {
            promptFile = "prompts/ci-fix-agent.md";
        }
        if (maxDiffLines <= 0) {
            maxDiffLines = 300;
        }
        if (maxFiles <= 0) {
            maxFiles = 10;
        }
        if (cooldownHours <= 0) {
            cooldownHours = 24;
        }
        if (maxOpenPrs <= 0) {
            maxOpenPrs = 20;
        }
    }
}
