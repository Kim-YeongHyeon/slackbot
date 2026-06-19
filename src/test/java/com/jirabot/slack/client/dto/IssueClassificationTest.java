package com.jirabot.slack.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IssueClassificationTest {

    @Test
    void cleanTitle_stripsLeadingIssueKey() {
        assertThat(IssueClassification.cleanTitle("[ES2-2077] CPU 사용 저조"))
                .isEqualTo("CPU 사용 저조");
        assertThat(IssueClassification.cleanTitle("ES2-100 결제 안됨"))
                .isEqualTo("결제 안됨");
    }

    @Test
    void cleanTitle_stripsTrailingCreateCommand() {
        assertThat(IssueClassification.cleanTitle("로그인 500 에러 이슈 등록해줘"))
                .isEqualTo("로그인 500 에러");
        assertThat(IssueClassification.cleanTitle("다크모드 추가해줘"))
                .isEqualTo("다크모드");
        assertThat(IssueClassification.cleanTitle("결제 안됨 버그 만들어 주세요"))
                .isEqualTo("결제 안됨");
    }

    @Test
    void cleanTitle_stripsBothKeyAndCommand() {
        assertThat(IssueClassification.cleanTitle(
                "[ES2-2077] compute에서 CPU를 최대한 안 쓰는 문제 티켓 만들어줘"))
                .isEqualTo("compute에서 CPU를 최대한 안 쓰는 문제");
    }

    @Test
    void cleanTitle_leavesNormalTitleUntouched() {
        assertThat(IssueClassification.cleanTitle("그냥 평범한 제목"))
                .isEqualTo("그냥 평범한 제목");
    }

    @Test
    void fallback_usesCleanedTitle_notRawCommand() {
        var c = IssueClassification.fallback("[ES2-2077] CPU 저조 이슈생겼는데 티켓 만들어줘");
        assertThat(c.type()).isEqualTo(IssueClassification.IssueType.OTHER);
        assertThat(c.title()).doesNotContain("만들어줘").doesNotContain("[ES2-2077]");
        assertThat(c.title()).contains("CPU");
        // summary 에는 원문 보존(맥락 유지).
        assertThat(c.summary()).contains("티켓 만들어줘");
    }

    @Test
    void fallback_blankInput_hasDefaultTitle() {
        assertThat(IssueClassification.fallback("  ").title()).isEqualTo("Untitled issue from Slack");
        assertThat(IssueClassification.fallback(null).title()).isEqualTo("Untitled issue from Slack");
    }
}
