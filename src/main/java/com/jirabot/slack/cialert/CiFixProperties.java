package com.jirabot.slack.cialert;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: SBOM 자동 수정 PR (v0.0.94) 설정. ci-alert.fix.* — 수정은 격리된 docker 컨테이너 안의 Claude 에이전트가 하고,
//        검증·커밋·push·PR 은 Java 가 한다. oauthToken 은 `claude setup-token` 으로 만든 CI 용 장기 토큰
//        (서버의 claude 로그인과 별개 — 컨테이너가 서버 로그인을 갱신해 기존 분류기가 끊기는 일을 막는다).
@ConfigurationProperties(prefix = "ci-alert.fix")
public record CiFixProperties(
        Boolean enabled,
        String oauthToken,
        // 비면 claude.model 을 쓴다.
        String model,
        int timeoutMinutes,
        int maxAttempts,
        int maxTurns,
        String agentImage,
        // 에이전트 이미지 Dockerfile 이 있는 디렉터리 (이미지가 없을 때만 빌드)
        String agentDockerDir,
        String promptFile,
        String workDir,
        // grype DB 캐시 (이 서버에 두고 재사용)
        String grypeImage,
        String grypeCacheDir,
        int maxDiffLines
) {
    public CiFixProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (oauthToken == null) {
            oauthToken = "";
        }
        if (model == null) {
            model = "";
        }
        if (timeoutMinutes <= 0) {
            timeoutMinutes = 20;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 2;
        }
        if (maxTurns <= 0) {
            maxTurns = 60;
        }
        if (agentImage == null || agentImage.isBlank()) {
            agentImage = "jirabot-fix-agent:latest";
        }
        if (agentDockerDir == null || agentDockerDir.isBlank()) {
            agentDockerDir = "docker/fix-agent";
        }
        if (promptFile == null || promptFile.isBlank()) {
            promptFile = "prompts/sbom-fix-agent.md";
        }
        if (workDir == null || workDir.isBlank()) {
            workDir = "/var/tmp/ci-fix";
        }
        if (grypeImage == null || grypeImage.isBlank()) {
            grypeImage = "anchore/grype:latest";
        }
        if (grypeCacheDir == null || grypeCacheDir.isBlank()) {
            grypeCacheDir = "/var/tmp/ci-fix/grype-db";
        }
        if (maxDiffLines <= 0) {
            maxDiffLines = 400;
        }
    }

    // 토큰이 없으면 수정 단계만 꺼진다 (표 답글은 그대로).
    public boolean effectivelyEnabled() {
        return enabled && !oauthToken.isBlank();
    }
}
