package com.jirabot.slack.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// STUDY: 버그 완료 시 Claude 가 생성하는 원인/해결 요약. 분류 실패 시 fallback() 으로 안전한 기본값을 쓴다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record BugResolutionSummary(String cause, String fix) {

    public static BugResolutionSummary fallback() {
        return new BugResolutionSummary("(자동 요약 실패)", "(자동 요약 실패)");
    }

    public String causeOrDefault() {
        return (cause == null || cause.isBlank()) ? "-" : cause;
    }

    public String fixOrDefault() {
        return (fix == null || fix.isBlank()) ? "-" : fix;
    }
}
