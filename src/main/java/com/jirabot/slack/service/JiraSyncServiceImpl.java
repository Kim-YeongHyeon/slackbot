package com.jirabot.slack.service;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.dto.SprintInfo;
import com.jirabot.slack.client.dto.SprintIssue;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// STUDY: @Transactional을 클래스 레벨에 붙이면 모든 public 메서드가 트랜잭션 내에서 실행된다.
//        동기화는 여러 이슈를 한 트랜잭션으로 묶어 일관성을 보장.
@Service
@Transactional
public class JiraSyncServiceImpl implements JiraSyncService {

    private static final Logger log = LoggerFactory.getLogger(JiraSyncServiceImpl.class);

    private final JiraApiClient jira;
    private final IssueRepository issueRepository;

    public JiraSyncServiceImpl(JiraApiClient jira, IssueRepository issueRepository) {
        this.jira = jira;
        this.issueRepository = issueRepository;
    }

    // STUDY: cron = "초 분 시 일 월 요일". 매일 오전 8시(KST)에 자동 실행.
    //        zone으로 타임존 지정 안 하면 서버 시스템 시간 기준.
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")
    public void scheduledSync() {
        log.info("Daily scheduled sync started");
        syncActiveSprint();
    }

    @Override
    public String syncActiveSprint() {
        Optional<SprintInfo> activeSprint = jira.getActiveSprint();
        if (activeSprint.isEmpty()) {
            return "활성 스프린트가 없어 동기화를 건너뜁니다.";
        }

        SprintInfo sprint = activeSprint.get();
        List<SprintIssue> jiraIssues = jira.getSprintIssues(sprint.id());

        int created = 0;
        int updated = 0;

        for (SprintIssue ji : jiraIssues) {
            Optional<IssueEntity> existing = issueRepository.findByIssueKey(ji.key());

            if (existing.isPresent()) {
                IssueEntity entity = existing.get();
                entity.updateFrom(
                        ji.summary(), ji.issueType(), ji.status(), ji.statusCategory(),
                        ji.assignee(), ji.storyPoint(),
                        parseInstant(ji.updated()));
                // STUDY: 동기화 시마다 스프린트 정보를 갱신. 이슈가 다른 스프린트로 이동하면 자동 반영.
                entity.setSprint(sprint.id(), sprint.name());
                updated++;
            } else {
                IssueEntity entity = new IssueEntity(
                        ji.key(), ji.summary(), ji.issueType(), ji.status(),
                        ji.statusCategory(), ji.assignee(), ji.storyPoint(),
                        null, null,
                        parseInstant(ji.created()), parseInstant(ji.updated()));
                entity.setSprint(sprint.id(), sprint.name());
                issueRepository.save(entity);
                created++;
            }
        }

        String result = String.format("스프린트 '%s' 동기화 완료: %d건 생성, %d건 업데이트 (전체 %d건)",
                sprint.name(), created, updated, jiraIssues.size());
        log.info(result);
        return result;
    }

    private Instant parseInstant(String isoDatetime) {
        if (isoDatetime == null || isoDatetime.isBlank()) return null;
        try {
            return Instant.parse(isoDatetime);
        } catch (Exception e) {
            return null;
        }
    }
}
