package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class ScrumReportStatisticsTest {

    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final UserMappingRepository userMappingRepository = mock(UserMappingRepository.class);
    private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
    private final JiraProperties jiraProps = new JiraProperties(
            "https://example.atlassian.net", "u@x.com", "token", "PROJ");
    private final ScrumReportServiceImpl service =
            new ScrumReportServiceImpl(issueRepository, userMappingRepository, slackNotifier, jiraProps);

    // --- progressBar tests ---

    @Test
    void progressBar_zero_allEmpty() {
        assertThat(service.progressBar(0.0)).isEqualTo("░".repeat(20));
    }

    @Test
    void progressBar_full_allFilled() {
        assertThat(service.progressBar(1.0)).isEqualTo("█".repeat(20));
    }

    @Test
    void progressBar_half() {
        String bar = service.progressBar(0.5);
        assertThat(bar).isEqualTo("██████████░░░░░░░░░░");
        assertThat(bar).hasSize(20);
    }

    @Test
    void progressBar_clampNegative() {
        assertThat(service.progressBar(-0.5)).isEqualTo("░".repeat(20));
    }

    @Test
    void progressBar_clampOverOne() {
        assertThat(service.progressBar(1.5)).isEqualTo("█".repeat(20));
    }

    @Test
    void progressBar_smallFraction() {
        // 0.04 * 20 = 0.8 → filled = 0
        assertThat(service.progressBar(0.04)).isEqualTo("░".repeat(20));
    }

    // --- generateStatisticsReport tests ---

    @Test
    void emptyDb_returnsMessage() throws ExecutionException, InterruptedException {
        when(issueRepository.countAndSumGroupByStatus()).thenReturn(List.of());

        String result = service.generateStatisticsReport().get();

        assertThat(result).contains("DB에 이슈가 없습니다");
        assertThat(result).contains("@지라 sync");
    }

    @Test
    void fullData_containsAllSections() throws ExecutionException, InterruptedException {
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);
        Instant twoDaysAgo = now.minus(2, ChronoUnit.DAYS);

        // DB aggregate query results: [statusCategory, count, sumSp]
        List<Object[]> statusStats = Arrays.<Object[]>asList(
                new Object[]{StatusCategory.DONE, 1L, 5.0},
                new Object[]{StatusCategory.IN_PROGRESS, 1L, 3.0},
                new Object[]{StatusCategory.TODO, 1L, 8.0}
        );
        when(issueRepository.countAndSumGroupByStatus()).thenReturn(statusStats);

        // Today completed issues
        IssueEntity completedIssue = createIssue("PROJ-1", "Complete task", StatusCategory.DONE, 5.0, "김영현", now, now);
        when(issueRepository.findCompletedSince(eq(StatusCategory.DONE), any(Instant.class)))
                .thenReturn(List.of(completedIssue));

        // In-progress issues
        IssueEntity inProgressIssue = createIssue("PROJ-2", "In progress task", StatusCategory.IN_PROGRESS, 3.0, "김영현", yesterday, null);
        when(issueRepository.findByStatusCategory(StatusCategory.IN_PROGRESS))
                .thenReturn(List.of(inProgressIssue));

        // Biggest uncompleted issue
        IssueEntity bigIssue = createIssue("PROJ-3", "Todo task", StatusCategory.TODO, 8.0, "최아록", twoDaysAgo, null);
        when(issueRepository.findTopUncompletedBySp(eq(StatusCategory.DONE), any()))
                .thenReturn(List.of(bigIssue));

        // Completed issues for burnup
        when(issueRepository.findByStatusCategory(StatusCategory.DONE))
                .thenReturn(List.of(completedIssue));

        String result = service.generateStatisticsReport().get();

        // Header — renamed from 스프린트 to 프로젝트
        assertThat(result).contains("프로젝트 통계 요약");
        // Progress section
        assertThat(result).contains("진척률");
        assertThat(result).contains("전체: 16 SP");
        assertThat(result).contains("완료: 5 SP");
        assertThat(result).contains("남음: 11 SP");
        assertThat(result).contains("31%");
        // Status breakdown
        assertThat(result).contains("상태별 현황");
        assertThat(result).contains("완료: 1건 (5 SP)");
        assertThat(result).contains("진행 중: 1건 (3 SP)");
        assertThat(result).contains("해야 할 일: 1건 (8 SP)");
        // Today resolved
        assertThat(result).contains("오늘 해결된 이슈");
        assertThat(result).contains("PROJ-1");
        // In progress
        assertThat(result).contains("현재 진행 중");
        assertThat(result).contains("PROJ-2");
        // Biggest issue
        assertThat(result).contains("가장 큰 이슈");
        assertThat(result).contains("PROJ-3");
        assertThat(result).contains("SP 8");
        // Burnup
        assertThat(result).contains("번업 (최근 7일)");
    }

    @Test
    void allSpZero_usesCountBased() throws ExecutionException, InterruptedException {
        List<Object[]> statusStats = Arrays.<Object[]>asList(
                new Object[]{StatusCategory.DONE, 1L, 0.0},
                new Object[]{StatusCategory.TODO, 1L, 0.0}
        );
        when(issueRepository.countAndSumGroupByStatus()).thenReturn(statusStats);
        when(issueRepository.findCompletedSince(eq(StatusCategory.DONE), any(Instant.class)))
                .thenReturn(List.of());
        when(issueRepository.findByStatusCategory(StatusCategory.IN_PROGRESS))
                .thenReturn(List.of());
        when(issueRepository.findTopUncompletedBySp(eq(StatusCategory.DONE), any()))
                .thenReturn(List.of());
        when(issueRepository.findByStatusCategory(StatusCategory.DONE))
                .thenReturn(List.of());

        String result = service.generateStatisticsReport().get();

        // Count-based progress
        assertThat(result).contains("전체: 2건");
        assertThat(result).contains("완료: 1건");
        assertThat(result).contains("50%");
    }

    @Test
    void noInProgressIssues_sectionSkipped() throws ExecutionException, InterruptedException {
        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);

        List<Object[]> statusStats = Arrays.<Object[]>asList(
                new Object[]{StatusCategory.DONE, 1L, 3.0},
                new Object[]{StatusCategory.TODO, 1L, 5.0}
        );
        when(issueRepository.countAndSumGroupByStatus()).thenReturn(statusStats);
        when(issueRepository.findCompletedSince(eq(StatusCategory.DONE), any(Instant.class)))
                .thenReturn(List.of());
        when(issueRepository.findByStatusCategory(StatusCategory.IN_PROGRESS))
                .thenReturn(List.of());
        when(issueRepository.findTopUncompletedBySp(eq(StatusCategory.DONE), any()))
                .thenReturn(List.of());
        when(issueRepository.findByStatusCategory(StatusCategory.DONE))
                .thenReturn(List.of(createIssue("PROJ-1", "Done", StatusCategory.DONE, 3.0, "A", twoDaysAgo, twoDaysAgo)));

        String result = service.generateStatisticsReport().get();

        assertThat(result).doesNotContain("현재 진행 중");
    }

    @Test
    void noTodayCompleted_showsNone() throws ExecutionException, InterruptedException {
        List<Object[]> statusStats = Arrays.<Object[]>asList(
                new Object[]{StatusCategory.DONE, 1L, 3.0}
        );
        when(issueRepository.countAndSumGroupByStatus()).thenReturn(statusStats);
        when(issueRepository.findCompletedSince(eq(StatusCategory.DONE), any(Instant.class)))
                .thenReturn(List.of());
        when(issueRepository.findByStatusCategory(StatusCategory.IN_PROGRESS))
                .thenReturn(List.of());
        when(issueRepository.findTopUncompletedBySp(eq(StatusCategory.DONE), any()))
                .thenReturn(List.of());
        Instant threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS);
        when(issueRepository.findByStatusCategory(StatusCategory.DONE))
                .thenReturn(List.of(createIssue("PROJ-1", "Old done", StatusCategory.DONE, 3.0, "A", threeDaysAgo, threeDaysAgo)));

        String result = service.generateStatisticsReport().get();

        assertThat(result).contains("오늘 해결된 이슈");
        assertThat(result).contains("(없음)");
    }

    @Test
    void nullSpIssues_treatedAsZeroSp() throws ExecutionException, InterruptedException {
        List<Object[]> statusStats = Arrays.<Object[]>asList(
                new Object[]{StatusCategory.IN_PROGRESS, 1L, 0.0}
        );
        when(issueRepository.countAndSumGroupByStatus()).thenReturn(statusStats);
        when(issueRepository.findCompletedSince(eq(StatusCategory.DONE), any(Instant.class)))
                .thenReturn(List.of());
        IssueEntity issue = createIssueNullSp("PROJ-1", "No SP", StatusCategory.IN_PROGRESS, "A", Instant.now());
        when(issueRepository.findByStatusCategory(StatusCategory.IN_PROGRESS))
                .thenReturn(List.of(issue));
        when(issueRepository.findTopUncompletedBySp(eq(StatusCategory.DONE), any()))
                .thenReturn(List.of());
        when(issueRepository.findByStatusCategory(StatusCategory.DONE))
                .thenReturn(List.of());

        String result = service.generateStatisticsReport().get();

        // Should use count-based since total SP = 0
        assertThat(result).contains("전체: 1건");
    }

    @Test
    void burnupChart_showsSevenDays() throws ExecutionException, InterruptedException {
        Instant now = Instant.now();
        IssueEntity doneIssue = createIssue("PROJ-1", "Done", StatusCategory.DONE, 5.0, "A", now, now);

        List<Object[]> statusStats = Arrays.<Object[]>asList(
                new Object[]{StatusCategory.DONE, 1L, 5.0}
        );
        when(issueRepository.countAndSumGroupByStatus()).thenReturn(statusStats);
        when(issueRepository.findCompletedSince(eq(StatusCategory.DONE), any(Instant.class)))
                .thenReturn(List.of(doneIssue));
        when(issueRepository.findByStatusCategory(StatusCategory.IN_PROGRESS))
                .thenReturn(List.of());
        when(issueRepository.findTopUncompletedBySp(eq(StatusCategory.DONE), any()))
                .thenReturn(List.of());
        when(issueRepository.findByStatusCategory(StatusCategory.DONE))
                .thenReturn(List.of(doneIssue));

        String result = service.generateStatisticsReport().get();

        // Count lines in burnup section — should have 7 date lines
        String burnupSection = result.substring(result.indexOf("번업 (최근 7일)"));
        long dateLines = burnupSection.lines()
                .filter(l -> l.matches(".*\\d{2}/\\d{2}.*"))
                .count();
        assertThat(dateLines).isEqualTo(7);
    }

    @Test
    void completedAtNull_fallsBackToJiraUpdated_showsApproxMarker() throws ExecutionException, InterruptedException {
        Instant now = Instant.now();
        // Issue with completedAt=null but jiraUpdated=today and status=완료
        IssueEntity issue = createIssue("PROJ-1", "Done no completedAt", StatusCategory.DONE, 3.0, "A", now, null);

        List<Object[]> statusStats = Arrays.<Object[]>asList(
                new Object[]{StatusCategory.DONE, 1L, 3.0}
        );
        when(issueRepository.countAndSumGroupByStatus()).thenReturn(statusStats);
        // DB query already handles fallback — return the issue as "today completed"
        when(issueRepository.findCompletedSince(eq(StatusCategory.DONE), any(Instant.class)))
                .thenReturn(List.of(issue));
        when(issueRepository.findByStatusCategory(StatusCategory.IN_PROGRESS))
                .thenReturn(List.of());
        when(issueRepository.findTopUncompletedBySp(eq(StatusCategory.DONE), any()))
                .thenReturn(List.of());
        when(issueRepository.findByStatusCategory(StatusCategory.DONE))
                .thenReturn(List.of(issue));

        String result = service.generateStatisticsReport().get();

        assertThat(result).contains("PROJ-1");
        assertThat(result).contains("오늘 해결된 이슈");
        // completedAt is null → should show (추정) marker
        assertThat(result).contains("(추정)");
    }

    @Test
    void completedAtPresent_noApproxMarker() throws ExecutionException, InterruptedException {
        Instant now = Instant.now();
        IssueEntity issue = createIssue("PROJ-1", "Done with completedAt", StatusCategory.DONE, 3.0, "A", now, now);

        List<Object[]> statusStats = Arrays.<Object[]>asList(
                new Object[]{StatusCategory.DONE, 1L, 3.0}
        );
        when(issueRepository.countAndSumGroupByStatus()).thenReturn(statusStats);
        when(issueRepository.findCompletedSince(eq(StatusCategory.DONE), any(Instant.class)))
                .thenReturn(List.of(issue));
        when(issueRepository.findByStatusCategory(StatusCategory.IN_PROGRESS))
                .thenReturn(List.of());
        when(issueRepository.findTopUncompletedBySp(eq(StatusCategory.DONE), any()))
                .thenReturn(List.of());
        when(issueRepository.findByStatusCategory(StatusCategory.DONE))
                .thenReturn(List.of(issue));

        String result = service.generateStatisticsReport().get();

        assertThat(result).contains("PROJ-1");
        // completedAt is present → no (추정) marker
        assertThat(result).doesNotContain("(추정)");
    }

    // --- Helper methods ---

    private IssueEntity createIssue(String key, String summary, String statusCategory,
                                     double sp, String assignee, Instant jiraUpdated,
                                     Instant completedAt) {
        IssueEntity issue = new IssueEntity(
                key, summary, "Story", statusCategory, statusCategory,
                assignee, sp, null, null, Instant.now().minus(10, ChronoUnit.DAYS), jiraUpdated);
        // STUDY: setCompletedAt()으로 테스트 데이터의 completedAt을 직접 설정.
        //        생성자가 "완료" 상태일 때 자동으로 Instant.now()를 설정하므로 테스트에서 재설정 필요.
        issue.setCompletedAt(completedAt);
        return issue;
    }

    private IssueEntity createIssueNullSp(String key, String summary, String statusCategory,
                                           String assignee, Instant jiraUpdated) {
        return new IssueEntity(
                key, summary, "Story", statusCategory, statusCategory,
                assignee, null, null, null, Instant.now().minus(10, ChronoUnit.DAYS), jiraUpdated);
    }
}
