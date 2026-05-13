package com.jirabot.slack.service;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// STUDY: Jira API 직접 호출에서 DB 조회로 전환. 응답 속도 대폭 개선 (API 수초 → DB 수ms).
//        데이터 정확성은 앱 시작 시 + 매일 8시 자동 동기화 + @지라 sync 수동 동기화로 보장.
@Service
public class ScrumReportServiceImpl implements ScrumReportService {

    private static final Logger log = LoggerFactory.getLogger(ScrumReportServiceImpl.class);

    private final IssueRepository issueRepository;
    private final UserMappingRepository userMappingRepository;
    private final SlackNotifier slackNotifier;
    private final String jiraBaseUrl;
    // STUDY: Jira UI 의 sprint SP 합계는 parent 만 카운트하고 subtask SP 는 parent 로 롤업된다.
    //        봇 응답을 UI 와 일치시키기 위해 SP 집계에서 subtask 타입을 제외한다.
    private final String subtaskTypeName;

    public ScrumReportServiceImpl(IssueRepository issueRepository,
                                  UserMappingRepository userMappingRepository,
                                  SlackNotifier slackNotifier,
                                  com.jirabot.slack.config.JiraProperties jiraProps) {
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
        this.slackNotifier = slackNotifier;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.subtaskTypeName = jiraProps.issueTypes().subtask();
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> generateReport() {
        try {
            // STUDY: scrum 리포트는 현재 스프린트 이슈만 대상. backlog 이슈는 검색용으로만 동기화되므로 제외.
            List<Object[]> sprintInfoList = issueRepository.findLatestSprintInfo(PageRequest.of(0, 1));
            if (sprintInfoList.isEmpty()) {
                return CompletableFuture.completedFuture(
                        "스프린트 정보가 없습니다. `@지라 sync`로 동기화해주세요.");
            }
            int sprintId = (Integer) sprintInfoList.get(0)[0];

            List<IssueEntity> sprintIssues = issueRepository.findBySprintId(sprintId);
            if (sprintIssues.isEmpty()) {
                return CompletableFuture.completedFuture("스프린트에 이슈가 없습니다.");
            }
            String report = formatReport(sprintIssues);
            log.info("Scrum report generated from DB, sprint={}, issues={}", sprintId, sprintIssues.size());
            return CompletableFuture.completedFuture(report);
        } catch (Exception e) {
            log.error("Scrum report generation failed: {}", e.toString());
            return CompletableFuture.completedFuture("스크럼 리포트 생성에 실패했습니다: " + e.getMessage());
        }
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> generateMyReport(String slackUserId) {
        try {
            // STUDY: 내 이슈를 찾는 2가지 경로:
            //        1. assignee가 내 Jira 이름인 이슈 (Jira에서 배정된 것)
            //        2. reporter가 내 Slack ID인 이슈 (봇으로 생성한 것)
            String jiraName = resolveJiraName(slackUserId);

            List<IssueEntity> allIssues = issueRepository.findAll();
            List<IssueEntity> myIssues = allIssues.stream()
                    .filter(i -> isMyIssue(i, slackUserId, jiraName))
                    .toList();

            if (myIssues.isEmpty()) {
                String nameInfo = jiraName != null ? " (" + jiraName + ")" : "";
                return CompletableFuture.completedFuture(
                        "배정된 작업이 없습니다." + nameInfo
                        + "\n매핑이 안 돼있다면: `scripts/register-user-mapping.sh` 실행");
            }

            StringBuilder sb = new StringBuilder();
            String displayName = jiraName != null ? jiraName : "내";
            sb.append(String.format(":bust_in_silhouette: *%s 작업*\n\n", displayName));
            appendIssuesByStatus(sb, myIssues);

            log.info("My report generated from DB for user={} issues={}", slackUserId, myIssues.size());
            return CompletableFuture.completedFuture(sb.toString());
        } catch (Exception e) {
            log.error("My report generation failed: {}", e.toString());
            return CompletableFuture.completedFuture("내 작업 조회에 실패했습니다: " + e.getMessage());
        }
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> generateMemberReport(String memberName) {
        try {
            List<IssueEntity> memberIssues = issueRepository.findByAssigneeContaining(memberName);

            if (memberIssues.isEmpty()) {
                return CompletableFuture.completedFuture(
                        String.format("*%s* 님의 배정된 작업이 없습니다.", memberName));
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(":bust_in_silhouette: *%s 님의 작업*\n\n",
                    memberIssues.get(0).getAssignee()));
            appendIssuesByStatus(sb, memberIssues);

            log.info("Member report generated from DB for name={} issues={}", memberName, memberIssues.size());
            return CompletableFuture.completedFuture(sb.toString());
        } catch (Exception e) {
            log.error("Member report generation failed: {}", e.toString());
            return CompletableFuture.completedFuture("작업 조회에 실패했습니다: " + e.getMessage());
        }
    }

    // STUDY: @Async 메서드는 CompletableFuture를 반환하여 비동기 실행 결과를 전달한다.
    //        Spring이 내부적으로 지정된 Executor 스레드에서 메서드를 실행하고, 결과를 Future에 담는다.
    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> generateStatisticsReport() {
        try {
            // STUDY: 가장 최근에 동기화된 스프린트를 찾아 해당 스프린트의 이슈만 통계에 포함.
            //        스프린트 정보가 없으면 (동기화 전) 안내 메시지 반환.
            List<Object[]> sprintInfoList = issueRepository.findLatestSprintInfo(PageRequest.of(0, 1));
            if (sprintInfoList.isEmpty()) {
                return CompletableFuture.completedFuture(
                        "스프린트 정보가 없습니다. `@지라 sync`로 동기화해주세요.");
            }

            Object[] sprintRow = sprintInfoList.get(0);
            int sprintId = (Integer) sprintRow[0];
            String sprintName = (String) sprintRow[1];

            List<Object[]> statusStats = issueRepository.countAndSumGroupByStatusAndSprint(sprintId);
            if (statusStats.isEmpty()) {
                return CompletableFuture.completedFuture(
                        String.format("스프린트 '%s'에 이슈가 없습니다.", sprintName));
            }

            String report = formatStatisticsReport(statusStats, sprintId, sprintName);
            log.info("Statistics report generated for sprint='{}' (id={})", sprintName, sprintId);
            return CompletableFuture.completedFuture(report);
        } catch (Exception e) {
            log.error("Statistics report generation failed: {}", e.toString());
            return CompletableFuture.completedFuture("통계 리포트 생성에 실패했습니다: " + e.getMessage());
        }
    }

    // STUDY: completedAt이 null인 이슈의 완료 시점을 jiraUpdated로 대체. jiraUpdated는 완료 후에도
    //        댓글/수정으로 갱신될 수 있어 정확한 완료 시점이 아닐 수 있다 (근사치).
    private Instant effectiveCompletedAt(IssueEntity issue) {
        return issue.getCompletedAt() != null ? issue.getCompletedAt() : issue.getJiraUpdated();
    }

    private String formatStatisticsReport(List<Object[]> statusStats, int sprintId, String sprintName) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDate today = LocalDate.now(kst);
        Instant todayStart = today.atStartOfDay(kst).toInstant();

        // STUDY: DB GROUP BY 결과를 Map으로 변환. Object[] = [statusCategory, count, sumSp].
        Map<String, long[]> statsMap = new HashMap<>();
        long totalCount = 0;
        double totalSp = 0;
        for (Object[] row : statusStats) {
            String status = (String) row[0];
            long count = (Long) row[1];
            double sp = ((Number) row[2]).doubleValue();
            statsMap.put(status, new long[]{count, (long) sp});
            totalCount += count;
            totalSp += sp;
        }

        long[] doneStats = statsMap.getOrDefault(StatusCategory.DONE, new long[]{0, 0});
        long completedCount = doneStats[0];
        double completedSp = doneStats[1];
        double remainingSp = totalSp - completedSp;

        boolean useCounts = totalSp == 0;
        double ratio = useCounts
                ? (totalCount > 0 ? (double) completedCount / totalCount : 0)
                : (totalSp > 0 ? completedSp / totalSp : 0);
        int percent = (int) (ratio * 100);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(":bar_chart: *스프린트 '%s' 통계 요약*\n\n", sprintName));

        // 진척률 섹션
        sb.append(":fire: *진척률*\n");
        if (useCounts) {
            sb.append(String.format("  전체: %d건 | 완료: %d건 | 남음: %d건\n",
                    totalCount, completedCount, totalCount - completedCount));
        } else {
            sb.append(String.format("  전체: %.0f SP | 완료: %.0f SP | 남음: %.0f SP\n",
                    totalSp, completedSp, remainingSp));
        }
        sb.append(String.format("  %s %d%%\n\n", progressBar(ratio), percent));

        // 상태별 현황 — statsMap에서 직접 출력
        sb.append(":clipboard: *상태별 현황*\n");
        appendStatusCountFromStats(sb, statsMap.get(StatusCategory.DONE), ":white_check_mark: 완료");
        appendStatusCountFromStats(sb, statsMap.get(StatusCategory.IN_PROGRESS), ":hammer: 진행 중");
        appendStatusCountFromStats(sb, statsMap.get(StatusCategory.TODO), ":clipboard: 해야 할 일");
        sb.append("\n");

        // 오늘 해결된 이슈 — 스프린트 내에서만 조회
        List<IssueEntity> todayCompleted = issueRepository.findCompletedSinceInSprint(
                StatusCategory.DONE, todayStart, sprintId);
        sb.append(":trophy: *오늘 해결된 이슈*\n");
        if (todayCompleted.isEmpty()) {
            sb.append("  (없음)\n");
        } else {
            double todaySp = 0;
            for (IssueEntity i : todayCompleted) {
                String assignee = i.getAssignee() != null ? i.getAssignee() : "미배정";
                // STUDY: completedAt이 null인 이슈는 jiraUpdated를 fallback으로 사용하므로
                //        실제 완료 시점이 아닐 수 있다. "(추정)" 표시로 사용자에게 알린다.
                String approx = i.getCompletedAt() == null ? " (추정)" : "";
                sb.append(String.format("  • %s %s (%s, 담당: %s)%s\n",
                        issueLink(i.getIssueKey()), i.getSummary(),
                        spLabel(i.getStoryPoint()), assignee, approx));
                todaySp += i.getStoryPoint() != null ? i.getStoryPoint() : 0;
            }
            if (todaySp > 0) {
                sb.append(String.format("  → 오늘 %.0f SP 완료!\n", todaySp));
            }
        }
        sb.append("\n");

        // 현재 진행 중 — 스프린트 내에서만 조회
        List<IssueEntity> inProgress = issueRepository.findByStatusCategoryAndSprintId(
                StatusCategory.IN_PROGRESS, sprintId);
        if (!inProgress.isEmpty()) {
            sb.append(":hammer: *현재 진행 중*\n");
            for (IssueEntity i : inProgress) {
                String assignee = i.getAssignee() != null ? i.getAssignee() : "미배정";
                sb.append(String.format("  • %s %s (%s, 담당: %s)\n",
                        issueLink(i.getIssueKey()), i.getSummary(),
                        spLabel(i.getStoryPoint()), assignee));
            }
            sb.append("\n");
        }

        // 가장 큰 이슈 (미완료 중 최대 SP) — 스프린트 내에서만 조회
        List<IssueEntity> biggest = issueRepository.findTopUncompletedBySpInSprint(
                StatusCategory.DONE, sprintId, PageRequest.of(0, 1));
        if (!biggest.isEmpty()) {
            IssueEntity i = biggest.get(0);
            String assignee = i.getAssignee() != null ? i.getAssignee() : "미배정";
            sb.append(String.format(":pushpin: *가장 큰 이슈 (미완료)*\n  • %s %s (SP %.0f, %s, 담당: %s)\n\n",
                    issueLink(i.getIssueKey()), i.getSummary(),
                    i.getStoryPoint(), i.getStatusCategory(), assignee));
        }

        // STUDY: 번업 차트 — 스프린트 내 완료 이슈만 로드하여 O(N log N) 정렬 + O(N) 순회.
        List<IssueEntity> completedIssues = issueRepository.findByStatusCategoryAndSprintId(
                StatusCategory.DONE, sprintId);
        sb.append(":chart_with_upwards_trend: *번업 (최근 7일)*\n");
        double totalForBurnup = useCounts ? totalCount : totalSp;
        appendBurnupChart(sb, completedIssues, today, kst, totalForBurnup, useCounts);

        return sb.toString();
    }

    // STUDY: O(N log N) 정렬 + O(N) 순회로 7일 번업 차트를 생성.
    //        기존: 7일 × 전체 이슈 스트림 필터링 = O(7*N). 개선: 정렬 1회 + 포인터 순회.
    private void appendBurnupChart(StringBuilder sb, List<IssueEntity> completedIssues,
                                    LocalDate today, ZoneId kst,
                                    double totalForBurnup, boolean useCounts) {
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("MM/dd");

        // effectiveCompletedAt 기준으로 정렬
        List<IssueEntity> sorted = new ArrayList<>(completedIssues);
        sorted.sort(Comparator.comparing(
                i -> effectiveCompletedAt(i) != null ? effectiveCompletedAt(i) : Instant.MIN));

        // 7일 전 자정까지의 누적값을 미리 계산 (base)
        Instant sevenDaysAgoEnd = today.minusDays(6).atStartOfDay(kst).toInstant();
        double baseCumulative = 0;
        int baseIndex = 0;
        for (int idx = 0; idx < sorted.size(); idx++) {
            Instant effective = effectiveCompletedAt(sorted.get(idx));
            if (effective != null && effective.isBefore(sevenDaysAgoEnd)) {
                if (useCounts) {
                    baseCumulative++;
                } else {
                    baseCumulative += sorted.get(idx).getStoryPoint() != null
                            ? sorted.get(idx).getStoryPoint() : 0;
                }
                baseIndex = idx + 1;
            }
        }

        // 각 날짜의 경계까지 포인터를 전진하며 누적
        double cumulative = baseCumulative;
        int pointer = baseIndex;
        for (int d = 6; d >= 0; d--) {
            LocalDate date = today.minusDays(d);
            Instant dayEnd = date.plusDays(1).atStartOfDay(kst).toInstant();

            while (pointer < sorted.size()) {
                Instant effective = effectiveCompletedAt(sorted.get(pointer));
                if (effective != null && effective.isBefore(dayEnd)) {
                    if (useCounts) {
                        cumulative++;
                    } else {
                        cumulative += sorted.get(pointer).getStoryPoint() != null
                                ? sorted.get(pointer).getStoryPoint() : 0;
                    }
                    pointer++;
                } else {
                    break;
                }
            }

            double burnupRatio = totalForBurnup > 0 ? cumulative / totalForBurnup : 0;
            String bar = progressBar(burnupRatio);
            String unit = useCounts ? "건" : "SP";
            sb.append(String.format("  %s %s %.0f/%.0f %s\n",
                    date.format(dateFmt), bar, cumulative, totalForBurnup, unit));
        }
    }

    // STUDY: 프로그레스 바를 20칸 고정 폭 텍스트로 렌더링. Slack에서 모노스페이스처럼 시각화.
    String progressBar(double ratio) {
        int filled = (int) (ratio * 20);
        if (filled < 0) filled = 0;
        if (filled > 20) filled = 20;
        return "█".repeat(filled) + "░".repeat(20 - filled);
    }

    // STUDY: SP 값을 사람이 읽기 쉬운 형태로 변환. null이나 0이면 "-" 표시.
    //        기존 spText()와 달리 괄호 없이 값만 반환하여 호출측에서 포맷을 조합한다.
    private String spLabel(Double sp) {
        return sp != null && sp > 0 ? String.format("SP %.0f", sp) : "SP -";
    }

    // STUDY: parent-subtask 구조를 시각적으로 보여준다. 같은 섹션 안에 부모가 있으면 그 아래에
    //        subtask 를 `-` 들여쓰기로 묶고, 부모가 섹션 밖이거나 없으면 subtask 도 최상위로 출력.
    private void appendHierarchical(StringBuilder sb, List<IssueEntity> items, String indent) {
        Map<String, IssueEntity> byKey = items.stream()
                .collect(Collectors.toMap(IssueEntity::getIssueKey, i -> i, (a, b) -> a));
        for (IssueEntity i : items) {
            String parentKey = i.getParentKey();
            // 부모가 같은 섹션에 존재하는 subtask 는 부모 라인 아래에서 출력되므로 여기선 건너뜀.
            if (parentKey != null && byKey.containsKey(parentKey)) {
                continue;
            }
            sb.append(String.format("%s• %s %s%s\n",
                    indent, issueLink(i.getIssueKey()), i.getSummary(), spText(i.getStoryPoint())));
            for (IssueEntity child : items) {
                if (i.getIssueKey().equals(child.getParentKey())) {
                    sb.append(String.format("%s  - %s %s%s\n",
                            indent, issueLink(child.getIssueKey()), child.getSummary(),
                            spText(child.getStoryPoint())));
                }
            }
        }
    }

    private void appendStatusCountFromStats(StringBuilder sb, long[] stats, String label) {
        long count = stats != null ? stats[0] : 0;
        long sp = stats != null ? stats[1] : 0;
        sb.append(String.format("  %s: %d건 (%d SP)\n", label, count, sp));
    }

    private String formatReport(List<IssueEntity> issues) {
        StringBuilder sb = new StringBuilder();
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDate today = LocalDate.now(kst);
        Instant yesterdayStart = today.minusDays(1).atStartOfDay(kst).toInstant();

        sb.append(":clipboard: *스프린트 리포트*\n\n");

        // STUDY: 담당자별로 그룹핑. 진행 중/해야 할 일 모든 이슈를 보여준다.
        //        "어제 수정된 것만" 필터링하면 오래 진행 중인 이슈가 누락되므로 전체 표시.
        Map<String, List<IssueEntity>> inProgressByAssignee = issues.stream()
                .filter(i -> StatusCategory.IN_PROGRESS.equals(i.getStatusCategory()))
                .collect(Collectors.groupingBy(
                        i -> i.getAssignee() != null ? i.getAssignee() : "미배정"));

        Map<String, List<IssueEntity>> todoByAssignee = issues.stream()
                .filter(i -> StatusCategory.TODO.equals(i.getStatusCategory()))
                .collect(Collectors.groupingBy(
                        i -> i.getAssignee() != null ? i.getAssignee() : "미배정"));

        // 담당자 전체 목록. "미배정"은 항상 마지막.
        java.util.Set<String> allAssignees = new java.util.LinkedHashSet<>();
        allAssignees.addAll(inProgressByAssignee.keySet());
        allAssignees.addAll(todoByAssignee.keySet());
        if (allAssignees.remove("미배정")) {
            allAssignees.add("미배정");
        }

        if (allAssignees.isEmpty()) {
            sb.append("진행 중이거나 해야 할 이슈가 없습니다.\n");
        } else {
            for (String assignee : allAssignees) {
                sb.append(String.format(":bust_in_silhouette: *%s*\n", assignee));

                // 진행 중 먼저
                List<IssueEntity> inProgress = inProgressByAssignee.getOrDefault(assignee, List.of());
                if (!inProgress.isEmpty()) {
                    sb.append("  진행 중 :hammer:\n");
                    appendHierarchical(sb, inProgress, "    ");
                }

                // 해야 할 일
                List<IssueEntity> todo = todoByAssignee.getOrDefault(assignee, List.of());
                if (!todo.isEmpty()) {
                    sb.append("  해야 할 일 :clipboard:\n");
                    appendHierarchical(sb, todo, "    ");
                }
                sb.append("\n");
            }
        }

        // 어제 완료된 이슈
        List<IssueEntity> yesterdayDone = issues.stream()
                .filter(i -> StatusCategory.DONE.equals(i.getStatusCategory()))
                .filter(i -> {
                    Instant completed = effectiveCompletedAt(i);
                    return completed != null && !completed.isBefore(yesterdayStart);
                })
                .toList();

        if (!yesterdayDone.isEmpty()) {
            double yesterdaySp = 0;
            sb.append(":trophy: *어제 해결된 이슈*\n");
            for (IssueEntity i : yesterdayDone) {
                String assignee = i.getAssignee() != null ? i.getAssignee() : "미배정";
                sb.append(String.format("  • %s %s%s (담당: %s)\n",
                        issueLink(i.getIssueKey()), i.getSummary(),
                        spText(i.getStoryPoint()), assignee));
                yesterdaySp += i.getStoryPoint() != null ? i.getStoryPoint() : 0;
            }
            if (yesterdaySp > 0) {
                sb.append(String.format("  → 어제 %.0f SP 완료!\n", yesterdaySp));
            }
            sb.append("\n");
        }

        // STUDY: SP 집계 — subtask 는 제외해 Jira UI 의 sprint 합계와 동일하게 맞춘다.
        //        Jira UI 는 parent SP 만 카운트하고 subtask SP 는 parent 로 롤업되므로 별도로 더하면 중복.
        double completedSp = issues.stream()
                .filter(i -> StatusCategory.DONE.equals(i.getStatusCategory()))
                .filter(i -> !subtaskTypeName.equals(i.getIssueType()))
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0)
                .sum();
        double totalSp = issues.stream()
                .filter(i -> !subtaskTypeName.equals(i.getIssueType()))
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0)
                .sum();
        sb.append(String.format(":bar_chart: *완료: %.0f SP / 전체: %.0f SP*", completedSp, totalSp));

        return sb.toString();
    }

    private void appendIssuesByStatus(StringBuilder sb, List<IssueEntity> issues) {
        Map<String, List<IssueEntity>> byStatus = issues.stream()
                .collect(Collectors.groupingBy(IssueEntity::getStatusCategory));
        appendStatusSection(sb, byStatus.get(StatusCategory.IN_PROGRESS), "진행 중 :hammer:");
        appendStatusSection(sb, byStatus.get(StatusCategory.TODO), "해야 할 일 :clipboard:");
        appendStatusSection(sb, byStatus.get(StatusCategory.DONE), "완료됨 :white_check_mark:");

        double completedSp = issues.stream()
                .filter(i -> StatusCategory.DONE.equals(i.getStatusCategory()))
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0).sum();
        double totalSp = issues.stream()
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0).sum();
        sb.append(String.format("\n:bar_chart: *완료: %.0f SP / 전체: %.0f SP*", completedSp, totalSp));
    }

    private void appendStatusSection(StringBuilder sb, List<IssueEntity> issues, String label) {
        if (issues == null || issues.isEmpty()) return;
        sb.append(String.format("  %s\n", label));
        for (IssueEntity i : issues) {
            String sp = spText(i.getStoryPoint());
            sb.append(String.format("    • %s %s%s\n", issueLink(i.getIssueKey()), i.getSummary(), sp));
        }
    }

    // STUDY: Slack 유저 ID → Jira displayName 변환.
    //        1순위: DB user_mappings 테이블 (수동 등록)
    //        2순위: Slack API users.info로 실명 조회 (표시용, 읽기 전용)
    private String resolveJiraName(String slackUserId) {
        if (slackUserId == null) return null;

        // 1. DB 매핑 확인
        var mapping = userMappingRepository.findBySlackUserId(slackUserId);
        if (mapping.isPresent()) {
            return mapping.get().getJiraDisplayName();
        }

        // 2. Slack API로 실명 조회 시도 (표시용으로만 사용)
        // STUDY: 여기서 자동 저장하지 않는 이유 — Slack 실명과 Jira displayName이 다를 수 있으므로
        //        자동 저장하면 잘못된 Jira 매핑이 생성되어 등록 가드를 우회하게 된다.
        //        매핑 등록은 반드시 `@지라 등록` 명령을 통해 명시적으로 수행해야 한다.
        try {
            String slackName = slackNotifier.getUserRealName(slackUserId);
            if (slackName != null && !slackName.isBlank()) {
                return slackName;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Slack user {}: {}", slackUserId, e.toString());
        }

        return null;
    }

    private boolean isMyIssue(IssueEntity issue, String slackUserId, String jiraName) {
        // reporter가 Slack ID와 일치 (봇으로 생성한 이슈)
        if (slackUserId != null && slackUserId.equals(issue.getReporter())) {
            return true;
        }
        // assignee가 Jira displayName과 일치 (Jira에서 배정된 이슈)
        if (jiraName != null && issue.getAssignee() != null
                && issue.getAssignee().contains(jiraName)) {
            return true;
        }
        return false;
    }

    private String spText(Double sp) {
        return sp != null && sp > 0 ? String.format(" (SP %.0f)", sp) : "";
    }

    private String issueLink(String key) {
        if (jiraBaseUrl.isEmpty()) return key;
        return String.format("<%s/browse/%s|%s>", jiraBaseUrl, key, key);
    }
}
