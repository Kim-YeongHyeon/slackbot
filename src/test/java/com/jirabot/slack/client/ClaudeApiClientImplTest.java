package com.jirabot.slack.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.IssueSearchEntry;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.ClaudeProperties;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClaudeApiClientImplTest {

    private ProcessRunner runner;
    private ClaudeApiClientImpl client;

    @BeforeEach
    void setUp() {
        runner = mock(ProcessRunner.class);
        ClaudeProperties props = new ClaudeProperties("claude", "claude-sonnet-4-6", "claude-haiku-4-5", 5, "plan", 1);
        client = new ClaudeApiClientImpl(runner, props, new ObjectMapper());
    }

    private static String envelope(String inner, boolean isError) {
        // inner 를 escape 해서 { "result": "..." } 에 그대로 박아넣는다.
        String escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"type":"result","subtype":"success","is_error":%s,"result":"%s"}
                """.formatted(Boolean.toString(isError), escaped);
    }

    @Test
    void parsesJsonResultIntoClassification() {
        String inner = "{\"type\":\"BUG\",\"storyPoint\":5,\"title\":\"T\",\"summary\":\"S\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false));

        IssueClassification result = client.classify("결제 금액 0원 표시");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.BUG);
        assertThat(result.storyPoint()).isEqualTo(5);
        assertThat(result.title()).isEqualTo("T");
        assertThat(result.summary()).isEqualTo("S");
    }

    @Test
    void parsesJsonResultWrappedInCodeFences() {
        String inner = "```json\n{\"type\":\"FEATURE\",\"storyPoint\":3,\"title\":\"TT\",\"summary\":\"SS\"}\n```";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false));

        IssueClassification result = client.classify("다크모드 토글 추가");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.FEATURE);
        assertThat(result.storyPoint()).isEqualTo(3);
        assertThat(result.title()).isEqualTo("TT");
    }

    @Test
    void nonZeroExitCode_returnsFallback() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(1, "", "command not found", false));

        IssueClassification result = client.classify("hello");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.OTHER);
        assertThat(result.storyPoint()).isEqualTo(3);
        assertThat(result.title()).contains("hello");
    }

    @Test
    void timeout_returnsFallback() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(-1, "", "", true));

        IssueClassification result = client.classify("오래 걸리는 입력");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.OTHER);
        assertThat(result.storyPoint()).isEqualTo(3);
    }

    @Test
    void emptyStdout_returnsFallback() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, "", "", false));

        IssueClassification result = client.classify("something");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.OTHER);
    }

    @Test
    void envelopeIsError_returnsFallback() {
        String inner = "{\"type\":\"BUG\",\"storyPoint\":2,\"title\":\"x\",\"summary\":\"y\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, true), "", false));

        IssueClassification result = client.classify("boom");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.OTHER);
    }

    @Test
    void malformedInnerJson_returnsFallback() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope("not json at all", false), "", false));

        IssueClassification result = client.classify("weird");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.OTHER);
        assertThat(result.title()).contains("weird");
    }

    @Test
    void blankInput_shortCircuitsWithoutInvokingRunner() {
        IssueClassification result = client.classify("");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.OTHER);
        verify(runner, never()).run(any(List.class), anyString(), any(Duration.class));
    }

    // --- searchIssues tests ---

    private static String searchEnvelope(String inner, boolean isError) {
        String escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"type":"result","subtype":"success","is_error":%s,"result":"%s"}
                """.formatted(Boolean.toString(isError), escaped);
    }

    private List<IssueSearchEntry> sampleIssues() {
        return List.of(
                new IssueSearchEntry("SLAC-7", "로그인 500 에러", "로그인 페이지에서 500 에러", "진행 중", "Alice"),
                new IssueSearchEntry("SLAC-15", "결제 금액 표시", "결제 완료 후 금액 문제", "완료", "Bob")
        );
    }

    @Test
    void searchIssues_parsesJsonArray() {
        String inner = "[\"SLAC-7\", \"SLAC-15\"]";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, searchEnvelope(inner, false), "", false));

        List<String> result = client.searchIssues("로그인 에러", sampleIssues());

        assertThat(result).containsExactly("SLAC-7", "SLAC-15");
    }

    @Test
    void searchIssues_emptyArray_returnsEmpty() {
        String inner = "[]";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, searchEnvelope(inner, false), "", false));

        List<String> result = client.searchIssues("존재하지 않는 이슈", sampleIssues());

        assertThat(result).isEmpty();
    }

    @Test
    void searchIssues_timeout_returnsEmpty() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(-1, "", "", true));

        List<String> result = client.searchIssues("query", sampleIssues());

        assertThat(result).isEmpty();
    }

    @Test
    void searchIssues_nonZeroExit_returnsEmpty() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(1, "", "error", false));

        List<String> result = client.searchIssues("query", sampleIssues());

        assertThat(result).isEmpty();
    }

    @Test
    void searchIssues_isError_returnsEmpty() {
        String inner = "[\"SLAC-7\"]";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, searchEnvelope(inner, true), "", false));

        List<String> result = client.searchIssues("query", sampleIssues());

        assertThat(result).isEmpty();
    }

    @Test
    void searchIssues_codeFencedArray_parsesCorrectly() {
        String inner = "```json\\n[\"SLAC-7\"]\\n```";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, searchEnvelope(inner, false), "", false));

        List<String> result = client.searchIssues("query", sampleIssues());

        assertThat(result).containsExactly("SLAC-7");
    }

    @Test
    void searchIssues_blankQuery_shortCircuits() {
        List<String> result = client.searchIssues("", sampleIssues());

        assertThat(result).isEmpty();
        verify(runner, never()).run(any(List.class), anyString(), any(Duration.class));
    }

    @Test
    void searchIssues_emptyIssueList_shortCircuits() {
        List<String> result = client.searchIssues("query", Collections.emptyList());

        assertThat(result).isEmpty();
        verify(runner, never()).run(any(List.class), anyString(), any(Duration.class));
    }

    @Test
    void buildSearchStdin_formatsCorrectly() {
        String systemPrompt = "test prompt";
        List<IssueSearchEntry> issues = List.of(
                new IssueSearchEntry("SLAC-7", "로그인 에러", "상세 설명", "진행 중", "Alice"),
                new IssueSearchEntry("SLAC-8", "UI 개선", null, "할 일", null)
        );

        String stdin = client.buildSearchStdin(systemPrompt, "로그인 문제", issues);

        assertThat(stdin).contains("[사용자 질문]");
        assertThat(stdin).contains("로그인 문제");
        assertThat(stdin).contains("[이슈 목록]");
        assertThat(stdin).contains("SLAC-7 | 로그인 에러 | 진행 중 | 담당: Alice");
        assertThat(stdin).contains("설명: 상세 설명");
        assertThat(stdin).contains("SLAC-8 | UI 개선 | 할 일 | 담당: 미배정");
        // No description line for null description
        assertThat(stdin).doesNotContain("설명: null");
    }

    @Test
    void englishBranchSlug_returnsModelText() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope("fix-login-500-error", false), "", false));

        assertThat(client.englishBranchSlug("로그인 페이지에서 500 에러 발생"))
                .isEqualTo("fix-login-500-error");
    }

    @Test
    void englishBranchSlug_stripsQuotesAndExtraLines() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope("\"add-dark-mode\"\n(extra prose)", false), "", false));

        assertThat(client.englishBranchSlug("다크모드 추가")).isEqualTo("add-dark-mode");
    }

    @Test
    void englishBranchSlug_timeoutReturnsEmpty() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(-1, "", "", true));

        assertThat(client.englishBranchSlug("아무 요약")).isEmpty();
    }

    @Test
    void englishBranchSlug_blankSummary_skipsCliCall() {
        assertThat(client.englishBranchSlug("  ")).isEmpty();
        verify(runner, never()).run(any(), anyString(), any(Duration.class));
    }

    // --- 프롬프트 skill 파일 외부화 (--system-prompt-file) ---
    // STUDY: Gradle test 워커의 working dir = 프로젝트 루트 → 리포의 prompts/*.md 가 실재해
    //        promptFileExists 분기가 실제 운영 경로(파일 사용)와 동일하게 동작한다.

    @SuppressWarnings("unchecked")
    private List<String> capturedCommand() {
        org.mockito.ArgumentCaptor<List<String>> cmd = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), anyString(), any(Duration.class));
        return cmd.getValue();
    }

    @Test
    void classify_usesSystemPromptFile_andStdinHasOnlyUserContent() {
        String inner = "{\"type\":\"BUG\",\"storyPoint\":2,\"title\":\"T\",\"summary\":\"S\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false));

        client.classify("로그인 500 에러");

        org.mockito.ArgumentCaptor<String> stdin = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<List<String>> cmd = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), stdin.capture(), any(Duration.class));
        assertThat(cmd.getValue()).containsSequence("--system-prompt-file", ClaudeApiClientImpl.CLASSIFIER_PROMPT_FILE);
        // 시스템 프롬프트는 파일로 빠지고 stdin 은 사용자 입력만 담는다.
        assertThat(stdin.getValue()).doesNotContain("Jira triage assistant");
        assertThat(stdin.getValue()).contains("USER INPUT:").contains("로그인 500 에러");
    }

    @Test
    void englishBranchSlug_usesFastModelAndSkillFile() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope("fix-login-error", false), "", false));

        client.englishBranchSlug("로그인 에러 수정");

        List<String> cmd = capturedCommand();
        // 단순 변환은 Sonnet 이 아닌 fastModel(Haiku) 로.
        assertThat(cmd).contains("claude-haiku-4-5").doesNotContain("claude-sonnet-4-6");
        assertThat(cmd).containsSequence("--system-prompt-file", ClaudeApiClientImpl.BRANCH_SLUG_PROMPT_FILE);
    }

    @Test
    void searchIssues_usesSkillFile_andStdinSkipsSystemPrompt() {
        String inner = "[\"SLAC-7\"]";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, searchEnvelope(inner, false), "", false));

        client.searchIssues("로그인", sampleIssues());

        org.mockito.ArgumentCaptor<String> stdin = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<List<String>> cmd = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), stdin.capture(), any(Duration.class));
        assertThat(cmd.getValue()).containsSequence("--system-prompt-file", ClaudeApiClientImpl.SEARCH_PROMPT_FILE);
        assertThat(stdin.getValue()).startsWith("[사용자 질문]");
    }

    @Test
    void buildSearchStdin_nullSystemPrompt_omitsPrefix() {
        String stdin = client.buildSearchStdin(null, "질문", sampleIssues());

        assertThat(stdin).startsWith("[사용자 질문]");
        assertThat(stdin).contains("[이슈 목록]");
    }
}
