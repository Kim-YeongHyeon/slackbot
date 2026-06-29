package com.jirabot.slack.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.IntentProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntentClassifierImplTest {

    private ProcessRunner runner;
    private IntentClassifierImpl classifier;

    @BeforeEach
    void setUp() {
        runner = mock(ProcessRunner.class);
        classifier = new IntentClassifierImpl(
                runner, new IntentProperties("claude-haiku-4-5", 40, "prompts/haiku-classifier.md"),
                new ObjectMapper());
    }

    private static String envelope(String inner, boolean isError) {
        String escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"type":"result","is_error":%s,"result":"%s"}
                """.formatted(Boolean.toString(isError), escaped);
    }

    @Test
    void parsesIntentResult() {
        String inner = "{\"intent\":\"my_tasks\",\"confidence\":0.96,\"extracted\":{},\"raw_input\":\"내가 안한 일?\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false));

        IntentResult r = classifier.classify("내가 안한 일?");

        assertThat(r.intent()).isEqualTo("my_tasks");
        assertThat(r.isActionable()).isTrue();
    }

    @Test
    void transientFailure_thenSuccess_retriesOnce() {
        String inner = "{\"intent\":\"my_tasks\",\"confidence\":0.96,\"extracted\":{},\"raw_input\":\"x\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(1, "", "", false))            // 1차 실패
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false)); // 2차 성공

        IntentResult r = classifier.classify("내가 아직 안한 일이 뭐가 있지?");

        assertThat(r.intent()).isEqualTo("my_tasks");   // 재시도로 복구 (이전엔 unknown)
        verify(runner, times(2)).run(any(List.class), anyString(), any(Duration.class));
    }

    @Test
    void timeout_thenSuccess_retriesWithShorterTimeout() {
        String inner = "{\"intent\":\"my_tasks\",\"confidence\":0.95,\"extracted\":{},\"raw_input\":\"x\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(-1, "", "", true))             // 1차 타임아웃
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false)); // 2차 성공

        IntentResult r = classifier.classify("보고자가 나인데 완료 안된 task?");

        assertThat(r.intent()).isEqualTo("my_tasks");   // 타임아웃도 재시도로 복구
        org.mockito.ArgumentCaptor<Duration> cap = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(runner, times(2)).run(any(List.class), anyString(), cap.capture());
        // 1차=40s(기본), 2차=15s(짧은 재시도 타임아웃)
        assertThat(cap.getAllValues().get(0)).isEqualTo(Duration.ofSeconds(40));
        assertThat(cap.getAllValues().get(1)).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void allTimeouts_returnsUnknown_afterMaxAttempts() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(-1, "", "", true));

        IntentResult r = classifier.classify("오래 걸리는 입력");

        assertThat(r.intent()).isEqualTo("unknown");
        verify(runner, times(IntentClassifierImpl.MAX_ATTEMPTS))
                .run(any(List.class), anyString(), any(Duration.class));
    }

    @Test
    void allAttemptsFail_returnsUnknown_afterMaxAttempts() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(1, "", "", false));

        IntentResult r = classifier.classify("뭔가");

        assertThat(r.intent()).isEqualTo("unknown");
        verify(runner, times(IntentClassifierImpl.MAX_ATTEMPTS))
                .run(any(List.class), anyString(), any(Duration.class));
    }

    @Test
    void blankInput_skipsCliCall() {
        IntentResult r = classifier.classify("  ");
        assertThat(r.intent()).isEqualTo("unknown");
        verify(runner, org.mockito.Mockito.never())
                .run(any(List.class), anyString(), any(Duration.class));
    }
}
