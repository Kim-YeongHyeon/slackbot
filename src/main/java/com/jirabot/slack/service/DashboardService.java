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

    /** 담당자별 미해결 부하 (미배정 포함). */
    List<AssigneeLoad> workload();

    BugStats bugs(int weeks);

    /** 필터형 이슈 목록 (최근 갱신순, cap 200). 파라미터는 null/blank 면 미적용. */
    List<IssueRow> issues(String statusCategory, String assignee, String issueType, String keyword);

    List<IntentFailureRow> intentFailures(int limit);

    /** 열린 PR 현황 + 연결 Jira 이슈 (5분 캐시 — CacheConfig.OPEN_PRS_CACHE). */
    com.jirabot.slack.dto.dashboard.DashboardDtos.PrBoard prs();
}
