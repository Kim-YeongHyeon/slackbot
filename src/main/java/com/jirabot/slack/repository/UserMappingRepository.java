package com.jirabot.slack.repository;

import com.jirabot.slack.entity.UserMappingEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMappingRepository extends JpaRepository<UserMappingEntity, Long> {

    Optional<UserMappingEntity> findBySlackUserId(String slackUserId);

    Optional<UserMappingEntity> findByJiraAccountId(String jiraAccountId);

    Optional<UserMappingEntity> findByJiraDisplayName(String jiraDisplayName);
}
