package com.jirabot.slack.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RagPropertiesTest {

    @Test
    void defaults_fillMissingValues() {
        RagProperties p = new RagProperties(null, " ", 0, 0, -1, null);

        assertThat(p.enabled()).isTrue();
        assertThat(p.baseUrl()).isEqualTo("http://127.0.0.1:8090");
        assertThat(p.topK()).isEqualTo(6);
        assertThat(p.searchTimeoutSeconds()).isEqualTo(20);
        assertThat(p.answerTimeoutSeconds()).isEqualTo(90);
        assertThat(p.modelOr("claude-sonnet-4-6")).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void explicitValues_areKept() {
        RagProperties p = new RagProperties(false, "http://rag:9000", 4, 5, 30, "claude-sonnet-5");

        assertThat(p.enabled()).isFalse();
        assertThat(p.baseUrl()).isEqualTo("http://rag:9000");
        assertThat(p.topK()).isEqualTo(4);
        assertThat(p.modelOr("x")).isEqualTo("claude-sonnet-5");
    }
}
