package com.jirabot.slack.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BranchNameBuilderTest {

    @Test
    void bugType_usesBugfixPrefix_korean() {
        assertThat(BranchNameBuilder.build("버그", "ES2-1948", "로그인 500 에러"))
                .isEqualTo("bugfix/ES2-1948-로그인-500-에러");
    }

    @Test
    void bugType_usesBugfixPrefix_english() {
        assertThat(BranchNameBuilder.build("Bug", "ES2-10", "NPE on save"))
                .isEqualTo("bugfix/ES2-10-npe-on-save");
    }

    @Test
    void nonBugType_usesFeaturePrefix() {
        assertThat(BranchNameBuilder.build("작업", "ES2-20", "다크모드 추가"))
                .isEqualTo("feature/ES2-20-다크모드-추가");
        assertThat(BranchNameBuilder.build("Story", "ES2-21", "Add dark mode"))
                .isEqualTo("feature/ES2-21-add-dark-mode");
    }

    @Test
    void slugify_collapsesAndTrimsSpecialChars() {
        // 특수문자 연속 → 단일 하이픈, 양끝 트림
        assertThat(BranchNameBuilder.build("Task", "ES2-30", "  Fix:  /auth/reset  (500!!)  "))
                .isEqualTo("feature/ES2-30-fix-auth-reset-500");
    }

    @Test
    void nullSummary_returnsPrefixPlusKeyOnly() {
        assertThat(BranchNameBuilder.build("Task", "ES2-40", null))
                .isEqualTo("feature/ES2-40");
    }

    @Test
    void blankAfterSlug_returnsPrefixPlusKeyOnly() {
        // 슬러그가 특수문자뿐이라 비면 키만 남는다.
        assertThat(BranchNameBuilder.build("Task", "ES2-41", "!!!"))
                .isEqualTo("feature/ES2-41");
    }

    @Test
    void nullIssueType_defaultsToFeature() {
        assertThat(BranchNameBuilder.build(null, "ES2-50", "anything"))
                .isEqualTo("feature/ES2-50-anything");
    }

    @Test
    void longSummary_isTruncatedTo50Chars() {
        String longSummary = "a".repeat(80);
        String result = BranchNameBuilder.build("Task", "ES2-60", longSummary);
        // "feature/ES2-60-" + 50 a's
        assertThat(result).isEqualTo("feature/ES2-60-" + "a".repeat(50));
    }
}
