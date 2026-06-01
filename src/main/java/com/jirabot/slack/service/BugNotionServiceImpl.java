package com.jirabot.slack.service;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.NotionApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.BugResolutionSummary;
import com.jirabot.slack.client.dto.SprintIssue;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.NotionProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.util.NotionProperty;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// STUDY: Notion 동기화의 도메인 로직. NotionApiClient(저수준) 위에서 컬럼 매핑/요약/백필을 담당.
//        모든 공개 메서드는 enabled() 가드 — token/DB 미설정 환경에서 안전.
@Service
public class BugNotionServiceImpl implements BugNotionService {

    private static final Logger log = LoggerFactory.getLogger(BugNotionServiceImpl.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final NotionApiClient notion;
    private final ClaudeApiClient claude;
    private final JiraApiClient jira;
    private final SlackNotifier slackNotifier;
    private final NotionProperties notionProps;
    private final JiraProperties jiraProps;

    public BugNotionServiceImpl(NotionApiClient notion, ClaudeApiClient claude, JiraApiClient jira,
                                SlackNotifier slackNotifier, NotionProperties notionProps,
                                JiraProperties jiraProps) {
        this.notion = notion;
        this.claude = claude;
        this.jira = jira;
        this.slackNotifier = slackNotifier;
        this.notionProps = notionProps;
        this.jiraProps = jiraProps;
    }

    @Override
    public boolean enabled() {
        return notionProps.effectivelyEnabled();
    }

    @Override
    public void syncOnStatusChange(IssueEntity issue, boolean toDone) {
        if (!enabled()) {
            return;
        }
        String resolvedDate = StatusCategory.DONE.equals(issue.getStatusCategory())
                ? instantToDate(issue.getJiraUpdated()) : null;
        upsertStatusRow(issue.getIssueKey(), issue.getSummary(), issue.getStatus(),
                issue.getStatusCategory(), issue.getAssignee(),
                instantToDate(issue.getJiraCreated()), resolvedDate);

        if (toDone) {
            upsertResolutionRow(issue);
        }
    }

    @Override
    public int backfillStatusDb() {
        if (!enabled() || isBlank(notionProps.statusDbId())) {
            log.warn("Notion backfill skipped: disabled or status-db-id missing");
            return 0;
        }
        // STUDY(L4 심화): JQL `issuetype = "버그"`(표시명)는 매칭 안 됨 — JQL 정식 이름은 "Bug"/id 라서 0건 반환.
        //        반면 검색 응답의 issuetype.name 은 "버그"(표시명)다. 그래서 JQL 에 issuetype 을 넣지 않고
        //        프로젝트 전체를 받아 응답의 표시명(설정값과 동일)으로 클라이언트 필터한다.
        String bugType = jiraProps.issueTypes().bug();
        String jql = String.format("project = %s ORDER BY created DESC", jiraProps.projectKey());
        List<SprintIssue> bugs = jira.searchByJql(jql).stream()
                .filter(i -> bugType != null && bugType.equalsIgnoreCase(i.issueType()))
                .toList();
        for (SprintIssue b : bugs) {
            String resolvedDate = StatusCategory.DONE.equals(b.statusCategory())
                    ? isoDateOnly(b.updated()) : null;
            upsertStatusRow(b.key(), b.summary(), b.status(), b.statusCategory(),
                    b.assignee(), isoDateOnly(b.created()), resolvedDate);
        }
        log.info("Notion backfill done: {} bugs", bugs.size());
        return bugs.size();
    }

    // --- 현황 DB(status) ---

    private void upsertStatusRow(String key, String summary, String jiraStatus, String statusCategory,
                                 String assignee, String createdDate, String resolvedDate) {
        String dbId = notionProps.statusDbId();
        if (isBlank(dbId) || isBlank(key)) {
            return;
        }
        boolean resolved = StatusCategory.DONE.equals(statusCategory);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("이슈", NotionProperty.title(key + " " + nz(summary)));
        props.put("상태", NotionProperty.select(resolved ? "해결" : "미해결"));
        props.put("Jira상태", NotionProperty.richText(nz(jiraStatus)));
        props.put("담당자", NotionProperty.richText(assignee == null ? "미배정" : assignee));
        props.put("생성일", NotionProperty.date(createdDate));
        props.put("해결일", NotionProperty.date(resolvedDate));
        props.put("Jira링크", NotionProperty.url(issueLink(key)));
        upsert(dbId, key, props);
    }

    // --- 해결 기록 DB(resolution) ---

    private void upsertResolutionRow(IssueEntity issue) {
        String dbId = notionProps.resolutionDbId();
        if (isBlank(dbId)) {
            return;
        }
        List<String> comments = jira.getComments(issue.getIssueKey());
        List<String> thread = (issue.getSlackChannel() != null && issue.getSlackThreadTs() != null)
                ? slackNotifier.getThreadMessages(issue.getSlackChannel(), issue.getSlackThreadTs())
                : List.of();
        BugResolutionSummary s = claude.summarizeBugResolution(
                issue.getIssueKey(), issue.getDescription(), comments, thread);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("이슈", NotionProperty.title(issue.getIssueKey() + " " + nz(issue.getSummary())));
        props.put("원인", NotionProperty.richText(s.causeOrDefault()));
        props.put("해결방법", NotionProperty.richText(s.fixOrDefault()));
        props.put("해결일", NotionProperty.date(instantToDate(issue.getJiraUpdated())));
        props.put("담당자", NotionProperty.richText(issue.getAssignee() == null ? "미배정" : issue.getAssignee()));
        props.put("Jira링크", NotionProperty.url(issueLink(issue.getIssueKey())));
        upsert(dbId, issue.getIssueKey(), props);
    }

    // --- 공통 ---

    private void upsert(String dbId, String issueKey, Map<String, Object> props) {
        Optional<String> existing = notion.findPageId(dbId, issueKey);
        if (existing.isPresent()) {
            notion.updateRow(existing.get(), props);
        } else {
            notion.createRow(dbId, props);
        }
    }

    private String issueLink(String key) {
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        if (base.isBlank()) {
            return null;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/browse/" + key;
    }

    private static String instantToDate(Instant instant) {
        return instant == null ? null : DATE.format(instant.atZone(KST).toLocalDate());
    }

    // STUDY: SprintIssue 의 created/updated 는 "2026-06-01T..." ISO datetime. 날짜 부분만 취한다.
    private static String isoDateOnly(String iso) {
        if (iso == null || iso.length() < 10) {
            return null;
        }
        return iso.substring(0, 10);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
