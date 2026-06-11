package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.repository.IssueRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// STUDY: v0.0.21 — 키워드별 N회 LIKE 쿼리에서 "미완료 1회 조회 + Java 집계" 로 변경.
//        테스트도 findByStatusCategoryNot 단일 mock 으로 후보 풀을 주고 동일 의미(2개 이상 키워드 겹침)를 검증한다.
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
        verify(repo, never()).findByStatusCategoryNot(StatusCategory.DONE);
    }

    @Test
    void singleKeywordIsBelowThreshold() {
        IssueRepository repo = mock(IssueRepository.class);
        var svc = new DuplicateDetectionServiceImpl(repo);

        // 의미 있는 키워드가 1개 미만이면 검색 자체를 안 한다 (오탐 방지).
        assertThat(svc.findSimilar("페이지")).isEmpty();
        verify(repo, never()).findByStatusCategoryNot(StatusCategory.DONE);
    }

    @Test
    void twoMatchingKeywordsPromoteCandidate() {
        IssueRepository repo = mock(IssueRepository.class);
        when(repo.findByStatusCategoryNot(StatusCategory.DONE))
                .thenReturn(List.of(issue(1, "로그인 페이지 500 에러")));
        var svc = new DuplicateDetectionServiceImpl(repo);

        List<IssueEntity> result = svc.findSimilar("로그인 페이지 응답 오류");

        assertThat(result).extracting(IssueEntity::getIssueKey).containsExactly("KEY-1");
        // 키워드 수와 무관하게 DB 조회는 1회.
        verify(repo).findByStatusCategoryNot(StatusCategory.DONE);
    }

    @Test
    void singleMatchingKeywordIsFilteredOut() {
        IssueRepository repo = mock(IssueRepository.class);
        // "로그인" 만 겹치고 페이지/응답/오류는 없음 → 후보 제외.
        when(repo.findByStatusCategoryNot(StatusCategory.DONE))
                .thenReturn(List.of(issue(1, "로그인 화면 깜빡임")));
        var svc = new DuplicateDetectionServiceImpl(repo);

        List<IssueEntity> result = svc.findSimilar("로그인 페이지 응답 오류");

        assertThat(result).isEmpty();
    }

    @Test
    void resultsSortedByMatchCountDescending() {
        IssueRepository repo = mock(IssueRepository.class);
        IssueEntity a = issue(1, "로그인 페이지 오류");  // 로그인+페이지+오류 = 3개 매칭
        IssueEntity b = issue(2, "로그인 응답");          // 로그인+응답 = 2개 매칭
        when(repo.findByStatusCategoryNot(StatusCategory.DONE)).thenReturn(List.of(b, a));
        var svc = new DuplicateDetectionServiceImpl(repo);

        List<IssueEntity> result = svc.findSimilar("로그인 페이지 응답 오류 문제");

        assertThat(result).extracting(IssueEntity::getIssueKey).containsExactly("KEY-1", "KEY-2");
    }

    @Test
    void matchingIsCaseInsensitive_likeOldLowerLikeQuery() {
        IssueRepository repo = mock(IssueRepository.class);
        // 기존 LOWER(summary) LIKE 와 동일하게 대소문자 무시로 매칭돼야 한다.
        when(repo.findByStatusCategoryNot(StatusCategory.DONE))
                .thenReturn(List.of(issue(1, "Login PAGE error")));
        var svc = new DuplicateDetectionServiceImpl(repo);

        List<IssueEntity> result = svc.findSimilar("login page 깨짐");

        assertThat(result).extracting(IssueEntity::getIssueKey).containsExactly("KEY-1");
    }

    @Test
    void stopWordsAreExcluded() {
        IssueRepository repo = mock(IssueRepository.class);
        var svc = new DuplicateDetectionServiceImpl(repo);

        // 모든 토큰이 불용어 → 키워드가 없어 검색 자체가 일어나지 않는다.
        assertThat(svc.findSimilar("필요 합니다 부탁 해주세요")).isEmpty();
        verify(repo, never()).findByStatusCategoryNot(StatusCategory.DONE);
    }
}
