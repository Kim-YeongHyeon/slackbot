package com.jirabot.slack.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StartupEnvValidatorTest {

    private MockEnvironment fullEnv() {
        return new MockEnvironment()
                .withProperty("slack.bot-token", "xoxb-1")
                .withProperty("slack.signing-secret", "sec")
                .withProperty("jira.base-url", "https://x.atlassian.net")
                .withProperty("jira.email", "a@b.c")
                .withProperty("jira.api-token", "tok")
                .withProperty("jira.project-key", "ES2")
                // v0.0.73: 대시보드 전면 로그인 — 계정 누락 시 무인증 기동 대신 fail-fast
                .withProperty("dashboard.user", "sol")
                .withProperty("dashboard.password", "pw");
    }

    @Test
    void allRequiredPresent_passes() {
        assertThatCode(() -> new StartupEnvValidator().validate(fullEnv()))
                .doesNotThrowAnyException();
    }

    @Test
    void missingKey_failsFastWithEnvVarNameInMessage() {
        MockEnvironment env = fullEnv();
        env.setProperty("jira.api-token", "");   // 빈 값도 누락으로 취급

        assertThatThrownBy(() -> new StartupEnvValidator().validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JIRA_API_TOKEN");
    }

    @Test
    void multipleMissing_listsAll() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("slack.bot-token", "xoxb-1")
                .withProperty("jira.base-url", "https://x")
                .withProperty("jira.email", "a@b.c")
                .withProperty("jira.api-token", "tok");

        assertThatThrownBy(() -> new StartupEnvValidator().validate(env))
                .hasMessageContaining("SLACK_SIGNING_SECRET")
                .hasMessageContaining("JIRA_PROJECT_KEY")
                .hasMessageContaining("DASHBOARD_USER")
                .hasMessageContaining("DASHBOARD_PASSWORD");
    }
}
