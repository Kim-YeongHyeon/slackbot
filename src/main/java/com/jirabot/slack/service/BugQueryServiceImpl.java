package com.jirabot.slack.service;

import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// STUDY: @Service + @Async("slackTaskExecutor") 패턴으로 컨트롤러에서 비즈니스 로직 분리.
//        CompletableFuture 반환 → 호출측에서 thenAccept/exceptionally 체이닝 가능.
@Service
public class BugQueryServiceImpl implements BugQueryService {

    private static final Logger log = LoggerFactory.getLogger(BugQueryServiceImpl.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter COMPLETION_DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final int MAX_BUG_QUERY_RESULTS = 50;
    private static final int DISPLAY_LIMIT = 15;
    // STUDY: Jira 이슈 타입명은 사이트 언어에 따라 다르다. 한국어: "버그", 영어: "Bug".
    //        application.yml의 slackbot.issue-type.bug 프로퍼티로 외부화하여 프로젝트 변경 시 코드 수정 없이 대응.
    private static final String DEFAULT_BUG_TYPE = "버그";

    private final IssueRepository issueRepository;
    private final String jiraBaseUrl;
    private final String bugIssueType;

    public BugQueryServiceImpl(IssueRepository issueRepository, JiraProperties jiraProps,
                               @org.springframework.beans.factory.annotation.Value("${slackbot.issue-type.bug:버그}") String bugIssueType) {
        this.issueRepository = issueRepository;
        this.bugIssueType = bugIssueType;
        // STUDY: baseUrl 후행 슬래시 제거. "https://jira.example.com/" → "https://jira.example.com"
        //        issueLink()에서 "/browse/KEY"를 붙이므로 후행 슬래시가 있으면 이중 슬래시가 된다.
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    // STUDY: @Async 프록시가 CompletableFuture를 감싸 비동기 실행. self-invocation에는 적용 안 됨.
    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> queryResolvedBugs(LocalDate since) {
        try {
            Instant sinceInstant = since.atStartOfDay(KST).toInstant();
            // STUDY: Pageable로 DB 레벨에서 결과 수를 제한. 대량 데이터 시 메모리 보호.
            List<IssueEntity> bugs = issueRepository.findResolvedBugsSince(
                    bugIssueType, sinceInstant, PageRequest.of(0, MAX_BUG_QUERY_RESULTS));

            String sinceDateStr = since.format(COMPLETION_DATE_FMT);
            StringBuilder sb = new StringBuilder();

            if (bugs.isEmpty()) {
                sb.append(String.format(":bug: %s 이후 해결된 버그가 없습니다.", sinceDateStr));
                return CompletableFuture.completedFuture(sb.toString());
            }

            int totalCount = bugs.size();
            int displayCount = Math.min(totalCount, DISPLAY_LIMIT);

            sb.append(String.format(":bug: %s 이후 해결된 버그 (%d건)\n\n", sinceDateStr, totalCount));

            for (int i = 0; i < displayCount; i++) {
                IssueEntity bug = bugs.get(i);
                // STUDY: completedAt이 null인 완료 이슈(sync 이전에 완료된 것)는 jiraUpdated를 fallback으로 사용한다.
                //        jiraUpdated는 완료 후에도 댓글/수정으로 갱신될 수 있어 정확한 완료 시점이 아닐 수 있다 (근사치).
                Instant completionInstant = bug.getCompletedAt() != null ? bug.getCompletedAt() : bug.getJiraUpdated();
                String completionDate = completionInstant != null
                        ? ZonedDateTime.ofInstant(completionInstant, KST).format(COMPLETION_DATE_FMT)
                        : "N/A";
                String jiraUrl = issueLink(bug.getIssueKey());
                // STUDY: SP null → "-" 표시 (0이 아님). PR #2 IssueSearchServiceImpl과 동일 패턴.
                String sp = bug.getStoryPoint() != null ? String.valueOf(bug.getStoryPoint().intValue()) : "-";
                String assignee = bug.getAssignee() != null ? bug.getAssignee() : "미배정";

                sb.append(String.format("  • <%s|%s> %s (완료 %s, SP %s, 담당: %s)\n",
                        jiraUrl, bug.getIssueKey(), bug.getSummary(), completionDate, sp, assignee));
            }

            if (totalCount > DISPLAY_LIMIT) {
                sb.append(String.format("  ... 외 %d건\n", totalCount - DISPLAY_LIMIT));
            }

            // STUDY: SP 합산 — null인 이슈는 "미추정"으로 별도 카운트. 0 SP와 구분하여 정확한 통계 제공.
            long estimatedCount = bugs.stream()
                    .filter(b -> b.getStoryPoint() != null && b.getStoryPoint() > 0).count();
            long unestimatedCount = bugs.size() - estimatedCount;
            double totalSp = bugs.stream()
                    .filter(b -> b.getStoryPoint() != null)
                    .mapToDouble(IssueEntity::getStoryPoint).sum();

            String summary = String.format("\n:bar_chart: 총 %d건 해결 / %.0f SP 완료", totalCount, totalSp);
            if (unestimatedCount > 0) {
                summary += String.format(" (미추정 %d건)", unestimatedCount);
            }
            sb.append(summary);

            log.info("Bug query completed: since={} count={}", sinceDateStr, totalCount);
            return CompletableFuture.completedFuture(sb.toString());
        } catch (Exception e) {
            log.error("Bug query failed: {}", e.toString());
            return CompletableFuture.completedFuture(":x: 버그 조회 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private String issueLink(String key) {
        if (jiraBaseUrl.isEmpty()) return key;
        return String.format("%s/browse/%s", jiraBaseUrl, key);
    }
}
