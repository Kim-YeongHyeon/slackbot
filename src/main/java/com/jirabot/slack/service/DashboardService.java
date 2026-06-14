package com.jirabot.slack.service;

import com.jirabot.slack.dto.dashboard.DashboardDtos.AssigneeLoad;
import com.jirabot.slack.dto.dashboard.DashboardDtos.BugStats;
import com.jirabot.slack.dto.dashboard.DashboardDtos.IntentFailureRow;
import com.jirabot.slack.dto.dashboard.DashboardDtos.IssueRow;
import com.jirabot.slack.dto.dashboard.DashboardDtos.SprintStats;
import com.jirabot.slack.dto.dashboard.DashboardDtos.Summary;
import com.jirabot.slack.dto.dashboard.DashboardDtos.TrendStats;
import java.util.List;

public interface DashboardService {

    Summary summary();

    SprintStats sprint();

    /** 최근 weeks 주의 생성/해결 추이 + 주별 평균 해결 소요시간. */
    TrendStats trends(int weeks);

    /** 담당자별 미해결 부하 (미배정 포함). scope="sprint" 면 현재 스프린트만, 그 외 전체. */
    List<AssigneeLoad> workload(String scope);

    /** 버그 통계. scope="sprint" 면 현재 스프린트 버그만, 그 외 로컬 전체. */
    BugStats bugs(int weeks, String scope);

    /** 해결된 버그를 Jira 라이브로 조회 (완료일 desc). q 가 있으면 요약/키 부분일치로 필터. */
    List<com.jirabot.slack.dto.dashboard.DashboardDtos.ResolvedBugRow> resolvedBugs(String q);

    /** 필터형 이슈 목록 (최근 갱신순, cap 200). 파라미터는 null/blank 면 미적용. */
    List<IssueRow> issues(String statusCategory, String assignee, String issueType, String keyword);

    List<IntentFailureRow> intentFailures(int limit);

    /** 응답 시간 보드 — 최근 7일 통계(성공 건) + 최근 50건 단계별 내역. */
    com.jirabot.slack.dto.dashboard.DashboardDtos.ResponseMetricBoard responseMetrics();

    /** 열린 PR 현황 + 연결 Jira 이슈 (5분 캐시 — CacheConfig.OPEN_PRS_CACHE). */
    com.jirabot.slack.dto.dashboard.DashboardDtos.PrBoard prs();
}
