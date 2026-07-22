package com.jirabot.slack.repository;

import com.jirabot.slack.entity.UserMappingEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMappingRepository extends JpaRepository<UserMappingEntity, Long> {

    Optional<UserMappingEntity> findBySlackUserId(String slackUserId);

    Optional<UserMappingEntity> findByJiraAccountId(String jiraAccountId);

    Optional<UserMappingEntity> findByJiraDisplayName(String jiraDisplayName);

    // STUDY: 한국어 이름은 slack_display_name 에 있다(예: slack=최아록 / jira=choiahrok).
    //        담당자 지정 시 "최아록"으로도 찾을 수 있게 Slack 표시명 정확 일치 조회.
    Optional<UserMappingEntity> findBySlackDisplayName(String slackDisplayName);

    // STUDY: 두 표시명 컬럼 모두에 대소문자 무시 부분일치 — "아록", "Song Hyeop" 같은 축약/부분 입력 대응.
    //        결과가 2건 이상이면 호출부가 모호 처리(후보 나열).
    @org.springframework.data.jpa.repository.Query(
            "SELECT u FROM UserMappingEntity u WHERE lower(u.slackDisplayName) LIKE lower(CONCAT('%', :name, '%')) "
                    + "OR lower(u.jiraDisplayName) LIKE lower(CONCAT('%', :name, '%'))")
    List<UserMappingEntity> searchByAnyDisplayName(
            @org.springframework.data.repository.query.Param("name") String name);

    List<UserMappingEntity> findByReminderEnabledTrue();
}
