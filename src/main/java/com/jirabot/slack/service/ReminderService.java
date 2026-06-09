package com.jirabot.slack.service;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.SprintInfo;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// STUDY: 리마인더 스케줄러 (2종).
//        - @ConditionalOnProperty(matchIfMissing=true): reminder.enabled 가 true 이거나 미설정이면 빈 생성.
//          명시적 false 이면 빈 자체가 안 만들어져 두 스케줄러 모두 미동작. effectivelyEnabled() 로 한 번 더 가드.
//        - 발송 대상은 Slack 명령 `리마인더 on` 으로 opt-in 한 사용자.
//        - 이슈 소유자 = 담당자(assignee), 담당자가 없으면 보고자(reporter). reporter 는 sync 후 Jira displayName.
//        (1) runDaily   — 평일 09:00, "현재 스프린트" 미해결 이슈. 단, 격주 전체 발송 월요일엔 생략(중복 방지).
//        (2) runBiweekly — 격주 월 09:30, "전체" 미해결 이슈(스프린트+백로그). anchor 기준 짝수 주차 월요일에만.
@Component
@ConditionalOnProperty(prefix = "reminder", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final UserMappingRepository userMappingRepository;
    private final IssueRepository issueRepository;
    private final SlackNotifier slackNotifier;
    private final JiraApiClient jira;
    private final ReminderProperties reminderProps;
    private final String jiraBaseUrl;

    public ReminderService(UserMappingRepository userMappingRepository,
                           IssueRepository issueRepository,
                           SlackNotifier slackNotifier,
                           JiraApiClient jira,
                           ReminderProperties reminderProps,
                           JiraProperties jiraProps) {
        this.userMappingRepository = userMappingRepository;
        this.issueRepository = issueRepository;
        this.slackNotifier = slackNotifier;
        this.jira = jira;
        this.reminderProps = reminderProps;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    // STUDY: 일일 리마인더 — 현재 활성 스프린트의 미해결 이슈만.
    //        @Scheduled 진입점은 "오늘" 만 주입하고 로직은 package-private 오버로드로 위임 → 날짜 의존 테스트가 결정적.
    @Scheduled(cron = "${reminder.cron:0 0 9 * * MON-FRI}", zone = "${reminder.zone:Asia/Seoul}")
    public void runDaily() {
        runDaily(LocalDate.now(ZoneId.of(reminderProps.zone())));
    }

    void runDaily(LocalDate today) {
        if (!reminderProps.effectivelyEnabled()) {
            log.info("Daily reminder skipped: reminder.enabled=false");
            return;
        }
        if (isBiweeklyFullToday(today)) {
            // 격주 전체 리마인더가 09:30 에 나가는 날 — 09:00 스프린트 리마인더는 생략해 중복 DM 방지.
            log.info("Daily reminder skipped: biweekly full reminder day ({})", today);
            return;
        }
        Optional<SprintInfo> active = jira.getActiveSprint();
        if (active.isEmpty()) {
            log.info("Daily reminder skipped: no active sprint");
            return;
        }
        List<IssueEntity> candidates = issueRepository
                .findByStatusCategoryNotAndSprintId(StatusCategory.DONE, active.get().id());
        dispatch(candidates, "현재 스프린트");
    }

    // STUDY: 격주 전체 리마인더 — 매주 월 09:30 점화되나 anchor parity 로 격주만 실제 발송.
    @Scheduled(cron = "${reminder.biweekly-cron:0 30 9 * * MON}", zone = "${reminder.zone:Asia/Seoul}")
    public void runBiweekly() {
        runBiweekly(LocalDate.now(ZoneId.of(reminderProps.zone())));
    }

    void runBiweekly(LocalDate today) {
        if (!reminderProps.effectivelyEnabled()) {
            log.info("Biweekly reminder skipped: reminder.enabled=false");
            return;
        }
        if (!isBiweeklyFullToday(today)) {
            log.debug("Biweekly reminder skipped: off-week or before anchor ({})", today);
            return;
        }
        List<IssueEntity> candidates = issueRepository.findByStatusCategoryNot(StatusCategory.DONE);
        dispatch(candidates, "전체");
    }

    // STUDY: 오늘이 "격주 전체 리마인더 발송일"인지 판단.
    //        anchor(기준 월요일)를 0주차로 보고, 월요일 && anchor 이후 && (주차 차이 % 2 == 0) 이면 발송.
    //        anchor 도 월요일이라 두 월요일 사이 ChronoUnit.WEEKS 는 항상 정수 주차가 된다.
    boolean isBiweeklyFullToday(LocalDate today) {
        if (today.getDayOfWeek() != DayOfWeek.MONDAY) {
            return false;
        }
        LocalDate anchor = reminderProps.anchor();
        if (today.isBefore(anchor)) {
            return false;
        }
        long weeks = ChronoUnit.WEEKS.between(anchor, today);
        return weeks % 2 == 0;
    }

    // STUDY: 후보 이슈들을 구독자별로 그룹핑해 DM. 0건 사용자는 생략.
    void dispatch(List<IssueEntity> candidates, String scopeLabel) {
        List<UserMappingEntity> subscribers = userMappingRepository.findByReminderEnabledTrue();
        log.info("Reminder dispatch scope='{}' subscribers={} candidates={}",
                scopeLabel, subscribers.size(), candidates.size());
        if (subscribers.isEmpty()) {
            return;
        }

        // displayName → 구독자. 동명이인은 첫 등록자에 귀속(현 운영 규모상 충돌 가능성 낮음).
        Map<String, UserMappingEntity> byName = new HashMap<>();
        for (UserMappingEntity s : subscribers) {
            String name = s.getJiraDisplayName();
            if (name != null && !name.isBlank()) {
                byName.putIfAbsent(name, s);
            }
        }
        if (byName.isEmpty()) {
            return;
        }

        // 소유자(담당자 우선, 없으면 보고자) 가 구독자인 이슈만 그룹핑. 입력 순서 보존.
        Map<UserMappingEntity, List<IssueEntity>> byUser = new LinkedHashMap<>();
        for (IssueEntity issue : candidates) {
            String owner = resolveOwner(issue);
            if (owner == null) {
                continue;
            }
            UserMappingEntity user = byName.get(owner);
            if (user == null) {
                continue;
            }
            byUser.computeIfAbsent(user, k -> new ArrayList<>()).add(issue);
        }

        for (Map.Entry<UserMappingEntity, List<IssueEntity>> e : byUser.entrySet()) {
            sendOne(e.getKey(), e.getValue(), scopeLabel);
        }
    }

    // STUDY: 이슈 소유자 = 담당자(assignee) 우선, 비어있으면 보고자(reporter).
    private String resolveOwner(IssueEntity issue) {
        String assignee = issue.getAssignee();
        if (assignee != null && !assignee.isBlank()) {
            return assignee;
        }
        String reporter = issue.getReporter();
        if (reporter != null && !reporter.isBlank()) {
            return reporter;
        }
        return null;
    }

    private void sendOne(UserMappingEntity user, List<IssueEntity> issues, String scopeLabel) {
        try {
            slackNotifier.sendDirectMessage(user.getSlackUserId(), buildMessage(issues, scopeLabel));
        } catch (Exception e) {
            // 한 사용자 실패가 전체 발송을 막지 않도록 warn 만.
            log.warn("Reminder DM failed for slackUserId={}: {}", user.getSlackUserId(), e.toString());
        }
    }

    String buildMessage(List<IssueEntity> openIssues, String scopeLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append(":sunny: 좋은 아침입니다. ").append(scopeLabel)
                .append(" 미해결 이슈 ").append(openIssues.size()).append("건이 있습니다.\n");
        for (IssueEntity issue : openIssues) {
            String url = issueLink(issue.getIssueKey());
            String status = (issue.getStatusCategory() == null || issue.getStatusCategory().isBlank())
                    ? "-" : issue.getStatusCategory();
            sb.append("• <").append(url).append("|").append(issue.getIssueKey()).append("> ")
                    .append(issue.getSummary()).append(" (").append(status).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    private String issueLink(String key) {
        if (jiraBaseUrl.isEmpty()) return key;
        return jiraBaseUrl + "/browse/" + key;
    }
}
