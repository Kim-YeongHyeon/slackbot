package com.jirabot.slack.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.cialert.CiAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class CiAlertAdminControllerTest {

    private CiAlertService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(CiAlertService.class);
        mockMvc = standaloneSetup(new CiAlertAdminController(service)).build();
    }

    private org.springframework.test.web.servlet.ResultActions replay(String json) throws Exception {
        return mockMvc.perform(post("/api/admin/ci-alerts/replay")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void replay_dryRun_isQueued() throws Exception {
        when(service.replay("1793345678.123456", "U1", false, false)).thenReturn(42L);

        replay("{\"ts\":\"1793345678.123456\",\"dryRunUser\":\"U1\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.alertId").value(42));
    }

    @Test
    void replay_badTs_is400WithoutCallingService() throws Exception {
        replay("{\"ts\":\"abc\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        replay("{}").andExpect(status().isBadRequest());
        verify(service, never()).replay(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(service, never()).replayRun(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void replay_byRunUrl_usesRunReplay() throws Exception {
        String url = "https://github.com/CryptoLabInc/HE-KMS/actions/runs/41989433173";
        when(service.replayRun(url, "U1", false, false)).thenReturn(7L);
        when(service.replayRun(url, null, false, false)).thenThrow(new IllegalArgumentException("dryRunUser 가 필요해요"));

        replay("{\"runUrl\":\"" + url + "\",\"dryRunUser\":\"U1\"}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.alertId").value(7));
        replay("{\"runUrl\":\"" + url + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("dryRunUser 가 필요해요"));
        verify(service, never()).replay(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void replay_fixFlag_isPassedThrough() throws Exception {
        String url = "https://github.com/CryptoLabInc/envector/actions/runs/42164925557";
        when(service.replayRun(url, "U1", true, false)).thenReturn(8L);

        replay("{\"runUrl\":\"" + url + "\",\"dryRunUser\":\"U1\",\"fix\":true}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.alertId").value(8));
    }

    @Test
    void replay_notAnAlert_is400_disabled_is409() throws Exception {
        when(service.replay("1.1", null, false, false)).thenThrow(new IllegalArgumentException("알림 앱이 올린 메시지가 아니에요"));
        when(service.replay("2.2", null, false, false)).thenThrow(new IllegalStateException("꺼져 있어요"));

        replay("{\"ts\":\"1.1\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("알림 앱이 올린 메시지가 아니에요"));
        replay("{\"ts\":\"2.2\"}").andExpect(status().isConflict());
    }

    @Test
    void replay_atRunCommit_isPassedThrough_andServiceRejectsItWithoutDryRun() throws Exception {
        when(service.replay("3.3", "U1", true, true)).thenReturn(9L);
        when(service.replay("4.4", null, true, true))
                .thenThrow(new IllegalArgumentException("atRunCommit 은 dry-run(dryRunUser)에서만 쓸 수 있어요"));

        replay("{\"ts\":\"3.3\",\"dryRunUser\":\"U1\",\"fix\":true,\"atRunCommit\":true}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.alertId").value(9));
        replay("{\"ts\":\"4.4\",\"fix\":true,\"atRunCommit\":true}").andExpect(status().isBadRequest());
    }
}
