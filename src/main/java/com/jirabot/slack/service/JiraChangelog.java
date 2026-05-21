package com.jirabot.slack.service;

// STUDY: Jira webhook 페이로드 changelog.items[] 의 1개 항목.
//        Jira 페이로드 키는 fromString / toString 이지만, record 컴포넌트로는 Object.toString() 과 충돌하므로
//        fromValue / toValue 로 표현한다. (JSON 파싱은 JsonNode 의 path 로 처리하므로 직렬화 호환 무관.)
public record JiraChangelog(
        String field,
        String fromValue,
        String toValue
) {}
