package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import com.jirabot.slack.service.DuplicateDetectionService;
import java.util.List;
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
            "https://example.atlassian.net", "u@x.com", "token", "PROJ");
    private final IssueCreateServiceImpl service =
            new IssueCreateServiceImpl(claude, jira, jiraProps, slackNotifier, duplicateDetection,
                    issueRepository, userMappingRepository);

    @Test
    void happyPath_createsIssueAndReturnsUrl() throws ExecutionException, InterruptedException {
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
        when(claude.classify(anyString(), any()))
                .thenReturn(IssueClassification.fallback("x"));
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(any(), anyString(), any())).thenThrow(new JiraApiException("400 bad"));

        var cmd = new IssueCreateCommand("x", "U1", "C", "0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("400");
    }
}
