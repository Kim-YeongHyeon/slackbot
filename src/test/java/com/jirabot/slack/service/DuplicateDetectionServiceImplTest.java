package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DuplicateDetectionServiceImplTest {

    private IssueEntity issue(long id, String summary) {
        IssueEntity entity = new IssueEntity("KEY-" + id, summary, "버그", "해야 할 일",
                "해야 할 일", null, 3.0, "reporter", "desc", Instant.now(), Instant.now());
        setId(entity, id);
        return entity;
    }

    private void setId(IssueEntity entity, long id) {
        try {
            Field f = IssueEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void blankTitleReturnsEmpty() {
        IssueRepository repo = mock(IssueRepository.class);
        var svc = new DuplicateDetectionServiceImpl(repo);

        assertThat(svc.findSimilar(null)).isEmpty();
        assertThat(svc.findSimilar("   ")).isEmpty();
    }

    @Test
    void singleKeywordIsBelowThreshold() {
        IssueRepository repo = mock(IssueRepository.class);
        var svc = new DuplicateDetectionServiceImpl(repo);

        // 의미 있는 키워드가 1개 미만이면 검색 자체를 안 한다 (오탐 방지).
        assertThat(svc.findSimilar("페이지")).isEmpty();
    }

    @Test
    void twoMatchingKeywordsPromoteCandidate() {
        IssueRepository repo = mock(IssueRepository.class);
        when(repo.findBySummaryContaining(anyString())).thenReturn(List.of());
        when(repo.findBySummaryContaining("로그인")).thenReturn(List.of(issue(1, "로그인 페이지 500 에러")));
        when(repo.findBySummaryContaining("페이지")).thenReturn(List.of(issue(1, "로그인 페이지 500 에러")));
        var svc = new DuplicateDetectionServiceImpl(repo);

        List<IssueEntity> result = svc.findSimilar("로그인 페이지 응답 오류");

        assertThat(result).extracting(IssueEntity::getIssueKey).containsExactly("KEY-1");
    }

    @Test
    void singleMatchingKeywordIsFilteredOut() {
        IssueRepository repo = mock(IssueRepository.class);
        when(repo.findBySummaryContaining(anyString())).thenReturn(List.of());
        when(repo.findBySummaryContaining("로그인")).thenReturn(List.of(issue(1, "로그인 화면 깜빡임")));
        // 다른 키워드는 매칭 없음 → 단 하나의 키워드만 겹치므로 후보에서 제외돼야 한다.
        var svc = new DuplicateDetectionServiceImpl(repo);

        List<IssueEntity> result = svc.findSimilar("로그인 페이지 응답 오류");

        assertThat(result).isEmpty();
    }

    @Test
    void resultsSortedByMatchCountDescending() {
        IssueRepository repo = mock(IssueRepository.class);
        IssueEntity a = issue(1, "로그인 페이지 오류");
        IssueEntity b = issue(2, "로그인 응답");
        when(repo.findBySummaryContaining(anyString())).thenReturn(List.of());
        when(repo.findBySummaryContaining("로그인")).thenReturn(List.of(a, b));
        when(repo.findBySummaryContaining("페이지")).thenReturn(List.of(a));
        when(repo.findBySummaryContaining("응답")).thenReturn(List.of(b));
        when(repo.findBySummaryContaining("오류")).thenReturn(List.of(a));
        var svc = new DuplicateDetectionServiceImpl(repo);

        List<IssueEntity> result = svc.findSimilar("로그인 페이지 응답 오류 문제");

        assertThat(result).extracting(IssueEntity::getIssueKey).containsExactly("KEY-1", "KEY-2");
    }

    @Test
    void stopWordsAreExcluded() {
        IssueRepository repo = mock(IssueRepository.class);
        when(repo.findBySummaryContaining(anyString())).thenReturn(List.of());
        var svc = new DuplicateDetectionServiceImpl(repo);

        // 모든 토큰이 불용어 → 키워드가 없어 검색 자체가 일어나지 않는다.
        assertThat(svc.findSimilar("필요 합니다 부탁 해주세요")).isEmpty();
    }
}
