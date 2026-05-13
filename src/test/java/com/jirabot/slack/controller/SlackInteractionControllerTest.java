package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.filter.CachedBodyFilter;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.util.BlockKitBuilder;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

class SlackInteractionControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JiraApiClient jiraApiClient = mock(JiraApiClient.class);
    private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final Executor directExecutor = Runnable::run;

    private SlackInteractionController controller;

    @BeforeEach
    void setUp() {
        controller = new SlackInteractionController(
                objectMapper, jiraApiClient, slackNotifier, issueRepository, directExecutor);
    }

    private HttpServletRequest mockRequest(String payloadJson) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        String formBody = "payload=" + URLEncoder.encode(payloadJson, StandardCharsets.UTF_8);
        byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
        when(request.getAttribute(CachedBodyFilter.RAW_BODY_ATTRIBUTE)).thenReturn(bodyBytes);
        return request;
    }

    private String buildPayload(String actionId, String issueKey) {
        return String.format("""
                {
                  "type": "block_actions",
                  "user": {"id": "U123", "name": "testuser"},
                  "channel": {"id": "C456"},
                  "message": {"ts": "1234567890.123456", "blocks": [
                    {"type": "section", "text": {"type": "mrkdwn", "text": "Issue info"}},
                    {"type": "divider"},
                    {"type": "actions", "elements": []}
                  ]},
                  "actions": [{"action_id": "%s", "value": "%s"}]
                }
                """, actionId, issueKey);
    }

    @Test
    void todoTransition_updatesAndShowsNextButton() {
        when(jiraApiClient.transitionIssue("PROJ-1", "해야 할 일")).thenReturn(true);
        IssueEntity issue = new IssueEntity("PROJ-1", "Test", "Task", "Backlog", "해야 할 일",
                null, 3.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findByIssueKey("PROJ-1")).thenReturn(Optional.of(issue));

        ResponseEntity<String> response = controller.onInteraction(
                mockRequest(buildPayload(BlockKitBuilder.ACTION_TODO, "PROJ-1")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(jiraApiClient).transitionIssue("PROJ-1", "해야 할 일");
        verify(jiraApiClient, never()).moveToActiveSprint(anyString());

        // 다음 버튼(진행 중)이 포함되어야 함
        ArgumentCaptor<String> blocksCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackNotifier).updateMessage(eq("C456"), eq("1234567890.123456"),
                anyString(), blocksCaptor.capture());
        assertThat(blocksCaptor.getValue()).contains("jira_transition_in_progress");
    }

    @Test
    void inProgressTransition_movesToSprintAndShowsNextButton() {
        when(jiraApiClient.transitionIssue("PROJ-1", "진행 중")).thenReturn(true);
        when(jiraApiClient.moveToActiveSprint("PROJ-1")).thenReturn(true);
        IssueEntity issue = new IssueEntity("PROJ-1", "Test", "Task", "해야 할 일", "해야 할 일",
                null, 3.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findByIssueKey("PROJ-1")).thenReturn(Optional.of(issue));

        controller.onInteraction(
                mockRequest(buildPayload(BlockKitBuilder.ACTION_IN_PROGRESS, "PROJ-1")));

        verify(jiraApiClient).transitionIssue("PROJ-1", "진행 중");
        verify(jiraApiClient).moveToActiveSprint("PROJ-1");

        ArgumentCaptor<String> blocksCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackNotifier).updateMessage(eq("C456"), eq("1234567890.123456"),
                anyString(), blocksCaptor.capture());
        assertThat(blocksCaptor.getValue()).contains("jira_transition_in_review");
    }

    @Test
    void inReviewTransition_showsDoneButton() {
        when(jiraApiClient.transitionIssue("PROJ-1", "검토 중")).thenReturn(true);
        IssueEntity issue = new IssueEntity("PROJ-1", "Test", "Task", "진행 중", "진행 중",
                null, 3.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findByIssueKey("PROJ-1")).thenReturn(Optional.of(issue));

        controller.onInteraction(
                mockRequest(buildPayload(BlockKitBuilder.ACTION_IN_REVIEW, "PROJ-1")));

        verify(jiraApiClient).transitionIssue("PROJ-1", "검토 중");

        ArgumentCaptor<String> blocksCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackNotifier).updateMessage(eq("C456"), eq("1234567890.123456"),
                anyString(), blocksCaptor.capture());
        assertThat(blocksCaptor.getValue()).contains("jira_transition_done");
    }

    @Test
    void doneTransition_removesAllButtons() throws Exception {
        when(jiraApiClient.transitionIssue("PROJ-2", "완료")).thenReturn(true);
        IssueEntity issue = new IssueEntity("PROJ-2", "Test", "Bug", "진행 중", "진행 중",
                null, 5.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findByIssueKey("PROJ-2")).thenReturn(Optional.of(issue));

        controller.onInteraction(
                mockRequest(buildPayload(BlockKitBuilder.ACTION_DONE, "PROJ-2")));

        verify(jiraApiClient).transitionIssue("PROJ-2", "완료");

        ArgumentCaptor<String> blocksCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackNotifier).updateMessage(eq("C456"), eq("1234567890.123456"),
                anyString(), blocksCaptor.capture());

        JsonNode updatedBlocks = objectMapper.readTree(blocksCaptor.getValue());
        for (JsonNode block : updatedBlocks) {
            assertThat(block.path("type").asText()).isNotEqualTo("actions");
        }
    }

    @Test
    void quickDone_transitionsAllStepsAndMovesToSprint() {
        when(jiraApiClient.transitionIssue("PROJ-3", "해야 할 일")).thenReturn(true);
        when(jiraApiClient.transitionIssue("PROJ-3", "진행 중")).thenReturn(true);
        when(jiraApiClient.moveToActiveSprint("PROJ-3")).thenReturn(true);
        when(jiraApiClient.transitionIssue("PROJ-3", "완료")).thenReturn(true);
        IssueEntity issue = new IssueEntity("PROJ-3", "Test", "Task", "Backlog", "해야 할 일",
                null, 2.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findByIssueKey("PROJ-3")).thenReturn(Optional.of(issue));

        controller.onInteraction(
                mockRequest(buildPayload(BlockKitBuilder.ACTION_QUICK_DONE, "PROJ-3")));

        verify(jiraApiClient).transitionIssue("PROJ-3", "해야 할 일");
        verify(jiraApiClient).transitionIssue("PROJ-3", "진행 중");
        verify(jiraApiClient).moveToActiveSprint("PROJ-3");
        verify(jiraApiClient).transitionIssue("PROJ-3", "완료");
        verify(slackNotifier).updateMessage(eq("C456"), eq("1234567890.123456"), anyString(), anyString());
    }

    @Test
    void quickDone_skipsTodoIfAlreadyInTodo() {
        // 해야 할 일 전환 실패 (이미 해당 상태) — 계속 진행해야 함
        when(jiraApiClient.transitionIssue("PROJ-4", "해야 할 일")).thenReturn(false);
        when(jiraApiClient.transitionIssue("PROJ-4", "진행 중")).thenReturn(true);
        when(jiraApiClient.moveToActiveSprint("PROJ-4")).thenReturn(true);
        when(jiraApiClient.transitionIssue("PROJ-4", "완료")).thenReturn(true);
        IssueEntity issue = new IssueEntity("PROJ-4", "Test", "Task", "해야 할 일", "해야 할 일",
                null, 2.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findByIssueKey("PROJ-4")).thenReturn(Optional.of(issue));

        controller.onInteraction(
                mockRequest(buildPayload(BlockKitBuilder.ACTION_QUICK_DONE, "PROJ-4")));

        verify(jiraApiClient).transitionIssue("PROJ-4", "완료");
        verify(slackNotifier).updateMessage(eq("C456"), eq("1234567890.123456"), anyString(), anyString());
    }

    @Test
    void transitionFailure_sendsErrorThreadReply() {
        when(jiraApiClient.transitionIssue("PROJ-5", "완료")).thenReturn(false);

        controller.onInteraction(
                mockRequest(buildPayload(BlockKitBuilder.ACTION_DONE, "PROJ-5")));

        verify(slackNotifier).postThreadReply(eq("C456"), eq("1234567890.123456"), anyString());
    }

    @Test
    void nonBlockActions_ignored() {
        String payload = """
                {
                  "type": "view_submission",
                  "user": {"id": "U123", "name": "testuser"}
                }
                """;

        ResponseEntity<String> response = controller.onInteraction(mockRequest(payload));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void invalidPayload_returns200() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(CachedBodyFilter.RAW_BODY_ATTRIBUTE))
                .thenReturn("payload={invalid".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.onInteraction(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void unknownActionId_ignored() {
        controller.onInteraction(
                mockRequest(buildPayload("unknown_action", "PROJ-1")));

        verify(jiraApiClient, never()).transitionIssue(anyString(), anyString());
    }
}
