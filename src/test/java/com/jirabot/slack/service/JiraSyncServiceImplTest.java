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
        service = new JiraSyncServiceImpl(jira, issueRepository,
                new com.jirabot.slack.config.JiraProperties("https://j.example.com", "u@x", "t", "ES2",
                        null, null));
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

    @Test
    void parseInstant_handlesJiraOffsetWithoutColon() {
        // Jira Cloud 의 +0900(콜론 없음) — 과거엔 Instant.parse 가 실패해 null 로 저장되던 형식.
        Instant got = JiraSyncServiceImpl.parseInstant("2026-06-09T16:07:15.273+0900");
        assertThat(got).isEqualTo(Instant.parse("2026-06-09T07:07:15.273Z"));
        // 콜론 있는 오프셋과 Z 형식도 모두 파싱.
        assertThat(JiraSyncServiceImpl.parseInstant("2026-06-09T16:07:15.273+09:00"))
                .isEqualTo(Instant.parse("2026-06-09T07:07:15.273Z"));
        assertThat(JiraSyncServiceImpl.parseInstant("2026-06-09T07:07:15Z"))
                .isEqualTo(Instant.parse("2026-06-09T07:07:15Z"));
        assertThat(JiraSyncServiceImpl.parseInstant(null)).isNull();
        assertThat(JiraSyncServiceImpl.parseInstant("garbage")).isNull();
    }

    @Test
    void backfillHistory_setsCreatedAndCompletedForDoneIssue() {
        SprintIssue doneBug = new SprintIssue("ES2-100", "옛 버그", "완료", "완료", "Alice", "Bob", "버그",
                false, 2.0, null, "2026-01-02T10:00:00.000+0900", "2026-01-05T10:00:00.000+0900",
                "2026-01-05T18:30:00.000+0900");
        when(jira.searchByJql(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(doneBug));
        when(issueRepository.findByIssueKey("ES2-100")).thenReturn(Optional.empty());

        String result = service.backfillHistory();

        assertThat(result).contains("전체 1건");
        ArgumentCaptor<IssueEntity> cap = ArgumentCaptor.forClass(IssueEntity.class);
        verify(issueRepository).save(cap.capture());
        IssueEntity saved = cap.getValue();
        assertThat(saved.getJiraCreated()).isEqualTo(Instant.parse("2026-01-02T01:00:00Z"));
        // 완료일은 resolutionDate(=resolutiondate||statuscategorychangedate) 사용
        assertThat(saved.getCompletedAt()).isEqualTo(Instant.parse("2026-01-05T09:30:00Z"));
    }

    @Test
    void backfillHistory_existingIssue_fillsCreatedAndCompleted_withoutTouchingSprint() {
        IssueEntity existing = open("ES2-101");
        existing.setSprint(933, "S3");
        existing.setJiraCreated(null);
        SprintIssue doneBug = new SprintIssue("ES2-101", "버그", "완료", "완료", "Alice", null, "버그",
                false, 1.0, null, "2026-02-01T10:00:00.000+0900", "2026-02-03T10:00:00.000+0900",
                "2026-02-03T10:00:00.000+0900");
        when(jira.searchByJql(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(doneBug));
        when(issueRepository.findByIssueKey("ES2-101")).thenReturn(Optional.of(existing));

        service.backfillHistory();

        assertThat(existing.getJiraCreated()).isEqualTo(Instant.parse("2026-02-01T01:00:00Z"));
        assertThat(existing.getCompletedAt()).isEqualTo(Instant.parse("2026-02-03T01:00:00Z"));
        // 스프린트 정보는 보존(활성 뷰 깨지지 않게)
        assertThat(existing.getSprintId()).isEqualTo(933);
    }
}
