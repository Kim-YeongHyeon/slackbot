package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class SlackInteractionControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JiraApiClient jiraApiClient = mock(JiraApiClient.class);
    private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
    private final IssueRepository issueRepository = mock(IssueRepository.class);
    // STUDY: 테스트에서는 동기 실행 executor를 사용하여 async 로직을 동기적으로 검증.
    private final Executor directExecutor = Runnable::run;

    private SlackInteractionController controller;

    @BeforeEach
    void setUp() {
        controller = new SlackInteractionController(
                objectMapper, jiraApiClient, slackNotifier, issueRepository, directExecutor);
    }

    @Test
    void inProgressTransition_updatesMessageAndDb() {
        String payload = """
                {
                  "type": "block_actions",
                  "user": {"id": "U123", "name": "testuser"},
                  "channel": {"id": "C456"},
                  "message": {"ts": "1234567890.123456"},
                  "actions": [{"action_id": "jira_transition_in_progress", "value": "PROJ-1"}]
                }
                """;

        when(jiraApiClient.transitionIssue("PROJ-1", "진행 중")).thenReturn(true);
        IssueEntity issue = new IssueEntity("PROJ-1", "Test", "작업", "해야 할 일", "해야 할 일",
                null, 3.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findByIssueKey("PROJ-1")).thenReturn(Optional.of(issue));

        ResponseEntity<String> response = controller.onInteraction(payload);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(jiraApiClient).transitionIssue("PROJ-1", "진행 중");
        verify(slackNotifier).updateMessage(eq("C456"), eq("1234567890.123456"), anyString(), anyString());
        verify(issueRepository).save(any(IssueEntity.class));
    }

    @Test
    void doneTransition_updatesMessageAndDb() {
        String payload = """
                {
                  "type": "block_actions",
                  "user": {"id": "U123", "name": "testuser"},
                  "channel": {"id": "C456"},
                  "message": {"ts": "1234567890.123456"},
                  "actions": [{"action_id": "jira_transition_done", "value": "PROJ-2"}]
                }
                """;

        when(jiraApiClient.transitionIssue("PROJ-2", "완료")).thenReturn(true);
        IssueEntity issue = new IssueEntity("PROJ-2", "Test", "버그", "진행 중", "진행 중",
                null, 5.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findByIssueKey("PROJ-2")).thenReturn(Optional.of(issue));

        ResponseEntity<String> response = controller.onInteraction(payload);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(jiraApiClient).transitionIssue("PROJ-2", "완료");
        verify(slackNotifier).updateMessage(eq("C456"), eq("1234567890.123456"), anyString(), anyString());
    }

    @Test
    void transitionFailure_sendsErrorThreadReply() {
        String payload = """
                {
                  "type": "block_actions",
                  "user": {"id": "U123", "name": "testuser"},
                  "channel": {"id": "C456"},
                  "message": {"ts": "1234567890.123456"},
                  "actions": [{"action_id": "jira_transition_done", "value": "PROJ-3"}]
                }
                """;

        when(jiraApiClient.transitionIssue("PROJ-3", "완료")).thenReturn(false);

        ResponseEntity<String> response = controller.onInteraction(payload);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
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

        ResponseEntity<String> response = controller.onInteraction(payload);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void invalidPayload_returns200() {
        ResponseEntity<String> response = controller.onInteraction("{invalid json");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void unknownActionId_ignored() {
        String payload = """
                {
                  "type": "block_actions",
                  "user": {"id": "U123", "name": "testuser"},
                  "channel": {"id": "C456"},
                  "message": {"ts": "1234567890.123456"},
                  "actions": [{"action_id": "unknown_action", "value": "PROJ-1"}]
                }
                """;

        ResponseEntity<String> response = controller.onInteraction(payload);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void buildCompletedBlocks_generatesValidJson() throws Exception {
        String json = SlackInteractionController.buildCompletedBlocks(
                "PROJ-1", ":white_check_mark:", "완료", "testuser");

        // Verify it's valid JSON
        var node = objectMapper.readTree(json);
        assertThat(node.isArray()).isTrue();
        assertThat(node.get(0).path("type").asText()).isEqualTo("section");
        assertThat(node.get(0).path("text").path("text").asText())
                .contains("PROJ-1")
                .contains("완료")
                .contains("testuser");
    }
}
