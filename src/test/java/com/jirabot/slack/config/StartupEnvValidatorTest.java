package com.jirabot.slack.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    // --- 프롬프트 파일 점검 (v0.0.105) — 작업 폴더가 앱 폴더가 아니면 기동 시점에 실패 ---

    private static Path appDir(Path root) throws IOException {
        Files.createDirectories(root.resolve("prompts"));
        Files.writeString(root.resolve("prompts/haiku-classifier.md"), "x");
        return root;
    }

    @Test
    void promptsDirPresent_passes(@TempDir Path root) throws IOException {
        MockEnvironment env = new MockEnvironment()
                .withProperty("claude.intent.prompt-file", "prompts/haiku-classifier.md");

        assertThatCode(() -> new StartupEnvValidator().validatePromptFiles(env, appDir(root)))
                .doesNotThrowAnyException();
    }

    @Test
    void absoluteOverride_resolvedAsIs(@TempDir Path root, @TempDir Path elsewhere) throws IOException {
        Path custom = Files.writeString(elsewhere.resolve("custom.md"), "x");
        MockEnvironment env = new MockEnvironment()
                .withProperty("ci-alert.fix.prompt-file", custom.toString());

        assertThatCode(() -> new StartupEnvValidator().validatePromptFiles(env, appDir(root)))
                .doesNotThrowAnyException();
    }

    @Test
    void blankOverride_skipped(@TempDir Path root) throws IOException {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ci-alert.code-fix.prompt-file", " ");

        assertThatCode(() -> new StartupEnvValidator().validatePromptFiles(env, appDir(root)))
                .doesNotThrowAnyException();
    }

    @Test
    void wrongWorkingDir_failsWithWorkDirInMessage(@TempDir Path home) {
        // cron 이 홈 폴더에서 봇을 띄운 경우(2026-11-19) — prompts/ 가 없다
        MockEnvironment env = new MockEnvironment()
                .withProperty("claude.intent.prompt-file", "prompts/haiku-classifier.md");

        assertThatThrownBy(() -> new StartupEnvValidator().validatePromptFiles(env, home))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(home.toString())
                .hasMessageContaining("prompts/,")
                .hasMessageContaining("prompts/haiku-classifier.md (claude.intent.prompt-file)");
    }

    @Test
    void overriddenFileMissing_failsWithKey(@TempDir Path root) throws IOException {
        MockEnvironment env = new MockEnvironment()
                .withProperty("claude.intent.prompt-file", "prompts/haiku-classifier.md")
                .withProperty("ci-alert.code-fix.go-check-prompt-file", "prompts/renamed.md");

        assertThatThrownBy(() -> new StartupEnvValidator().validatePromptFiles(env, appDir(root)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompts/renamed.md (ci-alert.code-fix.go-check-prompt-file)")
                .hasMessageNotContaining("haiku-classifier");
    }

    @Test
    void repoLayout_passes() {
        // Gradle 테스트의 작업 폴더는 프로젝트 루트 — 실제 application.yml 값이 가리키는 파일이 레포에 있어야 한다
        MockEnvironment env = new MockEnvironment()
                .withProperty("claude.intent.prompt-file", "prompts/haiku-classifier.md")
                .withProperty("ci-alert.fix.prompt-file", "prompts/sbom-fix-agent.md")
                .withProperty("ci-alert.code-fix.prompt-file", "prompts/ci-fix-agent.md")
                .withProperty("ci-alert.code-fix.go-check-prompt-file", "prompts/go-check-fix-agent.md");

        assertThatCode(() -> new StartupEnvValidator().validatePromptFiles(env, Path.of("").toAbsolutePath()))
                .doesNotThrowAnyException();
    }
}
