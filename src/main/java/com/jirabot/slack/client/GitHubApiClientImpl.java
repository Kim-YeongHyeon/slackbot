package com.jirabot.slack.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.BranchResult;
import com.jirabot.slack.config.GitHubProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

// STUDY: GitHub REST 로 브랜치(ref) 생성. 3단계 — (1) default_branch 조회 (2) base ref 의 sha 조회 (3) 새 ref 생성.
//        브랜치명에 Jira 이슈키(ES2-XXXX)가 들어가면 Jira 개발 패널이 자동으로 연결해 보여준다.
@Component
public class GitHubApiClientImpl implements GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubApiClientImpl.class);

    private final WebClient githubWebClient;
    private final GitHubProperties props;
    private final ObjectMapper objectMapper;

    public GitHubApiClientImpl(@Qualifier("githubWebClient") WebClient githubWebClient,
                               GitHubProperties props,
                               ObjectMapper objectMapper) {
        this.githubWebClient = githubWebClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public BranchResult createBranch(String repo, String branchName) {
        if (!props.enabled()) {
            return BranchResult.failed(branchName, "GitHub 토큰이 설정되지 않았습니다.");
        }
        if (repo == null || repo.isBlank() || branchName == null || branchName.isBlank()) {
            return BranchResult.failed(branchName, "repo/브랜치명이 비어있습니다.");
        }
        String org = props.org();
        String html = "https://github.com/" + org + "/" + repo + "/tree/" + branchName;
        try {
            // 1. 기본 브랜치 조회 (master 를 쓰는 repo 대비)
            String repoJson = githubWebClient.get()
                    .uri("/repos/{org}/{repo}", org, repo)
                    .retrieve().bodyToMono(String.class).block();
            String base = objectMapper.readTree(repoJson).path("default_branch").asText("main");

            // 2. base 브랜치 head sha
            String refJson = githubWebClient.get()
                    .uri("/repos/{org}/{repo}/git/ref/heads/{base}", org, repo, base)
                    .retrieve().bodyToMono(String.class).block();
            String sha = objectMapper.readTree(refJson).path("object").path("sha").asText("");
            if (sha.isBlank()) {
                log.warn("GitHub base sha empty for {}/{} base={}", org, repo, base);
                return BranchResult.failed(branchName, "base 브랜치 sha 조회 실패");
            }

            // 3. 새 ref 생성
            githubWebClient.post()
                    .uri("/repos/{org}/{repo}/git/refs", org, repo)
                    .bodyValue(Map.of("ref", "refs/heads/" + branchName, "sha", sha))
                    .retrieve().bodyToMono(String.class).block();

            log.info("Created branch {}/{} -> {} (base {})", org, repo, branchName, base);
            return BranchResult.created(branchName, html);
        } catch (WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            // STUDY: 동일 ref 가 이미 있으면 422 "Reference already exists".
            if (e.getStatusCode().value() == 422 && body != null && body.contains("already exists")) {
                return BranchResult.alreadyExists(branchName, html);
            }
            log.warn("GitHub createBranch {}/{} {} failed: {} body={}",
                    org, repo, branchName, e.getStatusCode(), truncate(body));
            return BranchResult.failed(branchName, "GitHub 오류 " + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("GitHub createBranch {}/{} {} error: {}", org, repo, branchName, e.toString());
            return BranchResult.failed(branchName, "요청 실패");
        }
    }

    @Override
    public java.util.List<com.jirabot.slack.client.dto.PullRequestInfo> listOpenPullRequests(String repo) {
        String org = props.org();
        try {
            String json = githubWebClient.get()
                    .uri("/repos/{org}/{repo}/pulls?state=open&per_page=50", org, repo)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode arr = objectMapper.readTree(json);
            java.util.List<com.jirabot.slack.client.dto.PullRequestInfo> result = new java.util.ArrayList<>();
            for (JsonNode pr : arr) {
                result.add(new com.jirabot.slack.client.dto.PullRequestInfo(
                        pr.path("number").asInt(),
                        pr.path("title").asText(""),
                        pr.path("html_url").asText(""),
                        pr.path("user").path("login").asText(""),
                        pr.path("draft").asBoolean(false),
                        pr.path("head").path("ref").asText(""),
                        parseInstant(pr.path("created_at").asText(null)),
                        parseInstant(pr.path("updated_at").asText(null))));
            }
            return result;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // STUDY: fine-grained 토큰은 권한 부족 시 403 대신 404 — "권한 또는 repo 접근 불가" 로 묶어 전달.
                throw new GitHubAccessException(repo + " → HTTP " + e.getStatusCode().value()
                        + " (토큰에 Pull requests: Read 권한이 있는지 확인)");
            }
            log.warn("GitHub listOpenPullRequests {}/{} failed: {}", org, repo, e.getStatusCode());
            return java.util.List.of();
        } catch (Exception e) {
            log.warn("GitHub listOpenPullRequests {}/{} error: {}", org, repo, e.toString());
            return java.util.List.of();
        }
    }

    @Override
    public java.util.Optional<com.jirabot.slack.client.dto.PullRequestDetail> getPullRequest(
            String owner, String repo, int number) {
        if (!props.enabled()) {
            return java.util.Optional.empty();
        }
        String o = (owner == null || owner.isBlank()) ? props.org() : owner;
        try {
            String json = githubWebClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", o, repo, number)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode pr = objectMapper.readTree(json);
            return java.util.Optional.of(new com.jirabot.slack.client.dto.PullRequestDetail(
                    pr.path("number").asInt(number),
                    pr.path("title").asText(""),
                    pr.path("body").isNull() ? "" : pr.path("body").asText(""),
                    pr.path("html_url").asText(""),
                    pr.path("user").path("login").asText(""),
                    pr.path("merged").asBoolean(false),
                    parseInstant(pr.path("created_at").asText(null)),
                    parseInstant(pr.path("merged_at").asText(null))));
        } catch (WebClientResponseException e) {
            log.warn("GitHub getPullRequest {}/{}#{} failed: {}", o, repo, number, e.getStatusCode());
            return java.util.Optional.empty();
        } catch (Exception e) {
            log.warn("GitHub getPullRequest {}/{}#{} error: {}", o, repo, number, e.toString());
            return java.util.Optional.empty();
        }
    }

    @Override
    public java.util.Optional<String> getUserDisplayName(String login) {
        if (!props.enabled() || login == null || login.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            String json = githubWebClient.get()
                    .uri("/users/{login}", login)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode u = objectMapper.readTree(json);
            String name = u.path("name").isNull() ? null : u.path("name").asText(null);
            // 프로필 이름이 없으면 empty — login 으로 Jira 검색하면 오매칭 위험이 커 시도하지 않는다.
            return (name == null || name.isBlank()) ? java.util.Optional.empty() : java.util.Optional.of(name);
        } catch (Exception e) {
            log.warn("GitHub getUserDisplayName {} error: {}", login, e.toString());
            return java.util.Optional.empty();
        }
    }

    private java.time.Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return java.time.Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
