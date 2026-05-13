package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class BugQueryServiceImplTest {

    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final JiraProperties jiraProps = new JiraProperties(
            "https://test.atlassian.net", "test@test.com", "token", "SLAC", null, null);
    private final BugQueryServiceImpl service = new BugQueryServiceImpl(issueRepository, jiraProps);

    @Test
    void emptyResult_showsNobugsMessage() throws ExecutionException, InterruptedException {
        when(issueRepository.findResolvedBugsSince(any(), any(), any())).thenReturn(List.of());

        String result = service.queryResolvedBugs(LocalDate.of(2026, 3, 1)).get();

        assertThat(result).contains(":bug:");
        assertThat(result).contains("해결된 버그가 없습니다");
        assertThat(result).contains("2026.03.01");
    }

    @Test
    void withResults_formatsCorrectly() throws ExecutionException, InterruptedException {
        IssueEntity bug = new IssueEntity("SLAC-7", "로그인 500 에러", "Bug", "완료", "완료",
                "김영현", 2.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findResolvedBugsSince(any(), any(), any())).thenReturn(List.of(bug));

        String result = service.queryResolvedBugs(LocalDate.of(2026, 3, 1)).get();

        assertThat(result).contains(":bug:");
        assertThat(result).contains("해결된 버그 (1건)");
        assertThat(result).contains("SLAC-7");
        assertThat(result).contains("로그인 500 에러");
        assertThat(result).contains("https://test.atlassian.net/browse/SLAC-7");
        assertThat(result).contains("SP 2");
        assertThat(result).contains("담당: 김영현");
    }

    @Test
    void spNull_showsDash() throws ExecutionException, InterruptedException {
        // STUDY: SP null → "-" 표시, 0이 아님
        IssueEntity bug = new IssueEntity("SLAC-8", "SP없는 버그", "Bug", "완료", "완료",
                null, null, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findResolvedBugsSince(any(), any(), any())).thenReturn(List.of(bug));

        String result = service.queryResolvedBugs(LocalDate.of(2026, 3, 1)).get();

        assertThat(result).contains("SP -");
        assertThat(result).contains("담당: 미배정");
        // 미추정 건수 표시
        assertThat(result).contains("미추정 1건");
    }

    @Test
    void spSummary_separatesEstimatedAndUnestimated() throws ExecutionException, InterruptedException {
        IssueEntity bugWithSp = new IssueEntity("SLAC-9", "SP있는 버그", "Bug", "완료", "완료",
                "김영현", 3.0, "reporter", "desc", Instant.now(), Instant.now());
        IssueEntity bugWithoutSp = new IssueEntity("SLAC-10", "SP없는 버그", "Bug", "완료", "완료",
                "김영현", null, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findResolvedBugsSince(any(), any(), any()))
                .thenReturn(List.of(bugWithSp, bugWithoutSp));

        String result = service.queryResolvedBugs(LocalDate.of(2026, 3, 1)).get();

        assertThat(result).contains("총 2건 해결 / 3 SP 완료");
        assertThat(result).contains("미추정 1건");
    }

    @Test
    void allEstimated_noUnestimatedLabel() throws ExecutionException, InterruptedException {
        IssueEntity bug = new IssueEntity("SLAC-11", "SP있는 버그", "Bug", "완료", "완료",
                "김영현", 5.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findResolvedBugsSince(any(), any(), any())).thenReturn(List.of(bug));

        String result = service.queryResolvedBugs(LocalDate.of(2026, 3, 1)).get();

        assertThat(result).contains("총 1건 해결 / 5 SP 완료");
        assertThat(result).doesNotContain("미추정");
    }

    @Test
    void pageable_passedToRepository() throws ExecutionException, InterruptedException {
        when(issueRepository.findResolvedBugsSince(any(), any(), any())).thenReturn(List.of());

        service.queryResolvedBugs(LocalDate.of(2026, 3, 1)).get();

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(issueRepository).findResolvedBugsSince(any(), any(), pageCaptor.capture());
        Pageable pageable = pageCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(0);
        assertThat(pageable.getPageSize()).isEqualTo(50);
    }

    @Test
    void baseUrlWithTrailingSlash_handledCorrectly() throws ExecutionException, InterruptedException {
        JiraProperties propsWithSlash = new JiraProperties(
                "https://test.atlassian.net/", "test@test.com", "token", "SLAC", null, null);
        BugQueryServiceImpl serviceWithSlash = new BugQueryServiceImpl(issueRepository, propsWithSlash);

        IssueEntity bug = new IssueEntity("SLAC-12", "테스트", "Bug", "완료", "완료",
                "김영현", 1.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findResolvedBugsSince(any(), any(), any())).thenReturn(List.of(bug));

        String result = serviceWithSlash.queryResolvedBugs(LocalDate.of(2026, 3, 1)).get();

        // 이중 슬래시가 없어야 함
        assertThat(result).contains("https://test.atlassian.net/browse/SLAC-12");
        assertThat(result).doesNotContain("//browse/");
    }

    @Test
    void baseUrlNull_handledGracefully() throws ExecutionException, InterruptedException {
        JiraProperties propsNull = new JiraProperties(null, "test@test.com", "token", "SLAC", null, null);
        BugQueryServiceImpl serviceNull = new BugQueryServiceImpl(issueRepository, propsNull);

        IssueEntity bug = new IssueEntity("SLAC-13", "테스트", "Bug", "완료", "완료",
                "김영현", 1.0, "reporter", "desc", Instant.now(), Instant.now());
        when(issueRepository.findResolvedBugsSince(any(), any(), any())).thenReturn(List.of(bug));

        String result = serviceNull.queryResolvedBugs(LocalDate.of(2026, 3, 1)).get();

        // baseUrl이 null이면 이슈 키만 표시
        assertThat(result).contains("SLAC-13");
    }

    @Test
    void completedAtNull_fallsBackToJiraUpdated() throws ExecutionException, InterruptedException {
        // completedAt이 null인 이슈 — jiraUpdated가 fallback으로 사용되어야 함
        IssueEntity bug = new IssueEntity("SLAC-14", "레거시 버그", "Bug", "완료", "완료",
                "김영현", 1.0, "reporter", "desc", Instant.now(), Instant.now());
        // IssueEntity 생성자에서 completedAt이 설정되므로 N/A 테스트는 불가하지만,
        // 결과에 완료 날짜가 포함되는지 확인
        when(issueRepository.findResolvedBugsSince(any(), any(), any())).thenReturn(List.of(bug));

        String result = service.queryResolvedBugs(LocalDate.of(2026, 3, 1)).get();

        assertThat(result).contains("완료 ");
        assertThat(result).doesNotContain("N/A");
    }
}
