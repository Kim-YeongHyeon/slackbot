package com.jirabot.slack.service;

import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.dto.dashboard.DashboardDtos.AssigneeLoad;
import com.jirabot.slack.dto.dashboard.DashboardDtos.BugStats;
import com.jirabot.slack.dto.dashboard.DashboardDtos.IntentFailureRow;
import com.jirabot.slack.dto.dashboard.DashboardDtos.IssueRow;
import com.jirabot.slack.dto.dashboard.DashboardDtos.SprintStats;
import com.jirabot.slack.dto.dashboard.DashboardDtos.StatusSlice;
import com.jirabot.slack.dto.dashboard.DashboardDtos.Summary;
import com.jirabot.slack.dto.dashboard.DashboardDtos.TrendStats;
import com.jirabot.slack.dto.dashboard.DashboardDtos.WeekBucket;
import com.jirabot.slack.dto.dashboard.DashboardDtos.WeekResolution;
import com.jirabot.slack.entity.IntentFailureEntity;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.repository.IntentFailureRepository;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// STUDY: 대시보드 집계. 이슈 수백 건 수준이라 "필요 범위 1회 조회 → 메모리 집계" 가
//        가장 단순하고 충분히 빠르다 (group-by 가 이미 있는 통계는 기존 쿼리 재사용).
//        모든 데이터는 로컬 DB — 대시보드를 아무리 새로고침해도 Jira API 호출이 없다.
@Service
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int ISSUE_TABLE_CAP = 200;

    private final IssueRepository issueRepository;
    private final UserMappingRepository userMappingRepository;
    private final IntentFailureRepository intentFailureRepository;
    private final JiraSyncService jiraSyncService;
    private final ReminderProperties reminderProps;
    private final com.jirabot.slack.client.GitHubApiClient gitHubApiClient;
    private final com.jirabot.slack.config.GitHubProperties gitHubProps;
    private final String jiraBaseUrl;
    private final String bugTypeName;

    public DashboardServiceImpl(IssueRepository issueRepository,
                                UserMappingRepository userMappingRepository,
                                IntentFailureRepository intentFailureRepository,
                                JiraSyncService jiraSyncService,
                                ReminderProperties reminderProps,
                                com.jirabot.slack.client.GitHubApiClient gitHubApiClient,
                                com.jirabot.slack.config.GitHubProperties gitHubProps,
                                JiraProperties jiraProps) {
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
        this.intentFailureRepository = intentFailureRepository;
        this.jiraSyncService = jiraSyncService;
        this.reminderProps = reminderProps;
        this.gitHubApiClient = gitHubApiClient;
        this.gitHubProps = gitHubProps;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl().replaceAll("/+$", "");
        this.jiraBaseUrl = base;
        this.bugTypeName = jiraProps.issueTypes() != null && jiraProps.issueTypes().bug() != null
                ? jiraProps.issueTypes().bug() : "Bug";
        // PR 브랜치명에서 이슈 키 추출용 — 프로젝트 키 기반(es2-123 같은 소문자 허용) 우선,
        // 그 외 대문자 일반 패턴 폴백 (소문자 일반 단어 test-123 의 오탐 방지).
        String key = jiraProps.projectKey() == null ? "" : jiraProps.projectKey();
        this.projectKeyPattern = key.isBlank() ? null
                : java.util.regex.Pattern.compile("(?i)" + java.util.regex.Pattern.quote(key) + "-\\d+");
    }

    private final java.util.regex.Pattern projectKeyPattern;

    @Override
    public Summary summary() {
        List<IssueEntity> all = issueRepository.findAll();
        long open = all.stream().filter(i -> !StatusCategory.DONE.equals(i.getStatusCategory())).count();
        long inProgress = all.stream()
                .filter(i -> StatusCategory.IN_PROGRESS.equals(i.getStatusCategory())).count();
        long stale = all.stream().filter(this::isStale).count();

        // 활성(최근 sync) 스프린트 SP 완료율 — 리마인더/통계와 동일하게 최근 sync 스프린트 기준.
        String sprintName = null;
        double spDone = 0;
        double spTotal = 0;
        Optional<int[]> sprint = latestSprintId();
        if (sprint.isPresent()) {
            int sprintId = sprint.get()[0];
            List<IssueEntity> sprintIssues = issueRepository.findBySprintId(sprintId);
            sprintName = sprintIssues.isEmpty() ? null : sprintIssues.get(0).getSprintName();
            for (IssueEntity i : sprintIssues) {
                if (i.isSubtask()) continue; // Jira UI 와 동일하게 하위작업 SP 제외
                double sp = i.getStoryPoint() == null ? 0 : i.getStoryPoint();
                spTotal += sp;
                if (StatusCategory.DONE.equals(i.getStatusCategory())) spDone += sp;
            }
        }
        int rate = spTotal > 0 ? (int) Math.round(spDone * 100.0 / spTotal) : 0;

        return new Summary(all.size(), open, inProgress, sprintName, spDone, spTotal, rate,
                stale, userMappingRepository.count(), jiraSyncService.lastSyncAt().orElse(null));
    }

    @Override
    public SprintStats sprint() {
        Optional<int[]> sprint = latestSprintId();
        if (sprint.isEmpty()) {
            return new SprintStats(null, List.of(), List.of(), List.of());
        }
        List<IssueEntity> issues = issueRepository.findBySprintId(sprint.get()[0]);
        String name = issues.isEmpty() ? null : issues.get(0).getSprintName();

        Map<String, long[]> byStatus = new LinkedHashMap<>();   // status → [count, sp*100]
        Map<String, double[]> byAssignee = new LinkedHashMap<>(); // assignee → [count, sp, stale]
        List<IssueRow> staleRows = new ArrayList<>();
        for (IssueEntity i : issues) {
            byStatus.computeIfAbsent(orDash(i.getStatusCategory()), k -> new long[2]);
            byStatus.get(orDash(i.getStatusCategory()))[0]++;
            byStatus.get(orDash(i.getStatusCategory()))[1] += Math.round(sp(i) * 100);

            if (!StatusCategory.DONE.equals(i.getStatusCategory())) {
                double[] load = byAssignee.computeIfAbsent(assigneeLabel(i), k -> new double[3]);
                load[0]++;
                load[1] += sp(i);
                if (isStale(i)) {
                    load[2]++;
                    staleRows.add(toRow(i));
                }
            }
        }
        List<StatusSlice> slices = byStatus.entrySet().stream()
                .map(e -> new StatusSlice(e.getKey(), e.getValue()[0], e.getValue()[1] / 100.0))
                .toList();
        List<AssigneeLoad> loads = byAssignee.entrySet().stream()
                .map(e -> new AssigneeLoad(e.getKey(), (long) e.getValue()[0], e.getValue()[1],
                        (long) e.getValue()[2]))
                .sorted(Comparator.comparingDouble(AssigneeLoad::openSp).reversed())
                .toList();
        staleRows.sort(Comparator.comparing(IssueRow::jiraUpdated,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new SprintStats(name, slices, loads, staleRows);
    }

    @Override
    public TrendStats trends(int weeks) {
        int w = clampWeeks(weeks);
        LocalDate firstWeekStart = currentWeekStart().minusWeeks(w - 1L);
        List<IssueEntity> all = issueRepository.findAll();

        // 주별 생성/해결 카운트 — KST 월요일 시작 버킷.
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        for (int k = 0; k < w; k++) {
            buckets.put(firstWeekStart.plusWeeks(k), new long[2]);
        }
        Map<LocalDate, double[]> resolution = new LinkedHashMap<>(); // weekStart → [sumHours, count]
        for (IssueEntity i : all) {
            LocalDate created = toWeekStart(i.getJiraCreated());
            if (created != null && buckets.containsKey(created)) {
                buckets.get(created)[0]++;
            }
            Instant done = i.getCompletedAt();
            LocalDate resolved = toWeekStart(done);
            if (resolved != null && buckets.containsKey(resolved)) {
                buckets.get(resolved)[1]++;
                // 평균 해결 소요시간: 생성→완료. 둘 다 있는 이슈만.
                if (i.getJiraCreated() != null && done.isAfter(i.getJiraCreated())) {
                    double hours = Duration.between(i.getJiraCreated(), done).toMinutes() / 60.0;
                    resolution.computeIfAbsent(resolved, k -> new double[2]);
                    resolution.get(resolved)[0] += hours;
                    resolution.get(resolved)[1]++;
                }
            }
        }
        List<WeekBucket> weekly = buckets.entrySet().stream()
                .map(e -> new WeekBucket(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        List<WeekResolution> res = buckets.keySet().stream()
                .map(ws -> {
                    double[] r = resolution.getOrDefault(ws, new double[2]);
                    double avg = r[1] > 0 ? Math.round(r[0] / r[1] * 10) / 10.0 : 0;
                    return new WeekResolution(ws, avg, (long) r[1]);
                })
                .toList();
        return new TrendStats(weekly, res);
    }

    @Override
    public List<AssigneeLoad> workload() {
        List<IssueEntity> open = issueRepository.findByStatusCategoryNot(StatusCategory.DONE);
        Map<String, double[]> byAssignee = new LinkedHashMap<>();
        for (IssueEntity i : open) {
            double[] load = byAssignee.computeIfAbsent(assigneeLabel(i), k -> new double[3]);
            load[0]++;
            load[1] += sp(i);
            if (isStale(i)) load[2]++;
        }
        return byAssignee.entrySet().stream()
                .map(e -> new AssigneeLoad(e.getKey(), (long) e.getValue()[0], e.getValue()[1],
                        (long) e.getValue()[2]))
                .sorted(Comparator.comparingLong(AssigneeLoad::openCount).reversed())
                .toList();
    }

    @Override
    public BugStats bugs(int weeks) {
        int w = clampWeeks(weeks);
        LocalDate firstWeekStart = currentWeekStart().minusWeeks(w - 1L);
        List<IssueEntity> all = issueRepository.findAll();

        long bugCount = 0;
        long openBugCount = 0;
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        for (int k = 0; k < w; k++) {
            buckets.put(firstWeekStart.plusWeeks(k), new long[2]);
        }
        List<IssueRow> openBugs = new ArrayList<>();
        for (IssueEntity i : all) {
            if (!isBug(i)) continue;
            bugCount++;
            LocalDate created = toWeekStart(i.getJiraCreated());
            if (created != null && buckets.containsKey(created)) buckets.get(created)[0]++;
            LocalDate resolved = toWeekStart(i.getCompletedAt());
            if (resolved != null && buckets.containsKey(resolved)) buckets.get(resolved)[1]++;
            if (!StatusCategory.DONE.equals(i.getStatusCategory())) {
                openBugCount++;
                openBugs.add(toRow(i));
            }
        }
        List<WeekBucket> weekly = buckets.entrySet().stream()
                .map(e -> new WeekBucket(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        openBugs.sort(Comparator.comparing(IssueRow::jiraUpdated,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new BugStats(bugCount, all.size(), openBugCount, weekly, openBugs);
    }

    @Override
    public List<IssueRow> issues(String statusCategory, String assignee, String issueType, String keyword) {
        // STUDY: 복합 필터라 메모리 필터가 단순하다 — findAllByOrderByJiraUpdatedDesc 로 최근순 상한 조회 후 거른다.
        List<IssueEntity> recent = issueRepository.findAllByOrderByJiraUpdatedDesc(
                PageRequest.of(0, 1000));
        String kw = blankToNull(keyword) == null ? null : keyword.toLowerCase();
        return recent.stream()
                .filter(i -> blankToNull(statusCategory) == null
                        || statusCategory.equals(i.getStatusCategory()))
                .filter(i -> blankToNull(assignee) == null
                        || assigneeLabel(i).equals(assignee))
                .filter(i -> blankToNull(issueType) == null || issueType.equals(i.getIssueType()))
                .filter(i -> kw == null
                        || (i.getSummary() != null && i.getSummary().toLowerCase().contains(kw))
                        || i.getIssueKey().toLowerCase().contains(kw))
                .limit(ISSUE_TABLE_CAP)
                .map(this::toRow)
                .toList();
    }

    @Override
    public List<IntentFailureRow> intentFailures(int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return intentFailureRepository.findAll().stream()
                .sorted(Comparator.comparing(IntentFailureEntity::getFailedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(capped)
                .map(f -> new IntentFailureRow(f.getFailedAt(), f.getErrorType(),
                        f.getRawInput(), f.getSlackUserId()))
                .toList();
    }

    // --- PR 현황 ---

    private static final java.util.regex.Pattern ISSUE_KEY_IN_TEXT =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9]*-\\d+");

    // STUDY: repo 수만큼 GitHub 왕복이라 5분 Caffeine 캐시(@Cacheable) — 새로고침 연타에도 rate limit 안전.
    //        권한 부족(4xx) repo 는 GitHubAccessException 으로 구분 수집해 UI 가 안내를 띄울 수 있게 한다.
    @Override
    @org.springframework.cache.annotation.Cacheable(com.jirabot.slack.config.CacheConfig.OPEN_PRS_CACHE)
    public com.jirabot.slack.dto.dashboard.DashboardDtos.PrBoard prs() {
        if (!gitHubProps.enabled()) {
            return new com.jirabot.slack.dto.dashboard.DashboardDtos.PrBoard(false, List.of(), List.of());
        }
        List<com.jirabot.slack.dto.dashboard.DashboardDtos.PullRequestRow> rows = new ArrayList<>();
        List<String> inaccessible = new ArrayList<>();
        for (String repo : gitHubProps.branchRepos()) {
            try {
                for (var pr : gitHubApiClient.listOpenPullRequests(repo)) {
                    rows.add(toPrRow(repo, pr));
                }
            } catch (com.jirabot.slack.client.GitHubAccessException e) {
                inaccessible.add(repo);
            }
        }
        rows.sort(Comparator.comparing(
                com.jirabot.slack.dto.dashboard.DashboardDtos.PullRequestRow::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new com.jirabot.slack.dto.dashboard.DashboardDtos.PrBoard(true, rows, inaccessible);
    }

    private com.jirabot.slack.dto.dashboard.DashboardDtos.PullRequestRow toPrRow(
            String repo, com.jirabot.slack.client.dto.PullRequestInfo pr) {
        // 이슈 키: 브랜치명(봇 규칙 feature/ES2-123-slug) 우선, 없으면 PR 제목에서.
        String issueKey = extractIssueKey(pr.headRef());
        if (issueKey == null) {
            issueKey = extractIssueKey(pr.title());
        }
        String summary = null;
        String status = null;
        String assignee = null;
        String issueUrl = null;
        if (issueKey != null) {
            Optional<IssueEntity> issue = issueRepository.findByIssueKey(issueKey);
            if (issue.isPresent()) {
                summary = issue.get().getSummary();
                status = issue.get().getStatusCategory();
                assignee = issue.get().getAssignee();
            }
            issueUrl = jiraBaseUrl.isEmpty() ? null : jiraBaseUrl + "/browse/" + issueKey;
        }
        return new com.jirabot.slack.dto.dashboard.DashboardDtos.PullRequestRow(
                repo, pr.number(), pr.title(), pr.htmlUrl(), pr.authorLogin(), pr.draft(),
                pr.headRef(), pr.createdAt(), pr.updatedAt(),
                issueKey, summary, status, assignee, issueUrl);
    }

    String extractIssueKey(String text) {
        if (text == null) return null;
        if (projectKeyPattern != null) {
            var pm = projectKeyPattern.matcher(text);
            if (pm.find()) return pm.group().toUpperCase();
        }
        var m = ISSUE_KEY_IN_TEXT.matcher(text);   // 대문자 일반 패턴 (다른 프로젝트 키 등)
        return m.find() ? m.group() : null;
    }

    // --- helpers ---

    /** ReminderService 와 동일 규칙: 진행 중 + inProgressSince 가 staleDays 이상 경과. */
    private boolean isStale(IssueEntity i) {
        if (!StatusCategory.IN_PROGRESS.equals(i.getStatusCategory()) || i.getInProgressSince() == null) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(i.getInProgressSince(), Instant.now());
        return days >= reminderProps.staleDays();
    }

    private boolean isBug(IssueEntity i) {
        String type = i.getIssueType();
        if (type == null) return false;
        String lower = type.toLowerCase();
        return lower.contains("버그") || lower.contains("bug") || type.equalsIgnoreCase(bugTypeName);
    }

    private Optional<int[]> latestSprintId() {
        List<Object[]> info = issueRepository.findLatestSprintInfo(PageRequest.of(0, 1));
        if (info.isEmpty()) return Optional.empty();
        return Optional.of(new int[]{(Integer) info.get(0)[0]});
    }

    /** KST 기준 해당 Instant 가 속한 주의 월요일. null 안전. */
    private LocalDate toWeekStart(Instant instant) {
        if (instant == null) return null;
        LocalDate d = instant.atZone(KST).toLocalDate();
        return d.minusDays(d.getDayOfWeek().getValue() - 1L);
    }

    private LocalDate currentWeekStart() {
        LocalDate today = LocalDate.now(KST);
        return today.minusDays(today.getDayOfWeek().getValue() - 1L);
    }

    private int clampWeeks(int weeks) {
        return Math.max(1, Math.min(weeks, 26));
    }

    private IssueRow toRow(IssueEntity i) {
        String url = jiraBaseUrl.isEmpty() ? null : jiraBaseUrl + "/browse/" + i.getIssueKey();
        return new IssueRow(i.getIssueKey(), i.getSummary(), i.getIssueType(), i.getStatus(),
                i.getStatusCategory(), i.getAssignee(), i.getStoryPoint(), i.getSprintName(),
                url, i.getJiraUpdated());
    }

    private double sp(IssueEntity i) {
        return i.getStoryPoint() == null ? 0 : i.getStoryPoint();
    }

    private String assigneeLabel(IssueEntity i) {
        return i.getAssignee() == null || i.getAssignee().isBlank() ? "미배정" : i.getAssignee();
    }

    private String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
