package com.jirabot.slack.client.dto;

/**
 * Jira 이슈 링크 타입. GET /rest/api/3/issueLinkType 응답의 한 항목.
 * <p>
 * STUDY: Jira 링크는 방향이 있다 — {@code inwardIssue <inward> outwardIssue}.
 *        예) Blocks: inward="is blocked by", outward="blocks" →
 *        {@code {inwardIssue:A, outwardIssue:B}} 는 "B blocks A / A is blocked by B".
 */
public record IssueLinkType(String id, String name, String inward, String outward) {}
