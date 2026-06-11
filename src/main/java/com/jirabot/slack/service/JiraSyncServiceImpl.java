package com.jirabot.slack.service;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.dto.SprintInfo;
import com.jirabot.slack.client.dto.SprintIssue;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.repository.IssueRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        log.info(fullSync());
    }

    @Override
    public String fullSync() {
        // STUDY: sprint/backlog 를 한 번만 fetch 해서 sync 와 prune 이 공유한다.
        //        기존엔 pruneDeletedIssues 가 같은 목록을 다시 fetch 해 Jira 왕복이 2배(페이지네이션 포함)였다.
        Optional<SprintInfo> active = jira.getActiveSprint();
        List<SprintIssue> sprintIssues = active.map(s -> jira.getSprintIssues(s.id())).orElse(List.of());
        List<SprintIssue> backlogIssues = jira.getBacklogIssues();

        String activeMsg = active.isEmpty()
                ? "활성 스프린트가 없어 동기화를 건너뜁니다."
                : syncActiveSprint(active.get(), sprintIssues);
        String backlogMsg = syncBacklog(backlogIssues);

        Set<String> seen = new HashSet<>();
        sprintIssues.forEach(i -> seen.add(i.key()));
        backlogIssues.forEach(i -> seen.add(i.key()));
        int pruned = pruneDeletedIssues(seen);

        lastSyncAt = Instant.now();
        String prune = pruned > 0 ? String.format("\n:wastebasket: Jira 에서 삭제된 이슈 %d건 정리", pruned) : "";
        return activeMsg + "\n" + backlogMsg + prune;
    }

    // STUDY: 검색 선행 sync 의 TTL 게이트용 타임스탬프. volatile — 스케줄러/검색이 서로 다른 스레드에서
    //        읽고 쓰므로 가시성만 보장하면 된다. 동시에 두 검색이 둘 다 sync 해도 멱등이라 락은 불필요.
    private volatile Instant lastSyncAt;

    @Override
    public void syncIfStale(java.time.Duration maxAge) {
        Instant last = lastSyncAt;
        if (last != null && Instant.now().isBefore(last.plus(maxAge))) {
            log.debug("Sync skipped — last sync {} is within TTL {}", last, maxAge);
            return;
        }
        syncActiveSprint();
        syncBacklog();
        lastSyncAt = Instant.now();
    }

    // STUDY: Jira 에서 삭제된 이슈 정리. 활성 스프린트+백로그 fetch 에 없는 "미완료" 로컬 이슈만 후보로 보고,
    //        Jira 에 직접 조회해 404 인 것만 삭제. 완료 이슈는 리포트/이력용으로 보존하므로 제외한다.
    //        fetch 가 maxResults 로 잘려도 존재 확인(issueExists)이 200 을 주면 보존돼 오삭제를 막는다.
    @Override
    public int pruneDeletedIssues() {
        Set<String> seen = new HashSet<>();
        Optional<SprintInfo> active = jira.getActiveSprint();
        active.ifPresent(s -> jira.getSprintIssues(s.id()).forEach(i -> seen.add(i.key())));
        jira.getBacklogIssues().forEach(i -> seen.add(i.key()));
        return pruneDeletedIssues(seen);
    }

    // fullSync 가 이미 fetch 한 키 집합을 재사용하는 내부 경로 (Jira 재왕복 없음).
    int pruneDeletedIssues(Set<String> seen) {
        if (seen.isEmpty()) {
            // 활성 스프린트도 없고 백로그도 비었음 → 비정상/일시 오류 가능. mass-delete 방지 위해 skip.
            log.warn("Prune skipped: no issues seen from Jira (active+backlog empty)");
            return 0;
        }

        List<IssueEntity> openIssues = issueRepository.findByStatusCategoryNot(StatusCategory.DONE);
        List<String> toDelete = new ArrayList<>();
        for (IssueEntity issue : openIssues) {
            if (seen.contains(issue.getIssueKey())) {
                continue;  // 현재 Jira 에 존재(활성/백로그)
            }
            // 후보: fetch 에 없음 → Jira 직접 확인. 404 만 삭제, 200/불확실은 보존.
            if (!jira.issueExists(issue.getIssueKey())) {
                toDelete.add(issue.getIssueKey());
            }
        }
        if (!toDelete.isEmpty()) {
            issueRepository.deleteByIssueKeyIn(toDelete);
            log.info("Pruned {} Jira-deleted issues: {}", toDelete.size(), toDelete);
        }
        return toDelete.size();
    }

    @Override
    public String syncActiveSprint() {
        Optional<SprintInfo> activeSprint = jira.getActiveSprint();
        if (activeSprint.isEmpty()) {
            return "활성 스프린트가 없어 동기화를 건너뜁니다.";
        }
        SprintInfo sprint = activeSprint.get();
        return syncActiveSprint(sprint, jira.getSprintIssues(sprint.id()));
    }

    // fullSync 의 공유-fetch 경로용 내부 구현.
    String syncActiveSprint(SprintInfo sprint, List<SprintIssue> jiraIssues) {
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
                entity.setReporter(ji.reporter());
                entity.setParentKey(ji.parentKey());
                entity.setSubtask(ji.subtask());
                // STUDY: 동기화 시마다 스프린트 정보를 갱신. 이슈가 다른 스프린트로 이동하면 자동 반영.
                entity.setSprint(sprint.id(), sprint.name());
                updated++;
            } else {
                IssueEntity entity = new IssueEntity(
                        ji.key(), ji.summary(), ji.issueType(), ji.status(),
                        ji.statusCategory(), ji.assignee(), ji.storyPoint(),
                        ji.reporter(), null,
                        parseInstant(ji.created()), parseInstant(ji.updated()));
                entity.setParentKey(ji.parentKey());
                entity.setSubtask(ji.subtask());
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

    @Override
    public String syncBacklog() {
        return syncBacklog(jira.getBacklogIssues());
    }

    // STUDY: Jira 보드의 백로그 뷰와 로컬 DB 의 sprint_id IS NULL 집합을 일치시킨다.
    //        - 기존 entity 가 옛 sprint_id 를 들고 있더라도 clearSprint 로 NULL 화 (sprint→backlog 이동 케이스)
    //        - 이번 sync 에 안 잡힌 sprint_id IS NULL 항목은 stale 로 간주해 삭제 (보드 필터 제외/완료 이동 등)
    String syncBacklog(List<SprintIssue> backlogIssues) {
        int created = 0;
        int updated = 0;

        for (SprintIssue ji : backlogIssues) {
            Optional<IssueEntity> existing = issueRepository.findByIssueKey(ji.key());
            if (existing.isPresent()) {
                IssueEntity entity = existing.get();
                entity.updateFrom(
                        ji.summary(), ji.issueType(), ji.status(), ji.statusCategory(),
                        ji.assignee(), ji.storyPoint(),
                        parseInstant(ji.updated()));
                entity.setReporter(ji.reporter());
                entity.setParentKey(ji.parentKey());
                entity.setSubtask(ji.subtask());
                entity.clearSprint();
                updated++;
            } else {
                IssueEntity entity = new IssueEntity(
                        ji.key(), ji.summary(), ji.issueType(), ji.status(),
                        ji.statusCategory(), ji.assignee(), ji.storyPoint(),
                        ji.reporter(), null,
                        parseInstant(ji.created()), parseInstant(ji.updated()));
                entity.setParentKey(ji.parentKey());
                entity.setSubtask(ji.subtask());
                issueRepository.save(entity);
                created++;
            }
        }

        int removed = 0;
        if (!backlogIssues.isEmpty()) {
            List<String> currentKeys = backlogIssues.stream().map(SprintIssue::key).toList();
            removed = issueRepository.deleteStaleBacklog(currentKeys);
        }

        String result = String.format("Backlog 동기화 완료: %d건 생성, %d건 업데이트, %d건 정리 (전체 %d건)",
                created, updated, removed, backlogIssues.size());
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
