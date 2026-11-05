package com.jirabot.slack.cialert;

// 알림 한 건에서 뽑은 정보. text 는 원문 그대로 — GitHub 로그를 못 받을 때 원문 속 로그 일부를 대신 쓴다.
public record CiAlert(Kind kind, String repo, long runId, String runUrl, String text) {

    public enum Kind { CI, SBOM }
}
