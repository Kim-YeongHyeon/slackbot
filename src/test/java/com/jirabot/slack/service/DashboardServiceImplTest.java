package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.entity.IntentFailureEntity;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IntentFailureRepository;
import com.jirabot.slack.repository.IssueRepository;
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
    private JiraSyncService jiraSyncService;
    private DashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        issueRepository = mock(IssueRepository.class);
        userMappingRepository = mock(UserMappingRepository.class);
        intentFailureRepository = mock(IntentFailureRepository.class);
        jiraSyncService = mock(JiraSyncService.class);
        when(jiraSyncService.lastSyncAt()).thenReturn(Optional.empty());
        ReminderProperties reminderProps = new ReminderProperties(
                true, "0 0 9 * * MON-FRI", "Asia/Seoul", "0 30 9 * * MON", "2026-06-22", 7);
        JiraProperties jiraProps = new JiraProperties("https://j.example.com", "u@x", "t", "ES2",
                null, new JiraProperties.IssueTypes("버그", "작업", "하위 작업"));
        service = new DashboardServiceImpl(issueRepository, userMappingRepository,
                intentFailureRepository, jiraSyncService, reminderProps, jiraProps);
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
