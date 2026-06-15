package com.jirabot.slack.controller;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.entity.GitHubUserMappingEntity;
import com.jirabot.slack.repository.GitHubUserMappingRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// STUDY: GitHub 로그인 ↔ Jira 사용자 매핑 관리 API. PR import 의 보고자/담당자 해결 1순위 소스.
//        POST 는 jiraDisplayName 으로 Jira accountId 를 자동 해석(없으면 accountId 직접 입력도 허용).
@RestController
@RequestMapping("/api/github-mappings")
public class GitHubMappingController {

    private static final Logger log = LoggerFactory.getLogger(GitHubMappingController.class);

    private final GitHubUserMappingRepository repository;
    private final JiraApiClient jiraApiClient;

    public GitHubMappingController(GitHubUserMappingRepository repository, JiraApiClient jiraApiClient) {
        this.repository = repository;
        this.jiraApiClient = jiraApiClient;
    }

    @GetMapping
    public List<GitHubUserMappingEntity> listAll() {
        return repository.findAll();
    }

    @PostMapping
    public ResponseEntity<Object> register(@RequestBody Map<String, String> body) {
        String githubLogin = trimmed(body.get("githubLogin"));
        String jiraDisplayName = trimmed(body.get("jiraDisplayName"));
        String jiraAccountId = trimmed(body.get("jiraAccountId"));
        if (githubLogin == null || jiraDisplayName == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "githubLogin and jiraDisplayName are required"));
        }
        // accountId 미입력 시 displayName 으로 Jira 검색해 해석.
        if (jiraAccountId == null) {
            jiraAccountId = jiraApiClient.findAccountId(jiraDisplayName);
        }
        if (jiraAccountId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Jira 사용자를 찾지 못했습니다: '" + jiraDisplayName
                            + "' (정확한 Jira 표시 이름 또는 jiraAccountId 직접 지정)"));
        }

        var existing = repository.findByGithubLoginIgnoreCase(githubLogin);
        GitHubUserMappingEntity entity;
        String status;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.update(jiraAccountId, jiraDisplayName);
            status = "updated";
        } else {
            entity = new GitHubUserMappingEntity(githubLogin, jiraAccountId, jiraDisplayName);
            status = "created";
        }
        repository.save(entity);
        log.info("GitHub mapping {} {} -> {} ({})", status, githubLogin, jiraDisplayName, jiraAccountId);
        return ResponseEntity.ok(Map.of("status", status, "githubLogin", githubLogin,
                "jiraDisplayName", jiraDisplayName, "jiraAccountId", jiraAccountId));
    }

    @DeleteMapping("/{githubLogin}")
    public ResponseEntity<Object> delete(@PathVariable("githubLogin") String githubLogin) {
        var existing = repository.findByGithubLoginIgnoreCase(githubLogin);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repository.delete(existing.get());
        return ResponseEntity.ok(Map.of("status", "deleted", "githubLogin", githubLogin));
    }

    private static String trimmed(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
