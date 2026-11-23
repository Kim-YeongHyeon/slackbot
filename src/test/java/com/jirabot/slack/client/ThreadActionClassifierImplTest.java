package com.jirabot.slack.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.ThreadActionResult;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.IntentProperties;
import com.jirabot.slack.entity.IssueEntity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// v0.0.106: 결과를 못 낸 경우(failed)와 "이해 못 함"(unknown)을 구분하는지 본다.
class ThreadActionClassifierImplTest {

    private ProcessRunner runner;
    private ThreadActionClassifierImpl classifier;
    private final IssueEntity parent = mock(IssueEntity.class);

    @BeforeEach
    void setUp() {
        runner = mock(ProcessRunner.class);
        classifier = new ThreadActionClassifierImpl(
                runner, new IntentProperties("claude-haiku-4-5", 40, "prompts/haiku-classifier.md"),
                new ObjectMapper());
    }

    private void cliReturns(int exitCode, String stdout, boolean timedOut) {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(exitCode, stdout, "", timedOut));
    }

    @Test
    void validAnswer_returnsAction() {
        cliReturns(0, """
                {"is_error":false,"result":"{\\"action\\":\\"comment\\",\\"confidence\\":0.9,\\"extracted\\":{\\"content\\":\\"확인함\\"}}"}
                """, false);

        ThreadActionResult r = classifier.classify(parent, List.of(), "확인했다고 댓글 달아줘");

        assertThat(r.action()).isEqualTo("comment");
        assertThat(r.isActionable()).isTrue();
        assertThat(r.isFailure()).isFalse();
    }

    @Test
    void cliExitNonZero_isFailure() {
        cliReturns(1, "", false);

        ThreadActionResult r = classifier.classify(parent, List.of(), "이거 내일까지로");

        assertThat(r.isFailure()).isTrue();
        assertThat(r.isActionable()).isFalse();
    }

    @Test
    void timeout_isFailure() {
        cliReturns(-1, "", true);

        assertThat(classifier.classify(parent, List.of(), "이거 내일까지로").isFailure()).isTrue();
    }

    @Test
    void unparsableAnswer_isFailure() {
        cliReturns(0, "not json", false);

        assertThat(classifier.classify(parent, List.of(), "이거 내일까지로").isFailure()).isTrue();
    }

    @Test
    void modelSaysUnknown_isNotFailure() {
        cliReturns(0, """
                {"is_error":false,"result":"{\\"action\\":\\"unknown\\",\\"confidence\\":0.2}"}
                """, false);

        ThreadActionResult r = classifier.classify(parent, List.of(), "음");

        assertThat(r.isFailure()).isFalse();
        assertThat(r.isActionable()).isFalse();
    }

    @Test
    void blankInput_isUnknown_withoutCallingCli() {
        ThreadActionResult r = classifier.classify(parent, List.of(), "  ");

        assertThat(r.action()).isEqualTo("unknown");
        assertThat(r.isFailure()).isFalse();
        verify(runner, never()).run(any(), anyString(), any(Duration.class));
    }
}
