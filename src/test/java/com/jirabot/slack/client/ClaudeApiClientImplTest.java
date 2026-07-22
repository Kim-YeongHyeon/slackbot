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
        ClaudeProperties props = new ClaudeProperties("claude", "claude-sonnet-4-6", "claude-haiku-4-5", 5, "plan", 1, true);
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
    void transientFailure_thenSuccess_retriesOnce() {
        String inner = "{\"type\":\"BUG\",\"storyPoint\":2,\"title\":\"T\",\"summary\":\"S\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(1, "", "", false))          // 1차: exit 1
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false)); // 2차: 성공

        IssueClassification result = client.classify("로그인 500 에러");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.BUG);   // 재시도로 정상 분류
        verify(runner, org.mockito.Mockito.times(2))
                .run(any(List.class), anyString(), any(Duration.class));
    }

    @Test
    void timeout_isRetried_upToMaxAttempts() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(-1, "", "", true));   // 매번 타임아웃

        client.classify("오래 걸리는 입력");

        // 신뢰성 우선: 타임아웃도 재시도 → 최대 MAX_ATTEMPTS 회 호출.
        verify(runner, org.mockito.Mockito.times(ClaudeApiClientImpl.MAX_ATTEMPTS))
                .run(any(List.class), anyString(), any(Duration.class));
    }

    @Test
    void twoFailures_thenSuccess_recoversWithinMaxAttempts() {
        String inner = "{\"type\":\"BUG\",\"storyPoint\":2,\"title\":\"T\",\"summary\":\"S\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(1, "", "", false))            // 1차 실패
                .thenReturn(new ProcessRunner.Result(-1, "", "", true))            // 2차 타임아웃
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false)); // 3차 성공

        IssueClassification result = client.classify("로그인 500 에러");

        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.BUG);
        verify(runner, org.mockito.Mockito.times(3))
                .run(any(List.class), anyString(), any(Duration.class));
    }

    @Test
    void allAttemptsFail_withRegisterBugHint_fallbackTypeIsBug() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(1, "", "", false));   // 매번 실패
        var hint = new com.jirabot.slack.client.dto.IntentResult(
                "register_bug", 0.9, java.util.Map.of(), "CPU 저조 티켓 만들어줘");

        IssueClassification result = client.classify("CPU 저조 티켓 만들어줘", hint);

        // 의도(register_bug) 기반으로 OTHER 대신 BUG, 제목엔 명령어구 없음.
        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.BUG);
        assertThat(result.title()).doesNotContain("만들어줘");
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

    // ==================== 의도별 스킬 선택 (v0.0.60) ====================
    // 주의: 테스트는 repo 루트에서 실행돼 prompts/*.md 가 실재한다 — 파일 존재 전제의 선택 로직 검증.

    private static com.jirabot.slack.client.dto.IntentResult hint(String intent) {
        return new com.jirabot.slack.client.dto.IntentResult(intent, 0.9, java.util.Map.of(), "t");
    }

    @Test
    void classifierPromptFileFor_selectsPerIntentSkill() {
        assertThat(client.classifierPromptFileFor(hint("register_bug")))
                .isEqualTo(ClaudeApiClientImpl.BUG_SKILL_PROMPT_FILE);
        assertThat(client.classifierPromptFileFor(hint("register_story")))
                .isEqualTo(ClaudeApiClientImpl.STORY_SKILL_PROMPT_FILE);
        // 에픽/무힌트 → 공용 classifier
        assertThat(client.classifierPromptFileFor(hint("register_epic")))
                .isEqualTo(ClaudeApiClientImpl.CLASSIFIER_PROMPT_FILE);
        assertThat(client.classifierPromptFileFor(null))
                .isEqualTo(ClaudeApiClientImpl.CLASSIFIER_PROMPT_FILE);
    }

    @Test
    void classify_bugIntent_usesBugSkillFileInCommand() {
        String inner = "{\"type\":\"BUG\",\"storyPoint\":2,\"title\":\"T\",\"summary\":\"S\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false));

        client.classify("로그인 500 에러", hint("register_bug"));

        org.mockito.ArgumentCaptor<List<String>> cmd = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), anyString(), any(Duration.class));
        int i = cmd.getValue().indexOf("--system-prompt-file");
        assertThat(i).isGreaterThanOrEqualTo(0);
        assertThat(cmd.getValue().get(i + 1)).isEqualTo(ClaudeApiClientImpl.BUG_SKILL_PROMPT_FILE);
    }

    @Test
    void classifyPr_usesPrSkillFile_andParses() {
        String inner = "{\"type\":\"BUG\",\"storyPoint\":2,\"title\":\"NPE 수정\",\"summary\":\"요약\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(inner, false), "", false));

        IssueClassification result = client.classifyPr("PR 제목: fix NPE\n\n본문");

        assertThat(result.title()).isEqualTo("NPE 수정");
        org.mockito.ArgumentCaptor<List<String>> cmd = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), anyString(), any(Duration.class));
        int i = cmd.getValue().indexOf("--system-prompt-file");
        assertThat(cmd.getValue().get(i + 1)).isEqualTo(ClaudeApiClientImpl.PR_IMPORT_PROMPT_FILE);
    }

    @Test
    void parseableButMissingFields_isRejectedAndRetried() {
        // JSON 으로는 유효하지만 필수 필드가 빠진 응답 → 통과시키면 "null" 제목 티켓 발생(스킬 eval 실측).
        String bad = "{\"foo\":\"bar\"}";
        String good = "{\"type\":\"BUG\",\"storyPoint\":2,\"title\":\"T\",\"summary\":\"S\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(bad, false), "", false))
                .thenReturn(new ProcessRunner.Result(0, envelope(good, false), "", false));

        IssueClassification result = client.classify("로그인 500 에러");

        assertThat(result.title()).isEqualTo("T");
        verify(runner, org.mockito.Mockito.times(2)).run(any(List.class), anyString(), any(Duration.class));
    }

    @Test
    void invalidStoryPointScale_isRejectedAndRetried() {
        // SP=13 처럼 팀 스케일 밖 값도 거부 → 재시도
        String bad = "{\"type\":\"BUG\",\"storyPoint\":13,\"title\":\"T\",\"summary\":\"S\"}";
        String good = "{\"type\":\"BUG\",\"storyPoint\":8,\"title\":\"T\",\"summary\":\"S\"}";
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(0, envelope(bad, false), "", false))
                .thenReturn(new ProcessRunner.Result(0, envelope(good, false), "", false));

        IssueClassification result = client.classify("큰 작업");

        assertThat(result.storyPoint()).isEqualTo(8);
    }

    @Test
    void classifyPr_allAttemptsFail_fallsBackToTitleFromText() {
        when(runner.run(any(), anyString(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(1, "", "err", false));

        IssueClassification result = client.classifyPr("PR 제목: feat something");

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(IssueClassification.IssueType.OTHER);
        verify(runner, org.mockito.Mockito.times(ClaudeApiClientImpl.MAX_ATTEMPTS))
                .run(any(List.class), anyString(), any(Duration.class));
    }
}
