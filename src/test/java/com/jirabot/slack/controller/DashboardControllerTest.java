package com.jirabot.slack.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.dto.dashboard.DashboardDtos.Summary;
import com.jirabot.slack.dto.dashboard.DashboardDtos.TrendStats;
import com.jirabot.slack.service.DashboardService;
import com.jirabot.slack.service.JiraSyncService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class DashboardControllerTest {

    private DashboardService dashboardService;
    private JiraSyncService jiraSyncService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dashboardService = mock(DashboardService.class);
        jiraSyncService = mock(JiraSyncService.class);
        mockMvc = standaloneSetup(new DashboardController(dashboardService, jiraSyncService)).build();
    }

    @Test
    void summary_returnsKpiJson() throws Exception {
        when(dashboardService.summary()).thenReturn(new Summary(
                10, 4, 2, "S3", 5.0, 10.0, 50, 1, 5, Instant.parse("2026-06-12T00:00:00Z")));

        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIssues").value(10))
                .andExpect(jsonPath("$.sprintCompletionRate").value(50))
                .andExpect(jsonPath("$.sprintName").value("S3"));
    }

    @Test
    void trends_passesWeeksParam_defaultsTo8() throws Exception {
        when(dashboardService.trends(anyInt())).thenReturn(new TrendStats(List.of(), List.of()));

        mockMvc.perform(get("/api/dashboard/trends")).andExpect(status().isOk());
        verify(dashboardService).trends(8);

        mockMvc.perform(get("/api/dashboard/trends?weeks=12")).andExpect(status().isOk());
        verify(dashboardService).trends(12);
    }

    @Test
    void issues_passesAllFilters() throws Exception {
        when(dashboardService.issues("진행 중", "Alice", "버그", "로그인")).thenReturn(List.of());

        mockMvc.perform(get("/api/dashboard/issues")
                        .param("status", "진행 중").param("assignee", "Alice")
                        .param("type", "버그").param("q", "로그인"))
                .andExpect(status().isOk());

        verify(dashboardService).issues("진행 중", "Alice", "버그", "로그인");
    }

    @Test
    void syncAction_invokesFullSyncAndReturnsResult() throws Exception {
        when(jiraSyncService.fullSync()).thenReturn("동기화 완료: 3건");

        mockMvc.perform(post("/api/dashboard/actions/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("동기화 완료: 3건"));
    }

    @Test
    void unknownSubPath_is404() throws Exception {
        mockMvc.perform(get("/api/dashboard/nope")).andExpect(status().isNotFound());
    }
}
