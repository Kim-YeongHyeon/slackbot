package com.jirabot.slack.controller;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.UserMappingRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// STUDY: 내부 관리용 API. Slack 유저 ↔ Jira displayName 매핑을 등록/조회/수정/삭제한다.
//        scripts/register-user-mapping.sh 와 웹 대시보드(사용자 관리 탭)에서 호출.
//        v0.0.26: Slack `@지라 등록` 플로우와 동일하게 jiraAccountId 자동 해석 + Slack 실명 자동 조회 추가
//        (기존 POST 는 accountId 를 안 채워서 이슈 생성 시 보고자/담당자 자동 설정이 빠지는 반쪽 등록이었다).
@RestController
@RequestMapping("/api/user-mappings")
public class UserMappingController {

    private final UserMappingRepository repository;
    private final JiraApiClient jiraApiClient;
    private final SlackNotifier slackNotifier;

    public UserMappingController(UserMappingRepository repository,
                                 JiraApiClient jiraApiClient,
                                 SlackNotifier slackNotifier) {
        this.repository = repository;
        this.jiraApiClient = jiraApiClient;
        this.slackNotifier = slackNotifier;
    }

    @GetMapping
    public List<UserMappingEntity> listAll() {
        return repository.findAll();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String slackUserId = body.get("slackUserId");
        String slackDisplayName = body.get("slackDisplayName");
        String jiraDisplayName = body.get("jiraDisplayName");

        if (slackUserId == null || slackUserId.isBlank()
                || jiraDisplayName == null || jiraDisplayName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "slackUserId and jiraDisplayName are required"));
        }

        // Slack 등록 플로우와 동일: Jira accountId 해석(이슈 생성 시 보고자/담당자 자동 설정에 필수)
        // + Slack 실명 자동 조회(미입력 시).
        String accountId = jiraApiClient.findAccountId(jiraDisplayName);
        if (slackDisplayName == null || slackDisplayName.isBlank()) {
            slackDisplayName = slackNotifier.getUserRealName(slackUserId);
        }

        var existing = repository.findBySlackUserId(slackUserId);
        UserMappingEntity entity;
        String status;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.setJiraDisplayName(jiraDisplayName);
            if (slackDisplayName != null) entity.setSlackDisplayName(slackDisplayName);
            status = "updated";
        } else {
            entity = new UserMappingEntity(slackUserId, slackDisplayName, jiraDisplayName);
            status = "created";
        }
        if (accountId != null) {
            entity.setJiraAccountId(accountId);
        }
        repository.save(entity);

        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("slackUserId", slackUserId);
        result.put("jiraDisplayName", jiraDisplayName);
        result.put("jiraAccountId", accountId);
        if (accountId == null) {
            // 저장은 하되 경고 — Jira 에서 이름을 못 찾으면 이슈 생성 시 보고자 자동 설정이 빠진다.
            result.put("warning", "Jira에서 '" + jiraDisplayName + "' 사용자를 찾지 못했습니다. "
                    + "Jira에 표시되는 이름인지 확인하세요 (accountId 미설정 상태로 저장됨).");
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{slackUserId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("slackUserId") String slackUserId) {
        var existing = repository.findBySlackUserId(slackUserId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repository.delete(existing.get());
        return ResponseEntity.ok(Map.of("status", "deleted", "slackUserId", slackUserId));
    }

    // STUDY: 부분 수정 — 알림 토글(리마인더/할당 DM)만 PATCH 로 받는다. body 에 있는 키만 반영.
    @PatchMapping("/{slackUserId}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable("slackUserId") String slackUserId,
                                                     @RequestBody Map<String, Object> body) {
        var existing = repository.findBySlackUserId(slackUserId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        UserMappingEntity entity = existing.get();
        if (body.get("reminderEnabled") instanceof Boolean reminder) {
            entity.setReminderEnabled(reminder);
        }
        if (body.get("assignDmEnabled") instanceof Boolean assignDm) {
            entity.setAssignDmEnabled(assignDm);
        }
        repository.save(entity);
        return ResponseEntity.ok(Map.of(
                "status", "patched",
                "slackUserId", slackUserId,
                "reminderEnabled", entity.isReminderEnabled(),
                "assignDmEnabled", entity.isAssignDmEnabled()));
    }
}
