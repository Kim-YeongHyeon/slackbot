package com.jirabot.slack.client.dto;

// STUDY: Jira Agile API 응답에서 필요한 필드만 추출한 경량 DTO.
//        API 응답의 nested 구조(fields.status.name 등)는 JiraApiClientImpl에서 JsonNode로 파싱 후 매핑.
public record SprintIssue(
        String key,
        String summary,
        String status,           // "해야 할 일", "진행 중", "완료"
        String statusCategory,   // "해야 할 일", "진행 중", "완료"
        String assignee,         // displayName, null이면 미배정
        String issueType,        // "버그", "작업", "스토리" 등
        double storyPoint,       // 0이면 미설정
        String parentKey,        // 하위 작업이면 부모 이슈 키, 아니면 null
        String created,          // ISO datetime
        String updated           // ISO datetime
) {}
