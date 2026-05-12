package com.jirabot.slack.client.dto;

// STUDY: Java record는 불변 데이터 캐리어. Sonnet에게 전달할 이슈 목록의 각 항목을 나타낸다.
public record IssueSearchEntry(
        String issueKey,
        String summary,
        String description,
        String status,
        String assignee
) {}
