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
    private final BugNotionService bugNotionService;

    public JiraWebhookServiceImpl(ObjectMapper objectMapper,
                                  IssueRepository issueRepository,
                                  UserMappingRepository userMappingRepository,
                                  ProcessedJiraChangelogRepository processedRepo,
                                  SlackNotifier slackNotifier,
                                  JiraWebhookProperties webhookProps,
                                  NotifyProperties notifyProps,
                                  JiraProperties jiraProps,
                                  JiraStatusCategoryResolver resolver,
                                  BugNotionService bugNotionService) {
        this.objectMapper = objectMapper;
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
        this.processedRepo = processedRepo;
        this.slackNotifier = slackNotifier;
        this.webhookProps = webhookProps;
        this.notifyProps = notifyProps;
        this.jiraProps = jiraProps;
        this.resolver = resolver;
        this.bugNotionService = bugNotionService;
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

            List<JiraChangelog> items = parseChangelogItems(root.path("changelog").path("items"));
            if (items.isEmpty()) {
                return;
            }

            // STUDY: 개인 할당 DM 은 봇 생성(스레드 보유) 이슈 여부와 무관하게 발송해야 하므로
            //        로컬 추적/스레드 가드보다 먼저 처리한다 (공식 앱의 personal notifications 대응).
            //        실패해도 스레드 알림 흐름을 깨지 않도록 내부에서 예외를 흡수한다.
            notifyAssigneeDm(root, issueKey, items);

            Optional<IssueEntity> found = issueRepository.findByIssueKey(issueKey);
            if (found.isEmpty()) {
                log.debug("Webhook thread-notify skipped: issue {} not tracked locally", issueKey);
                return;
            }
            IssueEntity issue = found.get();
            if (issue.getSlackChannel() == null || issue.getSlackThreadTs() == null) {
                log.debug("Webhook thread-notify skipped: issue {} has no Slack thread", issueKey);
                return;
            }

            // STUDY: 트리거 평가 후 메시지를 만들고 알림 발송. updateFrom 으로 DB 일관성도 함께 갱신.
            boolean shouldNotify = shouldNotify(items);
            if (shouldNotify) {
                String message = buildMessage(root, issue, items);
                slackNotifier.postThreadReply(issue.getSlackChannel(), issue.getSlackThreadTs(), message);
                applyIssueUpdate(root, issue);
                log.info("Webhook notified key={} changelogId={}", issueKey, changelogId);

                // STUDY: 버그의 상태 변경이면 Notion 현황 DB 를 동기화하고, 완료로 전환된 경우 해결 기록 DB 도 적재한다.
                //        Notion 비활성/실패는 enabled() 가드 + 내부 try-catch 로 본 흐름을 깨지 않는다.
                syncNotionIfBug(issue, items);
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
                    item.path("toString").isNull() ? null : item.path("toString").asText(null),
                    item.path("to").isNull() ? null : item.path("to").asText(null)));
        }
        return items;
    }

    // STUDY: Jira 에서 이슈가 누군가에게 할당되면 그 사람에게 DM 발송.
    //        조건: assignee 변경 항목 존재 && 새 담당자 매핑 등록됨 && assignDmEnabled && 셀프할당 아님.
    //        봇의 `할당` 명령으로 바꿔도 Jira webhook 이 돌아와 같은 경로로 DM — 알림 경로 일원화.
    void notifyAssigneeDm(JsonNode root, String issueKey, List<JiraChangelog> items) {
        try {
            JiraChangelog assigneeItem = items.stream()
                    .filter(i -> "assignee".equals(i.field())).findFirst().orElse(null);
            if (assigneeItem == null || (assigneeItem.toId() == null && assigneeItem.toValue() == null)) {
                return; // 할당 변경 아님 또는 담당자 해제
            }

            // 매핑 조회 — accountId 우선, displayName 폴백 (L4: 저장된 식별자가 진실).
            Optional<UserMappingEntity> mapping = Optional.empty();
            if (assigneeItem.toId() != null && !assigneeItem.toId().isBlank()) {
                mapping = userMappingRepository.findByJiraAccountId(assigneeItem.toId());
            }
            if (mapping.isEmpty() && assigneeItem.toValue() != null && !assigneeItem.toValue().isBlank()) {
                mapping = userMappingRepository.findByJiraDisplayName(assigneeItem.toValue());
            }
            // STUDY: 여기부터는 "할당 변경이 실제 감지된" 경우라 빈도가 낮다 — skip 사유를 INFO 로 남겨
            //        "알림이 안 왔어요" 문의를 로그만으로 진단할 수 있게 한다.
            if (mapping.isEmpty()) {
                log.info("Assign DM skipped key={}: no mapping for assignee '{}' (accountId={})",
                        issueKey, assigneeItem.toValue(), assigneeItem.toId());
                return;
            }
            if (!mapping.get().isAssignDmEnabled()) {
                log.info("Assign DM skipped key={}: disabled for {}", issueKey, mapping.get().getSlackUserId());
                return;
            }

            // 셀프할당(변경자 본인에게 할당)은 DM 생략 — 본인이 한 행동이라 알림 가치가 없다.
            String actorAccountId = root.path("user").path("accountId").asText(null);
            if (actorAccountId != null && actorAccountId.equals(assigneeItem.toId())) {
                log.info("Assign DM skipped key={}: self-assignment by {}", issueKey, actorAccountId);
                return;
            }

            String summary = root.path("issue").path("fields").path("summary").asText("");
            String actorDisplay = root.path("user").path("displayName").asText(null);
            String actor = (actorDisplay == null || actorDisplay.isBlank()) ? "자동화/시스템" : actorDisplay;
            String message = String.format(
                    ":bell: <%s|%s> %s\n회원님에게 할당되었습니다. (변경: %s)\n_알림 끄기: `@지라 할당알림 off`_",
                    issueLink(issueKey), issueKey, summary, actor);
            slackNotifier.sendDirectMessage(mapping.get().getSlackUserId(), message);
            log.info("Assign DM sent key={} to={}", issueKey, mapping.get().getSlackUserId());
        } catch (Exception e) {
            log.warn("Assign DM failed key={}: {}", issueKey, e.toString());
        }
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

    // STUDY: 버그 + 상태 변경일 때만 Notion 동기화. toDone 은 이번 변경에서 "완료 아님 → 완료" 전환인지.
    private void syncNotionIfBug(IssueEntity issue, List<JiraChangelog> items) {
        if (!bugNotionService.enabled() || !isBug(issue.getIssueType())) {
            return;
        }
        boolean statusChanged = items.stream().anyMatch(i -> "status".equals(i.field()));
        if (!statusChanged) {
            return;
        }
        boolean toDone = items.stream().anyMatch(i -> "status".equals(i.field())
                && resolver.isDone(i.toValue()) && !resolver.isDone(i.fromValue()));
        try {
            bugNotionService.syncOnStatusChange(issue, toDone);
        } catch (Exception e) {
            log.warn("Notion sync failed key={}: {}", issue.getIssueKey(), e.toString());
        }
    }

    private boolean isBug(String issueType) {
        if (issueType == null) {
            return false;
        }
        String cfg = jiraProps.issueTypes() != null ? jiraProps.issueTypes().bug() : null;
        return issueType.toLowerCase().contains("버그") || issueType.toLowerCase().contains("bug")
                || (cfg != null && issueType.equalsIgnoreCase(cfg));
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

        // STUDY: "변경자" 라인은 제거함. 봇이 일으킨 전환(슬랙 버튼/명령)은 단일 API 토큰으로 호출돼
        //        webhook actor 가 항상 토큰 소유자로 기록되므로(실제 클릭자와 무관) 오해를 부른다.
        //        버튼 클릭 시 원본 메시지는 buildTransitionedBlocks 가 실제 클릭자로 이미 갱신한다.

        // STUDY: 신규 담당자 라인. reporter 와 같으면 중복 생략.
        if (assigneeItem != null && assigneeItem.toValue() != null && !assigneeItem.toValue().isBlank()) {
            String newAssignee = assigneeItem.toValue();
            if (!newAssignee.equals(reporterDisplay)) {
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
        // STUDY: SP 커스텀 필드는 Jira 사이트마다 다르므로 설정값을 사용한다.
        JsonNode spNode = fields.path(jiraProps.storyPointField());
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
