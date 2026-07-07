package com.jirabot.slack.client.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultProcessRunnerTest {

    private static DefaultProcessRunner runner(boolean disableThinking) {
        return new DefaultProcessRunner(new com.jirabot.slack.config.ClaudeProperties(
                null, null, null, 0, null, 0, disableThinking));
    }

    // thinking 차단 on (프로덕션 기본과 동일).
    private final DefaultProcessRunner runner = runner(true);

    @Test
    void echoCommand_returnsStdoutAndZeroExit() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/echo", "hello-runner"), null, Duration.ofSeconds(5));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello-runner");
        assertThat(result.success()).isTrue();
    }

    @Test
    void nonZeroExit_isReported() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "exit 3"), null, Duration.ofSeconds(5));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.success()).isFalse();
    }

    @Test
    void longRunningCommand_timesOutAndIsKilled() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "sleep 5"), null, Duration.ofMillis(300));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void disableThinking_injectsMaxThinkingTokensEnv() {
        // thinking 차단 on → 서브프로세스 환경에 MAX_THINKING_TOKENS=0 이 주입된다.
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "echo ${MAX_THINKING_TOKENS:-unset}"), null, Duration.ofSeconds(5));

        assertThat(result.stdout().strip()).isEqualTo("0");
    }

    @Test
    void thinkingEnabled_doesNotInjectEnv() {
        // 복원 경로: disable-thinking=false → env 미주입(모델 기본 thinking 동작).
        DefaultProcessRunner thinkingOn = runner(false);
        ProcessRunner.Result result = thinkingOn.run(
                List.of("/bin/sh", "-c", "echo ${MAX_THINKING_TOKENS:-unset}"), null, Duration.ofSeconds(5));

        assertThat(result.stdout().strip()).isEqualTo("unset");
    }
}
