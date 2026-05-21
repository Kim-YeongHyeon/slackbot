package com.jirabot.slack.repository;

import com.jirabot.slack.entity.ProcessedJiraChangelog;
import org.springframework.data.jpa.repository.JpaRepository;

// STUDY: changelog.id 중복 처리 차단. existsById 와 save 만 사용한다.
public interface ProcessedJiraChangelogRepository extends JpaRepository<ProcessedJiraChangelog, String> {
}
