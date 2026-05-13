package com.jirabot.slack.repository;

import com.jirabot.slack.entity.IssueEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // STUDY: completedAt이 null인 완료 이슈(sync 이전에 완료된 것)는 jiraUpdated를 fallback으로 사용한다.
    //        jiraUpdated는 완료 후에도 댓글/수정으로 갱신될 수 있어 정확한 완료 시점이 아닐 수 있다 (근사치).
    //        COALESCE로 정렬 시에도 동일한 fallback 로직을 적용한다.
    // STUDY: issueType을 파라미터로 받아 Jira 사이트 언어/프로젝트 설정에 따라 유연하게 대응한다.
    //        한국어 Jira: "버그", 영어 Jira: "Bug" 등. 호출자가 결정.
    @Query("SELECT i FROM IssueEntity i WHERE LOWER(i.issueType) = LOWER(:issueType) " +
           "AND i.statusCategory = '완료' " +
           "AND (i.completedAt >= :since OR (i.completedAt IS NULL AND i.jiraUpdated >= :since)) " +
           "ORDER BY COALESCE(i.completedAt, i.jiraUpdated) DESC")
    List<IssueEntity> findResolvedBugsSince(@Param("issueType") String issueType,
                                            @Param("since") Instant since, Pageable pageable);

    // STUDY: GROUP BY로 상태별 건수/SP 합계를 DB에서 집계. findAll() → Java 스트림 집계 대비
    //        네트워크 전송량, 메모리, CPU 모두 절감. Object[]의 인덱스: [0]=statusCategory, [1]=count, [2]=sumSp.
    @Query("SELECT i.statusCategory, COUNT(i), COALESCE(SUM(i.storyPoint), 0) " +
           "FROM IssueEntity i GROUP BY i.statusCategory")
    List<Object[]> countAndSumGroupByStatus();

    List<IssueEntity> findByStatusCategory(String statusCategory);

    // STUDY: 완료 이슈 중 특정 시점 이후 완료된 것만 조회. completedAt 우선, null이면 jiraUpdated fallback.
    //        COALESCE로 정렬까지 동일 로직 적용.
    @Query("SELECT i FROM IssueEntity i WHERE i.statusCategory = :status " +
           "AND (i.completedAt >= :since OR (i.completedAt IS NULL AND i.jiraUpdated >= :since)) " +
           "ORDER BY COALESCE(i.completedAt, i.jiraUpdated) DESC")
    List<IssueEntity> findCompletedSince(@Param("status") String status, @Param("since") Instant since);

    // STUDY: 미완료 이슈 중 SP가 가장 큰 것. Pageable로 TOP 1만 가져온다.
    @Query("SELECT i FROM IssueEntity i WHERE i.statusCategory <> :doneStatus " +
           "AND i.storyPoint IS NOT NULL AND i.storyPoint > 0 " +
           "ORDER BY i.storyPoint DESC")
    List<IssueEntity> findTopUncompletedBySp(@Param("doneStatus") String doneStatus, Pageable pageable);

    // --- 스프린트 필터 버전 ---

    // STUDY: 스프린트별 통계를 위한 집계 쿼리. sprintId로 필터링하여 해당 스프린트 이슈만 집계.
    @Query("SELECT i.statusCategory, COUNT(i), COALESCE(SUM(i.storyPoint), 0) " +
           "FROM IssueEntity i WHERE i.sprintId = :sprintId GROUP BY i.statusCategory")
    List<Object[]> countAndSumGroupByStatusAndSprint(@Param("sprintId") int sprintId);

    List<IssueEntity> findByStatusCategoryAndSprintId(String statusCategory, int sprintId);

    @Query("SELECT i FROM IssueEntity i WHERE i.sprintId = :sprintId AND i.statusCategory = :status " +
           "AND (i.completedAt >= :since OR (i.completedAt IS NULL AND i.jiraUpdated >= :since)) " +
           "ORDER BY COALESCE(i.completedAt, i.jiraUpdated) DESC")
    List<IssueEntity> findCompletedSinceInSprint(@Param("status") String status,
                                                  @Param("since") Instant since,
                                                  @Param("sprintId") int sprintId);

    @Query("SELECT i FROM IssueEntity i WHERE i.sprintId = :sprintId " +
           "AND i.statusCategory <> :doneStatus " +
           "AND i.storyPoint IS NOT NULL AND i.storyPoint > 0 " +
           "ORDER BY i.storyPoint DESC")
    List<IssueEntity> findTopUncompletedBySpInSprint(@Param("doneStatus") String doneStatus,
                                                      @Param("sprintId") int sprintId,
                                                      Pageable pageable);

    // STUDY: 가장 최근에 동기화된 스프린트의 정보를 가져온다. 통계 요청 시 활성 스프린트를 식별.
    @Query("SELECT i.sprintId, i.sprintName FROM IssueEntity i " +
           "WHERE i.sprintId IS NOT NULL GROUP BY i.sprintId, i.sprintName " +
           "ORDER BY MAX(i.syncedAt) DESC")
    List<Object[]> findLatestSprintInfo(Pageable pageable);

    List<IssueEntity> findBySprintId(Integer sprintId);

    // STUDY: 백로그 sync 후 Jira 보드에서 사라진 stale 항목 정리용. sprint_id IS NULL 인 로컬 이슈 중
    //        이번 sync 에 포함되지 않은 키를 일괄 삭제한다. @Modifying 은 SELECT 가 아닌 쿼리에 필수.
    //        봇이 직접 생성한 이슈 (slack_channel IS NOT NULL) 는 아직 board backlog API 가 인덱싱하기
    //        전이라도 절대 지우지 않는다 — Slack 스레드 연결이 끊기는 사고 방지.
    @Modifying
    @Query("DELETE FROM IssueEntity i WHERE i.sprintId IS NULL AND i.slackChannel IS NULL " +
           "AND i.issueKey NOT IN :keys")
    int deleteStaleBacklog(@Param("keys") Collection<String> keys);
}
