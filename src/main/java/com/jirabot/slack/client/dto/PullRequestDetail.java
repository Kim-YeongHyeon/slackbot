package com.jirabot.slack.client.dto;

import java.time.Instant;

// STUDY: 단건 PR 상세 — PR import(merge 완료 PR → Jira 티켓) 용. 목록 DTO(PullRequestInfo)와 달리
//        body(설명)·mergedAt·merged 플래그가 필요해 별도 record 로 둔다.
public record PullRequestDetail(
        int number,
        String title,
        String body,
        String htmlUrl,
        String authorLogin,
        boolean merged,
        Instant createdAt,
        Instant mergedAt
) {}
