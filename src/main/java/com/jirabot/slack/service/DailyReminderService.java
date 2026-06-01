package com.jirabot.slack.service;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// STUDY: 일일 리마인더 스케줄러.
//        - @ConditionalOnProperty(havingValue="true", matchIfMissing=true) — reminder.enabled 가
//          true 이거나 미설정인 경우 빈을 생성. 명시적 false 이면 빈 자체가 만들어지지 않아 스케줄러도 미동작.
//          ReminderProperties.effectivelyEnabled() 도 같은 의미를 코드 레벨에서 한 번 더 확인한다.
//        - 실제 발송 대상은 Slack 명령 `리마인더 on` 으로 opt-in 한 사용자 (UserMappingEntity.reminderEnabled=true).
//        - 0건인 사용자는 발송 자체를 생략하여 DM 소음을 최소화.
//        - 구독자별 N+1 쿼리를 피하기 위해 미해결 이슈를 단일 IN 쿼리로 한 번에 로드한 뒤 assignee 별로 그룹핑한다.
@Component
@ConditionalOnProperty(prefix = "reminder", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DailyReminderService {

    private static final Logger log = LoggerFactory.getLogger(DailyReminderService.class);

    private final UserMappingRepository userMappingRepository;
    private final IssueRepository issueRepository;
    private final SlackNotifier slackNotifier;
    private final ReminderProperties reminderProps;
    private final String jiraBaseUrl;

    public DailyReminderService(UserMappingRepository userMappingRepository,
                                IssueRepository issueRepository,
                                SlackNotifier slackNotifier,
                                ReminderProperties reminderProps,
                                JiraProperties jiraProps) {
        this.userMappingRepository = userMappingRepository;
        this.issueRepository = issueRepository;
        this.slackNotifier = slackNotifier;
        this.reminderProps = reminderProps;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Scheduled(cron = "${reminder.cron:0 0 9 * * MON-FRI}", zone = "${reminder.zone:Asia/Seoul}")
    public void run() {
        // STUDY: 부팅 후 사용자가 yml 을 false 로 바꿔도 일관된 동작을 보장하기 위한 second-line guard.
        if (!reminderProps.effectivelyEnabled()) {
            log.info("Daily reminder skipped: reminder.enabled=false");
            return;
        }
        List<UserMappingEntity> subscribers = userMappingRepository.findByReminderEnabledTrue();
        log.info("Daily reminder tick: subscribers={}", subscribers.size());
        if (subscribers.isEmpty()) {
            return;
        }

        // STUDY: 구독자별 jiraDisplayName 목록을 모아 IN 절 한 번으로 미해결 이슈를 일괄 로드한다.
        //        구독자 수가 커져도 DB 쿼리는 1회 + 사용자 메타 조회 1회로 고정.
        List<String> assignees = subscribers.stream()
                .map(UserMappingEntity::getJiraDisplayName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        Map<String, List<IssueEntity>> byAssignee = new HashMap<>();
        if (!assignees.isEmpty()) {
            List<IssueEntity> open = issueRepository
                    .findByAssigneeInAndStatusCategoryNot(assignees, StatusCategory.DONE);
            for (IssueEntity issue : open) {
                byAssignee.computeIfAbsent(issue.getAssignee(), k -> new ArrayList<>()).add(issue);
            }
        }

        for (UserMappingEntity user : subscribers) {
            List<IssueEntity> openIssues = byAssignee.getOrDefault(user.getJiraDisplayName(), List.of());
            if (openIssues.isEmpty()) {
                log.debug("No open issues for slackUserId={}", user.getSlackUserId());
                continue;
            }
            sendOne(user, openIssues);
        }
    }

    private void sendOne(UserMappingEntity user, List<IssueEntity> openIssues) {
        try {
            String message = buildMessage(openIssues);
            slackNotifier.sendDirectMessage(user.getSlackUserId(), message);
        } catch (Exception e) {
            // 한 사용자 실패가 전체 발송을 막지 않도록 warn 만.
            log.warn("Reminder DM failed for slackUserId={}: {}", user.getSlackUserId(), e.toString());
        }
    }

    String buildMessage(List<IssueEntity> openIssues) {
        StringBuilder sb = new StringBuilder();
        sb.append(":sunny: 좋은 아침입니다. 미해결 이슈 ").append(openIssues.size()).append("건이 있습니다.\n");
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
