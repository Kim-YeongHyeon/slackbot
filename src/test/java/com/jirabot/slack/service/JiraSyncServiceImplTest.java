package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.dto.SprintInfo;
import com.jirabot.slack.client.dto.SprintIssue;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JiraSyncServiceImplTest {

    private JiraApiClient jira;
    private IssueRepository issueRepository;
    private JiraSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        jira = mock(JiraApiClient.class);
        issueRepository = mock(IssueRepository.class);
        service = new JiraSyncServiceImpl(jira, issueRepository);
    }

    private SprintIssue sprintIssue(String key) {
        return new SprintIssue(key, key, "진행 중", "진행 중", "Alice", null, "작업",
                false, 2.0, null, null, null, null);
    }

    private IssueEntity open(String key) {
        return new IssueEntity(key, key, "작업", "진행 중", "진행 중",
                "Alice", 2.0, null, "desc", Instant.now(), Instant.now());
    }

    @Test
    void prune_deletesOnlyJiraDeletedNotSeenIssues() {
        when(jira.getActiveSprint()).thenReturn(Optional.of(new SprintInfo(933, "S3", "active", null, null)));
        when(jira.getSprintIssues(933)).thenReturn(List.of(sprintIssue("ES2-2048")));   // seen
        when(jira.getBacklogIssues()).thenReturn(List.of());
        // 미완료 로컬: 2048(seen), 2041/2042(미seen), 9999(미seen이지만 Jira에 존재)
        when(issueRepository.findByStatusCategoryNot("완료"))
                .thenReturn(List.of(open("ES2-2048"), open("ES2-2041"), open("ES2-2042"), open("ES2-9999")));
        when(jira.issueExists("ES2-2041")).thenReturn(false); // 삭제됨
        when(jira.issueExists("ES2-2042")).thenReturn(false); // 삭제됨
        when(jira.issueExists("ES2-9999")).thenReturn(true);  // 이동/존재 → 보존

        int pruned = service.pruneDeletedIssues();

        assertThat(pruned).isEqualTo(2);
        ArgumentCaptor<java.util.Collection<String>> cap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(issueRepository).deleteByIssueKeyIn(cap.capture());
        assertThat(cap.getValue()).containsExactlyInAnyOrder("ES2-2041", "ES2-2042");
        // seen 이슈는 존재 확인조차 안 한다.
        verify(jira, never()).issueExists("ES2-2048");
    }

    @Test
    void prune_skipsWhenNothingSeen_avoidsMassDelete() {
        when(jira.getActiveSprint()).thenReturn(Optional.empty());
        when(jira.getBacklogIssues()).thenReturn(List.of());

        int pruned = service.pruneDeletedIssues();

        assertThat(pruned).isZero();
        verify(issueRepository, never()).findByStatusCategoryNot("완료");
        verify(issueRepository, never()).deleteByIssueKeyIn(anyCollection());
    }

    @Test
    void prune_noDeletionWhenAllSeenOrExisting() {
        when(jira.getActiveSprint()).thenReturn(Optional.of(new SprintInfo(933, "S3", "active", null, null)));
        when(jira.getSprintIssues(933)).thenReturn(List.of(sprintIssue("ES2-1")));
        when(jira.getBacklogIssues()).thenReturn(List.of());
        when(issueRepository.findByStatusCategoryNot("완료")).thenReturn(List.of(open("ES2-1")));

        int pruned = service.pruneDeletedIssues();

        assertThat(pruned).isZero();
        verify(issueRepository, never()).deleteByIssueKeyIn(anyCollection());
    }

    // --- syncIfStale (검색 선행 sync 의 TTL 게이트) ---

    @Test
    void syncIfStale_firstCallSyncs_secondWithinTtlSkips() {
        when(jira.getActiveSprint()).thenReturn(Optional.empty());
        when(jira.getBacklogIssues()).thenReturn(List.of());

        service.syncIfStale(java.time.Duration.ofSeconds(60));
        service.syncIfStale(java.time.Duration.ofSeconds(60));

        // 첫 호출만 Jira 왕복, TTL 내 두 번째 호출은 생략된다.
        verify(jira, org.mockito.Mockito.times(1)).getActiveSprint();
        verify(jira, org.mockito.Mockito.times(1)).getBacklogIssues();
    }

    @Test
    void syncIfStale_zeroTtl_alwaysSyncs() {
        when(jira.getActiveSprint()).thenReturn(Optional.empty());
        when(jira.getBacklogIssues()).thenReturn(List.of());

        service.syncIfStale(java.time.Duration.ZERO);
        service.syncIfStale(java.time.Duration.ZERO);

        verify(jira, org.mockito.Mockito.times(2)).getActiveSprint();
    }

    @Test
    void fullSync_refreshesTtl_soFollowingSyncIfStaleSkips() {
        when(jira.getActiveSprint()).thenReturn(Optional.empty());
        when(jira.getBacklogIssues()).thenReturn(List.of());

        service.fullSync();              // 수동/스케줄 sync — TTL 타임스탬프 갱신
        service.syncIfStale(java.time.Duration.ofSeconds(60));

        // fullSync 는 공유-fetch 로 sprint/backlog 를 각 1회만 조회하고(sync+prune 공용),
        // 이후 TTL 내 syncIfStale 은 통째로 생략된다.
        verify(jira, org.mockito.Mockito.times(1)).getActiveSprint();
        verify(jira, org.mockito.Mockito.times(1)).getBacklogIssues();
    }
}
