package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.client.IntentClassifier;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.ThreadActionClassifier;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.repository.IntentFailureRepository;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import com.jirabot.slack.service.BugQueryService;
import com.jirabot.slack.service.IssueCreateResult;
import com.jirabot.slack.service.IssueCreateService;
import com.jirabot.slack.service.IssueSearchService;
import com.jirabot.slack.service.JiraSyncService;
import com.jirabot.slack.service.ScrumReportService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// STUDY: standaloneSetup은 Spring 컨텍스트 없이 컨트롤러만 MockMvc로 래핑 — 다른 팀원 필터/빈의 영향을 받지 않음.
class SlackEventControllerTest {

    private IssueCreateService issueCreateService;
    private IssueSearchService issueSearchService;
    private ScrumReportService scrumReportService;
    private BugQueryService bugQueryService;
    private JiraSyncService jiraSyncService;
    private JiraApiClient jiraApiClient;
    private JiraProperties jiraProps;
    private IssueRepository issueRepository;
    private IntentClassifier intentClassifier;
    private ThreadActionClassifier threadActionClassifier;
    private IntentFailureRepository intentFailureRepository;
    private UserMappingRepository userMappingRepository;
    private SlackNotifier slackNotifier;
    private SlackEventController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        issueCreateService = mock(IssueCreateService.class);
        issueSearchService = mock(IssueSearchService.class);
        scrumReportService = mock(ScrumReportService.class);
        bugQueryService = mock(BugQueryService.class);
        jiraSyncService = mock(JiraSyncService.class);
        jiraApiClient = mock(JiraApiClient.class);
        jiraProps = new JiraProperties("https://test.atlassian.net", "test@test.com", "token", "SLAC", null, null);
        issueRepository = mock(IssueRepository.class);
        intentClassifier = mock(IntentClassifier.class);
        threadActionClassifier = mock(ThreadActionClassifier.class);
        intentFailureRepository = mock(IntentFailureRepository.class);
        userMappingRepository = mock(UserMappingRepository.class);
        slackNotifier = mock(SlackNotifier.class);
        Executor directExecutor = Runnable::run;
        SlackEventDeduplicator deduplicator = new SlackEventDeduplicator();
        controller = new SlackEventController(
                issueCreateService, issueSearchService, scrumReportService, bugQueryService,
                jiraSyncService, jiraApiClient, jiraProps, issueRepository, intentClassifier,
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

    // --- Bug query routing tests ---

    @Test
    void bugCommand_exact_queriesResolvedBugs() throws Exception {
        when(bugQueryService.queryResolvedBugs(any())).thenReturn(CompletableFuture.completedFuture("결과"));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그","channel":"C1","ts":"2.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(bugQueryService).queryResolvedBugs(any());
        verify(intentClassifier, never()).classify(any());
    }

    @Test
    void bugQueryCommand_exact_queriesResolvedBugs() throws Exception {
        when(bugQueryService.queryResolvedBugs(any())).thenReturn(CompletableFuture.completedFuture("결과"));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그조회","channel":"C1","ts":"2.1"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(bugQueryService).queryResolvedBugs(any());
    }

    @Test
    void bugEnglishCommand_queriesResolvedBugs() throws Exception {
        when(bugQueryService.queryResolvedBugs(any())).thenReturn(CompletableFuture.completedFuture("결과"));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> bug","channel":"C1","ts":"2.2"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(bugQueryService).queryResolvedBugs(any());
    }

    @Test
    void bugWithDate_queriesResolvedBugsWithCorrectDate() throws Exception {
        when(bugQueryService.queryResolvedBugs(any())).thenReturn(CompletableFuture.completedFuture("결과"));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그 2026.03.11","channel":"C1","ts":"2.3"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(bugQueryService).queryResolvedBugs(dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 3, 11));
    }

    @Test
    void bugWithDescriptiveText_fallsThroughToHaiku() throws Exception {
        when(intentClassifier.classify(any()))
                .thenReturn(new IntentResult("register_bug", 0.95, Map.of(), "버그 발생했어요"));
        when(issueCreateService.createFromSlackText(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(IssueCreateResult.ok("P-1", "u")));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그 발생했어요","channel":"C1","ts":"2.4"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Should NOT go to bug query — should fall through to Haiku
        verify(bugQueryService, never()).queryResolvedBugs(any());
        verify(intentClassifier).classify(any());
    }

    @Test
    void bugQuery_withResults_delegatesToService() throws Exception {
        // STUDY: 포맷팅 검증은 BugQueryServiceImplTest로 이동. 컨트롤러는 서비스 호출만 확인.
        when(bugQueryService.queryResolvedBugs(any()))
                .thenReturn(CompletableFuture.completedFuture(":bug: 결과"));

        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그","channel":"C1","ts":"2.5"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(bugQueryService).queryResolvedBugs(any());
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackNotifier).postThreadReply(any(), any(), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains(":bug: 결과");
    }

    @Test
    void bugQuery_noDateKeyword_defaultsTo7Days() throws Exception {
        when(bugQueryService.queryResolvedBugs(any()))
                .thenReturn(CompletableFuture.completedFuture("결과"));

        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그","channel":"C1","ts":"2.6"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(bugQueryService).queryResolvedBugs(dateCaptor.capture());
        // 7일 전 날짜가 전달되어야 함
        LocalDate expected = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(7);
        assertThat(dateCaptor.getValue()).isEqualTo(expected);
    }

    @Test
    void bugQuery_invalidDate_defaultsTo7DaysWithWarning() throws Exception {
        when(bugQueryService.queryResolvedBugs(any()))
                .thenReturn(CompletableFuture.completedFuture("결과"));

        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그 2026.13.40","channel":"C1","ts":"2.7"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 경고 메시지 + 서비스 호출 모두 발생
        verify(slackNotifier, org.mockito.Mockito.atLeast(1)).postThreadReply(any(), any(), any());
        verify(bugQueryService).queryResolvedBugs(any());
    }

    @Test
    void bugQuery_dashDateFormat_parsesCorrectly() throws Exception {
        when(bugQueryService.queryResolvedBugs(any()))
                .thenReturn(CompletableFuture.completedFuture("결과"));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그 2026-03-11","channel":"C1","ts":"2.8"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(bugQueryService).queryResolvedBugs(dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 3, 11));
    }

    @Test
    void bugQuery_slashDateFormat_parsesCorrectly() throws Exception {
        when(bugQueryService.queryResolvedBugs(any()))
                .thenReturn(CompletableFuture.completedFuture("결과"));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 버그 2026/03/11","channel":"C1","ts":"2.9"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(bugQueryService).queryResolvedBugs(dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 3, 11));
    }

    @Test
    void searchCommand_withKeyword_callsSearchService() throws Exception {
        when(issueSearchService.searchByKeyword("로그인"))
                .thenReturn(CompletableFuture.completedFuture(":mag: \"로그인\" 검색 결과 (1건)"));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 검색 로그인","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueSearchService).searchByKeyword("로그인");
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

        verify(issueRepository, never()).searchByKeyword(any(), any());
        verify(slackNotifier).postThreadReply("C1", "1.0", ":mag: 검색어를 입력해주세요. 예: `@지라 검색 로그인`");
    }

    @Test
    void searchCommand_english_callsSearchService() throws Exception {
        when(issueSearchService.searchByKeyword("login"))
                .thenReturn(CompletableFuture.completedFuture(":mag: \"login\" 검색 결과가 없습니다."));
        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> search login","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueSearchService).searchByKeyword("login");
    }

    @Test
    void semanticSearch_haiku_classifiesAsSearch_callsSearchService() throws Exception {
        when(intentClassifier.classify(any()))
                .thenReturn(new IntentResult("search", 0.90, Map.of("keyword", "로그인 에러"), "로그인 에러 관련 이슈 알려줘"));
        when(issueSearchService.searchSemantic(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(":mag: \"로그인 에러 관련 이슈 알려줘\" 검색 결과 (1건)\n• SLAC-7"));

        String body = """
                {"type":"event_callback","event":{
                    "type":"app_mention","user":"U1","text":"<@U0BOT> 로그인 에러 관련 이슈 알려줘","channel":"C1","ts":"1.0"}}
                """;

        mockMvc.perform(post("/api/slack/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueSearchService).searchSemantic(any(), any());
        verify(slackNotifier).postThreadReply(any(), any(), any());
    }

    @Test
    void appMention_stripsMentionTag() {
        // "<@U0AT5U95C4T> 로그인 에러" → "로그인 에러"
        assertThat(
                SlackEventController.stripMention("<@U0AT5U95C4T> 로그인 에러")).isEqualTo("로그인 에러");
        assertThat(
                SlackEventController.stripMention("<@U0AT5U95C4T>  멀티스페이스")).isEqualTo("멀티스페이스");
        assertThat(
                SlackEventController.stripMention(null)).isEmpty();
    }
}
