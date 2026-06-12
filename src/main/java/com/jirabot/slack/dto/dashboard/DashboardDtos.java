package com.jirabot.slack.dto.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// STUDY: 대시보드 응답 DTO 모음 — 화면 단위가 아니라 "API 응답 형태" 단위의 record.
//        Jackson 이 record 컴포넌트를 그대로 JSON 직렬화하므로 별도 getter/어노테이션 불필요.
//        한 파일에 모은 이유: 전부 수동 매핑되는 얇은 값 객체라 파일 분산보다 응집이 읽기 쉽다.
public final class DashboardDtos {

    private DashboardDtos() {}

    /** 개요 KPI — GET /api/dashboard/summary */
    public record Summary(
            long totalIssues,
            long openIssues,        // 완료 제외
            long inProgress,
            String sprintName,      // 활성(최근 sync) 스프린트, 없으면 null
            double sprintSpDone,
            double sprintSpTotal,
            int sprintCompletionRate,   // SP 기준 % (total 0 이면 0)
            long staleCount,        // 진행 중 N일+ 정체
            long mappedUsers,
            Instant lastSyncAt      // 마지막 Jira sync (재기동 후 sync 전이면 null)
    ) {}

    /** 상태별 분포 한 조각 (도넛/집계 공용) */
    public record StatusSlice(String status, long count, double sp) {}

    /** 담당자별 부하 한 줄 */
    public record AssigneeLoad(String assignee, long openCount, double openSp, long staleCount) {}

    /** 이슈 테이블 한 줄 */
    public record IssueRow(String key, String summary, String issueType, String status,
                           String statusCategory, String assignee, Double storyPoint,
                           String sprintName, String url, Instant jiraUpdated) {}

    /** 스프린트 통계 — GET /api/dashboard/sprint */
    public record SprintStats(String sprintName, List<StatusSlice> statusSlices,
                              List<AssigneeLoad> assigneeLoads, List<IssueRow> staleIssues) {}

    /** 주간 버킷 (생성/해결 추이) — weekStart 는 KST 월요일 */
    public record WeekBucket(LocalDate weekStart, long created, long resolved) {}

    /** 주별 평균 해결 소요시간 */
    public record WeekResolution(LocalDate weekStart, double avgHours, long resolvedCount) {}

    /** 추이 통계 — GET /api/dashboard/trends */
    public record TrendStats(List<WeekBucket> weekly, List<WeekResolution> resolution) {}

    /** 버그 통계 — GET /api/dashboard/bugs */
    public record BugStats(long bugCount, long totalCount, long openBugCount,
                           List<WeekBucket> weekly, List<IssueRow> openBugs) {}

    /** 봇 운영 — 의도분류 실패 한 줄 */
    public record IntentFailureRow(Instant failedAt, String errorType, String rawInput,
                                   String slackUserId) {}
}
