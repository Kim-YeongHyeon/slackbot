package com.jirabot.slack.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IntentResult(
        String intent,
        double confidence,
        Map<String, String> extracted,
        String rawInput
) {
    public static final double CONFIDENCE_THRESHOLD = 0.6;

    public boolean isActionable() {
        return confidence >= CONFIDENCE_THRESHOLD
                && intent != null
                && !"unknown".equals(intent);
    }

    public static IntentResult unknown(String rawInput) {
        return new IntentResult("unknown", 0, Map.of(), rawInput);
    }

    // STUDY: "이해 못 함"(분류기가 unknown·저신뢰로 답함)과 "분류 실패"(CLI 가 결과를 못 냄)는 사용자에게 다르게
    //        보여야 한다. 둘 다 unknown 이던 시절(~v0.0.105)에는 4일짜리 장애가 "이해하지 못했어요"로만 보였다.
    //        confidence 0 이라 isActionable() 은 그대로 false — 기존 분기를 깨지 않고 앞에서 따로 잡는다.
    public static final String FAILED = "classifier_error";

    public static IntentResult failed(String rawInput) {
        return new IntentResult(FAILED, 0, Map.of(), rawInput);
    }

    public boolean isFailure() {
        return FAILED.equals(intent);
    }
}
