package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.ClaudeProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CiFailureAnalyzerTest {

    private ProcessRunner runner;
    private CiFailureAnalyzer analyzer;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        runner = mock(ProcessRunner.class);
        CiAlertProperties props = new CiAlertProperties(true, "C1", List.of("B1"), "CryptoLabInc",
                List.of("evi"), "tok", null, 24, 7, 60, List.of(), "claude-sonnet-5");
        ClaudeProperties claude = new ClaudeProperties("claude", "claude-sonnet-4-6", "claude-haiku-4-5",
                5, "plan", 1, true);
        analyzer = new CiFailureAnalyzer(runner, claude, props, om);
    }

    private String envelope(String inner, boolean isError) throws Exception {
        return om.writeValueAsString(java.util.Map.of("type", "result", "is_error", isError, "result", inner));
    }

    private static CiFailureAnalyzer.Input input(List<CiFailureAnalyzer.FailedJob> jobs) {
        return new CiFailureAnalyzer.Input("evi", "evi main", "https://run/1", "main", "abcdef123456",
                "Fix build\n\nlong body", "dev", List.of("src/a.cpp"), "직전 실행은 성공", jobs, "ALERT TEXT");
    }

    private static final String VALID = """
            ```json
            {"category":"INFRA","confidence":"high","summary":"DNS 실패","details":"d",
             "evidence":["e1","e2","e3","e4"],"next_steps":["n1"]}
            ```""";

    @Test
    @SuppressWarnings("unchecked")
    void validOutput_isParsed_andCommandIsToolless() throws Exception {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(VALID, false), "", false));

        Optional<CiAnalysis> a = analyzer.analyze(input(List.of(
                new CiFailureAnalyzer.FailedJob("test (gpu)", List.of("Build"), "LOG EXCERPT"))));

        assertThat(a).isPresent();
        assertThat(a.get().category()).isEqualTo("INFRA");
        assertThat(a.get().evidence()).hasSize(3);   // 최대 3개로 자름
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> stdin = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(runner).run(cmd.capture(), stdin.capture(), timeout.capture());
        assertThat(cmd.getValue()).containsSequence("--tools", "")
                .containsSequence("--max-turns", "1")
                .containsSequence("--system-prompt-file", CiFailureAnalyzer.PROMPT_FILE)
                .containsSequence("--model", "claude-sonnet-5")
                .containsSequence("--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}");
        assertThat(stdin.getValue()).startsWith("아래 <data> 안은")
                .contains("<data>\n", "레포: evi", "커밋: abcdef12 (dev)", "커밋 메시지 첫 줄: Fix build\n",
                        "- src/a.cpp", "=== 실패 job: test (gpu) ===", "실패 step: Build", "LOG EXCERPT")
                .endsWith("</data>\n")
                .doesNotContain("ALERT TEXT");
        assertThat(timeout.getValue()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void invalidThenValid_retriesOnce() throws Exception {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope("{\"category\":\"WEIRD\",\"summary\":\"x\"}", false), "", false))
                .thenReturn(new ProcessRunner.Result(0, envelope(VALID, false), "", false));

        assertThat(analyzer.analyze(input(List.of()))).isPresent();
        verify(runner, times(2)).run(any(), anyString(), any(Duration.class));
    }

    @Test
    void errorEnvelopeOrBadExit_givesUpAfterTwoAttempts() throws Exception {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(VALID, true), "", false))
                .thenReturn(new ProcessRunner.Result(1, "", "boom", false));

        assertThat(analyzer.analyze(input(List.of()))).isEmpty();
        verify(runner, times(2)).run(any(), anyString(), any(Duration.class));
    }

    @Test
    void timeout_doesNotRetry() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(-1, "", "", true));

        assertThat(analyzer.analyze(input(List.of()))).isEmpty();
        verify(runner, times(1)).run(any(), anyString(), any(Duration.class));
    }

    @Test
    void missingSummary_isInvalid() throws Exception {
        assertThat(analyzer.parse(envelope("{\"category\":\"CODE\",\"summary\":\" \"}", false))).isEmpty();
        assertThat(analyzer.parse("not json")).isEmpty();
    }

    @Test
    void noFailedJobs_includesAlertTextInsteadOfLogs() {
        String stdin = CiFailureAnalyzer.buildInput(input(List.of()));

        assertThat(stdin).contains("(실패 job 로그를 받지 못함 — 아래는 알림 원문)\nALERT TEXT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankAlertModel_fallsBackToTheBotModel() throws Exception {
        CiAlertProperties noModel = new CiAlertProperties(true, "C1", List.of("B1"), "CryptoLabInc",
                List.of("evi"), "tok", null, 24, 7, 60, List.of(), " ");
        CiFailureAnalyzer fallback = new CiFailureAnalyzer(runner,
                new ClaudeProperties("claude", "claude-sonnet-4-6", "claude-haiku-4-5", 5, "plan", 1, true), noModel, om);
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(VALID, false), "", false));

        fallback.analyze(input(List.of()));

        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), anyString(), any(Duration.class));
        assertThat(cmd.getValue()).containsSequence("--model", "claude-sonnet-4-6");
    }

    @Test
    void failureDetail_readsTheJsonEnvelopeWhenStderrIsEmpty() {
        assertThat(analyzer.failureDetail(new ProcessRunner.Result(1,
                "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"result\":\"Reached\\nmax turns\"}",
                "", false))).isEqualTo("subtype=error_max_turns result=Reached max turns");
        assertThat(analyzer.failureDetail(new ProcessRunner.Result(1, "", "auth failed\nmore", false))).isEqualTo("auth failed");
        assertThat(analyzer.failureDetail(new ProcessRunner.Result(1, "not json", null, false))).isEqualTo("stdout=not json");
    }
}
