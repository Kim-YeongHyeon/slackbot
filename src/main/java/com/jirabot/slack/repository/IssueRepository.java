package com.jirabot.slack.repository;

import com.jirabot.slack.entity.IssueEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// STUDY: JpaRepository<Entity, ID타입>을 상속하면 CRUD + 페이징 + 정렬 메서드가 자동 제공된다.
//        메서드 이름 규칙(findBy...)으로 쿼리가 자동 생성됨.
public interface IssueRepository extends JpaRepository<IssueEntity, Long> {

    Optional<IssueEntity> findByIssueKey(String issueKey);

    // STUDY: JPQL의 LOWER + LIKE로 대소문자 무시 키워드 검색.
    //        완료된 이슈는 제외하여 활성 이슈만 비교 대상으로 삼는다.
    @Query("SELECT i FROM IssueEntity i WHERE LOWER(i.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "AND i.statusCategory <> '완료'")
    List<IssueEntity> findBySummaryContaining(@Param("keyword") String keyword);

    List<IssueEntity> findByAssigneeContaining(String name);

    List<IssueEntity> findByStatusCategoryNot(String statusCategory);

    @Query("SELECT i FROM IssueEntity i WHERE i.jiraUpdated >= :since")
    List<IssueEntity> findUpdatedSince(@Param("since") Instant since);

    Optional<IssueEntity> findBySlackChannelAndSlackThreadTs(String slackChannel, String slackThreadTs);

    // STUDY: findBySummaryContaining은 완료 이슈를 제외하지만, 검색 기능은 모든 상태의 이슈를 포함한다.
    @Query("SELECT i FROM IssueEntity i WHERE LOWER(i.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY i.jiraUpdated DESC")
    List<IssueEntity> searchByKeyword(@Param("keyword") String keyword);
}
