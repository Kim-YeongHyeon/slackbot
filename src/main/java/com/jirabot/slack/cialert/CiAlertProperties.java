package com.jirabot.slack.cialert;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: #sol-dev-alarm 알림 자동 대응(v0.0.91) 설정. record + compact constructor 로 기본값을 채운다
//        (DailyPromptProperties 와 같은 패턴). List<String> 은 yml 의 콤마 구분 문자열에서 바로 바인딩된다.
@ConfigurationProperties(prefix = "ci-alert")
public record CiAlertProperties(
        Boolean enabled,
        String channel,
        // 알림을 올리는 앱의 bot_id ("Sol Dev Alarm"). 이 앱이 채널 최상위에 올린 글만 알림으로 본다.
        List<String> alertBotIds,
        String org,
        List<String> repos,
        // GITHUB_ALERT_TOKEN — 4개 레포 전용 fine-grained PAT (Actions R / Contents RW / PR RW).
        String githubToken,
        String apiBaseUrl,
        int repeatWindowHours,
        int analysisTimeoutSeconds,
        int maxAlertAgeMinutes,
        // SBOM 취약점이 나온 이미지 → 고칠 레포 ("image=repo"). 목록에 없는 이미지는 알림이 온 레포가 고친다.
        // KMS 이미지는 envector 의 external/HE-KMS 서브모듈(HE-KMS 레포)에서 만들어진다.
        List<String> sbomImageOwners
) {
    public CiAlertProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (channel == null) {
            channel = "";
        }
        alertBotIds = alertBotIds == null ? List.of() : List.copyOf(alertBotIds);
        if (org == null || org.isBlank()) {
            org = "CryptoLabInc";
        }
        repos = repos == null ? List.of() : List.copyOf(repos);
        if (githubToken == null) {
            githubToken = "";
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.github.com";
        }
        if (repeatWindowHours <= 0) {
            repeatWindowHours = 24;
        }
        if (analysisTimeoutSeconds <= 0) {
            analysisTimeoutSeconds = 180;
        }
        if (maxAlertAgeMinutes <= 0) {
            maxAlertAgeMinutes = 60;
        }
        sbomImageOwners = sbomImageOwners == null ? List.of() : List.copyOf(sbomImageOwners);
    }

    // 이미지 이름으로 고칠 레포를 찾는다. 매핑이 없으면 fallbackRepo(알림이 온 레포).
    public String ownerOf(String image, String fallbackRepo) {
        for (String pair : sbomImageOwners) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).strip().equals(image)) {
                return pair.substring(eq + 1).strip();
            }
        }
        return fallbackRepo;
    }

    // 토큰·채널·발신 앱이 모두 있어야 동작한다. 하나라도 비면 서버는 정상 기동하고 이 기능만 꺼진다.
    public boolean effectivelyEnabled() {
        return enabled && !channel.isBlank() && !alertBotIds.isEmpty() && !githubToken.isBlank();
    }
}
