package com.jirabot.slack.service;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.GitHubApiClient;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.client.dto.PullRequestDetail;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// STUDY: 완료된 PR → Jira 티켓 회고 등록. GitHub(PR 조회) + Claude(내용분석) + Jira(생성/전환/스프린트)를
//        조합한다. SP 는 PR 생성→merge 의 "영업일"(주말 제외)로 산정한다.
@Service
public class PrImportServiceImpl implements PrImportService {

    private static final Logger log = LoggerFactory.getLogger(PrImportServiceImpl.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // https://github.com/{owner}/{repo}/pull/{number}  (쿼리/프래그먼트 허용)
    private static final Pattern PR_URL =
            Pattern.compile("github\\.com/([^/]+)/([^/]+)/pull/(\\d+)");

    private final GitHubApiClient gitHub;
    private final ClaudeApiClient claude;
    private final JiraApiClient jira;
    private final IssueRepository issueRepository;
    private final UserMappingRepository userMappingRepository;
    private final com.jirabot.slack.repository.GitHubUserMappingRepository gitHubUserMappingRepository;
    private final String jiraBaseUrl;

    public PrImportServiceImpl(GitHubApiClient gitHub, ClaudeApiClient claude, JiraApiClient jira,
                               IssueRepository issueRepository,
                               UserMappingRepository userMappingRepository,
                               com.jirabot.slack.repository.GitHubUserMappingRepository gitHubUserMappingRepository,
                               JiraProperties jiraProps) {
        this.gitHub = gitHub;
        this.claude = claude;
        this.jira = jira;
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
        this.gitHubUserMappingRepository = gitHubUserMappingRepository;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl().replaceAll("/+$", "");
        this.jiraBaseUrl = base;
    }

    @Override
    public Result importMergedPr(String prUrl, String slackUserId) {
        Matcher m = prUrl == null ? null : PR_URL.matcher(prUrl.trim());
        if (m == null || !m.find()) {
            return Result.fail("PR URL 형식이 올바르지 않습니다. 예: https://github.com/조직/repo/pull/123");
        }
        String owner = m.group(1);
        String repo = m.group(2);
        int number = Integer.parseInt(m.group(3));

        Optional<PullRequestDetail> prOpt = gitHub.getPullRequest(owner, repo, number);
        if (prOpt.isEmpty()) {
            return Result.fail("PR 을 가져오지 못했습니다 (토큰 권한/URL 확인): " + owner + "/" + repo + "#" + number);
        }
        PullRequestDetail pr = prOpt.get();
        if (!pr.merged() || pr.mergedAt() == null) {
            return Result.fail("아직 merge 되지 않은 PR 입니다. 완료(merge)된 PR 만 등록할 수 있어요.");
        }
        if (pr.createdAt() == null) {
            return Result.fail("PR 생성일을 확인할 수 없습니다.");
        }

        double businessDays = businessDaysBetween(pr.createdAt(), pr.mergedAt());
        int storyPoint = storyPointForBusinessDays(businessDays);

        // 내용 분석 — 제목/본문으로 BUG/FEATURE/OTHER + 제목/요약 생성. SP 는 PR 기간 값으로 덮어쓴다.
        IssueClassification analyzed = classifyContent(pr);
        IssueClassification classification = new IssueClassification(
                analyzed.type(), storyPoint, analyzed.title(), analyzed.summary());

        // 보고자/담당자 = PR 작성자. GitHub 프로필 name(없으면 login)으로 Jira user search 해서 accountId 해결.
        // createIssue 는 accountId 가 있으면 reporter+assignee 를 모두 그 계정으로 지정한다.
        // 작성자를 못 찾으면 실행한 Slack 사용자 → 토큰 소유자 순으로 폴백.
        String reporterName = null;
        String reporterAccountId = null;
        // 1순위: 명시적 GitHub→Jira 매핑(github_user_mappings). 가장 정확.
        var ghMapping = gitHubUserMappingRepository.findByGithubLoginIgnoreCase(pr.authorLogin());
        if (ghMapping.isPresent()) {
            reporterName = ghMapping.get().getJiraDisplayName();
            reporterAccountId = ghMapping.get().getJiraAccountId();
        } else {
            // 2순위: GitHub 프로필 이름으로 Jira 검색(login fuzzy 검색은 오매칭 위험이라 제외).
            String ghName = gitHub.getUserDisplayName(pr.authorLogin()).orElse(null);
            String authorAccountId = (ghName == null || ghName.isBlank()) ? null : jira.findAccountId(ghName);
            if (authorAccountId != null) {
                reporterName = ghName;
                reporterAccountId = authorAccountId;
            } else if (slackUserId != null) {
                // 3순위: 실행한 Slack 사용자.
                log.info("PR import: PR 작성자 '{}'(name={}) Jira 매칭 실패 → 실행자로 폴백",
                        pr.authorLogin(), ghName);
                Optional<UserMappingEntity> mapping = userMappingRepository.findBySlackUserId(slackUserId);
                if (mapping.isPresent()) {
                    reporterName = mapping.get().getJiraDisplayName();
                    reporterAccountId = mapping.get().getJiraAccountId();
                }
            }
        }

        JiraCreateResponse created;
        try {
            created = jira.createIssue(classification, reporterName, reporterAccountId);
        } catch (Exception e) {
            log.warn("PR import: Jira 생성 실패 {}: {}", prUrl, e.toString());
            return Result.fail("Jira 이슈 생성 실패: " + e.getMessage());
        }
        String key = created.key();
        String issueUrl = jiraBaseUrl.isEmpty() ? key : jiraBaseUrl + "/browse/" + key;

        // PR 출처를 댓글로 남겨 추적성 확보 (실패해도 비치명적).
        try {
            jira.addComment(key, String.format(
                    "PR import: %s\n생성 %s → merge %s (영업일 %.1f일 → SP %d)",
                    pr.htmlUrl(), pr.createdAt(), pr.mergedAt(), businessDays, storyPoint));
        } catch (Exception e) {
            log.debug("PR import comment skipped {}: {}", key, e.toString());
        }

        // 전체 워크플로 한번에: 해야 할 일 → 진행 중 → (스프린트 이동) → 검토 중 → 완료.
        String finalStatus = runFullWorkflow(key);

        // 로컬 DB 적재 — 추이/통계에 바로 반영. 작업 기간(PR 생성~merge)을 그대로 저장.
        try {
            persist(pr, key, classification, finalStatus);
        } catch (Exception e) {
            log.warn("PR import: 로컬 DB 적재 실패 {} (비치명적): {}", key, e.toString());
        }

        log.info("PR import done {} -> {} sp={} status={} reporter={} (PR {}/{}#{})",
                prUrl, key, storyPoint, finalStatus, reporterName, owner, repo, number);
        return new Result(true, key, issueUrl, storyPoint, businessDays, finalStatus, reporterName, null);
    }

    private IssueClassification classifyContent(PullRequestDetail pr) {
        try {
            String body = pr.body() == null ? "" : pr.body();
            String text = ("PR 제목: " + pr.title() + "\n\n" + body).strip();
            IssueClassification c = claude.classify(text);
            if (c != null && c.title() != null && !c.title().isBlank()) {
                return c;
            }
        } catch (Exception e) {
            log.warn("PR import: 내용 분석 실패, 폴백 사용: {}", e.toString());
        }
        String title = pr.title() == null || pr.title().isBlank() ? "PR #" + pr.number() : pr.title();
        return new IssueClassification(IssueClassification.IssueType.OTHER, 0, title,
                pr.body() == null ? "" : pr.body());
    }

    // 전환은 각 단계 best-effort. 마지막으로 성공한 상태를 반환.
    private String runFullWorkflow(String key) {
        String status = "Backlog";
        if (jira.transitionIssue(key, "해야 할 일")) status = "해야 할 일";
        if (jira.transitionIssue(key, "진행 중")) status = "진행 중";
        jira.moveToActiveSprint(key);
        if (jira.transitionIssue(key, "검토 중")) status = "검토 중";
        if (jira.transitionIssue(key, "완료")) status = "완료";
        return status;
    }

    private void persist(PullRequestDetail pr, String key, IssueClassification c, String finalStatus) {
        String statusCategory = statusCategoryOf(finalStatus);
        IssueEntity e = new IssueEntity(key, c.title(), issueTypeName(c.type()), finalStatus,
                statusCategory, null, (double) c.storyPoint(), null, c.summary(),
                pr.createdAt(), Instant.now());
        if (StatusCategory.DONE.equals(statusCategory)) {
            e.setCompletedAt(pr.mergedAt());   // 완료 시각 = merge 시각 (추이/리드타임 정확)
        }
        issueRepository.save(e);
    }

    private static String statusCategoryOf(String status) {
        return switch (status) {
            case "완료" -> StatusCategory.DONE;
            case "진행 중", "검토 중" -> StatusCategory.IN_PROGRESS;
            default -> StatusCategory.TODO;
        };
    }

    private static String issueTypeName(IssueClassification.IssueType type) {
        return switch (type) {
            case BUG -> "버그";
            case EPIC -> "에픽";
            default -> "작업";
        };
    }

    // --- Story Point 산정: PR 생성→merge 의 영업일(주말 제외) ---

    static int storyPointForBusinessDays(double bd) {
        if (bd <= 0.5) return 1;
        if (bd <= 1) return 2;
        if (bd <= 2) return 3;
        if (bd <= 3) return 5;
        return 8;
    }

    // 주말(토/일, KST)을 제외한 경과 시간을 "일"(24h=1일) 단위로 환산.
    static double businessDaysBetween(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) return 0;
        long weekdayMinutes = 0;
        LocalDate day = start.atZone(KST).toLocalDate();
        LocalDate last = end.atZone(KST).toLocalDate();
        while (!day.isAfter(last)) {
            switch (day.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> { /* 주말 제외 */ }
                default -> {
                    Instant dayStart = day.atStartOfDay(KST).toInstant();
                    Instant dayEnd = day.plusDays(1).atStartOfDay(KST).toInstant();
                    Instant from = start.isAfter(dayStart) ? start : dayStart;
                    Instant to = end.isBefore(dayEnd) ? end : dayEnd;
                    if (to.isAfter(from)) {
                        weekdayMinutes += Duration.between(from, to).toMinutes();
                    }
                }
            }
            day = day.plusDays(1);
        }
        return weekdayMinutes / (60.0 * 24.0);
    }
}
