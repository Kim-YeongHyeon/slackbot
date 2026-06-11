package com.jirabot.slack.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: GitHub 브랜치 생성용 설정. record + @ConfigurationProperties 로 application.yml `github.*` 바인딩.
//        - token: fine-grained PAT(대상 repo contents:write). 비어있으면 기능 비활성 → 호출부가 기존 힌트로 폴백.
//        - org: 대상 조직(기본 CryptoLabInc). repo 는 org 하위 이름만 설정(envector-msa 등).
//        - branchRepos: "진행 중" 전환 시 Slack 버튼으로 띄울 repo 목록. 콤마 구분 env → List 바인딩.
//        - apiBaseUrl: GitHub API 베이스(테스트/엔터프라이즈 대비 외부화).
@ConfigurationProperties(prefix = "github")
public record GitHubProperties(
        String token,
        String org,
        List<String> branchRepos,
        String apiBaseUrl
) {
    public GitHubProperties {
        if (org == null || org.isBlank()) {
            org = "CryptoLabInc";
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.github.com";
        }
        if (branchRepos == null || branchRepos.isEmpty()) {
            branchRepos = List.of("envector-msa", "evi");
        }
    }

    // STUDY: 토큰과 repo 목록이 모두 있어야 브랜치 생성 기능 활성. 미설정이면 호출부가 Jira UI 힌트로 폴백.
    public boolean enabled() {
        return token != null && !token.isBlank() && branchRepos != null && !branchRepos.isEmpty();
    }
}
