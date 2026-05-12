package com.jirabot.slack.service;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
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

    public ScrumReportServiceImpl(IssueRepository issueRepository,
                                  UserMappingRepository userMappingRepository,
                                  SlackNotifier slackNotifier,
                                  com.jirabot.slack.config.JiraProperties jiraProps) {
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
        this.slackNotifier = slackNotifier;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> generateReport() {
        try {
            List<IssueEntity> allIssues = issueRepository.findAll();
            if (allIssues.isEmpty()) {
                return CompletableFuture.completedFuture("DB에 이슈가 없습니다. `@지라 sync`로 동기화해주세요.");
            }
            String report = formatReport(allIssues);
            log.info("Scrum report generated from DB, issues={}", allIssues.size());
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
            List<IssueEntity> allIssues = issueRepository.findAll();
            if (allIssues.isEmpty()) {
                return CompletableFuture.completedFuture("DB에 이슈가 없습니다. `@지라 sync`로 동기화해주세요.");
            }
            String report = formatStatisticsReport(allIssues);
            log.info("Statistics report generated from DB, issues={}", allIssues.size());
            return CompletableFuture.completedFuture(report);
        } catch (Exception e) {
            log.error("Statistics report generation failed: {}", e.toString());
            return CompletableFuture.completedFuture("통계 리포트 생성에 실패했습니다: " + e.getMessage());
        }
    }

    private String formatStatisticsReport(List<IssueEntity> issues) {
        // STUDY: ZoneId.of("Asia/Seoul") — KST 기준으로 "오늘" 판단. 서버 타임존과 무관하게 일관성 유지.
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDate today = LocalDate.now(kst);
        Instant todayStart = today.atStartOfDay(kst).toInstant();

        StringBuilder sb = new StringBuilder();
        sb.append(":bar_chart: *스프린트 통계 요약*\n\n");

        // SP 집계 (전체/완료/남음)
        double totalSp = issues.stream()
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0).sum();
        double completedSp = issues.stream()
                .filter(i -> "완료".equals(i.getStatusCategory()))
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0).sum();
        double remainingSp = totalSp - completedSp;

        // SP가 모두 0이면 건수 기반으로 진척률 계산
        boolean useCounts = totalSp == 0;
        long totalCount = issues.size();
        long completedCount = issues.stream().filter(i -> "완료".equals(i.getStatusCategory())).count();
        double ratio = useCounts
                ? (totalCount > 0 ? (double) completedCount / totalCount : 0)
                : (totalSp > 0 ? completedSp / totalSp : 0);
        int percent = (int) (ratio * 100);

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

        // 상태별 현황
        Map<String, List<IssueEntity>> byStatus = issues.stream()
                .collect(Collectors.groupingBy(IssueEntity::getStatusCategory));
        sb.append(":clipboard: *상태별 현황*\n");
        appendStatusCount(sb, byStatus.get("완료"), ":white_check_mark: 완료");
        appendStatusCount(sb, byStatus.get("진행 중"), ":hammer: 진행 중");
        appendStatusCount(sb, byStatus.get("해야 할 일"), ":clipboard: 해야 할 일");
        sb.append("\n");

        // 오늘 해결된 이슈
        // STUDY: completedAt이 null인 완료 이슈는 jiraUpdated를 fallback으로 사용.
        //        동기화 시점에 이미 완료였던 이슈는 completedAt이 기록되지만,
        //        히스토리컬 데이터 복원 시 null일 수 있다.
        List<IssueEntity> todayCompleted = issues.stream()
                .filter(i -> "완료".equals(i.getStatusCategory()))
                .filter(i -> {
                    Instant effectiveCompleted = i.getCompletedAt() != null
                            ? i.getCompletedAt() : i.getJiraUpdated();
                    return effectiveCompleted != null && !effectiveCompleted.isBefore(todayStart);
                })
                .toList();

        sb.append(":trophy: *오늘 해결된 이슈*\n");
        if (todayCompleted.isEmpty()) {
            sb.append("  (없음)\n");
        } else {
            double todaySp = 0;
            for (IssueEntity i : todayCompleted) {
                String sp = spText(i.getStoryPoint());
                String assignee = i.getAssignee() != null ? i.getAssignee() : "미배정";
                sb.append(String.format("  • %s %s%s, 담당: %s)\n",
                        issueLink(i.getIssueKey()), i.getSummary(),
                        sp.isEmpty() ? " (" : sp.substring(0, sp.length() - 1) + ", ",
                        assignee));
                todaySp += i.getStoryPoint() != null ? i.getStoryPoint() : 0;
            }
            if (todaySp > 0) {
                sb.append(String.format("  → 오늘 %.0f SP 완료!\n", todaySp));
            }
        }
        sb.append("\n");

        // 현재 진행 중
        List<IssueEntity> inProgress = byStatus.getOrDefault("진행 중", List.of());
        if (!inProgress.isEmpty()) {
            sb.append(":hammer: *현재 진행 중*\n");
            for (IssueEntity i : inProgress) {
                String sp = spText(i.getStoryPoint());
                String assignee = i.getAssignee() != null ? i.getAssignee() : "미배정";
                sb.append(String.format("  • %s %s%s, 담당: %s)\n",
                        issueLink(i.getIssueKey()), i.getSummary(),
                        sp.isEmpty() ? " (" : sp.substring(0, sp.length() - 1) + ", ",
                        assignee));
            }
            sb.append("\n");
        }

        // 가장 큰 이슈 (미완료 중 최대 SP)
        issues.stream()
                .filter(i -> !"완료".equals(i.getStatusCategory()))
                .filter(i -> i.getStoryPoint() != null && i.getStoryPoint() > 0)
                .max(Comparator.comparingDouble(IssueEntity::getStoryPoint))
                .ifPresent(i -> {
                    String assignee = i.getAssignee() != null ? i.getAssignee() : "미배정";
                    sb.append(String.format(":pushpin: *가장 큰 이슈 (미완료)*\n  • %s %s (SP %.0f, %s, 담당: %s)\n\n",
                            issueLink(i.getIssueKey()), i.getSummary(),
                            i.getStoryPoint(), i.getStatusCategory(), assignee));
                });

        // 번업 차트 (최근 7일)
        sb.append(":chart_with_upwards_trend: *번업 (최근 7일)*\n");
        double totalForBurnup = totalSp > 0 ? totalSp : totalCount;
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("MM/dd");
        for (int d = 6; d >= 0; d--) {
            LocalDate date = today.minusDays(d);
            Instant dayEnd = date.plusDays(1).atStartOfDay(kst).toInstant();
            // STUDY: 번업 차트는 각 날짜까지의 누적 완료 SP를 계산.
            //        completedAt이 null인 완료 이슈는 jiraUpdated를 fallback으로 사용.
            double cumulativeSp;
            if (useCounts) {
                cumulativeSp = issues.stream()
                        .filter(i -> "완료".equals(i.getStatusCategory()))
                        .filter(i -> {
                            Instant effective = i.getCompletedAt() != null
                                    ? i.getCompletedAt() : i.getJiraUpdated();
                            return effective != null && effective.isBefore(dayEnd);
                        })
                        .count();
            } else {
                cumulativeSp = issues.stream()
                        .filter(i -> "완료".equals(i.getStatusCategory()))
                        .filter(i -> {
                            Instant effective = i.getCompletedAt() != null
                                    ? i.getCompletedAt() : i.getJiraUpdated();
                            return effective != null && effective.isBefore(dayEnd);
                        })
                        .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0)
                        .sum();
            }
            double burnupRatio = totalForBurnup > 0 ? cumulativeSp / totalForBurnup : 0;
            String bar = progressBar(burnupRatio);
            String unit = useCounts ? "건" : "SP";
            sb.append(String.format("  %s %s %.0f/%.0f %s\n",
                    date.format(dateFmt), bar, cumulativeSp, totalForBurnup, unit));
        }

        return sb.toString();
    }

    // STUDY: 프로그레스 바를 20칸 고정 폭 텍스트로 렌더링. Slack에서 모노스페이스처럼 시각화.
    String progressBar(double ratio) {
        int filled = (int) (ratio * 20);
        if (filled < 0) filled = 0;
        if (filled > 20) filled = 20;
        return "█".repeat(filled) + "░".repeat(20 - filled);
    }

    private void appendStatusCount(StringBuilder sb, List<IssueEntity> issues, String label) {
        int count = issues != null ? issues.size() : 0;
        double sp = issues != null
                ? issues.stream().mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0).sum()
                : 0;
        sb.append(String.format("  %s: %d건 (%.0f SP)\n", label, count, sp));
    }

    private String formatReport(List<IssueEntity> issues) {
        StringBuilder sb = new StringBuilder();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate yesterday = today.minusDays(1);
        Instant since = yesterday.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        sb.append(":clipboard: *스프린트 리포트*\n\n");

        // 어제~오늘 수정된 이슈 (진행한 업무)
        List<IssueEntity> recentlyUpdated = issues.stream()
                .filter(i -> i.getJiraUpdated() != null && !i.getJiraUpdated().isBefore(since))
                .filter(i -> !"해야 할 일".equals(i.getStatusCategory()))
                .toList();

        // 해야 할 일 (담당자별)
        Map<String, List<IssueEntity>> todoByAssignee = issues.stream()
                .filter(i -> "해야 할 일".equals(i.getStatusCategory()))
                .collect(Collectors.groupingBy(
                        i -> i.getAssignee() != null ? i.getAssignee() : "미배정"));

        // 담당자 전체 목록
        java.util.Set<String> allAssignees = new java.util.LinkedHashSet<>();
        recentlyUpdated.forEach(i -> allAssignees.add(
                i.getAssignee() != null ? i.getAssignee() : "미배정"));
        allAssignees.addAll(todoByAssignee.keySet());

        if (allAssignees.isEmpty()) {
            sb.append("변경된 이슈가 없습니다.\n");
        } else {
            for (String assignee : allAssignees) {
                sb.append(String.format(":bust_in_silhouette: *%s*\n", assignee));

                List<IssueEntity> worked = recentlyUpdated.stream()
                        .filter(i -> assignee.equals(
                                i.getAssignee() != null ? i.getAssignee() : "미배정"))
                        .toList();
                if (!worked.isEmpty()) {
                    Map<String, List<IssueEntity>> byStatus = worked.stream()
                            .collect(Collectors.groupingBy(IssueEntity::getStatusCategory));
                    appendStatusSection(sb, byStatus.get("완료"), "완료됨 :white_check_mark:");
                    appendStatusSection(sb, byStatus.get("진행 중"), "진행 중 :hammer:");
                }

                List<IssueEntity> todo = todoByAssignee.getOrDefault(assignee, List.of());
                if (!todo.isEmpty()) {
                    sb.append("  해야 할 일 :clipboard:\n");
                    for (IssueEntity i : todo) {
                        String sp = spText(i.getStoryPoint());
                        sb.append(String.format("    • %s %s%s\n",
                                issueLink(i.getIssueKey()), i.getSummary(), sp));
                    }
                }
                sb.append("\n");
            }
        }

        // SP 집계
        double completedSp = issues.stream()
                .filter(i -> "완료".equals(i.getStatusCategory()))
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0)
                .sum();
        double totalSp = issues.stream()
                .mapToDouble(i -> i.getStoryPoint() != null ? i.getStoryPoint() : 0)
                .sum();
        sb.append(String.format("\n:bar_chart: *완료: %.0f SP / 전체: %.0f SP*", completedSp, totalSp));

        return sb.toString();
    }

    private void appendIssuesByStatus(StringBuilder sb, List<IssueEntity> issues) {
        Map<String, List<IssueEntity>> byStatus = issues.stream()
                .collect(Collectors.groupingBy(IssueEntity::getStatusCategory));
        appendStatusSection(sb, byStatus.get("진행 중"), "진행 중 :hammer:");
        appendStatusSection(sb, byStatus.get("해야 할 일"), "해야 할 일 :clipboard:");
        appendStatusSection(sb, byStatus.get("완료"), "완료됨 :white_check_mark:");

        double completedSp = issues.stream()
                .filter(i -> "완료".equals(i.getStatusCategory()))
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
    //        2순위: Slack API users.info로 실명 조회 (이름이 같을 때)
    private String resolveJiraName(String slackUserId) {
        if (slackUserId == null) return null;

        // 1. DB 매핑 확인
        var mapping = userMappingRepository.findBySlackUserId(slackUserId);
        if (mapping.isPresent()) {
            return mapping.get().getJiraDisplayName();
        }

        // 2. Slack API로 실명 조회 시도
        try {
            String slackName = slackNotifier.getUserRealName(slackUserId);
            if (slackName != null && !slackName.isBlank()) {
                // 자동으로 매핑 저장 (다음번에는 DB에서 바로 조회)
                userMappingRepository.save(new UserMappingEntity(slackUserId, slackName, slackName));
                log.info("Auto-mapped Slack user {} -> Jira '{}'", slackUserId, slackName);
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
