package com.jirabot.slack.client.dto;

import java.time.Instant;

// STUDY: GitHub PR 목록 응답에서 대시보드에 필요한 필드만 추출한 경량 DTO.
public record PullRequestInfo(
        int number,
        String title,
        String htmlUrl,
        String authorLogin,
        boolean draft,
        String headRef,      // 브랜치명 — feature/ES2-123-slug 규칙이라 Jira 이슈 키 추출 소스
        Instant createdAt,
        Instant updatedAt
) {}
