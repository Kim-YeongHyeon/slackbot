package com.jirabot.slack.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreadActionResult(
        String action,
        double confidence,
        Map<String, String> extracted,
        String rawInput
) {
    public static final double CONFIDENCE_THRESHOLD = 0.6;

    public boolean isActionable() {
        return confidence >= CONFIDENCE_THRESHOLD
                && action != null
                && !"unknown".equals(action);
    }

    public static ThreadActionResult unknown(String rawInput) {
        return new ThreadActionResult("unknown", 0, Map.of(), rawInput);
    }

    // 분류기가 결과를 내지 못함(프로세스 실패·타임아웃·응답 파싱 실패) — "이해 못 함"(unknown)과 구분한다.
    public static final String FAILED = IntentResult.FAILED;

    public static ThreadActionResult failed(String rawInput) {
        return new ThreadActionResult(FAILED, 0, Map.of(), rawInput);
    }

    public boolean isFailure() {
        return FAILED.equals(action);
    }
}
