package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.entity.IntentFailureEntity;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.ResponseMetricEntity;
import com.jirabot.slack.repository.IntentFailureRepository;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.ResponseMetricRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardServiceImplTest {

    private IssueRepository issueRepository;
    private UserMappingRepository userMappingRepository;
    private IntentFailureRepository intentFailureRepository;
    private ResponseMetricRepository responseMetricRepository;
    private JiraSyncService jiraSyncService;
    private com.jirabot.slack.client.GitHubApiClient gitHubApiClient;
    private DashboardServiceImpl service;

    private DashboardServiceImpl build(com.jirabot.slack.config.GitHubProperties gitHubProps) {
        ReminderProperties reminderProps = new ReminderProperties(
                true, "0 0 9 * * MON-FRI", "Asia/Seoul", "0 30 9 * * MON", "2026-06-22", 7);
        JiraProperties jiraProps = new JiraProperties("https://j.example.com", "u@x", "t", "ES2",
                null, new JiraProperties.IssueTypes("버그", "작업", "하위 작업"));
        return new DashboardServiceImpl(issueRepository, userMappingRepository,
                intentFailureRepository, responseMetricRepository, jiraSyncService, reminderProps,
                gitHubApiClient, gitHubProps, jiraProps);
    }

    @BeforeEach
    void setUp() {
        issueRepository = mock(IssueRepository.class);
        userMappingRepository = mock(UserMappingRepository.class);
        intentFailureRepository = mock(IntentFailureRepository.class);
        responseMetricRepository = mock(ResponseMetricRepository.class);
        jiraSyncService = mock(JiraSyncService.class);
        gitHubApiClient = mock(com.jirabot.slack.client.GitHubApiClient.class);
        when(jiraSyncService.lastSyncAt()).thenReturn(Optional.empty());
        service = build(new com.jirabot.slack.config.GitHubProperties(
                "tok", "CryptoLabInc", List.of("evi"), "https://api.github.com"));
    }

    private IssueEntity issue(String key, String type, String statusCategory, String assignee,
                              Double sp, Instant created, Instant completed) {
        IssueEntity e = new IssueEntity(key, "요약-" + key, type, statusCategory, statusCategory,
                assignee, sp, "reporter", null, created, created);
        if (completed != null) {
            e.setCompletedAt(completed);
        }
        return e;
    }

    // --- summary ---

    @Test
    void summary_countsOpenInProgressAndCompletionRate() {
        Instant now = Instant.now();
        IssueEntity done = issue("ES2-1", "작업", "완료", "A", 3.0, now, now);
        IssueEntity doing = issue("ES2-2", "작업", "진행 중", "A", 2.0, now, null);
        IssueEntity todo = issue("ES2-3", "버그", "해야 할 일", null, 5.0, now, null);
        done.setSprint(933, "S3");
        doing.setSprint(933, "S3");
        todo.setSprint(933, "S3");
        when(issueRepository.findAll()).thenReturn(List.of(done, doing, todo));
        when(issueRepository.findLatestSprintInfo(any())).thenReturn(List.<Object[]>of(new Object[]{933, "S3"}));
        when(issueRepository.findBySprintId(933)).thenReturn(List.of(done, doing, todo));
        when(userMappingRepository.count()).thenReturn(5L);

        var s = service.summary();

        assertThat(s.totalIssues()).isEqualTo(3);
        assertThat(s.openIssues()).isEqualTo(2);
        assertThat(s.inProgress()).isEqualTo(1);
        assertThat(s.sprintName()).isEqualTo("S3");
        assertThat(s.sprintSpTotal()).isEqualTo(10.0);
        assertThat(s.sprintSpDone()).isEqualTo(3.0);
        assertThat(s.sprintCompletionRate()).isEqualTo(30);
        assertThat(s.mappedUsers()).isEqualTo(5);
    }

    @Test
    void summary_emptyDb_isAllZeroWithoutErrors() {
        when(issueRepository.findAll()).thenReturn(List.of());
        when(issueRepository.findLatestSprintInfo(any())).thenReturn(List.of());
        when(userMappingRepository.count()).thenReturn(0L);

        var s = service.summary();

        assertThat(s.totalIssues()).isZero();
        assertThat(s.sprintName()).isNull();
        assertThat(s.sprintCompletionRate()).isZero();
        assertThat(s.lastSyncAt()).isNull();
    }

    // --- trends ---

    @Test
    void trends_bucketsCreatedAndResolvedByWeek_andAveragesResolutionHours() {
        Instant created = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant resolved = Instant.now().minus(1, ChronoUnit.DAYS); // 24h 소요
        IssueEntity fast = issue("ES2-1", "작업", "완료", "A", 1.0, created, resolved);
        IssueEntity open = issue("ES2-2", "작업", "진행 중", "A", 1.0, created, null);
        when(issueRepository.findAll()).thenReturn(List.of(fast, open));

        var t = service.trends(4);

        assertThat(t.weekly()).hasSize(4);
        long totalCreated = t.weekly().stream().mapToLong(w -> w.created()).sum();
        long totalResolved = t.weekly().stream().mapToLong(w -> w.resolved()).sum();
        assertThat(totalCreated).isEqualTo(2);
        assertThat(totalResolved).isEqualTo(1);
        // 해결된 1건의 평균 소요 ≈ 24h
        double avg = t.resolution().stream().mapToDouble(r -> r.avgHours()).max().orElse(0);
        assertThat(avg).isBetween(23.0, 25.0);
    }

    @Test
    void trends_weeksClamped() {
        when(issueRepository.findAll()).thenReturn(List.of());
        assertThat(service.trends(0).weekly()).hasSize(1);     // 최소 1
        assertThat(service.trends(999).weekly()).hasSize(26);  // 최대 26
    }

    // --- workload ---

    @Test
    void workload_groupsByAssignee_unassignedLabeled() {
        Instant now = Instant.now();
        IssueEntity a1 = issue("ES2-1", "작업", "진행 중", "Alice", 3.0, now, null);
        IssueEntity a2 = issue("ES2-2", "작업", "해야 할 일", "Alice", 2.0, now, null);
        IssueEntity none = issue("ES2-3", "작업", "해야 할 일", null, 1.0, now, null);
        when(issueRepository.findByStatusCategoryNot("완료")).thenReturn(List.of(a1, a2, none));

        var loads = service.workload();

        assertThat(loads).hasSize(2);
        assertThat(loads.get(0).assignee()).isEqualTo("Alice");
        assertThat(loads.get(0).openCount()).isEqualTo(2);
        assertThat(loads.get(0).openSp()).isEqualTo(5.0);
        assertThat(loads).extracting("assignee").contains("미배정");
    }

    // --- bugs ---

    @Test
    void bugs_countsRatioAndOpenList() {
        Instant now = Instant.now();
        IssueEntity bug1 = issue("ES2-1", "버그", "진행 중", "A", 2.0, now, null);
        IssueEntity bug2 = issue("ES2-2", "Bug", "완료", "B", 1.0, now.minus(1, ChronoUnit.DAYS), now);
        IssueEntity task = issue("ES2-3", "작업", "완료", "A", 3.0, now, now);
        when(issueRepository.findAll()).thenReturn(List.of(bug1, bug2, task));

        var b = service.bugs(8);

        assertThat(b.bugCount()).isEqualTo(2);
        assertThat(b.totalCount()).isEqualTo(3);
        assertThat(b.openBugCount()).isEqualTo(1);
        assertThat(b.openBugs()).extracting("key").containsExactly("ES2-1");
        assertThat(b.weekly()).hasSize(8);
    }

    // --- issues filter ---

    @Test
    void issues_appliesFiltersAndKeyword() {
        Instant now = Instant.now();
        IssueEntity bug = issue("ES2-1", "버그", "진행 중", "Alice", 2.0, now, null);
        IssueEntity task = issue("ES2-2", "작업", "완료", "Bob", 1.0, now, now);
        when(issueRepository.findAllByOrderByJiraUpdatedDesc(any())).thenReturn(List.of(bug, task));

        assertThat(service.issues("진행 중", null, null, null)).extracting("key").containsExactly("ES2-1");
        assertThat(service.issues(null, "Bob", null, null)).extracting("key").containsExactly("ES2-2");
        assertThat(service.issues(null, null, "버그", null)).extracting("key").containsExactly("ES2-1");
        assertThat(service.issues(null, null, null, "es2-2")).extracting("key").containsExactly("ES2-2");
        assertThat(service.issues(null, null, null, "없는키워드")).isEmpty();
        assertThat(service.issues(null, null, null, null)).hasSize(2);
        // url 은 jira base 로 조립
        assertThat(service.issues(null, null, null, null).get(0).url())
                .startsWith("https://j.example.com/browse/");
    }

    // --- intent failures ---

    @Test
    void intentFailures_sortedDescAndLimited() {
        IntentFailureEntity old = new IntentFailureEntity("옛 입력", "LOW_CONFIDENCE", "d", "U1", "C1");
        IntentFailureEntity recent = new IntentFailureEntity("새 입력", "UNKNOWN_INTENT", "d", "U2", "C1");
        setFailedAt(old, Instant.now().minus(2, ChronoUnit.DAYS));
        setFailedAt(recent, Instant.now());
        when(intentFailureRepository.findAll()).thenReturn(List.of(old, recent));

        var rowsAll = service.intentFailures(50);
        assertThat(rowsAll).hasSize(2);
        assertThat(rowsAll.get(0).rawInput()).isEqualTo("새 입력");

        var rowsOne = service.intentFailures(1);
        assertThat(rowsOne).hasSize(1);
        assertThat(rowsOne.get(0).rawInput()).isEqualTo("새 입력");
    }

    // --- PR 현황 ---

    private com.jirabot.slack.client.dto.PullRequestInfo pr(int number, String title, String branch) {
        return new com.jirabot.slack.client.dto.PullRequestInfo(number, title,
                "https://github.com/x/evi/pull/" + number, "yhkim", false, branch,
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now());
    }

    @Test
    void prs_joinsJiraIssueByBranchKey_lowercaseToo() {
        Instant now = Instant.now();
        IssueEntity linked = issue("ES2-123", "버그", "진행 중", "Alice", 2.0, now, null);
        when(issueRepository.findByIssueKey("ES2-123")).thenReturn(Optional.of(linked));
        when(gitHubApiClient.listOpenPullRequests("evi"))
                .thenReturn(List.of(pr(1, "로그인 수정", "bugfix/es2-123-fix-login")));

        var board = service.prs();

        assertThat(board.enabled()).isTrue();
        assertThat(board.prs()).hasSize(1);
        var row = board.prs().get(0);
        assertThat(row.issueKey()).isEqualTo("ES2-123");      // 소문자 브랜치도 매칭
        assertThat(row.issueStatus()).isEqualTo("진행 중");
        assertThat(row.issueAssignee()).isEqualTo("Alice");
        assertThat(row.issueUrl()).isEqualTo("https://j.example.com/browse/ES2-123");
    }

    @Test
    void prs_noIssueKey_leavesIssueFieldsNull() {
        when(gitHubApiClient.listOpenPullRequests("evi"))
                .thenReturn(List.of(pr(2, "chore: bump deps", "chore/bump-deps")));

        var row = service.prs().prs().get(0);

        assertThat(row.issueKey()).isNull();
        assertThat(row.issueSummary()).isNull();
        assertThat(row.issueUrl()).isNull();
    }

    @Test
    void prs_titleFallback_whenBranchHasNoKey() {
        when(issueRepository.findByIssueKey("ES2-7")).thenReturn(Optional.empty());
        when(gitHubApiClient.listOpenPullRequests("evi"))
                .thenReturn(List.of(pr(3, "[ES2-7] 검색 개선", "improve-search")));

        var row = service.prs().prs().get(0);

        assertThat(row.issueKey()).isEqualTo("ES2-7");
        assertThat(row.issueSummary()).isNull();   // 로컬 DB 미보유 → 키/링크만
        assertThat(row.issueUrl()).contains("/browse/ES2-7");
    }

    @Test
    void prs_collectsInaccessibleRepos() {
        service = build(new com.jirabot.slack.config.GitHubProperties(
                "tok", "CryptoLabInc", List.of("evi", "locked"), "https://api.github.com"));
        when(gitHubApiClient.listOpenPullRequests("evi")).thenReturn(List.of(pr(1, "t", "b")));
        when(gitHubApiClient.listOpenPullRequests("locked"))
                .thenThrow(new com.jirabot.slack.client.GitHubAccessException("locked → HTTP 404"));

        var board = service.prs();

        assertThat(board.prs()).hasSize(1);
        assertThat(board.inaccessibleRepos()).containsExactly("locked");
    }

    @Test
    void prs_tokenDisabled_returnsDisabledBoard() {
        service = build(new com.jirabot.slack.config.GitHubProperties(
                "", "CryptoLabInc", List.of("evi"), "https://api.github.com"));

        var board = service.prs();

        assertThat(board.enabled()).isFalse();
        assertThat(board.prs()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(gitHubApiClient);
    }

    @Test
    void responseMetrics_computesStatsFromSuccessOnly_andListsRecent() {
        Instant now = Instant.now();
        // 성공 100/200/300/400/500ms + 실패 9999ms (실패는 통계 제외, failCount 로만 집계)
        List<ResponseMetricEntity> week = new java.util.ArrayList<>();
        long[] totals = {300, 100, 500, 200, 400};
        for (long t : totals) {
            week.add(new ResponseMetricEntity("issue_create", "ES2-1", "U1", "C1",
                    true, t, 50L, 10L, 100L, 5L, 20L, null, now));
        }
        week.add(new ResponseMetricEntity("issue_create", null, "U1", "C1",
                false, 9999, 50L, null, null, null, null, "JiraApiException", now));
        when(responseMetricRepository.findByStartedAtAfter(any())).thenReturn(week);
        when(responseMetricRepository.findTop50ByOrderByStartedAtDesc()).thenReturn(week);

        var board = service.responseMetrics();

        assertThat(board.weekly().count()).isEqualTo(5);
        assertThat(board.weekly().failCount()).isEqualTo(1);
        assertThat(board.weekly().avgMs()).isEqualTo(300);
        assertThat(board.weekly().p50Ms()).isEqualTo(300);   // nearest-rank: ceil(0.5*5)=3번째
        assertThat(board.weekly().p95Ms()).isEqualTo(500);
        assertThat(board.weekly().maxMs()).isEqualTo(500);
        assertThat(board.recent()).hasSize(6);
        assertThat(board.recent().get(5).errorType()).isEqualTo("JiraApiException");
    }

    @Test
    void responseMetrics_emptyData_returnsZeroStats() {
        when(responseMetricRepository.findByStartedAtAfter(any())).thenReturn(List.of());
        when(responseMetricRepository.findTop50ByOrderByStartedAtDesc()).thenReturn(List.of());

        var board = service.responseMetrics();

        assertThat(board.weekly().count()).isZero();
        assertThat(board.weekly().p95Ms()).isZero();
        assertThat(board.recent()).isEmpty();
    }

    private void setFailedAt(IntentFailureEntity e, Instant at) {
        try {
            var f = IntentFailureEntity.class.getDeclaredField("failedAt");
            f.setAccessible(true);
            f.set(e, at);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
