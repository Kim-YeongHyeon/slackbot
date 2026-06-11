package com.jirabot.slack.service;

// STUDY: Jira webhook 페이로드 changelog.items[] 의 1개 항목.
//        Jira 페이로드 키는 fromString / toString 이지만, record 컴포넌트로는 Object.toString() 과 충돌하므로
//        fromValue / toValue 로 표현한다. (JSON 파싱은 JsonNode 의 path 로 처리하므로 직렬화 호환 무관.)
public record JiraChangelog(
        String field,
        String fromValue,
        String toValue,
        // STUDY: 페이로드의 `to` — assignee 변경이면 새 담당자의 accountId. displayName 동명이인/표기 차이에
        //        흔들리지 않는 식별자라 할당 DM 의 매핑 조회 1순위로 쓴다.
        String toId
) {
    /** 기존 호출부 호환용 — toId 가 없는 항목(상태 변경 등). */
    public JiraChangelog(String field, String fromValue, String toValue) {
        this(field, fromValue, toValue, null);
    }
}
