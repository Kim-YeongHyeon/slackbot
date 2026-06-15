package com.jirabot.slack.repository;

import com.jirabot.slack.entity.GitHubUserMappingEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubUserMappingRepository extends JpaRepository<GitHubUserMappingEntity, Long> {

    // STUDY: GitHub 로그인은 대소문자 무시 비교(GitHub 자체가 대소문자 구분 안 함).
    Optional<GitHubUserMappingEntity> findByGithubLoginIgnoreCase(String githubLogin);
}
