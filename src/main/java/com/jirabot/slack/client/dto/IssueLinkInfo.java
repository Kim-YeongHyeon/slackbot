package com.jirabot.slack.client.dto;

/**
 * 한 이슈의 링크 한 건(조회자 기준). GET /rest/api/3/issue/{key}?fields=issuelinks 파싱 결과.
 * <p>
 * STUDY: issuelinks 항목은 조회자 상대적이다 — outwardIssue O 가 있으면 "&lt;this&gt; &lt;type.outward&gt; O",
 *        inwardIssue I 가 있으면 "&lt;this&gt; &lt;type.inward&gt; I". description 은 그 관계 설명(예: "blocks").
 *
 * @param linkId      링크 ID (해제 시 사용)
 * @param description 조회자 기준 관계 설명 (예: "blocks", "is blocked by")
 * @param otherKey    상대 이슈 키
 * @param otherSummary 상대 이슈 요약(없으면 null)
 */
public record IssueLinkInfo(String linkId, String description, String otherKey, String otherSummary) {}
