package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.JiraApiException;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.dto.IssueCreateCommand;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import com.jirabot.slack.service.DuplicateDetectionService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class IssueCreateServiceImplTest {

    private final ClaudeApiClient claude = mock(ClaudeApiClient.class);
    private final JiraApiClient jira = mock(JiraApiClient.class);
    private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
    private final DuplicateDetectionService duplicateDetection = mock(DuplicateDetectionService.class);
    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final UserMappingRepository userMappingRepository = mock(UserMappingRepository.class);
    private final JiraProperties jiraProps = new JiraProperties(
            "https://example.atlassian.net", "u@x.com", "token", "PROJ", null, null);
    private final IssueCreateServiceImpl service =
            new IssueCreateServiceImpl(claude, jira, jiraProps, slackNotifier, duplicateDetection,
                    issueRepository, userMappingRepository);

    @Test
    void happyPath_createsIssueAndReturnsUrl() throws ExecutionException, InterruptedException {
        // Registered user mapping must exist for the guard clause
        when(userMappingRepository.findBySlackUserId("U123"))
                .thenReturn(Optional.of(new UserMappingEntity("U123", "Kim", "김영현")));

        var classification = new IssueClassification(
                IssueClassification.IssueType.BUG, 2, "title", "summary");
        when(claude.classify(anyString(), any())).thenReturn(classification);
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(eq(classification), anyString(), any()))
                .thenReturn(new JiraCreateResponse("10001", "PROJ-1", "https://..."));

        var cmd = new IssueCreateCommand("login broken", "U123", "C1", "123.0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isTrue();
        assertThat(result.issueKey()).isEqualTo("PROJ-1");
        assertThat(result.issueUrl()).isEqualTo("https://example.atlassian.net/browse/PROJ-1");
        verify(claude).classify(eq("login broken"), any());
        verify(issueRepository).save(any());
        verify(slackNotifier).postBlockMessage(eq("C1"), eq("123.0"), any(), any());
    }

    @Test
    void jiraFailure_returnsFailureResult() throws Exception {
        when(userMappingRepository.findBySlackUserId("U1"))
                .thenReturn(Optional.of(new UserMappingEntity("U1", "User", "유저")));
        when(claude.classify(anyString(), any()))
                .thenReturn(IssueClassification.fallback("x"));
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(any(), anyString(), any())).thenThrow(new JiraApiException("400 bad"));

        var cmd = new IssueCreateCommand("x", "U1", "C", "0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("400");
    }

    @Test
    void unregisteredUser_notifiesAndReturnsFailure() throws Exception {
        // No mapping exists for this user
        when(userMappingRepository.findBySlackUserId("U_NEW"))
                .thenReturn(Optional.empty());

        var cmd = new IssueCreateCommand("deploy failed", "U_NEW", "C1", "111.0");
        var result = service.createFromSlackText(cmd).get();

        // Should fail with "unregistered"
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("unregistered");

        // Should send registration guidance via thread reply
        verify(slackNotifier).postThreadReply(eq("C1"), eq("111.0"), contains("등록"));

        // Should NOT call Claude classify or Jira API
        verify(claude, never()).classify(anyString(), any());
        verify(jira, never()).createIssue(any(), anyString(), any());
        verify(issueRepository, never()).save(any());
    }

    @Test
    void unregisteredUser_noChannelInfo_skipsNotification() throws Exception {
        when(userMappingRepository.findBySlackUserId("U_NEW"))
                .thenReturn(Optional.empty());

        var cmd = new IssueCreateCommand("deploy failed", "U_NEW", null, null);
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("unregistered");

        // No notification when channel/eventTs are null
        verify(slackNotifier, never()).postThreadReply(anyString(), anyString(), anyString());
        verify(claude, never()).classify(anyString(), any());
    }

    @Test
    void unregisteredUser_notificationFails_returnsUnregisteredNotRuntimeException() throws Exception {
        when(userMappingRepository.findBySlackUserId("U_FAIL"))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("Slack API down"))
                .when(slackNotifier).postThreadReply(anyString(), anyString(), anyString());

        var cmd = new IssueCreateCommand("deploy failed", "U_FAIL", "C1", "111.0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("unregistered");

        // Should NOT call Claude classify or Jira API
        verify(claude, never()).classify(anyString(), any());
        verify(jira, never()).createIssue(any(), anyString(), any());
    }

    @Test
    void registeredUser_issueCreatedNormally() throws Exception {
        // Verify that a registered user goes through the full creation flow
        var mappingEntity = new UserMappingEntity("U_REG", "Registered", "등록된사용자");
        when(userMappingRepository.findBySlackUserId("U_REG"))
                .thenReturn(Optional.of(mappingEntity));

        var classification = new IssueClassification(
                IssueClassification.IssueType.FEATURE, 3, "새 기능 추가", "새 기능 요약");
        when(claude.classify(anyString(), any())).thenReturn(classification);
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(eq(classification), eq("등록된사용자"), any()))
                .thenReturn(new JiraCreateResponse("10002", "PROJ-2", "https://..."));

        var cmd = new IssueCreateCommand("새 기능 추가해주세요", "U_REG", "C2", "222.0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isTrue();
        assertThat(result.issueKey()).isEqualTo("PROJ-2");

        // Verify full flow executed
        verify(claude).classify(eq("새 기능 추가해주세요"), any());
        verify(jira).createIssue(eq(classification), eq("등록된사용자"), any());
        verify(issueRepository).save(any());
    }
}
