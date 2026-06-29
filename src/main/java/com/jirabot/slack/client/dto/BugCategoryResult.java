package com.jirabot.slack.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// STUDY: 버그 원인 카테고리 자동 분류 결과. primary=주 소분류 코드(A1 등), secondaries=보조 코드.
//        분류 실패 시 empty()(primary=null) → 적재 시 카테고리 속성은 건너뛴다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record BugCategoryResult(String primary, List<String> secondaries) {

    public static BugCategoryResult empty() {
        return new BugCategoryResult(null, List.of());
    }

    public boolean isPresent() {
        return primary != null && !primary.isBlank();
    }

    public List<String> secondariesOrEmpty() {
        return secondaries == null ? List.of() : secondaries;
    }
}
