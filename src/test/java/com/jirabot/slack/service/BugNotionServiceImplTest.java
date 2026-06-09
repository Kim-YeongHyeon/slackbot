package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.NotionApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.BugResolutionSummary;
import com.jirabot.slack.client.dto.SprintIssue;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.NotionProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BugNotionServiceImplTest {

    private NotionApiClient notion;
    private ClaudeApiClient claude;
    private JiraApiClient jira;
    private SlackNotifier slackNotifier;
    private BugNotionServiceImpl service;

    private static final String STATUS_DB = "status-db";
    private static final String RESOLUTION_DB = "resolution-db";

    private NotionProperties enabledProps() {
        return new NotionProperties(true, "tok", null, "page", RESOLUTION_DB, STATUS_DB);
    }

    private JiraProperties jiraProps() {
        return new JiraProperties("https://x.atlassian.net", "u@x", "t", "ES2",
                "customfield_10036", new JiraProperties.IssueTypes("버그", "작업", "하위 작업"));
    }

    @BeforeEach
    void setUp() {
        notion = mock(NotionApiClient.class);
        claude = mock(ClaudeApiClient.class);
        jira = mock(JiraApiClient.class);
        slackNotifier = mock(SlackNotifier.class);
        when(notion.findPageId(anyString(), anyString())).thenReturn(Optional.empty());
        service = new BugNotionServiceImpl(notion, claude, jira, slackNotifier, enabledProps(), jiraProps());
    }

    private IssueEntity bug(String statusCategory) {
        return new IssueEntity("ES2-7", "로그인 500 에러", "버그", "진행 중", statusCategory,
                "김영현", 3.0, "김영현", "설명", Instant.now(), Instant.now());
    }

    @Test
    void statusChange_notDone_writesStatusRowOnly_noResolution() {
        service.syncOnStatusChange(bug("진행 중"), false);

        ArgumentCaptor<String> dbCaptor = ArgumentCaptor.forClass(String.class);
        verify(notion, times(1)).createRow(dbCaptor.capture(), any());
        assertThat(dbCaptor.getValue()).isEqualTo(STATUS_DB);
        verify(claude, never()).summarizeBugResolution(any(), any(), any(), any());
    }

    @Test
    void completion_writesBothStatusAndResolution() {
        when(claude.summarizeBugResolution(any(), any(), any(), any()))
                .thenReturn(new BugResolutionSummary("토큰 만료", "갱신 로직 추가"));
        when(jira.getComments(anyString())).thenReturn(List.of("댓글1"));

        service.syncOnStatusChange(bug(StatusCategory.DONE), true);

        ArgumentCaptor<String> dbCaptor = ArgumentCaptor.forClass(String.class);
        verify(notion, times(2)).createRow(dbCaptor.capture(), any());
        assertThat(dbCaptor.getAllValues()).containsExactlyInAnyOrder(STATUS_DB, RESOLUTION_DB);
        verify(claude).summarizeBugResolution(eq("ES2-7"), any(), any(), any());
    }

    @Test
    void disabled_whenTokenBlank_noNotionCalls() {
        BugNotionServiceImpl disabled = new BugNotionServiceImpl(notion, claude, jira, slackNotifier,
                new NotionProperties(true, "", null, "page", RESOLUTION_DB, STATUS_DB), jiraProps());

        assertThat(disabled.enabled()).isFalse();
        disabled.syncOnStatusChange(bug(StatusCategory.DONE), true);

        verify(notion, never()).createRow(any(), any());
        verify(notion, never()).updateRow(any(), any());
    }

    @Test
    void existingRow_isUpdatedNotCreated() {
        when(notion.findPageId(eq(STATUS_DB), eq("ES2-7"))).thenReturn(Optional.of("page-1"));

        service.syncOnStatusChange(bug("진행 중"), false);

        verify(notion).updateRow(eq("page-1"), any());
        verify(notion, never()).createRow(any(), any());
    }

    @Test
    void backfill_upsertsAllBugs_returnsCount() {
        SprintIssue b1 = new SprintIssue("ES2-1", "버그 A", "완료", "완료", "김영현", null, "버그",
                false, 2.0, null, "2026-05-01T00:00:00.000+0900", "2026-05-02T00:00:00.000+0900");
        SprintIssue b2 = new SprintIssue("ES2-2", "버그 B", "진행 중", "진행 중", null, null, "버그",
                false, 0.0, null, "2026-05-03T00:00:00.000+0900", "2026-05-03T00:00:00.000+0900");
        when(jira.searchByJql(anyString())).thenReturn(List.of(b1, b2));

        int count = service.backfillStatusDb();

        assertThat(count).isEqualTo(2);
        verify(notion, times(2)).createRow(eq(STATUS_DB), any());
    }

    @Test
    void backfill_statusRowHasResolvedLabelForDoneBug() {
        SprintIssue done = new SprintIssue("ES2-9", "완료 버그", "완료", "완료", "김영현", null, "버그",
                false, 1.0, null, "2026-05-01T00:00:00.000+0900", "2026-05-05T00:00:00.000+0900");
        when(jira.searchByJql(anyString())).thenReturn(List.of(done));

        ArgumentCaptor<Map<String, Object>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        service.backfillStatusDb();

        verify(notion).createRow(eq(STATUS_DB), propsCaptor.capture());
        Map<String, Object> statusProp = (Map<String, Object>) propsCaptor.getValue().get("상태");
        Map<String, Object> select = (Map<String, Object>) statusProp.get("select");
        assertThat(select.get("name")).isEqualTo("해결");
    }
}
