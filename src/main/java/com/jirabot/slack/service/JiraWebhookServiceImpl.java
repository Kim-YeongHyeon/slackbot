package com.jirabot.slack.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.JiraWebhookProperties;
import com.jirabot.slack.config.JiraWebhookProperties.NotifyTrigger;
import com.jirabot.slack.config.NotifyProperties;
import com.jirabot.slack.config.NotifyProperties.MentionMode;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.ProcessedJiraChangelog;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.ProcessedJiraChangelogRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// STUDY: Jira → 봇 webhook 처리의 핵심.
//        1) idempotency 기록(processed_jira_changelog) 을 save-first 로 잡아 race 차단
//        → 2) 봇 이슈 여부 확인 → 3) trigger 평가
//        → 4) 메시지 빌드 + Slack 전송 → 5) DB 일관성 (IssueEntity.updateFrom).
//        예외는 모두 본 메서드에서 catch + warn 한다 — 컨트롤러는 인증 통과 후 항상 200 으로 회신하므로
//        Jira 재시도 폭주를 막는 책임이 컨트롤러에 있고, 서비스는 처리 실패가 호출 스택을 깨지 않도록 보장.
@Service
public class JiraWebhookServiceImpl implements JiraWebhookService {

    private static final Logger log = LoggerFactory.getLogger(JiraWebhookServiceImpl.class);

    private final ObjectMapper objectMapper;
    private final IssueRepository issueRepository;
    private final UserMappingRepository userMappingRepository;
    private final ProcessedJiraChangelogRepository processedRepo;
    private final SlackNotifier slackNotifier;
    private final JiraWebhookProperties webhookProps;
    private final NotifyProperties notifyProps;
    private final JiraProperties jiraProps;
    private final JiraStatusCategoryResolver resolver;

    public JiraWebhookServiceImpl(ObjectMapper objectMapper,
                                  IssueRepository issueRepository,
                                  UserMappingRepository userMappingRepository,
                                  ProcessedJiraChangelogRepository processedRepo,
                                  SlackNotifier slackNotifier,
                                  JiraWebhookProperties webhookProps,
                                  NotifyProperties notifyProps,
                                  JiraProperties jiraProps,
                                  JiraStatusCategoryResolver resolver) {
        this.objectMapper = objectMapper;
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
        this.processedRepo = processedRepo;
        this.slackNotifier = slackNotifier;
        this.webhookProps = webhookProps;
        this.notifyProps = notifyProps;
        this.jiraProps = jiraProps;
        this.resolver = resolver;
    }

    @Override
    public void process(String jsonBody) {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);

            String changelogId = root.path("changelog").path("id").asText(null);
            if (changelogId == null || changelogId.isBlank()) {
                log.debug("Webhook ignored: changelog.id missing");
                return;
            }

            // STUDY: idempotency 를 save-first 로 잡는다. saveAndFlush 가 즉시 INSERT 를 발생시키고,
            //        PK 충돌(DataIntegrityViolationException)이면 다른 요청이 먼저 기록한 것이므로
            //        본 요청은 중복 처리하지 않고 종료한다. existsById + save 두 호출 사이의 race window 가 사라진다.
            //        또한 이 가드를 모든 early return 보다 먼저 두면 비봇 이슈/스레드 없음 같은 케이스에서도
            //        같은 changelogId 가 재전송될 때 다시 처리되는 일이 없다.
            try {
                processedRepo.saveAndFlush(new ProcessedJiraChangelog(changelogId));
            } catch (DataIntegrityViolationException duplicate) {
                log.info("Webhook duplicate ignored changelogId={}", changelogId);
                return;
            }

            String issueKey = root.path("issue").path("key").asText(null);
            if (issueKey == null || issueKey.isBlank()) {
                log.debug("Webhook ignored: issue.key missing");
                return;
            }

            Optional<IssueEntity> found = issueRepository.findByIssueKey(issueKey);
            if (found.isEmpty()) {
                log.debug("Webhook ignored: issue {} not tracked locally", issueKey);
                return;
            }
            IssueEntity issue = found.get();
            if (issue.getSlackChannel() == null || issue.getSlackThreadTs() == null) {
                log.debug("Webhook ignored: issue {} has no Slack thread", issueKey);
                return;
            }

            List<JiraChangelog> items = parseChangelogItems(root.path("changelog").path("items"));
            if (items.isEmpty()) {
                return;
            }

            // STUDY: 트리거 평가 후 메시지를 만들고 알림 발송. updateFrom 으로 DB 일관성도 함께 갱신.
            boolean shouldNotify = shouldNotify(items);
            if (shouldNotify) {
                String message = buildMessage(root, issue, items);
                slackNotifier.postThreadReply(issue.getSlackChannel(), issue.getSlackThreadTs(), message);
                applyIssueUpdate(root, issue);
                log.info("Webhook notified key={} changelogId={}", issueKey, changelogId);
            } else {
                log.debug("Webhook below threshold notify-on={} items={}", webhookProps.notifyOn(), items.size());
            }
        } catch (Exception e) {
            // STUDY: 처리 중 어떤 예외가 나더라도 컨트롤러는 200 을 회신하므로 Jira 재시도 폭주를 막는다.
            //        idempotency 기록은 이미 save-first 에서 남았으므로, 같은 changelogId 가 다시 와도 위에서 차단된다.
            //        다만 본 요청의 처리는 누락되므로 운영자가 warn 로그로 인지할 수 있도록 stack trace 와 함께 기록.
            log.warn("Jira webhook processing failed: {}", e.toString(), e);
        }
    }

    private List<JiraChangelog> parseChangelogItems(JsonNode itemsNode) {
        List<JiraChangelog> items = new ArrayList<>();
        if (!itemsNode.isArray()) return items;
        for (JsonNode item : itemsNode) {
            items.add(new JiraChangelog(
                    item.path("field").asText(""),
                    item.path("fromString").isNull() ? null : item.path("fromString").asText(null),
                    item.path("toString").isNull() ? null : item.path("toString").asText(null)));
        }
        return items;
    }

    boolean shouldNotify(List<JiraChangelog> items) {
        NotifyTrigger mode = webhookProps.notifyOn() == null
                ? NotifyTrigger.STATUS_AND_ASSIGNEE : webhookProps.notifyOn();
        for (JiraChangelog item : items) {
            String field = item.field();
            switch (mode) {
                case STATUS -> {
                    if ("status".equals(field)) return true;
                }
                case STATUS_CATEGORY -> {
                    if ("status".equals(field)
                            && !resolver.categoryOf(item.fromValue()).equals(resolver.categoryOf(item.toValue()))) {
                        return true;
                    }
                }
                case DONE_ONLY -> {
                    if ("status".equals(field)
                            && resolver.isDone(item.toValue()) && !resolver.isDone(item.fromValue())) {
                        return true;
                    }
                }
                case STATUS_AND_ASSIGNEE -> {
                    if ("status".equals(field) || "assignee".equals(field)) return true;
                }
            }
        }
        return false;
    }

    private String buildMessage(JsonNode root, IssueEntity issue, List<JiraChangelog> items) {
        String issueUrl = issueLink(issue.getIssueKey());
        String summary = root.path("issue").path("fields").path("summary").asText(issue.getSummary());

        StringBuilder sb = new StringBuilder();
        sb.append(":arrows_counterclockwise: <").append(issueUrl).append("|")
                .append(issue.getIssueKey()).append("> ").append(summary).append("\n");

        JiraChangelog statusItem = items.stream().filter(i -> "status".equals(i.field())).findFirst().orElse(null);
        JiraChangelog assigneeItem = items.stream().filter(i -> "assignee".equals(i.field())).findFirst().orElse(null);

        if (statusItem != null) {
            sb.append("상태: ").append(orDefault(statusItem.fromValue(), "-"))
                    .append(" → ").append(orDefault(statusItem.toValue(), "-")).append("\n");
        }
        if (assigneeItem != null) {
            sb.append("담당자: ").append(orDefault(assigneeItem.fromValue(), "미배정"))
                    .append(" → ").append(orDefault(assigneeItem.toValue(), "미배정")).append("\n");
        }

        // STUDY: reporter 멘션. IssueEntity.reporter 는 Jira displayName 이 저장됨.
        String reporterDisplay = issue.getReporter();
        String reporterMention = resolveMention(null, reporterDisplay);
        sb.append("reporter: ").append(reporterMention).append("\n");

        // STUDY: actor 멘션. webhook payload 최상위 user 노드.
        JsonNode userNode = root.path("user");
        String actorAccountId = userNode.path("accountId").isMissingNode() ? null : userNode.path("accountId").asText(null);
        String actorDisplay = userNode.path("displayName").isMissingNode() ? null : userNode.path("displayName").asText(null);
        if (userNode.isMissingNode() || userNode.isNull() || (actorAccountId == null && actorDisplay == null)) {
            sb.append("변경자: 자동화/시스템\n");
        } else {
            sb.append("변경자: ").append(resolveMention(actorAccountId, actorDisplay)).append("\n");
        }

        // STUDY: 신규 담당자 라인. 단, actor 또는 reporter 와 같으면 중복 생략.
        if (assigneeItem != null && assigneeItem.toValue() != null && !assigneeItem.toValue().isBlank()) {
            String newAssignee = assigneeItem.toValue();
            boolean sameAsActor = newAssignee.equals(actorDisplay);
            boolean sameAsReporter = newAssignee.equals(reporterDisplay);
            if (!sameAsActor && !sameAsReporter) {
                sb.append("신규 담당자: ").append(resolveMention(null, newAssignee)).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    private String resolveMention(String jiraAccountId, String jiraDisplayName) {
        // STUDY: 매핑 조회 우선순위 — accountId → displayName → 평문.
        Optional<UserMappingEntity> mapping = Optional.empty();
        if (jiraAccountId != null && !jiraAccountId.isBlank()) {
            mapping = userMappingRepository.findByJiraAccountId(jiraAccountId);
        }
        if (mapping.isEmpty() && jiraDisplayName != null && !jiraDisplayName.isBlank()) {
            mapping = userMappingRepository.findByJiraDisplayName(jiraDisplayName);
        }
        String fallback = (jiraDisplayName == null || jiraDisplayName.isBlank()) ? "(미상)" : jiraDisplayName;
        if (mapping.isEmpty()) {
            return fallback;
        }
        MentionMode mode = notifyProps.mention() == null ? MentionMode.MENTION : notifyProps.mention();
        if (mode == MentionMode.PLAIN) {
            return fallback;
        }
        return "<@" + mapping.get().getSlackUserId() + ">";
    }

    private void applyIssueUpdate(JsonNode root, IssueEntity issue) {
        JsonNode fields = root.path("issue").path("fields");
        String summary = fields.path("summary").asText(issue.getSummary());
        String issueType = fields.path("issuetype").path("name").asText(issue.getIssueType());
        String status = fields.path("status").path("name").asText(issue.getStatus());
        String statusCategoryRaw = fields.path("status").path("statusCategory").path("name").asText(issue.getStatusCategory());
        String statusCategory = resolveStatusCategoryKorean(statusCategoryRaw);
        JsonNode assigneeNode = fields.path("assignee");
        String assignee = (assigneeNode.isMissingNode() || assigneeNode.isNull())
                ? null : assigneeNode.path("displayName").asText(issue.getAssignee());
        JsonNode spNode = fields.path("customfield_10016");
        Double storyPoint = (spNode.isMissingNode() || spNode.isNull()) ? issue.getStoryPoint() : spNode.asDouble();
        Instant jiraUpdated = parseInstantOrFallback(fields.path("updated").asText(null), issue.getJiraUpdated());

        issue.updateFrom(summary, issueType, status, statusCategory, assignee, storyPoint, jiraUpdated);
        issueRepository.save(issue);
    }

    private String resolveStatusCategoryKorean(String rawCategoryName) {
        // STUDY: 한국어 Jira 사이트는 "완료"/"진행 중"/"해야 할 일" 등으로 반환되고, 영어는 "Done"/"In Progress"/"To Do" 등.
        //        IssueEntity 가 한국어 표기로 저장돼 있어 영어가 오면 매핑한다.
        if (rawCategoryName == null) return null;
        String lower = rawCategoryName.toLowerCase().strip();
        return switch (lower) {
            case "done", "complete" -> "완료";
            case "in progress", "indeterminate" -> "진행 중";
            case "to do", "new" -> "해야 할 일";
            default -> rawCategoryName;
        };
    }

    // STUDY: Jira webhook 의 updated 필드는 "2025-12-31T01:23:45.000+0900" 처럼 offset 에 콜론이 없는 형태로 온다.
    //        Instant.parse 는 "+09:00" 형식만 허용해 위 입력을 거부하므로 명시적 DateTimeFormatter 패턴을 우선 적용한다.
    //        실패 시 ISO-8601 표준 형식(예: "+09:00")으로 한 번 더 시도하고, 그래도 실패하면 기존 값(fallback)을 유지해
    //        DB 의 jiraUpdated 가 Instant.now() 로 덮여 Jira 와 drift 되는 사고를 막는다.
    private static final DateTimeFormatter JIRA_TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private Instant parseInstantOrFallback(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return OffsetDateTime.parse(raw, JIRA_TS_FORMAT).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException e2) {
                log.debug("Unparseable Jira timestamp '{}', keeping existing jiraUpdated", raw);
                return fallback;
            }
        }
    }

    private String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String issueLink(String key) {
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        if (base.isBlank()) return key;
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/browse/" + key;
    }
}
