package com.jirabot.slack.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotionApiClientImplTest {

    @Test
    void titleMatchesKey_exactFirstToken() {
        assertThat(NotionApiClientImpl.titleMatchesKey("ES2-1 로그인 에러", "ES2-1")).isTrue();
        assertThat(NotionApiClientImpl.titleMatchesKey("ES2-1012 결제 버그", "ES2-1012")).isTrue();
    }

    @Test
    void titleMatchesKey_rejectsSubstringCollision() {
        // 핵심: contains 라면 "ES2-1" 이 "ES2-1012" 를 잡지만, 첫 토큰 정확 일치는 거부해야 한다.
        assertThat(NotionApiClientImpl.titleMatchesKey("ES2-1012 결제 버그", "ES2-1")).isFalse();
        assertThat(NotionApiClientImpl.titleMatchesKey("ES2-676 어떤 버그", "ES2-67")).isFalse();
    }

    @Test
    void titleMatchesKey_nullsAndBlank() {
        assertThat(NotionApiClientImpl.titleMatchesKey(null, "ES2-1")).isFalse();
        assertThat(NotionApiClientImpl.titleMatchesKey("", "ES2-1")).isFalse();
        assertThat(NotionApiClientImpl.titleMatchesKey("ES2-1", null)).isFalse();
    }

    @Test
    void titleMatchesKey_keyOnlyTitle() {
        assertThat(NotionApiClientImpl.titleMatchesKey("ES2-1", "ES2-1")).isTrue();
    }
}
