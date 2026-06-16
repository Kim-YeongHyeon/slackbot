package com.jirabot.slack.client.dto;

import java.time.Instant;

// STUDY: 단건 PR 상세 — PR import(PR → Jira 티켓) 용. 목록 DTO(PullRequestInfo)와 달리
//        body(설명)·mergedAt·merged·draft 플래그가 필요해 별도 record 로 둔다.
//        merged → 완료, open&draft → 진행 중, open&ready → 검토 중 까지 워크플로 전환.
public record PullRequestDetail(
        int number,
        String title,
        String body,
        String htmlUrl,
        String authorLogin,
        boolean merged,
        boolean draft,
        Instant createdAt,
        Instant mergedAt
) {}
