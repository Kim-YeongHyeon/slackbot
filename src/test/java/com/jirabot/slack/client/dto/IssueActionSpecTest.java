package com.jirabot.slack.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IssueActionSpecTest {

    @Test
    void unknownAction_degradesToNone() {
        var s = new IssueActionSpec("explode", "es2-1", null, null, null,
                null, null, null, null, null, null, 1.5, null);
        assertThat(s.action()).isEqualTo("none");
        assertThat(s.confidence()).isEqualTo(1.0);   // clamp
        assertThat(s.issueKey()).isEqualTo("ES2-1"); // 대문자 정규화
        assertThat(s.directionConfident()).isTrue(); // null → true
        assertThat(s.isActionable()).isFalse();
    }

    @Test
    void lowConfidence_notActionable() {
        var s = new IssueActionSpec("assign", "ES2-1", null, "김", null,
                null, null, null, true, null, null, 0.4, null);
        assertThat(s.isActionable()).isFalse();
    }

    @Test
    void none_factory_isSafe() {
        assertThat(IssueActionSpec.none().isActionable()).isFalse();
    }
}
