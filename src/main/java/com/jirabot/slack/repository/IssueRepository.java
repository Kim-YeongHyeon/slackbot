package com.jirabot.slack.repository;

import com.jirabot.slack.entity.IssueEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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
    // STUDY: summary와 description 모두에서 검색하여 검색 범위를 확장한다. OR 절로 두 컬럼을 함께 매칭.
    // STUDY: ESCAPE '\\' 절을 추가하여 사용자 입력의 %, _, \ 를 리터럴로 처리한다.
    // STUDY: Pageable 파라미터로 DB 레벨에서 결과 수를 제한한다. Java에서 잘라내는 것보다 효율적.
    @Query("SELECT i FROM IssueEntity i WHERE (LOWER(i.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR LOWER(i.description) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\') ORDER BY i.jiraUpdated DESC")
    List<IssueEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
