package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IssueSearchServiceImplTest {

    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
    private final JiraSyncService jiraSyncService = mock(JiraSyncService.class);
    private final JiraProperties jiraProps =
            new JiraProperties("https://jira.example.com", "test@example.com", "token", "SLAC", null, null);

    private IssueSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IssueSearchServiceImpl(issueRepository, claudeApiClient, jiraSyncService, jiraProps);
    }

    @Test
    void searchByKeyword_returnsFormattedResults() throws ExecutionException, InterruptedException {
        IssueEntity issue = new IssueEntity("SLAC-7", "로그인 에러", "Bug", "진행 중", "진행 중",
                "김영현", 3.0, "reporter", null, Instant.now(), Instant.now());
        when(issueRepository.searchByKeyword(eq("로그인"), any())).thenReturn(List.of(issue));

        String result = service.searchByKeyword("로그인").get();

        assertThat(result)
                .contains(":mag: \"로그인\" 검색 결과 (1건)")
                .contains("SLAC-7")
                .contains("로그인 에러")
                .contains("담당: 김영현");
        verify(issueRepository).searchByKeyword(eq("로그인"), any());
    }

    @Test
    void searchByKeyword_emptyResults_showsEmptyMessage() throws ExecutionException, InterruptedException {
        when(issueRepository.searchByKeyword(eq("없는키워드"), any())).thenReturn(Collections.emptyList());

        String result = service.searchByKeyword("없는키워드").get();

        assertThat(result).isEqualTo(":mag: \"없는키워드\" 검색 결과가 없습니다.");
    }

    @Test
    void searchByKeyword_displaysMaxAndOverflow() throws ExecutionException, InterruptedException {
        // STUDY: Pageable로 DB 레벨에서 50건 제한. 여기서는 DB가 반환한 결과의 포맷팅을 검증.
        List<IssueEntity> manyIssues = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(i -> new IssueEntity("SLAC-" + i, "이슈 " + i, "Bug", "진행 중", "진행 중",
                        "담당자", 1.0, "reporter", null, Instant.now(), Instant.now()))
                .toList();
        when(issueRepository.searchByKeyword(eq("이슈"), any())).thenReturn(manyIssues);

        String result = service.searchByKeyword("이슈").get();

        // 15건 반환, 10건 표시, 나머지 5건 overflow
        assertThat(result)
                .contains(":mag: \"이슈\" 검색 결과 (15건)")
                .contains("외 5건이 더 있습니다.");
    }

    @Test
    void searchSemantic_sonnetReturnsResults() throws ExecutionException, InterruptedException {
        IssueEntity issue1 = new IssueEntity("SLAC-7", "로그인 500 에러", "Bug", "진행 중", "진행 중",
                "김영현", 3.0, "reporter", "로그인 페이지에서 500 에러", Instant.now(), Instant.now());
        IssueEntity issue2 = new IssueEntity("SLAC-8", "결제 금액 표시", "Bug", "완료", "완료",
                "최아록", 2.0, "reporter", null, Instant.now(), Instant.now());

        when(issueRepository.findAll()).thenReturn(List.of(issue1, issue2));
        when(claudeApiClient.searchIssues(any(), any())).thenReturn(List.of("SLAC-7"));

        String result = service.searchSemantic("로그인 에러 알려줘", "로그인").get();

        assertThat(result).contains("SLAC-7").contains("로그인 500 에러");
        verify(claudeApiClient).searchIssues(any(), any());
    }

    @Test
    void searchSemantic_sonnetEmpty_fallsBackToKeyword() throws ExecutionException, InterruptedException {
        IssueEntity issue = new IssueEntity("SLAC-7", "로그인 에러", "Bug", "진행 중", "진행 중",
                "김영현", 3.0, "reporter", null, Instant.now(), Instant.now());

        when(issueRepository.findAll()).thenReturn(List.of(issue));
        when(claudeApiClient.searchIssues(any(), any())).thenReturn(Collections.emptyList());
        when(issueRepository.searchByKeyword(eq("로그인"), any())).thenReturn(List.of(issue));

        String result = service.searchSemantic("로그인 관련 이슈", "로그인").get();

        assertThat(result).contains("SLAC-7");
        verify(issueRepository).searchByKeyword(eq("로그인"), any());
    }

    @Test
    void searchSemantic_sonnetThrows_fallsBackToKeyword() throws ExecutionException, InterruptedException {
        IssueEntity issue = new IssueEntity("SLAC-7", "로그인 에러", "Bug", "진행 중", "진행 중",
                "김영현", 3.0, "reporter", null, Instant.now(), Instant.now());

        when(issueRepository.findAll()).thenReturn(List.of(issue));
        when(claudeApiClient.searchIssues(any(), any())).thenThrow(new RuntimeException("Sonnet timeout"));
        when(issueRepository.searchByKeyword(eq("로그인"), any())).thenReturn(List.of(issue));

        String result = service.searchSemantic("로그인 관련 이슈", "로그인").get();

        assertThat(result).contains("SLAC-7");
        verify(issueRepository).searchByKeyword(eq("로그인"), any());
    }

    @Test
    void searchSemantic_noIssuesInDb_showsSyncMessage() throws ExecutionException, InterruptedException {
        when(issueRepository.findAll()).thenReturn(Collections.emptyList());

        String result = service.searchSemantic("로그인", "로그인").get();

        assertThat(result).contains("sync");
        verify(claudeApiClient, never()).searchIssues(any(), any());
    }

    @Test
    void escapeWildcards_escapesSpecialCharacters() {
        assertThat(service.escapeWildcards("test")).isEqualTo("test");
        assertThat(service.escapeWildcards("100%")).isEqualTo("100\\%");
        assertThat(service.escapeWildcards("a_b")).isEqualTo("a\\_b");
        assertThat(service.escapeWildcards("a\\b")).isEqualTo("a\\\\b");
        assertThat(service.escapeWildcards("100%_test\\")).isEqualTo("100\\%\\_test\\\\");
    }

    @Test
    void formatSearchResults_noResults_showsEmptyMessage() {
        String result = service.formatSearchResults("테스트", Collections.emptyList());
        assertThat(result).isEqualTo(":mag: \"테스트\" 검색 결과가 없습니다.");
    }

    @Test
    void formatSearchResults_withResults_showsFormattedList() {
        List<IssueEntity> issues = List.of(
                new IssueEntity("SLAC-7", "로그인 에러", "Bug", "진행 중", "진행 중",
                        "김영현", 3.0, "reporter", null, Instant.now(), Instant.now()),
                new IssueEntity("SLAC-8", "로그인 UI 개선", "Story", "할 일", "할 일",
                        null, null, "reporter", null, Instant.now(), Instant.now()));

        String result = service.formatSearchResults("로그인", issues);
        assertThat(result)
                .contains(":mag: \"로그인\" 검색 결과 (2건)")
                .contains("<https://jira.example.com/browse/SLAC-7|SLAC-7>")
                .contains("담당: 김영현")
                .contains("SP 3")
                .contains("<https://jira.example.com/browse/SLAC-8|SLAC-8>")
                .contains("담당: 미배정")
                .contains("SP -");
    }

    @Test
    void formatSearchResults_moreThanMax_showsOverflowMessage() {
        List<IssueEntity> issues = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(i -> new IssueEntity("SLAC-" + i, "이슈 " + i, "Bug", "진행 중", "진행 중",
                        "담당자", 1.0, "reporter", null, Instant.now(), Instant.now()))
                .toList();

        String result = service.formatSearchResults("이슈", issues);
        assertThat(result)
                .contains(":mag: \"이슈\" 검색 결과 (12건)")
                .contains("외 2건이 더 있습니다.")
                .doesNotContain("SLAC-11")
                .doesNotContain("SLAC-12");
    }
}
