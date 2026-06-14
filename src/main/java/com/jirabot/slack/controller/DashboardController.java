package com.jirabot.slack.controller;

import com.jirabot.slack.dto.dashboard.DashboardDtos.AssigneeLoad;
import com.jirabot.slack.dto.dashboard.DashboardDtos.BugStats;
import com.jirabot.slack.dto.dashboard.DashboardDtos.IntentFailureRow;
import com.jirabot.slack.dto.dashboard.DashboardDtos.IssueRow;
import com.jirabot.slack.dto.dashboard.DashboardDtos.SprintStats;
import com.jirabot.slack.dto.dashboard.DashboardDtos.Summary;
import com.jirabot.slack.dto.dashboard.DashboardDtos.TrendStats;
import com.jirabot.slack.service.DashboardService;
import com.jirabot.slack.service.JiraSyncService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// STUDY: 사내망 전용 웹 대시보드 API. ngrok 터널은 Go봇(:3000)만 노출하므로 이 경로는
//        외부 인터넷에서 도달 불가 — 사내망 http://<host>:8080/dashboard/ 에서만 사용.
//        (판매/외부 노출 시 이 지점에 인증 레이어를 추가할 것.)
@RestController
@RequestMapping(path = "/api/dashboard", produces = MediaType.APPLICATION_JSON_VALUE)
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final DashboardService dashboardService;
    private final JiraSyncService jiraSyncService;

    public DashboardController(DashboardService dashboardService, JiraSyncService jiraSyncService) {
        this.dashboardService = dashboardService;
        this.jiraSyncService = jiraSyncService;
    }

    @GetMapping("/summary")
    public Summary summary() {
        return dashboardService.summary();
    }

    @GetMapping("/sprint")
    public SprintStats sprint() {
        return dashboardService.sprint();
    }

    @GetMapping("/trends")
    public TrendStats trends(@RequestParam(name = "weeks", defaultValue = "8") int weeks) {
        return dashboardService.trends(weeks);
    }

    @GetMapping("/workload")
    public List<AssigneeLoad> workload(
            @RequestParam(name = "scope", defaultValue = "all") String scope) {
        return dashboardService.workload(scope);
    }

    @GetMapping("/bugs")
    public BugStats bugs(@RequestParam(name = "weeks", defaultValue = "8") int weeks,
                         @RequestParam(name = "scope", defaultValue = "all") String scope) {
        return dashboardService.bugs(weeks, scope);
    }

    // 해결된 버그 — Jira 라이브 조회(느림)라 대시보드에서 펼칠 때만 호출. q 는 완료 버그 내 검색어.
    @GetMapping("/bugs/resolved")
    public List<com.jirabot.slack.dto.dashboard.DashboardDtos.ResolvedBugRow> resolvedBugs(
            @RequestParam(name = "q", required = false) String q) {
        return dashboardService.resolvedBugs(q);
    }

    @GetMapping("/issues")
    public List<IssueRow> issues(@RequestParam(name = "status", required = false) String status,
                                 @RequestParam(name = "assignee", required = false) String assignee,
                                 @RequestParam(name = "type", required = false) String type,
                                 @RequestParam(name = "q", required = false) String q) {
        return dashboardService.issues(status, assignee, type, q);
    }

    @GetMapping("/prs")
    public com.jirabot.slack.dto.dashboard.DashboardDtos.PrBoard prs() {
        return dashboardService.prs();
    }

    @GetMapping("/intent-failures")
    public List<IntentFailureRow> intentFailures(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return dashboardService.intentFailures(limit);
    }

    @GetMapping("/response-metrics")
    public com.jirabot.slack.dto.dashboard.DashboardDtos.ResponseMetricBoard responseMetrics() {
        return dashboardService.responseMetrics();
    }

    // STUDY: 수동 동기화 — fullSync 는 2~5초 동기 실행. 대시보드 버튼 1회성 호출 용도라
    //        비동기로 빼지 않는다 (브라우저가 결과 메시지를 바로 보여줄 수 있음).
    @PostMapping("/actions/sync")
    public Map<String, String> syncNow() {
        log.info("Dashboard manual sync requested");
        return Map.of("result", jiraSyncService.fullSync());
    }

    // STUDY: 히스토리 백필 — Jira 전체 이슈를 1회 적재(추이/통계 과거 기록용). 느린 단발 호출이라 동기 실행.
    @PostMapping("/actions/backfill-history")
    public Map<String, String> backfillHistory() {
        log.info("Dashboard history backfill requested");
        return Map.of("result", jiraSyncService.backfillHistory());
    }
}
