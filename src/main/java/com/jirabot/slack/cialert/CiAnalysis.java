package com.jirabot.slack.cialert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;

// Claude 가 돌려주는 CI 실패 분석 (prompts/ci-failure-analysis.md 의 출력 형식).
@JsonIgnoreProperties(ignoreUnknown = true)
public record CiAnalysis(
        String category,
        String confidence,
        String summary,
        String details,
        List<String> evidence,
        @JsonProperty("next_steps") List<String> nextSteps
) {
    static final Set<String> CATEGORIES =
            Set.of("CODE", "FLAKY", "INFRA", "CONFIG", "DEPENDENCY", "VULNERABILITY", "UNKNOWN");

    public CiAnalysis {
        evidence = evidence == null ? List.of() : evidence.stream().limit(3).toList();
        nextSteps = nextSteps == null ? List.of() : nextSteps.stream().limit(3).toList();
    }

    // 필수 필드가 빠진 응답은 재시도 대상 (ClaudeApiClientImpl 과 같은 기준).
    public boolean valid() {
        return category != null && CATEGORIES.contains(category) && summary != null && !summary.isBlank();
    }
}
