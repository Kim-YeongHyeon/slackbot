package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JiraStatusCategoryResolverTest {

    private final JiraStatusCategoryResolver resolver = new JiraStatusCategoryResolver();

    @Test
    void koreanStatusNamesMapToCategory() {
        assertThat(resolver.categoryOf("해야 할 일")).isEqualTo("new");
        assertThat(resolver.categoryOf("진행 중")).isEqualTo("indeterminate");
        assertThat(resolver.categoryOf("완료")).isEqualTo("done");
        assertThat(resolver.categoryOf("종료")).isEqualTo("done");
    }

    @Test
    void englishStatusNamesMapToCategory() {
        assertThat(resolver.categoryOf("To Do")).isEqualTo("new");
        assertThat(resolver.categoryOf("In Progress")).isEqualTo("indeterminate");
        assertThat(resolver.categoryOf("Done")).isEqualTo("done");
        assertThat(resolver.categoryOf("Closed")).isEqualTo("done");
        assertThat(resolver.categoryOf("Resolved")).isEqualTo("done");
    }

    @Test
    void caseInsensitive() {
        assertThat(resolver.categoryOf("DONE")).isEqualTo("done");
        assertThat(resolver.categoryOf("in progress")).isEqualTo("indeterminate");
    }

    @Test
    void unknownStatusFallsBackToInProgress() {
        // STUDY: 매핑 누락은 indeterminate (진행 중) 로 안전하게 처리한다.
        assertThat(resolver.categoryOf("자체 정의 워크플로 상태")).isEqualTo("indeterminate");
    }

    @Test
    void nullOrBlankReturnsInProgress() {
        assertThat(resolver.categoryOf(null)).isEqualTo("indeterminate");
        assertThat(resolver.categoryOf("  ")).isEqualTo("indeterminate");
    }

    @Test
    void isDoneShortcut() {
        assertThat(resolver.isDone("완료")).isTrue();
        assertThat(resolver.isDone("Done")).isTrue();
        assertThat(resolver.isDone("진행 중")).isFalse();
        assertThat(resolver.isDone(null)).isFalse();
    }
}
