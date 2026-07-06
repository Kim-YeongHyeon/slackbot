package com.jirabot.slack.client.dto;

/**
 * Jira 우선순위. GET /rest/api/3/priority 응답 항목.
 * STUDY: 이름은 사이트마다 로컬라이즈될 수 있어 사용자 입력(높음/high…)을 이 목록에 매칭해 해석한다.
 */
public record PriorityInfo(String id, String name) {}
