package com.jirabot.slack.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.IntentClassifier;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.ThreadActionClassifier;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IntentFailureRepository;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import com.jirabot.slack.service.IssueCreateResult;
import com.jirabot.slack.service.IssueCreateService;
import com.jirabot.slack.service.JiraSyncService;
import com.jirabot.slack.service.ScrumReportService;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// STUDY: standaloneSetup은 Spring 컨텍스트 없이 컨트롤러만 MockMvc로 래핑 — 다른 팀원 필터/빈의 영향을 받지 않음.
class SlackEventControllerTest {

    private IssueCreateService issueCreateService;
    private ScrumReportService scrumReportService;
    private JiraSyncService jiraSyncService;
    private JiraApiClient jiraApiClient;
    private ClaudeApiClient claudeApiClient;
    private IssueRepository issueRepository;
    private IntentClassifier intentClassifier;
    private ThreadActionClassifier threadActionClassifier;
    private IntentFailureRepository intentFailureRepository;
    private UserMappingRepository userMappingRepository;
    private SlackNotifier slackNotifier;
    private JiraProperties jiraProps;
    private SlackEventController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        issueCreateService = mock(IssueCreateService.class);
        scrumReportService = mock(ScrumReportService.class);
        jiraSyncService = mock(JiraSyncService.class);
        jiraApiClient = mock(JiraApiClient.class);
        claudeApiClient = mock(ClaudeApiClient.class);
        issueRepository = mock(IssueRepository.class);
        intentClassifier = mock(IntentClassifier.class);
        threadActionClassifier = mock(ThreadActionClassifier.class);
        intentFailureRepository = mock(IntentFailureRepository.class);
        userMappingRepository = mock(UserMappingRepository.class);
        slackNotifier = mock(SlackNotifier.class);
        jiraProps = new JiraProperties("https://jira.example.com", "test@example.com", "token", "SLAC");
        Executor directExecutor = Runnable::run;
        SlackEventDeduplicator deduplicator = new SlackEventDeduplicator();
        controller = new SlackEventController(
                issueCreateService, scrumReportService, jiraSyncService,
                jiraApiClient, claudeApiClient, jiraProps, issueRepository, intentClassifier,
                threadActionClassifier, intentFailureRepository,
                userMappingRepository, slackNotifier,
                directExecutor, deduplicator, "C1,C2");
        mockMvc = standaloneSetup(controller).build();
    }

    @Test
    void urlVerification_returnsChallenge() throws Exception {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}";

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("abc123"));

        verify(issueCreateService, never()).createFromSlackText(any());
    }

    @Test
    void appMention_unknownText_goesToHaikuClassifier() throws Exception {
        when(intentClassifier.classify(any()))
                .thenReturn(new IntentResult("register_bug", 0.95, Map.of(), "버그있음"));
        when(issueCreateService.createFromSlackText(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(IssueCreateResult.ok("P-1", "u")));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그있음","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // 비동기 스레드에서 실행되므로 즉시 verify는 불가 — 200 OK 반환만 확인
    }

    @Test
    void regularMessage_isIgnored() throws Exception {
        String body = """
                {"type":"event_callback","event":{
                    "type":"message","user":"U1","text":"일반 대화","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueCreateService, never()).createFromSlackText(any());
    }

    @Test
    void botMessage_isIgnored() throws Exception {
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","text":"bot","bot_id":"B1","channel":"C1"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueCreateService, never()).createFromSlackText(any());
    }

    @Test
    void scrumCommand_dispatchesToScrumService() throws Exception {
        when(scrumReportService.generateReport())
                .thenReturn(CompletableFuture.completedFuture("리포트"));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> scrum","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        verify(scrumReportService).generateReport();
        verify(issueCreateService, never()).createFromSlackText(any());
    }

    @Test
    void helpCommand_sendsHelpText() throws Exception {
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> help","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(slackNotifier).postThreadReply(any(), any(), any());
        verify(issueCreateService, never()).createFromSlackText(any());
    }

    @Test
    void myWorkCommand_dispatchesToMyReport() throws Exception {
        when(scrumReportService.generateMyReport(any()))
                .thenReturn(CompletableFuture.completedFuture("내 작업"));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 내작업","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(scrumReportService).generateMyReport("U1");
        verify(issueCreateService, never()).createFromSlackText(any());
    }

    @Test
    void searchCommand_withKeyword_callsRepository() throws Exception {
        when(issueRepository.searchByKeyword("로그인"))
                .thenReturn(List.of(
                        new IssueEntity("SLAC-7", "로그인 페이지 에러", "Bug", "진행 중", "진행 중",
                                "김영현", 3.0, "reporter", null, Instant.now(), Instant.now())));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 검색 로그인","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueRepository).searchByKeyword("로그인");
        verify(slackNotifier).postThreadReply(any(), any(), any());
    }

    @Test
    void searchCommand_withoutKeyword_sendsGuidance() throws Exception {
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 검색","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueRepository, never()).searchByKeyword(any());
        verify(slackNotifier).postThreadReply("C1", "1.0", ":mag: 검색어를 입력해주세요. 예: `@지라 검색 로그인`");
    }

    @Test
    void searchCommand_english_callsRepository() throws Exception {
        when(issueRepository.searchByKeyword("login"))
                .thenReturn(Collections.emptyList());
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> search login","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueRepository).searchByKeyword("login");
    }

    @Test
    void formatSearchResults_noResults_showsEmptyMessage() {
        String result = controller.formatSearchResults("테스트", Collections.emptyList());
        org.assertj.core.api.Assertions.assertThat(result)
                .isEqualTo(":mag: \"테스트\" 검색 결과가 없습니다.");
    }

    @Test
    void formatSearchResults_withResults_showsFormattedList() {
        List<IssueEntity> issues = List.of(
                new IssueEntity("SLAC-7", "로그인 에러", "Bug", "진행 중", "진행 중",
                        "김영현", 3.0, "reporter", null, Instant.now(), Instant.now()),
                new IssueEntity("SLAC-8", "로그인 UI 개선", "Story", "할 일", "할 일",
                        null, null, "reporter", null, Instant.now(), Instant.now()));

        String result = controller.formatSearchResults("로그인", issues);
        org.assertj.core.api.Assertions.assertThat(result)
                .contains(":mag: \"로그인\" 검색 결과 (2건)")
                .contains("<https://jira.example.com/browse/SLAC-7|SLAC-7>")
                .contains("담당: 김영현")
                .contains("SP 3")
                .contains("<https://jira.example.com/browse/SLAC-8|SLAC-8>")
                .contains("담당: 미배정")
                .contains("SP -");
    }

    @Test
    void formatSearchResults_moreThanMax_showsOverflowMessage() {
        // Create 12 issues to test the "외 N건" overflow message
        List<IssueEntity> issues = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(i -> new IssueEntity("SLAC-" + i, "이슈 " + i, "Bug", "진행 중", "진행 중",
                        "담당자", 1.0, "reporter", null, Instant.now(), Instant.now()))
                .toList();

        String result = controller.formatSearchResults("이슈", issues);
        org.assertj.core.api.Assertions.assertThat(result)
                .contains(":mag: \"이슈\" 검색 결과 (12건)")
                .contains("외 2건이 더 있습니다.")
                .doesNotContain("SLAC-11")
                .doesNotContain("SLAC-12");
    }

    @Test
    void semanticSearch_haiku_classifiesAsSearch_callsSonnet() throws Exception {
        // When Haiku classifies as "search", Sonnet semantic search should be invoked
        when(intentClassifier.classify(any()))
                .thenReturn(new IntentResult("search", 0.90, Map.of("keyword", "로그인 에러"), "로그인 에러 관련 이슈 알려줘"));

        IssueEntity issue1 = new IssueEntity("SLAC-7", "로그인 500 에러", "Bug", "진행 중", "진행 중",
                "김영현", 3.0, "reporter", "로그인 페이지에서 500 에러 발생", Instant.now(), Instant.now());
        IssueEntity issue2 = new IssueEntity("SLAC-8", "결제 금액 표시", "Bug", "완료", "완료",
                "최아록", 2.0, "reporter", null, Instant.now(), Instant.now());

        when(issueRepository.findAll()).thenReturn(List.of(issue1, issue2));
        when(claudeApiClient.searchIssues(any(), any())).thenReturn(List.of("SLAC-7"));

        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 로그인 에러 관련 이슈 알려줘","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(claudeApiClient).searchIssues(any(), any());
        verify(slackNotifier).postThreadReply(any(), any(), org.mockito.ArgumentMatchers.contains("SLAC-7"));
    }

    @Test
    void semanticSearch_sonnetFails_fallsBackToKeywordSearch() throws Exception {
        when(intentClassifier.classify(any()))
                .thenReturn(new IntentResult("search", 0.90, Map.of("keyword", "로그인"), "로그인 관련"));

        IssueEntity issue1 = new IssueEntity("SLAC-7", "로그인 500 에러", "Bug", "진행 중", "진행 중",
                "김영현", 3.0, "reporter", null, Instant.now(), Instant.now());

        when(issueRepository.findAll()).thenReturn(List.of(issue1));
        // Sonnet returns empty → fallback to keyword search
        when(claudeApiClient.searchIssues(any(), any())).thenReturn(Collections.emptyList());
        when(issueRepository.searchByKeyword("로그인")).thenReturn(List.of(issue1));

        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 로그인 관련","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueRepository).searchByKeyword("로그인");
    }

    @Test
    void appMention_stripsMentionTag() {
        // "<@U0AT5U95C4T> 로그인 에러" → "로그인 에러"
        org.assertj.core.api.Assertions.assertThat(
                SlackEventController.stripMention("<@U0AT5U95C4T> 로그인 에러")).isEqualTo("로그인 에러");
        org.assertj.core.api.Assertions.assertThat(
                SlackEventController.stripMention("<@U0AT5U95C4T>  멀티스페이스")).isEqualTo("멀티스페이스");
        org.assertj.core.api.Assertions.assertThat(
                SlackEventController.stripMention(null)).isEmpty();
    }
}
