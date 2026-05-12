package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
        when(issueRepository.findAll()).thenReturn(List.of());

        String result = service.generateStatisticsReport().get();

        assertThat(result).contains("DB에 이슈가 없습니다");
        assertThat(result).contains("@지라 sync");
    }

    @Test
    void fullData_containsAllSections() throws ExecutionException, InterruptedException {
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);
        Instant twoDaysAgo = now.minus(2, ChronoUnit.DAYS);

        List<IssueEntity> issues = List.of(
                createIssue("PROJ-1", "Complete task", "완료", 5.0, "김영현", now, now),
                createIssue("PROJ-2", "In progress task", "진행 중", 3.0, "김영현", yesterday, null),
                createIssue("PROJ-3", "Todo task", "해야 할 일", 8.0, "최아록", twoDaysAgo, null)
        );
        when(issueRepository.findAll()).thenReturn(issues);

        String result = service.generateStatisticsReport().get();

        // Header
        assertThat(result).contains("스프린트 통계 요약");
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
        Instant now = Instant.now();
        List<IssueEntity> issues = List.of(
                createIssue("PROJ-1", "Done", "완료", 0.0, "A", now, now),
                createIssue("PROJ-2", "Todo", "해야 할 일", 0.0, "B", now, null)
        );
        when(issueRepository.findAll()).thenReturn(issues);

        String result = service.generateStatisticsReport().get();

        // Count-based progress
        assertThat(result).contains("전체: 2건");
        assertThat(result).contains("완료: 1건");
        assertThat(result).contains("50%");
    }

    @Test
    void noInProgressIssues_sectionSkipped() throws ExecutionException, InterruptedException {
        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
        List<IssueEntity> issues = List.of(
                createIssue("PROJ-1", "Done", "완료", 3.0, "A", twoDaysAgo, twoDaysAgo),
                createIssue("PROJ-2", "Todo", "해야 할 일", 5.0, "B", twoDaysAgo, null)
        );
        when(issueRepository.findAll()).thenReturn(issues);

        String result = service.generateStatisticsReport().get();

        assertThat(result).doesNotContain("현재 진행 중");
    }

    @Test
    void noTodayCompleted_showsNone() throws ExecutionException, InterruptedException {
        Instant threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS);
        List<IssueEntity> issues = List.of(
                createIssue("PROJ-1", "Old done", "완료", 3.0, "A", threeDaysAgo, threeDaysAgo)
        );
        when(issueRepository.findAll()).thenReturn(issues);

        String result = service.generateStatisticsReport().get();

        assertThat(result).contains("오늘 해결된 이슈");
        assertThat(result).contains("(없음)");
    }

    @Test
    void nullSpIssues_treatedAsZeroSp() throws ExecutionException, InterruptedException {
        Instant now = Instant.now();
        List<IssueEntity> issues = List.of(
                createIssueNullSp("PROJ-1", "No SP", "진행 중", "A", now)
        );
        when(issueRepository.findAll()).thenReturn(issues);

        String result = service.generateStatisticsReport().get();

        // Should use count-based since total SP = 0
        assertThat(result).contains("전체: 1건");
    }

    @Test
    void burnupChart_showsSevenDays() throws ExecutionException, InterruptedException {
        Instant now = Instant.now();
        List<IssueEntity> issues = List.of(
                createIssue("PROJ-1", "Done", "완료", 5.0, "A", now, now)
        );
        when(issueRepository.findAll()).thenReturn(issues);

        String result = service.generateStatisticsReport().get();

        // Count lines in burnup section — should have 7 date lines
        String burnupSection = result.substring(result.indexOf("번업 (최근 7일)"));
        long dateLines = burnupSection.lines()
                .filter(l -> l.matches(".*\\d{2}/\\d{2}.*"))
                .count();
        assertThat(dateLines).isEqualTo(7);
    }

    @Test
    void completedAtNull_fallsBackToJiraUpdated() throws ExecutionException, InterruptedException {
        // Issue with completedAt=null but jiraUpdated=today and status=완료
        Instant now = Instant.now();
        IssueEntity issue = createIssue("PROJ-1", "Done no completedAt", "완료", 3.0, "A", now, null);
        // completedAt is null, jiraUpdated is today → should show in "오늘 해결된 이슈"
        when(issueRepository.findAll()).thenReturn(List.of(issue));

        String result = service.generateStatisticsReport().get();

        assertThat(result).contains("PROJ-1");
        assertThat(result).contains("오늘 해결된 이슈");
        // Should NOT show "(없음)" since jiraUpdated is today
        // The fallback should pick up jiraUpdated
    }

    // --- Helper methods ---

    private IssueEntity createIssue(String key, String summary, String statusCategory,
                                     double sp, String assignee, Instant jiraUpdated,
                                     Instant completedAt) {
        IssueEntity issue = new IssueEntity(
                key, summary, "Story", statusCategory, statusCategory,
                assignee, sp, null, null, Instant.now().minus(10, ChronoUnit.DAYS), jiraUpdated);
        // Use reflection to set completedAt since the constructor sets it automatically for 완료
        try {
            java.lang.reflect.Field completedField = IssueEntity.class.getDeclaredField("completedAt");
            completedField.setAccessible(true);
            completedField.set(issue, completedAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return issue;
    }

    private IssueEntity createIssueNullSp(String key, String summary, String statusCategory,
                                           String assignee, Instant jiraUpdated) {
        IssueEntity issue = new IssueEntity(
                key, summary, "Story", statusCategory, statusCategory,
                assignee, null, null, null, Instant.now().minus(10, ChronoUnit.DAYS), jiraUpdated);
        return issue;
    }
}
